package com.example;

import com.example.antitower.AntiTowerHandler;
import net.fabricmc.api.ModInitializer;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChallengeMod implements ModInitializer {
	public static final String MOD_ID = "challengecraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public enum TargetMode {
		FAST,
		SLOW
	}

	private static final int STOP_CODE = 1379;
	private static volatile TargetMode targetMode = TargetMode.FAST;
	private static volatile boolean challengeActive = true;
	private static volatile boolean challengeLocked;
	private static volatile int pendingActivationTicks;
	private static volatile boolean pendingLock;
	private static volatile double speedMultiplier = 1.0D;
	private static volatile boolean benchmarkOverride = false;

	// Anti-Tower settings
	private static volatile boolean antiTowerEnabled = true;
	private static volatile double antiTowerDelay = 3.0D; // seconds before tower blocks are destroyed

	// TPS tracking
	private static final int TPS_SAMPLE_SIZE = 20;
	private static final long[] tickTimes = new long[TPS_SAMPLE_SIZE];
	private static int tickTimeIndex = 0;
	private static long lastTickTime = 0;
	private static volatile double currentTps = 20.0;

	public static TargetMode getTargetMode() {
		return targetMode;
	}

	public static void setTargetMode(TargetMode mode) {
		targetMode = mode;
	}

	public static int getTargetIntervalTicks() {
		return targetMode == TargetMode.SLOW ? 10 : 1;
	}

	public static boolean isChallengeActive() {
		if (benchmarkOverride) {
			return false;
		}
		return challengeActive;
	}

	public static void setChallengeActive(boolean active) {
		challengeActive = active;
	}

	public static double getSpeedMultiplier() {
		return speedMultiplier;
	}

	public static void setSpeedMultiplier(double multiplier) {
		speedMultiplier = multiplier;
	}

	public static void setBenchmarkOverride(boolean override) {
		benchmarkOverride = override;
	}

	public static double getCurrentTps() {
		return currentTps;
	}

	// Anti-Tower getters and setters
	public static boolean isAntiTowerEnabled() {
		return antiTowerEnabled;
	}

	public static void setAntiTowerEnabled(boolean enabled) {
		antiTowerEnabled = enabled;
	}

	public static double getAntiTowerDelay() {
		return antiTowerDelay;
	}

	public static void setAntiTowerDelay(double delay) {
		antiTowerDelay = Math.max(0.5, Math.min(30.0, delay)); // Clamp between 0.5 and 30 seconds
	}

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("ChallengeMod initialized");

		// Register anti-tower handler
		AntiTowerHandler.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("fasttarget")
					.executes(context -> setTargetMode(context.getSource(), TargetMode.FAST)));
			dispatcher.register(Commands.literal("slowtarget")
					.executes(context -> setTargetMode(context.getSource(), TargetMode.SLOW)));
			dispatcher.register(Commands.literal("fast")
					.then(Commands.literal("target")
							.executes(context -> setTargetMode(context.getSource(), TargetMode.FAST))));
			dispatcher.register(Commands.literal("slow")
					.then(Commands.literal("target")
							.executes(context -> setTargetMode(context.getSource(), TargetMode.SLOW))));

			dispatcher.register(Commands.literal("startchallenge")
					.then(Commands.argument("seconds", IntegerArgumentType.integer(0))
							.executes(context -> startChallenge(context.getSource(),
									IntegerArgumentType.getInteger(context, "seconds"), false))
							.then(Commands.literal("lock")
									.executes(context -> startChallenge(context.getSource(),
											IntegerArgumentType.getInteger(context, "seconds"), true)))));
			dispatcher.register(Commands.literal("stopchallenge")
					.executes(context -> stopChallenge(context.getSource(), false)));
			dispatcher.register(Commands.literal("stop")
					.then(Commands.argument("code", IntegerArgumentType.integer())
							.executes(context -> stopChallenge(context.getSource(),
									IntegerArgumentType.getInteger(context, "code") == STOP_CODE))));

			dispatcher.register(Commands.literal("challenge")
					.then(Commands.literal("speed")
							.then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.1D))
									.executes(context -> setSpeedMultiplier(context.getSource(),
											DoubleArgumentType.getDouble(context, "multiplier"))))));
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// Track TPS
			long now = System.nanoTime();
			if (lastTickTime != 0) {
				long tickDuration = now - lastTickTime;
				tickTimes[tickTimeIndex] = tickDuration;
				tickTimeIndex = (tickTimeIndex + 1) % TPS_SAMPLE_SIZE;

				// Calculate average TPS from samples
				long totalTime = 0;
				for (long time : tickTimes) {
					totalTime += time;
				}
				if (totalTime > 0) {
					double avgTickTimeMs = (totalTime / (double) TPS_SAMPLE_SIZE) / 1_000_000.0;
					currentTps = Math.min(20.0, 1000.0 / avgTickTimeMs);
				}
			}
			lastTickTime = now;

			if (pendingActivationTicks > 0) {
				pendingActivationTicks--;
				if (pendingActivationTicks == 0) {
					challengeActive = true;
					challengeLocked = pendingLock;
					LOGGER.info("Challenge enabled (locked={})", challengeLocked);
				}
			}
		});
	}

	private static int setTargetMode(CommandSourceStack source, TargetMode mode) {
		targetMode = mode;
		source.sendSuccess(() -> Component.literal("Targeting mode set to " + mode.name().toLowerCase()), false);
		return 1;
	}

	private static int startChallenge(CommandSourceStack source, int seconds, boolean lock) {
		startChallengeInternal(seconds, lock);
		if (seconds <= 0) {
			source.sendSuccess(() -> Component.literal("Challenge started" + (lock ? " (locked)" : "")), false);
			return 1;
		}
		source.sendSuccess(
				() -> Component.literal("Challenge will start in " + seconds + "s" + (lock ? " (locked)" : "")), false);
		return 1;
	}

	private static int stopChallenge(CommandSourceStack source, boolean hasStopCode) {
		if (challengeLocked && !hasStopCode) {
			source.sendFailure(Component.literal("Challenge is locked. Use the override stop command."));
			return 0;
		}
		stopChallengeInternal();
		source.sendSuccess(() -> Component.literal("Challenge stopped"), false);
		return 1;
	}

	private static void startChallengeInternal(int seconds, boolean lock) {
		int delayTicks = Math.max(0, seconds) * 20;
		pendingActivationTicks = delayTicks;
		pendingLock = lock;
		challengeActive = delayTicks == 0;
		challengeLocked = delayTicks == 0 && lock;
	}

	private static void stopChallengeInternal() {
		pendingActivationTicks = 0;
		pendingLock = false;
		challengeActive = false;
		challengeLocked = false;
	}

	private static int setSpeedMultiplier(CommandSourceStack source, double multiplier) {
		speedMultiplier = multiplier;
		source.sendSuccess(() -> Component.literal("Challenge speed set to " + multiplier), false);
		return 1;
	}

}
