package nl.geocraft.overlay;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import nl.geocraft.overlay.markers.ForeignCheck;
import nl.geocraft.overlay.markers.MarkerProbe;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * World access for the marker scanner and the foreign check (common {@code markers} package),
 * and the names of the marker blocks. Client thread only. Yarn twin of the mojmap probe in
 * {@code fabric-26.x}; keep them in step.
 */
final class McMarkerProbe implements MarkerProbe {

    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private Block[] markers = new Block[0];
    private Set<Block> trunks = Set.of();
    private Block[] targets = new Block[0];

    private static ClientWorld world() {
        return MinecraftClient.getInstance().world;
    }

    static Block resolve(String name) {
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        Block block = id == null ? null : Registries.BLOCK.get(id);
        return block == null ? Blocks.AIR : block;
    }

    /**
     * The name of a marker block for the actionbar, in the language of the client ("Red Wool"),
     * so it is the name the player finds in the creative inventory; the id if the game has no
     * name for it. In 1.2.0 this was always the Dutch name, to match the site; the site
     * shows the English names since 13 November 2026.
     */
    static String displayName(String name) {
        Block block = resolve(name);
        if (block == Blocks.AIR) return name;
        String local = block.getName().getString();
        return local.equals(block.getTranslationKey()) ? name : local;
    }

    // -- MarkerProbe --------------------------------------------------

    @Override
    public void beginPlan(List<String> markerBlocks, Set<String> trunkBlocks) {
        markers = markerBlocks.stream().map(McMarkerProbe::resolve).toArray(Block[]::new);
        Set<Block> t = new HashSet<>();
        for (String name : trunkBlocks) {
            Block b = resolve(name);
            if (b != Blocks.AIR) t.add(b);
        }
        trunks = t;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ClientWorld w = world();
        return w != null && w.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public int kind(int x, int y, int z, int markerBlock) {
        ClientWorld w = world();
        if (w == null) return AIR;
        BlockState s = w.getBlockState(pos.set(x, y, z));
        if (markerBlock >= 0 && markerBlock < markers.length && s.isOf(markers[markerBlock])) return MARKER;
        if (s.isAir()) return AIR;
        if (!s.getFluidState().isEmpty()) return WATER;
        if (s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES) || trunks.contains(s.getBlock())) return TREE;
        if (s.isReplaceable()) return REPLACEABLE;
        return SOLID;
    }

    @Override
    public int surfaceY(int x, int z) {
        ClientWorld w = world();
        return w == null ? 64 : w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // -- Foreign check ------------------------------------------------

    @Override
    public void beginTargets(List<String> names) {
        targets = names.stream().map(McMarkerProbe::resolve).toArray(Block[]::new);
    }

    @Override
    public void scanChunk(int chunkX, int chunkZ, ForeignCheck.Hit out) {
        ClientWorld w = world();
        if (w == null) return;
        WorldChunk chunk = w.getChunk(chunkX, chunkZ);
        ChunkSection[] sections = chunk.getSectionArray();
        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null || section.isEmpty() || !section.hasAny(this::isTarget)) continue;
            int baseY = w.sectionIndexToCoord(si) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int t = targetIndex(section.getBlockState(x, y, z));
                        if (t >= 0) out.accept((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z, t);
                    }
                }
            }
        }
    }

    private boolean isTarget(BlockState s) {
        return targetIndex(s) >= 0;
    }

    private int targetIndex(BlockState s) {
        Block b = s.getBlock();
        for (int i = 0; i < targets.length; i++) if (targets[i] == b) return i;
        return -1;
    }
}
