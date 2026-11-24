#!/usr/bin/env bash
# 离线构建 Sink Journey Core。
#
# 这台机器访问不到 maven.neoforged.net，ModDevGradle 拉不到 NeoForge userdev，
# 所以这里直接用整合包里现成的 jar 当 classpath：客户端 srg jar（Mojang 官方名）
# + neoforge universal jar + Industrial Upgrade jar。产物与 Gradle 构建一致。
#
# 用法：
#   tools/build-offline.sh                       # 默认读 C:/Users/Administrator/pcl/.minecraft
#   SJ_GAME_DIR=/d/mc tools/build-offline.sh     # 指定游戏目录
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GAME="${SJ_GAME_DIR:-C:/Users/Administrator/pcl/.minecraft}"
NEO_VERSION="${NEO_VERSION:-21.1.265}"
MC_DIR="${MC_DIR:-$GAME/libraries/net/minecraft/client/1.21.1-20240808.144430}"

# 注意：同目录下还有 client-*-extra.jar（只有 assets），必须挑 -srg 的那个
MC_JAR="$(ls "$MC_DIR"/client-*-srg.jar | head -1)"
NEO_DIR="$GAME/libraries/net/neoforged/neoforge/$NEO_VERSION"
NEO_JAR="$NEO_DIR/neoforge-$NEO_VERSION-universal.jar"
# NeoForge 的 client jar 里是被补丁过的官方名类：BlockEntityType$BlockEntitySupplier 之类的
# 内部类型在那里才是 public（MMCR 的端口 API 直接暴露它）。它只包含被补丁的类，
# 所以 javac 的 classpath 要把它放在最前面，其余类回落到 srg jar。
NEO_CLIENT_JAR="$NEO_DIR/neoforge-$NEO_VERSION-client.jar"
# 只认本整合包版本目录里的 IU（其它版本目录里还有别的 IU 构建，别混用）
SJ_VERSION_DIR="${SJ_VERSION_DIR:-Sink Journey}"
IU_JAR="$(ls "$GAME/versions/$SJ_VERSION_DIR/mods/"*IndustrialUpgrade-*.jar 2>/dev/null | head -1 || true)"
# MMCR（模块化机械）用于分级输入仓：方块 / 方块实体 / 端口种类都复用它的注册表
MMCR_JAR="$(ls "$GAME/versions/$SJ_VERSION_DIR/mods/"mmcr-*.jar 2>/dev/null | head -1 || true)"

for jar in "$MC_JAR" "$NEO_JAR" "$NEO_CLIENT_JAR" "$IU_JAR" "$MMCR_JAR"; do
    [[ -f "$jar" ]] || { echo "缺少依赖：$jar" >&2; exit 1; }
done

# 把依赖硬链接/复制到一个目录，用 javac 的通配符 classpath（命令行长度有限制，
# 直接拼几百个 jar 会超限）。排除 1.20.x 客户端 jar 与其它 NeoForge 版本。
CP_DIR="$ROOT/build/cp"
rm -rf "$ROOT/build"
mkdir -p "$CP_DIR" "$ROOT/build/libs"

link() {
    src="$1"
    dst="$CP_DIR/$(basename "$src")"
    ln -f "$src" "$dst" 2>/dev/null || cp -n "$src" "$dst"
}

link "$MC_JAR"
link "$NEO_CLIENT_JAR"
link "$NEO_JAR"
link "$IU_JAR"
link "$MMCR_JAR"

find "$GAME/libraries" -name '*.jar'     -not -path '*/net/minecraft/client/*'     -not -path '*/net/minecraftforge/forge/*'     -not -path '*/net/neoforged/neoforge/*'     | while IFS= read -r jar; do link "$jar"; done

OUT="$ROOT/build/classes"
mkdir -p "$OUT"

# 原生 javac 不认 /c/... 形式的路径，统一转成 Windows 路径；
# 源码用相对路径喂进去，避开 xargs 的路径转义。
CP_DIR_W="$(cygpath -w "$CP_DIR")"
OUT_W="$(cygpath -w "$OUT")"
NEO_CLIENT_W="$(cygpath -w "$NEO_CLIENT_JAR")"

(
    cd "$ROOT/src/main/java"
    find . -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 --release 21 -nowarn -cp "$NEO_CLIENT_W;$CP_DIR_W\*" -d "$OUT_W"
)

cp -r "$ROOT/src/main/resources/." "$OUT/"

JAR_OUT="$ROOT/build/libs/sinkjourneycore-0.1.0.jar"
jar --create --file "$(cygpath -w "$JAR_OUT")" -C "$OUT_W" .
echo "构建完成：$JAR_OUT"
