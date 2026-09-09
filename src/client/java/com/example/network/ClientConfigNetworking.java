package com.example.network;

import com.example.config.ModConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class ClientConfigNetworking {
    private static boolean serverSynced;

    private ClientConfigNetworking() {
    }

    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverSynced = false;
            ModConfig.load();
        });
        ClientPlayNetworking.registerGlobalReceiver(ConfigPayload.TYPE, (payload, context) -> {
            payload.apply();
            serverSynced = true;
        });
    }

    public static boolean isServerControlled() {
        return ClientPlayNetworking.canSend(ConfigPayload.TYPE);
    }

    public static boolean isServerSynced() {
        return serverSynced;
    }

    public static void submit(ConfigPayload payload) {
        if (isServerControlled()) {
            ClientPlayNetworking.send(payload);
        } else {
            payload.apply();
            ModConfig.save();
        }
    }
}
