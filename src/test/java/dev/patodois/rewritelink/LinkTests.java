package dev.patodois.rewritelink;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Wire.Datagram;
import dev.patodois.rewritelink.Wire.Header;
import dev.patodois.rewritelink.Wire.WireException;

/**
 * Plain test runner (no dependencies): {@code java -cp build/classes/java/main:build/test-classes
 * dev.patodois.rewritelink.LinkTests}. Exits 1 if any test fails.
 */
public final class LinkTests {
    private static int passed, failed;

    @FunctionalInterface
    interface Test {
        void run() throws Exception;
    }

    static void test(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("ok   " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    static void eq(Object a, Object b, String what) {
        if (!java.util.Objects.equals(a, b)) throw new AssertionError(what + ": expected " + b + " but was " + a);
    }

    static byte[] hex(String s) {
        String h = s.replace(" ", "");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    static void expectWireError(Test t, String what) throws Exception {
        try {
            t.run();
        } catch (WireException e) {
            return;
        }
        throw new AssertionError("expected a WireException: " + what);
    }

    static boolean until(long ms, BooleanSupplier c) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(2);
        }
        return c.getAsBoolean();
    }

    // The worked example of CONTRACT.md section 11.
    static final String EXAMPLE_HEARTBEAT = "52 42 4C 4B 01 00 01 00 01 00 CD AB 04 00 00 00 00 40 1E 18 24 0A 06 00";

    public static void main(String[] args) throws Exception {
        // ------------------------------------------------------------- wire format
        test("worked example: heartbeat encodes to the contract bytes", () -> {
            Header h = new Header(1, 0, 0xABCD0001, 4, 1_700_000_000_000_000L);
            eq(Arrays.toString(Wire.encode(h, new Datagram.Heartbeat())), Arrays.toString(hex(EXAMPLE_HEARTBEAT)),
                    "heartbeat bytes");
        });

        test("worked example: heartbeat decodes from the contract bytes", () -> {
            byte[] b = hex(EXAMPLE_HEARTBEAT);
            Wire.Decoded d = Wire.decode(b, b.length);
            eq(d.header(), new Header(1, 0, 0xABCD0001, 4, 1_700_000_000_000_000L), "header");
            check(d.body() instanceof Datagram.Heartbeat, "kind 1 is a heartbeat");
            eq(Integer.toUnsignedLong(d.header().session()), 0xABCD0001L, "session as u32");
        });

        test("constants match the contract", () -> {
            eq(Wire.MAGIC, 0x4B4C4252, "magic");
            eq(Wire.MAX_POSES, 102, "max poses");
            eq(Wire.MAX_TRIANGLES, 226, "max triangles");
            eq(Wire.HEADER_LEN, 24, "header");
            eq(Wire.CAMERA_LEN, 152, "camera payload");
            eq(Wire.DEFAULT_CONTROL_PORT, 47800, "port");
            byte[] magic = Wire.encode(new Header(1, 0, 1, 0, 0), new Datagram.Heartbeat());
            eq(new String(magic, 0, 4, StandardCharsets.US_ASCII), "RBLK", "magic bytes spell RBLK");
        });

        test("camera: 176-byte datagram with the contract offsets", () -> {
            float[] view = new float[16], proj = new float[16];
            for (int i = 0; i < 16; i++) {
                view[i] = i + 1;
                proj[i] = 100 + i;
            }
            Wire.Camera c = new Wire.Camera(view, proj, 1.0f, 0.1f, Float.POSITIVE_INFINITY, 1280, 720,
                    Wire.DepthRange.REVERSED);
            byte[] b = Wire.encode(new Header(1, 0, 7, 9, 5), new Datagram.CameraMsg(c));
            eq(b.length, 176, "datagram length");
            ByteBuffer bb = le(b);
            eq(bb.getFloat(24), 1f, "view[0] at payload 0");
            eq(bb.getFloat(24 + 15 * 4), 16f, "view[15]");
            eq(bb.getFloat(24 + 64), 100f, "proj[0] at payload 64");
            eq(bb.getFloat(24 + 128), 1.0f, "fov_y at payload 128");
            eq(bb.getFloat(24 + 132), 0.1f, "near at payload 132");
            eq(bb.getFloat(24 + 136), Float.POSITIVE_INFINITY, "far at payload 136");
            eq(bb.getInt(24 + 140), 1280, "viewport_width at payload 140");
            eq(bb.getInt(24 + 144), 720, "viewport_height at payload 144");
            eq(bb.getInt(24 + 148), 2, "depth_range at payload 148");
            Wire.Camera back = ((Datagram.CameraMsg) Wire.decode(b, b.length).body()).camera();
            check(Arrays.equals(back.view(), view) && Arrays.equals(back.proj(), proj), "matrices round trip");
            eq(back.depthRange(), Wire.DepthRange.REVERSED, "depth range");
        });

        test("pose: record offsets and exact length 24 + 8 + 80n", () -> {
            Wire.Pose p = new Wire.Pose(0xFFFFFFFEL, Wire.POSE_TELEPORT, 1_700_000_000_123_456L,
                    new float[] {1, 2, 3}, new float[] {0, 0.5f, 0, 0.5f}, new float[] {1, 1, 2},
                    new float[] {4, 5, 6}, new float[] {7, 8, 9});
            byte[] b = Wire.encode(new Header(1, 0, 7, 1, 2), new Datagram.PoseMsg(Arrays.asList(p, p)));
            eq(b.length, 24 + 8 + 160, "two poses");
            ByteBuffer bb = le(b);
            eq(bb.getInt(24), 2, "count");
            int r = 32; // first record
            eq(bb.getInt(r), 0xFFFFFFFE, "entity");
            eq(bb.getInt(r + 4), 1, "flags");
            eq(bb.getLong(r + 8), 1_700_000_000_123_456L, "time_us");
            eq(bb.getFloat(r + 16), 1f, "translation.x");
            eq(bb.getFloat(r + 28 + 4), 0.5f, "rotation.y");
            eq(bb.getFloat(r + 28 + 12), 0.5f, "rotation.w");
            eq(bb.getFloat(r + 44 + 8), 2f, "scale.z");
            eq(bb.getFloat(r + 56), 4f, "velocity.x");
            eq(bb.getFloat(r + 68 + 8), 9f, "angular_velocity.z");
            Wire.Pose back = ((Datagram.PoseMsg) Wire.decode(b, b.length).body()).poses().get(1);
            eq(back.entity(), 0xFFFFFFFEL, "entity round trip as u32");
            check(Arrays.equals(back.translation(), p.translation()), "translation round trip");
        });

        test("pose: a full datagram of 102 poses is exactly 8192 bytes", () -> {
            List<Wire.Pose> ps = new ArrayList<>();
            for (int i = 0; i < 102; i++) ps.add(Wire.Pose.identity(i, 10));
            byte[] b = Wire.encode(new Header(1, 0, 1, 0, 0), new Datagram.PoseMsg(ps));
            eq(b.length, 8192, "full pose datagram");
            expectWireError(() -> Wire.encode(new Header(1, 0, 1, 0, 0),
                    new Datagram.PoseMsg(Collections.<Wire.Pose>emptyList())), "zero poses");
        });

        test("collision: chunking, offsets and exact length", () -> {
            List<float[]> tris = new ArrayList<>();
            for (int i = 0; i < 800; i++) tris.add(new float[] {i, 0, 0, i, 0, 1, i + 1, 0, 1});
            List<Wire.CollisionChunk> chunks = Wire.chunks(5, new float[] {1, 2, 3}, 10f, tris);
            eq(chunks.size(), 4, "800 triangles need 4 chunks of at most 226");
            eq(chunks.get(0).triangles().size(), 226, "first chunk");
            eq(chunks.get(3).triangles().size(), 800 - 3 * 226, "last chunk");
            byte[] b = Wire.encode(new Header(1, 0, 1, 0, 0), new Datagram.CollisionMsg(chunks.get(3)));
            eq(b.length, 24 + 32 + 36 * (800 - 3 * 226), "length");
            ByteBuffer bb = le(b);
            eq(bb.getInt(24), 5, "query");
            eq(Short.toUnsignedInt(bb.getShort(28)), 3, "chunk_index");
            eq(Short.toUnsignedInt(bb.getShort(30)), 4, "chunk_count");
            eq(bb.getFloat(32), 1f, "center.x");
            eq(bb.getFloat(44), 10f, "radius");
            eq(bb.getInt(48), 800 - 3 * 226, "triangle_count");
            eq(Wire.chunks(1, new float[3], 1f, Collections.<float[]>emptyList()).size(), 1, "empty snapshot is one chunk");
            Wire.CollisionChunk back = ((Datagram.CollisionMsg) Wire.decode(b, b.length).body()).chunk();
            check(Arrays.equals(back.triangles().get(0), chunks.get(3).triangles().get(0)), "triangle round trip");
        });

        test("frame datagram: 48 bytes", () -> {
            byte[] b = Wire.encode(new Header(1, 0, 1, 0, 0),
                    new Datagram.FrameMsg(new Wire.FrameReady(3, 0, 77, 64, 36)));
            eq(b.length, 48, "frame length");
            Wire.FrameReady f = ((Datagram.FrameMsg) Wire.decode(b, b.length).body()).frame();
            eq(f, new Wire.FrameReady(3, 0, 77, 64, 36), "frame round trip");
        });

        test("malformed datagrams are rejected, never crash", () -> {
            byte[] ok = hex(EXAMPLE_HEARTBEAT);
            expectWireError(() -> Wire.decode(ok, 23), "truncated header");
            byte[] badMagic = ok.clone();
            badMagic[0] = 'X';
            expectWireError(() -> Wire.decode(badMagic, badMagic.length), "bad magic");
            byte[] badVersion = ok.clone();
            badVersion[4] = 2;
            expectWireError(() -> Wire.decode(badVersion, badVersion.length), "bad version");
            byte[] longHeartbeat = Arrays.copyOf(ok, 25);
            expectWireError(() -> Wire.decode(longHeartbeat, longHeartbeat.length), "heartbeat with payload");
            byte[] cam = Wire.encode(new Header(1, 0, 1, 0, 0), new Datagram.CameraMsg(
                    Wire.Camera.lookAt(new float[] {0, 0, 5}, new float[3], 1f, 0.1f, 100f, 16, 9)));
            expectWireError(() -> Wire.decode(cam, cam.length - 1), "short camera");
            byte[] badDepth = cam.clone();
            badDepth[24 + 148] = 3;
            expectWireError(() -> Wire.decode(badDepth, badDepth.length), "depth_range 3");
            byte[] pose = Wire.encode(new Header(1, 0, 1, 0, 0), new Datagram.PoseMsg(Collections.singletonList(Wire.Pose.identity(1, 1))));
            byte[] count0 = pose.clone();
            count0[24] = 0;
            expectWireError(() -> Wire.decode(count0, count0.length), "pose count 0");
            byte[] count2 = pose.clone();
            count2[24] = 2;
            expectWireError(() -> Wire.decode(count2, count2.length), "pose count does not match length");
            byte[] frame0 = Wire.encode(new Header(1, 0, 1, 0, 0), new Datagram.FrameMsg(new Wire.FrameReady(1, 0, 0, 1, 1)));
            Arrays.fill(frame0, 24, 32, (byte) 0);
            expectWireError(() -> Wire.decode(frame0, frame0.length), "frame 0");
            // An unknown kind is not an error.
            byte[] unknown = ok.clone();
            unknown[6] = 99;
            check(Wire.decode(unknown, unknown.length).body() instanceof Datagram.Unknown, "unknown kind ignored");
            // Fuzz: random bytes must only ever throw WireException.
            java.util.Random rnd = new java.util.Random(42);
            for (int i = 0; i < 20000; i++) {
                byte[] junk = new byte[rnd.nextInt(300)];
                rnd.nextBytes(junk);
                if (junk.length >= 8 && rnd.nextBoolean()) {
                    System.arraycopy(ok, 0, junk, 0, 6);
                    junk[6] = (byte) (1 + rnd.nextInt(6));
                }
                try {
                    Wire.decode(junk, junk.length);
                } catch (WireException expected) {
                    // fine
                }
            }
        });

        test("serial arithmetic wraps", () -> {
            check(Wire.seqNewer(1, 0), "1 newer than 0");
            check(!Wire.seqNewer(0, 1), "0 not newer than 1");
            check(Wire.seqNewer(0, -1), "0 newer than 0xFFFFFFFF after wrap");
            check(!Wire.seqNewer(5, 5), "equal is not newer");
            eq(Wire.negotiate(1, 1, 1, 1), 1, "same version");
            eq(Wire.negotiate(1, 3, 1, 2), 2, "overlap picks the lower max");
            eq(Wire.negotiate(2, 3, 1, 1), -1, "disjoint ranges");
        });

        test("camera helpers: eye and forward invert look_at", () -> {
            Wire.Camera c = Wire.Camera.lookAt(new float[] {3, 4, 5}, new float[] {3, 4, 0}, 1f, 0.1f, 100f, 16, 9);
            float[] eye = c.eye(), fwd = c.forward();
            for (int i = 0; i < 3; i++) check(Math.abs(eye[i] - new float[] {3, 4, 5}[i]) < 1e-4, "eye " + i);
            check(Math.abs(fwd[2] + 1) < 1e-5 && Math.abs(fwd[0]) < 1e-5, "forward is -Z");
        });

        test("quaternions: rotate and from_to agree", () -> {
            float[] q = dev.patodois.rewritelink.Quat.axisAngle(new float[] {0, 1, 0}, (float) Math.PI / 2);
            float[] r = dev.patodois.rewritelink.Quat.rotate(q, new float[] {0, 0, 1});
            check(Math.abs(r[0] - 1) < 1e-5 && Math.abs(r[1]) < 1e-5 && Math.abs(r[2]) < 1e-5, "+Z turned 90 deg about +Y is +X");
            float[] target = {0.3f, 0.2f, -0.9f};
            float[] qq = dev.patodois.rewritelink.Quat.fromTo(new float[] {0, 0, 1}, target);
            float[] fwd = dev.patodois.rewritelink.Quat.rotate(qq, new float[] {0, 0, 1});
            float n = (float) Math.sqrt(0.09 + 0.04 + 0.81);
            for (int i = 0; i < 3; i++) check(Math.abs(fwd[i] - target[i] / n) < 1e-5, "fromTo axis " + i);
            float[] flip = dev.patodois.rewritelink.Quat.fromTo(new float[] {0, 0, 1}, new float[] {0, 0, -1});
            float[] back = dev.patodois.rewritelink.Quat.rotate(flip, new float[] {0, 0, 1});
            check(Math.abs(back[2] + 1) < 1e-5, "opposite direction");
        });

        // ------------------------------------------------------------- control lines
        test("control: contract examples parse and serialise", () -> {
            Control g = Control.parse("{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"Warframe rewrite\","
                    + "\"protocol\":1,\"min_protocol\":1,\"udp_port\":53001,\"heartbeat_ms\":250,\"timeout_ms\":2000}\n");
            eq(g, new Control.Hello(Role.GUEST, "Warframe rewrite", 1, 1, 53001, 250, 2000, null), "guest hello");
            Control h = Control.parse("{\"type\":\"hello\",\"role\":\"host\",\"game\":\"War Thunder rewrite\","
                    + "\"protocol\":1,\"min_protocol\":1,\"udp_port\":53000,\"heartbeat_ms\":250,\"timeout_ms\":2000,"
                    + "\"session\":2882400001}\r\n");
            eq(((Control.Hello) h).session(), 2882400001L, "session 0xABCD0001");
            eq(Control.parse("{\"type\":\"spawn\",\"entity\":7,\"provider\":\"Warframe\","
                    + "\"asset\":\"/Lotus/Characters/Excalibur\",\"name\":\"Excalibur\"}"),
                    new Control.Spawn(7, "Warframe", "/Lotus/Characters/Excalibur", "Excalibur"), "spawn");
            eq(Control.parse("{\"type\":\"despawn\",\"entity\":7}"), new Control.Despawn(7), "despawn");
            eq(Control.parse("{\"type\":\"input\",\"focus\":\"guest\"}"), new Control.Input(Role.GUEST, null), "input");
            eq(Control.parse("{\"type\":\"bye\",\"reason\":\"shutting down\"}"), new Control.Bye("shutting down"), "bye");
            eq(Control.parse("{\"type\":\"frame_channel\",\"path\":\"/tmp/x.frames\",\"width\":1280,\"height\":720,\"slots\":3}"),
                    new Control.FrameChannel("/tmp/x.frames", 1280, 720, 3), "frame_channel");
            eq(Control.parse("{\"type\":\"future_thing\",\"x\":1}"), new Control.Unknown("future_thing"), "unknown type");
            eq(Control.parse("{\"type\":\"despawn\",\"entity\":7,\"extra\":[1,{\"a\":null}]}"), new Control.Despawn(7),
                    "unknown fields ignored");
            // Round trips through the line writer.
            for (Control c : Arrays.asList(g, h, new Control.Spawn(1, "a\"b", "c\\d\n", null), new Control.Despawn(4294967295L),
                    new Control.Input(Role.HOST, "why"), new Control.Bye("é ü 日本"))) {
                String line = c.toLine();
                check(line.endsWith("\n") && line.indexOf('\n') == line.length() - 1, "one line");
                eq(Control.parse(line), c, "round trip " + line.trim());
            }
        });

        test("control: broken lines are protocol errors", () -> {
            for (String bad : Arrays.asList("not json", "[]", "{\"no\":\"type\"}", "{\"type\":5}",
                    "{\"type\":\"despawn\"}", "{\"type\":\"despawn\",\"entity\":4294967296}",
                    "{\"type\":\"despawn\",\"entity\":-1}", "{\"type\":\"despawn\",\"entity\":1.5}",
                    "{\"type\":\"input\",\"focus\":\"both\"}",
                    "{\"type\":\"hello\",\"role\":\"host\",\"game\":\"g\",\"protocol\":70000,\"min_protocol\":1,"
                            + "\"udp_port\":1,\"heartbeat_ms\":1,\"timeout_ms\":1}",
                    "{\"type\":\"spawn\",\"entity\":1,\"provider\":\"p\"}")) {
                expectWireError(() -> Control.parse(bad), bad);
            }
        });

        // ------------------------------------------------------------- live sessions (loopback, in process)
        test("handshake, state flow and a clean bye with half-close", () -> {
            LinkConfig hc = new LinkConfig("Test Host");
            hc.controlPort = 0;
            try (Link host = Link.host(hc)) {
                LinkConfig gc = new LinkConfig("Test Guest");
                gc.controlPort = host.controlPort();
                try (Link guest = Link.guest(gc)) {
                    List<LinkEvent> hostEvents = new ArrayList<>(), guestEvents = new ArrayList<>();
                    check(until(3000, () -> {
                        hostEvents.addAll(host.poll());
                        guestEvents.addAll(guest.poll());
                        return host.isConnected() && guest.isConnected();
                    }), "both sides connect");
                    LinkEvent.PeerInfo gp = guest.peer(), hp = host.peer();
                    eq(gp.game(), "Test Host", "guest sees the host game");
                    eq(hp.game(), "Test Guest", "host sees the guest game");
                    eq(gp.session(), hp.session(), "same session");
                    check(gp.session() != 0, "session is nonzero");
                    eq(gp.protocol(), 1, "protocol 1");

                    // Camera host -> guest, Pose guest -> host.
                    Wire.Camera cam = Wire.Camera.lookAt(new float[] {1, 2, 3}, new float[] {1, 2, 0}, 1f, .1f, 50f, 640, 360);
                    host.sendCamera(cam);
                    guest.spawn(new Control.Spawn(9, "Test", "demo/cube", "Cube"));
                    Wire.Pose pose = Wire.Pose.identity(9, Wire.nowUs());
                    pose.translation()[0] = 42;
                    guest.sendPoses(Collections.singletonList(pose));
                    check(until(2000, () -> {
                        hostEvents.addAll(host.poll());
                        guestEvents.addAll(guest.poll());
                        return guestEvents.stream().anyMatch(e -> e instanceof LinkEvent.Camera)
                                && hostEvents.stream().anyMatch(e -> e instanceof LinkEvent.Poses)
                                && hostEvents.stream().anyMatch(e -> e instanceof LinkEvent.Spawn);
                    }), "camera, spawn and pose arrive");
                    LinkEvent.Poses got = (LinkEvent.Poses) hostEvents.stream()
                            .filter(e -> e instanceof LinkEvent.Poses).findFirst().get();
                    eq(got.poses().get(0).translation()[0], 42f, "pose x");

                    // A stale pose (older time) is dropped.
                    Wire.Pose old = Wire.Pose.identity(9, pose.timeUs() - 1000);
                    guest.sendPoses(Collections.singletonList(old));
                    Thread.sleep(50);
                    hostEvents.clear();
                    hostEvents.addAll(host.poll());
                    check(hostEvents.stream().noneMatch(e -> e instanceof LinkEvent.Poses), "stale pose dropped");

                    // Focus: the guest asks, the host grants.
                    guest.setFocus(Role.GUEST);
                    check(until(2000, () -> host.poll().stream().anyMatch(e -> e instanceof LinkEvent.Focus && ((LinkEvent.Focus) e).requested())),
                            "host sees the focus request");
                    host.setFocus(Role.GUEST);
                    check(until(2000, () -> guest.poll().stream().anyMatch(e -> e instanceof LinkEvent.Focus)), "guest sees grant");
                    eq(guest.focus(), Role.GUEST, "guest has the focus");

                    // Bye: the guest disconnects; the host sees "peer said bye", not a reset failure.
                    guest.disconnect("test over");
                    List<LinkEvent> after = new ArrayList<>();
                    check(until(2000, () -> {
                        after.addAll(host.poll());
                        guest.poll();
                        return after.stream().anyMatch(e -> e instanceof LinkEvent.Lost);
                    }), "host notices the end");
                    LinkEvent.Lost lost = (LinkEvent.Lost) after.stream().filter(e -> e instanceof LinkEvent.Lost).findFirst().get();
                    check(lost.reason().contains("peer said bye: test over"), "reason is the bye, got: " + lost.reason());
                    eq(host.focus(), Role.HOST, "focus returns to the host");

                    // The guest reconnects on its own with a new session.
                    check(until(4000, () -> {
                        host.poll();
                        guest.poll();
                        return host.isConnected() && guest.isConnected();
                    }), "guest reconnects");
                    check(host.peer().session() != hp.session(), "new session after reconnect");
                }
            }
        });

        test("a second connection gets bye busy", () -> {
            LinkConfig hc = new LinkConfig("Busy Host");
            hc.controlPort = 0;
            try (Link host = Link.host(hc)) {
                LinkConfig gc = new LinkConfig("G");
                gc.controlPort = host.controlPort();
                try (Link guest = Link.guest(gc);
                     Socket extra = new Socket()) {
                    check(until(3000, () -> {
                        host.poll();
                        guest.poll();
                        return host.isConnected() && guest.isConnected();
                    }), "first guest connects");
                    extra.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), host.controlPort()));
                    extra.setSoTimeout(2000);
                    Thread pump = new Thread(() -> {
                        for (int i = 0; i < 300; i++) {
                            host.poll();
                            guest.poll();
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    });
                    pump.start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(extra.getInputStream(), StandardCharsets.UTF_8));
                    String line = r.readLine();
                    eq(line, "{\"type\":\"bye\",\"reason\":\"busy\"}", "busy bye line");
                    eq(r.readLine(), null, "then end of stream (clean close)");
                    pump.join();
                    check(host.isConnected(), "the first session is untouched");
                }
            }
        });

        test("a guest with a wrong role or an incompatible protocol gets a bye", () -> {
            LinkConfig hc = new LinkConfig("Strict Host");
            hc.controlPort = 0;
            try (Link host = Link.host(hc)) {
                for (String[] c : new String[][] {
                        {"{\"type\":\"hello\",\"role\":\"host\",\"game\":\"x\",\"protocol\":1,\"min_protocol\":1,\"udp_port\":5,\"heartbeat_ms\":250,\"timeout_ms\":2000}", "role"},
                        {"{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"x\",\"protocol\":9,\"min_protocol\":2,\"udp_port\":5,\"heartbeat_ms\":250,\"timeout_ms\":2000}", "protocol"},
                        {"garbage", "protocol error"}}) {
                    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), host.controlPort())) {
                        s.setSoTimeout(2000);
                        s.getOutputStream().write((c[0] + "\n").getBytes(StandardCharsets.UTF_8));
                        Thread pump = new Thread(() -> {
                            for (int i = 0; i < 200; i++) {
                                host.poll();
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        });
                        pump.start();
                        String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
                        check(line != null && line.startsWith("{\"type\":\"bye\"") && line.contains(c[1]),
                                "bye mentioning " + c[1] + " but got " + line);
                        pump.join();
                    }
                }
            }
        });

        test("watchdog: a silent guest is lost after the timeout", () -> {
            LinkConfig hc = new LinkConfig("Watch Host");
            hc.controlPort = 0;
            hc.timeoutMs = 400;
            hc.heartbeatMs = 100;
            try (Link host = Link.host(hc);
                 Socket s = new Socket(InetAddress.getLoopbackAddress(), host.controlPort())) {
                // The guest says hello with a heartbeat of 50 ms (effective timeout max(400, 200)) and then goes silent.
                s.getOutputStream().write(("{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"mute\",\"protocol\":1,"
                        + "\"min_protocol\":1,\"udp_port\":9,\"heartbeat_ms\":50,\"timeout_ms\":400}\n").getBytes(StandardCharsets.UTF_8));
                List<LinkEvent> events = new ArrayList<>();
                long t0 = System.nanoTime();
                check(until(3000, () -> {
                    events.addAll(host.poll());
                    return events.stream().anyMatch(e -> e instanceof LinkEvent.Lost);
                }), "watchdog fires");
                long ms = (System.nanoTime() - t0) / 1_000_000;
                check(ms >= 350 && ms < 1500, "fires after about the timeout, took " + ms + " ms");
                LinkEvent.Lost lost = (LinkEvent.Lost) events.stream().filter(e -> e instanceof LinkEvent.Lost).findFirst().get();
                check(lost.reason().startsWith("watchdog"), "reason: " + lost.reason());
                // The host said bye before closing.
                s.setSoTimeout(1000);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String hello = r.readLine();
                check(hello != null && hello.contains("\"role\":\"host\"") && hello.contains("\"session\":"), "host hello: " + hello);
                for (int i = 0; i < 100; i++) host.poll(); // let the drain finish
                String bye = r.readLine();
                check(bye != null && bye.contains("\"type\":\"bye\"") && bye.contains("watchdog"), "bye line: " + bye);
            }
        });

        test("datagrams from another address are dropped", () -> {
            LinkConfig hc = new LinkConfig("Addr Host");
            hc.controlPort = 0;
            try (Link host = Link.host(hc)) {
                LinkConfig gc = new LinkConfig("G");
                gc.controlPort = host.controlPort();
                try (Link guest = Link.guest(gc);
                     java.net.DatagramSocket stranger = new java.net.DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                    check(until(3000, () -> {
                        host.poll();
                        guest.poll();
                        return host.isConnected() && guest.isConnected();
                    }), "connect");
                    byte[] b = Wire.encode(new Header(1, 0, host.peer().session(), 1, Wire.nowUs()),
                            new Datagram.PoseMsg(Collections.singletonList(Wire.Pose.identity(1, Wire.nowUs()))));
                    stranger.send(new java.net.DatagramPacket(b, b.length, InetAddress.getLoopbackAddress(), host.udpPort()));
                    Thread.sleep(50);
                    List<LinkEvent> ev = host.poll();
                    check(ev.stream().noneMatch(e -> e instanceof LinkEvent.Poses), "stranger's pose ignored");
                    check(host.foreign >= 1, "counted as foreign");
                }
            }
        });

        PixelFileTests.run();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
