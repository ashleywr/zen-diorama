package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class DioramaHudOverlay {
    private static final Component SCALE_BADGE = Component.literal("1:16 scale");
    private static final int PANEL_COLOR = 0x88000000;
    private static final int BORDER_COLOR = 0x66F1D7A4;
    private static final int TEXT_COLOR = 0xFFF1D7A4;
    private static final int TRANSITION_FRAMES = 28;
    private static boolean wasInDiorama;
    private static int transitionFrames;

    private DioramaHudOverlay() {
    }

    public static void beginTransition(boolean entering) {
        transitionFrames = TRANSITION_FRAMES;
        wasInDiorama = entering;
    }

    public static boolean isTransitionActive() {
        return transitionFrames > 0;
    }

    public static void suppressMouse(InputEvent.MouseButton.Pre event) {
        if (isTransitionActive()) {
            event.setCanceled(true);
        }
    }

    public static void suppressScroll(InputEvent.MouseScrollingEvent event) {
        if (isTransitionActive()) {
            event.setCanceled(true);
        }
    }

    public static void suppressInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (isTransitionActive()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    public static void suppressMovement(ClientTickEvent.Pre event) {
        if (!isTransitionActive()) {
            return;
        }

        Options options = Minecraft.getInstance().options;
        options.keyUp.setDown(false);
        options.keyDown.setDown(false);
        options.keyLeft.setDown(false);
        options.keyRight.setDown(false);
        options.keyJump.setDown(false);
        options.keyShift.setDown(false);
        options.keySprint.setDown(false);
        options.keyUse.setDown(false);
        options.keyAttack.setDown(false);
    }

    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        // While the entry cubemap is being captured, the world is rendering 6 spun faces underneath.
        // Cover the screen with opaque black so the player sees a brief blackout, not the spin.
        if (DioramaOffscreenCubemap.isActive()) {
            GuiGraphics cover = event.getGuiGraphics();
            cover.fill(0, 0, cover.guiWidth(), cover.guiHeight(), 0xFF000000);
            return;
        }

        boolean inDiorama = minecraft.level != null && minecraft.level.dimension().equals(DioramaDimensions.DIORAMA_LEVEL);
        if (inDiorama != wasInDiorama) {
            transitionFrames = TRANSITION_FRAMES;
            wasInDiorama = inDiorama;
        }

        if (!inDiorama && transitionFrames <= 0) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        renderVignette(graphics, graphics.guiWidth(), graphics.guiHeight(), inDiorama);
        if (transitionFrames > 0) {
            transitionFrames--;
        }

        if (!inDiorama) {
            return;
        }

        Font font = minecraft.font;
        int x = 8;
        int y = 8;
        int paddingX = 6;
        int paddingY = 4;
        int width = font.width(SCALE_BADGE) + paddingX * 2;
        int height = font.lineHeight + paddingY * 2;

        graphics.fill(x, y, x + width, y + height, PANEL_COLOR);
        graphics.fill(x, y, x + width, y + 1, BORDER_COLOR);
        graphics.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);
        graphics.fill(x, y, x + 1, y + height, BORDER_COLOR);
        graphics.fill(x + width - 1, y, x + width, y + height, BORDER_COLOR);
        graphics.drawString(font, SCALE_BADGE, x + paddingX, y + paddingY, TEXT_COLOR, false);
    }

    private static void renderVignette(GuiGraphics graphics, int width, int height, boolean inDiorama) {
        int baseAlpha = inDiorama ? 38 : 0;
        int pulseAlpha = transitionFrames > 0 ? (int) (150.0F * transitionFrames / TRANSITION_FRAMES) : 0;
        int edgeAlpha = Math.min(180, baseAlpha + pulseAlpha);
        if (edgeAlpha <= 0) {
            return;
        }

        int edgeColor = edgeAlpha << 24;
        int edge = Math.max(18, Math.min(width, height) / 7);
        graphics.fill(0, 0, width, edge, edgeColor);
        graphics.fill(0, height - edge, width, height, edgeColor);
        graphics.fill(0, edge, edge, height - edge, edgeColor);
        graphics.fill(width - edge, edge, width, height - edge, edgeColor);
    }
}
