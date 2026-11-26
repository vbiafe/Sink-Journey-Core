package com.vbiafe.sjcore.space;

import com.denfop.api.space.BaseResource;
import com.denfop.api.space.IBaseResource;
import com.denfop.api.space.IBody;
import com.denfop.api.space.ISpaceNet;
import com.denfop.api.space.SpaceNet;
import com.denfop.api.space.colonies.DataItem;
import com.denfop.api.space.colonies.api.IColonyNet;
import com.denfop.api.space.rovers.enums.EnumTypeRovers;
import com.vbiafe.sjcore.SJCore;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IU 太空远征（探测器 / 卫星 / 漫游车 / 火箭，采矿模块加成的那条线）的矿表。
 *
 * <p>IU 把这套表写死在 {@code SpaceInit.init()}：29 个可采天体各 2~6 种矿石，
 * 既有锡、陨铁这种"月球出锡"的随机分配，也有矿物文档已经删掉的铂 / 钯 / 铱 / 钕 / 钆 / 钇
 * 单元素矿——有矿就能绕开湿法线。这里按 {@code kubejs/docs/ORE_GENERATION.md} 的维度分层
 * 重排：每个天体只出它那一层该出的矿，单元素铂族与稀土一律不再从太空来。
 *
 * <table>
 *   <caption>天体分层</caption>
 *   <tr><th>层</th><th>矿物文档</th><th>天体</th></tr>
 *   <tr><td>月球层</td><td>§4.4 钛 / 钒 / 锆</td><td>moon、phobos、deimos、charon、proteus</td></tr>
 *   <tr><td>水星层</td><td>§4.4 钨 / 钼 / 钽 / 铌</td><td>mercury</td></tr>
 *   <tr><td>火星层</td><td>§4.4 独居石 / 铪</td><td>mars</td></tr>
 *   <tr><td>冰星层</td><td>§4.4 锂 / 铍</td><td>九颗冰壳卫星</td></tr>
 *   <tr><td>下界层</td><td>§4.2 砷 / 铋 / 铊 / 硫</td><td>venus、io</td></tr>
 *   <tr><td>末地层</td><td>§4.3 镍 / 钴 / 铬 / 锰 + 两种复合矿</td><td>asteroids</td></tr>
 *   <tr><td>主世界层</td><td>§4.1 常见矿</td><td>ceres 与六颗冷天体（盐类 / 挥发分）</td></tr>
 *   <tr><td>完美级层</td><td>§4.6 的铂族 / 稀土例外</td><td>pluto、charon、eris、makemake、haumea（只有 EnumRoversLevel.FOUR 才到得了）</td></tr>
 * </table>
 *
 * <p>完美级（{@code EnumRoversLevel.FOUR}）才到得了的五颗远处天体是唯一的例外：它们照旧出
 * 铂 / 钯 / 铱与钕 / 钆 / 钇的单元素矿，当作后期奖励——绕开湿法线只在最后一档解锁。
 *
 * <p>只换矿石种类，不换节奏：每颗天体原来有几条"卫星 / 火箭出矿"的资源条目，替换后还是几条，
 * {@code count / chance / percent} 全部沿用原值，矿石按表里的顺序循环填进去。
 * 石头、卵石、流体那几类资源不动。
 *
 * <p>殖民地（采矿工厂被动产出）走的是另一张表 {@code ColonyNet.bodyItemStackList}，
 * 同样按这张矿表重排，等级与数量沿用原值——否则远征里删掉的铂族 / 单元素稀土矿
 * 还会从殖民地里漏出来。
 */
public final class SpaceOreTable {

    /** IU 的太空矿方块都在 {@code industrialupgrade:blockspace[2-4]?/*} 下，别的资源（石头 / 卵石 / 流体）不走这个前缀。 */
    private static final ResourceLocation SPACE_ORE_PREFIX = ResourceLocation.fromNamespaceAndPath(SJCore.IU_MODID, "blockspace");

    /** 独居石给的是物品（一块矿挖下来 2 份），不是矿石方块。 */
    private static final String MONAZITE = "kubejs:monazite";

    /**
     * IU 的虚构矿：现实里没有这种矿，矿物文档也就没给它们分层。这些留在原天体不动，
     * 免得 IU 自己那套用它们的配方（精金 / 秘银 / 山铜 / 血石等）突然没料了。
     */
    private static final Set<String> FICTIONAL_ORES = Set.of(
        "industrialupgrade:blockspace2/europa_mikhail_ore",
        "industrialupgrade:blockspace4/umbriel_spinel_ore",
        "industrialupgrade:blockspace2/mars_adamantium_ore",
        "industrialupgrade:blockspace3/mercury_mithril_ore",
        "industrialupgrade:blockspace/deimos_orichalcum_ore",
        "industrialupgrade:blockspace/ariel_draconium",
        "industrialupgrade:blockspace3/mimas_bloodstone");

    private static final Map<String, List<String>> ORE_BY_BODY = buildTable();

    /** 只在物品注册表就绪之后成功跑一次；{@code ensureSpaceCatalogInitialized} 每次访问都会调到这里。 */
    private static boolean applied;

    private SpaceOreTable() {
    }

    /**
     * 由 {@code IuSpaceCatalogMixin} 在太空目录建立之后调用。
     *
     * <p>太空目录可能在物品注册表填满之前就被世界生成侧的 bootstrap 摸到（那时 {@code ITEM.get()}
     * 只会返回空气），所以这里先确认整张表的物品都解析得出来，解析不出来就原样放回去，等下一次访问再试；
     * 成功后置 {@code applied}，之后每次访问直接跳过。
     */
    public static void apply() {
        if (applied) {
            return;
        }
        ISpaceNet net = SpaceNet.instance;
        if (net == null) {
            SJCore.LOGGER.warn("[SJCore] 太空目录尚未建立，太空矿表未生效");
            return;
        }
        if (!allOresRegistered()) {
            return;
        }
        int bodies = 0;
        int entries = 0;
        for (IBody body : net.getBodyList()) {
            List<String> ores = ORE_BY_BODY.get(body.getName());
            if (ores == null) {
                continue;
            }
            List<IBaseResource> resources = body.getResources();
            List<Row> rows = new ArrayList<>();
            resources.removeIf(resource -> {
                if (!isSpaceOre(resource.getItemStack())) {
                    return false;
                }
                ItemStack stack = resource.getItemStack();
                rows.add(new Row(stack.getCount(), resource.getChance(), resource.getMaxChance(),
                    resource.getPercentPanel(), resource.getTypeRovers()));
                return true;
            });
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                new BaseResource(oreStack(ores, i, row.count()), row.min(), row.max(), row.percent(), body, row.rover());
            }
            bodies++;
            entries += rows.size();
        }
        int colonyEntries = rewriteColonyOres(net);
        applied = true;
        SJCore.LOGGER.info("[SJCore] IU 太空矿表已按矿物文档重排：{} 个天体、{} 条远征出矿、{} 条殖民地出矿",
            bodies, entries, colonyEntries);
    }

    private static boolean allOresRegistered() {
        for (List<String> ores : ORE_BY_BODY.values()) {
            for (String id : ores) {
                if (BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)) == Items.AIR) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int rewriteColonyOres(ISpaceNet net) {
        IColonyNet colonies = net.getColonieNet();
        if (colonies == null) {
            return 0;
        }
        int entries = 0;
        for (Map.Entry<String, List<String>> entry : ORE_BY_BODY.entrySet()) {
            IBody body = net.getBodyFromName(entry.getKey());
            if (body == null) {
                continue;
            }
            List<DataItem<ItemStack>> items = colonies.getItemsFromBody(body);
            if (items.isEmpty()) {
                continue;
            }
            List<ColonyRow> rows = new ArrayList<>();
            items.removeIf(data -> {
                if (!isSpaceOre(data.getElement())) {
                    return false;
                }
                rows.add(new ColonyRow(data.getElement().getCount(), data.getLevel()));
                return true;
            });
            for (int i = 0; i < rows.size(); i++) {
                ColonyRow row = rows.get(i);
                colonies.addItemStack(body, row.level(), oreStack(entry.getValue(), i, row.count()));
            }
            entries += rows.size();
        }
        return entries;
    }

    /**
     * 第 {@code index} 条出矿条目换成矿表里对应的矿。
     *
     * <p>独居石是个例外：世界里的一块独居石矿挖下来是 2 份 {@code kubejs:monazite}
     * （{@code rare_earth_ore.js}），所以太空这条也按"一块矿 = 2 份"给，数量翻倍。
     */
    private static ItemStack oreStack(List<String> ores, int index, int count) {
        String id = ores.get(index % ores.size());
        if (MONAZITE.equals(id)) {
            return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(MONAZITE)), count * 2);
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count);
    }

    private static boolean isSpaceOre(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!SJCore.IU_MODID.equals(id.getNamespace())) {
            return false;
        }
        if (FICTIONAL_ORES.contains(id.toString())) {
            return false;
        }
        String path = id.getPath();
        if (!path.startsWith(SPACE_ORE_PREFIX.getPath())) {
            return false;
        }
        String suffix = path.substring(SPACE_ORE_PREFIX.getPath().length());
        if (!suffix.startsWith("/") && !(suffix.length() > 1 && suffix.charAt(0) >= '2' && suffix.charAt(0) <= '4'
            && suffix.charAt(1) == '/')) {
            return false;
        }
        return true;
    }

    private record Row(int count, int min, int max, int percent, EnumTypeRovers rover) {
    }

    private record ColonyRow(int count, int level) {
    }

    private static Map<String, List<String>> buildTable() {
        String titanium = "industrialupgrade:blockspace4/titan_titanium_ore";
        String vanadium = "industrialupgrade:blockspace4/rhea_vanadium_ore";
        String zirconium = "industrialupgrade:blockspace3/phobos_zirconium_ore";
        String tungsten = "industrialupgrade:blockspace2/ganymede_tungsten_ore";
        String molybdenum = "industrialupgrade:blockspace4/tethys_molybdenum_ore";
        String tantalum = "industrialupgrade:blockspace3/phobos_tantalum";
        String niobium = "industrialupgrade:blockspace3/proteus_niobium";
        String hafnium = "industrialupgrade:blockspace3/oberon_hafnium";
        String monazite = MONAZITE;
        String lithium = "industrialupgrade:blockspace/callisto_lithium_ore";
        String beryllium = "industrialupgrade:blockspace2/ganymede_beryllium";
        String arsenic = "industrialupgrade:blockspace/charon_arsenic";
        String bismuth = "industrialupgrade:blockspace2/makemake_bismuth";
        String thallium = "industrialupgrade:blockspace3/miranda_thallium";
        String sulfur = "industrialupgrade:blockspace2/io_sulfur";
        String nickel = "industrialupgrade:blockspace4/titan_nickel_ore";
        String cobalt = "industrialupgrade:blockspace3/mars_cobalt_ore";
        String chromium = "industrialupgrade:blockspace4/rhea_chromium";
        String manganese = "industrialupgrade:blockspace/eris_manganese";
        String braggite = "industrialupgrade:mineral/braggite";
        String iridosmine = "industrialupgrade:mineral/iridosmine";
        String tin = "industrialupgrade:blockspace3/moon_tin_ore";
        String lead = "industrialupgrade:blockspace/dione_lead_ore";
        String zinc = "industrialupgrade:blockspace/charon_zinc";
        String silver = "industrialupgrade:blockspace2/makemake_silver";
        String aluminium = "industrialupgrade:blockspace2/ganymede_aluminium_ore";
        String boron = "industrialupgrade:blockspace3/pluto_boron";
        String calcium = "industrialupgrade:blockspace2/ganymede_calcium";
        String strontium = "industrialupgrade:blockspace4/triton_strontium";
        String barium = "industrialupgrade:blockspace/asteroid_barium_ore";
        String gallium = "industrialupgrade:blockspace4/tethys_gallium";
        String platinum = "industrialupgrade:blockspace/dione_platinum_ore";
        String palladium = "industrialupgrade:blockspace3/proteus_palladium";
        String iridium = "industrialupgrade:blockspace3/pluto_iridium";
        String neodymium = "industrialupgrade:blockspace4/triton_neodymium";
        String gadolinium = "industrialupgrade:blockspace3/oberon_gadolinium";
        String yttrium = "industrialupgrade:blockspace4/titania_yttrium";

        List<String> moonLayer = List.of(titanium, vanadium, zirconium);
        List<String> mercuryLayer = List.of(tungsten, molybdenum, tantalum, niobium);
        List<String> marsLayer = List.of(monazite, hafnium);
        List<String> icyLayer = List.of(lithium, beryllium);
        List<String> volcanicLayer = List.of(arsenic, bismuth, thallium, sulfur);
        List<String> endLayer = List.of(nickel, cobalt, chromium, manganese, braggite, iridosmine);
        List<String> commonLayer = List.of(tin, lead, zinc, silver, aluminium);
        List<String> volatileLayer = List.of(boron, calcium, strontium, barium, gallium);
        List<String> perfectLayer = List.of(platinum, palladium, iridium, neodymium, gadolinium, yttrium);

        Map<String, List<String>> table = new LinkedHashMap<>();
        for (String body : List.of("moon", "phobos", "deimos", "proteus")) {
            table.put(body, moonLayer);
        }
        table.put("mercury", mercuryLayer);
        table.put("mars", marsLayer);
        for (String body : List.of("europe", "ganymede", "callisto", "enceladus", "rhea", "tethys", "titania", "oberon", "ariel")) {
            table.put(body, icyLayer);
        }
        for (String body : List.of("venus", "io")) {
            table.put(body, volcanicLayer);
        }
        table.put("asteroids", endLayer);
        table.put("ceres", commonLayer);
        for (String body : List.of("titan", "dione", "mimas", "umbriel", "miranda", "triton")) {
            table.put(body, volatileLayer);
        }
        for (String body : List.of("pluto", "charon", "eris", "makemake", "haumea")) {
            table.put(body, perfectLayer);
        }
        return Map.copyOf(table);
    }
}
