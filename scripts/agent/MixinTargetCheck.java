import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * mixin 注入点的静态核对：把注解里的字符串拿到真实类上解析一遍。
 *
 * <p>为什么需要它：mixin 的 {@code @Inject(method = "...")}、{@code @At(target = "...")}、
 * {@code @Accessor("...")} 全都是<b>字符串</b>。编译器一个字都不检查，注解处理器也不检查
 * （它只校验 {@code @Shadow} 这类有 Java 声明的成员）。字符串写错的表现是：编译通过、jar 打得出来、
 * 启动时要么 {@code @Mixin target not found}，要么更糟——注入点匹配不到，mixin 静默跳过，游戏照跑，
 * 只是那个功能没了。</p>
 *
 * <p>这正是改名（Yarn → Mojmap）最危险的地方：{@code World} 改成 {@code Level} 之后，所有
 * {@code Lnet/minecraft/world/World;} 的描述符都不再指向任何东西，而编译器不会说一个字。</p>
 *
 * <p>做法是把每一处字符串拆成「类 + 成员」，用反射到类路径上的真实 jar 里找：
 * <ul>
 *   <li>{@code @Mixin(X.class)} → X 存在吗</li>
 *   <li>{@code method = "name"} → 目标类（含父类）有叫这个名字的方法吗</li>
 *   <li>{@code method = "name(desc)ret"} → 签名也对得上吗</li>
 *   <li>{@code @At(target = "Lpkg/Cls;m(desc)ret")} → 那个类和那个方法存在吗</li>
 *   <li>{@code @Accessor("x")} / {@code @Invoker("y")} → 字段 / 方法存在吗</li>
 * </ul>
 *
 * <p>不启动游戏，也不初始化任何类（{@code Class.forName(..., false, ...)}），所以可以挂 CI，
 * 也可以在没有客户端的机器上跑。**它检查的是"引用能不能解析"，不是"注入点语义对不对"**——后者只有
 * 真开一局才知道。</p>
 */
public final class MixinTargetCheck {

    /** 带 {@code method = } 的注解。 */
    private static final Set<String> METHOD_ANNOTATIONS = Set.of(
            "Inject", "Redirect", "ModifyVariable", "ModifyArg", "ModifyArgs", "ModifyConstant",
            "ModifyExpressionValue", "WrapOperation", "WrapWithCondition", "Overwrite");

    private static int failures;
    private static int mixinCount;
    private static int referenceCount;

    public static void main(String[] args) throws IOException {
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--src".equals(args[i])) {
                roots.add(Path.of(args[++i]));
            }
        }
        if (roots.isEmpty()) {
            roots.add(Path.of("src/main/java"));
            roots.add(Path.of("src/client/java"));
        }

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                fail("源码目录不存在：" + root.toAbsolutePath());
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            fail("没有找到任何 .java（源码目录：" + roots + "）");
        }

        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!source.contains("@Mixin")) {
                continue;
            }
            mixinCount++;
            checkFile(file, source);
        }

        System.out.println("    核对 " + mixinCount + " 个 mixin 类、" + referenceCount + " 处字符串引用");
        System.out.println(failures == 0
                ? "[mixin-target] 全部通过"
                : "[mixin-target] " + failures + " 处无法解析");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 单个文件

    private static void checkFile(Path file, String raw) {
        String where = file.getFileName().toString();
        String source = stripComments(raw);
        Map<String, String> imports = imports(source);

        List<Annotation> mixins = annotations(source, "Mixin");
        if (mixins.isEmpty()) {
            fail(where + "：文件里有 @Mixin 字样但解析不出来");
            return;
        }
        for (Annotation mixin : mixins) {
            String target = mixinTarget(mixin.args, imports);
            if (target == null) {
                fail(where + "：@Mixin 的目标解析不出来（" + squash(mixin.args) + "）");
                continue;
            }
            referenceCount++;
            Class<?> targetClass = load(target);
            if (targetClass == null) {
                fail(where + "：@Mixin 的目标类加载不出来 " + target + "（类名写错，或类路径不全）");
                continue;
            }
            checkMethodTargets(where, source, targetClass);
            checkAtTargets(where, source);
            checkMemberAccessors(where, source, targetClass);
        }
    }

    /** 逐个 {@code method = "..."} 到目标类上找。 */
    private static void checkMethodTargets(String where, String source, Class<?> targetClass) {
        for (Annotation annotation : allAnnotations(source)) {
            if (!METHOD_ANNOTATIONS.contains(annotation.name)) {
                continue;
            }
            for (String spec : stringList(assignment(annotation.args, "method"))) {
                referenceCount++;
                String problem = problem(targetClass, spec);
                if (problem != null) {
                    fail(where + "：@" + annotation.name + " 的 method = \"" + spec + "\" 在 "
                            + targetClass.getName() + " 上" + problem);
                }
            }
        }
    }

    /** {@code @At(target = "Lpkg/Cls;m(desc)ret")} 里的类和成员。 */
    private static void checkAtTargets(String where, String source) {
        for (Annotation at : annotations(source, "At")) {
            // 用 join 而不是 firstString：长描述符几乎总是被 `+` 拼成两个字面量
            // （`"Lnet/...GameMode;" + "hasLimitedAttackSpeed()Z"`），只取第一个字面量得到的是
            // "类名 + 空成员"，于是这里静默 continue，检查报"全部通过"，而注入点在游戏里 0 匹配。
            String target = join(assignment(at.args, "target"));
            if (target == null || target.isEmpty()) {
                continue;
            }
            referenceCount++;
            int semicolon = target.indexOf(';');
            if (!target.startsWith("L") || semicolon < 0) {
                fail(where + "：@At 的 target 不是 \"Lpkg/Cls;...\" 形式：" + target);
                continue;
            }
            String className = target.substring(1, semicolon).replace('/', '.');
            Class<?> owner = load(className);
            if (owner == null) {
                fail(where + "：@At target 里的类不存在 " + className + "（" + target + "）");
                continue;
            }
            String member = target.substring(semicolon + 1);
            if (member.isEmpty()) {
                continue;
            }
            if (member.indexOf(':') >= 0) {
                // 字段：Lpkg/Cls;name:Ltype;
                String fieldName = member.substring(0, member.indexOf(':'));
                if (findField(owner, fieldName) == null) {
                    fail(where + "：@At target 里的字段 " + fieldName + " 在 " + className + " 上找不到");
                }
            } else {
                String problem = problem(owner, member);
                if (problem != null) {
                    fail(where + "：@At target 里的 " + member + " 在 " + className + " 上" + problem);
                }
            }
        }
    }

    /** {@code @Accessor("x")} / {@code @Invoker("y")}：直接写在注解值里的成员名。 */
    private static void checkMemberAccessors(String where, String source, Class<?> targetClass) {
        for (Annotation accessor : annotations(source, "Accessor")) {
            String name = firstString(accessor.args);
            if (name == null) {
                continue;
            }
            referenceCount++;
            if (findField(targetClass, name) == null) {
                fail(where + "：@Accessor(\"" + name + "\") 在 " + targetClass.getName() + " 上没有这个字段");
            }
        }
        for (Annotation invoker : annotations(source, "Invoker")) {
            String name = firstString(invoker.args);
            if (name == null) {
                continue;
            }
            referenceCount++;
            String problem = problem(targetClass, name);
            if (problem != null) {
                fail(where + "：@Invoker(\"" + name + "\") 在 " + targetClass.getName() + " 上" + problem);
            }
        }
    }

    // ------------------------------------------------------------------ 反射解析

    /**
     * 一个方法描述符（{@code name} 或 {@code name(desc)ret}）在类或其父类链上是否说得通。
     *
     * <p>返回 {@code null} 表示通过，否则是"为什么不行"。只给名字时只要有同名方法就算过——注入点用
     * 不用得上由 mixin 自己判断，这里只回答"这个名字还在不在"。给了完整描述符时连参数类型一起比，
     * 因为改名最容易漏的正是描述符里的类名：{@code (Lnet/minecraft/util/math/BlockPos;...)} 里的
     * BlockPos 挪了包，方法名却一个字没变。</p>
     *
     * <p><b>描述符里的类名解析不出来必须算失败，不能降级成"只看名字"</b>——降级等于把这一整类错误
     * 静默放过去，而那正是本检查存在的理由。只有描述符本身被截断（没写完的括号）才退到只看名字，
     * 那种写法在源码里确实存在，语义上也没法再查。</p>
     */
    private static String problem(Class<?> owner, String spec) {
        int paren = spec.indexOf('(');
        String name = paren < 0 ? spec : spec.substring(0, paren);
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf(';') >= 0) {
            return "不是合法的方法名";
        }
        if (paren < 0 || spec.indexOf(')') < 0) {
            return findMethodByName(owner, name) != null ? null : "没有这个方法";
        }
        Class<?>[] params;
        try {
            params = parseParameters(spec.substring(paren));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (findMethod(owner, name, params) == null) {
            return "没有这个签名（参数 " + Arrays.toString(params) + "）";
        }
        return null;
    }

    private static Method findMethodByName(Class<?> owner, String name) {
        for (Class<?> type : hierarchy(owner)) {
            Method method = anyDeclared(type, name);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>[] params) {
        for (Class<?> type : hierarchy(owner)) {
            for (Method method : declaredMethods(type)) {
                if (method.getName().equals(name) && Arrays.equals(method.getParameterTypes(), params)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> type : hierarchy(owner)) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往上找
            } catch (Throwable ignored) {
                // 成员类型缺失时反射会抛 NoClassDefFoundError；当作"这个类查不了"，交给父类继续
            }
        }
        return null;
    }

    private static Method anyDeclared(Class<?> type, String name) {
        for (Method method : declaredMethods(type)) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable t) {
            // 参数或返回值类型不在类路径上时整个类的声明都读不出来，跳过它而不是崩掉。
            return new Method[0];
        }
    }

    /** 类自身 + 父类链 + 所有接口（mixin 也能注入接口的默认方法）。 */
    private static List<Class<?>> hierarchy(Class<?> owner) {
        List<Class<?>> out = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            if (seen.add(type)) {
                out.add(type);
            }
        }
        for (int i = 0; i < out.size(); i++) {
            collectInterfaces(out.get(i), out, seen);
        }
        return out;
    }

    private static void collectInterfaces(Class<?> type, List<Class<?>> out, Set<Class<?>> seen) {
        for (Class<?> iface : type.getInterfaces()) {
            if (seen.add(iface)) {
                out.add(iface);
                collectInterfaces(iface, out, seen);
            }
        }
    }

    /** {@code (ZLjava/lang/String;I)V} → 参数类型数组；读不出来抛 {@link IllegalArgumentException}。 */
    private static Class<?>[] parseParameters(String descriptor) {
        int close = descriptor.indexOf(')');
        if (close < 0) {
            throw new IllegalArgumentException("描述符没有右括号：" + descriptor);
        }
        String inner = descriptor.substring(1, close);
        List<Class<?>> params = new ArrayList<>();
        int i = 0;
        while (i < inner.length()) {
            char c = inner.charAt(i);
            if (c == '[') {
                int dims = 0;
                while (i < inner.length() && inner.charAt(i) == '[') {
                    i++;
                    dims++;
                }
                Class<?> component = resolveType(inner, i);
                i = nextTypeEnd(inner, i);
                try {
                    params.add(Class.forName("[".repeat(dims) + component.descriptorString()));
                } catch (Throwable t) {
                    throw new IllegalArgumentException("描述符里的数组类型解析不了：" + inner);
                }
                continue;
            }
            Class<?> type = resolveType(inner, i);
            params.add(type);
            i = nextTypeEnd(inner, i);
        }
        return params.toArray(new Class<?>[0]);
    }

    private static Class<?> resolveType(String inner, int at) {
        if (at >= inner.length()) {
            throw new IllegalArgumentException("描述符提前结束：" + inner);
        }
        switch (inner.charAt(at)) {
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'V': return void.class;
            case 'L': {
                int end = inner.indexOf(';', at);
                if (end < 0) {
                    throw new IllegalArgumentException("描述符里的类名没有分号：" + inner);
                }
                String className = inner.substring(at + 1, end).replace('/', '.');
                Class<?> loaded = load(className);
                if (loaded == null) {
                    throw new IllegalArgumentException("描述符里的类不存在：" + className);
                }
                return loaded;
            }
            default:
                throw new IllegalArgumentException("描述符里有看不懂的类型字符 '" + inner.charAt(at)
                        + "'：" + inner);
        }
    }

    private static int nextTypeEnd(String inner, int at) {
        if (inner.charAt(at) == 'L') {
            int end = inner.indexOf(';', at);
            return end < 0 ? inner.length() : end + 1;
        }
        return at + 1;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, MixinTargetCheck.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 源码扫描

    /** 一条注解：名字 + 括号里的原文。 */
    private record Annotation(String name, String args) {
    }

    private static List<Annotation> allAnnotations(String source) {
        List<Annotation> out = new ArrayList<>();
        int i = 0;
        while ((i = source.indexOf('@', i)) >= 0) {
            i++;
            int start = i;
            while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
                i++;
            }
            String name = source.substring(start, i);
            if (name.isEmpty()) {
                continue;
            }
            int open = skipSpaces(source, i);
            if (open < source.length() && source.charAt(open) == '(') {
                int close = matching(source, open);
                if (close > 0) {
                    out.add(new Annotation(name, source.substring(open + 1, close)));
                    i = close + 1;
                }
            }
        }
        return out;
    }

    private static List<Annotation> annotations(String source, String name) {
        List<Annotation> out = new ArrayList<>();
        for (Annotation annotation : allAnnotations(source)) {
            if (annotation.name.equals(name)) {
                out.add(annotation);
            }
        }
        return out;
    }

    /**
     * 取 {@code key = <expr>} 里 {@code expr} 的原文。
     *
     * <p>表达式可能跨行、可能带 {@code @At(...)} 这类嵌套括号，所以按深度切而不是按逗号切。遇到
     * 括号深度为 0 的 {@code ,} 或 {@code )} 就算结束——注意传进来的 {@code args} 已经去掉了最外层
     * 括号，所以只可能遇到 {@code ,}。</p>
     */
    private static String assignment(String args, String key) {
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(args, i);
                continue;
            }
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (depth == 0 && args.startsWith(key, i) && wordBoundary(args, i, key.length())) {
                int eq = skipSpaces(args, i + key.length());
                if (eq < args.length() && args.charAt(eq) == '=') {
                    int start = skipSpaces(args, eq + 1);
                    int end = start;
                    int inner = 0;
                    while (end < args.length()) {
                        char ch = args.charAt(end);
                        if (ch == '"' || ch == '\'') {
                            end = skipLiteral(args, end) + 1;
                            continue;
                        }
                        if (ch == '(' || ch == '{') {
                            inner++;
                        } else if (ch == ')' || ch == '}') {
                            inner--;
                        } else if (ch == ',' && inner == 0) {
                            break;
                        }
                        end++;
                    }
                    return args.substring(start, Math.min(end, args.length()));
                }
            }
        }
        return null;
    }

    /** 把一段表达式里的字符串字面量取出来。数组形式（{@code {"a","b"}}）会拆成多个。 */
    private static List<String> stringList(String expression) {
        List<String> out = new ArrayList<>();
        if (expression == null) {
            return out;
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("{")) {
            for (String part : splitTopLevel(trimmed.substring(1, Math.max(1, trimmed.length() - 1)))) {
                String value = join(part);
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
            return out;
        }
        String value = join(trimmed);
        if (!value.isEmpty()) {
            out.add(value);
        }
        return out;
    }

    private static String firstString(String expression) {
        List<String> all = stringList(expression);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 把 {@code "a" + "b"} 拼成一个字符串。
     *
     * <p>跨行的长描述符就是这么写的（{@code "renderItem(Lnet/...ItemStack;" + "Lnet/...BakedModel;)V"}），
     * 只取第一个字面量会得到半截描述符，于是每一处都报"找不到"。</p>
     */
    private static String join(String expression) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '"') {
                StringBuilder literal = new StringBuilder();
                i++;
                while (i < expression.length() && expression.charAt(i) != '"') {
                    if (expression.charAt(i) == '\\' && i + 1 < expression.length()) {
                        i++;
                    }
                    literal.append(expression.charAt(i));
                    i++;
                }
                out.append(literal);
            }
            i++;
        }
        return out.toString();
    }

    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipLiteral(text, i);
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    /** {@code @Mixin(World.class)} → 全限定名；{@code targets = "a.b.C"} 直接用。 */
    private static String mixinTarget(String args, Map<String, String> imports) {
        String targets = assignment(args, "targets");
        if (targets != null) {
            return firstString(targets);
        }
        String trimmed = args.trim();
        if (trimmed.endsWith(".class")) {
            String simple = trimmed.substring(0, trimmed.length() - ".class".length()).trim();
            String imported = imports.get(simple);
            return imported != null ? imported : simple;
        }
        return null;
    }

    private static Map<String, String> imports(String source) {
        Map<String, String> out = new HashMap<>();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            String name = trimmed.substring("import ".length()).replace(";", "").trim();
            if (name.startsWith("static ")) {
                continue;
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                out.put(name.substring(dot + 1), name);
            }
        }
        return out;
    }

    /** 把注释换成空格（保留换行与字符串），否则 javadoc 里的 {@code @Inject} 会被当成真的。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipLiteral(source, i);
                out.append(source, i, Math.min(end + 1, source.length()));
                i = end + 1;
                continue;
            }
            if (c == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    while (i < source.length() && source.charAt(i) != '\n') {
                        out.append(' ');
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    while (i < source.length() && !(source.charAt(i) == '*' && i + 1 < source.length()
                            && source.charAt(i + 1) == '/')) {
                        out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    out.append("  ");
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 返回字符串/字符字面量的收尾引号下标。 */
    private static int skipLiteral(String text, int at) {
        char quote = text.charAt(at);
        int i = at + 1;
        while (i < text.length() && text.charAt(i) != quote) {
            if (text.charAt(i) == '\\') {
                i++;
            }
            i++;
        }
        return Math.min(i, text.length() - 1);
    }

    private static int matching(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(text, i);
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipSpaces(String text, int at) {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static boolean wordBoundary(String text, int at, int length) {
        if (at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))) {
            return false;
        }
        int after = at + length;
        return after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after));
    }

    private static String squash(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static void fail(String message) {
        failures++;
        System.out.println("  ! " + message);
    }
}
