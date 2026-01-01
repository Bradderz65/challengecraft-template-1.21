package com.example;

import com.example.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class ChallengeModClient implements ClientModInitializer {
	private static final int BENCHMARK_DURATION_TICKS = 200; // 10 seconds

	private boolean benchmarkRunning = false;
	private boolean benchmarkPhaseModEnabled = false;
	private int benchmarkTicksRemaining = 0;

	private final List<Float> fpsModOff = new ArrayList<>();
	private final List<Float> frametimeModOff = new ArrayList<>();
	private final List<Double> tpsModOff = new ArrayList<>();
	private final List<Float> fpsModOn = new ArrayList<>();
	private final List<Float> frametimeModOn = new ArrayList<>();
	private final List<Double> tpsModOn = new ArrayList<>();

	@Override
	public void onInitializeClient() {
		// Load config from file
		ModConfig.load();

		// Register keybinds
		ModKeybinds.register();

		// Register debug renderers
		com.example.render.PathDebugRenderer.register();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("benchmark")
					.executes(context -> {
						if (benchmarkRunning) {
							context.getSource().sendFeedback(Component.literal("§cBenchmark already in progress!"));
							return 0;
						}
						startBenchmark(context.getSource().getClient());
						return 1;
					}));
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
	}

	private void startBenchmark(Minecraft client) {
		benchmarkRunning = true;
		benchmarkPhaseModEnabled = false;
		benchmarkTicksRemaining = BENCHMARK_DURATION_TICKS;

		fpsModOff.clear();
		frametimeModOff.clear();
		tpsModOff.clear();
		fpsModOn.clear();
		frametimeModOn.clear();
		tpsModOn.clear();

		// Disable mod for first phase
		ChallengeMod.setBenchmarkOverride(true);

		client.player.displayClientMessage(
				Component.literal("§6[Benchmark] §eStarting... Mod DISABLED for 10 seconds"), false);
	}

	private void onClientTick(Minecraft client) {
		if (!benchmarkRunning || client.player == null) {
			return;
		}

		// Collect FPS, frametime, and TPS data
		float fps = client.getFps();
		float frametime = 1000.0f / Math.max(fps, 1.0f);
		double tps = ChallengeMod.getCurrentTps();

		if (!benchmarkPhaseModEnabled) {
			fpsModOff.add(fps);
			frametimeModOff.add(frametime);
			tpsModOff.add(tps);
		} else {
			fpsModOn.add(fps);
			frametimeModOn.add(frametime);
			tpsModOn.add(tps);
		}

		benchmarkTicksRemaining--;

		if (benchmarkTicksRemaining <= 0) {
			if (!benchmarkPhaseModEnabled) {
				// Switch to phase 2: mod enabled
				benchmarkPhaseModEnabled = true;
				benchmarkTicksRemaining = BENCHMARK_DURATION_TICKS;
				ChallengeMod.setBenchmarkOverride(false);

				client.player.displayClientMessage(
						Component.literal("§6[Benchmark] §eMod ENABLED for 10 seconds"), false);
			} else {
				// Benchmark complete
				finishBenchmark(client);
			}
		}
	}

	private void finishBenchmark(Minecraft client) {
		benchmarkRunning = false;
		ChallengeMod.setBenchmarkOverride(false);

		// Calculate statistics
		BenchmarkStats offStats = calculateStats(fpsModOff, frametimeModOff, tpsModOff);
		BenchmarkStats onStats = calculateStats(fpsModOn, frametimeModOn, tpsModOn);

		// Display results
		client.player.displayClientMessage(Component.literal("§6========== BENCHMARK RESULTS =========="), false);
		client.player.displayClientMessage(Component.literal("§e"), false);

		client.player.displayClientMessage(Component.literal("§c■ MOD DISABLED:"), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7FPS: §f%.1f avg §7(min: %.1f, max: %.1f)",
				offStats.avgFps, offStats.minFps, offStats.maxFps)), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7Frametime: §f%.2fms avg §7(min: %.2fms, max: %.2fms)",
				offStats.avgFrametime, offStats.minFrametime, offStats.maxFrametime)), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7TPS: §f%.1f avg §7(min: %.1f, max: %.1f)",
				offStats.avgTps, offStats.minTps, offStats.maxTps)), false);

		client.player.displayClientMessage(Component.literal("§e"), false);

		client.player.displayClientMessage(Component.literal("§a■ MOD ENABLED:"), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7FPS: §f%.1f avg §7(min: %.1f, max: %.1f)",
				onStats.avgFps, onStats.minFps, onStats.maxFps)), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7Frametime: §f%.2fms avg §7(min: %.2fms, max: %.2fms)",
				onStats.avgFrametime, onStats.minFrametime, onStats.maxFrametime)), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7TPS: §f%.1f avg §7(min: %.1f, max: %.1f)",
				onStats.avgTps, onStats.minTps, onStats.maxTps)), false);

		client.player.displayClientMessage(Component.literal("§e"), false);

		// Performance difference
		float fpsDiff = onStats.avgFps - offStats.avgFps;
		float frametimeDiff = onStats.avgFrametime - offStats.avgFrametime;
		double tpsDiff = onStats.avgTps - offStats.avgTps;
		String fpsColor = fpsDiff >= 0 ? "§a" : "§c";
		String frametimeColor = frametimeDiff <= 0 ? "§a" : "§c";
		String tpsColor = tpsDiff >= 0 ? "§a" : "§c";

		client.player.displayClientMessage(Component.literal("§b■ IMPACT:"), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7FPS Change: %s%+.1f §7(%.1f%%)",
				fpsColor, fpsDiff, (fpsDiff / Math.max(offStats.avgFps, 1)) * 100)), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7Frametime Change: %s%+.2fms",
				frametimeColor, frametimeDiff)), false);
		client.player.displayClientMessage(Component.literal(String.format(
				"  §7TPS Change: %s%+.1f",
				tpsColor, tpsDiff)), false);

		client.player.displayClientMessage(Component.literal("§6========================================="), false);
	}

	private BenchmarkStats calculateStats(List<Float> fpsList, List<Float> frametimeList, List<Double> tpsList) {
		if (fpsList.isEmpty()) {
			return new BenchmarkStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
		}

		float sumFps = 0, minFps = Float.MAX_VALUE, maxFps = 0;
		float sumFrametime = 0, minFrametime = Float.MAX_VALUE, maxFrametime = 0;
		double sumTps = 0, minTps = Double.MAX_VALUE, maxTps = 0;

		for (int i = 0; i < fpsList.size(); i++) {
			float fps = fpsList.get(i);
			float frametime = frametimeList.get(i);
			double tps = tpsList.get(i);

			sumFps += fps;
			minFps = Math.min(minFps, fps);
			maxFps = Math.max(maxFps, fps);

			sumFrametime += frametime;
			minFrametime = Math.min(minFrametime, frametime);
			maxFrametime = Math.max(maxFrametime, frametime);

			sumTps += tps;
			minTps = Math.min(minTps, tps);
			maxTps = Math.max(maxTps, tps);
		}

		int count = fpsList.size();
		return new BenchmarkStats(
				sumFps / count, minFps, maxFps,
				sumFrametime / count, minFrametime, maxFrametime,
				sumTps / count, minTps, maxTps);
	}

	private record BenchmarkStats(
			float avgFps, float minFps, float maxFps,
			float avgFrametime, float minFrametime, float maxFrametime,
			double avgTps, double minTps, double maxTps) {
	}
}