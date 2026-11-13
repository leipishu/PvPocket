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
 * Resolves every string in a mixin annotation against the real class on the classpath.
 *
 * <p>{@code @Inject(method = "...")}, {@code @At(target = "...")} and {@code @Accessor("...")}
 * hold plain strings that javac and the annotation processor do not validate. A typo compiles
 * and packages fine, then fails at startup or silently skips the injection.
 *
 * <p>No game starts and no class initialises, so it runs in CI. It checks whether a reference
 * resolves, not whether the injection point is semantically right.
 *
 * <p>Usage: {@code MixinTargetCheck [--src <dir> ...]}; exits 0 when all resolve, 1 otherwise.
 */
public final class MixinTargetCheck {

    /** Annotations that take a {@code method = } argument. */
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

    // ------------------------------------------------------------------ one file

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
            checkShadowMembers(where, source, targetClass);
        }
    }

    /** Looks up every {@code method = "..."} value on the target class. */
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

    /** Resolves the class and member in {@code @At(target = "Lpkg/Cls;m(desc)ret")}. */
    private static void checkAtTargets(String where, String source) {
        for (Annotation at : annotations(source, "At")) {
            // join, not firstString: long descriptors are concatenated literals, and the first
            // one alone yields a class with an empty member, which would pass unnoticed.
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
                // Field form: Lpkg/Cls;name:Ltype
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

    /**
     * Checks that a {@code @Shadow} field or method exists on the target class.
     *
     * <p>A shadowed field that does not bind only logs a compiler warning, and the field stays
     * null at runtime. The declared field type is compared with the target's simple name.
     */
    private static void checkShadowMembers(String where, String source, Class<?> targetClass) {
        int index = 0;
        while ((index = source.indexOf("@Shadow", index)) >= 0) {
            index += "@Shadow".length();
            String decl = declarationAfter(source, index);
            if (decl == null) {
                continue;
            }
            int paren = decl.indexOf('(');
            int semi = decl.indexOf(';');
            if (paren >= 0 && (semi < 0 || paren < semi)) {
                // Method: name only; parameter types are checked by the @Inject path.
                String name = decl.substring(0, paren).trim();
                int space = name.lastIndexOf(' ');
                if (space >= 0) {
                    name = name.substring(space + 1).trim();
                }
                referenceCount++;
                if (!name.isEmpty() && anyDeclared(targetClass, name) == null) {
                    fail(where + "：@Shadow 的方法 " + name + " 在 " + targetClass.getName() + " 上不存在");
                }
                continue;
            }
            // Field. Stripping modifiers may remove the semicolon, so semi < 0 is not decisive.
            String body = semi >= 0 ? decl.substring(0, semi).trim() : decl.trim();
            int space = body.lastIndexOf(' ');
            if (space < 0) {
                continue;
            }
            String declaredType = body.substring(0, space).trim();
            String name = body.substring(space + 1).trim();
            referenceCount++;
            Field field = findField(targetClass, name);
            if (field == null) {
                fail(where + "：@Shadow 的字段 " + name + " 在 " + targetClass.getName()
                        + " 上不存在（改名时漏了？）");
                continue;
            }
            // Compare the type too: a matching name with a stale type still fails at runtime.
            String wanted = declaredType.substring(declaredType.lastIndexOf('.') + 1).trim();
            String actual = field.getType().getSimpleName();
            if (!wanted.equals(actual)) {
                fail(where + "：@Shadow 的字段 " + name + " 声明成 " + wanted + "，而 "
                        + targetClass.getName() + " 上是 " + actual);
            }
        }
    }

    /**
     * Returns the declaration text after an annotation, up to {@code ;} or <code>{</code>, with
     * annotations and modifiers stripped.
     *
     * <p>{@code @Shadow} is often followed by {@code @Final} or {@code @Mutable}, so the next
     * token alone is not the declaration.
     */
    private static String declarationAfter(String source, int from) {
        int end = -1;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ';' || c == '{') {
                end = i;
                break;
            }
            // Annotations may contain parentheses and commas; a semicolon ends the scan.
        }
        if (end < 0) {
            return null;
        }
        String decl = source.substring(from, end).trim();
        // Strip leading annotations (possibly with arguments) and modifiers.
        while (true) {
            String before = decl;
            decl = decl.replaceFirst("^@[\\w.]+\\s*(\\([^)]*\\))?\\s*", "");
            decl = decl.replaceFirst(
                    "^(?:public|protected|private|final|static|transient|volatile|abstract)\\s+", "");
            if (decl.equals(before)) {
                break;
            }
        }
        return decl.isEmpty() ? null : decl;
    }

    /** Resolves the member names given to {@code @Accessor("x")} and {@code @Invoker("y")}. */
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

    // ------------------------------------------------------------------ reflection

    /**
     * Checks a method descriptor ({@code name} or {@code name(desc)ret}) against a class and its
     * hierarchy.
     *
     * <p>Returns {@code null} on success, otherwise the reason. A bare name passes when any
     * method of that name exists. A full descriptor also compares parameter types, where renames
     * tend to break. An unresolvable class in the descriptor is a failure, not a fallback to a
     * name-only check; only a truncated descriptor falls back.
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
                // Try the superclass.
            } catch (Throwable ignored) {
                // A missing member type makes reflection throw; treat the class as unreadable.
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
            // An absent parameter type hides every declaration; skip the class, do not crash.
            return new Method[0];
        }
    }

    /** The class, its superclass chain and every interface; mixins can target default methods. */
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

    /**
     * Parses a descriptor such as {@code (ZLjava/lang/String;)V} into the parameter type array.
     *
     * @throws IllegalArgumentException when the descriptor cannot be read
     */
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

    // ------------------------------------------------------------------ source scanning

    /** One annotation: its name and the raw text between its parentheses. */
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
     * Returns the raw text of the expression assigned to {@code key} in {@code key = <expr>}.
     *
     * <p>The expression may span lines or nest parentheses, so it is split by depth rather than
     * by comma. The passed {@code args} already excludes the outer parentheses.
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

    /**
     * Collects the string literals of an expression.
     *
     * <p>An array form such as {@code {"a","b"}} yields one entry per element.
     */
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
     * Concatenates {@code "a" + "b"} into one string.
     *
     * <p>Long descriptors are written that way across lines, and the first literal alone yields
     * half a descriptor that matches nothing.
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

    /**
     * Resolves {@code @Mixin(World.class)} to a fully qualified name.
     *
     * <p>A {@code targets = "a.b.C"} value is used as written.
     */
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

    /**
     * Replaces comments with spaces, keeping newlines and string literals.
     *
     * <p>Otherwise an {@code @Inject} mentioned in a Javadoc would be scanned as a real one.
     */
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

    /** Returns the index of the closing quote of a string or char literal. */
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
