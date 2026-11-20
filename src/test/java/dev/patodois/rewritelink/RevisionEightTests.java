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
}
