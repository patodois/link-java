package dev.patodois.rewritelink;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Wire.WireException;

/**
 * The Java peer of the cross-language interop test (the specification is {@code tests/interop/README.md} of
 * rewrite-bridge): one side of a scripted two-process session over real sockets and a real pixel file.
 *
 * <pre>
 * java -cp build/classes/java/main:build/classes/java/test dev.patodois.rewritelink.InteropPeer host --port 47810 --label java
 * </pre>
 *
 * <p>It prints log lines of its own choosing and, as its last stdout line, {@code RBL-INTEROP-RESULT {json}}. The
 * exit status is 0 when every check passed, 1 when one failed, 2 for a bad command line. This library has no
 * counters, so {@code stats} in the result is {@code null}.
 */
public final class InteropPeer {
    // ------------------------------------------------------------------ the scripted session's constants

    static final int N = 5;
    static final int FRAME_W = 64;
    static final int FRAME_H = 48;
    static final int SLOTS = 3;
    static final long HOST_ENTITY = 3;
    static final long GUEST_ENTITY = 7;
    static final long BASE_US = 1_700_000_000_000_000L;
    static final int TRIANGLES = 300;
    static final long STEP_TIMEOUT_MS = 10_000;
    static final long TOTAL_TIMEOUT_MS = 30_000;
    static final long CLOSE_DRAIN_MS = 200;

    /** What the guest asks the host for when it sees the focus given to it (README: {@code input host}). */
    static final Role GUEST_REQUEST = Role.HOST;

    static Wire.Camera camera(int k) {
        float[] view = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, k, 0.5f, -8, 1};
        float[] proj = {1.5f, 0, 0, 0, 0, 2, 0, 0, 0, 0, -1, -1, 0, 0, -0.25f, 0};
        float far = (k % 2 == 1) ? Float.POSITIVE_INFINITY : 500f;
        Wire.DepthRange range = Wire.DepthRange.values()[k % 3];
        return new Wire.Camera(view, proj, 1.0f, 0.25f, far, FRAME_W, FRAME_H, range);
    }

    static Wire.Pose hostPose(int k) {
        return new Wire.Pose(HOST_ENTITY, k == 1 ? Wire.POSE_TELEPORT : 0, BASE_US + 1000L * k,
                new float[] {k, 2, -3}, new float[] {0, 0, 0, 1}, new float[] {1, 1, 1},
                new float[] {0.5f, 0, 0}, new float[] {0, 0.25f, 0});
    }

    static Wire.Pose guestPose(int k) {
        return new Wire.Pose(GUEST_ENTITY, k == 1 ? Wire.POSE_TELEPORT : 0, BASE_US + 1000L * k + 500,
                new float[] {-k, 1, 4}, new float[] {0, 0, 0, 1}, new float[] {1, 1, 1},
                new float[] {0.5f, 0, 0}, new float[] {0, 0.25f, 0});
    }

    static List<float[]> triangles() {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < TRIANGLES; i++) {
            float b = i % 64;
            out.add(new float[] {b, 0, 0, b + 1, 0, 0, b, 0, 1});
        }
        return out;
    }

    static final float[] CENTER = {1, 2, 3};
    static final float RADIUS = 50;

    static byte[] pixels(int k) {
        byte[] rgba = new byte[FRAME_W * FRAME_H * 4];
        for (int i = 0; i < rgba.length; i++) rgba[i] = (byte) ((37 * i + 11 * k + 5) & 0xFF);
        rgba[0] = rgba[1] = rgba[2] = rgba[3] = 0;
        return rgba;
    }

    static float[] depth(int k) {
        float[] d = new float[FRAME_W * FRAME_H];
        d[0] = Float.POSITIVE_INFINITY;
        for (int p = 1; p < d.length; p++) d[p] = 0.5f * (p + 1) + k;
        return d;
    }

    // ------------------------------------------------------------------ the peer

    /** Ends the script early: the check that waits could not be passed. */
    static final class Abort extends Exception {
        private static final long serialVersionUID = 1L;

        Abort(String message) {
            super(message);
        }
    }

    private final Role role;
    private final String label;
    private final String peerGamePrefix;
    private final long startNs = System.nanoTime();
    private Link link;

    // what the events said
    private LinkEvent.PeerInfo peer;
    private String lostReason;
    private final List<Long> spawns = new ArrayList<>();
    private final List<Long> despawns = new ArrayList<>();
    private final List<String> focus = new ArrayList<>();
    private final List<LinkEvent.Camera> cameraEvents = new ArrayList<>();
    private final List<LinkEvent.Poses> peerPoses = new ArrayList<>(); // events with a pose of the peer's entity
    private final List<LinkEvent.Frame> frameEvents = new ArrayList<>();
    private LinkEvent.Collision collision;
    private Control.FrameChannel frameChannel;

    // the result
    private final List<String> failures = new ArrayList<>();
    private int camerasOk, posesOk, collisionTriangles, framesOk, framesBad;

    private InteropPeer(Role role, String label) {
        this.role = role;
        this.label = label;
        this.peerGamePrefix = role == Role.HOST ? "interop guest (" : "interop host (";
    }

    private void log(String s) {
        System.out.println("[" + role.wire + " " + label + "] " + s);
    }

    private void fail(String what) {
        failures.add(what);
        log("FAILED CHECK: " + what);
    }

    private void check(boolean ok, String what) {
        if (!ok) fail(what);
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    /** Takes in what the link reports; reacts the way the script says the focus exchange goes. */
    private void pump() throws Abort {
        for (LinkEvent e : link.poll()) {
            if (e instanceof LinkEvent.Connected) {
                peer = ((LinkEvent.Connected) e).peer();
            } else if (e instanceof LinkEvent.Lost) {
                lostReason = ((LinkEvent.Lost) e).reason();
            } else if (e instanceof LinkEvent.HandshakeFailed) {
                throw new Abort("handshake failed: " + ((LinkEvent.HandshakeFailed) e).reason());
            } else if (e instanceof LinkEvent.Spawn) {
                spawns.add(((LinkEvent.Spawn) e).spawn().entity());
            } else if (e instanceof LinkEvent.Despawn) {
                despawns.add(((LinkEvent.Despawn) e).entity());
            } else if (e instanceof LinkEvent.Camera) {
                cameraEvents.add((LinkEvent.Camera) e);
            } else if (e instanceof LinkEvent.Poses) {
                peerPoses.add((LinkEvent.Poses) e);
            } else if (e instanceof LinkEvent.Frame) {
                frameEvents.add((LinkEvent.Frame) e);
            } else if (e instanceof LinkEvent.Collision) {
                collision = (LinkEvent.Collision) e;
            } else if (e instanceof LinkEvent.FrameChannel) {
                frameChannel = ((LinkEvent.FrameChannel) e).channel();
            } else if (e instanceof LinkEvent.Focus) {
                LinkEvent.Focus f = (LinkEvent.Focus) e;
                focus.add(f.focus().wire + ":" + (f.requested() ? "request" : "set"));
                try {
                    if (role == Role.HOST && f.requested()) {
                        link.setFocus(Role.HOST); // the host answers a request by setting the focus
                    } else if (role == Role.GUEST && !f.requested() && f.focus() == Role.GUEST) {
                        link.setFocus(GUEST_REQUEST); // the guest, seeing the focus given to it, asks for something back
                    }
                } catch (WireException ex) {
                    throw new Abort("could not answer the focus event: " + ex.getMessage());
                }
            }
        }
    }

    /** Polls the link every few milliseconds until {@code cond} holds; gives up after 10 s (or when 30 s have passed since the start). */
    private void waitFor(String what, BooleanSupplier cond) throws Abort {
        long end = System.nanoTime() + STEP_TIMEOUT_MS * 1_000_000L;
        while (true) {
            pump();
            if (cond.getAsBoolean()) return;
            if (System.nanoTime() >= end) throw new Abort("waited more than " + STEP_TIMEOUT_MS / 1000 + " s for " + what);
            if (elapsedMs() >= TOTAL_TIMEOUT_MS) throw new Abort("30 s passed while waiting for " + what);
            try {
                Thread.sleep(2);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new Abort("interrupted");
            }
        }
    }

    private void sleepPolling(long ms) throws Abort {
        long end = System.nanoTime() + ms * 1_000_000L;
        waitFor("a short pause", () -> System.nanoTime() >= end);
    }

    private static boolean hasPose(LinkEvent.Poses ev, long entity) {
        for (Wire.Pose p : ev.poses()) {
            if (p.entity() == entity) return true;
        }
        return false;
    }

    private static Wire.Pose poseOf(LinkEvent.Poses ev, long entity) {
        for (Wire.Pose p : ev.poses()) {
            if (p.entity() == entity) return p;
        }
        return null;
    }

    /** The k-th (1-based) event that carries a pose of {@code entity}, or null. */
    private LinkEvent.Poses kthPoses(int k, long entity) {
        int n = 0;
        for (LinkEvent.Poses ev : peerPoses) {
            if (hasPose(ev, entity) && ++n == k) return ev;
        }
        return null;
    }

    private int countPoses(long entity) {
        int n = 0;
        for (LinkEvent.Poses ev : peerPoses) {
            if (hasPose(ev, entity)) n++;
        }
        return n;
    }

    private void checkHandshake() {
        check(peer.game().startsWith(peerGamePrefix), "peer game is \"" + peer.game() + "\", expected the prefix \"" + peerGamePrefix + "\"");
        check(peer.protocol() == 1, "negotiated protocol " + peer.protocol() + ", expected 1");
        check(peer.session() != 0, "session is 0");
    }

    private void checkPose(Wire.Pose got, Wire.Pose want, String who) {
        check(want.equals(got), who + ": pose is " + got + ", expected " + want);
    }

    // ------------------------------------------------------------------ host

    private void runHost() throws Abort, IOException, WireException {
        waitFor("the guest's hello (Connected)", () -> peer != null);
        checkHandshake();
        log("connected to \"" + peer.game() + "\", protocol " + peer.protocol() + ", session " + Integer.toUnsignedString(peer.session()));
        link.spawn(new Control.Spawn(HOST_ENTITY, "War Thunder", "/interop/tank", "Host Tank"));
        link.setFocus(Role.GUEST);

        waitFor("the guest's spawn of 7, its frame_channel and the focus exchange",
                () -> spawns.contains(GUEST_ENTITY) && frameChannel != null && !focus.isEmpty());
        check(frameChannel.width() == FRAME_W && frameChannel.height() == FRAME_H && frameChannel.slots() == SLOTS,
                "frame_channel says " + frameChannel.width() + "x" + frameChannel.height() + " with " + frameChannel.slots()
                        + " slots, expected " + FRAME_W + "x" + FRAME_H + " with " + SLOTS);
        PixelFile.Reader reader = null;
        try {
            try {
                reader = PixelFile.Reader.open(Paths.get(frameChannel.path()));
            } catch (IOException e) {
                throw new Abort("cannot open the pixel file " + frameChannel.path() + ": " + e);
            }
            check(reader.hasDepth(), "the pixel file has no depth plane");

            for (int k = 1; k <= N; k++) {
                int camSeq = link.sendCamera(camera(k));
                link.sendPoses(Collections.singletonList(hostPose(k)));
                if (k == 1) link.sendCollision(CENTER, RADIUS, triangles());
                final int kk = k;
                waitFor("the guest's pose and frame " + k, () -> countPoses(GUEST_ENTITY) >= kk && frameEvents.size() >= kk);
                LinkEvent.Poses pev = kthPoses(k, GUEST_ENTITY);
                int before = failures.size();
                checkPose(poseOf(pev, GUEST_ENTITY), guestPose(k), "guest pose " + k);
                if (failures.size() == before) posesOk++;

                LinkEvent.Frame fev = frameEvents.get(k - 1);
                Wire.FrameReady fr = fev.frame();
                boolean good = true;
                if (fr.frame() != k || fr.cameraSeq() != camSeq || fr.width() != FRAME_W || fr.height() != FRAME_H) {
                    fail("Frame datagram " + k + " says " + fr + ", expected frame " + k + ", camera_seq " + camSeq + ", " + FRAME_W + "x" + FRAME_H);
                    good = false;
                }
                PixelFile.Frame got = reader.read(fr.frame(), fr.slot(), true);
                if (got == null) {
                    fail("frame " + k + " could not be read from slot " + fr.slot());
                    good = false;
                } else {
                    if (got.cameraSeq() != camSeq) {
                        fail("frame " + k + ": camera_seq " + got.cameraSeq() + ", expected " + camSeq);
                        good = false;
                    }
                    if (got.width() != FRAME_W || got.height() != FRAME_H) {
                        fail("frame " + k + ": size " + got.width() + "x" + got.height());
                        good = false;
                    }
                    if (got.timeUs() != BASE_US + 16_666L * k) {
                        fail("frame " + k + ": time_us " + got.timeUs());
                        good = false;
                    }
                    if (!Arrays.equals(got.rgba(), pixels(k))) {
                        fail("frame " + k + ": colour bytes differ");
                        good = false;
                    }
                    float[] d = got.depth();
                    float[] want = depth(k);
                    boolean sameDepth = d != null && d.length == want.length;
                    for (int i = 0; sameDepth && i < d.length; i++) {
                        sameDepth = Float.floatToRawIntBits(d[i]) == Float.floatToRawIntBits(want[i]);
                    }
                    if (!sameDepth) {
                        fail("frame " + k + ": depth differs");
                        good = false;
                    }
                }
                if (good) framesOk++;
                else framesBad++;
            }
        } finally {
            if (reader != null) reader.close();
        }

        // Once the last frame is verified the host despawns; the guest answers with its own despawn (control lines are
        // read before datagrams in a poll, so the guest must not despawn before it knows the host read its last frame).
        link.despawn(HOST_ENTITY);
        waitFor("the guest's despawn of 7", () -> despawns.contains(GUEST_ENTITY));
        waitFor("the guest's bye (Lost)", () -> lostReason != null);
        check(lostReason.contains("bye"), "Lost reason \"" + lostReason + "\" does not contain \"bye\"");
        log("the guest said bye: " + lostReason);
    }

    // ------------------------------------------------------------------ guest

    private void runGuest() throws Abort, IOException, WireException {
        waitFor("the host's hello (Connected)", () -> peer != null);
        checkHandshake();
        log("connected to \"" + peer.game() + "\", protocol " + peer.protocol() + ", session " + Integer.toUnsignedString(peer.session()));
        Path file = Paths.get(System.getProperty("java.io.tmpdir"), "rbl-interop-" + pid() + "-" + label + ".frames").toAbsolutePath();
        PixelFile.Writer writer = new PixelFile.Writer(file, FRAME_W, FRAME_H, SLOTS, true);
        try {
            link.spawn(new Control.Spawn(GUEST_ENTITY, "Warframe", "/Lotus/Characters/Excalibur", "Excalibur"));
            link.announceFrames(writer);

            waitFor("the host's spawn of 3 and the focus exchange", () -> spawns.contains(HOST_ENTITY) && focus.size() >= 2);

            for (int k = 1; k <= N; k++) {
                final int kk = k;
                waitFor("the host's camera " + k, () -> cameraEvents.size() >= kk);
                LinkEvent.Camera cev = cameraEvents.get(k - 1);
                int before = failures.size();
                check(camera(k).equals(cev.camera()), "camera " + k + " is " + cev.camera() + ", expected " + camera(k));
                if (failures.size() == before) camerasOk++;

                link.sendPoses(Collections.singletonList(guestPose(k)));
                long frame = writer.write(pixels(k), depth(k), FRAME_W, FRAME_H, cev.header().seq(), BASE_US + 16_666L * k);
                check(frame == k, "the pixel file numbered a frame " + frame + ", expected " + k);
                link.sendFrame(new Wire.FrameReady(frame, (int) (frame % SLOTS), cev.header().seq(), FRAME_W, FRAME_H));

                waitFor("the host's pose " + k, () -> countPoses(HOST_ENTITY) >= kk);
                before = failures.size();
                checkPose(poseOf(kthPoses(k, HOST_ENTITY), HOST_ENTITY), hostPose(k), "host pose " + k);
                if (failures.size() == before) posesOk++;
                if (k == 1) {
                    waitFor("the host's collision snapshot", () -> collision != null);
                    checkCollision();
                }
            }

            waitFor("the host's despawn of 3", () -> despawns.contains(HOST_ENTITY));
            link.despawn(GUEST_ENTITY);
            link.disconnect("done");
            sleepPolling(CLOSE_DRAIN_MS); // keep polling so the closing drains
            link.close();
            log("said bye");
            check(lostReason == null, "a Lost event after disconnect: " + lostReason);
        } finally {
            writer.close();
        }
    }

    private void checkCollision() {
        collisionTriangles = collision.triangles().size();
        boolean same = collisionTriangles == TRIANGLES && Arrays.equals(collision.center(), CENTER) && collision.radius() == RADIUS;
        List<float[]> want = triangles();
        for (int i = 0; same && i < want.size(); i++) same = Arrays.equals(collision.triangles().get(i), want.get(i));
        check(same, "the collision snapshot differs: " + collision.triangles().size() + " triangles, center "
                + Arrays.toString(collision.center()) + ", radius " + collision.radius());
    }

    /** The process id, on Java 8 too (no ProcessHandle there): the part of the runtime name before the @. */
    private static String pid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    // ------------------------------------------------------------------ result

    private static String list(List<?> items, boolean quote) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append(quote ? Json.quote(String.valueOf(items.get(i))) : String.valueOf(items.get(i)));
        }
        return b.append(']').toString();
    }

    private String resultJson() {
        StringBuilder b = new StringBuilder("{");
        b.append("\"role\":").append(Json.quote(role.wire));
        b.append(",\"label\":").append(Json.quote(label));
        b.append(",\"ok\":").append(failures.isEmpty());
        b.append(",\"failures\":").append(list(failures, true));
        b.append(",\"peer_game\":").append(peer == null ? "null" : Json.quote(peer.game()));
        b.append(",\"protocol\":").append(peer == null ? "null" : String.valueOf(peer.protocol()));
        b.append(",\"session\":").append(peer == null ? "null" : Integer.toUnsignedString(peer.session()));
        b.append(",\"spawns\":").append(list(spawns, false));
        b.append(",\"despawns\":").append(list(despawns, false));
        b.append(",\"cameras\":").append(camerasOk);
        b.append(",\"poses\":").append(posesOk);
        b.append(",\"collision_triangles\":").append(collisionTriangles);
        b.append(",\"frames_ok\":").append(framesOk);
        b.append(",\"frames_bad\":").append(framesBad);
        b.append(",\"focus\":").append(list(focus, true));
        b.append(",\"lost_reason\":").append(lostReason == null ? "null" : Json.quote(lostReason));
        b.append(",\"stats\":null");
        return b.append('}').toString();
    }

    private int run(int port) {
        LinkConfig cfg = new LinkConfig("interop " + role.wire + " (" + label + ")");
        cfg.controlPort = port;
        cfg.udpPort = 0;
        cfg.heartbeatMs = 100;
        cfg.timeoutMs = 3000;
        cfg.reconnectMs = 200;
        try {
            link = role == Role.HOST ? Link.host(cfg) : Link.guest(cfg);
            log("started, control port " + port + ", udp port " + link.udpPort());
            if (role == Role.HOST) runHost();
            else runGuest();
        } catch (Abort a) {
            fail(a.getMessage());
        } catch (Exception e) {
            fail("unexpected " + e);
            e.printStackTrace(System.out);
        } finally {
            if (link != null) link.close();
        }
        System.out.println("RBL-INTEROP-RESULT " + resultJson());
        System.out.flush();
        return failures.isEmpty() ? 0 : 1;
    }

    public static void main(String[] args) {
        Role role = null;
        int port = -1;
        String label = null;
        if (args.length >= 1) role = Role.parse(args[0]);
        for (int i = 1; role != null && i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    port = -1;
                }
            } else if (args[i].equals("--label") && i + 1 < args.length) {
                label = args[++i];
            } else {
                role = null;
            }
        }
        if (role == null || port < 1 || port > 65535 || label == null) {
            System.err.println("usage: InteropPeer host|guest --port PORT --label LABEL");
            System.exit(2);
            return;
        }
        System.exit(new InteropPeer(role, label).run(port));
    }
}
