package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.DioramaConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.sanhiruzu.zendiorama.ZenDiorama;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.lang.reflect.Method;

/**
 * Six-face cubemap capture using Minecraft's normal render pipeline. For six consecutive frames the
 * render camera is overridden (per-face yaw/pitch + position at the frame block's centre) and a real
 * 90 degree FOV; each rendered frame is grabbed at {@link RenderGuiEvent.Pre} (clean world, before the
 * GUI) and uploaded as a cubemap face. The player never sees the spin because the capture frames are
 * covered by an opaque overlay (see {@link DioramaHudOverlay}).
 *
 * Camera-at-block-centre means the frame block's own faces cull, so it does not appear in the capture.
 */
public final class DioramaOffscreenCubemap {
    // Face order matches DioramaSkyboxRenderer: 0=north(-Z),1=south(+Z),2=east(+X),3=west(-X),4=up(+Y),5=down(-Y)
    // Minecraft yaw: 0=south, 90=west, 180=north, 270=east. Pole faces use yaw 180 to match the cube mapping.
    private static final float[] FACE_YAWS   = { 180f, 0f, 270f, 90f, 180f, 180f };
    private static final float[] FACE_PITCHES = {   0f, 0f,   0f,  0f,  -90f,  90f };

    /** Client ticks a capture may stay open before it is abandoned. Six faces normally take six
     *  rendered frames; anything past a couple of seconds means the grab hook is never firing
     *  (another mod cancelling RenderGuiEvent.Pre, no GUI pass, a stuck screen). Without this the
     *  capture would stay {@code active} forever, which keeps the opaque cover on screen and
     *  suppresses every miniature. */
    private static final int CAPTURE_TIMEOUT_TICKS = 60;

    private static boolean active = false;
    private static int nextFace = 0;
    private static int ticksActive = 0;
    private static final NativeImage[] captured = new NativeImage[6];
    private static Runnable onComplete = null;
    private static double camX, camY, camZ;

    private static Method setPositionMethod;

    private DioramaOffscreenCubemap() {
    }

    /** Begins a capture centred on the given frame-block world position. afterCapture runs once all 6 faces are grabbed. */
    public static void beginCapture(double frameCenterX, double frameCenterY, double frameCenterZ, Runnable afterCapture) {
        for (NativeImage img : captured) {
            if (img != null) {
                img.close();
            }
        }
        for (int i = 0; i < 6; i++) {
            captured[i] = null;
        }
        camX = frameCenterX;
        camY = frameCenterY;
        camZ = frameCenterZ;
        nextFace = 0;
        ticksActive = 0;
        onComplete = afterCapture;
        active = true;
        DioramaFrameRenderer.suppressMiniature = true;
        ZenDiorama.LOGGER.info("[zen_diorama] cubemap capture started at {} {} {}",
                frameCenterX, frameCenterY, frameCenterZ);
    }

    /** Watchdog: abandons a capture whose faces stopped arriving so the screen cover is released. */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) {
            return;
        }
        if (++ticksActive < CAPTURE_TIMEOUT_TICKS) {
            return;
        }
        ZenDiorama.LOGGER.warn(
                "[zen_diorama] cubemap capture timed out after {} ticks with {}/6 faces - "
                        + "RenderGuiEvent.Pre is not reaching the capture hook. Falling back to the flat sky.",
                ticksActive, nextFace);
        abort();
    }

    private static void abort() {
        active = false;
        ticksActive = 0;
        nextFace = 6;
        DioramaFrameRenderer.suppressMiniature = false;
        for (int i = 0; i < 6; i++) {
            if (captured[i] != null) {
                captured[i].close();
                captured[i] = null;
            }
        }
        if (onComplete != null) {
            // Still ack the server, otherwise entry waits out the full teleport timeout.
            onComplete.run();
            onComplete = null;
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** Overrides camera orientation and position for the current capture face. */
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!active || nextFace >= 6) return;
        event.setYaw(FACE_YAWS[nextFace]);
        event.setPitch(FACE_PITCHES[nextFace]);
        event.setRoll(0f);
        setCameraPosition(event.getCamera(), camX, camY, camZ);
    }

    /** Real 90 degree FOV so the centre-square crop of each frame is an exact cube face. */
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!active || nextFace >= 6) return;
        event.setFOV(90.0D);
    }

    /** Hide the held item so it never appears in a face. */
    public static void onRenderHand(RenderHandEvent event) {
        if (active) event.setCanceled(true);
    }

    /** After the 3D scene renders but before the GUI, grab this frame as one cubemap face. */
    public static void onGuiPre(RenderGuiEvent.Pre event) {
        if (!active || nextFace >= 6) return;

        Minecraft mc = Minecraft.getInstance();
        NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        int target = Mth.clamp(DioramaConfig.SKYBOX_CAPTURE_RESOLUTION.get(), 16, 512);
        captured[nextFace] = cropCenterSquare(full, target);
        full.close();

        nextFace++;
        if (nextFace == 6) {
            finish();
        }
    }

    private static void finish() {
        active = false;
        ticksActive = 0;
        DioramaFrameRenderer.suppressMiniature = false;

        int blurPasses = DioramaConfig.SKYBOX_BLUR_RADIUS.get();
        NativeImage[] faces = new NativeImage[6];
        for (int face = 0; face < 6; face++) {
            NativeImage img = captured[face];
            for (int i = 0; i < blurPasses; i++) {
                img = boxBlur(img);
            }
            faces[face] = img;
            captured[face] = null;
        }

        blendTopSeam(faces);

        for (int face = 0; face < 6; face++) {
            DioramaSkyboxTextures.upload(face, faces[face]);
        }
        // Mean luminance per face separates "the capture never ran" from "the capture ran and the
        // room really is that dark" - the two look identical in game.
        ZenDiorama.LOGGER.info(
                "[zen_diorama] cubemap capture complete, ready={}, mean face luminance N/S/E/W/U/D = {}",
                DioramaSkyboxTextures.isReady(), describeLuminance(faces));
        if (onComplete != null) {
            onComplete.run();
            onComplete = null;
        }
    }

    private static String describeLuminance(NativeImage[] faces) {
        StringBuilder out = new StringBuilder();
        for (int face = 0; face < faces.length; face++) {
            if (face > 0) {
                out.append('/');
            }
            out.append(Math.round(meanLuminance(faces[face])));
        }
        return out.toString();
    }

    private static double meanLuminance(NativeImage image) {
        long total = 0;
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                total += (FastColor.ABGR32.red(abgr) * 30
                        + FastColor.ABGR32.green(abgr) * 59
                        + FastColor.ABGR32.blue(abgr) * 11) / 100;
            }
        }
        return (double) total / Math.max(1, w * h);
    }

    private static void setCameraPosition(Camera camera, double x, double y, double z) {
        try {
            if (setPositionMethod == null) {
                setPositionMethod = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
                setPositionMethod.setAccessible(true);
            }
            setPositionMethod.invoke(camera, new Vec3(x, y, z));
        } catch (Exception e) {
            com.sanhiruzu.zendiorama.ZenDiorama.LOGGER.error("[zen_diorama] camera position override failed", e);
        }
    }

    // Crops the center square of src and scales it down to targetSize×targetSize
    private static NativeImage cropCenterSquare(NativeImage src, int targetSize) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int sq = Math.min(sw, sh);
        int ox = (sw - sq) / 2;
        int oy = (sh - sq) / 2;
        NativeImage out = new NativeImage(targetSize, targetSize, false);
        for (int y = 0; y < targetSize; y++) {
            for (int x = 0; x < targetSize; x++) {
                int sx = ox + x * sq / targetSize;
                int sy = oy + y * sq / targetSize;
                out.setPixelRGBA(x, y, src.getPixelRGBA(sx, sy));
            }
        }
        return out;
    }

    // Feathers the top face and the side faces' upper edges toward one shared sky color so the
    // (often flat) top doesn't cut hard against the detailed sides. Side faces are captured at
    // pitch 0, so their top pixel row (py=0) points at the zenith.
    private static void blendTopSeam(NativeImage[] faces) {
        int size = faces[4].getWidth();
        int band = Math.max(2, size / 6);

        long ra = 0, ga = 0, ba = 0;
        int n = 0;
        for (int f = 0; f < 4; f++) {
            NativeImage img = faces[f];
            int w = img.getWidth();
            for (int x = 0; x < w; x++) {
                int abgr = img.getPixelRGBA(x, 0);
                ra += FastColor.ABGR32.red(abgr);
                ga += FastColor.ABGR32.green(abgr);
                ba += FastColor.ABGR32.blue(abgr);
                n++;
            }
        }
        if (n == 0) return;
        int ar = (int) (ra / n), ag = (int) (ga / n), ab = (int) (ba / n);

        NativeImage top = faces[4];
        int s = top.getWidth();
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int dist = Math.min(Math.min(x, s - 1 - x), Math.min(y, s - 1 - y));
                if (dist >= band) continue;
                blendPixelToward(top, x, y, ar, ag, ab, 1.0f - (float) dist / band);
            }
        }

        for (int f = 0; f < 4; f++) {
            NativeImage img = faces[f];
            int w = img.getWidth();
            int h = img.getHeight();
            for (int y = 0; y < band && y < h; y++) {
                float t = 1.0f - (float) y / band;
                for (int x = 0; x < w; x++) {
                    blendPixelToward(img, x, y, ar, ag, ab, t);
                }
            }
        }
    }

    private static void blendPixelToward(NativeImage img, int x, int y, int tr, int tg, int tb, float strength) {
        float t = Mth.clamp(strength, 0.0f, 1.0f) * 0.85f;
        int abgr = img.getPixelRGBA(x, y);
        int r = Math.round(Mth.lerp(t, FastColor.ABGR32.red(abgr), tr));
        int g = Math.round(Mth.lerp(t, FastColor.ABGR32.green(abgr), tg));
        int b = Math.round(Mth.lerp(t, FastColor.ABGR32.blue(abgr), tb));
        img.setPixelRGBA(x, y, FastColor.ABGR32.color(255, b, g, r));
    }

    private static NativeImage boxBlur(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long ra = 0, ga = 0, ba = 0;
                int count = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        int sx = Mth.clamp(x + ox, 0, w - 1);
                        int sy = Mth.clamp(y + oy, 0, h - 1);
                        int abgr = src.getPixelRGBA(sx, sy);
                        ra += FastColor.ABGR32.red(abgr);
                        ga += FastColor.ABGR32.green(abgr);
                        ba += FastColor.ABGR32.blue(abgr);
                        count++;
                    }
                }
                dst.setPixelRGBA(x, y, FastColor.ABGR32.color(255,
                        (int) (ba / count), (int) (ga / count), (int) (ra / count)));
            }
        }
        src.close();
        return dst;
    }
}
