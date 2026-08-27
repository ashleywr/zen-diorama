package com.sanhiruzu.zendiorama.client;

import com.sanhiruzu.zendiorama.world.DioramaDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

public final class DioramaSoundMuffle {
    private static final float MUFFLED_VOLUME = 0.62F;
    private static final float MUFFLED_PITCH = 0.92F;

    private DioramaSoundMuffle() {
    }

    public static void onPlaySound(PlaySoundEvent event) {
        if (!isInsideDiorama()) {
            return;
        }

        SoundInstance sound = event.getSound();
        if (sound == null || isExcluded(sound.getSource()) || sound instanceof MuffledSoundInstance) {
            return;
        }

        event.setSound(new MuffledSoundInstance(sound));
    }

    private static boolean isInsideDiorama() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && DioramaDimensions.DIORAMA_LEVEL.equals(minecraft.level.dimension());
    }

    private static boolean isExcluded(SoundSource source) {
        return source == SoundSource.MASTER || source == SoundSource.MUSIC || source == SoundSource.RECORDS || source == SoundSource.VOICE;
    }

    private record MuffledSoundInstance(SoundInstance delegate) implements SoundInstance {
        @Override
        public ResourceLocation getLocation() {
            return delegate.getLocation();
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            return delegate.resolve(manager);
        }

        @Override
        public Sound getSound() {
            return delegate.getSound();
        }

        @Override
        public SoundSource getSource() {
            return delegate.getSource();
        }

        @Override
        public boolean isLooping() {
            return delegate.isLooping();
        }

        @Override
        public boolean isRelative() {
            return delegate.isRelative();
        }

        @Override
        public int getDelay() {
            return delegate.getDelay();
        }

        @Override
        public float getVolume() {
            return delegate.getVolume() * MUFFLED_VOLUME;
        }

        @Override
        public float getPitch() {
            return delegate.getPitch() * MUFFLED_PITCH;
        }

        @Override
        public double getX() {
            return delegate.getX();
        }

        @Override
        public double getY() {
            return delegate.getY();
        }

        @Override
        public double getZ() {
            return delegate.getZ();
        }

        @Override
        public Attenuation getAttenuation() {
            return delegate.getAttenuation();
        }

        @Override
        public boolean canStartSilent() {
            return delegate.canStartSilent();
        }

        @Override
        public boolean canPlaySound() {
            return delegate.canPlaySound();
        }
    }
}
