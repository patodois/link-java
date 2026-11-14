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
