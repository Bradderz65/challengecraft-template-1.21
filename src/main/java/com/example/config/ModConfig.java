package com.example.config;

import com.example.ChallengeMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
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

        // Hunt settings
        double huntRange = 50.0;

        // A* Pathfinding settings
        boolean aStarEnabled = false;
        boolean aStarDebugEnabled = false;
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
            } catch (IOException | JsonParseException e) {
                ChallengeMod.LOGGER.error("Failed to load config; using defaults", e);
                data = new ConfigData();
            }
        } else {
            // Create default config file
            save();
        }

        normalize();
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
        ChallengeMod.setHuntRange(data.huntRange);
        ChallengeMod.setAStarEnabled(data.aStarEnabled);
        ChallengeMod.setAStarDebugEnabled(data.aStarDebugEnabled);
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
        } catch (IllegalArgumentException | NullPointerException e) {
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

    public static double getHuntRange() {
        return data.huntRange;
    }

    public static boolean isAStarEnabled() {
        return data.aStarEnabled;
    }

    public static boolean isAStarDebugEnabled() {
        return data.aStarDebugEnabled;
    }

    // ========== Setters ==========

    public static void setChallengeActive(boolean active) {
        data.challengeActive = active;
    }

    public static void setTargetMode(ChallengeMod.TargetMode mode) {
        data.targetMode = mode.name();
    }

    public static void setSpeedMultiplier(double multiplier) {
        data.speedMultiplier = clampFinite(multiplier, 0.1, 10.0, 1.0);
    }

    public static void setAntiTowerEnabled(boolean enabled) {
        data.antiTowerEnabled = enabled;
    }

    public static void setAntiTowerDelay(double delay) {
        data.antiTowerDelay = clampFinite(delay, 0.5, 30.0, 3.0);
    }

    public static void setHuntRange(double range) {
        data.huntRange = clampFinite(range, 10.0, 500.0, 50.0);
    }

    public static void setAStarEnabled(boolean enabled) {
        data.aStarEnabled = enabled;
    }

    public static void setAStarDebugEnabled(boolean enabled) {
        data.aStarDebugEnabled = enabled;
    }

    private static void normalize() {
        setTargetMode(getTargetMode());
        setSpeedMultiplier(data.speedMultiplier);
        setAntiTowerDelay(data.antiTowerDelay);
        setHuntRange(data.huntRange);
        if (!data.aStarEnabled) {
            data.aStarDebugEnabled = false;
        }
    }

    private static double clampFinite(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
