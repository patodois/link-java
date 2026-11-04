package dev.patodois.rewritelink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.code_intelligence.jazzer.api.BugDetectors;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import dev.patodois.rewritelink.Control.Role;

/**
 * Fuzzes the handshake with arbitrary control bytes, over real loopback sockets and the real {@link Link#poll()}:
 * a host with no token, with an ASCII token, with a token of 256 bytes of multi-byte UTF-8, and a guest with a
 * token. Checks: no exception, no hang, no allocation beyond a budget, the token is in no event, no bye and no
 * message, and a host that has a token accepts a hello only when it carries exactly that token.
 */
class HandshakeFuzz {
    static final InetAddress LO = loopback();

    static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token with four 3-byte characters in the middle: 256 bytes exactly. */
    static String longUnicodeToken() {
        StringBuilder b = new StringBuilder("é☃😀");
        while (b.toString().getBytes(StandardCharsets.UTF_8).length < 256 - 4) b.append('k');
        while (b.toString().getBytes(StandardCharsets.UTF_8).length < 256) b.append('z');
        return b.toString();
    }

    private static final String[] TOKENS = {null, Tokens.generate(), longUnicodeToken(), "a"};
    private static final Link[] HOSTS = new Link[TOKENS.length];

    private static synchronized Link host(int i) throws IOException {
        if (HOSTS[i] == null) {
            LinkConfig cfg = new LinkConfig("fuzz-host");
            cfg.controlPort = 0;
            cfg.timeoutMs = 2000;
            cfg.token = TOKENS[i];
            HOSTS[i] = Link.host(cfg);
            final Link h = HOSTS[i];
            Runtime.getRuntime().addShutdownHook(new Thread(h::close));
        }
        return HOSTS[i];
    }

    // ------------------------------------------------------------------ host side

    /** Raw bytes as the first things a guest says. */
    @FuzzTest(maxDuration = "30m")
    void hostRaw(FuzzedDataProvider d) throws Exception {
        BugDetectors.allowNetworkConnections();
        int which = d.consumeInt(0, TOKENS.length - 1);
        byte[] bytes = d.consumeRemainingAsBytes();
        runHost(which, bytes, null);
    }

    /** A well-formed hello whose token is right, nearly right or wrong, then fuzzed lines. */
    @FuzzTest(maxDuration = "30m")
    void hostHello(FuzzedDataProvider d) throws Exception {
        BugDetectors.allowNetworkConnections();
        int which = d.consumeInt(0, TOKENS.length - 1);
        String real = TOKENS[which];
        String sent;     // what the guest sends; null = no token field
        String variant;  // the JSON text of the token field value
        String base = real != null ? real : "whatever-token";
        switch (d.consumeInt(0, 11)) {
            case 0: sent = base; break;
            case 1: sent = base.substring(0, base.length() - 1); break;
            case 2: sent = base + "x"; break;
            case 3: sent = ""; break;
            case 4: sent = null; break;
            case 5: sent = base.toUpperCase(java.util.Locale.ROOT).equals(base) ? base + " " : base.toUpperCase(java.util.Locale.ROOT); break;
            case 6: sent = " " + base; break;
            case 7: sent = base + base; break;
            case 8: sent = new StringBuilder(base).reverse().toString(); break;
            case 9: { // one character changed
                char[] c = base.toCharArray();
                int i = d.consumeInt(0, c.length - 1);
                c[i] = (char) (c[i] == 'q' ? 'r' : 'q');
                sent = new String(c);
                break;
            }
            case 10: sent = base; break; // same string, spelled with \\u escapes below
            default: sent = d.consumeString(300); break;
        }
        boolean escaped = d.consumeBoolean();
        if (sent == null) variant = null;
        else if (escaped) variant = allEscaped(sent);
        else variant = Json.quote(sent);
        int fieldsMode = d.consumeInt(0, 5);
        String hello = "{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"g\",\"protocol\":1,\"min_protocol\":1,\"udp_port\":"
                + (fieldsMode == 1 ? 0 : 4000) + ",\"heartbeat_ms\":" + (fieldsMode == 2 ? 5 : 250) + ",\"timeout_ms\":2000"
                + (variant != null ? ",\"token\":" + variant : "") + (fieldsMode == 3 ? ",\"session\":1" : "") + "}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(hello.getBytes(StandardCharsets.UTF_8));
        out.write(d.consumeRemainingAsBytes());
        boolean validOtherFields = fieldsMode != 1 && fieldsMode != 2 && (sent == null || sent.indexOf('\0') < 0); // U+0000 in any string is a protocol error
        Boolean expectAccept = validOtherFields ? Boolean.valueOf(real == null || (sent != null && sent.equals(real))) : null;
        runHost(which, out.toByteArray(), expectAccept);
    }

    static String allEscaped(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) b.append(String.format("\\u%04x", (int) s.charAt(i)));
        return b.append('"').toString();
    }

    private static void runHost(int which, byte[] bytes, Boolean expectAccept) throws Exception {
        if (bytes.length > 300_000) bytes = java.util.Arrays.copyOf(bytes, 300_000);
        Link host = host(which);
        String token = TOKENS[which];
        long t0 = System.nanoTime();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(LO, host.controlPort()), 2000);
        s.setSoTimeout(2000);
        s.setTcpNoDelay(true);
        List<LinkEvent> events = new ArrayList<>();
        long alloc0 = allocated();
        try {
            s.getOutputStream().write(bytes);
            s.getOutputStream().flush();
            s.shutdownOutput();
            pollUntilEnded(host, events, 5000);
            // The host must be free again for the next connection.
            if (host.isConnected()) throw new AssertionError("still connected after the peer ended");
            String sent = new String(readAll(s), StandardCharsets.UTF_8);
            check(token, bytes, events, sent, expectAccept);
        } finally {
            s.close();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long bytesAllocated = allocated() - alloc0;
        if (ms > 4000) throw new AssertionError("a connection took " + ms + " ms");
        if (bytesAllocated > 256L << 20) throw new AssertionError("a connection allocated " + bytesAllocated + " bytes");
    }

    // ------------------------------------------------------------------ guest side

    /** A fake host that says fuzzed things to a guest that has (or has not) a token. */
    @FuzzTest(maxDuration = "30m")
    void guest(FuzzedDataProvider d) throws Exception {
        BugDetectors.allowNetworkConnections();
        int which = d.consumeInt(0, 1);
        byte[] reply;
        if (d.consumeBoolean()) {
            String h = "{\"type\":\"hello\",\"role\":\"" + (d.consumeInt(0, 9) == 0 ? "guest" : "host") + "\",\"game\":\"g\",\"protocol\":"
                    + d.pickValue(new String[] {"1", "1", "1", "0", "2", "65535", "-1"}) + ",\"min_protocol\":1,\"udp_port\":4000,\"heartbeat_ms\":"
                    + d.pickValue(new String[] {"250", "250", "9", "10001", "10", "10000", "0"}) + ",\"timeout_ms\":2000,\"session\":"
                    + d.pickValue(new String[] {"7", "7", "0", "4294967295", "4294967296"}) + "}\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(h.getBytes(StandardCharsets.UTF_8));
            out.write(d.consumeRemainingAsBytes());
            reply = out.toByteArray();
        } else {
            reply = d.consumeRemainingAsBytes();
        }
        if (reply.length > 300_000) reply = java.util.Arrays.copyOf(reply, 300_000);
        runGuest(which, reply);
    }

    private static final String[] GUEST_TOKENS = {null, Tokens.generate()};
    private static ServerSocket server;
    private static final Link[] GUESTS = new Link[2];

    private static synchronized Link guest(int i) throws IOException {
        if (server == null) {
            server = new ServerSocket(0, 50, LO);
            server.setSoTimeout(3000);
            final ServerSocket sv = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    sv.close();
                } catch (IOException ignored) {
                    // exiting
                }
            }));
        }
        if (GUESTS[i] == null) {
            LinkConfig cfg = new LinkConfig("fuzz-guest");
            cfg.controlPort = server.getLocalPort();
            cfg.reconnectMs = 0;
            cfg.timeoutMs = 2000;
            cfg.token = GUEST_TOKENS[i];
            GUESTS[i] = Link.guest(cfg);
            final Link g = GUESTS[i];
            Runtime.getRuntime().addShutdownHook(new Thread(g::close));
        }
        return GUESTS[i];
    }

    private static void runGuest(int which, byte[] reply) throws Exception {
        Link guest = guest(which);
        String token = GUEST_TOKENS[which];
        List<LinkEvent> events = new ArrayList<>();
        // Wait for the guest to connect to the fake host. Only the guest under test connects to it at this moment: the
        // other guest, if it was used earlier, is idle in its reconnect loop and would also connect; it is never polled
        // here, so its attempt stays in the backlog until it is polled and then sees a closed socket.
        Socket s = null;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (s == null) {
            events.addAll(guest.poll());
            try {
                server.setSoTimeout(1);
                s = server.accept();
            } catch (SocketTimeoutException e) {
                if (System.nanoTime() > deadline) throw new AssertionError("the guest did not connect");
            }
        }
        try {
            s.setSoTimeout(2000);
            // Its hello: one line that parses, carries the guest's token (if any) and nothing else secret.
            String line = readLine(s, events, guest);
            Control c;
            try {
                c = Control.parse(line);
            } catch (Wire.WireException e) {
                throw new AssertionError("the guest's hello does not parse: " + line, e);
            }
            if (!(c instanceof Control.Hello) || ((Control.Hello) c).role() != Role.GUEST) throw new AssertionError("not a guest hello: " + line);
            String sentToken = ((Control.Hello) c).token();
            if (token == null ? sentToken != null : !token.equals(sentToken)) throw new AssertionError("wrong token in the guest's hello");
            s.getOutputStream().write(reply);
            s.getOutputStream().flush();
            s.shutdownOutput();
            pollUntilEnded(guest, events, 5000);
            String after = new String(readAll(s), StandardCharsets.UTF_8); // what the guest says after its hello
            check(token, reply, events, after, null);
        } finally {
            s.close();
        }
    }

    // ------------------------------------------------------------------ common

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /** Polls until the link reports the end of the connection (a failed handshake or a lost session). */
    private static void pollUntilEnded(Link link, List<LinkEvent> events, long maxMs) throws Exception {
        long deadline = System.nanoTime() + maxMs * 1_000_000L;
        while (true) {
            long t = System.nanoTime();
            List<LinkEvent> got = link.poll();
            long took = (System.nanoTime() - t) / 1_000_000L;
            if (took > 1500) throw new AssertionError("one poll() took " + took + " ms");
            events.addAll(got);
            for (LinkEvent e : got) {
                if (e instanceof LinkEvent.HandshakeFailed || e instanceof LinkEvent.Lost) return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("the link did not end the connection within " + maxMs + " ms; events " + events);
            Thread.yield();
        }
    }

    private static byte[] readAll(Socket s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        InputStream in = s.getInputStream();
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > 4 << 20) throw new AssertionError("the link wrote more than 4 MiB to a peer that said little");
            }
        } catch (SocketTimeoutException e) {
            throw new AssertionError("the link did not close the control connection (read timed out)");
        } catch (java.net.SocketException reset) {
            // a reset after the bye is a way for a connection to end
        }
        return out.toByteArray();
    }

    /** One line from the guest, polling it meanwhile (it writes its hello when a poll sees the connection complete). */
    private static String readLine(Socket s, List<LinkEvent> events, Link link) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = s.getInputStream();
        s.setSoTimeout(1);
        long deadline = System.nanoTime() + 3_000_000_000L;
        try {
            while (true) {
                events.addAll(link.poll());
                int b;
                try {
                    b = in.read();
                } catch (SocketTimeoutException e) {
                    if (System.nanoTime() > deadline) throw new AssertionError("the guest sent no hello");
                    continue;
                }
                if (b < 0) throw new AssertionError("the guest closed before its hello");
                if (b == '\n') break;
                out.write(b);
                if (out.size() > Wire.MAX_LINE) throw new AssertionError("the guest's hello line is too long");
            }
        } finally {
            s.setSoTimeout(2000);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The checks every run shares. {@code peerSent}: the bytes the fuzzer sent; {@code said}: what the link wrote back. */
    private static void check(String token, byte[] peerSent, List<LinkEvent> events, String said, Boolean expectAccept) throws Exception {
        boolean secretCheck = token != null && token.length() >= 8 && indexOf(peerSent, token.getBytes(StandardCharsets.UTF_8)) < 0;
        boolean connected = false;
        for (LinkEvent e : events) {
            String shown = String.valueOf(e);
            if (e instanceof LinkEvent.Connected) connected = true;
            if (secretCheck && shown.contains(token)) throw new AssertionError("the token is in an event: " + shown);
        }
        if (secretCheck && said.contains(token)) throw new AssertionError("the token is in what the link wrote to the peer: " + said);
        // Everything the link writes must be a line that parses.
        for (String l : said.split("\n")) {
            if (l.isEmpty()) continue;
            try {
                Control.parse(l);
            } catch (Wire.WireException e) {
                throw new AssertionError("the link wrote a line that does not parse: " + l, e);
            }
        }
        if (token != null && connected && peerSent != null && TOKENS_AS_HOST.contains(token)) {
            // The first line the peer sent must have been a hello with exactly this token.
            String first = firstLine(peerSent);
            Control c = first == null ? null : Control.parse(first);
            if (!(c instanceof Control.Hello) || !token.equals(((Control.Hello) c).token())) {
                throw new AssertionError("a host with a token accepted a hello without it: " + first);
            }
        }
        if (expectAccept != null && expectAccept != connected) {
            throw new AssertionError("expected accepted=" + expectAccept + " but connected=" + connected + " for " + new String(peerSent, StandardCharsets.UTF_8).split("\n")[0] + "; events " + events);
        }
    }

    private static final java.util.Set<String> TOKENS_AS_HOST = new java.util.HashSet<>();

    static {
        for (String t : TOKENS) if (t != null) TOKENS_AS_HOST.add(t);
    }

    private static String firstLine(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n') return Guard.strictUtf8(java.util.Arrays.copyOf(b, i));
        }
        return null;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
