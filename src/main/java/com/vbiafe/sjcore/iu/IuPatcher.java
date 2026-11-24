package com.vbiafe.sjcore.iu;

import com.denfop.api.Recipes;
import com.denfop.api.recipe.BaseFluidMachineRecipe;
import com.denfop.api.recipe.BaseMachineRecipe;
import com.denfop.api.recipe.IBaseRecipe;
import com.denfop.api.recipe.IInputFluid;
import com.denfop.api.recipe.IRecipes;
import com.denfop.api.recipe.Input;
import com.denfop.api.recipe.InputFluid;
import com.denfop.api.recipe.IRecipeInputStack;
import com.denfop.api.recipe.RecipeArrayList;
import com.denfop.api.recipe.RecipeInputStack;
import com.denfop.api.recipe.RecipeOutput;
import com.denfop.api.recipe.RecipesFluidCore;
import com.denfop.recipe.IInputItemStack;
import com.denfop.recipe.InputItemStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vbiafe.sjcore.SJCore;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.fluids.FluidStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 把 {@code config/sinkjourneycore/iu_patches.json} 里的补丁施加到 IU 的机器配方池。
 *
 * 为什么不走 KubeJS：IU 的机器配方是 Java 内存结构，KubeJS 侧只能靠桥模组在
 * {@code ServerEvents.loaded} 里直接改内存，每次 /reload 脚本都会再跑一遍，
 * 只能靠“先删后加”兜住重复条目。这里在 IU 的 TagsUpdatedEvent 之后统一重放一次，
 * 并且“加”之前先把同输入同产物的旧条目清掉，所以重复触发是安全的。
 *
 * 配方分两类：物品池（{@code RecipesCore.map_recipes}）和流体池
 * （{@code RecipesFluidCore.map_recipes_fluid}）。补丁里有流体产物、或者只有流体输入时走流体池，
 * 物品 + 流体混合输入而产物是物品时走“物品池 + 辅助流体输入”。
 */
public final class IuPatcher {

    private static final String PATCH_FILE_NAME = "iu_patches.json";

    /**
     * 第一次改某个池子之前拍下的原始内容。
     *
     * IU 的配方 bootstrap 每个 JVM 只跑一次（{@code IUCore.getore} 里的 register 标志），
     * 所以 /reload 不会重建配方池：不管谁改过，改动都会一直留到重启。有了快照，
     * {@code /sjcore iu reload} 就能先还原再重放，补丁的增删改都不需要重启游戏。
     */
    /**
     * 有些流体机器的输出罐数量是写死的，配方产物个数必须对上，否则机器在 update() 里
     * 直接 {@code output_fluid.get(i)} 越界。这里只登记已经确认过的池子，用来提前警告。
     */
    private static final Map<String, Integer> REQUIRED_FLUID_OUTPUTS = Map.of(
            "imp_oil_refiner", 2,
            "oil_refiner", 2,
            "adv_oil_refiner", 2);

    private static final Map<String, List<BaseMachineRecipe>> ITEM_BASE = new HashMap<>();
    private static final Map<String, List<BaseFluidMachineRecipe>> FLUID_BASE = new HashMap<>();

    private IuPatcher() {
    }

    /** 补丁目录：优先 kubejs/sjcore（跟整合包脚本放一起），没有 kubejs 目录时退回 config/sinkjourneycore。 */
    public static Path patchDir() {
        Path kubejs = FMLPaths.GAMEDIR.get().resolve("kubejs");
        if (Files.isDirectory(kubejs)) {
            return kubejs.resolve("sjcore");
        }
        return FMLPaths.CONFIGDIR.get().resolve(SJCore.MODID);
    }

    public static synchronized void applyAll() {
        if (Recipes.recipes == null) {
            SJCore.LOGGER.warn("[SJCore] IU 配方系统尚未初始化，本轮补丁跳过");
            return;
        }

        List<JsonObject> patches;
        try {
            patches = loadPatches();
        } catch (Exception e) {
            SJCore.LOGGER.error("[SJCore] 读取 {} 失败：{}", patchDir(), e.toString());
            return;
        }

        // 1. 先给这次要动的池子拍快照（只拍一次，取的是补丁施加前的原始状态）
        Set<String> touched = new LinkedHashSet<>();
        for (JsonObject patch : patches) {
            String pool = string(patch, "pool", "");
            if (!pool.isEmpty()) {
                touched.add(pool);
            }
        }
        for (String pool : touched) {
            snapshot(pool);
        }

        // 2. 把之前改过的池子还原成原始状态，再按当前配置重新施加
        int restored = 0;
        for (String pool : ITEM_BASE.keySet()) {
            if (restoreItem(pool)) {
                restored++;
            }
        }
        for (String pool : FLUID_BASE.keySet()) {
            restoreFluid(pool);
        }

        // 3. 逐条施加
        int applied = 0;
        int removed = 0;
        int added = 0;
        int failed = 0;

        for (JsonObject patch : patches) {
            String op = string(patch, "op", "add");
            String pool = string(patch, "pool", "");
            String file = string(patch, "_file", "?");
            if (pool.isEmpty() || !poolExists(pool)) {
                SJCore.LOGGER.warn("[SJCore] 未知配方池 '{}'（{}），跳过补丁（用 /sjcore iu pools 查池名）", pool, file);
                failed++;
                continue;
            }

            try {
                int removedHere = 0;
                int addedHere = 0;
                switch (op) {
                    case "clear" -> removedHere = clearPool(pool);
                    case "remove" -> removedHere = removeOutputs(pool, patch);
                    case "add" -> addedHere = addRecipe(pool, patch);
                    case "replace" -> {
                        removedHere = removeOutputs(pool, patch);
                        addedHere = addRecipe(pool, patch);
                    }
                    default -> {
                        SJCore.LOGGER.warn("[SJCore] 未知 op '{}'（pool={}，{}），跳过", op, pool, file);
                        failed++;
                        continue;
                    }
                }
                removed += removedHere;
                added += addedHere;
                applied++;
                SJCore.LOGGER.info("[SJCore] {} {}（{}）：移除 {} / 新增 {}", op, pool, file, removedHere, addedHere);
            } catch (Exception e) {
                SJCore.LOGGER.error("[SJCore] 补丁失败（{} / {}，{}）：{}", op, pool, file, e.toString());
                failed++;
            }
        }

        SJCore.LOGGER.info("[SJCore] IU 配方补丁：{} 条生效（移除 {} / 新增 {}），{} 条失败；还原 {} 个配方池为原始状态",
                applied, removed, added, failed, restored);
    }

    /** /sjcore iu pools */
    public static List<String> listPools() {
        IRecipes recipes = Recipes.recipes;
        if (recipes == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(recipes.getMap_recipe_managers());
        RecipesFluidCore fluid = fluidCore();
        if (fluid != null) {
            for (String name : fluid.map_recipe_managers.keySet()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /** /sjcore iu recipes &lt;pool&gt; */
    public static List<String> describePool(String pool) {
        List<String> lines = new ArrayList<>();
        IRecipes recipes = Recipes.recipes;
        if (recipes == null) {
            return lines;
        }

        IBaseRecipe manager = recipes.getRecipe(pool);
        if (manager != null) {
            List<BaseMachineRecipe> list = recipes.getRecipeList(pool);
            lines.add(String.format("[物品] %s（槽位 %d，消耗 %s）= %d 条", pool, manager.getSize(),
                    manager.consume(), list == null ? 0 : list.size()));
            if (list != null) {
                for (BaseMachineRecipe recipe : list) {
                    StringBuilder sb = new StringBuilder("  ");
                    List<IInputItemStack> inputs = recipe.input.getInputs();
                    for (int i = 0; i < inputs.size(); i++) {
                        if (i > 0) {
                            sb.append(" + ");
                        }
                        sb.append(firstStack(inputs.get(i)));
                    }
                    sb.append(" -> ");
                    ItemStack[] items = recipe.output.items.toArray(new ItemStack[0]);
                    for (int i = 0; i < items.length; i++) {
                        if (i > 0) {
                            sb.append(" + ");
                        }
                        sb.append(name(items[i]));
                    }
                    lines.add(sb.toString());
                }
            }
        }

        RecipesFluidCore fluid = fluidCore();
        if (fluid != null && fluid.map_recipe_managers.containsKey(pool)) {
            List<BaseFluidMachineRecipe> list = fluid.getRecipeList(pool);
            lines.add(String.format("[流体] %s = %d 条", pool, list == null ? 0 : list.size()));
            if (list != null) {
                for (BaseFluidMachineRecipe recipe : list) {
                    lines.add("  " + describeFluid(recipe));
                }
            }
        }

        if (lines.isEmpty()) {
            lines.add("未知配方池：" + pool);
        }
        return lines;
    }

    private static boolean poolExists(String pool) {
        if (Recipes.recipes.getRecipe(pool) != null) {
            return true;
        }
        RecipesFluidCore fluid = fluidCore();
        return fluid != null && fluid.map_recipe_managers.containsKey(pool);
    }

    private static RecipesFluidCore fluidCore() {
        IRecipes recipes = Recipes.recipes;
        if (recipes == null) {
            return null;
        }
        try {
            return recipes.getRecipeFluid();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<JsonObject> loadPatches() throws IOException {
        Path dir = patchDir();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            Files.createDirectories(dir);
            Path file = dir.resolve(PATCH_FILE_NAME);
            Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
            SJCore.LOGGER.info("[SJCore] 已生成默认补丁文件 {}", file);
            files.add(file);
        }

        List<JsonObject> patches = new ArrayList<>();
        for (Path file : files) {
            JsonObject root;
            try {
                root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                SJCore.LOGGER.error("[SJCore] 解析 {} 失败：{}", file, e.toString());
                continue;
            }
            JsonElement element = root.get("patches");
            if (element == null || !element.isJsonArray()) {
                continue;
            }
            for (JsonElement entry : element.getAsJsonArray()) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject patch = entry.getAsJsonObject();
                if (patch.has("enabled") && !patch.get("enabled").getAsBoolean()) {
                    continue;
                }
                patch.addProperty("_file", file.getFileName().toString());
                patches.add(patch);
            }
        }
        SJCore.LOGGER.info("[SJCore] 从 {} 读取 {} 条补丁（{} 个文件）", dir, patches.size(), files.size());
        return patches;
    }

    // ---------------------------------------------------------------- 清池

    /** op=clear：整池清空（列表原地清空，快照仍然能还原）。 */
    private static int clearPool(String pool) {
        int cleared = 0;
        List<BaseMachineRecipe> items = Recipes.recipes.getRecipeList(pool);
        if (items != null) {
            cleared += items.size();
            items.clear();
            RecipeArrayList<IRecipeInputStack> index = Recipes.recipes.getMap_recipe_managers_itemStack(pool);
            if (index != null) {
                index.clear();
            }
        }
        RecipesFluidCore fluid = fluidCore();
        if (fluid != null) {
            List<BaseFluidMachineRecipe> recipes = fluid.getRecipeList(pool);
            if (recipes != null) {
                cleared += recipes.size();
                recipes.clear();
            }
        }
        return cleared;
    }

    // ---------------------------------------------------------------- 快照 / 还原

    private static void snapshot(String pool) {
        if (!ITEM_BASE.containsKey(pool)) {
            List<BaseMachineRecipe> list = Recipes.recipes.getRecipeList(pool);
            if (list != null) {
                ITEM_BASE.put(pool, new ArrayList<>(list));
            }
        }
        RecipesFluidCore fluid = fluidCore();
        if (fluid != null && !FLUID_BASE.containsKey(pool)) {
            List<BaseFluidMachineRecipe> list = fluid.getRecipeList(pool);
            if (list != null) {
                FLUID_BASE.put(pool, new ArrayList<>(list));
            }
        }
    }

    private static boolean restoreItem(String pool) {
        List<BaseMachineRecipe> base = ITEM_BASE.get(pool);
        List<BaseMachineRecipe> current = Recipes.recipes.getRecipeList(pool);
        if (base == null || current == null) {
            return false;
        }
        current.clear();
        current.addAll(base);
        rebuildItemIndex(pool, current);
        return true;
    }

    private static void restoreFluid(String pool) {
        RecipesFluidCore fluid = fluidCore();
        List<BaseFluidMachineRecipe> base = FLUID_BASE.get(pool);
        if (fluid == null || base == null) {
            return;
        }
        List<BaseFluidMachineRecipe> current = fluid.getRecipeList(pool);
        if (current == null) {
            return;
        }
        current.clear();
        current.addAll(base);
    }

    /** 池子内容被整体替换后，把 map_recipe_managers_itemStack 这个索引一起重建。 */
    private static void rebuildItemIndex(String pool, List<BaseMachineRecipe> list) {
        RecipeArrayList<IRecipeInputStack> index = Recipes.recipes.getMap_recipe_managers_itemStack(pool);
        if (index == null) {
            return;
        }
        index.clear();
        for (BaseMachineRecipe recipe : list) {
            if (recipe.input == null) {
                continue;
            }
            for (IInputItemStack input : recipe.input.getInputs()) {
                index.add(new RecipeInputStack(input));
            }
        }
    }

    // ---------------------------------------------------------------- 删除

    private static int removeOutputs(String pool, JsonObject patch) {
        Out parsed = Out.parse(patch.get("outputs"));
        int removed = 0;

        if (!parsed.items.isEmpty() && Recipes.recipes.getRecipeList(pool) != null) {
            removed += shrinkItemPool(pool, parsed.items.toArray(new ItemStack[0]));
        }

        RecipesFluidCore fluid = fluidCore();
        if (fluid != null && fluid.map_recipe_managers.containsKey(pool)) {
            List<BaseFluidMachineRecipe> before = fluid.getRecipeList(pool);
            int sizeBefore = before == null ? 0 : before.size();
            for (ItemStack stack : parsed.items) {
                fluid.removeAllRecipe(pool, true, stack);
            }
            for (FluidStack stack : parsed.fluids) {
                fluid.removeAllRecipe(pool, true, stack);
            }
            List<BaseFluidMachineRecipe> after = fluid.getRecipeList(pool);
            int sizeAfter = after == null ? 0 : after.size();
            removed += Math.max(0, sizeBefore - sizeAfter);
        }

        return removed;
    }

    private static int shrinkItemPool(String pool, ItemStack[] outputs) {
        List<BaseMachineRecipe> list = Recipes.recipes.getRecipeList(pool);
        int before = list == null ? 0 : list.size();
        Recipes.recipes.removeAllRecipe(pool, true, new RecipeOutput(new CompoundTag(), outputs));
        List<BaseMachineRecipe> after = Recipes.recipes.getRecipeList(pool);
        int now = after == null ? 0 : after.size();
        return Math.max(0, before - now);
    }

    // ---------------------------------------------------------------- 新增

    private static int addRecipe(String pool, JsonObject patch) {
        In slot = In.parse(patch.get("inputs"));
        Out out = Out.parse(patch.get("outputs"));
        if ((slot.items.isEmpty() && slot.fluids.isEmpty()) || (out.items.isEmpty() && out.fluids.isEmpty())) {
            SJCore.LOGGER.warn("[SJCore] 配方池 '{}' 的补丁缺少 inputs 或 outputs，跳过", pool);
            return 0;
        }

        CompoundTag meta = meta(patch);
        RecipesFluidCore fluid = fluidCore();
        boolean fluidPool = fluid != null && fluid.map_recipe_managers.containsKey(pool);
        boolean fluidMachine = fluidPool && (!out.fluids.isEmpty() || (slot.items.isEmpty() && !slot.fluids.isEmpty()));

        if (fluidMachine) {
            return addFluidRecipe(pool, fluid, slot, out, meta);
        }
        if (!slot.fluids.isEmpty()) {
            return addItemRecipeWithFluid(pool, slot, out, meta);
        }
        return addPlainItemRecipe(pool, slot, out, meta);
    }

    private static int addPlainItemRecipe(String pool, In in, Out out, CompoundTag meta) {
        if (out.fluids.size() > 0) {
            SJCore.LOGGER.warn("[SJCore] 配方池 '{}' 是物品池，但给了流体产物；流体产物只能进流体池", pool);
        }
        dedupeItem(pool, in.items.get(0), out.items.get(0));
        Input input = new Input(in.items.toArray(new IInputItemStack[0]));
        RecipeOutput output = new RecipeOutput(meta, out.items.toArray(new ItemStack[0]));
        Recipes.recipes.addRecipe(pool, new BaseMachineRecipe(input, output));
        return 1;
    }

    private static int addItemRecipeWithFluid(String pool, In in, Out out, CompoundTag meta) {
        // 物品池里“物品 + 辅助流体”的形态（IU 里叫 Input(FluidStack, itemStack...)）
        dedupeItem(pool, in.items.get(0), out.items.get(0));
        Input input = new Input(in.fluids.get(0), in.items.toArray(new IInputItemStack[0]));
        RecipeOutput output = new RecipeOutput(meta, out.items.toArray(new ItemStack[0]));
        Recipes.recipes.addRecipe(pool, new BaseMachineRecipe(input, output));
        return 1;
    }

    private static int addFluidRecipe(String pool, RecipesFluidCore fluid, In in, Out out, CompoundTag meta) {
        if (fluid == null) {
            SJCore.LOGGER.warn("[SJCore] 流体配方系统未初始化，跳过 '{}'", pool);
            return 0;
        }
        if (in.items.size() > 1) {
            SJCore.LOGGER.warn("[SJCore] 流体池 '{}' 只认第一个物品输入，多余的 {} 个被忽略",
                    pool, in.items.size() - 1);
        }
        IInputFluid input = in.items.isEmpty()
                ? new InputFluid(in.fluids.toArray(new FluidStack[0]))
                : new InputFluid(in.items.get(0), in.fluids.toArray(new FluidStack[0]));

        dedupeFluid(pool, fluid, input, out);

        BaseFluidMachineRecipe recipe;
        if (!out.items.isEmpty()) {
            RecipeOutput output = new RecipeOutput(meta, out.items.toArray(new ItemStack[0]));
            recipe = out.fluids.isEmpty()
                    ? new BaseFluidMachineRecipe(input, output)
                    : new BaseFluidMachineRecipe(input, output, out.fluids);
        } else {
            recipe = new BaseFluidMachineRecipe(input, out.fluids);
        }
        Integer required = REQUIRED_FLUID_OUTPUTS.get(pool);
        if (required != null && out.fluids.size() != required) {
            SJCore.LOGGER.warn("[SJCore] '{}' 的机器有 {} 个输出罐，这条配方给了 {} 个流体产物，"
                    + "机器匹配到会越界而不工作", pool, required, out.fluids.size());
        }
        fluid.addRecipe(pool, recipe);
        return 1;
    }

    private static CompoundTag meta(JsonObject patch) {
        CompoundTag meta = new CompoundTag();
        double energy = number(patch, "energy", 0);
        if (energy > 0) {
            meta.putDouble("energy", energy);
        }
        int temperature = (int) number(patch, "temperature", 0);
        if (temperature > 0) {
            meta.putShort("temperature", (short) temperature);
        }
        JsonElement extra = patch.get("meta");
        if (extra != null && extra.isJsonObject()) {
            meta.merge(compound(extra.getAsJsonObject()));
        }
        return meta;
    }

    // ---------------------------------------------------------------- 去重

    private static void dedupeItem(String pool, IInputItemStack input, ItemStack output) {
        List<BaseMachineRecipe> list = Recipes.recipes.getRecipeList(pool);
        if (list == null) {
            return;
        }
        ItemStack wantedIn = input.getInputs().isEmpty() ? ItemStack.EMPTY : input.getInputs().get(0);
        list.removeIf(recipe -> {
            if (recipe.input == null || recipe.input.getInputs().isEmpty() || recipe.output == null) {
                return false;
            }
            ItemStack in = recipe.input.getInputs().get(0).getInputs().isEmpty()
                    ? ItemStack.EMPTY
                    : recipe.input.getInputs().get(0).getInputs().get(0);
            ItemStack out = recipe.output.items.isEmpty() ? ItemStack.EMPTY : recipe.output.items.get(0);
            return sameStack(in, wantedIn) && sameStack(out, output);
        });
    }

    private static void dedupeFluid(String pool, RecipesFluidCore fluid, IInputFluid input, Out out) {
        List<BaseFluidMachineRecipe> list = fluid.getRecipeList(pool);
        if (list == null) {
            return;
        }
        FluidStack wantedIn = input.getInputs().isEmpty() ? FluidStack.EMPTY : input.getInputs().get(0);
        FluidStack wantedOutFluid = out.fluids.isEmpty() ? FluidStack.EMPTY : out.fluids.get(0);
        ItemStack wantedOutItem = out.items.isEmpty() ? ItemStack.EMPTY : out.items.get(0);
        list.removeIf(recipe -> {
            if (recipe.input == null) {
                return false;
            }
            FluidStack in = recipe.input.getInputs().isEmpty() ? FluidStack.EMPTY : recipe.input.getInputs().get(0);
            FluidStack outFluid = recipe.output_fluid == null || recipe.output_fluid.isEmpty()
                    ? FluidStack.EMPTY
                    : recipe.output_fluid.get(0);
            RecipeOutput output = recipe.getOutput();
            ItemStack outItem = output == null || output.items.isEmpty() ? ItemStack.EMPTY : output.items.get(0);
            return sameFluid(in, wantedIn)
                    && sameFluid(outFluid, wantedOutFluid)
                    && sameStack(outItem, wantedOutItem);
        });
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty();
        }
        return a.getItem() == b.getItem() && a.getCount() == b.getCount();
    }

    private static boolean sameFluid(FluidStack a, FluidStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty();
        }
        return a.getFluid() == b.getFluid() && a.getAmount() == b.getAmount();
    }

    // ---------------------------------------------------------------- 解析

    /** 输入槽：物品（含标签）与流体分开存。 */
    private record In(List<IInputItemStack> items, List<FluidStack> fluids) {

        static In parse(JsonElement element) {
            List<IInputItemStack> items = new ArrayList<>();
            List<FluidStack> fluids = new ArrayList<>();
            for (JsonObject slot : slots(element)) {
                String fluidId = string(slot, "fluid", "");
                if (!fluidId.isEmpty()) {
                    FluidStack stack = fluidStack(fluidId, (int) Math.max(1, number(slot, "amount", 1000)));
                    if (stack != null) {
                        fluids.add(stack);
                    }
                    continue;
                }
                int count = (int) Math.max(1, number(slot, "count", 1));
                String tag = string(slot, "tag", "");
                if (!tag.isEmpty()) {
                    items.add(Recipes.inputFactory.getInput(tag, count));
                    continue;
                }
                ItemStack stack = itemStack(string(slot, "item", ""), count);
                if (stack != null) {
                    items.add(new InputItemStack(stack));
                }
            }
            return new In(items, fluids);
        }
    }

    /** 输出槽：物品与流体分开存。 */
    private record Out(List<ItemStack> items, List<FluidStack> fluids) {

        static Out parse(JsonElement element) {
            List<ItemStack> items = new ArrayList<>();
            List<FluidStack> fluids = new ArrayList<>();
            for (JsonObject slot : slots(element)) {
                String fluidId = string(slot, "fluid", "");
                if (!fluidId.isEmpty()) {
                    FluidStack stack = fluidStack(fluidId, (int) Math.max(1, number(slot, "amount", 1000)));
                    if (stack != null) {
                        fluids.add(stack);
                    }
                    continue;
                }
                int count = (int) Math.max(1, number(slot, "count", 1));
                ItemStack stack = itemStack(string(slot, "item", ""), count);
                if (stack != null) {
                    items.add(stack);
                }
            }
            return new Out(items, fluids);
        }
    }

    private static List<JsonObject> slots(JsonElement element) {
        List<JsonObject> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return result;
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonObject()) {
                result.add(entry.getAsJsonObject());
            }
        }
        return result;
    }

    private static ItemStack itemStack(String id, int count) {
        if (id.isEmpty()) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            SJCore.LOGGER.warn("[SJCore] 物品 id 非法：{}", id);
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(location);
        if (item == null || item == Items.AIR) {
            SJCore.LOGGER.warn("[SJCore] 未注册的物品 id：{}", id);
            return null;
        }
        return new ItemStack(item, count);
    }

    private static FluidStack fluidStack(String id, int amount) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            SJCore.LOGGER.warn("[SJCore] 流体 id 非法：{}", id);
            return null;
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(location);
        if (fluid == null || fluid == Fluids.EMPTY) {
            SJCore.LOGGER.warn("[SJCore] 未注册的流体 id：{}", id);
            return null;
        }
        return new FluidStack(fluid, amount);
    }

    private static CompoundTag compound(JsonObject json) {
        CompoundTag tag = new CompoundTag();
        for (String key : json.keySet()) {
            JsonElement value = json.get(key);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                tag.putDouble(key, value.getAsDouble());
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                tag.putBoolean(key, value.getAsBoolean());
            } else if (value.isJsonPrimitive()) {
                tag.putString(key, value.getAsString());
            }
        }
        return tag;
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static double number(JsonObject json, String key, double fallback) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
    }

    private static String firstStack(IInputItemStack input) {
        List<ItemStack> stacks = input.getInputs();
        return stacks.isEmpty() ? "?" : name(stacks.get(0));
    }

    private static String name(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key == null ? "?" : key.toString();
        return stack.getCount() == 1 ? id : stack.getCount() + "x " + id;
    }

    private static String fluidName(FluidStack stack) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        String id = key == null ? "?" : key.toString();
        return stack.getAmount() + "mB " + id;
    }

    private static String describeFluid(BaseFluidMachineRecipe recipe) {
        StringBuilder sb = new StringBuilder();
        if (recipe.input.getStack() != null) {
            sb.append(firstStack(recipe.input.getStack())).append(" + ");
        }
        List<FluidStack> inputs = recipe.input.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                sb.append(" + ");
            }
            sb.append(fluidName(inputs.get(i)));
        }
        sb.append(" -> ");
        boolean first = true;
        RecipeOutput output = recipe.getOutput();
        if (output != null && !output.items.isEmpty()) {
            for (ItemStack stack : output.items) {
                if (!first) {
                    sb.append(" + ");
                }
                sb.append(name(stack));
                first = false;
            }
        }
        if (recipe.output_fluid != null) {
            for (FluidStack stack : recipe.output_fluid) {
                if (!first) {
                    sb.append(" + ");
                }
                sb.append(fluidName(stack));
                first = false;
            }
        }
        return sb.toString();
    }

    private static final String DEFAULT_FILE = """
            {
              "_readme": "IU 机器配方补丁。op: clear / remove / add / replace；pool 用 /sjcore iu pools 查。inputs/outputs 支持 {item:...} / {tag:...}（带 count）与 {fluid:..., amount: mB}。改完在游戏里执行 /sjcore iu reload。",
              "patches": [
                {
                  "enabled": false,
                  "op": "clear",
                  "pool": "oil_refiner"
                },
                {
                  "enabled": false,
                  "op": "remove",
                  "pool": "anvil",
                  "outputs": [
                    { "item": "industrialupgrade:itemplates/iron_plate", "count": 1 }
                  ]
                },
                {
                  "enabled": false,
                  "op": "add",
                  "pool": "gearing",
                  "inputs": [
                    { "tag": "c:ingots/bronze", "count": 4 }
                  ],
                  "outputs": [
                    { "item": "kubejs:bronze_gear", "count": 1 }
                  ]
                },
                {
                  "enabled": false,
                  "op": "replace",
                  "pool": "fluid_integrator",
                  "inputs": [
                    { "fluid": "industrialupgrade:iufluidsteam", "amount": 1000 },
                    { "item": "industrialupgrade:itemplates/iron_plate", "count": 1 }
                  ],
                  "outputs": [
                    { "fluid": "industrialupgrade:iufluiddistilled_water", "amount": 500 }
                  ]
                }
              ]
            }
            """;
}
