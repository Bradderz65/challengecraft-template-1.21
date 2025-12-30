package com.example.screen;

import com.example.ChallengeMod;
import com.example.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A clean, minimal configuration screen for ChallengeCraft mod options.
 */
public class ModConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;

    private final Screen parent;

    // Current values (editable in this screen)
    private boolean challengeActive;
    private ChallengeMod.TargetMode targetMode;
    private double speedMultiplier;

    // UI components
    private Button challengeToggleButton;
    private Button targetModeButton;
    private Button speedDecreaseButton;
    private Button speedIncreaseButton;

    public ModConfigScreen(Screen parent) {
        super(Component.literal("ChallengeCraft Options"));
        this.parent = parent;

        // Load current values from config
        this.challengeActive = ModConfig.isChallengeActive();
        this.targetMode = ModConfig.getTargetMode();
        this.speedMultiplier = ModConfig.getSpeedMultiplier();
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = this.height / 2 - 60;
        int buttonWidth = PANEL_WIDTH - 20;

        // Challenge Active Toggle
        this.challengeToggleButton = Button.builder(
                getChallengeToggleText(),
                button -> {
                    this.challengeActive = !this.challengeActive;
                    button.setMessage(getChallengeToggleText());
                })
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("Toggle whether the challenge is active")))
                .build();
        this.addRenderableWidget(this.challengeToggleButton);

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 8;

        // Target Mode Toggle
        this.targetModeButton = Button.builder(
                getTargetModeText(),
                button -> {
                    this.targetMode = (this.targetMode == ChallengeMod.TargetMode.FAST)
                            ? ChallengeMod.TargetMode.SLOW
                            : ChallengeMod.TargetMode.FAST;
                    button.setMessage(getTargetModeText());
                })
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("Switch between fast and slow targeting modes")))
                .build();
        this.addRenderableWidget(this.targetModeButton);

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 8;

        // Speed Multiplier Controls
        int speedButtonWidth = 30;

        this.speedDecreaseButton = Button.builder(
                Component.literal("-"),
                button -> {
                    this.speedMultiplier = Math.max(0.1, this.speedMultiplier - 0.1);
                })
                .bounds(centerX - buttonWidth / 2, startY, speedButtonWidth, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.speedDecreaseButton);

        this.speedIncreaseButton = Button.builder(
                Component.literal("+"),
                button -> {
                    this.speedMultiplier = Math.min(10.0, this.speedMultiplier + 0.1);
                })
                .bounds(centerX + buttonWidth / 2 - speedButtonWidth, startY, speedButtonWidth, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.speedIncreaseButton);

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 24;

        // Done Button
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> this.onClose())
                .bounds(centerX - 50, startY, 100, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int panelX = centerX - PANEL_WIDTH / 2;
        int panelY = this.height / 2 - 80;
        int panelHeight = 180;

        // Draw semi-transparent panel background
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xCC1a1a2e);

        // Draw panel border
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, 0xFF4a4a6a);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFF4a4a6a);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0xFF4a4a6a);
        graphics.fill(panelX + PANEL_WIDTH - 1, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFF4a4a6a);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, centerX, panelY + 8, 0xFFFFFFFF);

        // Draw separator line under title
        graphics.fill(panelX + 10, panelY + 22, panelX + PANEL_WIDTH - 10, panelY + 23, 0xFF4a4a6a);

        // Draw speed multiplier value in the center (between - and + buttons)
        int speedY = this.height / 2 - 60 + (BUTTON_HEIGHT + BUTTON_SPACING + 8) * 2;
        String speedText = String.format("Speed: %.1fx", this.speedMultiplier);
        graphics.drawCenteredString(this.font, speedText, centerX, speedY + 6, 0xFFFFFFFF);

        // Render all widgets
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // Save settings when closing
        ModConfig.setChallengeActive(this.challengeActive);
        ModConfig.setTargetMode(this.targetMode);
        ModConfig.setSpeedMultiplier(this.speedMultiplier);
        ModConfig.save();

        // Return to parent screen
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // Don't render the blurred background - we want a clear view of the game
    }

    @Override
    protected void renderMenuBackground(GuiGraphics graphics) {
        // Don't render the default menu background
    }

    private Component getChallengeToggleText() {
        String status = this.challengeActive ? "§aON" : "§cOFF";
        return Component.literal("Challenge: " + status);
    }

    private Component getTargetModeText() {
        String modeStr = this.targetMode == ChallengeMod.TargetMode.FAST ? "§bFast" : "§eSlow";
        return Component.literal("Target Mode: " + modeStr);
    }
}
