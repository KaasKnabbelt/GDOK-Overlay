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

    public OverlayRenderer(OverlayManager overlayManager) {
        this.overlayManager = overlayManager;
    }

    // -- Event hooks --------------------------------------------------

    public void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        if (context.worldState() == null) return;

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
        List<MeshCache.Entry> entries = cache.entries();
        for (int i = 0, n = entries.size(); i < n; i++) {
            MeshCache.Entry e = entries.get(i);
            e.mesh.setRenderY(overlayManager.getRenderY(e.overlay));
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

    /** Called every client tick: budgeted occupancy rescans, nearest to the player first. */
    public void tick(MinecraftClient client) {
        if (client.world == null || cache.isEmpty()) return;
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

    private void drawSlabs(MatrixStack.Entry matrix, VertexConsumer buffer) {
        sink.begin(buffer, matrix);
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
            mesh.replaySlabs(sink, frustum, camX, camY, camZ, mesh.renderY(), maxDist);
        }
        lastSlabVertices = sink.takeCount();
    }

    private void drawBorders(MatrixStack.Entry matrix, VertexConsumer buffer) {
        sink.begin(buffer, matrix);
        List<MeshCache.Entry> entries = cache.entries();
        for (int i = 0, n = entries.size(); i < n; i++) {
            MeshCache.Entry e = entries.get(i);
            if (overlayManager.isHidden(e.overlay.id())) continue;
            sink.setColor(e.overlay.red(), e.overlay.green(), e.overlay.blue(), alpha);
            e.mesh.replayBorders(sink, frustum, camX, camY, camZ, e.mesh.renderY(), maxDist);
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
