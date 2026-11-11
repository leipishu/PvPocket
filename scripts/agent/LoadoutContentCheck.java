import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.Material;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 配装目录的版本可用性自检。
 *
 * <p>这一段的由来：模组的物品目录跨版本不是常量。重锤与风弹是 1.21 才有的，长矛是 1.21.11 才有的，
 * 铜制武器还要更晚，而且铜可能只对部分种类成立（有铜矛不一定有铜剑）。把这张表写成常量，每移植一个
 * 版本就要靠人记得改，改漏的表现是"面板上有这一行，加上去却什么都没发"。</p>
 *
 * <p>所以真实代码把判断交给物品注册表（{@link ItemKind#available()}），而把"问谁"抽成了
 * {@link ItemKind.ItemIds}。本类就是用三份手写的 id 集合冒充 1.20.1 / 1.21.1 / 1.21.11 的注册表，
 * 断言每一个版本应当看到什么。**不需要启动游戏**，因此可以挂 CI，也可以在还没有多版本构建的时候就跑。</p>
 *
 * <p>它测的是机制，不是真实注册表的内容：真实内容由各版本的 dev 客户端导出的清单核对（见方案文档
 * §8）。两者分工明确——机制错了在这里红，数据错了在清单差异里红。</p>
 */
public final class LoadoutContentCheck {

    private static int failures;

    public static void main(String[] args) {
        ItemKind.ItemIds v1201 = ids(Version.V1201);
        ItemKind.ItemIds v1211 = ids(Version.V1211);
        ItemKind.ItemIds v12111 = ids(Version.V12111);

        checkKinds(v1201, v1211, v12111);
        checkMaterials(v1211, v12111);
        checkVariantKinds(v1201, v1211, v12111);
        checkEveryKindIsReachable(v1201, v1211, v12111);
        checkWireIds();

        // 第二段：拿真实的游戏 jar 验一遍。上面那三份是"机制对不对"，这一段是"当前这版游戏里到底有
        // 什么"。物品 id 从客户端 jar 的 en_us.json 里取——每个原版物品都有一条
        // item.minecraft.<id>，所以不需要启动游戏也不需要 Bootstrap。
        if (args.length == 2 && "--mcjar".equals(args[0])) {
            checkAgainstJar(args[1]);
        }

        System.out.println(failures == 0
                ? "[loadout-content] 全部通过"
                : "[loadout-content] " + failures + " 项失败");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * 用真实的游戏 jar 验一遍当前版本。
     *
     * <p>这一段的断言只写"不会随版本变化的事实"：任何版本都必须有剑、有食物、有方块；1.21.x 必须有
     * 重锤与风弹。长矛不在这里断言，因为它随版本出现——那部分由上面的三份模拟清单负责。</p>
     */
    private static void checkAgainstJar(String jarPath) {
        Set<String> itemIds;
        try {
            itemIds = itemIdsFromJar(jarPath);
        } catch (Exception e) {
            fail("读不了游戏 jar：" + jarPath + "（" + e.getMessage() + "）");
            return;
        }
        if (itemIds.size() < 300) {
            fail("从 " + jarPath + " 只读到 " + itemIds.size() + " 个物品 id，lang 文件多半换了位置");
            return;
        }
        ItemKind.ItemIds ids = itemIds::contains;

        for (ItemKind.Page page : ItemKind.Page.values()) {
            List<ItemKind> offered = ItemKind.availableOf(page, ids);
            StringBuilder line = new StringBuilder("    " + page + ": ");
            for (ItemKind kind : offered) {
                line.append(kind.id).append(' ');
            }
            System.out.println(line.toString().stripTrailing());
            expect(!offered.isEmpty(), "页面 " + page + " 在这一版游戏里一个种类都没有");
        }

        // 材质矩阵：每一个可用的材质化种类，至少要有一种材质；顺序必须是阶梯顺序。
        for (ItemKind kind : ItemKind.values()) {
            if (!kind.materialised || !kind.available(ids)) {
                continue;
            }
            List<Material> materials = kind.materials(ids);
            expect(!materials.isEmpty(), kind.id + " 可用却没有一种材质");
            expect(isTierOrdered(materials), kind.id + " 的材质顺序不是阶梯顺序：" + materials);
        }

        // 这几个是所有 1.21.x 都成立的事实；写成断言是为了让"哪天它们没了"当场变红。
        expect(ItemKind.SWORD.available(ids), "这一版游戏里没有剑？");
        expect(ItemKind.MACE.available(ids), "1.21.x 应该有重锤");
        expect(ItemKind.WIND_CHARGE.available(ids), "1.21.x 应该有风弹");
        expect(ItemKind.FOOD.available(ids), "食物应该永远可用");
        System.out.println("    共 " + itemIds.size() + " 个物品 id（来自 " + jarPath + "）");
    }

    /**
     * 从客户端 jar 的 {@code assets/minecraft/lang/en_us.json} 里取物品 id。
     *
     * <p>只保留 {@code item.minecraft.<rest>} 且 {@code <rest>} 里没有点号的键：药水有
     * {@code item.minecraft.splash_potion.effect.speed} 这种键，它描述的是一个物品的变体，不是注册表
     * 里的一项。这个过滤是唯一容易出错的地方，所以下面的断言会检查数量级。</p>
     */
    private static Set<String> itemIdsFromJar(String jarPath) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath)) {
            java.util.zip.ZipEntry entry = zip.getEntry("assets/minecraft/lang/en_us.json");
            if (entry == null) {
                throw new IllegalStateException("jar 里没有 assets/minecraft/lang/en_us.json");
            }
            String json;
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            Set<String> out = new LinkedHashSet<>();
            for (String key : com.google.gson.JsonParser.parseString(json).getAsJsonObject().keySet()) {
                if (!key.startsWith("item.minecraft.")) {
                    continue;
                }
                String id = key.substring("item.minecraft.".length());
                if (id.indexOf('.') < 0) {
                    out.add(id);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 期望值

    /** 三个目标版本各自"注册表里有什么"，只列配装目录会用到的那些。 */
    private enum Version {
        /** 没有重锤、没有风弹、没有长矛、没有铜制武器。 */
        V1201(false, false, false, Set.of()),
        /** 有重锤与风弹，没有长矛，没有铜制武器。 */
        V1211(true, true, false, Set.of()),
        /**
         * 全都有。铜**只对长矛**成立——这是刻意的：现实里"有铜矛不一定有铜剑"，用它来证明材质
         * 过滤是按（种类 × 材质）算的，不是按材质全局算的。
         */
        V12111(true, true, true, Set.of("copper_spear"));

        final boolean mace;
        final boolean windCharge;
        final boolean spear;
        /** 额外存在的 id，用来表达"这个版本多出来的具体变体"。 */
        final Set<String> extra;

        Version(boolean mace, boolean windCharge, boolean spear, Set<String> extra) {
            this.mace = mace;
            this.windCharge = windCharge;
            this.spear = spear;
            this.extra = extra;
        }
    }

    /**
     * 把一份版本规则展开成 id 集合。
     *
     * <p>不是手打一长串：手打的那份会随 {@code ItemKind} 增删而失同步，而它恰恰是用来发现失同步的。</p>
     */
    private static ItemKind.ItemIds ids(Version version) {
        Set<String> set = new LinkedHashSet<>();
        for (ItemKind kind : ItemKind.values()) {
            if (kind.resolvesByVariant()) {
                continue;
            }
            if (kind == ItemKind.MACE) {
                if (version.mace) {
                    set.add(kind.itemPath(null));
                }
                continue;
            }
            if (kind == ItemKind.WIND_CHARGE) {
                if (version.windCharge) {
                    set.add(kind.itemPath(null));
                }
                continue;
            }
            if (kind == ItemKind.SPEAR) {
                if (version.spear) {
                    for (Material material : Material.values()) {
                        set.add(kind.itemPath(material));
                    }
                }
                continue;
            }
            if (kind.materialised) {
                for (Material material : Material.values()) {
                    // 铜只出现在版本明确声明的变体里；其余材质每种都有。
                    if (material == Material.COPPER && !version.extra.contains(kind.itemPath(material))) {
                        continue;
                    }
                    set.add(kind.itemPath(material));
                }
            } else {
                set.add(kind.itemPath(null));
            }
        }
        set.addAll(version.extra);
        return set::contains;
    }

    // ------------------------------------------------------------------ 断言

    private static void checkKinds(ItemKind.ItemIds v1201, ItemKind.ItemIds v1211,
                                   ItemKind.ItemIds v12111) {
        List<ItemKind> weapons1201 = ItemKind.availableOf(ItemKind.Page.WEAPON, v1201);
        List<ItemKind> weapons1211 = ItemKind.availableOf(ItemKind.Page.WEAPON, v1211);
        List<ItemKind> weapons12111 = ItemKind.availableOf(ItemKind.Page.WEAPON, v12111);

        expect(!weapons1201.contains(ItemKind.MACE), "1.20.1 不应该提供重锤");
        expect(weapons1211.contains(ItemKind.MACE), "1.21.1 应该提供重锤");
        expect(!weapons1211.contains(ItemKind.SPEAR), "1.21.1 不应该提供长矛");
        expect(weapons12111.contains(ItemKind.SPEAR), "1.21.11 应该提供长矛");
        expect(!weapons1201.contains(ItemKind.SPEAR), "1.20.1 不应该提供长矛");

        List<ItemKind> consumables1201 = ItemKind.availableOf(ItemKind.Page.CONSUMABLE, v1201);
        List<ItemKind> consumables1211 = ItemKind.availableOf(ItemKind.Page.CONSUMABLE, v1211);
        expect(!consumables1201.contains(ItemKind.WIND_CHARGE), "1.20.1 不应该提供风弹");
        expect(consumables1211.contains(ItemKind.WIND_CHARGE), "1.21.1 应该提供风弹");

        // 页内顺序必须与声明顺序一致，否则面板每次构建的排布会随注册表顺序漂移。
        expect(ordered(weapons12111), "weapon 页的顺序不是声明顺序：" + weapons12111);
    }

    private static void checkMaterials(ItemKind.ItemIds v1211, ItemKind.ItemIds v12111) {
        List<Material> swords1211 = ItemKind.SWORD.materials(v1211);
        expect(!swords1211.contains(Material.COPPER), "1.21.1 的剑不应该有铜制");
        expect(swords1211.contains(Material.IRON), "1.21.1 的剑应该有铁制");

        List<Material> swords12111 = ItemKind.SWORD.materials(v12111);
        expect(!swords12111.contains(Material.COPPER),
                "铜只声明给了长矛，剑不该凭空多出铜制——材质过滤必须按（种类 × 材质）算");

        List<Material> spears1211 = ItemKind.SPEAR.materials(v1211);
        expect(spears1211.isEmpty(), "1.21.1 不该有长矛材质可选，实际 " + spears1211);

        List<Material> spears12111 = ItemKind.SPEAR.materials(v12111);
        expect(spears12111.contains(Material.COPPER), "1.21.11 的长矛应该有铜制");
        expect(spears12111.size() == Material.values().length,
                "1.21.11 的长矛应该七种材质齐全，实际 " + spears12111);

        // 顺序必须是阶梯顺序（木 → 石 → 铜 → 铁 → 金 → 钻石 → 下界合金），否则循环控件的方向是乱的。
        expect(isTierOrdered(spears12111), "长矛材质顺序不是阶梯顺序：" + spears12111);
    }

    private static void checkVariantKinds(ItemKind.ItemIds v1201, ItemKind.ItemIds v1211,
                                          ItemKind.ItemIds v12111) {
        // 回归：这三个种类没有自己的物品 id，早期版本用"注册表里有没有 food"来判断可用性，
        // 结果整个消耗品页在每一个版本上都是空的。
        for (ItemKind.ItemIds ids : List.of(v1201, v1211, v12111)) {
            expect(ItemKind.FOOD.available(ids), "食物应该永远可用（它由 variant 决定物品）");
            expect(ItemKind.POTION.available(ids), "药水应该永远可用");
            expect(ItemKind.BLOCK.available(ids), "方块应该永远可用");
        }
    }

    private static void checkEveryKindIsReachable(ItemKind.ItemIds v1201, ItemKind.ItemIds v1211,
                                                  ItemKind.ItemIds v12111) {
        for (ItemKind kind : ItemKind.values()) {
            boolean anywhere = kind.available(v1201) || kind.available(v1211) || kind.available(v12111);
            expect(anywhere, "种类 " + kind.id + " 在三个版本里都不可用——多半是 id 拼错了");
        }
        for (ItemKind.Page page : ItemKind.Page.values()) {
            expect(!ItemKind.availableOf(page, v12111).isEmpty(),
                    "页面 " + page + " 在 1.21.11 上一个种类都没有");
        }
    }

    private static void checkWireIds() {
        // 线格式用的是 kind.id / material.id，不是 ordinal。改名等于让所有旧预设失效，这里钉住。
        for (ItemKind kind : ItemKind.values()) {
            expect(ItemKind.byId(kind.id) == kind, "ItemKind.byId 没有往返：" + kind.id);
        }
        for (Material material : Material.values()) {
            expect(Material.byId(material.id) == material, "Material.byId 没有往返：" + material.id);
        }
        expect("spear".equals(ItemKind.SPEAR.id), "长矛的线 id 必须稳定为 spear");
        expect("copper".equals(Material.COPPER.id), "铜的线 id 必须稳定为 copper");
        // 材质化的种类拼 id，非材质化的直接用种类 id。
        expect("wooden_sword".equals(ItemKind.SWORD.itemPath(Material.WOOD)), "木剑 id 拼错");
        expect("copper_spear".equals(ItemKind.SPEAR.itemPath(Material.COPPER)), "铜矛 id 拼错");
        expect("mace".equals(ItemKind.MACE.itemPath(Material.IRON)), "非材质化种类不该带材质前缀");
    }

    // ------------------------------------------------------------------ 工具

    private static boolean ordered(List<ItemKind> kinds) {
        List<ItemKind> expected = new ArrayList<>();
        for (ItemKind kind : ItemKind.values()) {
            if (kind.page == ItemKind.Page.WEAPON && kinds.contains(kind)) {
                expected.add(kind);
            }
        }
        return expected.equals(kinds);
    }

    private static boolean isTierOrdered(List<Material> materials) {
        int previous = -1;
        for (Material material : materials) {
            if (material.tier <= previous) {
                return false;
            }
            previous = material.tier;
        }
        return true;
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    /** 无条件失败。用于"后面的断言全都做不了"的情形（例如 jar 读不出来）。 */
    private static void fail(String message) {
        failures++;
        System.out.println("  ! " + message);
    }
}
