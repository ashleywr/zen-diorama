package com.sanhiruzu.zendiorama.block;

import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.core.SurveyPinMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A deliberately small block entity so loaded chunks can enumerate Survey Pins without scanning
 * every block position in a large world-map area.
 */
public final class SurveyPinBlockEntity extends BlockEntity {
    public SurveyPinBlockEntity(BlockPos pos, BlockState state) {
        super(ZenDiorama.SURVEY_PIN_ENTITY.get(), pos, state);
    }

    public SurveyPinMarker toMarker() {
        DyeColor color = getBlockState().getValue(SurveyPinBlock.COLOR);
        return new SurveyPinMarker(worldPosition.getX(), worldPosition.getZ(), color.getTextColor());
    }
}
