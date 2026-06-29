package com.sanhiruzu.zendiorama.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Unbreakable decorative lip marking the diorama interior edge. */
public class DioramaEdgeBlock extends Block {
    public static final MapCodec<DioramaEdgeBlock> CODEC = simpleCodec(DioramaEdgeBlock::new);

    public DioramaEdgeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
