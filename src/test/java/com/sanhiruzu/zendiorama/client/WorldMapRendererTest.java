package com.sanhiruzu.zendiorama.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldMapRendererTest {
    @Test
    void keepsUnlitMapsOnlyFaintlyVisible() {
        float brightness = WorldMapRenderer.mapBrightnessForLightLevels(0, 0, 0);

        assertTrue(brightness <= 0.06f, "unlit maps should not read clearly in darkness");
    }

    @Test
    void brightensProgressivelyAsBlockLightIncreases() {
        float dark = WorldMapRenderer.mapBrightnessForLightLevels(0, 0, 0);
        float low = WorldMapRenderer.mapBrightnessForLightLevels(4, 0, 0);
        float medium = WorldMapRenderer.mapBrightnessForLightLevels(8, 0, 0);
        float full = WorldMapRenderer.mapBrightnessForLightLevels(15, 0, 0);

        assertTrue(dark < low);
        assertTrue(low < medium);
        assertTrue(medium < full);
        assertEquals(0.88f, full, 0.0001f);
    }

    @Test
    void appliesSkyDarkenBeforeComputingSkylightBrightness() {
        float moonlight = WorldMapRenderer.mapBrightnessForLightLevels(0, 15, 11);
        float torchlight = WorldMapRenderer.mapBrightnessForLightLevels(8, 0, 0);
        float daylight = WorldMapRenderer.mapBrightnessForLightLevels(0, 15, 0);

        assertTrue(moonlight < 0.12f, "moonlit maps should stay fairly subdued");
        assertTrue(moonlight < torchlight);
        assertTrue(torchlight < daylight);
        assertTrue(daylight < 0.9f, "daylight should still read as a lit object, not a self-lit screen");
    }
}
