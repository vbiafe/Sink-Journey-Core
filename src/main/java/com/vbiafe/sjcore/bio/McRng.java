package com.vbiafe.sjcore.bio;

import net.minecraft.util.RandomSource;

/** 把 Minecraft 的 {@link RandomSource} 接到纯逻辑的 {@link Rng} 上。 */
public final class McRng implements Rng {

    private final RandomSource source;

    private McRng(RandomSource source) {
        this.source = source;
    }

    public static Rng of(RandomSource source) {
        return new McRng(source);
    }

    @Override
    public int nextInt(int bound) {
        return source.nextInt(bound);
    }

    @Override
    public boolean nextBoolean() {
        return source.nextBoolean();
    }
}
