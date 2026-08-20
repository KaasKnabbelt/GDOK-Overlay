package nl.geocraft.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import nl.geocraft.overlay.render.VertexSink;

/**
 * Forwards mesh vertices to Minecraft's {@link VertexConsumer}. One instance is reused for
 * every overlay and every frame; the per-pass constants (buffer, pose, colour) are fields,
 * so replaying a mesh allocates nothing.
 */
final class McVertexSink implements VertexSink {

    /** Packed full-bright light value (block=15, sky=15). */
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int NO_OVERLAY = OverlayTexture.NO_OVERLAY;

    private VertexConsumer buffer;
    private PoseStack.Pose pose;
    private int r, g, b, a;
    private long count;

    void begin(VertexConsumer buffer, PoseStack.Pose pose) {
        this.buffer = buffer;
        this.pose = pose;
    }

    /** Vertices emitted since the previous call (diagnostics). */
    long takeCount() {
        long c = count;
        count = 0;
        return c;
    }

    void setColor(int r, int g, int b, int a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    @Override
    public void vertex(float x, float y, float z, float u, float v, int face) {
        count++;
        VertexConsumer vc = buffer.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(NO_OVERLAY)
                .setLight(FULL_BRIGHT);
        switch (face) {
            case FACE_NORTH -> vc.setNormal(pose, 0f, 0f, -1f);
            case FACE_SOUTH -> vc.setNormal(pose, 0f, 0f, 1f);
            case FACE_WEST -> vc.setNormal(pose, -1f, 0f, 0f);
            case FACE_EAST -> vc.setNormal(pose, 1f, 0f, 0f);
            default -> vc.setNormal(pose, 0f, 1f, 0f);
        }
    }

    @Override
    public void flatVertex(float x, float y, float z) {
        buffer.addVertex(pose, x, y, z).setColor(r, g, b, a);
    }
}
