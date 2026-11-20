package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;
import static dev.patodois.rewritelink.LinkTests.until;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Contract revision 8 (section 12 of CONTRACT.md lists its nine rules): tests of what this library does for each
 * rule, beyond what the shared vectors can see from outside.
 */
final class RevisionEightTests {
    private RevisionEightTests() {}

    static final InetAddress LO = loopback();

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    static void run() {
        texts();
        poseOrder();
        candidates();
        bounds();
        connectedUdp();
        notRunning();
        files();
        numbers();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    static LinkConfig hostConfig() {
        LinkConfig c = new LinkConfig("rev8 host");
        c.controlPort = 0;
        return c;
    }

    /** A guest hello for a raw peer, with {@code extra} spliced in before the closing brace. */
    static String guestHello(String extra) {
        return "{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"raw\",\"protocol\":1,\"min_protocol\":1,\"udp_port\":4000,"
                + "\"heartbeat_ms\":250,\"timeout_ms\":2000" + extra + "}\n";
    }

    /** A raw control connection to {@code link}, read while the link is polled. */
    static final class Raw implements AutoCloseable {
        final VectorTests.Raw raw;

        Raw(Socket s) throws IOException {
            raw = new VectorTests.Raw(s);
        }

        static Raw to(Link host) throws IOException {
            return new Raw(new Socket(LO, host.controlPort()));
        }

        void send(String text) throws IOException {
            raw.out.write(text.getBytes(StandardCharsets.UTF_8));
            raw.out.flush();
        }

        /** The next line from the link (without its newline), polling {@code link} into {@code events}; null if none. */
        String line(Link link, List<LinkEvent> events, long ms) throws InterruptedException {
            byte[][] got = {null};
            until(ms, () -> {
                events.addAll(link.poll());
                raw.fill();
                got[0] = raw.takeLine();
                return got[0] != null || raw.eof;
            });
            return got[0] == null ? null : new String(got[0], 0, got[0].length - 1, StandardCharsets.UTF_8);
        }

        /** Whether the link closed this connection within {@code ms}, polling it; lines read meanwhile are dropped. */
        boolean closed(Link link, List<LinkEvent> events, long ms) throws InterruptedException {
            return until(ms, () -> {
                events.addAll(link.poll());
                raw.fill();
                while (raw.takeLine() != null) {
                    // dropped
                }
                return raw.eof;
            });
        }

        @Override
        public void close() {
            raw.close();
        }
    }

    /**
     * A raw guest of {@code host}: a control connection whose hello announces a UDP socket of its own, and datagrams sent
     * from that socket with the session of the host's hello.
     */
    static final class RawGuest implements AutoCloseable {
        final Link host;
        final List<LinkEvent> events = new ArrayList<>();
        final java.net.DatagramSocket udp;
        final Raw ctl;
        final long session;
        int seq;

        RawGuest(Link host) throws Exception {
            this.host = host;
            udp = new java.net.DatagramSocket(0, LO);
            ctl = Raw.to(host);
            ctl.send(guestHello("").replace("\"udp_port\":4000", "\"udp_port\":" + udp.getLocalPort()));
            String hello = ctl.line(host, events, 3000);
            check(hello != null && hello.contains("\"hello\""), "the host's hello, got " + hello);
            session = (Long) VectorTests.obj(Json.parse(hello)).get("session");
            check(await(host, events, LinkEvent.Connected.class, 3000) != null, "connected");
            events.clear();
        }

        void line(String text) throws Exception {
            ctl.send(text);
        }

        void datagram(Wire.Datagram body) throws Exception {
            byte[] b = Wire.encode(new Wire.Header(1, 0, (int) session, seq++, Wire.nowUs()), body);
            udp.send(new java.net.DatagramPacket(b, b.length, LO, host.udpPort()));
        }

        @Override
        public void close() {
            ctl.close();
            udp.close();
        }
    }

    /** Polls {@code link} until an event of {@code type} arrives (collecting all events), and returns it, or null. */
    static <T extends LinkEvent> T await(Link link, List<LinkEvent> events, Class<T> type, long ms) throws InterruptedException {
        Object[] got = {null};
        until(ms, () -> {
            events.addAll(link.poll());
            for (LinkEvent e : events) {
                if (type.isInstance(e)) {
                    got[0] = e;
                    return true;
                }
            }
            return false;
        });
        return type.cast(got[0]);
    }

    // ---------------------------------------------------------------------------------------------
    // Error texts quote nothing from the line, text from the peer is cleaned (sections 6.2 and 8, bye)

    static void texts() {
        test("revision 8: a host's refusal of a first line quotes nothing from it, in the bye and in the event", () -> {
            // {line, marker that must not come back}
            String[][] cases = {
                {guestHello("").replace("\"heartbeat_ms\":250", "\"heartbeat_ms\":98765"), "98765"},
                {guestHello("").replace("\"protocol\":1,\"min_protocol\":1", "\"protocol\":4321,\"min_protocol\":4321"), "4321"},
                {guestHello("").replace("\"udp_port\":4000", "\"udp_port\":0"), "port 0"},
                {guestHello(",\"token\":@Zq7x"), "Zq7x"},
                {guestHello(",\"Zq7xKEY\" 1"), "Zq7x"},
                {"{\"type\":\"spawn\",\"entity\":1,\"provider\":\"Zq7x\",\"asset\":\"a\"}\n", "Zq7x"},
                {"{\"type\":\"hello\",\"role\":\"Zq7x\"}\n", "Zq7x"},
            };
            try (Link host = Link.host(hostConfig())) {
                for (String[] c : cases) {
                    List<LinkEvent> events = new ArrayList<>();
                    try (Raw raw = Raw.to(host)) {
                        raw.send(c[0]);
                        String bye = raw.line(host, events, 3000);
                        check(bye != null && bye.startsWith("{\"type\":\"bye\""), "a bye answers " + c[0].trim() + ", got " + bye);
                        check(!bye.contains(c[1]), "the bye quotes " + c[1] + ": " + bye);
                        LinkEvent.HandshakeFailed f = await(host, events, LinkEvent.HandshakeFailed.class, 3000);
                        check(f != null, "a HandshakeFailed event for " + c[0].trim());
                        check(!f.reason().contains(c[1]), "the event quotes " + c[1] + ": " + f.reason());
                    }
                }
            }
        });

        test("revision 8: a guest's refusal of the host's hello quotes nothing from it", () -> {
            // {protocol, heartbeat_ms, marker}
            String[][] cases = {{"4321", "250", "4321"}, {"1", "98765", "98765"}};
            for (String[] c : cases) {
                try (ServerSocket server = new ServerSocket(0, 5, LO)) {
                    server.setSoTimeout(1);
                    LinkConfig gc = new LinkConfig("rev8 guest");
                    gc.controlPort = server.getLocalPort();
                    try (Link guest = Link.guest(gc)) {
                        List<LinkEvent> events = new ArrayList<>();
                        Socket[] s = {null};
                        check(until(3000, () -> {
                            events.addAll(guest.poll());
                            try {
                                s[0] = server.accept();
                            } catch (SocketTimeoutException none) {
                                return false;
                            } catch (IOException e) {
                                throw new AssertionError(e);
                            }
                            return true;
                        }), "the guest connects");
                        try (Raw raw = new Raw(s[0])) {
                            check(raw.line(guest, events, 3000) != null, "the guest's hello");
                            raw.send("{\"type\":\"hello\",\"role\":\"host\",\"game\":\"raw\",\"protocol\":" + c[0] + ",\"min_protocol\":" + c[0]
                                    + ",\"udp_port\":4000,\"heartbeat_ms\":" + c[1] + ",\"timeout_ms\":2000,\"session\":7}\n");
                            String bye = raw.line(guest, events, 3000);
                            check(bye != null && bye.startsWith("{\"type\":\"bye\""), "the guest answers with a bye, got " + bye);
                            check(!bye.contains(c[2]), "the bye quotes " + c[2] + ": " + bye);
                            LinkEvent.HandshakeFailed f = await(guest, events, LinkEvent.HandshakeFailed.class, 3000);
                            check(f != null && !f.reason().contains(c[2]), "the event quotes nothing: " + f);
                        }
                    }
                }
            }
        });

        test("revision 8: text for people from the peer has every byte below 0x20 and 0x7F replaced by a space", () -> {
            eq(Link.forPeople("a\u001b[2Jb\nforged\r\u0007c\u007f\u0000d"), "a [2Jb forged  c  d", "cleaned");
            eq(Link.forPeople("é☃😀 ok\u0080"), "é☃😀 ok\u0080", "other characters stay");
            try (Link host = Link.host(hostConfig())) {
                List<LinkEvent> events = new ArrayList<>();
                try (Raw raw = Raw.to(host)) {
                    raw.send(guestHello("").replace("\"game\":\"raw\"", "\"game\":\"g\\u001b]0;x\\u0007\\n\\u007f\""));
                    LinkEvent.Connected c = await(host, events, LinkEvent.Connected.class, 3000);
                    check(c != null, "connected");
                    eq(c.peer().game(), "g ]0;x   ", "the game as the application gets it");
                    eq(host.peer().game(), "g ]0;x   ", "Link.peer() gives the same");
                    raw.send("{\"type\":\"spawn\",\"entity\":5,\"provider\":\"P\\u0001\",\"asset\":\"a\\n\",\"name\":\"n\\u001b[0m\\r\\nx\"}\n");
                    LinkEvent.Spawn sp = await(host, events, LinkEvent.Spawn.class, 3000);
                    check(sp != null, "spawn");
                    eq(sp.spawn().name(), "n [0m  x", "the name as the application gets it");
                    eq(sp.spawn().provider(), "P\u0001", "an identifier is handed on as sent");
                    eq(sp.spawn().asset(), "a\n", "an identifier is handed on as sent");
                    raw.send("{\"type\":\"bye\",\"reason\":\"a\\u001b[2Jb\\nforged\\r\\u0007c\\u007f\"}\n");
                    LinkEvent.Lost lost = await(host, events, LinkEvent.Lost.class, 3000);
                    check(lost != null, "lost");
                    eq(lost.reason(), "peer said bye: a [2Jb forged  c ", "the peer's reason as the application gets it");
                }
                // A bye as the first line of a connection: the HandshakeFailed reason is cleaned too.
                events.clear();
                try (Raw raw = Raw.to(host)) {
                    raw.send("{\"type\":\"bye\",\"reason\":\"x\\u001by\"}\n");
                    LinkEvent.HandshakeFailed f = await(host, events, LinkEvent.HandshakeFailed.class, 3000);
                    check(f != null, "handshake failed");
                    eq(f.reason(), "peer said bye: x y", "the first line's reason, cleaned");
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Pose order (sections 5, 7, 8 spawn, 9.4)

    static Wire.Pose pose(long entity, int flags, long timeUs) {
        return new Wire.Pose(entity, flags, timeUs, new float[3], new float[] {0, 0, 0, 1}, new float[] {1, 1, 1}, new float[3], new float[3]);
    }

    /** The time_us of every pose the link delivered, in order, after polling it for a while. */
    static List<Long> deliveredTimes(Link link, long ms) throws InterruptedException {
        List<Long> out = new ArrayList<>();
        until(ms, () -> {
            for (LinkEvent e : link.poll()) {
                if (e instanceof LinkEvent.Poses) for (Wire.Pose p : ((LinkEvent.Poses) e).poses()) out.add(p.timeUs());
            }
            return false;
        });
        return out;
    }

    static void poseOrder() {
        test("revision 8: a sender raises a pose time that goes back to the last one plus 1 us, and counts it", () -> {
            try (ReviewTests.Pair p = new ReviewTests.Pair()) {
                p.guest.spawn(new Control.Spawn(9, "P", "a", null));
                deliveredTimes(p.host, 100);
                p.guest.sendPoses(java.util.Arrays.asList(pose(9, 0, 1000), pose(9, 0, 500)));
                p.guest.sendPoses(java.util.Collections.singletonList(pose(9, 0, 700)));
                eq(deliveredTimes(p.host, 300), java.util.Arrays.asList(1000L, 1001L, 1002L), "times the host received");
                eq(p.guest.poseTimeRaised, 2L, "counted");
                // TELEPORT starts the order again at its own time, and later poses are ordered after it.
                p.guest.sendPoses(java.util.Collections.singletonList(pose(9, Wire.POSE_TELEPORT, 200)));
                p.guest.sendPoses(java.util.Collections.singletonList(pose(9, 0, 100)));
                eq(deliveredTimes(p.host, 300), java.util.Arrays.asList(200L, 201L), "after a teleport");
                // A spawn of the live entity starts it again too.
                p.guest.spawn(new Control.Spawn(9, "P", "a", null));
                deliveredTimes(p.host, 100);
                p.guest.sendPoses(java.util.Collections.singletonList(pose(9, 0, 50)));
                eq(deliveredTimes(p.host, 300), java.util.Collections.singletonList(50L), "after a respawn");
                eq(p.guest.poseTimeRaised, 3L, "counted");
                // The largest u64 stays itself.
                p.guest.sendPoses(java.util.Arrays.asList(pose(9, 0, -1L), pose(9, 0, 5)));
                eq(deliveredTimes(p.host, 300), java.util.Arrays.asList(-1L, -1L), "the largest time is not wrapped");
                // Another entity has an order of its own.
                p.guest.spawn(new Control.Spawn(10, "P", "a", null));
                deliveredTimes(p.host, 100);
                p.guest.sendPoses(java.util.Collections.singletonList(pose(10, 0, 3)));
                eq(deliveredTimes(p.host, 300), java.util.Collections.singletonList(3L), "entity 10");
            }
        });

        test("revision 8: a receiver uses a TELEPORT pose whatever its time, and a respawn forgets the last time", () -> {
            try (Link host = Link.host(hostConfig()); RawGuest g = new RawGuest(host)) {
                g.line("{\"type\":\"spawn\",\"entity\":5,\"provider\":\"P\",\"asset\":\"a\"}\n");
                deliveredTimes(host, 100);
                long stale0 = host.stale;
                // One datagram per pose, so that each is a step of its own.
                long[][] sent = {{1000, 0}, {999, Wire.POSE_TELEPORT}, {999, 0}, {998, 0}, {1001, 0}};
                for (long[] s : sent) g.datagram(new Wire.Datagram.PoseMsg(java.util.Collections.singletonList(pose(5, (int) s[1], s[0]))));
                eq(deliveredTimes(host, 300), java.util.Arrays.asList(1000L, 999L, 999L, 1001L), "used");
                eq(host.stale - stale0, 1L, "the plain older pose is dropped");
                // Records of one datagram are taken in record order.
                g.datagram(new Wire.Datagram.PoseMsg(java.util.Arrays.asList(pose(5, 0, 900), pose(5, Wire.POSE_TELEPORT, 800), pose(5, 0, 850))));
                eq(deliveredTimes(host, 300), java.util.Arrays.asList(800L, 850L), "in record order");
                g.line("{\"type\":\"spawn\",\"entity\":5,\"provider\":\"P\",\"asset\":\"a\"}\n");
                deliveredTimes(host, 100);
                g.datagram(new Wire.Datagram.PoseMsg(java.util.Collections.singletonList(pose(5, 0, 10))));
                eq(deliveredTimes(host, 300), java.util.Collections.singletonList(10L), "after a respawn");
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Candidates (section 2)

    static void candidates() {
        test("revision 8: a silent connection does not keep the guest with the token out", () -> {
            LinkConfig hc = hostConfig();
            hc.token = Tokens.generate();
            try (Link host = Link.host(hc); Raw silent = Raw.to(host)) {
                List<LinkEvent> events = new ArrayList<>();
                check(silent.line(host, events, 100) == null, "nothing for the silent connection yet");
                LinkConfig gc = new LinkConfig("rev8 guest");
                gc.controlPort = host.controlPort();
                gc.token = hc.token;
                try (Link guest = Link.guest(gc)) {
                    check(until(500, () -> {
                        events.addAll(host.poll());
                        guest.poll();
                        return host.isConnected() && guest.isConnected();
                    }), "the guest is connected within 500 ms");
                    eq(silent.line(host, events, 1000), "{\"type\":\"bye\",\"reason\":\"busy\"}", "the silent one is then told busy");
                    check(silent.closed(host, events, 1000), "and closed");
                }
            }
        });

        test("revision 8: a host holds four candidates; newer connections displace the oldest, not with busy", () -> {
            try (Link host = Link.host(hostConfig())) {
                List<LinkEvent> events = new ArrayList<>();
                List<Raw> raws = new ArrayList<>();
                try {
                    for (int i = 0; i < 6; i++) {
                        raws.add(Raw.to(host));
                        until(30, () -> {
                            events.addAll(host.poll());
                            return false;
                        });
                    }
                    for (int i = 0; i < 2; i++) {
                        String bye = raws.get(i).line(host, events, 1000);
                        check(bye != null && bye.startsWith("{\"type\":\"bye\"") && !bye.contains("busy"), "connection " + i + " is displaced: " + bye);
                    }
                    for (int i = 2; i < 6; i++) check(raws.get(i).line(host, events, 50) == null, "connection " + i + " is still held");
                    raws.get(5).send(guestHello(""));
                    check(raws.get(5).line(host, events, 1000).contains("\"hello\""), "the newest becomes the session");
                    for (int i = 2; i < 5; i++) eq(raws.get(i).line(host, events, 1000), "{\"type\":\"bye\",\"reason\":\"busy\"}", "connection " + i);
                    for (LinkEvent e : events) check(!(e instanceof LinkEvent.HandshakeFailed), "no event for a displaced or busy candidate: " + e);
                } finally {
                    for (Raw r : raws) r.close();
                }
            }
        });

        test("revision 8: a refused candidate leaves the others, and a candidate has the host's timeout", () -> {
            LinkConfig hc = hostConfig();
            hc.timeoutMs = 600;
            try (Link host = Link.host(hc); Raw a = Raw.to(host); Raw b = Raw.to(host)) {
                List<LinkEvent> events = new ArrayList<>();
                check(a.line(host, events, 100) == null, "a is held");
                b.send("{\"type\":\"spawn\",\"entity\":1,\"provider\":\"P\",\"asset\":\"a\"}\n");
                String bye = b.line(host, events, 1000);
                check(bye != null && bye.contains("bye") && !bye.contains("busy"), "b is refused: " + bye);
                check(await(host, events, LinkEvent.HandshakeFailed.class, 1000) != null, "and reported");
                events.clear();
                long t0 = System.nanoTime();
                bye = a.line(host, events, 2000);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                check(bye != null && bye.contains("no hello before the timeout"), "a times out: " + bye);
                check(ms >= 300 && ms < 1500, "after the host's timeout of 600 ms, took " + ms);
                check(await(host, events, LinkEvent.HandshakeFailed.class, 1000) != null, "reported");
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Bounds per poll (sections 2 and 6.1)

    static void bounds() {
        test("revision 8: a host accepts at most 8 connections per poll and holds at most 16 closing sockets", () -> {
            try (ReviewTests.Pair p = new ReviewTests.Pair()) {
                List<Raw> raws = new ArrayList<>();
                try {
                    for (int i = 0; i < 40; i++) raws.add(Raw.to(p.host)); // in the backlog: the host is not polled meanwhile
                    Thread.sleep(100);
                    p.host.poll();
                    int answered = 0;
                    Thread.sleep(100);
                    for (Raw r : raws) {
                        r.raw.fill();
                        if (r.raw.takeLine() != null) answered++;
                    }
                    eq(answered, Link.MAX_ACCEPTS_PER_POLL, "connections answered busy by one poll");
                    check(p.host.closingCount() <= Link.MAX_CLOSING, "closing sockets after one poll: " + p.host.closingCount());
                    int most = 0;
                    for (int i = 0; i < 10; i++) {
                        p.host.poll();
                        most = Math.max(most, p.host.closingCount());
                    }
                    eq(most, Link.MAX_CLOSING, "the closing sockets stay at the bound (none of the 40 reads)");
                    // Every connection is answered or closed, and the session goes on.
                    for (Raw r : raws) check(r.closed(p.host, new ArrayList<>(), 3000), "a connection of the flood ends");
                    check(p.host.isConnected(), "the session goes on");
                } finally {
                    for (Raw r : raws) r.close();
                }
            }
        });

        test("revision 8: a poll reads at most 64 datagrams with no session, and at most 1024 with one", () -> {
            try (Link host = Link.host(hostConfig()); java.net.DatagramSocket other = new java.net.DatagramSocket(0, LO)) {
                for (int i = 0; i < 100; i++) other.send(new java.net.DatagramPacket(new byte[1], 1, LO, host.udpPort()));
                Thread.sleep(100);
                long before = host.foreign;
                host.poll();
                long read = host.foreign - before;
                check(read >= 1 && read <= Link.MAX_DATAGRAMS_WITHOUT_SESSION, "read without a session: " + read);
                try (RawGuest g = new RawGuest(host)) {
                    for (int i = 0; i < 1500; i++) g.datagram(new Wire.Datagram.Heartbeat());
                    Thread.sleep(200);
                    long r0 = host.datagramsReceived;
                    host.poll();
                    long first = host.datagramsReceived - r0;
                    check(first >= 1 && first <= Link.MAX_DATAGRAMS_PER_POLL, "read in one poll of a session: " + first);
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The connected UDP socket (section 2)

    static int freeUdpPort() throws IOException {
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket(0, LO)) {
            return probe.getLocalPort();
        }
    }

    static void connectBoth(Link host, Link guest, int session) throws InterruptedException {
        check(until(3000, () -> {
            host.poll();
            guest.poll();
            return host.isConnected() && guest.isConnected();
        }), "session " + session + " connects");
    }

    /** A stranger's datagrams to the host's UDP port are dropped by the system; the peers' own datagrams still arrive. */
    static void strangerNeverArrives(Link host, Link guest, java.net.DatagramSocket stranger, int session) throws Exception {
        long foreign = host.foreign, got = host.datagramsReceived;
        byte[] b = Wire.encode(new Wire.Header(1, 0, host.peer().session(), 1, Wire.nowUs()), new Wire.Datagram.Heartbeat());
        for (int i = 0; i < 20; i++) stranger.send(new java.net.DatagramPacket(b, b.length, LO, host.udpPort()));
        check(until(2000, () -> {
            host.poll();
            guest.poll();
            return host.datagramsReceived > got;
        }), "the guest's datagrams arrive in session " + session);
        until(100, () -> {
            host.poll();
            guest.poll();
            return false;
        });
        eq(host.foreign - foreign, 0L, "stranger's datagrams that reached the host in session " + session);
    }

    static void connectedUdp() {
        test("revision 8: during a session the UDP socket is connected, so the system drops other senders' datagrams", () -> {
            try (ReviewTests.Pair p = new ReviewTests.Pair(); java.net.DatagramSocket other = new java.net.DatagramSocket(0, LO)) {
                long foreign = p.host.foreign;
                for (int i = 0; i < 20; i++) other.send(new java.net.DatagramPacket(new byte[24], 24, LO, p.host.udpPort()));
                until(600, () -> { // more than a heartbeat period: the peers' own datagrams arrive meanwhile
                    p.host.poll();
                    p.guest.poll();
                    return false;
                });
                eq(p.host.foreign - foreign, 0L, "datagrams from another address that reached the host");
                check(p.host.isConnected() && p.guest.isConnected(), "the session goes on");
                check(p.host.datagramsReceived > 0 && p.guest.datagramsReceived > 0, "the peers' own datagrams arrive");
            }
        });

        test("revision 8: with port 0 each session has its own UDP socket, and a new session uses the port it announces", () -> {
            try (Link host = Link.host(hostConfig())) {
                LinkConfig gc = new LinkConfig("rev8 guest");
                gc.controlPort = host.controlPort();
                gc.reconnectMs = 0;
                try (Link guest = Link.guest(gc)) {
                    int[] ports = new int[4];
                    for (int session = 0; session < 2; session++) {
                        check(until(3000, () -> {
                            host.poll();
                            guest.poll();
                            return host.isConnected() && guest.isConnected();
                        }), "session " + session + " connects");
                        ports[2 * session] = host.udpPort();
                        ports[2 * session + 1] = guest.udpPort();
                        eq(host.peer().udpAddr().getPort(), guest.udpPort(), "the host uses the port the guest announced");
                        eq(guest.peer().udpAddr().getPort(), host.udpPort(), "the guest uses the port the host announced");
                        long got = host.datagramsReceived;
                        check(until(2000, () -> {
                            host.poll();
                            guest.poll();
                            return host.datagramsReceived > got;
                        }), "datagrams arrive in session " + session);
                        if (session == 0) guest.disconnect("next");
                    }
                    check(ports[2] != ports[0], "the host's second session has a new socket: " + ports[0] + " then " + ports[2]);
                    check(ports[3] != ports[1], "the guest's second session has a new socket: " + ports[1] + " then " + ports[3]);
                }
            }
        });

        test("revision 8: a fixed UDP port holds over three sessions, and a stranger's datagram never reaches the host", () -> {
            LinkConfig hc = hostConfig();
            hc.udpPort = freeUdpPort();
            try (Link host = Link.host(hc); java.net.DatagramSocket stranger = new java.net.DatagramSocket(0, LO)) {
                LinkConfig gc = new LinkConfig("rev8 guest");
                gc.controlPort = host.controlPort();
                gc.reconnectMs = 0;
                try (Link guest = Link.guest(gc)) {
                    for (int session = 0; session < 3; session++) {
                        connectBoth(host, guest, session);
                        eq(host.udpPort(), hc.udpPort, "the fixed port in session " + session);
                        eq(guest.peer().udpAddr().getPort(), hc.udpPort, "the port the host announced in session " + session);
                        check(host.lastError() == null, "no error: " + host.lastError());
                        strangerNeverArrives(host, guest, stranger, session);
                        guest.disconnect("next");
                    }
                }
            }
        });

        test("revision 8: a fixed UDP port taken between two sessions gives one session an automatic port, and says so", () -> {
            LinkConfig hc = hostConfig();
            hc.udpPort = freeUdpPort();
            java.net.DatagramSocket[] squatter = {null};
            try (Link host = Link.host(hc); java.net.DatagramSocket stranger = new java.net.DatagramSocket(0, LO)) {
                LinkConfig gc = new LinkConfig("rev8 guest");
                gc.controlPort = host.controlPort();
                gc.reconnectMs = 0;
                try (Link guest = Link.guest(gc)) {
                    connectBoth(host, guest, 0);
                    // Another process takes the port in the instant between closing the socket and binding it again.
                    Link.beforeRebindForTests = () -> {
                        try {
                            squatter[0] = new java.net.DatagramSocket(hc.udpPort, LO);
                        } catch (java.net.SocketException e) {
                            throw new AssertionError(e);
                        }
                    };
                    try {
                        guest.disconnect("next");
                        connectBoth(host, guest, 1);
                    } finally {
                        Link.beforeRebindForTests = null;
                    }
                    check(squatter[0] != null, "the port was taken");
                    check(host.udpPort() != hc.udpPort, "an automatic port for this session");
                    eq(guest.peer().udpAddr().getPort(), host.udpPort(), "the host announced the port it uses");
                    check(host.lastError() != null && host.lastError().contains("could not be bound again"), "lastError says so: " + host.lastError());
                    strangerNeverArrives(host, guest, stranger, 1);
                    squatter[0].close();
                    guest.disconnect("next");
                    connectBoth(host, guest, 2);
                    eq(host.udpPort(), hc.udpPort, "the fixed port again once it is free");
                    check(host.lastError() == null, "and no error: " + host.lastError());
                    strangerNeverArrives(host, guest, stranger, 2);
                }
            } finally {
                if (squatter[0] != null) squatter[0].close();
            }
        });

        test("revision 8: an unreachable peer port is a lost datagram, not the end of the session", () -> {
            LinkConfig hc = hostConfig();
            hc.heartbeatMs = 10;
            hc.timeoutMs = 3000;
            try (Link host = Link.host(hc)) {
                RawGuest g = new RawGuest(host);
                try {
                    g.udp.close(); // the peer's port goes away: the host's heartbeats are refused from now on
                    List<LinkEvent> events = new ArrayList<>();
                    until(600, () -> {
                        events.addAll(host.poll());
                        return false;
                    });
                    for (LinkEvent e : events) check(!(e instanceof LinkEvent.Lost), "the session ended: " + e);
                    check(host.isConnected(), "the session goes on until the watchdog decides");
                } finally {
                    g.close();
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // A side that was not running (sections 6 and 7)

    /** Polls {@code link} for {@code ms}, collecting its events. */
    static void pollFor(Link link, List<LinkEvent> events, long ms) throws InterruptedException {
        until(ms, () -> {
            events.addAll(link.poll());
            return false;
        });
    }

    static boolean has(List<LinkEvent> events, Class<? extends LinkEvent> type) {
        for (LinkEvent e : events) if (type.isInstance(e)) return true;
        return false;
    }

    static void notRunning() {
        test("revision 8: after a pause longer than the timeout the watchdog counts the peer's silence from the next poll", () -> {
            LinkConfig hc = hostConfig();
            hc.timeoutMs = 1000; // the raw guest's heartbeat_ms is 250: the watchdog is 1000 ms
            try (Link host = Link.host(hc); RawGuest g = new RawGuest(host)) {
                check(g.session != 0, "a session");
                Thread.sleep(1500); // not polled, as if the process had been stopped
                List<LinkEvent> events = new ArrayList<>();
                pollFor(host, events, 300);
                check(!has(events, LinkEvent.Lost.class), "not lost at once after the pause: " + events);
                pollFor(host, events, 1500);
                check(has(events, LinkEvent.Lost.class), "lost once the peer stays silent a whole timeout");
            }
        });

        test("revision 8: a pause shorter than the timeout counts as before", () -> {
            LinkConfig hc = hostConfig();
            hc.timeoutMs = 1000;
            try (Link host = Link.host(hc); RawGuest g = new RawGuest(host)) {
                check(g.session != 0, "a session");
                List<LinkEvent> events = new ArrayList<>();
                pollFor(host, events, 700);
                Thread.sleep(600);
                pollFor(host, events, 50);
                check(has(events, LinkEvent.Lost.class), "1300 ms of silence at the next poll: lost at once");
            }
        });

        test("revision 8: after a pause a host's candidate has the whole timeout again for its first line", () -> {
            LinkConfig hc = hostConfig();
            hc.timeoutMs = 500;
            try (Link host = Link.host(hc); Raw a = Raw.to(host)) {
                List<LinkEvent> events = new ArrayList<>();
                pollFor(host, events, 50);
                Thread.sleep(1000);
                pollFor(host, events, 100);
                a.send(guestHello(""));
                check(await(host, events, LinkEvent.Connected.class, 1000) != null, "the hello after the pause is in time: " + events);
            }
        });

        test("revision 8: after a pause a guest has the whole timeout again for the host's hello", () -> {
            try (ServerSocket server = new ServerSocket(0, 5, LO)) {
                server.setSoTimeout(1);
                LinkConfig gc = new LinkConfig("rev8 guest");
                gc.controlPort = server.getLocalPort();
                gc.timeoutMs = 500;
                try (Link guest = Link.guest(gc)) {
                    List<LinkEvent> events = new ArrayList<>();
                    Socket[] s = {null};
                    check(until(3000, () -> {
                        events.addAll(guest.poll());
                        try {
                            s[0] = server.accept();
                        } catch (SocketTimeoutException none) {
                            return false;
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                        return true;
                    }), "the guest connects");
                    try (Raw raw = new Raw(s[0])) {
                        check(raw.line(guest, events, 3000) != null, "the guest's hello");
                        Thread.sleep(1000);
                        pollFor(guest, events, 100);
                        raw.send("{\"type\":\"hello\",\"role\":\"host\",\"game\":\"raw\",\"protocol\":1,\"min_protocol\":1,"
                                + "\"udp_port\":4000,\"heartbeat_ms\":250,\"timeout_ms\":2000,\"session\":7}\n");
                        check(await(guest, events, LinkEvent.Connected.class, 1000) != null, "the host's hello after the pause is in time: " + events);
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Windows paths, the pixel file's mode, the endpoint file's owner (sections 6.2 and 9.6)

    static void files() {
        test("revision 8: on Windows a receiver opens only drive-letter paths that name no device", () -> {
            String[] ok = {"C:\\Users\\me\\AppData\\Local\\Temp\\pixel.frames", "c:/temp/pixel.frames", "C:\\a\\b/c.frames",
                "\\\\?\\C:\\Temp\\pixel.frames", "D:\\NULL.frames", "D:\\CONSOLE", "D:\\COM0.frames", "D:\\LPT10", "D:\\x\\nul.d\\f",
                "D:\\aux1.frames", "D:\\.frames"};
            String[] refused = {"\\\\192.0.2.1\\rbl-vectors\\pixel.frames", "\\\\?\\UNC\\192.0.2.1\\share\\pixel.frames",
                "\\\\.\\pipe\\rbl-vectors-pixel", "\\\\?\\GLOBALROOT\\Device\\Null", "\\\\.\\C:\\x.frames", "//server/share/x.frames",
                "C:\\rbl-vectors\\NUL.frames", "C:\\x\\nul", "C:\\x\\Con.txt", "C:\\x\\COM1", "C:\\x\\lpt9.frames", "C:\\x\\NUL .frames",
                "C:\\x\\aux", "C:\\x\\prn.", "C:pixel.frames", "C:", "C:\\", "C:\\x\\", "pixel.frames", "\\x\\pixel.frames", "",
                "1:\\x.frames", "\\\\?\\C:/x.frames", "\\\\?\\C:\\a\\..\\b.frames", "\\\\?\\C:\\a\\.\\b.frames", "\\\\?\\c:", "\\??\\C:\\x.frames"};
            for (String p : ok) check(PixelFile.windowsPathIsDriveLetter(p), "accepted: " + p);
            for (String p : refused) check(!PixelFile.windowsPathIsDriveLetter(p), "refused: " + p);
        });

        test("revision 8: PixelFile.Reader.open(String) opens what open(Path) opens", () -> {
            java.nio.file.Path dir = LinkTests.tempDir("rev8-open");
            java.nio.file.Path file = dir.resolve("w.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(file, 4, 2, 2, false)) {
                eq(w.width, 4, "the writer's width");
                try (PixelFile.Reader r = PixelFile.Reader.open(file.toAbsolutePath().toString())) {
                    eq(r.width, 4, "width");
                }
                boolean refused = false;
                try {
                    PixelFile.Reader.open("relative.frames").close();
                } catch (IOException e) {
                    refused = true;
                }
                check(refused, "a relative path is refused");
            }
        });

        test("revision 8: a new pixel file is owner-only (0600) from its creation on a POSIX file system", () -> {
            java.nio.file.Path dir = LinkTests.tempDir("rev8-mode");
            if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) return; // Windows: the directory's ACL
            java.nio.file.Path file = dir.resolve("w.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(file, 4, 2, 2, true)) {
                eq(w.path(), file, "the writer's file");
                eq(java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file)), "rw-------", "mode");
            }
        });

        test("revision 8: the endpoint file must belong to this process's user, where Java can tell", () -> {
            java.nio.file.Path dir = LinkTests.tempDir("rev8-owner");
            java.nio.file.Path file = dir.resolve("endpoint.json");
            String token = Tokens.generate();
            Endpoint.write(file, 47801, token);
            eq(Endpoint.read(file).controlPort(), 47801, "read by its owner");
            Long me = Endpoint.processUid();
            boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
            if (!posix) {
                check(me == null || !VectorTests.windows(), "no owner check without POSIX permissions");
                return;
            }
            check(me != null, "a Unix JVM tells its user id");
            Endpoint.uidForTests = me + 1; // as if another user ran this process
            try {
                Endpoint.read(file);
                throw new AssertionError("a file of another user was accepted");
            } catch (IOException refused) {
                eq(refused.getMessage(), "the endpoint file belongs to another user", "the refusal");
                check(!refused.getMessage().contains(token), "no token in the refusal");
            } finally {
                Endpoint.uidForTests = null;
            }
            eq(Endpoint.read(file).controlPort(), 47801, "read again by its owner");
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Numbers too large for a double (section 8, "Syntax of a line" 9)

    static void numbers() {
        test("revision 8: a number anywhere in a line is refused exactly when its correctly rounded double is not finite", () -> {
            java.math.BigInteger half = java.math.BigInteger.ONE.shiftLeft(1024).subtract(java.math.BigInteger.ONE.shiftLeft(970));
            String h = half.toString(); // 2^1024 - 2^970, 309 digits
            String m = h.charAt(0) + "." + h.substring(1);
            String nines = new String(new char[2000]).replace('\0', '9');
            String zeros = new String(new char[1500]).replace('\0', '0');
            String[] accepted = {"1.7976931348623157e308", "1.7976931348623158e308", "1.797693134862315807e308", "-1.797693134862315807e308",
                half.subtract(java.math.BigInteger.ONE).toString(), "-" + half.subtract(java.math.BigInteger.ONE),
                half.subtract(java.math.BigInteger.ONE) + "." + nines, m.substring(0, m.length() - 1) + "1" + nines + "e308",
                "0." + h.substring(0, h.length() - 1) + "1" + nines + "e309", "1e-400", "-1e-400", "0e999999999", "1e-999999999"};
            String[] refused = {h, "-" + h, m + "e308", h + ".0", h + "e0", "1.797693134862315808e308", m + zeros + "1e308",
                "1e400", "-1e400", "1e999999999", "1" + new String(new char[309]).replace('\0', '0'),
                "1" + new String(new char[65000]).replace('\0', '0')};
            for (String n : accepted) {
                Control c = Control.parse("{\"type\":\"despawn\",\"entity\":1,\"x\":[{\"y\":" + n + "}]}");
                check(c instanceof Control.Despawn, "accepted: " + (n.length() > 60 ? n.substring(0, 60) + "..." : n));
            }
            for (String n : refused) {
                boolean threw = false;
                try {
                    Control.parse("{\"type\":\"despawn\",\"entity\":1,\"x\":[{\"y\":" + n + "}]}");
                } catch (Wire.WireException e) {
                    threw = true;
                }
                check(threw, "refused: " + (n.length() > 60 ? n.substring(0, 60) + "..." : n));
            }
        });
    }
}
