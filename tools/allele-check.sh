#!/usr/bin/env bash
# 脱离游戏验证交配规则：AlleleMath 是纯整数运算，不需要 Minecraft。
#
#   tools/allele-check.sh
#
# 检查四件事：
#   1. 双隐性保底：后代显性 >= min(cap, 好亲本显性 + 1)，穷举所有 cap/亲本组合；
#   2. 不变量：20 万次随机交配，显性/隐性都落在 [0, cap]，阶梯轴隐性不超过显性 + 2；
#   3. 类别轴只继承父母实际携带的基因，不升档；
#   4. 阶梯轴不会倒退到差于双亲中较好的那个显性。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIO="$ROOT/src/main/java/com/vbiafe/sjcore/bio"
OUT="$ROOT/build/allele-check"
rm -rf "$OUT"
mkdir -p "$OUT"

cat > "$OUT/Check.java" <<'EOF'
import com.vbiafe.sjcore.bio.AlleleMath;
import com.vbiafe.sjcore.bio.Rng;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class Check {
    static int failures = 0;

    static void check(boolean cond, String what) {
        if (!cond) {
            failures++;
            if (failures < 20) {
                System.out.println("FAIL " + what);
            }
        }
    }

    static Rng forceRecessive() {
        return new Rng() {
            public int nextInt(int bound) { return 0; }
            public boolean nextBoolean() { return true; }
        };
    }

    public static void main(String[] args) {
        for (int cap = 1; cap <= 5; cap++) {
            for (int a = 0; a <= cap; a++) {
                for (int b = 0; b <= cap; b++) {
                    AlleleMath.Pair child = AlleleMath.crossLadder(
                            new AlleleMath.Pair(a, cap), new AlleleMath.Pair(b, cap), cap, forceRecessive());
                    int floor = Math.min(cap, Math.max(a, b) + 1);
                    check(child.dominant() >= floor, "guarantee cap=" + cap + " a=" + a + " b=" + b);
                }
            }
        }

        Random random = new Random(20261124L);
        Rng rng = Rng.of(random);
        for (int i = 0; i < 200_000; i++) {
            int cap = 1 + random.nextInt(5);
            AlleleMath.Pair a = new AlleleMath.Pair(random.nextInt(cap + 1), random.nextInt(cap + 1));
            AlleleMath.Pair b = new AlleleMath.Pair(random.nextInt(cap + 1), random.nextInt(cap + 1));
            boolean ladder = random.nextBoolean();
            AlleleMath.Pair c = ladder
                    ? AlleleMath.crossLadder(a, b, cap, rng)
                    : AlleleMath.crossFlag(a, b, cap, rng);
            check(c.dominant() >= 0 && c.dominant() <= cap, "dominant bounded " + c);
            check(c.recessive() >= 0 && c.recessive() <= cap, "recessive bounded " + c);
            if (ladder) {
                check(c.recessive() >= c.dominant(), "recessive >= dominant " + c);
                check(c.recessive() <= Math.min(cap, c.dominant() + 2), "recessive <= dominant+2 " + c);
                check(c.dominant() >= Math.max(a.dominant(), b.dominant()), "ladder regression " + c);
            } else {
                Set<Integer> pool = new HashSet<>(List.of(a.dominant(), a.recessive(), b.dominant(), b.recessive()));
                check(pool.contains(c.dominant()) && pool.contains(c.recessive()), "flag allele from parents " + c);
            }
        }

        int rounds = 50_000;
        int after = 0;
        for (int i = 0; i < rounds; i++) {
            after += AlleleMath.crossLadder(new AlleleMath.Pair(2, 3), new AlleleMath.Pair(2, 3), 3, rng).dominant();
        }
        check(after >= 2 * rounds, "expectation regressed");
        System.out.printf("mean dominant tier %.3f (parents 2.000)%n", after / (double) rounds);

        System.out.println(failures == 0 ? "PASS: all invariants hold" : "FAIL: " + failures + " violations");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
EOF

# 原生 javac 不认 /c/... 形式的路径，统一转成 Windows 路径（和 tools/build-offline.sh 一样）
OUT_W="$(cygpath -w "$OUT")"
BIO_W="$(cygpath -w "$BIO")"

javac -encoding UTF-8 --release 21 -d "$OUT_W" "$OUT_W\\Check.java" "$BIO_W\\AlleleMath.java" "$BIO_W\\Rng.java"
java -cp "$OUT_W" Check
