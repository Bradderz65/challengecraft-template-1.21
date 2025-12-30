package com.example.config;

import com.example.ChallengeMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration manager for ChallengeCraft mod settings.
 * Handles saving/loading settings to a JSON file.
 */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("challengecraft.json");

    private static ConfigData data = new ConfigData();

    /**
     * Internal data class holding all configuration values.
     */
    private static class ConfigData {
        boolean challengeActive = true;
        String targetMode = "FAST";
        double speedMultiplier = 1.0;
        // Anti-Tower settings
        boolean antiTowerEnabled = true;
        double antiTowerDelay = 3.0; // seconds
    }

    /**
     * Load configuration from disk, or create defaults if file doesn't exist.
     */
    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                data = GSON.fromJson(json, ConfigData.class);
                if (data == null) {
                    data = new ConfigData();
                }
                ChallengeMod.LOGGER.info("ChallengeCraft config loaded");
            } catch (IOException e) {
                ChallengeMod.LOGGER.error("Failed to load config", e);
                data = new ConfigData();
            }
        } else {
            // Create default config file
            save();
        }

        // Apply loaded config to main mod
        applyToMod();
    }

    /**
     * Apply current config values to the main mod.
     */
    public static void applyToMod() {
        ChallengeMod.setChallengeActive(data.challengeActive);
        ChallengeMod.setTargetMode(getTargetMode());
        ChallengeMod.setSpeedMultiplier(data.speedMultiplier);
        ChallengeMod.setAntiTowerEnabled(data.antiTowerEnabled);
        ChallengeMod.setAntiTowerDelay(data.antiTowerDelay);
    }

    /**
     * Save current configuration to disk.
     */
    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            String json = GSON.toJson(data);
            Files.writeString(CONFIG_PATH, json);
            ChallengeMod.LOGGER.info("ChallengeCraft config saved");
        } catch (IOException e) {
            ChallengeMod.LOGGER.error("Failed to save config", e);
        }

        // Apply to running mod
        applyToMod();
    }

    // ========== Getters ==========

    public static boolean isChallengeActive() {
        return data.challengeActive;
    }

    public static ChallengeMod.TargetMode getTargetMode() {
        try {
            return ChallengeMod.TargetMode.valueOf(data.targetMode);
        } catch (IllegalArgumentException e) {
            return ChallengeMod.TargetMode.FAST;
        }
    }

    public static double getSpeedMultiplier() {
        return data.speedMultiplier;
    }

    public static boolean isAntiTowerEnabled() {
        return data.antiTowerEnabled;
    }

    public static double getAntiTowerDelay() {
        return data.antiTowerDelay;
    }

    // ========== Setters ==========

    public static void setChallengeActive(boolean active) {
        data.challengeActive = active;
    }

    public static void setTargetMode(ChallengeMod.TargetMode mode) {
        data.targetMode = mode.name();
    }

    public static void setSpeedMultiplier(double multiplier) {
        data.speedMultiplier = Math.max(0.1, Math.min(10.0, multiplier));
    }

    public static void setAntiTowerEnabled(boolean enabled) {
        data.antiTowerEnabled = enabled;
    }

    public static void setAntiTowerDelay(double delay) {
        data.antiTowerDelay = Math.max(0.5, Math.min(30.0, delay));
    }
}
