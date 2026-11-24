package com.vbiafe.sjcore.bio;

import java.util.Random;

/**
 * 交配算法只依赖这两个随机原语，这样可以脱离游戏用裸 Java 跑验证
 * （见 tools/allele-check.sh）。游戏内的随机源适配见 {@link McRng}。
 */
public interface Rng {

    int nextInt(int bound);

    boolean nextBoolean();

    static Rng of(Random random) {
        return new Rng() {
            @Override
            public int nextInt(int bound) {
                return random.nextInt(bound);
            }

            @Override
            public boolean nextBoolean() {
                return random.nextBoolean();
            }
        };
    }

    static Rng seeded(long seed) {
        return of(new Random(seed));
    }
}
