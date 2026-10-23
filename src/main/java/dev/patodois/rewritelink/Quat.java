package dev.patodois.rewritelink;

/** Quaternion helpers in the link's convention: {@code x, y, z, w}, unit length, +Z is an entity's front. */
public final class Quat {
    private Quat() {}

    public static final float[] IDENTITY = {0, 0, 0, 1};

    /** Rotates vector {@code v} by unit quaternion {@code q}. */
    public static float[] rotate(float[] q, float[] v) {
        // v' = v + 2w (u x v) + 2 u x (u x v), with u = (qx, qy, qz)
        float ux = q[0], uy = q[1], uz = q[2], w = q[3];
        float cx = uy * v[2] - uz * v[1];
        float cy = uz * v[0] - ux * v[2];
        float cz = ux * v[1] - uy * v[0];
        float dx = uy * cz - uz * cy;
        float dy = uz * cx - ux * cz;
        float dz = ux * cy - uy * cx;
        return new float[] {v[0] + 2 * (w * cx + dx), v[1] + 2 * (w * cy + dy), v[2] + 2 * (w * cz + dz)};
    }

    /** The shortest-arc rotation taking direction {@code a} onto direction {@code b} (no roll). */
    public static float[] fromTo(float[] a, float[] b) {
        float[] u = Wire.normalize(a), v = Wire.normalize(b);
        float d = Wire.dot(u, v);
        if (d < -0.999999f) {
            // Opposite directions: half a turn about any axis perpendicular to u.
            float[] axis = Wire.cross(u, Math.abs(u[0]) < 0.9f ? new float[] {1, 0, 0} : new float[] {0, 1, 0});
            axis = Wire.normalize(axis);
            return new float[] {axis[0], axis[1], axis[2], 0};
        }
        float[] c = Wire.cross(u, v);
        float[] q = {c[0], c[1], c[2], 1 + d};
        float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        return new float[] {q[0] / n, q[1] / n, q[2] / n, q[3] / n};
    }

    /** The unit quaternion for a rotation of {@code angle} radians about unit axis {@code axis}. */
    public static float[] axisAngle(float[] axis, float angle) {
        float s = (float) Math.sin(angle * 0.5);
        return new float[] {axis[0] * s, axis[1] * s, axis[2] * s, (float) Math.cos(angle * 0.5)};
    }
}
