package dev.patodois.rewritelink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import dev.patodois.rewritelink.Control.Role;

/**
 * Command-line peer used for the interop tests against the Rust {@code fake_host} and
 * {@code fake_guest} examples. It plays either role with the same behaviour as those examples.
 *
 * <pre>
 *   java -cp build/classes/java/main dev.patodois.rewritelink.LinkDemo guest [--port 47800] [--seconds 6] [--frames]
 *   java -cp build/classes/java/main dev.patodois.rewritelink.LinkDemo host  [--port 47800] [--seconds 6] [--big-collision 400]
 * </pre>
 *
 * <p>{@code guest --frames} renders a disc with linear depth into a 320x240 pixel file and announces it
 * for every camera it gets; the host reads such frames and reports what is in them. {@code host
 * --big-collision N} sends a snapshot of N full chunks each second instead of the small ground patch.
 */
public final class LinkDemo {
    private LinkDemo() {}

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return def;
    }

    private static long start;
    private static int bigCollisionChunks;
    private static boolean frames;
    /** Guest: instead of floating 3 m ahead of the camera, circle it at 8 m (shows movement and heading). */
    private static boolean orbit;

    private static void log(String role, String msg) {
        System.out.printf("[java-%s %6.2fs] %s%n", role, (System.nanoTime() - start) / 1e9, msg);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !(args[0].equals("host") || args[0].equals("guest"))) {
            System.err.println("usage: LinkDemo host|guest [--port N] [--seconds S]");
            System.exit(2);
        }
        boolean host = args[0].equals("host");
        int port = Integer.parseInt(arg(args, "--port", String.valueOf(Wire.DEFAULT_CONTROL_PORT)));
        double seconds = Double.parseDouble(arg(args, "--seconds", "0"));
        orbit = java.util.Arrays.asList(args).contains("--orbit");
        frames = java.util.Arrays.asList(args).contains("--frames");
        bigCollisionChunks = Integer.parseInt(arg(args, "--big-collision", "0"));
        start = System.nanoTime();
        LinkConfig cfg = new LinkConfig(host ? "Java Host" : "Java Guest");
        cfg.controlPort = port;
        String name = host ? "host" : "guest";
        try (Link link = host ? Link.host(cfg) : Link.guest(cfg)) {
            if (host) log(name, "listening on 127.0.0.1:" + link.controlPort());
            else log(name, "connecting to 127.0.0.1:" + port);
            if (host) runHost(link, seconds);
            else runGuest(link, seconds);
            log(name, "done");
        }
    }

    private static void runHost(Link link, double seconds) throws Exception {
        int cameras = 0, poseMsgs = 0, poses = 0, framesRead = 0, framesMissed = 0;
        String lastFrame = "none";
        PixelFile.Reader reader = null;
        float[] lastPose = null;
        double nextReport = 1, nextCollision = 0;
        Double giveBackAt = null;
        while (true) {
            double t = (System.nanoTime() - start) / 1e9;
            if (seconds > 0 && t > seconds) break;
            for (LinkEvent e : link.poll()) {
                if (e instanceof LinkEvent.Connected) {
                    LinkEvent.Connected c = (LinkEvent.Connected) e;
                    log("host", String.format("connected: guest \"%s\", protocol v%d, session 0x%08x, guest udp %d",
                            c.peer().game(), c.peer().protocol(), c.peer().session(), c.peer().udpAddr().getPort()));
                    nextCollision = t;
                } else if (e instanceof LinkEvent.FrameChannel) {
                    Control.FrameChannel c = ((LinkEvent.FrameChannel) e).channel();
                    closeQuietly(reader);
                    reader = null;
                    try {
                        reader = PixelFile.Reader.open(Paths.get(c.path()));
                        log("host", "guest pixel file " + c.path() + " (" + c.width() + "x" + c.height() + ", " + c.slots()
                                + " slots, depth plane: " + reader.hasDepth() + ")");
                    } catch (IOException ex) {
                        log("host", "cannot open the guest's pixel file: " + ex.getMessage());
                    }
                } else if (e instanceof LinkEvent.Frame) {
                    Wire.FrameReady fr = ((LinkEvent.Frame) e).frame();
                    PixelFile.Frame f = reader == null ? null : reader.read(fr, true);
                    if (f == null) {
                        framesMissed++;
                    } else {
                        framesRead++;
                        lastFrame = describe(f);
                    }
                } else if (e instanceof LinkEvent.Lost) {
                    closeQuietly(reader);
                    reader = null;
                    log("host", "LOST the guest: " + ((LinkEvent.Lost) e).reason());
                } else if (e instanceof LinkEvent.HandshakeFailed) {
                    log("host", "handshake failed: " + ((LinkEvent.HandshakeFailed) e).reason());
                } else if (e instanceof LinkEvent.Spawn) {
                    Control.Spawn sp = ((LinkEvent.Spawn) e).spawn();
                    log("host", "guest spawned entity " + sp.entity() + " = " + sp.provider() + " asset \"" + sp.asset() + "\"");
                } else if (e instanceof LinkEvent.Despawn) {
                    log("host", "guest despawned entity " + ((LinkEvent.Despawn) e).entity());
                } else if (e instanceof LinkEvent.Poses) {
                    LinkEvent.Poses p = (LinkEvent.Poses) e;
                    poseMsgs++;
                    poses += p.poses().size();
                    lastPose = p.poses().get(p.poses().size() - 1).translation();
                } else if (e instanceof LinkEvent.Focus && ((LinkEvent.Focus) e).requested()) {
                    LinkEvent.Focus f = (LinkEvent.Focus) e;
                    log("host", "guest asks for input focus; granting it to the " + f.focus().wire);
                    link.setFocus(f.focus());
                    giveBackAt = t + 2;
                }
            }
            if (link.isConnected()) {
                double a = 0.5 * t;
                float[] eye = {(float) (10 * Math.cos(a)), 3, (float) (10 * Math.sin(a))};
                Wire.Camera cam = Wire.Camera.lookAt(eye, new float[] {0, 1, 0}, 1.0f, 0.1f, 1000f, 1280, 720);
                link.sendCamera(cam);
                cameras++;
                if (t >= nextCollision) {
                    nextCollision = t + 1;
                    float cx = Math.round(eye[0]), cz = Math.round(eye[2]);
                    List<float[]> tris = new java.util.ArrayList<>();
                    for (int i = -10; i < 10; i++) {
                        for (int j = -10; j < 10; j++) {
                            float x = cx + i, z = cz + j;
                            tris.add(new float[] {x, 0, z, x, 0, z + 1, x + 1, 0, z + 1});
                            tris.add(new float[] {x, 0, z, x + 1, 0, z + 1, x + 1, 0, z});
                        }
                    }
                    if (bigCollisionChunks > 0) {
                        tris = new java.util.ArrayList<>();
                        for (int i = 0; i < bigCollisionChunks * Wire.MAX_TRIANGLES; i++) {
                            float x = i % 4096;
                            tris.add(new float[] {x, 0, 0, x + 1, 0, 0, x, 0, 1});
                        }
                    }
                    link.sendCollision(new float[] {cx, 0, cz}, 10, tris);
                }
                if (giveBackAt != null && t >= giveBackAt) {
                    giveBackAt = null;
                    log("host", "taking input focus back");
                    link.setFocus(Role.HOST);
                }
            }
            if (t >= nextReport) {
                nextReport += 1;
                if (link.isConnected()) {
                    log("host", String.format("sent %d cameras; got %d poses in %d datagrams; last pose %s; malformed %d stale %d foreign %d unknown_entity %d",
                            cameras, poses, poseMsgs, lastPose == null ? "none" : java.util.Arrays.toString(lastPose),
                            link.malformed, link.stale, link.foreign, link.unknownEntity));
                    if (framesRead + framesMissed > 0) {
                        log("host", "frames read " + framesRead + ", missed " + framesMissed + "; last: " + lastFrame);
                    }
                }
                cameras = poseMsgs = poses = framesRead = framesMissed = 0;
            }
            Thread.sleep(16);
        }
        closeQuietly(reader);
    }

    private static void closeQuietly(PixelFile.Reader r) {
        if (r != null) r.close();
    }

    /** What the host can tell about a frame it read: its size, camera and how much of it is drawn. */
    private static String describe(PixelFile.Frame f) {
        int opaque = 0, finite = 0;
        float near = Float.POSITIVE_INFINITY, far = 0;
        for (int i = 0; i < f.width() * f.height(); i++) {
            if ((f.rgba()[i * 4 + 3] & 0xFF) > 0) opaque++;
            if (f.depth() != null && Float.isFinite(f.depth()[i])) {
                finite++;
                near = Math.min(near, f.depth()[i]);
                far = Math.max(far, f.depth()[i]);
            }
        }
        return String.format("frame %d %dx%d camera_seq %d, %d drawn pixels, %d with finite depth%s", f.frame(), f.width(), f.height(),
                Integer.toUnsignedLong(f.cameraSeq()), opaque, finite, finite > 0 ? String.format(" (%.2f..%.2f m)", near, far) : "");
    }

    private static void runGuest(Link link, double seconds) throws Exception {
        final long cube = 1;
        int cameras = 0, posesSent = 0;
        double nextReport = 1;
        Double askFocusAt = null;
        float[] lastPos = null;
        long lastT = 0;
        PixelFile.Writer writer = null;
        int framesSent = 0;
        while (true) {
            double t = (System.nanoTime() - start) / 1e9;
            if (seconds > 0 && t > seconds) break;
            for (LinkEvent e : link.poll()) {
                if (e instanceof LinkEvent.Connected) {
                    LinkEvent.Connected c = (LinkEvent.Connected) e;
                    log("guest", String.format("connected: host \"%s\", protocol v%d, session 0x%08x",
                            c.peer().game(), c.peer().protocol(), c.peer().session()));
                    link.spawn(new Control.Spawn(cube, "Java Guest", "demo/cube", "Cube"));
                    askFocusAt = t + 2;
                    lastPos = null;
                    if (frames) {
                        closeQuietly(writer);
                        Path file = Paths.get(System.getProperty("java.io.tmpdir"),
                                "link-java-" + pid() + "-guest-" + Integer.toHexString(c.peer().session()) + ".frames");
                        writer = new PixelFile.Writer(file, FRAME_W, FRAME_H, 3, true);
                        link.announceFrames(writer);
                        log("guest", "pixel file " + file + " (" + FRAME_W + "x" + FRAME_H + ", depth: true)");
                    }
                } else if (e instanceof LinkEvent.Lost) {
                    closeQuietly(writer);
                    writer = null;
                    log("guest", "LOST the host: " + ((LinkEvent.Lost) e).reason());
                } else if (e instanceof LinkEvent.HandshakeFailed) {
                    log("guest", "handshake failed: " + ((LinkEvent.HandshakeFailed) e).reason());
                } else if (e instanceof LinkEvent.Camera) {
                    LinkEvent.Camera c = (LinkEvent.Camera) e;
                    cameras++;
                    float[] eye = c.camera().eye(), fwd = c.camera().forward();
                    float[] pos = {eye[0] + 3 * fwd[0], eye[1] + 3 * fwd[1], eye[2] + 3 * fwd[2]};
                    long now = Wire.nowUs();
                    Wire.Pose pose = Wire.Pose.identity(cube, now);
                    if (orbit) {
                        double a = 0.6 * t;
                        pos = new float[] {eye[0] + (float) (8 * Math.cos(a)), eye[1], eye[2] + (float) (8 * Math.sin(a))};
                        // The front (+Z) points along the direction of travel.
                        float[] heading = {(float) -Math.sin(a), 0, (float) Math.cos(a)};
                        System.arraycopy(Quat.fromTo(new float[] {0, 0, 1}, heading), 0, pose.rotation(), 0, 4);
                    }
                    System.arraycopy(pos, 0, pose.translation(), 0, 3);
                    if (lastPos != null) {
                        float dt = (now - lastT) * 1e-6f;
                        if (dt > 0) for (int i = 0; i < 3; i++) pose.velocity()[i] = (pos[i] - lastPos[i]) / dt;
                    }
                    lastPos = pos;
                    lastT = now;
                    link.sendPoses(Collections.singletonList(pose));
                    posesSent++;
                    if (writer != null) {
                        byte[] rgba = new byte[FRAME_W * FRAME_H * 4];
                        float[] depth = new float[FRAME_W * FRAME_H];
                        render(rgba, depth, framesSent);
                        long number = writer.write(rgba, depth, FRAME_W, FRAME_H, c.header().seq(), Wire.nowUs());
                        link.sendFrame(new Wire.FrameReady(number, (int) (number % writer.slots), c.header().seq(), FRAME_W, FRAME_H));
                        framesSent++;
                    }
                } else if (e instanceof LinkEvent.Collision) {
                    LinkEvent.Collision c = (LinkEvent.Collision) e;
                    log("guest", "collision query " + Integer.toUnsignedLong(c.query())
                            + ": " + c.triangles().size() + " triangles within " + c.radius() + " m of "
                            + java.util.Arrays.toString(c.center()));
                } else if (e instanceof LinkEvent.Focus) {
                    log("guest", "host says input focus is now with the " + ((LinkEvent.Focus) e).focus().wire);
                }
            }
            if (askFocusAt != null && t >= askFocusAt) {
                askFocusAt = null;
                log("guest", "asking the host for input focus");
                link.setFocus(Role.GUEST);
            }
            if (t >= nextReport) {
                nextReport += 1;
                if (link.isConnected()) {
                    log("guest", "got " + cameras + " cameras; sent " + posesSent + " poses, " + framesSent + " frames; focus with the "
                            + link.focus().wire);
                }
                cameras = posesSent = 0;
                framesSent = 0;
            }
            Thread.sleep(4);
        }
        closeQuietly(writer);
    }

    private static final int FRAME_W = 320, FRAME_H = 240;

    /** The process id, on Java 8 too (no ProcessHandle there): the part of the runtime name before the @. */
    private static String pid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    private static void closeQuietly(PixelFile.Writer w) {
        if (w != null) w.close();
    }

    /** A coloured disc, 3 m away, on nothing: transparent with depth +inf outside it. */
    private static void render(byte[] rgba, float[] depth, int n) {
        int cx = FRAME_W / 2 + (n % 40) - 20, cy = FRAME_H / 2, r = 60;
        for (int y = 0; y < FRAME_H; y++) {
            for (int x = 0; x < FRAME_W; x++) {
                int i = y * FRAME_W + x;
                boolean inside = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r;
                rgba[i * 4] = (byte) (inside ? 40 + x / 2 : 0);
                rgba[i * 4 + 1] = (byte) (inside ? 120 : 0);
                rgba[i * 4 + 2] = (byte) (inside ? 200 - y / 3 : 0);
                rgba[i * 4 + 3] = (byte) (inside ? 255 : 0);
                depth[i] = inside ? 3f : Float.POSITIVE_INFINITY;
            }
        }
    }
}
