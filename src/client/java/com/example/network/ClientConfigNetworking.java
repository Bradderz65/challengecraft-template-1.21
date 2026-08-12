package com.example.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientConfigNetworking {
    private static boolean serverSynced;

    private ClientConfigNetworking() {
    }

    public static void register() {
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
            com.example.config.ModConfig.save();
        }
    }
}
