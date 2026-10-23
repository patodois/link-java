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

    /** Largest width or height of a pixel file (CONTRACT.md 9.6). */
    public static final int MAX_FRAME_DIM = 16384;
    /** Largest width * height of a pixel file (2^26, 8192 x 8192): a read copies at most 256 MiB of colour and 256 MiB of depth. */
    public static final long MAX_FRAME_PIXELS = 1L << 26;
    /** Live entities a receiver keeps for its peer; a spawn beyond that is ignored (CONTRACT.md 8). */
    public static final int MAX_ENTITIES = 1024;
    /** The range of {@code heartbeat_ms} a hello may announce (CONTRACT.md 8). */
    public static final int MIN_HEARTBEAT_MS = 10;
    public static final int MAX_HEARTBEAT_MS = 10_000;
    /** Collision datagrams one {@link Link#poll()} sends at most (CONTRACT.md 9.5). */
    public static final int COLLISION_PER_POLL = 32;
    /** The UDP receive buffer a receiver asks for, in bytes (CONTRACT.md 2); the OS may grant less. */
    public static final int RECV_BUFFER_BYTES = 4 << 20;

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

    /**
     * Microseconds since the Unix epoch, wall clock. {@code Instant.now()} has microsecond resolution
     * on Java 9 and later and millisecond resolution on Java 8 (the value is then a multiple of 1000,
     * which the contract allows).
     */
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
    public static final class Header {
        private final int version;
        private final int flags;
        private final int session;
        private final int seq;
        private final long timeUs;

        public Header(int version, int flags, int session, int seq, long timeUs) {
            this.version = version;
            this.flags = flags;
            this.session = session;
            this.seq = seq;
            this.timeUs = timeUs;
        }

        public int version() {
            return version;
        }

        public int flags() {
            return flags;
        }

        public int session() {
            return session;
        }

        public int seq() {
            return seq;
        }

        public long timeUs() {
            return timeUs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Header)) return false;
            Header that = (Header) o;
            return version == that.version
                    && flags == that.flags
                    && session == that.session
                    && seq == that.seq
                    && timeUs == that.timeUs;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Integer.hashCode(version);
            h = 31 * h + Integer.hashCode(flags);
            h = 31 * h + Integer.hashCode(session);
            h = 31 * h + Integer.hashCode(seq);
            h = 31 * h + Long.hashCode(timeUs);
            return h;
        }

        @Override
        public String toString() {
            return "Header[version=" + version + ", flags=" + flags + ", session=" + session + ", seq=" + seq + ", timeUs=" + timeUs + "]";
        }
    }

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
    public static final class Camera {
        private final float[] view;
        private final float[] proj;
        private final float fovY;
        private final float near;
        private final float far;
        private final int viewportWidth;
        private final int viewportHeight;
        private final DepthRange depthRange;

        public Camera(float[] view, float[] proj, float fovY, float near, float far, int viewportWidth, int viewportHeight, DepthRange depthRange) {
            this.view = view;
            this.proj = proj;
            this.fovY = fovY;
            this.near = near;
            this.far = far;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.depthRange = depthRange;
        }

        public float[] view() {
            return view;
        }

        public float[] proj() {
            return proj;
        }

        public float fovY() {
            return fovY;
        }

        public float near() {
            return near;
        }

        public float far() {
            return far;
        }

        public int viewportWidth() {
            return viewportWidth;
        }

        public int viewportHeight() {
            return viewportHeight;
        }

        public DepthRange depthRange() {
            return depthRange;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Camera)) return false;
            Camera that = (Camera) o;
            return java.util.Arrays.equals(view, that.view)
                    && java.util.Arrays.equals(proj, that.proj)
                    && Float.compare(fovY, that.fovY) == 0
                    && Float.compare(near, that.near) == 0
                    && Float.compare(far, that.far) == 0
                    && viewportWidth == that.viewportWidth
                    && viewportHeight == that.viewportHeight
                    && java.util.Objects.equals(depthRange, that.depthRange);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(view);
            h = 31 * h + java.util.Arrays.hashCode(proj);
            h = 31 * h + Float.hashCode(fovY);
            h = 31 * h + Float.hashCode(near);
            h = 31 * h + Float.hashCode(far);
            h = 31 * h + Integer.hashCode(viewportWidth);
            h = 31 * h + Integer.hashCode(viewportHeight);
            h = 31 * h + java.util.Objects.hashCode(depthRange);
            return h;
        }

        @Override
        public String toString() {
            return "Camera[view=" + java.util.Arrays.toString(view) + ", proj=" + java.util.Arrays.toString(proj) + ", fovY=" + fovY + ", near=" + near + ", far=" + far + ", viewportWidth=" + viewportWidth + ", viewportHeight=" + viewportHeight + ", depthRange=" + depthRange + "]";
        }


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
    public static final class Pose {
        private final long entity;
        private final int flags;
        private final long timeUs;
        private final float[] translation;
        private final float[] rotation;
        private final float[] scale;
        private final float[] velocity;
        private final float[] angularVelocity;

        public Pose(long entity, int flags, long timeUs, float[] translation, float[] rotation, float[] scale, float[] velocity, float[] angularVelocity) {
            this.entity = entity;
            this.flags = flags;
            this.timeUs = timeUs;
            this.translation = translation;
            this.rotation = rotation;
            this.scale = scale;
            this.velocity = velocity;
            this.angularVelocity = angularVelocity;
        }

        public long entity() {
            return entity;
        }

        public int flags() {
            return flags;
        }

        public long timeUs() {
            return timeUs;
        }

        public float[] translation() {
            return translation;
        }

        public float[] rotation() {
            return rotation;
        }

        public float[] scale() {
            return scale;
        }

        public float[] velocity() {
            return velocity;
        }

        public float[] angularVelocity() {
            return angularVelocity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pose)) return false;
            Pose that = (Pose) o;
            return entity == that.entity
                    && flags == that.flags
                    && timeUs == that.timeUs
                    && java.util.Arrays.equals(translation, that.translation)
                    && java.util.Arrays.equals(rotation, that.rotation)
                    && java.util.Arrays.equals(scale, that.scale)
                    && java.util.Arrays.equals(velocity, that.velocity)
                    && java.util.Arrays.equals(angularVelocity, that.angularVelocity);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Long.hashCode(entity);
            h = 31 * h + Integer.hashCode(flags);
            h = 31 * h + Long.hashCode(timeUs);
            h = 31 * h + java.util.Arrays.hashCode(translation);
            h = 31 * h + java.util.Arrays.hashCode(rotation);
            h = 31 * h + java.util.Arrays.hashCode(scale);
            h = 31 * h + java.util.Arrays.hashCode(velocity);
            h = 31 * h + java.util.Arrays.hashCode(angularVelocity);
            return h;
        }

        @Override
        public String toString() {
            return "Pose[entity=" + entity + ", flags=" + flags + ", timeUs=" + timeUs + ", translation=" + java.util.Arrays.toString(translation) + ", rotation=" + java.util.Arrays.toString(rotation) + ", scale=" + java.util.Arrays.toString(scale) + ", velocity=" + java.util.Arrays.toString(velocity) + ", angularVelocity=" + java.util.Arrays.toString(angularVelocity) + "]";
        }


        /** An identity pose at the origin, at rest. */
        public static Pose identity(long entity, long timeUs) {
            return new Pose(entity, 0, timeUs, new float[3], new float[] {0, 0, 0, 1},
                    new float[] {1, 1, 1}, new float[3], new float[3]);
        }
    }

    /** One datagram's share of a collision snapshot; each triangle is nine floats a, b, c. */
    public static final class CollisionChunk {
        private final int query;
        private final int chunkIndex;
        private final int chunkCount;
        private final float[] center;
        private final float radius;
        private final List<float[]> triangles;

        public CollisionChunk(int query, int chunkIndex, int chunkCount, float[] center, float radius, List<float[]> triangles) {
            this.query = query;
            this.chunkIndex = chunkIndex;
            this.chunkCount = chunkCount;
            this.center = center;
            this.radius = radius;
            this.triangles = triangles;
        }

        public int query() {
            return query;
        }

        public int chunkIndex() {
            return chunkIndex;
        }

        public int chunkCount() {
            return chunkCount;
        }

        public float[] center() {
            return center;
        }

        public float radius() {
            return radius;
        }

        public List<float[]> triangles() {
            return triangles;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CollisionChunk)) return false;
            CollisionChunk that = (CollisionChunk) o;
            return query == that.query
                    && chunkIndex == that.chunkIndex
                    && chunkCount == that.chunkCount
                    && java.util.Arrays.equals(center, that.center)
                    && Float.compare(radius, that.radius) == 0
                    && sameTriangles(triangles, that.triangles);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Integer.hashCode(query);
            h = 31 * h + Integer.hashCode(chunkIndex);
            h = 31 * h + Integer.hashCode(chunkCount);
            h = 31 * h + java.util.Arrays.hashCode(center);
            h = 31 * h + Float.hashCode(radius);
            h = 31 * h + hashTriangles(triangles);
            return h;
        }

        @Override
        public String toString() {
            return "CollisionChunk[query=" + query + ", chunkIndex=" + chunkIndex + ", chunkCount=" + chunkCount + ", center=" + java.util.Arrays.toString(center) + ", radius=" + radius + ", triangles=" + triangles.size() + " triangles]";
        }
    }

    /** Whether two triangle lists hold equal triangles (arrays compared by content). */
    static boolean sameTriangles(List<float[]> a, List<float[]> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    static int hashTriangles(List<float[]> a) {
        if (a == null) return 0;
        int h = 1;
        for (float[] t : a) h = 31 * h + java.util.Arrays.hashCode(t);
        return h;
    }

    public static final class FrameReady {
        private final long frame;
        private final int slot;
        private final int cameraSeq;
        private final int width;
        private final int height;

        public FrameReady(long frame, int slot, int cameraSeq, int width, int height) {
            this.frame = frame;
            this.slot = slot;
            this.cameraSeq = cameraSeq;
            this.width = width;
            this.height = height;
        }

        public long frame() {
            return frame;
        }

        public int slot() {
            return slot;
        }

        public int cameraSeq() {
            return cameraSeq;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FrameReady)) return false;
            FrameReady that = (FrameReady) o;
            return frame == that.frame
                    && slot == that.slot
                    && cameraSeq == that.cameraSeq
                    && width == that.width
                    && height == that.height;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Long.hashCode(frame);
            h = 31 * h + Integer.hashCode(slot);
            h = 31 * h + Integer.hashCode(cameraSeq);
            h = 31 * h + Integer.hashCode(width);
            h = 31 * h + Integer.hashCode(height);
            return h;
        }

        @Override
        public String toString() {
            return "FrameReady[frame=" + frame + ", slot=" + slot + ", cameraSeq=" + cameraSeq + ", width=" + width + ", height=" + height + "]";
        }
    }

    public interface Datagram {
        final class Heartbeat implements Datagram {
            public Heartbeat() {
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Heartbeat)) return false;
                return true;
            }

            @Override
            public int hashCode() {
                return 0;
            }

            @Override
            public String toString() {
                return "Heartbeat[]";
            }
        }
        final class CameraMsg implements Datagram {
            private final Camera camera;

            public CameraMsg(Camera camera) {
                this.camera = camera;
            }

            public Camera camera() {
                return camera;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof CameraMsg)) return false;
                CameraMsg that = (CameraMsg) o;
                return java.util.Objects.equals(camera, that.camera);
            }

            @Override
            public int hashCode() {
                int h = 0;
                h = 31 * h + java.util.Objects.hashCode(camera);
                return h;
            }

            @Override
            public String toString() {
                return "CameraMsg[camera=" + camera + "]";
            }
        }
        final class PoseMsg implements Datagram {
            private final List<Pose> poses;

            public PoseMsg(List<Pose> poses) {
                this.poses = poses;
            }

            public List<Pose> poses() {
                return poses;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof PoseMsg)) return false;
                PoseMsg that = (PoseMsg) o;
                return java.util.Objects.equals(poses, that.poses);
            }

            @Override
            public int hashCode() {
                int h = 0;
                h = 31 * h + java.util.Objects.hashCode(poses);
                return h;
            }

            @Override
            public String toString() {
                return "PoseMsg[poses=" + poses + "]";
            }
        }
        final class CollisionMsg implements Datagram {
            private final CollisionChunk chunk;

            public CollisionMsg(CollisionChunk chunk) {
                this.chunk = chunk;
            }

            public CollisionChunk chunk() {
                return chunk;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof CollisionMsg)) return false;
                CollisionMsg that = (CollisionMsg) o;
                return java.util.Objects.equals(chunk, that.chunk);
            }

            @Override
            public int hashCode() {
                int h = 0;
                h = 31 * h + java.util.Objects.hashCode(chunk);
                return h;
            }

            @Override
            public String toString() {
                return "CollisionMsg[chunk=" + chunk + "]";
            }
        }
        final class FrameMsg implements Datagram {
            private final FrameReady frame;

            public FrameMsg(FrameReady frame) {
                this.frame = frame;
            }

            public FrameReady frame() {
                return frame;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof FrameMsg)) return false;
                FrameMsg that = (FrameMsg) o;
                return java.util.Objects.equals(frame, that.frame);
            }

            @Override
            public int hashCode() {
                int h = 0;
                h = 31 * h + java.util.Objects.hashCode(frame);
                return h;
            }

            @Override
            public String toString() {
                return "FrameMsg[frame=" + frame + "]";
            }
        }
        final class Unknown implements Datagram {
            private final int kind;
            private final int payloadLen;

            public Unknown(int kind, int payloadLen) {
                this.kind = kind;
                this.payloadLen = payloadLen;
            }

            public int kind() {
                return kind;
            }

            public int payloadLen() {
                return payloadLen;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Unknown)) return false;
                Unknown that = (Unknown) o;
                return kind == that.kind
                        && payloadLen == that.payloadLen;
            }

            @Override
            public int hashCode() {
                int h = 0;
                h = 31 * h + Integer.hashCode(kind);
                h = 31 * h + Integer.hashCode(payloadLen);
                return h;
            }

            @Override
            public String toString() {
                return "Unknown[kind=" + kind + ", payloadLen=" + payloadLen + "]";
            }
        }

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
        if (d instanceof Datagram.Heartbeat) return KIND_HEARTBEAT;
        if (d instanceof Datagram.CameraMsg) return KIND_CAMERA;
        if (d instanceof Datagram.PoseMsg) return KIND_POSE;
        if (d instanceof Datagram.CollisionMsg) return KIND_COLLISION;
        if (d instanceof Datagram.FrameMsg) return KIND_FRAME;
        if (d instanceof Datagram.Unknown) return ((Datagram.Unknown) d).kind();
        throw new IllegalArgumentException("not a datagram of this library: " + d);
    }

    /** Encodes {@code header} and {@code body} into a fresh array of exactly the datagram's size. */
    public static byte[] encode(Header h, Datagram body) throws WireException {
        int payload;
        if (body instanceof Datagram.Heartbeat) {
            payload = 0;
        } else if (body instanceof Datagram.CameraMsg) {
            if (((Datagram.CameraMsg) body).camera().depthRange() == null) throw new WireException("bad value: depth_range");
            payload = CAMERA_LEN;
        } else if (body instanceof Datagram.PoseMsg) {
            int n = ((Datagram.PoseMsg) body).poses().size();
            if (n < 1 || n > MAX_POSES) throw new WireException("pose count " + n);
            payload = POSE_HEADER_LEN + POSE_LEN * n;
        } else if (body instanceof Datagram.CollisionMsg) {
            CollisionChunk c = ((Datagram.CollisionMsg) body).chunk();
            int n = c.triangles().size();
            if (n > MAX_TRIANGLES) throw new WireException("triangle count " + n);
            if (c.chunkCount() < 1 || c.chunkCount() > 0xFFFF || c.chunkIndex() < 0 || c.chunkIndex() >= c.chunkCount()) {
                throw new WireException("bad value: chunk_index " + c.chunkIndex() + " of " + c.chunkCount()
                        + " (chunk_index must be below chunk_count, both fit 16 bits)");
            }
            payload = COLLISION_HEADER_LEN + TRIANGLE_LEN * n;
        } else if (body instanceof Datagram.FrameMsg) {
            if (((Datagram.FrameMsg) body).frame().frame() == 0) throw new WireException("bad value: frame numbers start at 1");
            payload = FRAME_LEN;
        } else {
            throw new WireException("cannot encode an unknown kind");
        }
        ByteBuffer b = buffer(HEADER_LEN + payload);
        b.putInt(MAGIC);
        b.putShort((short) h.version());
        b.put((byte) kindOf(body));
        b.put((byte) h.flags());
        b.putInt(h.session());
        b.putInt(h.seq());
        b.putLong(h.timeUs());
        if (body instanceof Datagram.CameraMsg) {
            Camera c = ((Datagram.CameraMsg) body).camera();
            putFloats(b, c.view(), 16);
            putFloats(b, c.proj(), 16);
            b.putFloat(c.fovY()).putFloat(c.near()).putFloat(c.far());
            b.putInt(c.viewportWidth()).putInt(c.viewportHeight()).putInt(c.depthRange().code);
        } else if (body instanceof Datagram.PoseMsg) {
            List<Pose> poses = ((Datagram.PoseMsg) body).poses();
            b.putInt(poses.size()).putInt(0);
            for (Pose p : poses) {
                b.putInt((int) p.entity()).putInt(p.flags()).putLong(p.timeUs());
                putFloats(b, p.translation(), 3);
                putFloats(b, p.rotation(), 4);
                putFloats(b, p.scale(), 3);
                putFloats(b, p.velocity(), 3);
                putFloats(b, p.angularVelocity(), 3);
            }
        } else if (body instanceof Datagram.CollisionMsg) {
            CollisionChunk c = ((Datagram.CollisionMsg) body).chunk();
            b.putInt(c.query()).putShort((short) c.chunkIndex()).putShort((short) c.chunkCount());
            putFloats(b, c.center(), 3);
            b.putFloat(c.radius()).putInt(c.triangles().size()).putInt(0);
            for (float[] t : c.triangles()) putFloats(b, t, 9);
        } else if (body instanceof Datagram.FrameMsg) {
            FrameReady f = ((Datagram.FrameMsg) body).frame();
            b.putLong(f.frame()).putInt(f.slot()).putInt(f.cameraSeq())
                    .putInt(f.width()).putInt(f.height());
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
    public static final class Decoded {
        private final Header header;
        private final Datagram body;

        public Decoded(Header header, Datagram body) {
            this.header = header;
            this.body = body;
        }

        public Header header() {
            return header;
        }

        public Datagram body() {
            return body;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Decoded)) return false;
            Decoded that = (Decoded) o;
            return java.util.Objects.equals(header, that.header)
                    && java.util.Objects.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(header);
            h = 31 * h + java.util.Objects.hashCode(body);
            return h;
        }

        @Override
        public String toString() {
            return "Decoded[header=" + header + ", body=" + body + "]";
        }
    }

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
            case KIND_HEARTBEAT: {
                exact(kind, payload, 0);
                body = new Datagram.Heartbeat();
                break;
            }
            case KIND_CAMERA: {
                exact(kind, payload, CAMERA_LEN);
                float[] view = floats(b, 16);
                float[] proj = floats(b, 16);
                float fov = b.getFloat(), near = b.getFloat(), far = b.getFloat();
                int w = b.getInt(), ht = b.getInt();
                DepthRange d = DepthRange.of(b.getInt());
                body = new Datagram.CameraMsg(new Camera(view, proj, fov, near, far, w, ht, d));
                break;
            }
            case KIND_POSE: {
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
                break;
            }
            case KIND_COLLISION: {
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
                break;
            }
            case KIND_FRAME: {
                exact(kind, payload, FRAME_LEN);
                FrameReady f = new FrameReady(b.getLong(), b.getInt(), b.getInt(), b.getInt(), b.getInt());
                if (f.frame() == 0) throw new WireException("bad value: frame 0");
                body = new Datagram.FrameMsg(f);
                break;
            }
            default:
                body = new Datagram.Unknown(kind, payload);
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
