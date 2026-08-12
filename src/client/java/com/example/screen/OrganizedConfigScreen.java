package com.example.screen;

import com.example.ChallengeMod;
import com.example.config.ModConfig;
import com.example.network.ClientConfigNetworking;
import com.example.network.ConfigPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class OrganizedConfigScreen extends Screen {
    private static final int WIDTH = 310;
    private final Screen parent;
    private boolean active, antiTower, aStar, debug;
    private ChallengeMod.TargetMode mode;
    private double speed, delay, range;

    public OrganizedConfigScreen(Screen parent) {
        super(Component.literal("ChallengeCraft Settings"));
        this.parent = parent;
        active = ModConfig.isChallengeActive(); mode = ModConfig.getTargetMode(); speed = ModConfig.getSpeedMultiplier();
        antiTower = ModConfig.isAntiTowerEnabled(); delay = ModConfig.getAntiTowerDelay(); range = ModConfig.getHuntRange();
        aStar = ModConfig.isAStarEnabled(); debug = ModConfig.isAStarDebugEnabled();
    }

    @Override protected void init() {
        clearWidgets();
        int x = width / 2 - WIDTH / 2 + 12, y = contentTop(), full = WIDTH - 24;
        addToggle(x, y, full, () -> "Challenge: " + onOff(active), () -> active = !active,
                "Turns all ChallengeCraft behavior on or off");
        addToggle(x, y + 24, full, () -> "Targeting: " + (mode == ChallengeMod.TargetMode.FAST ? "Fast" : "Slow"),
                () -> mode = mode == ChallengeMod.TargetMode.FAST ? ChallengeMod.TargetMode.SLOW : ChallengeMod.TargetMode.FAST,
                "Fast updates every tick; Slow reduces update frequency");
        addStepper(x, y + 48, full, () -> String.format("Challenge speed: %.1fx", speed),
                () -> speed = Math.max(.1, speed - .1), () -> speed = Math.min(10, speed + .1));
        addToggle(x, y + 88, full, () -> "Anti-tower: " + onOff(antiTower), () -> antiTower = !antiTower,
                "Breaks tower blocks after the configured delay");
        addStepper(x, y + 112, full, () -> String.format("Break delay: %.1fs", delay),
                () -> delay = Math.max(.5, delay - .5), () -> delay = Math.min(30, delay + .5));
        addStepper(x, y + 136, full, () -> String.format("Mob detection range: %.0f blocks", range),
                () -> range = Math.max(10, range - 10), () -> range = Math.min(500, range + 10));
        addToggle(x, y + 176, full / 2 - 2, () -> "A* paths: " + onOff(aStar), () -> { aStar = !aStar; if (!aStar) debug = false; },
                "Enables advanced mob pathfinding");
        addToggle(x + full / 2 + 2, y + 176, full / 2 - 2, () -> "Debug: " + onOff(debug), () -> { if (aStar) debug = !debug; },
                "Shows pathfinding visualization");
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> closeWithoutSaving()).bounds(x, y + 210, full / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply()).bounds(x + full / 2 + 2, y + 210, full / 2 - 2, 20).build());
    }

    private int contentTop() {
        return height / 2 - 94;
    }

    private void addToggle(int x, int y, int w, java.util.function.Supplier<String> label, Runnable action, String tip) {
        Button[] ref = new Button[1];
        ref[0] = Button.builder(Component.literal(label.get()), b -> { action.run(); b.setMessage(Component.literal(label.get())); })
                .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(tip))).build();
        addRenderableWidget(ref[0]);
    }

    private void addStepper(int x, int y, int w, java.util.function.Supplier<String> label, Runnable down, Runnable up) {
        Button[] value = new Button[1];
        addRenderableWidget(Button.builder(Component.literal("−"), b -> { down.run(); value[0].setMessage(Component.literal(label.get())); }).bounds(x, y, 28, 20).build());
        value[0] = addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {}).bounds(x + 32, y, w - 64, 20).build());
        value[0].active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> { up.run(); value[0].setMessage(Component.literal(label.get())); }).bounds(x + w - 28, y, 28, 20).build());
    }

    private void apply() {
        ClientConfigNetworking.submit(new ConfigPayload(active, mode, speed, antiTower, delay, range, aStar, debug));
        Minecraft.getInstance().setScreen(parent);
    }

    private void closeWithoutSaving() { Minecraft.getInstance().setScreen(parent); }
    @Override public void onClose() { closeWithoutSaving(); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics g, int mx, int my, float tick) {
        super.render(g, mx, my, tick);
        int cx = width / 2, x = cx - WIDTH / 2 + 12, y = contentTop();
        g.drawCenteredString(font, title, cx, y - 38, 0xFFFFFF);
        String status = ClientConfigNetworking.isServerControlled()
                ? (ClientConfigNetworking.isServerSynced() ? "Server-controlled • operator permission required" : "Waiting for server settings…")
                : "Local world settings";
        g.drawCenteredString(font, status, cx, y - 24, ClientConfigNetworking.isServerControlled() ? 0xFFCC66 : 0x88DD88);
        g.drawString(font, "CORE CHALLENGE", x, y - 11, 0xAAAAFF);
        g.drawString(font, "MOB BEHAVIOR", x, y + 77, 0xAAAAFF);
        g.drawString(font, "PATHFINDING", x, y + 165, 0xAAAAFF);
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
}
