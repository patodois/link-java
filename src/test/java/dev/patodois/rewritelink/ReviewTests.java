package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;
import static dev.patodois.rewritelink.LinkTests.until;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.patodois.rewritelink.Wire.Datagram;
import dev.patodois.rewritelink.Wire.Header;
import dev.patodois.rewritelink.Wire.WireException;

/**
 * Regression tests for the findings of the code review of both implementations (contract v1,
 * revision 4). Each test names its finding and fails on the code the review was written against.
 */
final class ReviewTests {
    private ReviewTests() {}

    private static Path temp(String name) throws Exception {
        Path dir = Files.createTempDirectory("review-test");
        dir.toFile().deleteOnExit();
        return dir.resolve(name);
    }

    private static void put(RandomAccessFile f, long pos, byte[] b) throws Exception {
        f.seek(pos);
        f.write(b);
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] le64(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }

    /** A pixel file header (no slots written) of the given shape, as a sparse file of {@code length} bytes. */
    private static Path headerOnly(String name, int slots, int w, int h, int stride, long slotSize, long length) throws Exception {
        Path p = temp(name);
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(length);
            put(f, 0, new byte[] {'R', 'B', 'F', 'R', 'A', 'M', 'E', 0});
            put(f, 8, le32(1));
            put(f, 12, le32(slots));
            put(f, 16, le32(w));
            put(f, 20, le32(h));
            put(f, 24, le32(stride));
            put(f, 28, le32(1));
            put(f, 32, le64(slotSize));
        }
        return p;
    }

    private static boolean refused(Path p) {
        try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
            return r == null;
        } catch (PixelFile.FormatException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean unix() {
        return !System.getProperty("os.name").toLowerCase().contains("win");
    }

    static void run(String[] args) throws Exception {
        // ----- J2: slots * slot_size wrapped in 64 bits and passed the file-length check
        test("review M4 (J2): a slot_size that makes slots * slot_size wrap is refused", () -> {
            long s = java.math.BigInteger.ONE.shiftLeft(64).add(java.math.BigInteger.valueOf(128))
                    .divide(java.math.BigInteger.valueOf(3)).longValue();
            eq(3 * s, 128L, "the product wraps to 128, inside the 4096-byte file");
            eq(s % 64, 0L, "and it is a multiple of 64, so no other check refuses it");
            check(refused(headerOnly("wrap.frames", 3, 1, 1, 4, s, 4096)), "slot_size " + s + " must be refused");
        });

        // ----- M4 (Java half): the dimension limit is 16384, not 32768
        test("review M4: width or height above 16384, or more than 2^26 pixels, is refused; 16384 x 1 and 8192 x 8192 are not", () -> {
            int[][] cases = {
                {16384, 1, 1}, {1, 16384, 1}, {8192, 8192, 1}, {16384, 4096, 1},
                {16384, 8192, 0}, {8193, 8192, 0}, {16385, 1, 0}, {1, 16385, 0}, {32768, 1, 0}, {1, 32768, 0},
            };
            for (int[] c : cases) {
                int stride = c[0] * 4;
                long slot = (64L + (long) stride * c[1] + 63) & ~63L;
                Path p = headerOnly("dim.frames", 1, c[0], c[1], stride, slot, 64 + slot); // sparse
                check(refused(p) == (c[2] == 0), c[0] + "x" + c[1] + (c[2] == 0 ? " must be refused" : " must open"));
                Files.delete(p);
            }
        });

        test("review M4: the writer stops at 16384 too", () -> {
            boolean threw = false;
            try {
                new PixelFile.Writer(temp("big.frames"), 16385, 1, 1, false).close();
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "a writer of 16385 x 1 must be refused");
            for (int[] wh : new int[][] {{16384, 8192}, {8193, 8192}}) {
                threw = false;
                Path p = temp("big2.frames");
                try {
                    new PixelFile.Writer(p, wh[0], wh[1], 1, false).close();
                } catch (IllegalArgumentException e) {
                    threw = true;
                }
                check(threw, "a writer of " + wh[0] + " x " + wh[1] + " must be refused");
                check(!Files.exists(p), "and creates no file");
            }
        });

        test("pixel file: stride must be exactly width * 4", () -> {
            long slot = 192;
            Path p = headerOnly("stride.frames", 1, 4, 4, 20, slot, 64 + slot);
            check(refused(p), "stride 20 for width 4");
        });

        // ----- M3: a symlink is not a regular file
        test("review M3: a symlink to a pixel file is refused", () -> {
            if (!unix()) return;
            Path real = temp("real.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(real, 4, 4, 2, false)) {
                Path link = temp("link.frames");
                Files.createSymbolicLink(link, real);
                check(refused(link), "a symlink to " + w.path() + " must be refused");
                try (PixelFile.Reader r = PixelFile.Reader.open(real)) {
                    check(r != null, "the file itself opens");
                }
            }
        });

        // ----- Truncation under a mapped reader (Rv3), in a child JVM so a crash cannot take the runner down
        test("review M5: a pixel file truncated under a reader is gone, not a crash", () -> {
            // Windows refuses to shorten a file any process has mapped, so a reader there can never see it shrink
            if (System.getProperty("os.name", "").startsWith("Windows")) return;
            Path p = temp("trunc.frames");
            List<String> cmd = new ArrayList<>();
            cmd.add(System.getProperty("java.home") + "/bin/java");
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add("dev.patodois.rewritelink.ReviewTests");
            cmd.add("truncate");
            cmd.add(p.toString());
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            int code = proc.waitFor();
            check(code == 0 && out.indexOf("RESULT null") >= 0, "child exit " + code + ": " + out);
        });

        // ----- L5: equals and hashCode compare array contents
        test("review L5: Camera, Pose and Decoded are equal when their arrays are", () -> {
            Wire.Camera c = Wire.Camera.lookAt(new float[] {0, 0, 5}, new float[] {0, 0, 0}, 1f, 0.1f, 100f, 640, 480);
            Header h = new Header(1, 0, 7, 1, 5);
            byte[] b = Wire.encode(h, new Datagram.CameraMsg(c));
            Wire.Decoded d1 = Wire.decode(b, b.length), d2 = Wire.decode(b, b.length);
            check(d1.equals(d2) && d1.hashCode() == d2.hashCode(), "the same bytes decoded twice");
            Wire.Camera c1 = ((Datagram.CameraMsg) d1.body()).camera(), c2 = ((Datagram.CameraMsg) d2.body()).camera();
            check(c1.equals(c2) && c1.hashCode() == c2.hashCode(), "Camera");
            Wire.Pose p = Wire.Pose.identity(1, 5), q = Wire.Pose.identity(1, 5);
            check(p.equals(q) && p.hashCode() == q.hashCode(), "two identity poses");
            q.translation()[0] = 1;
            check(!p.equals(q), "a different translation is a different pose");
            Wire.CollisionChunk a = new Wire.CollisionChunk(1, 0, 1, new float[] {1, 2, 3}, 4f,
                    Collections.singletonList(new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}));
            Wire.CollisionChunk a2 = new Wire.CollisionChunk(1, 0, 1, new float[] {1, 2, 3}, 4f,
                    Collections.singletonList(new float[] {1, 2, 3, 4, 5, 6, 7, 8, 9}));
            check(a.equals(a2) && a.hashCode() == a2.hashCode(), "CollisionChunk");
        });

        // ----- L6: the encoder validates like the Rust one
        test("review L6: Wire.encode refuses a bad chunk index or count and frame 0", () -> {
            Header h = new Header(1, 0, 7, 1, 5);
            float[] c3 = new float[3];
            for (int[] ic : new int[][] {{1, 1}, {5, 2}, {0, 0}}) {
                Wire.CollisionChunk bad = new Wire.CollisionChunk(1, ic[0], ic[1], c3, 1f, new ArrayList<float[]>());
                boolean threw = false;
                try {
                    Wire.encode(h, new Datagram.CollisionMsg(bad));
                } catch (WireException e) {
                    threw = true;
                }
                check(threw, "chunk_index " + ic[0] + " of " + ic[1] + " must not encode");
            }
            boolean threw = false;
            try {
                Wire.encode(h, new Datagram.FrameMsg(new Wire.FrameReady(0, 0, 0, 1, 1)));
            } catch (WireException e) {
                threw = true;
            }
            check(threw, "frame 0 must not encode");
        });

        // ----- L4: the writer validates its arguments and never leaves the lock odd
        test("review L4: a bad write is refused and leaves the slot lock even and the frame number unused", () -> {
            Path p = temp("w.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(p, 4, 2, 3, true)) {
                byte[] ok = new byte[4 * 2 * 4];
                float[] okd = new float[8];
                Object[][] bad = {
                    {new byte[5], okd, 4, 2},          // short colour array
                    {ok, new float[3], 4, 2},          // short depth array
                    {new byte[5 * 2 * 4], new float[10], 5, 2}, // wider than the file
                    {new byte[4 * 3 * 4], new float[12], 4, 3}, // taller than the file
                    {new byte[0], new float[0], 0, 0},   // empty
                    {ok, okd, -1, 2},
                };
                for (Object[] a : bad) {
                    boolean threw = false;
                    try {
                        w.write((byte[]) a[0], (float[]) a[1], (Integer) a[2], (Integer) a[3], 0, 1L);
                    } catch (IllegalArgumentException e) {
                        threw = true;
                    } catch (RuntimeException e) {
                        threw = false;
                    }
                    check(threw, "IllegalArgumentException for " + a[2] + "x" + a[3] + " with " + ((byte[]) a[0]).length + " bytes");
                }
                ByteBuffer raw = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
                for (int slot = 0; slot < 3; slot++) {
                    eq(raw.getLong((int) (64 + slot * w.slotSize)) & 1, 0L, "slot " + slot + " lock is even");
                }
                eq(w.write(ok, okd, 4, 2, 0, 1L), 1L, "the refused writes used no frame number");
            }
        });

        // ----- L7: Json is as strict as serde_json
        test("review L7: the JSON reader accepts and rejects what serde_json does", () -> {
            for (Object[] row : JSON_CORPUS) {
                String doc = (String) row[0];
                boolean ok = (Boolean) row[1];
                boolean got;
                try {
                    Json.parse(doc);
                    got = true;
                } catch (Json.JsonException e) {
                    got = false;
                }
                check(got == ok, "\"" + doc.replace("\n", "\\n") + "\" should be " + (ok ? "accepted" : "rejected"));
            }
            for (int n : new int[] {1, 32, 33, 100, 127, 128, 200}) {
                StringBuilder arrays = new StringBuilder(), objects = new StringBuilder(), end = new StringBuilder(), oend = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    arrays.append('[');
                    end.append(']');
                    objects.append("{\"a\":");
                    oend.append('}');
                }
                for (String doc : new String[] {arrays.toString() + end, objects.toString() + "1" + oend}) {
                    boolean got;
                    try {
                        Json.parse(doc);
                        got = true;
                    } catch (Json.JsonException e) {
                        got = false;
                    }
                    check(got == (n <= 127), "nesting " + n + " is " + (n <= 127 ? "accepted" : "rejected") + " by serde_json");
                }
            }
        });

        // ----- M1 (Java): a new frame_channel restarts the frame numbers
        test("review M1: frames of a new pixel file are not stale", () -> {
            try (Pair p = new Pair()) {
                List<LinkEvent> he = new ArrayList<>();
                for (long f = 1; f <= 10; f++) p.guest.sendFrame(new Wire.FrameReady(f, (int) (f % 3), 0, 16, 8));
                check(until(2000, () -> {
                    he.addAll(p.host.poll());
                    p.guest.poll();
                    return count(he, LinkEvent.Frame.class) == 10;
                }), "10 frames of file 1");
                he.clear();
                p.announce(new Control.FrameChannel("/tmp/second.frames", 32, 16, 3));
                Thread.sleep(30);
                he.addAll(p.host.poll());
                for (long f = 1; f <= 5; f++) p.guest.sendFrame(new Wire.FrameReady(f, (int) (f % 3), 0, 32, 16));
                until(1000, () -> {
                    he.addAll(p.host.poll());
                    p.guest.poll();
                    return count(he, LinkEvent.Frame.class) >= 5;
                });
                eq(count(he, LinkEvent.Frame.class), 5, "frames 1..5 of the new channel; stale counter " + p.host.stale);
            }
        });

        // ----- M2 (Java): a big snapshot, in one call, arrives
        test("review M2: a 400-chunk collision snapshot completes", () -> {
            try (Pair p = new Pair()) {
                List<float[]> tris = new ArrayList<>();
                for (int i = 0; i < 400 * Wire.MAX_TRIANGLES; i++) tris.add(new float[] {i, 0, 0, i + 1, 0, 0, i, 0, -1});
                int q = p.host.sendCollision(new float[3], 16f, tris);
                List<LinkEvent> ge = new ArrayList<>();
                boolean done = until(15000, () -> {
                    p.host.poll();
                    ge.addAll(p.guest.poll());
                    return count(ge, LinkEvent.Collision.class) > 0;
                });
                check(done, "the snapshot never completed");
                LinkEvent.Collision c = null;
                for (LinkEvent e : ge) if (e instanceof LinkEvent.Collision) c = (LinkEvent.Collision) e;
                eq(c.query(), q, "query");
                eq(c.triangles().size(), tris.size(), "triangles");
            }
        });

        // ----- M6 (Java): only spawned entities have pose state
        test("review M6: poses of unspawned entities are ignored; at most 1024 live entities", () -> {
            try (Pair p = new Pair()) {
                List<LinkEvent> he = new ArrayList<>();
                p.guest.spawn(new Control.Spawn(5, "P", "a", null));
                long t = Wire.nowUs();
                p.guest.sendPoses(Arrays.asList(Wire.Pose.identity(5, t), Wire.Pose.identity(6, t)));
                until(300, () -> {
                    he.addAll(p.host.poll());
                    p.guest.poll();
                    return false;
                });
                eq(posesFor(he, 5), 1, "the spawned entity");
                eq(posesFor(he, 6), 0, "never spawned");
                he.clear();
                for (int e = 0; e < 1030; e++) {
                    if (e == 5) continue;
                    p.guest.spawn(new Control.Spawn(e, "P", "a", null));
                }
                until(500, () -> {
                    he.addAll(p.host.poll());
                    p.guest.poll();
                    return false;
                });
                eq(count(he, LinkEvent.Spawn.class), 1023, "spawns up to the table size (entity 5 was already live)");
                p.guest.sendPoses(Arrays.asList(Wire.Pose.identity(0, t + 5), Wire.Pose.identity(1029, t + 5)));
                until(300, () -> {
                    he.addAll(p.host.poll());
                    p.guest.poll();
                    return false;
                });
                eq(posesFor(he, 0), 1, "a live entity");
                eq(posesFor(he, 1029), 0, "its spawn was ignored");
            }
        });

        test("review M2: collision chunks are paced per poll and a newer snapshot replaces queued ones", () -> {
            try (Pair p = new Pair()) {
                List<float[]> big = new ArrayList<>();
                for (int i = 0; i < 400 * Wire.MAX_TRIANGLES; i++) big.add(new float[] {i, 0, 0, i + 1, 0, 0, i, 0, -1});
                Thread.sleep(100);
                p.host.poll();
                long before = p.host.datagramsSent;
                p.host.sendCollision(new float[3], 16f, big);
                p.host.poll();
                long sent = p.host.datagramsSent - before;
                check(sent >= 1 && sent <= 33, "one sendCollision and one poll put " + sent + " datagrams on the wire");

                // A second snapshot while the first still waits: the first's chunks are dropped.
                long before2 = p.host.datagramsSent;
                int q2 = p.host.sendCollision(new float[] {1, 1, 1}, 8f, big.subList(0, 2 * Wire.MAX_TRIANGLES));
                List<LinkEvent> ge = new ArrayList<>();
                check(until(5000, () -> {
                    p.host.poll();
                    ge.addAll(p.guest.poll());
                    return count(ge, LinkEvent.Collision.class) > 0;
                }), "the replacing snapshot arrives");
                until(200, () -> {
                    p.host.poll();
                    ge.addAll(p.guest.poll());
                    return false;
                });
                long sent2 = p.host.datagramsSent - before2;
                check(sent2 < 60, "the replaced snapshot's chunks were sent anyway: " + sent2);
                int n = 0;
                for (LinkEvent e : ge) {
                    if (e instanceof LinkEvent.Collision) {
                        n++;
                        eq(((LinkEvent.Collision) e).query(), q2, "only the replacing query completes");
                    }
                }
                eq(n, 1, "one snapshot");
            }
        });

        test("review M2: sendCollision without a peer is an error", () -> {
            LinkConfig gc = new LinkConfig("lonely");
            gc.controlPort = 1;
            try (Link g = Link.guest(gc)) {
                boolean threw = false;
                try {
                    g.sendCollision(new float[3], 1f, new ArrayList<float[]>());
                } catch (WireException e) {
                    threw = true;
                }
                check(threw, "not connected");
            }
        });

        test("review M6: the unknown_entity and spawn_ignored counters", () -> {
            try (Pair p = new Pair()) {
                p.guest.spawn(new Control.Spawn(5, "P", "a", null));
                long t = Wire.nowUs();
                p.guest.sendPoses(Arrays.asList(Wire.Pose.identity(5, t), Wire.Pose.identity(6, t), Wire.Pose.identity(7, t)));
                for (int e = 100; e < 100 + 1030; e++) p.guest.spawn(new Control.Spawn(e, "P", "a", null));
                until(600, () -> {
                    p.host.poll();
                    p.guest.poll();
                    return false;
                });
                eq(p.host.unknownEntity, 2L, "poses of 6 and 7");
                eq(p.host.spawnIgnored, 7L, "1 + 1030 spawns for 1024 places");
            }
        });

        // ----- L8
        test("review L8: only 127.0.0.1 and the peer's port is the peer", () -> {
            InetSocketAddress peer = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 5000);
            check(Link.isPeerAddress(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 5000), peer), "the peer");
            check(!Link.isPeerAddress(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 5001), peer), "another port");
            check(!Link.isPeerAddress(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 2}), 5000), peer), "another loopback address");
            check(!Link.isPeerAddress(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 1, 2, 3}), 5000), peer), "127.1.2.3");
            check(!Link.isPeerAddress(new InetSocketAddress(InetAddress.getByName("::1"), 5000), peer), "::1");
            check(!Link.isPeerAddress(new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, 1}), 5000), peer), "10.0.0.1");
        });

        // ----- L3
        test("review L3: closing a writer unmaps its file before deleting it, and a closed writer refuses writes", () -> {
            Path p = temp("close.frames");
            PixelFile.Writer w = new PixelFile.Writer(p, 4, 2, 2, false);
            w.write(new byte[32], null, 4, 2, 0, 1L);
            check(!w.wasUnmapped(), "mapped while open");
            w.close();
            check(w.wasUnmapped(), "the mapping was released on this JVM (java.version " + System.getProperty("java.version") + ")");
            check(!Files.exists(p), "deleted");
            boolean threw = false;
            try {
                w.write(new byte[32], null, 4, 2, 0, 1L);
            } catch (IllegalStateException e) {
                threw = true;
            }
            check(threw, "a write after close must not touch the unmapped buffer");
            w.close(); // twice is fine
            check(!PixelFile.unmap(ByteBuffer.allocate(8)), "a heap buffer cannot be unmapped");
        });

        // ----- announceFrames
        test("Link.announceFrames sends a frame_channel line", () -> {
            try (Pair p = new Pair()) {
                Path file = temp("announce.frames");
                try (PixelFile.Writer w = new PixelFile.Writer(file, 32, 16, 3, true)) {
                    p.guest.announceFrames(w);
                    List<LinkEvent> he = new ArrayList<>();
                    check(until(2000, () -> {
                        he.addAll(p.host.poll());
                        p.guest.poll();
                        return count(he, LinkEvent.FrameChannel.class) == 1;
                    }), "the host sees the announcement");
                    Control.FrameChannel c = null;
                    for (LinkEvent e : he) if (e instanceof LinkEvent.FrameChannel) c = ((LinkEvent.FrameChannel) e).channel();
                    eq(c, new Control.FrameChannel(file.toAbsolutePath().toString(), 32, 16, 3), "the channel");
                    // And what it names opens and reads.
                    long f = w.write(new byte[32 * 16 * 4], new float[32 * 16], 32, 16, 7, 99L);
                    try (PixelFile.Reader r = PixelFile.Reader.open(java.nio.file.Paths.get(c.path()))) {
                        check(r.read(f, (int) (f % 3), true) != null, "the frame reads");
                    }
                }
            }
        });

        test("review L9: our own heartbeat is kept within what a hello may announce", () -> {
            LinkConfig hc = new LinkConfig("H");
            hc.controlPort = 0;
            LinkConfig gc = new LinkConfig("G");
            gc.heartbeatMs = 1; // outside 10..10000
            try (Link host = Link.host(hc)) {
                gc.controlPort = host.controlPort();
                try (Link guest = Link.guest(gc)) {
                    check(until(3000, () -> {
                        host.poll();
                        guest.poll();
                        return host.isConnected() && guest.isConnected();
                    }), "a guest configured with heartbeat 1 ms still connects");
                    eq(host.peer().heartbeatMs(), 10L, "it announced the nearest legal value");
                }
            }
        });

        // ----- L9 (Java)
        test("review L9: heartbeat_ms outside 10..10000 is a handshake error", () -> {
            for (long ms : new long[] {0, 1, 9, 10001, 3600000L, 4294967295L}) {
                String reason = helloWithHeartbeat(ms);
                check(reason != null && reason.contains("heartbeat_ms"), "heartbeat_ms " + ms + ": " + reason);
            }
            for (long ms : new long[] {10, 250, 10000}) {
                eq(helloWithHeartbeat(ms), null, "heartbeat_ms " + ms + " is fine");
            }
        });
    }

    /** Says hello as a raw guest with the given heartbeat; returns the host's HandshakeFailed reason, or null if it connected. */
    private static String helloWithHeartbeat(long ms) throws Exception {
        LinkConfig hc = new LinkConfig("H");
        hc.controlPort = 0;
        try (Link host = Link.host(hc);
             Socket s = new Socket(InetAddress.getByName("127.0.0.1"), host.controlPort())) {
            OutputStream out = s.getOutputStream();
            out.write(("{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"raw\",\"protocol\":1,\"min_protocol\":1,\"udp_port\":9,"
                    + "\"heartbeat_ms\":" + ms + ",\"timeout_ms\":2000}\n").getBytes(StandardCharsets.UTF_8));
            List<LinkEvent> ev = new ArrayList<>();
            until(2000, () -> {
                ev.addAll(host.poll());
                return !ev.isEmpty();
            });
            check(ev.size() == 1, "one event: " + ev);
            if (ev.get(0) instanceof LinkEvent.HandshakeFailed) return ((LinkEvent.HandshakeFailed) ev.get(0)).reason();
            check(ev.get(0) instanceof LinkEvent.Connected, "connected: " + ev);
            return null;
        }
    }

    static int count(List<LinkEvent> events, Class<? extends LinkEvent> type) {
        int n = 0;
        for (LinkEvent e : events) if (type.isInstance(e)) n++;
        return n;
    }

    static int posesFor(List<LinkEvent> events, long entity) {
        int n = 0;
        for (LinkEvent e : events) {
            if (e instanceof LinkEvent.Poses) {
                for (Wire.Pose p : ((LinkEvent.Poses) e).poses()) if (p.entity() == entity) n++;
            }
        }
        return n;
    }

    /** A connected host and guest in one process. */
    static final class Pair implements AutoCloseable {
        final Link host, guest;

        Pair() throws Exception {
            LinkConfig hc = new LinkConfig("Review Host");
            hc.controlPort = 0;
            hc.timeoutMs = 4000;
            host = Link.host(hc);
            LinkConfig gc = new LinkConfig("Review Guest");
            gc.controlPort = host.controlPort();
            gc.timeoutMs = 4000;
            guest = Link.guest(gc);
            check(until(3000, () -> {
                host.poll();
                guest.poll();
                return host.isConnected() && guest.isConnected();
            }), "both sides connect");
        }

        void announce(Control.FrameChannel c) throws Exception {
            guest.announceFrames(c);
        }

        @Override
        public void close() {
            guest.close();
            host.close();
        }
    }

    // ------------------------------------------------------------------ the shared JSON table

    /** Documents and whether serde_json (the Rust side) accepts them; the same table is in rewrite-bridge's tests/link_review.rs. */
    static final Object[][] JSON_CORPUS = {
        // numbers
        {"0", true}, {"-0", true}, {"-1", true}, {"1.5", true}, {"1e5", true}, {"1E+5", true}, {"1e-5", true},
        {"0e0", true}, {"-0.0", true}, {"1e-999", true}, {"123456789012345678901234567890", true},
        {"01", false}, {"-01", false}, {"00", false}, {"1.", false}, {"1.e5", false}, {"-.5", false}, {".5", false},
        {"1e", false}, {"1.0e+", false}, {"+1", false}, {"-", false}, {"- 1", false}, {"0x10", false},
        {"1e999", false}, {"Infinity", false}, {"NaN", false},
        // string escapes
        {"\"\\u0041\"", true}, {"\"\\u00E9\"", true}, {"\"\\u0000\"", true}, {"\"\\/\"", true},
        {"\"\\ud800\\udc00\"", true}, {"\"\\ud83d\\ude00\"", true}, {"\"\\uD834\\uDD1E\"", true},
        {"\"\\u004\"", false}, {"\"\\u00zz\"", false}, {"\"\\u+041\"", false}, {"\"\\u-041\"", false},
        {"\"\\u D800\"", false}, {"\"\\ud800\"", false}, {"\"\\udc00\"", false}, {"\"\\ud800x\"", false},
        {"\"\\ud800\\u0041\"", false}, {"\"\\ud800\\ud800\"", false}, {"\"\\udc00\\ud800\"", false},
        {"\"\\x41\"", false}, {"\"\\U0041\"", false}, {"\"a\nb\"", false},
        // structure
        {"true", true}, {"[true,false,null]", true}, {"\t1\n", true}, {"truex", false}, {"tru", false},
        {"[1,]", false}, {"[,1]", false}, {"{\"a\":1,}", false}, {"{\"a\":1 \"b\":2}", false},
        {"{'a':1}", false}, {"\uFEFF1", false},
    };

    // ------------------------------------------------------------------ child process for the truncation test

    /** {@code truncate <path>}: the dangerous half of the F5 test, run in a JVM of its own. */
    static void childTruncate(String path) throws Exception {
        Path p = java.nio.file.Paths.get(path).toAbsolutePath();
        Files.deleteIfExists(p);
        PixelFile.Writer w = new PixelFile.Writer(p, 64, 64, 3, true);
        long f = w.write(new byte[64 * 64 * 4], new float[64 * 64], 64, 64, 0, 1);
        PixelFile.Reader r = PixelFile.Reader.open(p);
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(64); // the peer truncates the file after the reader mapped it
        }
        Object result;
        try {
            result = r.read(f, (int) (f % 3), true);
        } catch (Throwable t) {
            result = "threw " + t;
        }
        System.out.println("RESULT " + result);
        System.out.flush();
        Files.deleteIfExists(p);
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("truncate")) {
            childTruncate(args[1]);
            return;
        }
        System.err.println("usage: ReviewTests truncate <path>");
        System.exit(2);
    }
}
