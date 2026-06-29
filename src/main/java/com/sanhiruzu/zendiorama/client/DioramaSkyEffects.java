package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.sanhiruzu.zendiorama.ZenDiorama;
import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import org.joml.Matrix4f;

public final class DioramaSkyEffects {
    private DioramaSkyEffects() {
    }

    public static void register(RegisterDimensionSpecialEffectsEvent event) {
        event.register(
                ResourceLocation.fromNamespaceAndPath(ZenDiorama.MOD_ID, "diorama"),
                new InteriorSky());
    }

    private static final class InteriorSky extends DimensionSpecialEffects {
        InteriorSky() {
            super(Float.NaN, false, SkyType.NORMAL, true, false);
        }

        @Override
        public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX, double camY, double camZ, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
            return DioramaDimensions.DIORAMA_LEVEL.equals(level.dimension());
        }

        @Override
        public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera, Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
            if (!DioramaDimensions.DIORAMA_LEVEL.equals(level.dimension())) {
                return false;
            }
            setupFog.run();
            DioramaSkyboxRenderer.renderSky(modelViewMatrix);
            return true;
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
            if (!isDioramaDimension()) {
                return fogColor;
            }

            Vec3 sourceSky = directionalSkyColor();
            Vec3 blurred = applySkyBlur(sourceSky);
            Vec3 blended = blurred.lerp(fogColor, 0.35F).scale(0.82F);
            float timeCycle = getTimeCycle();
            float rain = DioramaSkySnapshotState.rainLevel();
            float thunder = DioramaSkySnapshotState.thunderLevel();
            float weather = Math.max(rain, thunder);

            float timeTint = 0.25F + 0.65F * (float) Math.sin(timeCycle * Math.PI * 2.0F + 0.6F);
            timeTint = Mth.clamp(timeTint * 0.5F + 0.5F, 0.0F, 1.0F);
            float atmospheric = Math.max(0.50F, Math.min(1.0F, brightness * 0.72F + 0.20F + timeTint * 0.12F));
            float wetness = 1.0F - Math.min(1.0F, weather * 0.75F);

            return new Vec3(
                    blended.x * atmospheric * wetness + 0.06F + weather * 0.06F,
                    blended.y * atmospheric * wetness + 0.10F + weather * 0.03F,
                    blended.z * atmospheric * wetness + 0.18F + weather * 0.01F);
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return true;
        }

        @Override
        public float[] getSunriseColor(float timeOfDay, float partialTick) {
            if (!isDioramaDimension()) {
                return super.getSunriseColor(timeOfDay, partialTick);
            }

            Vec3 source = directionalSkyColor();
            float warmth = 0.45F + 0.55F * DioramaSkySnapshotState.rainLevel();
            float glow = 0.10F + 0.14F * (float) Math.abs(Math.sin(getTimeCycle() * Math.PI));
            return new float[]{
                    (float) (source.x * 0.85D + 0.08F),
                    (float) (source.y * (0.55F * (1.0F - warmth) + 0.75F) + 0.05F),
                    (float) (source.z * 0.55F * warmth + 0.10F),
                    Math.max(0.08F, glow)
            };
        }

        private static Vec3 directionalSkyColor() {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                return DioramaSkySnapshotState.sourceSkyColor();
            }

            float yaw = player.getYHeadRot() * ((float) Math.PI / 180.0F);
            float pitch = player.getXRot() * ((float) Math.PI / 180.0F);
            float cosPitch = Mth.cos(pitch);
            float x = -Mth.sin(yaw) * cosPitch;
            float y = -Mth.sin(pitch);
            float z = Mth.cos(yaw) * cosPitch;

            return DioramaSkySnapshotState.sourceSkyColor(x, y, z);
        }

        private static Vec3 applySkyBlur(Vec3 sourceColor) {
            float blurRadius = Mth.clamp(DioramaConfig.SKYBOX_BLUR_RADIUS.get() / 32.0F, 0.0F, 1.0F);
            double tint = 0.20F + 0.60F * (1.0F - blurRadius);
            double softBlue = 0.54F + 0.28F * blurRadius;
            double softGreen = 0.66F + 0.20F * blurRadius;
            double softRed = 0.58F + 0.24F * blurRadius;

            return new Vec3(
                    Mth.lerp((float) blurRadius, sourceColor.x, softRed),
                    Mth.lerp((float) blurRadius, sourceColor.y, softGreen),
                    Mth.lerp((float) blurRadius, sourceColor.z, softBlue)
            ).scale(tint);
        }

        private static float getTimeCycle() {
            return DioramaSkySnapshotState.sourceDayOfTime();
        }

        private static boolean isDioramaDimension() {
            return Minecraft.getInstance().level != null && DioramaDimensions.DIORAMA_LEVEL.equals(Minecraft.getInstance().level.dimension());
        }
    }
}
