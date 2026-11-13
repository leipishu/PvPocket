import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.Material;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks that the loadout catalogue offers the right items for each target version.
 *
 * <p>The catalogue is not constant across versions: mace and wind charge arrive in 1.21, the
 * spear in 1.21.11, copper weapons later, and copper may exist for only some kinds.
 *
 * <p>Real code asks the item registry through {@link ItemKind#available()}. This class answers
 * with three hand-written id sets standing in for 1.20.1, 1.21.1 and 1.21.11.
 *
 * <p>No game starts, so it runs in CI and checks the mechanism, not the registry contents.
 * Usage: {@code LoadoutContentCheck [--mcjar <path>]}; exits 0 when every assertion holds.
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

        // Second part: the current game jar, whose en_us.json lists every vanilla item as
        // item.minecraft.<id>. The three sets above cover the mechanism, this covers the data.
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
     * Checks the current game jar.
     *
     * <p>These assertions hold for every version: swords, food and blocks always exist, and
     * 1.21.x has mace and wind charge. The spear is not asserted here because it appears by
     * version; the simulated sets above cover it.
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

        // Material matrix: each available materialised kind has materials, in tier order.
        for (ItemKind kind : ItemKind.values()) {
            if (!kind.materialised || !kind.available(ids)) {
                continue;
            }
            List<Material> materials = kind.materials(ids);
            expect(!materials.isEmpty(), kind.id + " 可用却没有一种材质");
            expect(isTierOrdered(materials), kind.id + " 的材质顺序不是阶梯顺序：" + materials);
        }

        // Facts that hold for every 1.21.x; asserted so that losing them fails here.
        expect(ItemKind.SWORD.available(ids), "这一版游戏里没有剑？");
        expect(ItemKind.MACE.available(ids), "1.21.x 应该有重锤");
        expect(ItemKind.WIND_CHARGE.available(ids), "1.21.x 应该有风弹");
        expect(ItemKind.FOOD.available(ids), "食物应该永远可用");
        System.out.println("    共 " + itemIds.size() + " 个物品 id（来自 " + jarPath + "）");
    }

    /**
     * Reads item ids from {@code assets/minecraft/lang/en_us.json} in the client jar.
     *
     * <p>Only keys of the form {@code item.minecraft.<rest>} with no dot in {@code <rest>} are
     * kept: potions add keys such as {@code item.minecraft.splash_potion.effect.speed}, which
     * name a variant rather than a registry entry. The caller checks the resulting count.
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

    // ------------------------------------------------------------------ expected values

    /** What each target version's registry contains, limited to ids the loadout catalogue uses. */
    private enum Version {
        /** No mace, no wind charge, no spear, no copper weapons. */
        V1201(false, false, false, Set.of()),
        /** Mace and wind charge, no spear, no copper weapons. */
        V1211(true, true, false, Set.of()),
        /**
         * Everything, with copper only for the spear. That combination proves material filtering
         * runs per kind and material rather than per material globally.
         */
        V12111(true, true, true, Set.of("copper_spear"));

        final boolean mace;
        final boolean windCharge;
        final boolean spear;
        /** Ids that exist only in this version, used for kind-and-material variants. */
        final Set<String> extra;

        Version(boolean mace, boolean windCharge, boolean spear, Set<String> extra) {
            this.mace = mace;
            this.windCharge = windCharge;
            this.spear = spear;
            this.extra = extra;
        }
    }

    /**
     * Expands one version rule into a set of ids.
     *
     * <p>Generated from {@link ItemKind} and {@link Material} rather than typed out, so an entry
     * added to either enum without a matching rule fails here.
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
                    // Copper appears only where the version declares it; other materials always do.
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

    // ------------------------------------------------------------------ assertions

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

        // Page order must follow declaration order, or the panel layout drifts with registry order.
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

        // Order must be tier order: wood, stone, copper, iron, gold, diamond, netherite.
        expect(isTierOrdered(spears12111), "长矛材质顺序不是阶梯顺序：" + spears12111);
    }

    private static void checkVariantKinds(ItemKind.ItemIds v1201, ItemKind.ItemIds v1211,
                                          ItemKind.ItemIds v12111) {
        // These kinds own no item id; availability comes from variants, not the registry.
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
        // The wire format uses kind.id and material.id, not ordinal; renaming breaks saved presets.
        for (ItemKind kind : ItemKind.values()) {
            expect(ItemKind.byId(kind.id) == kind, "ItemKind.byId 没有往返：" + kind.id);
        }
        for (Material material : Material.values()) {
            expect(Material.byId(material.id) == material, "Material.byId 没有往返：" + material.id);
        }
        expect("spear".equals(ItemKind.SPEAR.id), "长矛的线 id 必须稳定为 spear");
        expect("copper".equals(Material.COPPER.id), "铜的线 id 必须稳定为 copper");
        // Materialised kinds concatenate the material; others use the kind id alone.
        expect("wooden_sword".equals(ItemKind.SWORD.itemPath(Material.WOOD)), "木剑 id 拼错");
        expect("copper_spear".equals(ItemKind.SPEAR.itemPath(Material.COPPER)), "铜矛 id 拼错");
        expect("mace".equals(ItemKind.MACE.itemPath(Material.IRON)), "非材质化种类不该带材质前缀");
    }

    // ------------------------------------------------------------------ helpers

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

    /** Records a failure unconditionally, for cases where later assertions cannot run. */
    private static void fail(String message) {
        failures++;
        System.out.println("  ! " + message);
    }
}
