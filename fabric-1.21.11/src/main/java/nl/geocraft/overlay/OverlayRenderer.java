package nl.geocraft.overlay;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import nl.geocraft.overlay.markers.MarkerLayer;
import nl.geocraft.overlay.markers.MarkerManager;
import nl.geocraft.overlay.markers.MarkerRenderCore;
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

    private static final Identifier BLOCK_ATLAS = Identifier.of("minecraft", "textures/atlas/blocks.png");

    private final OverlayManager overlayManager;
    private final MeshCache cache = new MeshCache();
    private final OccupancyUpdater occupancy = new OccupancyUpdater();
    private final McVertexSink sink = new McVertexSink();
    private final RenderLayer slabRenderLayer = RenderLayers.entityTranslucent(BLOCK_ATLAS);
    /** Identity pose for the buffered draws: the replayed vertices are already camera-relative. */
    private final MatrixStack identityMatrices = new MatrixStack();
    private final BlockPos.Mutable probePos = new BlockPos.Mutable();
    private final OccupancyProbe probe = this::isOccupied;
    private final MeshCache.UvLookup uvLookup = this::lookupUv;
    private final MarkerRenderCore.UvLookup markerUvLookup = this::lookupUv;
    private final McMarkerProbe markerProbe = new McMarkerProbe();
    private final ViewFrustum frustum = new ViewFrustum();

    private long lastRevision = -1;
    private RenderMode lastMode = null;
    /** Sprite instance of the default block at the last reconcile; changes after a resource reload. */
    private Object canarySprite = null;

    // Per-frame state captured in render(), read by the deferred passes.
    private double camX, camY, camZ;
    private double maxDist;
    private int alpha;
    private boolean drawOverlays, drawMarkers;
    private long lastSlabVertices, lastMarkerVertices;
    /** Klik-marker-cel (of SKIP_NONE): andere overlays op dezelfde render-Y slaan die cel over. */
    private int markerX = BakedOverlayMesh.SKIP_NONE, markerZ, markerY;

    public OverlayRenderer(OverlayManager overlayManager) {
        this.overlayManager = overlayManager;
    }

    // -- Event hooks --------------------------------------------------

    public void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        if (context.worldState() == null) return;

        // Markerlagen van de bomenplannen (common markers-pakket) tekenen mee in dezelfde twee
        // passes, ook als er geen overlays zijn.
        List<MarkerLayer> markerLayers = MarkerManager.getInstance().layers();
        boolean drawMarkers = !markerLayers.isEmpty();

        boolean drawOverlays = true;
        if (overlayManager.getOverlays().isEmpty()) {
            if (!cache.isEmpty()) cache.clear();
            lastRevision = overlayManager.revision();
            drawOverlays = false;
        }
        if (overlayManager.isOverBlockLimit()) drawOverlays = false;
        if (!drawOverlays && !drawMarkers) return;

        RenderMode mode = OverlayConfig.getInstance().getRenderMode();
        Object canary = canarySprite(client);
        boolean uvChanged = canary != canarySprite;
        if (uvChanged) MarkerRenderCore.invalidateUvs(markerLayers);
        long revision = overlayManager.revision();
        if (drawOverlays && (revision != lastRevision || mode != lastMode || uvChanged)) {
            cache.reconcile(overlayManager.getOverlays(), mode, uvLookup, uvChanged);
            lastRevision = revision;
            lastMode = mode;
        }
        canarySprite = canary;
        if (cache.isEmpty()) drawOverlays = false;
        if (!drawOverlays && !drawMarkers) return;
        this.drawOverlays = drawOverlays;
        this.drawMarkers = drawMarkers;

        Vec3d cameraPos = context.worldState().cameraRenderState.pos;
        camX = cameraPos.x;
        camY = cameraPos.y;
        camZ = cameraPos.z;
        maxDist = client.options.getViewDistance().getValue() * 16.0;
        alpha = Math.max(1, Math.round(255 * OverlayConfig.getInstance().getOpacityMultiplier()));
        var camera = client.gameRenderer.getCamera();
        int w = client.getWindow().getFramebufferWidth(), h = client.getWindow().getFramebufferHeight();
        frustum.set(camX, camY, camZ, camera.getYaw(), camera.getPitch(),
                client.options.getFov().getValue(), h > 0 ? (double) w / h : 1.78);

        // Keep every mesh's render Y current (a change clears + dirties its occupancy bits).
        List<MeshCache.Entry> entries = drawOverlays ? cache.entries() : List.of();
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

        // Rechtstreeks tekenen, bínnen de main pass (dit draait op BEFORE_TRANSLUCENT: na de
        // entities, vóór het doorzichtige terrein). Niet via commandQueue().submitCustom: de
        // command queue wordt pas geleegd in de flush aan het begin van renderHand, ná de
        // depth-clear, waardoor de overlay over de arm/hand heen tekende (gametest stap 12).
        // De expliciete draw per layer is nodig omdat deze layers geen vast buffer in de
        // Immediate hebben; zonder draw zouden ze pas bij een willekeurige latere layer-wissel
        // op het scherm komen.
        MatrixStack.Entry identity = identityMatrices.peek();
        VertexConsumerProvider.Immediate consumers = (VertexConsumerProvider.Immediate) context.consumers();
        drawSlabs(identity, consumers.getBuffer(slabRenderLayer));
        consumers.draw(slabRenderLayer);
        RenderLayer borderLayer = RenderLayers.debugQuads();
        drawBorders(identity, consumers.getBuffer(borderLayer));
        consumers.draw(borderLayer);
    }

    /**
     * Called every client tick: budgeted occupancy rescans, nearest to the player first, and
     * the marker layers (site messages applied, statuses rescanned, progress to the site).
     */
    public void tick(MinecraftClient client) {
        double px = client.player != null ? client.player.getX() : camX;
        double pz = client.player != null ? client.player.getZ() : camZ;
        MarkerManager.getInstance().tick(client.world != null ? markerProbe : null, px, pz);
        if (client.world == null || cache.isEmpty()) return;
        occupancy.tick(cache.entries(), probe, px, pz);
    }

    /** World access for the markers (scanner, plant check). */
    public McMarkerProbe markerProbe() {
        return markerProbe;
    }

    /** Vertices emitted for marker ghosts in the last slab pass (diagnostics/gametests). */
    public long lastMarkerVertices() {
        return lastMarkerVertices;
    }

    /** Vertices emitted in the last slab pass (diagnostics/gametests). */
    public long lastSlabVertices() {
        return lastSlabVertices;
    }

    public void onChunkLoad(int chunkX, int chunkZ) {
        cache.markChunkDirty(chunkX, chunkZ);
        MarkerManager.getInstance().markChunkDirty(chunkX, chunkZ);
    }

    /**
     * A block was placed or broken at the given column (Fabric interaction events in
     * {@link GeoOverlayMod}): rescan that chunk's occupancy next tick instead of waiting
     * for the slow round-robin.
     */
    public void onBlockChanged(int x, int z) {
        cache.markChunkDirty(x >> 4, z >> 4);
        MarkerManager.getInstance().markChunkDirty(x >> 4, z >> 4);
    }

    // -- Passes -------------------------------------------------------

    private void drawSlabs(MatrixStack.Entry matrix, VertexConsumer buffer) {
        sink.begin(buffer, matrix);
        List<MeshCache.Entry> entries = drawOverlays ? cache.entries() : List.of();
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
        if (drawMarkers) {
            MarkerRenderCore.emitGhosts(MarkerManager.getInstance().layers(), sink, frustum, camX, camY, camZ, maxDist, alpha, markerUvLookup);
        }
        lastMarkerVertices = sink.takeCount();
    }

    private void drawBorders(MatrixStack.Entry matrix, VertexConsumer buffer) {
        sink.begin(buffer, matrix);
        if (drawMarkers) {
            MarkerRenderCore.emitFrames(MarkerManager.getInstance().layers(), sink, frustum, camX, camY, camZ, maxDist, alpha);
        }
        List<MeshCache.Entry> entries = drawOverlays ? cache.entries() : List.of();
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
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return false;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        return !world.getBlockState(probePos.set(x, y, z)).isAir();
    }

    private static Block resolveBlock(String tag) {
        Block block = null;
        if (tag != null) {
            Identifier id = Identifier.tryParse(tag.contains(":") ? tag : "minecraft:" + tag);
            if (id != null) block = Registries.BLOCK.get(id);
        }
        if (block == null || block == Blocks.AIR) block = Blocks.WHITE_WOOL;
        return block;
    }

    private static Sprite particleSprite(MinecraftClient client, Block block) {
        return client.getBlockRenderManager().getModels().getModelParticleSprite(block.getDefaultState());
    }

    private float[] lookupUv(String tag) {
        Sprite sprite = particleSprite(MinecraftClient.getInstance(), resolveBlock(tag));
        return new float[]{sprite.getMinU(), sprite.getMaxU(), sprite.getMinV(), sprite.getMaxV()};
    }

    private static Object canarySprite(MinecraftClient client) {
        return particleSprite(client, Blocks.WHITE_WOOL);
    }
}
