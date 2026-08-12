package com.example.network;

import com.example.ChallengeMod;
import com.example.config.ModConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ConfigPayload(boolean challengeActive, ChallengeMod.TargetMode targetMode,
                            double speedMultiplier, boolean antiTowerEnabled,
                            double antiTowerDelay, double huntRange,
                            boolean aStarEnabled, boolean aStarDebugEnabled) implements CustomPacketPayload {
    public static final Type<ConfigPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ChallengeMod.MOD_ID, "config"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeBoolean(value.challengeActive);
                buf.writeEnum(value.targetMode);
                buf.writeDouble(value.speedMultiplier);
                buf.writeBoolean(value.antiTowerEnabled);
                buf.writeDouble(value.antiTowerDelay);
                buf.writeDouble(value.huntRange);
                buf.writeBoolean(value.aStarEnabled);
                buf.writeBoolean(value.aStarDebugEnabled);
            },
            buf -> new ConfigPayload(buf.readBoolean(), buf.readEnum(ChallengeMod.TargetMode.class),
                    buf.readDouble(), buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                    buf.readBoolean(), buf.readBoolean()));

    public static ConfigPayload current() {
        return new ConfigPayload(ModConfig.isChallengeActive(), ModConfig.getTargetMode(),
                ModConfig.getSpeedMultiplier(), ModConfig.isAntiTowerEnabled(),
                ModConfig.getAntiTowerDelay(), ModConfig.getHuntRange(),
                ModConfig.isAStarEnabled(), ModConfig.isAStarDebugEnabled());
    }

    public void apply() {
        ModConfig.setChallengeActive(challengeActive);
        ModConfig.setTargetMode(targetMode);
        ModConfig.setSpeedMultiplier(speedMultiplier);
        ModConfig.setAntiTowerEnabled(antiTowerEnabled);
        ModConfig.setAntiTowerDelay(antiTowerDelay);
        ModConfig.setHuntRange(huntRange);
        ModConfig.setAStarEnabled(aStarEnabled);
        ModConfig.setAStarDebugEnabled(aStarEnabled && aStarDebugEnabled);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
