package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.HashSet;
import java.util.Set;

/**
 * Renders overlay blocks as thin textured slabs with neighbor-aware face culling.
 * Only exterior side faces are drawn; internal faces between adjacent overlay blocks are skipped.
 * Migrated to Minecraft 26.1.1 unobfuscated API.
 */
public class OverlayRenderer {

    private static final float SLAB_HEIGHT = 0.1f;
    private static final float Y_OFFSET = 0.005f;
    /** Packed full-bright light value (block=15, sky=15) */
    private static final int FULL_BRIGHT = 0xF000F0;

    private final OverlayManager overlayManager;

    public OverlayRenderer(OverlayManager overlayManager) {
        this.overlayManager = overlayManager;
    }

    public void render(LevelRenderContext context) {
        var overlays = overlayManager.getOverlays();
        if (overlays.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        if (context.levelState() == null) return;

        Vec3 cameraPos = context.levelState().cameraRenderState.pos;

        // Textured slab faces
        context.submitNodeCollector().submitCustomGeometry(
                context.poseStack(),
                RenderTypes.entityTranslucent(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")),
                (matrixEntry, vertexConsumer) -> {
                    drawSlabOverlays(client, vertexConsumer, matrixEntry, cameraPos);
                }
        );

        // Thin border lines on top via debug layer (for occupied positions)
        context.submitNodeCollector().submitCustomGeometry(
                context.poseStack(),
                RenderTypes.debugQuads(),
                (matrixEntry, vertexConsumer) -> {
                    drawOccupiedBorders(client, vertexConsumer, matrixEntry, cameraPos);
                }
        );
    }

    private void drawSlabOverlays(Minecraft client, VertexConsumer buffer,
                                  PoseStack.Pose matrixEntry, Vec3 cameraPos) {

        float opacityMul = OverlayConfig.getInstance().getOpacityMultiplier();
        double maxDistSq = getMaxRenderDistanceSq(client);

        for (OverlayData overlay : overlayManager.getOverlays()) {
            int r = overlay.red();
            int g = overlay.green();
            int b = overlay.blue();
            int a = Math.max(1, Math.round(255 * opacityMul));

            int overlayY = overlay.y();

            // Fetch block sprite for texturing (validate tag, fall back to white_wool)
            Identifier blockId = Identifier.tryParse("minecraft:" + overlay.tag());
            if (blockId == null) blockId = Identifier.fromNamespaceAndPath("minecraft", "white_wool");
            Block block = BuiltInRegistries.BLOCK.getValue(blockId);
            if (block == Blocks.AIR) {
                block = Blocks.WHITE_WOOL;
            }
            TextureAtlasSprite sprite = client.getModelManager().getBlockStateModelSet().getParticleMaterial(block.defaultBlockState()).sprite();

            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();

            Set<Long> positionSet = new HashSet<>(overlay.blocks().length * 2);
            for (OverlayData.BlockPos pos : overlay.blocks()) {
                positionSet.add(packPos(pos.x(), pos.z()));
            }

            for (OverlayData.BlockPos pos : overlay.blocks()) {
                double dx = pos.x() + 0.5 - cameraPos.x;
                double dz = pos.z() + 0.5 - cameraPos.z;
                if (dx * dx + dz * dz > maxDistSq) continue;

                if (isOccupied(client, pos.x(), overlayY, pos.z())) continue;

                float x = (float) (pos.x() - cameraPos.x);
                float z = (float) (pos.z() - cameraPos.z);
                float y0 = (float) (overlayY - cameraPos.y) + Y_OFFSET;
                float y1 = y0 + SLAB_HEIGHT;

                boolean hasNorth = positionSet.contains(packPos(pos.x(), pos.z() - 1));
                boolean hasSouth = positionSet.contains(packPos(pos.x(), pos.z() + 1));
                boolean hasWest  = positionSet.contains(packPos(pos.x() - 1, pos.z()));
                boolean hasEast  = positionSet.contains(packPos(pos.x() + 1, pos.z()));

                drawSlab(buffer, matrixEntry, x, y0, y1, z, r, g, b, a,
                        u0, u1, v0, v1, hasNorth, hasSouth, hasWest, hasEast);
            }
        }
    }

    private void drawOccupiedBorders(Minecraft client, VertexConsumer buffer,
                                     PoseStack.Pose matrixEntry, Vec3 cameraPos) {

        float opacityMul = OverlayConfig.getInstance().getOpacityMultiplier();
        double maxDistSq = getMaxRenderDistanceSq(client);

        for (OverlayData overlay : overlayManager.getOverlays()) {
            int r = overlay.red();
            int g = overlay.green();
            int b = overlay.blue();
            int a = Math.max(1, Math.round(255 * opacityMul));

            int overlayY = overlay.y();

            Set<Long> positionSet = new HashSet<>(overlay.blocks().length * 2);
            for (OverlayData.BlockPos pos : overlay.blocks()) {
                positionSet.add(packPos(pos.x(), pos.z()));
            }

            for (OverlayData.BlockPos pos : overlay.blocks()) {
                double dx = pos.x() + 0.5 - cameraPos.x;
                double dz = pos.z() + 0.5 - cameraPos.z;
                if (dx * dx + dz * dz > maxDistSq) continue;

                if (!isOccupied(client, pos.x(), overlayY, pos.z())) continue;

                float x = (float) (pos.x() - cameraPos.x);
                float z = (float) (pos.z() - cameraPos.z);
                float yt = (float) (overlayY + 1 - cameraPos.y) + Y_OFFSET;

                boolean hasNorth = positionSet.contains(packPos(pos.x(), pos.z() - 1));
                boolean hasSouth = positionSet.contains(packPos(pos.x(), pos.z() + 1));
                boolean hasWest  = positionSet.contains(packPos(pos.x() - 1, pos.z()));
                boolean hasEast  = positionSet.contains(packPos(pos.x() + 1, pos.z()));

                drawTopBorder(buffer, matrixEntry, x, yt, z, r, g, b, a,
                        hasNorth, hasSouth, hasWest, hasEast);
            }
        }
    }

    private void drawSlab(VertexConsumer buffer, PoseStack.Pose m,
                          float x, float y0, float y1, float z,
                          int r, int g, int b, int a,
                          float u0, float u1, float v0, float v1,
                          boolean hasNorth, boolean hasSouth, boolean hasWest, boolean hasEast) {

        int light = FULL_BRIGHT;
        int overlay = OverlayTexture.NO_OVERLAY;

        float vSide0 = v0;
        float vSide1 = v0 + (v1 - v0) * SLAB_HEIGHT;

        // Top face (always rendered)
        buffer.addVertex(m, x, y1, z).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(m, 0, 1, 0);
        buffer.addVertex(m, x, y1, z + 1).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(m, 0, 1, 0);
        buffer.addVertex(m, x + 1, y1, z + 1).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(m, 0, 1, 0);
        buffer.addVertex(m, x + 1, y1, z).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(m, 0, 1, 0);

        if (!hasNorth) {
            buffer.addVertex(m, x, y1, z).setColor(r, g, b, a).setUv(u1, vSide0).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, -1);
            buffer.addVertex(m, x + 1, y1, z).setColor(r, g, b, a).setUv(u0, vSide0).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, -1);
            buffer.addVertex(m, x + 1, y0, z).setColor(r, g, b, a).setUv(u0, vSide1).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, -1);
            buffer.addVertex(m, x, y0, z).setColor(r, g, b, a).setUv(u1, vSide1).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, -1);
        }
        if (!hasSouth) {
            buffer.addVertex(m, x + 1, y1, z + 1).setColor(r, g, b, a).setUv(u1, vSide0).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, 1);
            buffer.addVertex(m, x, y1, z + 1).setColor(r, g, b, a).setUv(u0, vSide0).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, 1);
            buffer.addVertex(m, x, y0, z + 1).setColor(r, g, b, a).setUv(u0, vSide1).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, 1);
            buffer.addVertex(m, x + 1, y0, z + 1).setColor(r, g, b, a).setUv(u1, vSide1).setOverlay(overlay).setLight(light).setNormal(m, 0, 0, 1);
        }
        if (!hasWest) {
            buffer.addVertex(m, x, y1, z + 1).setColor(r, g, b, a).setUv(u1, vSide0).setOverlay(overlay).setLight(light).setNormal(m, -1, 0, 0);
            buffer.addVertex(m, x, y1, z).setColor(r, g, b, a).setUv(u0, vSide0).setOverlay(overlay).setLight(light).setNormal(m, -1, 0, 0);
            buffer.addVertex(m, x, y0, z).setColor(r, g, b, a).setUv(u0, vSide1).setOverlay(overlay).setLight(light).setNormal(m, -1, 0, 0);
            buffer.addVertex(m, x, y0, z + 1).setColor(r, g, b, a).setUv(u1, vSide1).setOverlay(overlay).setLight(light).setNormal(m, -1, 0, 0);
        }
        if (!hasEast) {
            buffer.addVertex(m, x + 1, y1, z).setColor(r, g, b, a).setUv(u1, vSide0).setOverlay(overlay).setLight(light).setNormal(m, 1, 0, 0);
            buffer.addVertex(m, x + 1, y1, z + 1).setColor(r, g, b, a).setUv(u0, vSide0).setOverlay(overlay).setLight(light).setNormal(m, 1, 0, 0);
            buffer.addVertex(m, x + 1, y0, z + 1).setColor(r, g, b, a).setUv(u0, vSide1).setOverlay(overlay).setLight(light).setNormal(m, 1, 0, 0);
            buffer.addVertex(m, x + 1, y0, z).setColor(r, g, b, a).setUv(u1, vSide1).setOverlay(overlay).setLight(light).setNormal(m, 1, 0, 0);
        }
    }

    private void drawTopBorder(VertexConsumer buffer, PoseStack.Pose m,
                               float x, float yt, float z,
                               int r, int g, int b, int a,
                               boolean hasNorth, boolean hasSouth, boolean hasWest, boolean hasEast) {
        float w = 0.06f;

        if (!hasNorth) {
            buffer.addVertex(m, x, yt, z).setColor(r, g, b, a);
            buffer.addVertex(m, x, yt, z + w).setColor(r, g, b, a);
            buffer.addVertex(m, x + 1, yt, z + w).setColor(r, g, b, a);
            buffer.addVertex(m, x + 1, yt, z).setColor(r, g, b, a);
        }
        if (!hasSouth) {
            buffer.addVertex(m, x, yt, z + 1 - w).setColor(r, g, b, a);
            buffer.addVertex(m, x, yt, z + 1).setColor(r, g, b, a);
            buffer.addVertex(m, x + 1, yt, z + 1).setColor(r, g, b, a);
            buffer.addVertex(m, x + 1, yt, z + 1 - w).setColor(r, g, b, a);
        }
        if (!hasWest) {
            buffer.addVertex(m, x, yt, z).setColor(r, g, b, a);
            buffer.addVertex(m, x + w, yt, z).setColor(r, g, b, a);
            buffer.addVertex(m, x + w, yt, z + 1).setColor(r, g, b, a);
            buffer.addVertex(m, x, yt, z + 1).setColor(r, g, b, a);
        }
        if (!hasEast) {
            buffer.addVertex(m, x + 1 - w, yt, z).setColor(r, g, b, a);
            buffer.addVertex(m, x + 1, yt, z).setColor(r, g, b, a);
            buffer.addVertex(m, x + 1, yt, z + 1).setColor(r, g, b, a);
            buffer.addVertex(m, x + 1 - w, yt, z + 1).setColor(r, g, b, a);
        }
    }

    private static double getMaxRenderDistanceSq(Minecraft client) {
        int viewDistance = client.options.renderDistance().get();
        double maxDist = viewDistance * 16.0;
        return maxDist * maxDist;
    }

    private static long packPos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private boolean isOccupied(Minecraft client, int x, int y, int z) {
        if (client.level == null) return false;
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!client.level.hasChunk(chunkX, chunkZ)) return false;
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        return !client.level.getBlockState(pos).isAir();
    }
}
