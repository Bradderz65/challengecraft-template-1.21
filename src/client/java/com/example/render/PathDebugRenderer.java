package com.example.render;

import com.example.config.ModConfig;
import com.example.ai.PathDebugData;
import com.example.ai.BuildPlanData;
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
    private static final float[] BUILD_COLOR = { 0.0f, 1.0f, 0.3f, 1.0f }; // Green for build markers
    private static final float[] BREAK_COLOR = { 1.0f, 0.0f, 0.0f, 1.0f }; // Red for break markers

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

        // Render build plans (blocks to be placed)
        Map<UUID, List<BlockPos>> allBuildPlans = BuildPlanData.getAllBuildPlans();

        for (Map.Entry<UUID, List<BlockPos>> entry : allBuildPlans.entrySet()) {
            List<BlockPos> plan = entry.getValue();
            if (plan != null && !plan.isEmpty()) {
                renderBuildPlan(poseStack, bufferSource, plan);
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
            
            // Check if block is solid (needs breaking)
            boolean isSolid = Minecraft.getInstance().level.getBlockState(pos).blocksMotion();

            if (isSolid) {
                // Render full-size red wireframe for break targets
                renderBlockMarker(poseStack, bufferSource, pos, BREAK_COLOR, 0.005f);
            } else if (i == 0) {
                renderNodeMarker(poseStack, bufferSource, pos, START_COLOR, 0.3f);
            } else if (i == path.size() - 1) {
                renderNodeMarker(poseStack, bufferSource, pos, END_COLOR, 0.3f);
            } else {
                renderNodeMarker(poseStack, bufferSource, pos, NODE_COLOR, 0.15f);
            }
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

        // Draw a small wireframe cube
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

    /**
     * Render a build plan (blocks to be placed) as green wireframe cubes
     */
    private static void renderBuildPlan(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
            List<BlockPos> plan) {
        if (plan.isEmpty())
            return;

        for (BlockPos pos : plan) {
            // Render full-size green wireframe cube at block position
            renderBlockMarker(poseStack, bufferSource, pos, BUILD_COLOR, 0.0f);
        }
    }

    /**
     * Render a full block wireframe cube with optional inflation
     */
    private static void renderBlockMarker(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
            BlockPos pos, float[] color, float inflation) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();

        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();
        
        float minX = x - inflation;
        float minY = y - inflation;
        float minZ = z - inflation;
        float maxX = x + 1 + inflation;
        float maxY = y + 1 + inflation;
        float maxZ = z + 1 + inflation;

        // Draw a full block wireframe (1x1x1 cube)
        // Bottom face
        drawLine(consumer, matrix, poseStack, minX, minY, minZ, maxX, minY, minZ, color);
        drawLine(consumer, matrix, poseStack, maxX, minY, minZ, maxX, minY, maxZ, color);
        drawLine(consumer, matrix, poseStack, maxX, minY, maxZ, minX, minY, maxZ, color);
        drawLine(consumer, matrix, poseStack, minX, minY, maxZ, minX, minY, minZ, color);

        // Top face
        drawLine(consumer, matrix, poseStack, minX, maxY, minZ, maxX, maxY, minZ, color);
        drawLine(consumer, matrix, poseStack, maxX, maxY, minZ, maxX, maxY, maxZ, color);
        drawLine(consumer, matrix, poseStack, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        drawLine(consumer, matrix, poseStack, minX, maxY, maxZ, minX, maxY, minZ, color);

        // Vertical edges
        drawLine(consumer, matrix, poseStack, minX, minY, minZ, minX, maxY, minZ, color);
        drawLine(consumer, matrix, poseStack, maxX, minY, minZ, maxX, maxY, minZ, color);
        drawLine(consumer, matrix, poseStack, maxX, minY, maxZ, maxX, maxY, maxZ, color);
        drawLine(consumer, matrix, poseStack, minX, minY, maxZ, minX, maxY, maxZ, color);

        bufferSource.endBatch(RenderType.lines());
    }
}
