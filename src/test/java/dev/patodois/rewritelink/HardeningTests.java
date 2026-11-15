package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;
import static dev.patodois.rewritelink.LinkTests.until;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Regression tests for robustness fixes: inputs and calls that used to cost too much, fail silently or reach the peer
 * as values the wire cannot carry. Each test fails on the code before its fix.
 */
final class HardeningTests {
    private HardeningTests() {}

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    static void run() {
        test("json: an integer literal longer than 309 digits is refused by its length, as before", () -> {
            // Lengths around the boundary: the same accept/reject set as computing the BigInteger and asking whether it
            // is finite as a double.
            for (int digits = 300; digits <= 320; digits++) {
                for (String body : new String[] {repeat('9', digits), "1" + repeat('0', digits - 1), "17976931348623157" + repeat('0', Math.max(0, digits - 17))}) {
                    if (body.length() != digits) continue;
                    for (String t : new String[] {body, "-" + body}) {
                        boolean finite = !Double.isInfinite(new BigInteger(t).doubleValue());
                        boolean accepted;
                        try {
                            Object v = Json.parse(t);
                            accepted = true;
                            eq(v, new BigInteger(t), "value of a " + digits + "-digit integer");
                        } catch (Json.JsonException e) {
                            accepted = false;
                            eq(e.getMessage(), "number out of range", "message for a " + digits + "-digit integer");
                        }
                        eq(accepted, finite, "a " + digits + "-digit integer starting " + t.substring(0, 3) + " accepted");
                    }
                }
            }
        });

        test("json: a 65500-digit integer in a control line is refused in bounded time", () -> {
            // Building the BigInteger is quadratic in the digits: such a line took about 45 ms per parse before the length
            // check, on a recent laptop, and well under a millisecond after it. Twenty parses per round, best of three
            // rounds, must stay under 200 ms: ten times the old cost of one parse, a hundred times the new cost of twenty.
            String line = "{\"type\":\"hello\",\"x\":" + repeat('9', 65500) + "}";
            check(line.length() < Wire.MAX_LINE, "the line fits a control line");
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 3; round++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < 20; i++) {
                    try {
                        Control.parse(line);
                        throw new AssertionError("a 65500-digit integer was accepted");
                    } catch (Wire.WireException expected) {
                        check(expected.getMessage().contains("number out of range"), "message: " + expected.getMessage());
                    }
                }
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000L);
            }
            System.out.println("     twenty parses of a 65500-digit integer: " + best + " ms (best of three)");
            check(best < 200, "twenty parses of a 65500-digit integer took " + best + " ms");
        });

        interrupts();

        test("send: values the wire cannot carry are refused with nothing sent and the session kept", () -> {
            try (ReviewTests.Pair p = new ReviewTests.Pair()) {
                LinkTests.expectWireError(() -> p.guest.spawn(new Control.Spawn(-1, "t", "a", null)), "spawn of entity -1");
                LinkTests.expectWireError(() -> p.guest.spawn(new Control.Spawn(1L << 32, "t", "a", null)), "spawn of entity 2^32");
                LinkTests.expectWireError(() -> p.guest.despawn(-1), "despawn of entity -1");
                LinkTests.expectWireError(() -> p.guest.announceFrames(new Control.FrameChannel("/x.frames", -1, 2, 3)), "width -1");
                LinkTests.expectWireError(() -> p.guest.announceFrames(new Control.FrameChannel("/x.frames", 2, 1L << 32, 3)), "height 2^32");
                LinkTests.expectWireError(() -> p.guest.announceFrames(new Control.FrameChannel("/x.frames", 2, 2, -3)), "slots -3");
                Wire.Pose far = Wire.Pose.identity((1L << 32) + 7, Wire.nowUs());
                LinkTests.expectWireError(() -> Wire.encode(new Wire.Header(1, 0, 1, 0, 0), new Wire.Datagram.PoseMsg(Collections.singletonList(far))),
                        "Wire.encode of entity 2^32 + 7");
                LinkTests.expectWireError(() -> p.guest.sendPoses(Collections.singletonList(far)), "a pose of entity 2^32 + 7");
                List<float[]> tris = new ArrayList<>();
                for (int i = 0; i < Wire.MAX_TRIANGLES; i++) tris.add(new float[9]);
                tris.add(new float[8]); // the first triangle of the second chunk
                LinkTests.expectWireError(() -> p.guest.sendCollision(new float[3], 1f, tris), "a triangle of 8 floats");
                LinkTests.expectWireError(() -> p.guest.sendCollision(new float[3], 1f, Collections.singletonList(null)), "a null triangle");
                LinkTests.expectWireError(() -> p.guest.sendCollision(new float[2], 1f, Collections.<float[]>emptyList()), "a centre of 2 floats");
                long sentBefore = p.guest.datagramsSent;
                p.guest.poll();
                eq(p.guest.datagramsSent - sentBefore <= 1, true, "no collision chunk was queued (a heartbeat may go)");

                // Nothing reached the host: the session is intact and the next valid messages arrive in order.
                p.guest.spawn(new Control.Spawn(4294967295L, "t", "a", null));
                eq(p.guest.sendCollision(new float[3], 1f, Collections.singletonList(new float[9])), 1, "the query number was not used");
                List<LinkEvent> he = new ArrayList<>();
                check(until(2000, () -> {
                    he.addAll(p.host.poll());
                    p.guest.poll();
                    return ReviewTests.count(he, LinkEvent.Spawn.class) == 1 && ReviewTests.count(he, LinkEvent.Collision.class) == 1;
                }), "the valid spawn and snapshot arrive: " + he);
                eq(((LinkEvent.Spawn) he.stream().filter(e -> e instanceof LinkEvent.Spawn).findFirst().get()).spawn().entity(), 4294967295L, "entity");
                check(ReviewTests.count(he, LinkEvent.Lost.class) == 0 && p.host.isConnected() && p.guest.isConnected(), "still connected: " + he);
            }
        });

        test("pixel file: a file replaced between the check and the open is refused", () -> {
            if (System.getProperty("os.name", "").startsWith("Windows")) return; // symbolic links need a privilege there
            Path dir = tempDir("swap");
            Path path = dir.resolve("f.frames"), other = dir.resolve("other.frames"), link = dir.resolve("link.frames");
            try (PixelFile.Writer a = new PixelFile.Writer(path, 4, 2, 3, false);
                 PixelFile.Writer b = new PixelFile.Writer(other, 4, 2, 3, false)) {
                a.write(new byte[32], null, 4, 2, 0, 1);
                b.write(new byte[32], null, 4, 2, 0, 1);
                // A symbolic link swapped in after the type check: the open must not follow it.
                Files.createSymbolicLink(link, other);
                boolean refused = false;
                try (PixelFile.Reader r = PixelFile.Reader.open(path, () -> {
                    try {
                        Files.move(link, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })) {
                    check(r == null, "unreachable");
                } catch (java.io.IOException expected) {
                    refused = true;
                }
                check(refused, "a symlink swapped in before the open is refused");
            }
            try (PixelFile.Writer a = new PixelFile.Writer(path, 4, 2, 3, false);
                 PixelFile.Writer b = new PixelFile.Writer(other, 4, 2, 3, false)) {
                a.write(new byte[32], null, 4, 2, 0, 1);
                b.write(new byte[32], null, 4, 2, 0, 1);
                // Another regular file renamed over the checked one: a different file key.
                boolean refused = false;
                try (PixelFile.Reader r = PixelFile.Reader.open(path, () -> {
                    try {
                        Files.move(other, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })) {
                    check(r == null, "unreachable");
                } catch (PixelFile.FormatException expected) {
                    refused = true;
                }
                check(refused, "another file renamed over the checked one is refused");
                Files.deleteIfExists(path);
            }
            try (PixelFile.Writer a = new PixelFile.Writer(path, 4, 2, 3, false);
                 PixelFile.Reader r = PixelFile.Reader.open(path, () -> { })) {
                long f = a.write(new byte[32], null, 4, 2, 0, 1);
                check(r.read(new Wire.FrameReady(f, (int) (f % 3), 0, 4, 2), false) != null, "an unchanged file opens and reads");
            }
        });

        test("pixel file: a Unix reader opens a file over 2 GiB and reads its last slot", () -> {
            if (System.getProperty("os.name", "").startsWith("Windows")) return; // mapped there: 2 GiB at most
            if ("1".equals(System.getenv("RBL_VECTORS_SKIP_LARGE"))) {
                System.out.println("     skipped: RBL_VECTORS_SKIP_LARGE=1");
                return;
            }
            // 4096 x 4096 with depth and 16 slots: 2147484736 bytes, inside every limit of the contract. Sparse: only the
            // header, one slot header and two rows are written.
            int w = 4096, h = 4096, stride = w * 4, slots = 16;
            long colourEnd = 64 + (long) stride * h, depthOffset = colourEnd, slotSize = depthOffset + (long) stride * h;
            long length = 64 + slots * slotSize;
            check(length > Integer.MAX_VALUE, "the file is over 2 GiB: " + length);
            Path path = tempDir("large").resolve("f.frames");
            try {
                java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                head.put(new byte[] {'R', 'B', 'F', 'R', 'A', 'M', 'E', 0}).putInt(1).putInt(slots).putInt(w).putInt(h)
                        .putInt(stride).putInt(PixelFile.FORMAT_RGBA8).putLong(slotSize)
                        .putInt(PixelFile.DEPTH_LINEAR_F32).putInt(stride).putLong(depthOffset);
                long base = 64 + 15 * slotSize;
                java.nio.ByteBuffer slot = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                slot.putLong(0).putLong(7).putLong(123).putInt(5).putInt(4).putInt(2);
                byte[] row0 = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, row1 = new byte[16];
                java.util.Arrays.fill(row1, (byte) 99);
                try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(path.toFile(), "rw")) {
                    f.setLength(length);
                    f.seek(0);
                    f.write(head.array());
                    f.seek(base);
                    f.write(slot.array());
                    f.seek(base + 64);
                    f.write(row0);
                    f.seek(base + 64 + stride);
                    f.write(row1);
                }
                try (PixelFile.Reader r = PixelFile.Reader.open(path)) {
                    eq(r.slots, 16, "slots");
                    PixelFile.Frame got = r.readLatest(new Wire.FrameReady(7, 15, 5, 4, 2), false);
                    check(got != null, "slot 15 reads");
                    byte[] want = new byte[32];
                    System.arraycopy(row0, 0, want, 0, 16);
                    System.arraycopy(row1, 0, want, 16, 16);
                    check(java.util.Arrays.equals(got.rgba(), want), "the pixels of slot 15");
                    eq(got.timeUs(), 123L, "time_us");
                }
            } finally {
                Files.deleteIfExists(path);
            }
        });

        test("PixelFile.Frame is equal when its arrays are, and prints their lengths", () -> {
            PixelFile.Frame a = new PixelFile.Frame(1, 2, 3, 2, 1, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, new float[] {1f, 2f});
            PixelFile.Frame b = new PixelFile.Frame(1, 2, 3, 2, 1, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, new float[] {1f, 2f});
            PixelFile.Frame c = new PixelFile.Frame(1, 2, 3, 2, 1, new byte[] {1, 2, 3, 4, 5, 6, 7, 9}, new float[] {1f, 2f});
            PixelFile.Frame d = new PixelFile.Frame(1, 2, 3, 2, 1, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, null);
            eq(a, b, "equal contents");
            eq(a.hashCode(), b.hashCode(), "hash codes");
            check(!a.equals(c) && !a.equals(d), "other pixels or no depth");
            check(!a.toString().contains("[B@") && a.toString().contains("8 bytes") && a.toString().contains("2 floats"),
                    "toString: " + a);
            check(d.toString().contains("depth=null"), "toString without depth: " + d);
        });

        test("Camera.lookAt straight down or up gives a rigid view", () -> {
            float[][][] cases = {
                {{0, 10, 0}, {0, 0, 0}}, {{0, -3, 0}, {0, 5, 0}}, {{2, 7, -1}, {2, 1, -1}}, {{1, 2, 3}, {1, 2.0001f, 3}},
            };
            for (float[][] c : cases) {
                Wire.Camera cam = Wire.Camera.lookAt(c[0], c[1], 1f, 0.1f, 100f, 16, 9);
                float[] v = cam.view();
                String what = java.util.Arrays.toString(c[0]) + " -> " + java.util.Arrays.toString(c[1]);
                for (float x : v) check(Float.isFinite(x), "finite view for " + what);
                // The rotation part must be orthonormal: each column has length 1 and the determinant is 1.
                float det = v[0] * (v[5] * v[10] - v[9] * v[6]) - v[4] * (v[1] * v[10] - v[9] * v[2]) + v[8] * (v[1] * v[6] - v[5] * v[2]);
                check(Math.abs(det - 1) < 1e-4, "determinant " + det + " for " + what);
                float[] eye = cam.eye(), fwd = cam.forward();
                for (int i = 0; i < 3; i++) check(Math.abs(eye[i] - c[0][i]) < 1e-3, "eye " + java.util.Arrays.toString(eye) + " for " + what);
                float dir = Math.signum(c[1][1] - c[0][1]);
                check(Math.abs(fwd[1] - dir) < 1e-4, "forward " + java.util.Arrays.toString(fwd) + " for " + what);
            }
        });

        test("json: an unexpected character is not quoted in the error", () -> {
            String line = "{\"type\":\"hello\",\"role\":\"guest\",\"token\":Zebra-secret}";
            try {
                Control.parse(line);
                throw new AssertionError("accepted " + line);
            } catch (Wire.WireException e) {
                eq(e.getMessage(), "bad control line: unexpected character", "message");
            }
        });

        test("disconnect(null) says bye with an empty reason", () -> {
            try (ReviewTests.Pair p = new ReviewTests.Pair()) {
                p.guest.disconnect(null);
                String[] lost = {null};
                check(until(3000, () -> {
                    p.guest.poll();
                    for (LinkEvent e : p.host.poll()) if (e instanceof LinkEvent.Lost) lost[0] = ((LinkEvent.Lost) e).reason();
                    return lost[0] != null;
                }), "the host sees the end");
                eq(lost[0], "peer said bye: ", "the host's reason");
            }
        });

        test("config: ports, timeouts and a null game are checked when the link is created", () -> {
            String[][] bad = {
                {"controlPort", "70000"}, {"controlPort", "-1"}, {"udpPort", "65536"}, {"udpPort", "-1"},
                {"timeoutMs", "-1"}, {"timeoutMs", "4294967296"}, {"reconnectMs", "-1"}, {"reconnectMs", "4294967296"},
                {"game", null},
            };
            for (String[] b : bad) {
                for (boolean host : new boolean[] {true, false}) {
                    LinkConfig c = new LinkConfig("Config");
                    c.controlPort = 0;
                    switch (b[0]) {
                        case "controlPort": c.controlPort = Integer.parseInt(b[1]); break;
                        case "udpPort": c.udpPort = Integer.parseInt(b[1]); break;
                        case "timeoutMs": c.timeoutMs = Long.parseLong(b[1]); break;
                        case "reconnectMs": c.reconnectMs = Long.parseLong(b[1]); break;
                        default: c.game = null;
                    }
                    boolean refused = false;
                    try (Link l = host ? Link.host(c) : Link.guest(c)) {
                        l.poll();
                    } catch (IllegalArgumentException expected) {
                        refused = true;
                    }
                    check(refused, (host ? "host" : "guest") + " with " + b[0] + " = " + b[1] + " must be refused");
                }
            }
            LinkConfig edge = new LinkConfig("Edge");
            edge.controlPort = 0;
            edge.timeoutMs = 4294967295L;
            edge.reconnectMs = 0;
            try (Link l = Link.host(edge)) {
                l.poll();
            }
        });

        test("config: changing a LinkConfig after creating the link changes nothing", () -> {
            LinkConfig hc = new LinkConfig("Host Game");
            hc.controlPort = 0;
            try (Link host = Link.host(hc)) {
                hc.game = "Changed Host";
                LinkConfig gc = new LinkConfig("Guest Game");
                gc.controlPort = host.controlPort();
                try (Link guest = Link.guest(gc)) {
                    gc.controlPort = 1;
                    gc.game = null;
                    gc.reconnectMs = 1_000_000;
                    check(until(3000, () -> {
                        host.poll();
                        guest.poll();
                        return host.isConnected() && guest.isConnected();
                    }), "the guest connects to the port it was created with");
                    eq(host.peer().game(), "Guest Game", "the guest's game");
                    eq(guest.peer().game(), "Host Game", "the host's game");
                }
            }
        });

        test("LinkRunner: a listener that throws on one event still gets the others of the poll, and the tick runs", () -> {
            LinkConfig hc = new LinkConfig("Runner Host");
            hc.controlPort = 0;
            try (Link host = Link.host(hc);
                 java.net.Socket guest = new java.net.Socket(java.net.InetAddress.getByName("127.0.0.1"), host.controlPort())) {
                // The hello and five spawns in one write, so that one poll reads them all.
                StringBuilder lines = new StringBuilder("{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"g\",\"protocol\":1,"
                        + "\"min_protocol\":1,\"udp_port\":9,\"heartbeat_ms\":250,\"timeout_ms\":4000}\n");
                for (int id = 1; id <= 5; id++) {
                    lines.append("{\"type\":\"spawn\",\"entity\":").append(id).append(",\"provider\":\"t\",\"asset\":\"a\"}\n");
                }
                guest.getOutputStream().write(lines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                guest.getOutputStream().flush();
                Thread.sleep(100);
                List<String> seen = Collections.synchronizedList(new ArrayList<>());
                // A long period: the first round is the only one the test waits for.
                try (LinkRunner runner = new LinkRunner(host, e -> {
                    if (!(e instanceof LinkEvent.Spawn)) return;
                    long id = ((LinkEvent.Spawn) e).spawn().entity();
                    if (id == 2) throw new IllegalStateException("a listener bug (expected by the test)");
                    seen.add(Long.toString(id));
                }, () -> seen.add("tick"), 5000)) {
                    runner.start();
                    check(until(2000, () -> seen.contains("tick")), "the tick ran in the first round: " + seen);
                    synchronized (seen) {
                        eq(new ArrayList<>(seen), java.util.Arrays.asList("1", "3", "4", "5", "tick"), "events and tick of the first round");
                    }
                }
            }
        });
    }

    static Path tempDir(String prefix) throws Exception {
        return Files.createTempDirectory(prefix);
    }

    // On Java 8 every NIO channel operation, a non-blocking one too, closes the channel when the calling thread's interrupt
    // status is set; a FileChannel does that on every JVM. These tests must also be run on a Java 8 runtime (see README).
    /** A test that sets the interrupt status: it is cleared whatever happens, so it cannot leak into the next test. */
    static void interruptTest(String name, LinkTests.Test t) {
        test(name, () -> {
            try {
                t.run();
            } finally {
                Thread.interrupted();
            }
        });
    }

    static void interrupts() {
        interruptTest("interrupt: a pending interrupt neither closes a host's channels nor is lost", () -> {
            LinkConfig hc = new LinkConfig("Interrupted Host");
            hc.controlPort = 0;
            try (Link host = Link.host(hc)) {
                int control = host.controlPort(), udp = host.udpPort();
                Thread.currentThread().interrupt();
                host.poll();
                check(Thread.interrupted(), "the interrupt status is still set after poll()");
                eq(host.controlPort(), control, "control port after an interrupted poll");
                eq(host.udpPort(), udp, "UDP port after an interrupted poll");
                eq(host.lastError(), null, "lastError");
                LinkConfig gc = new LinkConfig("G");
                gc.controlPort = control;
                try (Link guest = Link.guest(gc)) {
                    check(until(3000, () -> {
                        host.poll();
                        guest.poll();
                        return host.isConnected() && guest.isConnected();
                    }), "a guest connects after the interrupted poll");
                }
            }
        });

        interruptTest("interrupt: sends and close() with a pending interrupt keep the session and end it with a bye", () -> {
            try (ReviewTests.Pair p = new ReviewTests.Pair()) {
                List<LinkEvent> he = new ArrayList<>();
                Thread.currentThread().interrupt();
                p.guest.spawn(new Control.Spawn(5, "Test", "demo/cube", null));
                p.guest.sendPoses(Collections.singletonList(Wire.Pose.identity(5, Wire.nowUs())));
                p.guest.poll();
                check(Thread.interrupted(), "the interrupt status is still set after the sends");
                check(until(2000, () -> {
                    he.addAll(p.host.poll());
                    p.guest.poll();
                    return ReviewTests.count(he, LinkEvent.Spawn.class) == 1 && ReviewTests.count(he, LinkEvent.Poses.class) == 1;
                }), "the spawn and the pose arrive: " + he);
                check(p.guest.isConnected() && p.host.isConnected(), "still connected");
                Thread.currentThread().interrupt();
                p.guest.close();
                check(Thread.interrupted(), "the interrupt status is still set after close()");
                String[] lost = {null};
                check(until(3000, () -> {
                    for (LinkEvent e : p.host.poll()) if (e instanceof LinkEvent.Lost) lost[0] = ((LinkEvent.Lost) e).reason();
                    return lost[0] != null;
                }), "the host sees the end");
                check(lost[0].startsWith("peer said bye"), "the end is the guest's bye: " + lost[0]);
            }
        });

        interruptTest("interrupt: a pending interrupt does not make a pixel reader gone", () -> {
            Path path = tempDir("interrupt").resolve("f.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(path, 4, 2, 3, true);
                 PixelFile.Reader r = PixelFile.Reader.open(path)) {
                long f1 = w.write(new byte[32], null, 4, 2, 0, 1);
                Thread.currentThread().interrupt();
                PixelFile.Frame got = r.readLatest(new Wire.FrameReady(f1, (int) (f1 % 3), 0, 4, 2), true);
                boolean gone = r.isGone();
                check(Thread.interrupted(), "the interrupt status is still set after the read");
                check(got != null, "the frame is read with a pending interrupt");
                check(!gone, "the reader is not gone");
                long f2 = w.write(new byte[32], null, 4, 2, 0, 2);
                check(r.readLatest(new Wire.FrameReady(f2, (int) (f2 % 3), 0, 4, 2), true) != null, "the next frame is read too");
            }
        });

        test("a closed UDP channel or listener shows in lastError", () -> {
            LinkConfig hc = new LinkConfig("Closed Host");
            hc.controlPort = 0;
            try (Link host = Link.host(hc)) {
                Field udp = Link.class.getDeclaredField("udp");
                udp.setAccessible(true);
                ((java.nio.channels.DatagramChannel) udp.get(host)).close();
                host.poll();
                String err = host.lastError();
                check(err != null && err.contains("UDP"), "lastError after the UDP channel closed: " + err);
            }
            try (Link host = Link.host(hc)) {
                Field listener = Link.class.getDeclaredField("listener");
                listener.setAccessible(true);
                ((java.nio.channels.ServerSocketChannel) listener.get(host)).close();
                host.poll();
                String err = host.lastError();
                check(err != null && err.contains("listener"), "lastError after the listener closed: " + err);
            }
        });
    }
}
