package dev.patodois.rewritelink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary datagram layouts of the passthrough link, protocol v1 (docs/CONTRACT.md section 9 and
 * 10 of the rewrite-bridge repository). Everything is little-endian, with no padding.
 *
 * <p>Unsigned 32-bit fields (session, seq, entity) are carried in Java {@code int} or
 * {@code long} as documented on each record; use {@link Integer#toUnsignedLong(int)} to print.
 */
public final class Wire {
    private Wire() {}

    public static final int MAGIC = 0x4B4C4252; // bytes 'R' 'B' 'L' 'K'
    public static final int PROTOCOL_VERSION = 1;
    public static final int MIN_PROTOCOL_VERSION = 1;
    public static final int DEFAULT_CONTROL_PORT = 47800;

    public static final int HEADER_LEN = 24;
    public static final int MAX_DATAGRAM = 8192;
    public static final int CAMERA_LEN = 152;
    public static final int POSE_HEADER_LEN = 8;
    public static final int POSE_LEN = 80;
    public static final int MAX_POSES = (MAX_DATAGRAM - HEADER_LEN - POSE_HEADER_LEN) / POSE_LEN; // 102
    public static final int COLLISION_HEADER_LEN = 32;
    public static final int TRIANGLE_LEN = 36;
    public static final int MAX_TRIANGLES = (MAX_DATAGRAM - HEADER_LEN - COLLISION_HEADER_LEN) / TRIANGLE_LEN; // 226
    public static final int FRAME_LEN = 24;
    public static final int MAX_LINE = 65536;
    public static final int MAX_PENDING_CONTROL = 1 << 20;
    public static final int LINGER_MS = 200;

    public static final int POSE_TELEPORT = 1;

    public static final int KIND_HEARTBEAT = 1;
    public static final int KIND_CAMERA = 2;
    public static final int KIND_POSE = 3;
    public static final int KIND_COLLISION = 4;
    public static final int KIND_FRAME = 5;

    /** A datagram that cannot be encoded or decoded. */
    public static final class WireException extends Exception {
        private static final long serialVersionUID = 1L;

        public WireException(String message) {
            super(message);
        }
    }

    /** Microseconds since the Unix epoch, wall clock. */
    public static long nowUs() {
        java.time.Instant i = java.time.Instant.now();
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1000;
    }

    /** RFC 1982 serial comparison on 32-bit counters: is {@code a} newer than {@code b}? */
    public static boolean seqNewer(int a, int b) {
        return (a - b) > 0;
    }

    /** The session version two sides agree on, or -1 when their ranges do not overlap. */
    public static int negotiate(int hostMin, int hostMax, int guestMin, int guestMax) {
        int v = Math.min(hostMax, guestMax);
        return v >= Math.max(hostMin, guestMin) ? v : -1;
    }

    // ------------------------------------------------------------------ message types

    /** The 24-byte header. {@code session} and {@code seq} are u32 stored in an int. */
    public record Header(int version, int flags, int session, int seq, long timeUs) {}

    public enum DepthRange {
        NEG_ONE_TO_ONE(0), ZERO_TO_ONE(1), REVERSED(2);

        public final int code;

        DepthRange(int code) {
            this.code = code;
        }

        static DepthRange of(int code) throws WireException {
            for (DepthRange d : values()) {
                if (d.code == code) return d;
            }
            throw new WireException("bad value: depth_range " + code);
        }
    }

    /** The host's view. Matrices are column-major, {@code m[c*4 + r]}. */
    public record Camera(
            float[] view, float[] proj, float fovY, float near, float far,
            int viewportWidth, int viewportHeight, DepthRange depthRange) {

        /** A right-handed look-at camera with an OpenGL-style perspective projection. */
        public static Camera lookAt(float[] eye, float[] target, float fovY, float near, float far,
                                    int width, int height) {
            float[] f = normalize(sub(target, eye));
            float[] s = normalize(cross(f, new float[] {0, 1, 0}));
            float[] u = cross(s, f);
            float[] view = {
                s[0], u[0], -f[0], 0,
                s[1], u[1], -f[1], 0,
                s[2], u[2], -f[2], 0,
                -dot(s, eye), -dot(u, eye), dot(f, eye), 1,
            };
            float aspect = (float) width / Math.max(height, 1);
            float t = (float) (1.0 / Math.tan(fovY * 0.5));
            float[] proj = new float[16];
            proj[0] = t / aspect;
            proj[5] = t;
            proj[11] = -1;
            if (Float.isFinite(far)) {
                proj[10] = (far + near) / (near - far);
                proj[14] = 2 * far * near / (near - far);
            } else {
                proj[10] = -1;
                proj[14] = -2 * near;
            }
            return new Camera(view, proj, fovY, near, far, width, height, DepthRange.NEG_ONE_TO_ONE);
        }

        /** The camera's world position, assuming {@code view} is a rigid transform. */
        public float[] eye() {
            float[] t = {view[12], view[13], view[14]};
            float[] e = new float[3];
            for (int c = 0; c < 3; c++) {
                float s = 0;
                for (int r = 0; r < 3; r++) s += view[c * 4 + r] * t[r];
                e[c] = -s;
            }
            return e;
        }

        /** The direction the camera looks in, world space. */
        public float[] forward() {
            return new float[] {-view[2], -view[6], -view[10]};
        }
    }

    /** One entity pose, world space. {@code entity} is a u32 in a long. */
    public record Pose(
            long entity, int flags, long timeUs,
            float[] translation, float[] rotation, float[] scale,
            float[] velocity, float[] angularVelocity) {

        /** An identity pose at the origin, at rest. */
        public static Pose identity(long entity, long timeUs) {
            return new Pose(entity, 0, timeUs, new float[3], new float[] {0, 0, 0, 1},
                    new float[] {1, 1, 1}, new float[3], new float[3]);
        }
    }

    /** One datagram's share of a collision snapshot; each triangle is nine floats a, b, c. */
    public record CollisionChunk(
            int query, int chunkIndex, int chunkCount, float[] center, float radius,
            List<float[]> triangles) {}

    public record FrameReady(long frame, int slot, int cameraSeq, int width, int height) {}

    public sealed interface Datagram {
        record Heartbeat() implements Datagram {}
        record CameraMsg(Camera camera) implements Datagram {}
        record PoseMsg(List<Pose> poses) implements Datagram {}
        record CollisionMsg(CollisionChunk chunk) implements Datagram {}
        record FrameMsg(FrameReady frame) implements Datagram {}
        record Unknown(int kind, int payloadLen) implements Datagram {}

    }

    // ------------------------------------------------------------------ encode

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void putFloats(ByteBuffer b, float[] v, int n) throws WireException {
        if (v == null || v.length != n) throw new WireException("expected " + n + " floats");
        for (float x : v) b.putFloat(x);
    }

    public static int kindOf(Datagram d) {
        return switch (d) {
            case Datagram.Heartbeat h -> KIND_HEARTBEAT;
            case Datagram.CameraMsg c -> KIND_CAMERA;
            case Datagram.PoseMsg p -> KIND_POSE;
            case Datagram.CollisionMsg c -> KIND_COLLISION;
            case Datagram.FrameMsg f -> KIND_FRAME;
            case Datagram.Unknown u -> u.kind();
        };
    }

    /** Encodes {@code header} and {@code body} into a fresh array of exactly the datagram's size. */
    public static byte[] encode(Header h, Datagram body) throws WireException {
        int payload = switch (body) {
            case Datagram.Heartbeat x -> 0;
            case Datagram.CameraMsg x -> CAMERA_LEN;
            case Datagram.PoseMsg x -> {
                int n = x.poses().size();
                if (n < 1 || n > MAX_POSES) throw new WireException("pose count " + n);
                yield POSE_HEADER_LEN + POSE_LEN * n;
            }
            case Datagram.CollisionMsg x -> {
                int n = x.chunk().triangles().size();
                if (n > MAX_TRIANGLES) throw new WireException("triangle count " + n);
                yield COLLISION_HEADER_LEN + TRIANGLE_LEN * n;
            }
            case Datagram.FrameMsg x -> FRAME_LEN;
            case Datagram.Unknown x -> throw new WireException("cannot encode an unknown kind");
        };
        ByteBuffer b = buffer(HEADER_LEN + payload);
        b.putInt(MAGIC);
        b.putShort((short) h.version());
        b.put((byte) kindOf(body));
        b.put((byte) h.flags());
        b.putInt(h.session());
        b.putInt(h.seq());
        b.putLong(h.timeUs());
        switch (body) {
            case Datagram.Heartbeat x -> { }
            case Datagram.CameraMsg x -> {
                Camera c = x.camera();
                putFloats(b, c.view(), 16);
                putFloats(b, c.proj(), 16);
                b.putFloat(c.fovY()).putFloat(c.near()).putFloat(c.far());
                b.putInt(c.viewportWidth()).putInt(c.viewportHeight()).putInt(c.depthRange().code);
            }
            case Datagram.PoseMsg x -> {
                b.putInt(x.poses().size()).putInt(0);
                for (Pose p : x.poses()) {
                    b.putInt((int) p.entity()).putInt(p.flags()).putLong(p.timeUs());
                    putFloats(b, p.translation(), 3);
                    putFloats(b, p.rotation(), 4);
                    putFloats(b, p.scale(), 3);
                    putFloats(b, p.velocity(), 3);
                    putFloats(b, p.angularVelocity(), 3);
                }
            }
            case Datagram.CollisionMsg x -> {
                CollisionChunk c = x.chunk();
                b.putInt(c.query()).putShort((short) c.chunkIndex()).putShort((short) c.chunkCount());
                putFloats(b, c.center(), 3);
                b.putFloat(c.radius()).putInt(c.triangles().size()).putInt(0);
                for (float[] t : c.triangles()) putFloats(b, t, 9);
            }
            case Datagram.FrameMsg x -> {
                FrameReady f = x.frame();
                b.putLong(f.frame()).putInt(f.slot()).putInt(f.cameraSeq())
                        .putInt(f.width()).putInt(f.height());
            }
            case Datagram.Unknown x -> { }
        }
        return b.array();
    }

    // ------------------------------------------------------------------ decode

    /** Reads the header only, checking length, magic and version. {@code kindOut[0]} gets the kind. */
    public static Header decodeHeader(byte[] buf, int len, int[] kindOut) throws WireException {
        if (len < HEADER_LEN) throw new WireException("truncated: need 24 bytes, got " + len);
        ByteBuffer b = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        int magic = b.getInt();
        if (magic != MAGIC) throw new WireException(String.format("bad magic 0x%08x", magic));
        int version = Short.toUnsignedInt(b.getShort());
        if (version != PROTOCOL_VERSION) throw new WireException("unsupported version " + version);
        int kind = Byte.toUnsignedInt(b.get());
        int flags = Byte.toUnsignedInt(b.get());
        Header h = new Header(version, flags, b.getInt(), b.getInt(), b.getLong());
        if (kindOut != null) kindOut[0] = kind;
        return h;
    }

    /** The result of {@link #decode}. */
    public record Decoded(Header header, Datagram body) {}

    private static float[] floats(ByteBuffer b, int n) {
        float[] v = new float[n];
        for (int i = 0; i < n; i++) v[i] = b.getFloat();
        return v;
    }

    /** Decodes one datagram. Anything malformed throws {@link WireException}; nothing else is thrown. */
    public static Decoded decode(byte[] buf, int len) throws WireException {
        int[] kindOut = new int[1];
        Header h = decodeHeader(buf, len, kindOut);
        int kind = kindOut[0];
        int payload = len - HEADER_LEN;
        ByteBuffer b = ByteBuffer.wrap(buf, HEADER_LEN, payload).order(ByteOrder.LITTLE_ENDIAN);
        Datagram body;
        switch (kind) {
            case KIND_HEARTBEAT -> {
                exact(kind, payload, 0);
                body = new Datagram.Heartbeat();
            }
            case KIND_CAMERA -> {
                exact(kind, payload, CAMERA_LEN);
                float[] view = floats(b, 16);
                float[] proj = floats(b, 16);
                float fov = b.getFloat(), near = b.getFloat(), far = b.getFloat();
                int w = b.getInt(), ht = b.getInt();
                DepthRange d = DepthRange.of(b.getInt());
                body = new Datagram.CameraMsg(new Camera(view, proj, fov, near, far, w, ht, d));
            }
            case KIND_POSE -> {
                if (payload < 4) throw new WireException("truncated pose");
                long count = Integer.toUnsignedLong(b.getInt());
                if (count == 0 || count > MAX_POSES) throw new WireException("bad value: pose count");
                exact(kind, payload, POSE_HEADER_LEN + POSE_LEN * (int) count);
                b.getInt();
                List<Pose> poses = new ArrayList<>((int) count);
                for (int i = 0; i < count; i++) {
                    long entity = Integer.toUnsignedLong(b.getInt());
                    int flags = b.getInt();
                    long t = b.getLong();
                    poses.add(new Pose(entity, flags, t, floats(b, 3), floats(b, 4), floats(b, 3),
                            floats(b, 3), floats(b, 3)));
                }
                body = new Datagram.PoseMsg(poses);
            }
            case KIND_COLLISION -> {
                if (payload < COLLISION_HEADER_LEN) throw new WireException("truncated collision");
                int query = b.getInt();
                int index = Short.toUnsignedInt(b.getShort());
                int count = Short.toUnsignedInt(b.getShort());
                float[] center = floats(b, 3);
                float radius = b.getFloat();
                long n = Integer.toUnsignedLong(b.getInt());
                b.getInt();
                if (count == 0 || index >= count) throw new WireException("bad value: chunk_index");
                if (n > MAX_TRIANGLES) throw new WireException("bad value: triangle_count");
                exact(kind, payload, COLLISION_HEADER_LEN + TRIANGLE_LEN * (int) n);
                List<float[]> tris = new ArrayList<>((int) n);
                for (int i = 0; i < n; i++) tris.add(floats(b, 9));
                body = new Datagram.CollisionMsg(new CollisionChunk(query, index, count, center, radius, tris));
            }
            case KIND_FRAME -> {
                exact(kind, payload, FRAME_LEN);
                FrameReady f = new FrameReady(b.getLong(), b.getInt(), b.getInt(), b.getInt(), b.getInt());
                if (f.frame() == 0) throw new WireException("bad value: frame 0");
                body = new Datagram.FrameMsg(f);
            }
            default -> body = new Datagram.Unknown(kind, payload);
        }
        return new Decoded(h, body);
    }

    private static void exact(int kind, int payload, int expected) throws WireException {
        if (payload != expected) {
            throw new WireException("kind " + kind + ": expected " + (HEADER_LEN + expected)
                    + " bytes, got " + (HEADER_LEN + payload));
        }
    }

    /** Splits a collision snapshot into datagram-sized chunks (an empty one still gives one chunk). */
    public static List<CollisionChunk> chunks(int query, float[] center, float radius, List<float[]> tris)
            throws WireException {
        int count = Math.max(1, (tris.size() + MAX_TRIANGLES - 1) / MAX_TRIANGLES);
        if (count > 0xFFFF) throw new WireException("too many triangles");
        List<CollisionChunk> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = Math.min(tris.size(), i * MAX_TRIANGLES);
            int to = Math.min(tris.size(), from + MAX_TRIANGLES);
            out.add(new CollisionChunk(query, i, count, center, radius, new ArrayList<>(tris.subList(from, to))));
        }
        return out;
    }

    // ------------------------------------------------------------------ small vector helpers

    static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float[] normalize(float[] a) {
        float l = (float) Math.sqrt(dot(a, a));
        return l > 0 ? new float[] {a[0] / l, a[1] / l, a[2] / l} : a;
    }
}
