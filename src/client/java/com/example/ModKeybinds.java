package com.example;

import com.example.screen.ModConfigScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Handles keybind registration for ChallengeCraft mod.
 */
public class ModKeybinds {
    private static KeyMapping openConfigKey;

    /**
     * Register all mod keybindings.
     */
    public static void register() {
        // Register the config menu keybind (default: O key)
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.challengecraft.open_config", // Translation key
                GLFW.GLFW_KEY_O, // Default key
                "category.challengecraft.main" // Category translation key
        ));

        // Handle keybind presses each tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                if (client.screen == null) {
                    openConfigScreen(client);
                }
            }
        });
    }

    /**
     * Open the mod configuration screen.
     */
    public static void openConfigScreen(Minecraft client) {
        client.setScreen(new ModConfigScreen(null));
    }
}
