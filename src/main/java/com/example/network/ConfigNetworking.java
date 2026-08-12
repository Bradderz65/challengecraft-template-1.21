package com.example.network;

import com.example.config.ModConfig;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;

public final class ConfigNetworking {
    private ConfigNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ConfigPayload.TYPE, ConfigPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigPayload.TYPE, ConfigPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigPayload.TYPE, (payload, context) -> {
            if (!context.player().hasPermissions(2)) {
                context.player().displayClientMessage(Component.literal("You need operator permission to change ChallengeCraft settings."), false);
                ServerPlayNetworking.send(context.player(), ConfigPayload.current());
                return;
            }
            payload.apply();
            ModConfig.save();
            context.server().getPlayerList().getPlayers().forEach(player -> {
                if (ServerPlayNetworking.canSend(player, ConfigPayload.TYPE)) {
                    ServerPlayNetworking.send(player, ConfigPayload.current());
                }
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.player, ConfigPayload.TYPE)) {
                ServerPlayNetworking.send(handler.player, ConfigPayload.current());
            }
        });
    }
}
