package com.sanhiruzu.zendiorama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SurfaceSamplerTest {
    @Test
    void sampledGridWidthMatchesStrideBudget() {
        assertEquals(143, SurfaceSampler.sampledGridWidth(427, 192 * 192 * 2));
        assertEquals(128, SurfaceSampler.sampledGridWidth(256, 128 * 128 * 2));
    }
}
