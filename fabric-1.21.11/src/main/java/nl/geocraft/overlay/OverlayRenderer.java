package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/**
 * Renders overlay blocks as thin textured slabs with neighbor-aware face culling.
 * Only exterior side faces are drawn; internal faces between adjacent overlay blocks are skipped.
 * Uses the 1.21.11 command-queue rendering API.
 */
public class OverlayRenderer {

    /** Height of the overlay slab (0.1 = carpet-thin) */
    private static final float SLAB_HEIGHT = 0.1f;
    /** Small offset above the Y level to float above terrain and prevent z-fighting */
    private static final float Y_OFFSET = 0.005f;

    private final OverlayManager overlayManager;

    public OverlayRenderer(OverlayManager overlayManager) {
        this.overlayManager = overlayManager;
    }

    public void render(WorldRenderContext context) {
        var overlays = overlayManager.getOverlays();
        if (overlays.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        if (context.worldState() == null) return;

        Vec3d cameraPos = context.worldState().cameraRenderState.pos;

        // Textured slab faces
        context.commandQueue().submitCustom(
                context.matrices(),
                RenderLayers.entityTranslucent(net.minecraft.util.Identifier.of("minecraft", "textures/atlas/blocks.png")),
                (matrixEntry, vertexConsumer) -> {
                    drawSlabOverlays(client, vertexConsumer, matrixEntry, cameraPos);
                }
        );

        // Thin border lines on top via debug layer (for occupied positions)
        context.commandQueue().submitCustom(
                context.matrices(),
                RenderLayers.debugQuads(),
                (matrixEntry, vertexConsumer) -> {
                    drawOccupiedBorders(client, vertexConsumer, matrixEntry, cameraPos);
                }
        );
    }

    private void drawSlabOverlays(MinecraftClient client, VertexConsumer buffer,
                                  MatrixStack.Entry matrixEntry, Vec3d cameraPos) {

        float opacityMul = OverlayConfig.getInstance().getOpacityMultiplier();
        double maxDistSq = getMaxRenderDistanceSq(client);

        for (OverlayData overlay : overlayManager.getOverlays()) {
            int r = overlay.red();
            int g = overlay.green();
            int b = overlay.blue();
            int a = Math.max(1, Math.round(255 * opacityMul));

            int overlayY = overlay.y();

            // Fetch block sprite for texturing (validate tag, fall back to white_wool)
            net.minecraft.util.Identifier blockId = net.minecraft.util.Identifier.tryParse("minecraft", overlay.tag());
            if (blockId == null) blockId = net.minecraft.util.Identifier.of("minecraft", "white_wool");
            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(blockId);
            if (block == net.minecraft.block.Blocks.AIR) {
                block = net.minecraft.block.Blocks.WHITE_WOOL;
            }
            net.minecraft.client.texture.Sprite sprite = client.getBlockRenderManager()
                    .getModels().getModelParticleSprite(block.getDefaultState());

            float u0 = sprite.getMinU();
            float u1 = sprite.getMaxU();
            float v0 = sprite.getMinV();
            float v1 = sprite.getMaxV();

            // Build a set of all block positions in this overlay for neighbor lookups
            Set<Long> positionSet = new HashSet<>(overlay.blocks().length * 2);
            for (OverlayData.BlockPos pos : overlay.blocks()) {
                positionSet.add(packPos(pos.x(), pos.z()));
            }

            for (OverlayData.BlockPos pos : overlay.blocks()) {
                // Skip blocks beyond render distance
                double dx = pos.x() + 0.5 - cameraPos.x;
                double dz = pos.z() + 0.5 - cameraPos.z;
                if (dx * dx + dz * dz > maxDistSq) continue;

                if (isOccupied(client, pos.x(), overlayY, pos.z())) continue;

                float x = (float) (pos.x() - cameraPos.x);
                float z = (float) (pos.z() - cameraPos.z);
                float y0 = (float) (overlayY - cameraPos.y) + Y_OFFSET;
                float y1 = y0 + SLAB_HEIGHT;

                // Check neighbors for face culling
                boolean hasNorth = positionSet.contains(packPos(pos.x(), pos.z() - 1));
                boolean hasSouth = positionSet.contains(packPos(pos.x(), pos.z() + 1));
                boolean hasWest  = positionSet.contains(packPos(pos.x() - 1, pos.z()));
                boolean hasEast  = positionSet.contains(packPos(pos.x() + 1, pos.z()));

                drawSlab(buffer, matrixEntry, x, y0, y1, z, r, g, b, a,
                        u0, u1, v0, v1, hasNorth, hasSouth, hasWest, hasEast);
            }
        }
    }

    /**
     * For occupied positions, draw a flat colored border on top of the real block.
     */
    private void drawOccupiedBorders(MinecraftClient client, VertexConsumer buffer,
                                     MatrixStack.Entry matrixEntry, Vec3d cameraPos) {

        float opacityMul = OverlayConfig.getInstance().getOpacityMultiplier();
        double maxDistSq = getMaxRenderDistanceSq(client);

        for (OverlayData overlay : overlayManager.getOverlays()) {
            int r = overlay.red();
            int g = overlay.green();
            int b = overlay.blue();
            int a = Math.max(1, Math.round(255 * opacityMul));

            int overlayY = overlay.y();

            // Build position set for neighbor lookups
            Set<Long> positionSet = new HashSet<>(overlay.blocks().length * 2);
            for (OverlayData.BlockPos pos : overlay.blocks()) {
                positionSet.add(packPos(pos.x(), pos.z()));
            }

            for (OverlayData.BlockPos pos : overlay.blocks()) {
                // Skip blocks beyond render distance
                double dx = pos.x() + 0.5 - cameraPos.x;
                double dz = pos.z() + 0.5 - cameraPos.z;
                if (dx * dx + dz * dz > maxDistSq) continue;

                if (!isOccupied(client, pos.x(), overlayY, pos.z())) continue;

                float x = (float) (pos.x() - cameraPos.x);
                float z = (float) (pos.z() - cameraPos.z);
                float yt = (float) (overlayY + 1 - cameraPos.y) + Y_OFFSET;

                // Only draw border edges on the outside (where no neighbor)
                boolean hasNorth = positionSet.contains(packPos(pos.x(), pos.z() - 1));
                boolean hasSouth = positionSet.contains(packPos(pos.x(), pos.z() + 1));
                boolean hasWest  = positionSet.contains(packPos(pos.x() - 1, pos.z()));
                boolean hasEast  = positionSet.contains(packPos(pos.x() + 1, pos.z()));

                drawTopBorder(buffer, matrixEntry, x, yt, z, r, g, b, a,
                        hasNorth, hasSouth, hasWest, hasEast);
            }
        }
    }

    /**
     * Draw a thin slab (top face always, side faces only where no neighbor).
     */
    private void drawSlab(VertexConsumer buffer, MatrixStack.Entry m,
                          float x, float y0, float y1, float z,
                          int r, int g, int b, int a,
                          float u0, float u1, float v0, float v1,
                          boolean hasNorth, boolean hasSouth, boolean hasWest, boolean hasEast) {

        int light = net.minecraft.client.render.LightmapTextureManager.MAX_LIGHT_COORDINATE;
        int overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;

        // Scale V coords for thin side faces (proportional to slab height)
        float vSide0 = v0;
        float vSide1 = v0 + (v1 - v0) * SLAB_HEIGHT;

        // Top face (always rendered)
        buffer.vertex(m, x, y1, z).color(r, g, b, a).texture(u0, v0).overlay(overlay).light(light).normal(m, 0, 1, 0);
        buffer.vertex(m, x, y1, z + 1).color(r, g, b, a).texture(u0, v1).overlay(overlay).light(light).normal(m, 0, 1, 0);
        buffer.vertex(m, x + 1, y1, z + 1).color(r, g, b, a).texture(u1, v1).overlay(overlay).light(light).normal(m, 0, 1, 0);
        buffer.vertex(m, x + 1, y1, z).color(r, g, b, a).texture(u1, v0).overlay(overlay).light(light).normal(m, 0, 1, 0);

        // Side faces only where no neighbor (exterior edges)
        if (!hasNorth) {
            buffer.vertex(m, x, y1, z).color(r, g, b, a).texture(u1, vSide0).overlay(overlay).light(light).normal(m, 0, 0, -1);
            buffer.vertex(m, x + 1, y1, z).color(r, g, b, a).texture(u0, vSide0).overlay(overlay).light(light).normal(m, 0, 0, -1);
            buffer.vertex(m, x + 1, y0, z).color(r, g, b, a).texture(u0, vSide1).overlay(overlay).light(light).normal(m, 0, 0, -1);
            buffer.vertex(m, x, y0, z).color(r, g, b, a).texture(u1, vSide1).overlay(overlay).light(light).normal(m, 0, 0, -1);
        }
        if (!hasSouth) {
            buffer.vertex(m, x + 1, y1, z + 1).color(r, g, b, a).texture(u1, vSide0).overlay(overlay).light(light).normal(m, 0, 0, 1);
            buffer.vertex(m, x, y1, z + 1).color(r, g, b, a).texture(u0, vSide0).overlay(overlay).light(light).normal(m, 0, 0, 1);
            buffer.vertex(m, x, y0, z + 1).color(r, g, b, a).texture(u0, vSide1).overlay(overlay).light(light).normal(m, 0, 0, 1);
            buffer.vertex(m, x + 1, y0, z + 1).color(r, g, b, a).texture(u1, vSide1).overlay(overlay).light(light).normal(m, 0, 0, 1);
        }
        if (!hasWest) {
            buffer.vertex(m, x, y1, z + 1).color(r, g, b, a).texture(u1, vSide0).overlay(overlay).light(light).normal(m, -1, 0, 0);
            buffer.vertex(m, x, y1, z).color(r, g, b, a).texture(u0, vSide0).overlay(overlay).light(light).normal(m, -1, 0, 0);
            buffer.vertex(m, x, y0, z).color(r, g, b, a).texture(u0, vSide1).overlay(overlay).light(light).normal(m, -1, 0, 0);
            buffer.vertex(m, x, y0, z + 1).color(r, g, b, a).texture(u1, vSide1).overlay(overlay).light(light).normal(m, -1, 0, 0);
        }
        if (!hasEast) {
            buffer.vertex(m, x + 1, y1, z).color(r, g, b, a).texture(u1, vSide0).overlay(overlay).light(light).normal(m, 1, 0, 0);
            buffer.vertex(m, x + 1, y1, z + 1).color(r, g, b, a).texture(u0, vSide0).overlay(overlay).light(light).normal(m, 1, 0, 0);
            buffer.vertex(m, x + 1, y0, z + 1).color(r, g, b, a).texture(u0, vSide1).overlay(overlay).light(light).normal(m, 1, 0, 0);
            buffer.vertex(m, x + 1, y0, z).color(r, g, b, a).texture(u1, vSide1).overlay(overlay).light(light).normal(m, 1, 0, 0);
        }
    }

    /**
     * Draw a thin colored border on top of occupied blocks (only exterior edges).
     */
    private void drawTopBorder(VertexConsumer buffer, MatrixStack.Entry m,
                               float x, float yt, float z,
                               int r, int g, int b, int a,
                               boolean hasNorth, boolean hasSouth, boolean hasWest, boolean hasEast) {
        float w = 0.06f; // border width

        // North edge
        if (!hasNorth) {
            buffer.vertex(m, x, yt, z).color(r, g, b, a);
            buffer.vertex(m, x, yt, z + w).color(r, g, b, a);
            buffer.vertex(m, x + 1, yt, z + w).color(r, g, b, a);
            buffer.vertex(m, x + 1, yt, z).color(r, g, b, a);
        }
        // South edge
        if (!hasSouth) {
            buffer.vertex(m, x, yt, z + 1 - w).color(r, g, b, a);
            buffer.vertex(m, x, yt, z + 1).color(r, g, b, a);
            buffer.vertex(m, x + 1, yt, z + 1).color(r, g, b, a);
            buffer.vertex(m, x + 1, yt, z + 1 - w).color(r, g, b, a);
        }
        // West edge
        if (!hasWest) {
            buffer.vertex(m, x, yt, z).color(r, g, b, a);
            buffer.vertex(m, x + w, yt, z).color(r, g, b, a);
            buffer.vertex(m, x + w, yt, z + 1).color(r, g, b, a);
            buffer.vertex(m, x, yt, z + 1).color(r, g, b, a);
        }
        // East edge
        if (!hasEast) {
            buffer.vertex(m, x + 1 - w, yt, z).color(r, g, b, a);
            buffer.vertex(m, x + 1, yt, z).color(r, g, b, a);
            buffer.vertex(m, x + 1, yt, z + 1).color(r, g, b, a);
            buffer.vertex(m, x + 1 - w, yt, z + 1).color(r, g, b, a);
        }
    }

    private static double getMaxRenderDistanceSq(MinecraftClient client) {
        int viewDistance = client.options.getViewDistance().getValue();
        double maxDist = viewDistance * 16.0;
        return maxDist * maxDist;
    }

    private static long packPos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private boolean isOccupied(MinecraftClient client, int x, int y, int z) {
        if (client.world == null) return false;
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!client.world.isChunkLoaded(chunkX, chunkZ)) return false;
        var pos = new net.minecraft.util.math.BlockPos(x, y, z);
        return !client.world.getBlockState(pos).isAir();
    }
}
