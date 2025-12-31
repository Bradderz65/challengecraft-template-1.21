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
    private static final int PANEL_WIDTH = 260;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;

    private final Screen parent;

    // Current values (editable in this screen)
    private boolean challengeActive;
    private ChallengeMod.TargetMode targetMode;
    private double speedMultiplier;
    private boolean antiTowerEnabled;
    private double antiTowerDelay;
    private double huntRange;

    // UI components
    private Button challengeToggleButton;
    private Button targetModeButton;
    private Button speedDecreaseButton;
    private Button speedIncreaseButton;
    private Button antiTowerToggleButton;
    private Button delayDecreaseButton;
    private Button delayIncreaseButton;

    public ModConfigScreen(Screen parent) {
        super(Component.literal("ChallengeCraft Options"));
        this.parent = parent;

        // Load current values from config
        this.challengeActive = ModConfig.isChallengeActive();
        this.targetMode = ModConfig.getTargetMode();
        this.speedMultiplier = ModConfig.getSpeedMultiplier();
        this.antiTowerEnabled = ModConfig.isAntiTowerEnabled();
        this.antiTowerDelay = ModConfig.getAntiTowerDelay();
        this.huntRange = ModConfig.getHuntRange();
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = this.height / 2 - 110;
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

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 6;

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

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 6;

        // Speed Multiplier Controls
        int controlButtonWidth = 30;

        this.speedDecreaseButton = Button.builder(
                Component.literal("-"),
                button -> {
                    this.speedMultiplier = Math.max(0.1, this.speedMultiplier - 0.1);
                })
                .bounds(centerX - buttonWidth / 2, startY, controlButtonWidth, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.speedDecreaseButton);

        this.speedIncreaseButton = Button.builder(
                Component.literal("+"),
                button -> {
                    this.speedMultiplier = Math.min(10.0, this.speedMultiplier + 0.1);
                })
                .bounds(centerX + buttonWidth / 2 - controlButtonWidth, startY, controlButtonWidth, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.speedIncreaseButton);

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 10;

        // ========== Anti-Tower Section ==========

        // Anti-Tower Toggle
        this.antiTowerToggleButton = Button.builder(
                getAntiTowerToggleText(),
                button -> {
                    this.antiTowerEnabled = !this.antiTowerEnabled;
                    button.setMessage(getAntiTowerToggleText());
                    updateDelayButtonsState();
                })
                .bounds(centerX - buttonWidth / 2, startY, buttonWidth, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(
                        Component.literal("§6Anti-Tower§r: Destroys blocks below you when towering up to escape mobs")))
                .build();
        this.addRenderableWidget(this.antiTowerToggleButton);

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 6;

        // Anti-Tower Delay Controls
        this.delayDecreaseButton = Button.builder(
                Component.literal("-"),
                button -> {
                    this.antiTowerDelay = Math.max(0.5, this.antiTowerDelay - 0.5);
                })
                .bounds(centerX - buttonWidth / 2, startY, controlButtonWidth, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("Decrease tower destruction delay")))
                .build();
        this.addRenderableWidget(this.delayDecreaseButton);

        this.delayIncreaseButton = Button.builder(
                Component.literal("+"),
                button -> {
                    this.antiTowerDelay = Math.min(30.0, this.antiTowerDelay + 0.5);
                })
                .bounds(centerX + buttonWidth / 2 - controlButtonWidth, startY, controlButtonWidth, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("Increase tower destruction delay")))
                .build();
        this.addRenderableWidget(this.delayIncreaseButton);

        updateDelayButtonsState();

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 10;

        // Hunt Range Controls
        this.addRenderableWidget(Button.builder(
                Component.literal("-"),
                button -> {
                    this.huntRange = Math.max(10.0, this.huntRange - 10.0);
                })
                .bounds(centerX - buttonWidth / 2, startY, controlButtonWidth, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("Decrease detection range")))
                .build());

        this.addRenderableWidget(Button.builder(
                Component.literal("+"),
                button -> {
                    this.huntRange = Math.min(500.0, this.huntRange + 10.0);
                })
                .bounds(centerX + buttonWidth / 2 - controlButtonWidth, startY, controlButtonWidth, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.literal("Increase detection range")))
                .build());

        startY += BUTTON_HEIGHT + BUTTON_SPACING + 18;

        // Done Button
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> this.onClose())
                .bounds(centerX - 50, startY, 100, BUTTON_HEIGHT)
                .build());
    }

    /**
     * Updates the enabled state of delay buttons based on anti-tower enabled state.
     */
    private void updateDelayButtonsState() {
        if (this.delayDecreaseButton != null) {
            this.delayDecreaseButton.active = this.antiTowerEnabled;
        }
        if (this.delayIncreaseButton != null) {
            this.delayIncreaseButton.active = this.antiTowerEnabled;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int panelX = centerX - PANEL_WIDTH / 2;
        int panelY = this.height / 2 - 130;
        int panelHeight = 270;

        // Draw semi-transparent panel background
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xCC1a1a2e);

        // Draw panel border with gradient effect
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 2, 0xFF6366f1);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFF4a4a6a);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0xFF4a4a6a);
        graphics.fill(panelX + PANEL_WIDTH - 1, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xFF4a4a6a);

        // Draw title
        graphics.drawCenteredString(this.font, this.title, centerX, panelY + 10, 0xFFFFFFFF);

        // Draw separator line under title
        graphics.fill(panelX + 10, panelY + 24, panelX + PANEL_WIDTH - 10, panelY + 25, 0xFF4a4a6a);

        // Draw speed multiplier value in the center (between - and + buttons)
        int speedY = this.height / 2 - 90 + (BUTTON_HEIGHT + BUTTON_SPACING + 6) * 2;
        String speedText = String.format("Speed: %.1fx", this.speedMultiplier);
        graphics.drawCenteredString(this.font, speedText, centerX, speedY + 6, 0xFFFFFFFF);

        // Draw section separator before Anti-Tower
        int separatorY = speedY + BUTTON_HEIGHT + 5;
        graphics.fill(panelX + 20, separatorY, panelX + PANEL_WIDTH - 20, separatorY + 1, 0xFF4a4a6a);

        // Draw Anti-Tower delay value
        int delayY = speedY + BUTTON_HEIGHT + BUTTON_SPACING + 10 + BUTTON_HEIGHT + BUTTON_SPACING + 6;
        String delayText;
        if (this.antiTowerEnabled) {
            delayText = String.format("§6Tower Delay: §f%.1fs", this.antiTowerDelay);
        } else {
            delayText = String.format("§7Tower Delay: %.1fs", this.antiTowerDelay);
        }
        graphics.drawCenteredString(this.font, Component.literal(delayText), centerX, delayY + 6, 0xFFFFFFFF);

        // Draw Hunt Range Value
        int huntRangeY = delayY + BUTTON_HEIGHT + BUTTON_SPACING + 16;
        String huntRangeText = String.format("Detection Range: %.0f blocks", this.huntRange);
        graphics.drawCenteredString(this.font, Component.literal(huntRangeText), centerX, huntRangeY + 6, 0xFFFFFFFF);

        // Render all widgets
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        // Save settings when closing
        ModConfig.setChallengeActive(this.challengeActive);
        ModConfig.setTargetMode(this.targetMode);
        ModConfig.setSpeedMultiplier(this.speedMultiplier);
        ModConfig.setAntiTowerEnabled(this.antiTowerEnabled);
        ModConfig.setAntiTowerDelay(this.antiTowerDelay);
        ModConfig.setHuntRange(this.huntRange);
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

    private Component getAntiTowerToggleText() {
        String status = this.antiTowerEnabled ? "§aON" : "§cOFF";
        return Component.literal("§6Anti-Tower§r: " + status);
    }
}
