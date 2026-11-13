package nl.geocraft.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import nl.geocraft.overlay.markers.ForeignCheck;
import nl.geocraft.overlay.markers.MarkerProbe;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * World access for the marker scanner and the foreign check (common {@code markers} package),
 * and the names of the marker blocks. Client thread only. Mojmap twin of the yarn probe in
 * {@code fabric-1.21.11}; keep them in step.
 */
final class McMarkerProbe implements MarkerProbe {

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private Block[] markers = new Block[0];
    private Set<Block> trunks = Set.of();
    private Block[] targets = new Block[0];

    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    static Block resolve(String name) {
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
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
        return local.equals(block.getDescriptionId()) ? name : local;
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
        ClientLevel l = level();
        return l != null && l.hasChunk(chunkX, chunkZ);
    }

    @Override
    public int kind(int x, int y, int z, int markerBlock) {
        ClientLevel l = level();
        if (l == null) return AIR;
        BlockState s = l.getBlockState(pos.set(x, y, z));
        if (markerBlock >= 0 && markerBlock < markers.length && s.getBlock() == markers[markerBlock]) return MARKER;
        if (s.isAir()) return AIR;
        if (!s.getFluidState().isEmpty()) return WATER;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || trunks.contains(s.getBlock())) return TREE;
        if (s.canBeReplaced()) return REPLACEABLE;
        return SOLID;
    }

    @Override
    public int surfaceY(int x, int z) {
        ClientLevel l = level();
        return l == null ? 64 : l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // -- Foreign check ------------------------------------------------

    @Override
    public void beginTargets(List<String> names) {
        targets = names.stream().map(McMarkerProbe::resolve).toArray(Block[]::new);
    }

    @Override
    public void scanChunk(int chunkX, int chunkZ, ForeignCheck.Hit out) {
        ClientLevel l = level();
        if (l == null) return;
        LevelChunk chunk = l.getChunk(chunkX, chunkZ);
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(this::isTarget)) continue;
            int baseY = l.getSectionYFromSectionIndex(si) << 4;
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
