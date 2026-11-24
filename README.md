# Sink Journey Core

整合包的 Java 侧魔改层。目前两件事：

1. 给 **Industrial Upgrade** 的机器配方池打补丁（见下文「补丁文件」）。
2. 给 **MMCR（模块化机械）** 注册分级仓：按时代各一套物品输入/输出总线、流体输入/输出仓，
   代码在 `src/main/java/com/vbiafe/sjcore/port/SinkPorts.java`，
   玩法说明见 `kubejs/docs/PORT_TIERS.md`。

## 分级输入仓

MMCR 自带的仓只有尺寸分级（微型 1 格 → 超级 32 格），机器的端口要求只有「至少 N 档」，
没有「只能是这个仓」的写法。本模组按时代注册 8 套输入仓（id 在 `mmcr` 命名空间下，
例如 `mmcr:item_input_bus_primitive`），结构脚本里把端口位写成方块 id 即可锁死：

```js
.set('I', 'mmcr:item_input_bus_kinetic')
```

id 必须写成 `item_input_bus_<时代>` 这种「原版仓名 + 后缀」的形式：MMCR 是按 id 前缀
决定右键开哪个界面、查哪套能力的，前缀对不上就会出现「点不开、没标题」。

注册方式复用了 MMCR 自己的 `ItemBusKind` / `FluidHatchKind`（所以 GUI、能力、模型、
JEI 预览都和原版仓一致），但 MMCR 的端口定义在静态初始化时就 freeze 了，
所以方块 / 方块实体 / 物品直接补进它的三张注册表，端口种类与定义反射补进内部表；
这要求本模组在 MMCR 之后构造（`neoforge.mods.toml` 里 `ordering="AFTER"`）。

KubeJS 能改工作台配方，但 IU 的机器配方（挤压机 / 冲孔机 / 铁砧 / 离心机 / 激光台 …）
全部在 Java 内存里，KubeJS 只能靠桥模组在 `ServerEvents.loaded` 里直接改内存，
每跑一次 `/reload` 脚本就会再跑一遍，只能靠「先删后加」兜住重复条目。

本模组改成：等 IU 自己在 `TagsUpdatedEvent` 里重建完配方池，**下一 tick** 统一施加一次补丁。
加配方前先把「同输入 + 同产物」的旧条目清掉，删配方用 `removeAllRecipe`，
所以重复触发是安全的，重载也不会堆叠。

## 构建

本机访问不到 `maven.neoforged.net`，Gradle（ModDevGradle）拉不到 NeoForge userdev，所以有两条路：

```bash
# 离线构建：用整合包里现成的 jar 当 classpath，产物直接进 build/libs/
tools/build-offline.sh

# 有网的机器上走标准 MDK 流程（需要先把 IU jar 放到 libs/industrialupgrade.jar）
./gradlew build
```

离线脚本默认读 `C:/Users/Administrator/pcl/.minecraft`，可用环境变量覆盖：
`SJ_GAME_DIR=/d/mc SJ_VERSION_DIR="Sink Journey" tools/build-offline.sh`。

产物 `build/libs/sinkjourneycore-0.1.0.jar` 复制到整合包的 `mods/` 即可。

## 补丁文件

补丁放在 **`kubejs/sjcore/`** 目录下（和整合包脚本放一起，便于随 KJS 一起提交），
目录里所有 `*.json` 都会被读进来合并；没有 `kubejs` 目录的整合包才退回
`config/sinkjourneycore/`。首次启动若目录为空会生成一份样例 `iu_patches.json`：

```json
{
  "patches": [
    { "op": "remove", "pool": "anvil", "outputs": [ { "item": "industrialupgrade:itemplates/iron_plate", "count": 1 } ] },
    { "op": "add", "pool": "gearing",
      "inputs":  [ { "tag": "c:ingots/bronze", "count": 4 } ],
      "outputs": [ { "item": "kubejs:bronze_gear", "count": 1 } ] }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `enabled` | 省略即启用；写 `false` 可留着不生效 |
| `op` | `clear` 整池清空；`remove` 删产物匹配的条目；`add` 加一条；`replace` 先删后加 |
| `pool` | 配方池名，用 `/sjcore iu pools` 查 |
| `inputs` | 每项 `{item}` 或 `{tag}`，可带 `count`（默认 1） |
| `outputs` | 同上；`remove` / `replace` 用它匹配要删的产物 |
| `energy` / `temperature` | 可选，写进配方元数据（分子变换器等机器读 `energy`） |
| `meta` | 可选，任意 NBT 键值（数字 / 布尔 / 字符串） |

改完不用重启：游戏里 `/sjcore iu reload` 立刻重新施加。多条产线可以拆成多个文件，
例如现在已经有的 `kubejs/sjcore/oil_line.json`（石油线的 IU 侧）。

### 为什么这里能不重启

IU 的配方 bootstrap 每个 JVM 只跑一次（`IUCore.getore` 开头的 `register` 标志），
所以 `/reload` 之后 IU **不会**重建自己的配方池——不管谁改过池子（删一条、加一条），改动都会一直留到重启。
这就是为什么桥模组做的封禁，把 JS 删掉再 `/reload` 也撤不回来。

本模组在第一次动某个池子之前把它的原始内容存下来（`ITEM_BASE` / `FLUID_BASE`），
每次施加补丁前先把这些池子整体还原成快照、再按当前 JSON 重放。
于是：

| 操作 | 要不要重启 |
|---|---|
| 换 / 加 / 卸 mod jar | **要**，和 kubejs_iu 一样 |
| 改 `iu_patches.json`（加、改、删补丁，包括封禁） | 不要，`/sjcore iu reload` |
| 撤销一条封禁 | 不要，删掉那条 JSON 再 reload 就恢复原配方 |

前提：**这些池子别再让 KubeJS 桥同时改**，否则快照还原会把桥那边加的东西一起抹掉。

## 流体配方

流体槽写成 `{ "fluid": "<id>", "amount": <mB> }`，可以和物品槽混在同一个 `inputs` / `outputs` 里。
IU 的流体注册名是 `industrialupgrade:iufluid<名字>`，例如 `industrialupgrade:iufluidsteam`、
`industrialupgrade:iufluiddistilled_water`、`industrialupgrade:iufluidcoolant`。

```json
{
  "op": "replace",
  "pool": "fluid_integrator",
  "inputs":  [ { "fluid": "industrialupgrade:iufluidsteam", "amount": 1000 },
               { "item": "industrialupgrade:itemplates/iron_plate", "count": 1 } ],
  "outputs": [ { "fluid": "industrialupgrade:iufluiddistilled_water", "amount": 500 } ]
}
```

IU 有 **两套配方池**：物品池（`RecipesCore.map_recipes`）和流体池（`RecipesFluidCore`，
共 42 个：`fluid_integrator`、`fluid_separator`、`fluid_mixer`、`electrolyzer`、`smeltery`、
`ingot_casting`、`oil_refiner`、`refrigerator`、`heat`…）。补丁按内容自动选路：

| 补丁内容 | 落到哪 |
|---|---|
| 只有物品 | 物品池，`BaseMachineRecipe(Input(items), RecipeOutput)` |
| 物品输入 + 流体产物 / 只有流体输入（且该池是流体池） | 流体池，`BaseFluidMachineRecipe` |
| 物品 + 流体混合输入、产物是物品 | 物品池，流体作为辅助输入（`Input(FluidStack, items...)`） |

流体池的删除按流体匹配（`RecipesFluidCore.removeAllRecipe(pool, true, FluidStack)`），
物品池按物品匹配；同一个池名在两边都存在时会各删一次。

## 命令

```
/sjcore iu reload            重新读配置并施加
/sjcore iu pools             列出全部配方池（115 个）
/sjcore iu recipes <pool>    打印某个池的现有配方
```

## 从 KubeJS 迁过来

同一件事**不要两边都做**，否则 JEI 里会出现重复条目。迁移时先停用对应的 JS：

| 现在的 KubeJS | 迁成 JSON 补丁 |
|---|---|
| `bans/iu_anvil_ban.js` 里的 `iu.removeByOutput('anvil', …)` | `{"op":"remove","pool":"anvil","outputs":[…]}` |
| `industrial/if_gears.js` 的 `BlockEntityGearMachine.addrecipe(...)` | `{"op":"add","pool":"gearing","inputs":[{"tag":…,"count":4}],"outputs":[…]}` |
| `silicon/iu_crystal.js` 的 `iu.addItemRecipe(...)` | `{"op":"replace","pool":"silicon_recipe","inputs":[…],"outputs":[…]}` |

IU 数据包里的工作台配方（`industrialupgrade:shaped_recipe`）不归这里管，仍然留在 KubeJS。
