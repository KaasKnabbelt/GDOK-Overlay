package nl.geocraft.overlay.render;

/**
 * Conservative view frustum for bucket culling, independent of any Minecraft type.
 *
 * <p>Built from the camera position, its yaw/pitch and the vertical field of view. Only the
 * four side planes are used (near/far are irrelevant: distance culling is separate). The
 * half-angles are widened by {@link #FOV_MARGIN} so that dynamic FOV effects (sprinting,
 * speed) and small errors never cull a visible bucket: culling too little costs a few
 * vertices, culling too much makes overlays pop.</p>
 *
 * <p>{@link #set} is called once per frame; {@link #intersectsSphere} allocates nothing.</p>
 */
public final class ViewFrustum {

    /** Multiplier on tan(fov/2); 1.25 covers Minecraft's dynamic FOV effects (max ~1.1x) with room to spare. */
    public static final double FOV_MARGIN = 1.25;

    /** Bounding-sphere radius of a 16x16 bucket that is one block high: sqrt(8² + 8² + 0.5²). */
    public static final double BUCKET_RADIUS = 11.4;

    // Camera position
    private double cx, cy, cz;
    // Plane normals (unit, pointing inward): left, right, top, bottom
    private final double[] nx = new double[4], ny = new double[4], nz = new double[4];
    private boolean valid = false;

    /**
     * @param yawDeg   camera yaw in degrees, Minecraft convention (0 = +z/south, 90 = -x/west)
     * @param pitchDeg camera pitch in degrees, positive = looking down
     * @param verticalFovDeg vertical field of view in degrees (the "FOV" option)
     * @param aspect window width / height
     */
    public void set(double camX, double camY, double camZ,
                    double yawDeg, double pitchDeg,
                    double verticalFovDeg, double aspect) {
        this.cx = camX;
        this.cy = camY;
        this.cz = camZ;

        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        // Minecraft camera basis: forward, up, left.
        double fx = -sy * cp, fy = -sp, fz = cy * cp;
        double ux = -sy * sp, uy = cp, uz = cy * sp;
        double lx = cy, ly = 0, lz = sy;

        double tanV = Math.tan(Math.toRadians(Math.max(1, Math.min(179, verticalFovDeg))) / 2) * FOV_MARGIN;
        double tanH = tanV * Math.max(0.1, aspect);

        // A point p is inside the horizontal extent when |(p-c)·l| <= ((p-c)·f)·tanH, i.e. both
        // (p-c)·(f·tanH - l) >= 0 and (p-c)·(f·tanH + l) >= 0; same vertically with u.
        plane(0, fx * tanH - lx, fy * tanH - ly, fz * tanH - lz);
        plane(1, fx * tanH + lx, fy * tanH + ly, fz * tanH + lz);
        plane(2, fx * tanV - ux, fy * tanV - uy, fz * tanV - uz);
        plane(3, fx * tanV + ux, fy * tanV + uy, fz * tanV + uz);
        valid = true;
    }

    private void plane(int i, double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) {
            nx[i] = ny[i] = nz[i] = 0;
            return;
        }
        nx[i] = x / len;
        ny[i] = y / len;
        nz[i] = z / len;
    }

    public void invalidate() {
        valid = false;
    }

    public boolean isValid() {
        return valid;
    }

    /** True when a sphere may intersect the view frustum. Always true when the frustum is not set. */
    public boolean intersectsSphere(double sx, double sy, double sz, double radius) {
        if (!valid) return true;
        double dx = sx - cx, dy = sy - cy, dz = sz - cz;
        for (int i = 0; i < 4; i++) {
            if (dx * nx[i] + dy * ny[i] + dz * nz[i] < -radius) return false;
        }
        return true;
    }
}
