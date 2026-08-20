package nl.geocraft.overlay;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import nl.geocraft.overlay.render.VertexSink;

/**
 * Forwards mesh vertices to Minecraft's {@link VertexConsumer}. One instance is reused for
 * every overlay and every frame; the per-pass constants (buffer, matrix entry, colour) are
 * fields, so replaying a mesh allocates nothing.
 */
final class McVertexSink implements VertexSink {

    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final int NO_OVERLAY = OverlayTexture.DEFAULT_UV;

    private VertexConsumer buffer;
    private MatrixStack.Entry matrix;
    private int r, g, b, a;
    private long count;

    void begin(VertexConsumer buffer, MatrixStack.Entry matrix) {
        this.buffer = buffer;
        this.matrix = matrix;
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
        VertexConsumer vc = buffer.vertex(matrix, x, y, z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(NO_OVERLAY)
                .light(FULL_BRIGHT);
        switch (face) {
            case FACE_NORTH -> vc.normal(matrix, 0f, 0f, -1f);
            case FACE_SOUTH -> vc.normal(matrix, 0f, 0f, 1f);
            case FACE_WEST -> vc.normal(matrix, -1f, 0f, 0f);
            case FACE_EAST -> vc.normal(matrix, 1f, 0f, 0f);
            default -> vc.normal(matrix, 0f, 1f, 0f);
        }
    }

    @Override
    public void flatVertex(float x, float y, float z) {
        buffer.vertex(matrix, x, y, z).color(r, g, b, a);
    }
}
