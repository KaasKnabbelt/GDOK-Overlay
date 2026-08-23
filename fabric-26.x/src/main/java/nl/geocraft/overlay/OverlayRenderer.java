package nl.geocraft.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import nl.geocraft.overlay.render.BakedOverlayMesh;
import nl.geocraft.overlay.render.MeshCache;
import nl.geocraft.overlay.render.OccupancyProbe;
import nl.geocraft.overlay.render.OccupancyUpdater;
import nl.geocraft.overlay.render.RenderMode;
import nl.geocraft.overlay.render.ViewFrustum;

import java.util.List;

/**
 * Thin Minecraft-side shim around the cached geometry in {@code common/}: event hook,
 * gate/limit checks, mesh-cache reconciliation (sprite lookup only when a mesh is rebuilt),
 * two submits with pre-allocated callbacks and the per-overlay replay. All geometry lives in
 * {@link BakedOverlayMesh}; see {@code nl.geocraft.overlay.render}.
 */
public class OverlayRenderer {

    private static final Identifier BLOCK_ATLAS = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");

    private final OverlayManager overlayManager;
    private final MeshCache cache = new MeshCache();
    private final OccupancyUpdater occupancy = new OccupancyUpdater();
    private final McVertexSink sink = new McVertexSink();
    private final RenderType slabRenderType = RenderTypes.entityTranslucent(BLOCK_ATLAS);
    private final SubmitNodeCollector.CustomGeometryRenderer slabPass = this::drawSlabs;
    private final SubmitNodeCollector.CustomGeometryRenderer borderPass = this::drawBorders;
    private final BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();
    private final OccupancyProbe probe = this::isOccupied;
    private final MeshCache.UvLookup uvLookup = this::lookupUv;
    private final ViewFrustum frustum = new ViewFrustum();

    private long lastRevision = -1;
    private RenderMode lastMode = null;
    /** Sprite instance of the default block at the last reconcile; changes after a resource reload. */
    private Object canarySprite = null;

    // Per-frame state captured in render(), read by the deferred passes.
    private double camX, camY, camZ;
    private double maxDist;
    private int alpha;
    private long lastSlabVertices;
    /** Klik-marker-cel (of SKIP_NONE): andere overlays op dezelfde render-Y slaan die cel over. */
    private int markerX = BakedOverlayMesh.SKIP_NONE, markerZ, markerY;

    public OverlayRenderer(OverlayManager overlayManager) {
        this.overlayManager = overlayManager;
    }

    // -- Event hooks --------------------------------------------------

    public void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        if (context.levelState() == null) return;

        if (overlayManager.getOverlays().isEmpty()) {
            if (!cache.isEmpty()) cache.clear();
            lastRevision = overlayManager.revision();
            return;
        }
        if (overlayManager.isOverBlockLimit()) return;

        RenderMode mode = OverlayConfig.getInstance().getRenderMode();
        Object canary = canarySprite(client);
        boolean uvChanged = canary != canarySprite;
        long revision = overlayManager.revision();
        if (revision != lastRevision || mode != lastMode || uvChanged) {
            cache.reconcile(overlayManager.getOverlays(), mode, uvLookup, uvChanged);
            lastRevision = revision;
            lastMode = mode;
            canarySprite = canary;
        }
        if (cache.isEmpty()) return;

        Vec3 cameraPos = context.levelState().cameraRenderState.pos;
        camX = cameraPos.x;
        camY = cameraPos.y;
        camZ = cameraPos.z;
        maxDist = client.options.renderDistance().get() * 16.0;
        alpha = Math.max(1, Math.round(255 * OverlayConfig.getInstance().getOpacityMultiplier()));
        var camera = client.gameRenderer.mainCamera();
        int w = client.getWindow().getWidth(), h = client.getWindow().getHeight();
        frustum.set(camX, camY, camZ, camera.yRot(), camera.xRot(),
                client.options.fov().get(), h > 0 ? (double) w / h : 1.78);

        // Keep every mesh's render Y current (a change clears + dirties its occupancy bits).
        List<MeshCache.Entry> entries = cache.entries();
        for (int i = 0, n = entries.size(); i < n; i++) {
            MeshCache.Entry e = entries.get(i);
            e.mesh.setRenderY(overlayManager.getRenderY(e.overlay));
        }

        // Marker-prioriteit: staat de klik-marker in een andere overlay op dezelfde hoogte,
        // dan zou hij in dat vlak wegvallen (z-fight, of in fullblock-modus er volledig in
        // opgesloten zitten). Die overlays slaan de marker-cel daarom over in de replay.
        markerX = BakedOverlayMesh.SKIP_NONE;
        for (int i = 0, n = entries.size(); i < n; i++) {
            MeshCache.Entry e = entries.get(i);
            if (!OverlayData.CLICK_ID.equals(e.overlay.id())) continue;
            if (!overlayManager.isHidden(e.overlay.id()) && e.mesh.blockCount() == 1) {
                markerX = e.overlay.blocks()[0].x();
                markerZ = e.overlay.blocks()[0].z();
                markerY = e.mesh.renderY();
            }
            break;
        }

        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), slabRenderType, slabPass);
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), borderPass);
    }

    /** Called every client tick: budgeted occupancy rescans, nearest to the player first. */
    public void tick(Minecraft client) {
        if (client.level == null || cache.isEmpty()) return;
        double px = client.player != null ? client.player.getX() : camX;
        double pz = client.player != null ? client.player.getZ() : camZ;
        occupancy.tick(cache.entries(), probe, px, pz);
    }

    /** Vertices emitted in the last slab pass (diagnostics/gametests). */
    public long lastSlabVertices() {
        return lastSlabVertices;
    }

    public void onChunkLoad(int chunkX, int chunkZ) {
        cache.markChunkDirty(chunkX, chunkZ);
    }

    /**
     * A block was placed or broken at the given column (Fabric interaction events in
     * {@link GeoOverlayMod}): rescan that chunk's occupancy next tick instead of waiting
     * for the slow round-robin.
     */
    public void onBlockChanged(int x, int z) {
        cache.markChunkDirty(x >> 4, z >> 4);
    }

    // -- Passes -------------------------------------------------------

    private void drawSlabs(PoseStack.Pose pose, VertexConsumer buffer) {
        sink.begin(buffer, pose);
        List<MeshCache.Entry> entries = cache.entries();
        for (int i = 0, n = entries.size(); i < n; i++) {
            MeshCache.Entry e = entries.get(i);
            if (overlayManager.isHidden(e.overlay.id())) continue;
            BakedOverlayMesh mesh = e.mesh;
            if (mesh.isTinted()) {
                sink.setColor(e.overlay.red(), e.overlay.green(), e.overlay.blue(), alpha);
            } else {
                sink.setColor(255, 255, 255, alpha);
            }
            boolean yield = markerX != BakedOverlayMesh.SKIP_NONE
                    && mesh.renderY() == markerY
                    && !OverlayData.CLICK_ID.equals(e.overlay.id());
            mesh.replaySlabs(sink, frustum, camX, camY, camZ, mesh.renderY(), maxDist,
                    yield ? markerX : BakedOverlayMesh.SKIP_NONE, yield ? markerZ : 0);
        }
        lastSlabVertices = sink.takeCount();
    }

    private void drawBorders(PoseStack.Pose pose, VertexConsumer buffer) {
        sink.begin(buffer, pose);
        List<MeshCache.Entry> entries = cache.entries();
        for (int i = 0, n = entries.size(); i < n; i++) {
            MeshCache.Entry e = entries.get(i);
            if (overlayManager.isHidden(e.overlay.id())) continue;
            sink.setColor(e.overlay.red(), e.overlay.green(), e.overlay.blue(), alpha);
            boolean yield = markerX != BakedOverlayMesh.SKIP_NONE
                    && e.mesh.renderY() == markerY
                    && !OverlayData.CLICK_ID.equals(e.overlay.id());
            e.mesh.replayBorders(sink, frustum, camX, camY, camZ, e.mesh.renderY(), maxDist,
                    yield ? markerX : BakedOverlayMesh.SKIP_NONE, yield ? markerZ : 0);
        }
    }

    // -- World / sprite access ----------------------------------------

    private boolean isOccupied(int x, int y, int z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        return !level.getBlockState(probePos.set(x, y, z)).isAir();
    }

    private static Block resolveBlock(String tag) {
        Block block = null;
        if (tag != null) {
            Identifier id = Identifier.tryParse(tag.contains(":") ? tag : "minecraft:" + tag);
            if (id != null) block = BuiltInRegistries.BLOCK.getValue(id);
        }
        if (block == null || block == Blocks.AIR) block = Blocks.WOOL.white();
        return block;
    }

    private static TextureAtlasSprite particleSprite(Minecraft client, Block block) {
        return client.getModelManager().getBlockStateModelSet()
                .getParticleMaterial(block.defaultBlockState()).sprite();
    }

    private float[] lookupUv(String tag) {
        TextureAtlasSprite sprite = particleSprite(Minecraft.getInstance(), resolveBlock(tag));
        return new float[]{sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1()};
    }

    private static Object canarySprite(Minecraft client) {
        return particleSprite(client, Blocks.WOOL.white());
    }
}
