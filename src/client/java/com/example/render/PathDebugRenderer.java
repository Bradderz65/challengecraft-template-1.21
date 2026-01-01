package com.example.render;

import com.example.config.ModConfig;
import com.example.ai.PathDebugData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders debug visualization for A* pathfinding.
 * Shows paths as colored lines and node markers.
 */
public class PathDebugRenderer {

    // Path colors (RGBA)
    private static final float[] NODE_COLOR = { 1.0f, 0.8f, 0.0f, 1.0f }; // Gold
    private static final float[] START_COLOR = { 0.0f, 0.5f, 1.0f, 1.0f }; // Blue
    private static final float[] END_COLOR = { 1.0f, 0.0f, 0.5f, 1.0f }; // Pink

    /**
     * Register the renderer with Fabric's world render events
     */
    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PathDebugRenderer::render);
    }

    /**
     * Main render method called each frame
     */
    private static void render(WorldRenderContext context) {
        // Check if debug rendering is enabled
        if (!ModConfig.isAStarDebugEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        PoseStack poseStack = context.matrixStack();
        Vec3 cameraPos = context.camera().getPosition();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();

        // Get all mob paths and render them
        Map<UUID, List<BlockPos>> allPaths = PathDebugData.getAllPaths();

        for (Map.Entry<UUID, List<BlockPos>> entry : allPaths.entrySet()) {
            List<BlockPos> path = entry.getValue();
            if (path != null && path.size() > 1) {
                renderPath(poseStack, bufferSource, path);
            }
        }

        poseStack.popPose();
    }

    /**
     * Render a single path
     */
    private static void renderPath(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
            List<BlockPos> path) {
        if (path.isEmpty())
            return;

        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();

        // Render path lines
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos from = path.get(i);
            BlockPos to = path.get(i + 1);

            // Calculate gradient color based on position in path
            float progress = (float) i / (path.size() - 1);
            float[] color = lerpColor(START_COLOR, END_COLOR, progress);

            // Draw line from center of one block to center of next
            float x1 = from.getX() + 0.5f;
            float y1 = from.getY() + 0.5f;
            float z1 = from.getZ() + 0.5f;

            float x2 = to.getX() + 0.5f;
            float y2 = to.getY() + 0.5f;
            float z2 = to.getZ() + 0.5f;

            // Calculate normal for the line
            float dx = x2 - x1;
            float dy = y2 - y1;
            float dz = z2 - z1;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0) {
                dx /= len;
                dy /= len;
                dz /= len;
            }

            lineConsumer.addVertex(matrix, x1, y1, z1)
                    .setColor(color[0], color[1], color[2], color[3])
                    .setNormal(poseStack.last(), dx, dy, dz);

            lineConsumer.addVertex(matrix, x2, y2, z2)
                    .setColor(color[0], color[1], color[2], color[3])
                    .setNormal(poseStack.last(), dx, dy, dz);
        }

        // Flush the buffer
        bufferSource.endBatch(RenderType.lines());

        // Render node markers
        for (int i = 0; i < path.size(); i++) {
            BlockPos pos = path.get(i);
            float[] color;
            float size;

            if (i == 0) {
                color = START_COLOR;
                size = 0.3f;
            } else if (i == path.size() - 1) {
                color = END_COLOR;
                size = 0.3f;
            } else {
                color = NODE_COLOR;
                size = 0.15f;
            }

            renderNodeMarker(poseStack, bufferSource, pos, color, size);
        }
    }

    /**
     * Render a small cube marker at a node position
     */
    private static void renderNodeMarker(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
            BlockPos pos, float[] color, float size) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();

        float x = pos.getX() + 0.5f;
        float y = pos.getY() + 0.5f;
        float z = pos.getZ() + 0.5f;

        float half = size / 2;

        // Draw a wireframe cube
        // Bottom face
        drawLine(consumer, matrix, poseStack, x - half, y - half, z - half, x + half, y - half, z - half, color);
        drawLine(consumer, matrix, poseStack, x + half, y - half, z - half, x + half, y - half, z + half, color);
        drawLine(consumer, matrix, poseStack, x + half, y - half, z + half, x - half, y - half, z + half, color);
        drawLine(consumer, matrix, poseStack, x - half, y - half, z + half, x - half, y - half, z - half, color);

        // Top face
        drawLine(consumer, matrix, poseStack, x - half, y + half, z - half, x + half, y + half, z - half, color);
        drawLine(consumer, matrix, poseStack, x + half, y + half, z - half, x + half, y + half, z + half, color);
        drawLine(consumer, matrix, poseStack, x + half, y + half, z + half, x - half, y + half, z + half, color);
        drawLine(consumer, matrix, poseStack, x - half, y + half, z + half, x - half, y + half, z - half, color);

        // Vertical edges
        drawLine(consumer, matrix, poseStack, x - half, y - half, z - half, x - half, y + half, z - half, color);
        drawLine(consumer, matrix, poseStack, x + half, y - half, z - half, x + half, y + half, z - half, color);
        drawLine(consumer, matrix, poseStack, x + half, y - half, z + half, x + half, y + half, z + half, color);
        drawLine(consumer, matrix, poseStack, x - half, y - half, z + half, x - half, y + half, z + half, color);

        bufferSource.endBatch(RenderType.lines());
    }

    /**
     * Draw a single line segment
     */
    private static void drawLine(VertexConsumer consumer, Matrix4f matrix, PoseStack poseStack,
            float x1, float y1, float z1, float x2, float y2, float z2, float[] color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            dx /= len;
            dy /= len;
            dz /= len;
        }

        consumer.addVertex(matrix, x1, y1, z1)
                .setColor(color[0], color[1], color[2], color[3])
                .setNormal(poseStack.last(), dx, dy, dz);

        consumer.addVertex(matrix, x2, y2, z2)
                .setColor(color[0], color[1], color[2], color[3])
                .setNormal(poseStack.last(), dx, dy, dz);
    }

    /**
     * Linear interpolation between two colors
     */
    private static float[] lerpColor(float[] from, float[] to, float t) {
        return new float[] {
                from[0] + (to[0] - from[0]) * t,
                from[1] + (to[1] - from[1]) * t,
                from[2] + (to[2] - from[2]) * t,
                from[3] + (to[3] - from[3]) * t
        };
    }
}
