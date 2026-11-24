package com.vbiafe.sjcore.bio;

/**
 * 单条遗传轴的等位基因运算。规则来自 IC&amp;C 的 {@code CropGenetics.cross}：
 *
 * <ul>
 *   <li>后代从父母各取一个等位基因，取显还是取隐各 50%；</li>
 *   <li>显性档 = max(双亲显性档, 被选中的两个等位基因里较小的那个)；</li>
 *   <li>隐性档 = max(显性档, 被选中的两个里较大的那个)，且隐性最多比显性高 2 档——</li>
 *   <li>两个被选中的都是隐性时保底：显性档至少 = 好亲本的显性档 + 1，并顺带随机再加 0~1 档。</li>
 * </ul>
 *
 * IC&amp;C 的版本把上限写死成六档品质，这里把 cap 参数化，才能套到 IU 那些
 * 三档、四档的轴上。纯整数运算，不碰 Minecraft，方便脱离游戏验证。
 */
public final class AlleleMath {

    /** 一对等位基因：0 档表示该轴没有基因。 */
    public record Pair(int dominant, int recessive) {

        public Pair {
            if (dominant < 0 || recessive < 0) {
                throw new IllegalArgumentException("档位不能为负: " + dominant + "/" + recessive);
            }
        }
    }

    private AlleleMath() {
    }

    /** 阶梯轴（产量、抗性这类"越高越好"的轴）。 */
    public static Pair crossLadder(Pair a, Pair b, int cap, Rng rng) {
        if (cap <= 0) {
            return new Pair(0, 0);
        }
        boolean pickRecessiveA = rng.nextBoolean();
        boolean pickRecessiveB = rng.nextBoolean();
        int pickA = pickRecessiveA ? a.recessive() : a.dominant();
        int pickB = pickRecessiveB ? b.recessive() : b.dominant();
        int bestDominant = Math.max(a.dominant(), b.dominant());

        if (pickRecessiveA && pickRecessiveB) {
            int floor = Math.min(cap, bestDominant + 1);
            int dominant = Math.max(Math.max(floor, pickA), pickB);
            int room = cap - dominant;
            if (room > 0) {
                dominant += rng.nextInt(Math.min(2, room));
            }
            return normalize(dominant, dominant, cap);
        }

        int lower = Math.max(bestDominant, Math.min(pickA, pickB));
        int upper = Math.max(lower, Math.max(pickA, pickB));
        return normalize(lower, upper, cap);
    }

    /**
     * 类别轴（生物群系、水分、阳光这类"选哪个"的轴）：不升档，只从父母两边各取一个。
     * 类别轴的档位是并列选项，所以这里不做"隐性最多高 2 档"的夹紧——那条约束只对阶梯轴有意义。
     */
    public static Pair crossFlag(Pair a, Pair b, int cap, Rng rng) {
        if (cap <= 0) {
            return new Pair(0, 0);
        }
        int pickA = clamp(rng.nextBoolean() ? a.recessive() : a.dominant(), cap);
        int pickB = clamp(rng.nextBoolean() ? b.recessive() : b.dominant(), cap);
        if (pickA == 0 && pickB == 0) {
            return new Pair(0, 0);
        }
        // 一边没有这个基因时，孩子直接继承另一边
        if (pickA == 0) {
            return new Pair(pickB, pickB);
        }
        if (pickB == 0) {
            return new Pair(pickA, pickA);
        }
        return rng.nextBoolean() ? new Pair(pickA, pickB) : new Pair(pickB, pickA);
    }

    private static int clamp(int value, int cap) {
        return Math.max(0, Math.min(cap, value));
    }

    /** 夹紧到 [0, cap]，并保证 显性 <= 隐性 <= min(cap, 显性 + 2)。 */
    public static Pair normalize(int dominant, int recessive, int cap) {
        int d = Math.max(0, Math.min(cap, dominant));
        int r = Math.max(d, Math.min(Math.min(cap, d + 2), recessive));
        return new Pair(d, r);
    }
}
