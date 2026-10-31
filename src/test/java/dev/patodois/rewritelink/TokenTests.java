package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;
import static dev.patodois.rewritelink.LinkTests.until;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Contract revision 6 (CONTRACT.md 6.2): the token, its comparison, the endpoint file, and a live link with them. */
final class TokenTests {
    private TokenTests() {}

    private static final String T = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String W = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Path tempDir() throws Exception {
        Path d = Files.createTempDirectory("token-test");
        d.toFile().deleteOnExit();
        return d;
    }

    /** Deletes the files of a directory the test made (never recursive beyond one level), then the directory. */
    private static void cleanup(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.collect(Collectors.toList())) Files.deleteIfExists(p);
        }
        Files.deleteIfExists(dir);
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static void expectIllegal(LinkConfig c, String why, String... mustNotContain) throws Exception {
        try {
            Link.host(c).close();
        } catch (IllegalArgumentException e) {
            for (String s : mustNotContain) check(!String.valueOf(e.getMessage()).contains(s), "the message shows the token: " + e.getMessage());
            return;
        }
        throw new AssertionError("expected IllegalArgumentException: " + why);
    }

    /** Sends one hello line as a raw guest (the {@code tokenJson} member is inserted verbatim, or nothing if null); returns the host's reply line. */
    private static String rawHello(Link host, String tokenMember, List<LinkEvent> events) throws Exception {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), host.controlPort())) {
            String line = "{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"raw\",\"protocol\":1,\"min_protocol\":1,\"udp_port\":9,"
                    + "\"heartbeat_ms\":250,\"timeout_ms\":2000" + (tokenMember == null ? "" : "," + tokenMember) + "}\n";
            s.getOutputStream().write(utf8(line));
            InputStream in = s.getInputStream();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            until(3000, () -> {
                events.addAll(host.poll());
                try {
                    while (in.available() > 0) got.write(in.read());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return got.toString().indexOf('\n') >= 0;
            });
            return got.toString("UTF-8");
        }
    }

    private static LinkConfig hostConfig(String token) {
        LinkConfig c = new LinkConfig("Token Host");
        c.controlPort = 0;
        c.token = token;
        return c;
    }

    private static boolean connects(Link a, Link b) throws Exception {
        return until(4000, () -> {
            a.poll();
            b.poll();
            return a.isConnected() && b.isConnected();
        });
    }

    static void run() {
        // ---------------------------------------------------------------- comparison
        test("revision 6: Tokens.equal, the reference method", () -> {
            check(Tokens.equal(utf8("abc"), utf8("abc")), "equal");
            check(!Tokens.equal(utf8("abc"), utf8("xbc")), "differ at the first byte");
            check(!Tokens.equal(utf8("abc"), utf8("abx")), "differ at the last byte");
            check(!Tokens.equal(utf8("abc"), utf8("ab")), "prefix");
            check(!Tokens.equal(utf8("ab"), utf8("abc")), "longer");
            check(!Tokens.equal(utf8("abc"), new byte[0]), "empty against a token");
            check(Tokens.equal(new byte[0], new byte[0]), "two empty arrays are equal here (never reachable from a handshake)");
            check(!Tokens.equal(new byte[] {0}, new byte[0]), "a NUL byte is not nothing: the lengths differ");
            String a256 = repeat("t", 256);
            check(Tokens.equal(utf8(a256), utf8(a256)), "256 against 256");
            check(!Tokens.equal(utf8(a256), utf8(repeat("t", 255) + "u")), "256 differing in the last byte");
            String a257 = repeat("t", 257);
            check(!Tokens.equal(utf8(a257), utf8(a257)), "257 against the same 257: false");
            check(!Tokens.equal(utf8(a256), utf8(a257)), "256 against its extension");
            check(Tokens.equal("é", "é") && !Tokens.equal("é", "e"), "the String form compares the UTF-8 bytes");
        });

        test("revision 6: Tokens.generate gives 64 lowercase hex digits and a new one each time", () -> {
            String a = Tokens.generate();
            String b = Tokens.generate();
            eq(a.length(), 64, "length");
            check(a.matches("[0-9a-f]{64}") && b.matches("[0-9a-f]{64}"), "lowercase hex");
            check(!a.equals(b), "two calls differ");
            check(!a.equals(repeat("0", 64)), "not the zero string");
        });

        test("revision 6: a configured token must have 1 to 256 UTF-8 bytes and no U+0000", () -> {
            String secret = "s3cr3t-" + repeat("x", 300);
            expectIllegal(hostConfig(""), "empty");
            expectIllegal(hostConfig(secret), "over 256 bytes", secret, "s3cr3t");
            expectIllegal(hostConfig(repeat("t", 257)), "257 bytes");
            expectIllegal(hostConfig(repeat("é", 129)), "129 two-byte characters are 258 bytes");
            expectIllegal(hostConfig("a\0b"), "NUL");
            expectIllegal(hostConfig("\ud800"), "a lone surrogate");
            for (String ok : new String[] {"a", repeat("t", 256), repeat("é", 128), repeat("😀", 64)}) {
                Link.host(hostConfig(ok)).close();
            }
            LinkConfig g = new LinkConfig("G");
            g.token = "";
            try {
                Link.guest(g).close();
                throw new AssertionError("a guest with an empty token must fail");
            } catch (IllegalArgumentException expected) {
                // fine
            }
        });

        test("revision 6: a hello with a 257-byte token parses whole and does not equal a 256-byte host token", () -> {
            String t257 = repeat("t", 257);
            Control c = Control.parse("{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"g\",\"protocol\":1,\"min_protocol\":1,\"udp_port\":9,"
                    + "\"heartbeat_ms\":250,\"timeout_ms\":2000,\"token\":\"" + t257 + "\"}");
            eq(((Control.Hello) c).token(), t257, "the parser keeps the whole token");
            LinkConfig hc = hostConfig(repeat("t", 256));
            try (Link host = Link.host(hc)) {
                List<LinkEvent> ev = new ArrayList<>();
                String reply = rawHello(host, "\"token\":\"" + t257 + "\"", ev);
                eq(reply, "{\"type\":\"bye\",\"reason\":\"unauthorized\"}\n", "a 256-byte token is not a prefix match");
            }
        });

        test("revision 6: Hello.toString and LinkConfig.toString do not show the token", () -> {
            Control.Hello h = new Control.Hello(Control.Role.GUEST, "g", 1, 1, 9, 250, 2000, null, T);
            check(!h.toString().contains(T) && !h.toString().contains("0123456789"), "Hello: " + h);
            LinkConfig c = new LinkConfig("g");
            c.token = T;
            check(!c.toString().contains(T) && !c.toString().contains("0123456789"), "LinkConfig: " + c);
            check(!new Endpoint(47800, T).toString().contains("0123456789"), "Endpoint");
            check(h.toLine().contains("\"token\":\"" + T + "\""), "the line itself carries it");
            check(!new Control.Hello(Control.Role.HOST, "g", 1, 1, 9, 250, 2000, 5L).toLine().contains("token"), "no token member without a token");
        });

        // ---------------------------------------------------------------- endpoint file
        test("revision 6: Endpoint.write then read round trip, owner-only, replaces, leaves no temporary file", () -> {
            Path dir = tempDir();
            try {
                Path f = dir.resolve("ep.json");
                Endpoint.write(f, 47800, T);
                eq(names(dir), Arrays.asList("ep.json"), "only the endpoint file is there");
                eq(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), "{\"control_port\":47800,\"token\":\"" + T + "\"}\n", "bytes");
                if (posix()) eq(PosixFilePermissions.toString(Files.getPosixFilePermissions(f)), "rw-------", "mode 0600");
                Endpoint e = Endpoint.read(f);
                eq(e.controlPort(), 47800, "port");
                eq(e.token(), T, "token");
                Endpoint.write(f, 1234, W); // replaces
                e = Endpoint.read(f);
                eq(e.controlPort(), 1234, "replaced port");
                eq(e.token(), W, "replaced token");
                eq(names(dir), Arrays.asList("ep.json"), "still only the endpoint file");
                if (posix()) eq(PosixFilePermissions.toString(Files.getPosixFilePermissions(f)), "rw-------", "still mode 0600");
                Endpoint.delete(f);
                check(!Files.exists(f), "deleted");
                Endpoint.delete(f); // a missing file is fine
            } finally {
                cleanup(dir);
            }
        });

        test("revision 6: Endpoint.write does not create directories and leaves nothing behind when it fails", () -> {
            Path dir = tempDir();
            try {
                try {
                    Endpoint.write(dir.resolve("missing").resolve("ep.json"), 47800, T);
                    throw new AssertionError("writing into a missing directory must fail");
                } catch (IOException expected) {
                    check(!String.valueOf(expected.getMessage()).contains(T), "message: " + expected.getMessage());
                }
                check(!Files.exists(dir.resolve("missing")), "no directory was created");
                // the target is a directory: the rename fails, the temporary file must be removed
                Files.createDirectory(dir.resolve("ep.json"));
                Files.createFile(dir.resolve("ep.json").resolve("x"));
                try {
                    Endpoint.write(dir.resolve("ep.json"), 47800, T);
                    throw new AssertionError("renaming over a non-empty directory must fail");
                } catch (IOException expected) {
                    check(!String.valueOf(expected.getMessage()).contains(T), "message: " + expected.getMessage());
                }
                eq(names(dir), Arrays.asList("ep.json"), "no temporary file left behind");
                Files.delete(dir.resolve("ep.json").resolve("x"));
                try {
                    Endpoint.write(dir.resolve("e.json"), 0, T);
                    throw new AssertionError("port 0");
                } catch (IllegalArgumentException expected) {
                    // fine
                }
            } finally {
                cleanup(dir);
            }
        });

        test("revision 6: Endpoint.read refuses a file others can read (mode 0644), and a missing one, without quoting the file", () -> {
            Path dir = tempDir();
            try {
                Path f = dir.resolve("ep.json");
                Endpoint.write(f, 47800, T);
                if (posix()) {
                    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
                    try {
                        Endpoint.read(f);
                        throw new AssertionError("mode 0644 must be refused");
                    } catch (IOException expected) {
                        check(!String.valueOf(expected.getMessage()).contains(T), "message: " + expected.getMessage());
                    }
                    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--------"));
                    eq(Endpoint.read(f).controlPort(), 47800, "mode 0400 is fine");
                }
                try {
                    Endpoint.read(dir.resolve("nothing.json"));
                    throw new AssertionError("a missing file");
                } catch (IOException expected) {
                    // fine
                }
            } finally {
                cleanup(dir);
            }
        });

        // ---------------------------------------------------------------- live links
        test("revision 6: a host with an endpoint file writes it after binding, a guest connects through it, close removes it", () -> {
            Path dir = tempDir();
            try {
                Path f = dir.resolve("ep.json");
                LinkConfig hc = hostConfig(null);
                hc.endpointFile = f;
                Link host = Link.host(hc);
                try {
                    check(Files.isRegularFile(f), "the file exists as soon as the host is created");
                    Endpoint e = Endpoint.read(f);
                    eq(e.controlPort(), host.controlPort(), "the port the host bound (it was configured as 0)");
                    check(e.token().matches("[0-9a-f]{64}"), "a generated token");
                    eq(hc.token, null, "the caller's config is not modified");
                    if (posix()) eq(PosixFilePermissions.toString(Files.getPosixFilePermissions(f)), "rw-------", "mode 0600");
                    LinkConfig gc = new LinkConfig("Token Guest");
                    gc.controlPort = 1; // ignored: the file decides
                    gc.endpointFile = f;
                    gc.reconnectMs = 50;
                    try (Link guest = Link.guest(gc)) {
                        check(connects(host, guest), "the guest connects with the token from the file");
                    }
                } finally {
                    host.close();
                }
                check(!Files.exists(f), "the file is gone after close");
                host.close(); // twice is fine
                eq(names(dir), Arrays.asList(), "nothing else is left");
            } finally {
                cleanup(dir);
            }
        });

        test("revision 6: a host given a token and an endpoint file writes that token; a guest with the token but no file connects", () -> {
            Path dir = tempDir();
            try {
                Path f = dir.resolve("ep.json");
                LinkConfig hc = hostConfig(W);
                hc.endpointFile = f;
                try (Link host = Link.host(hc)) {
                    eq(Endpoint.read(f).token(), W, "the configured token is the one in the file");
                    LinkConfig gc = new LinkConfig("G");
                    gc.controlPort = host.controlPort();
                    gc.token = W;
                    try (Link guest = Link.guest(gc)) {
                        check(connects(host, guest), "connected with the configured token");
                    }
                }
            } finally {
                cleanup(dir);
            }
        });

        test("revision 6: if the endpoint file cannot be written, creating the host fails, frees the port and says nothing of the token", () -> {
            Path dir = tempDir();
            try {
                int port;
                try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                    port = probe.getLocalPort();
                }
                LinkConfig hc = hostConfig(T);
                hc.controlPort = port;
                hc.endpointFile = dir.resolve("no-such-dir").resolve("ep.json");
                try {
                    Link.host(hc).close();
                    throw new AssertionError("the host must not be created");
                } catch (IOException e) {
                    for (Throwable t = e; t != null; t = t.getCause()) check(!String.valueOf(t.getMessage()).contains(T), "message: " + t.getMessage());
                }
                hc.endpointFile = null;
                Link.host(hc).close(); // the port was released
            } finally {
                cleanup(dir);
            }
        });

        test("revision 6: a guest whose endpoint file is missing makes no attempt, then connects when it appears, and follows a restarted host", () -> {
            Path dir = tempDir();
            try {
                Path f = dir.resolve("ep.json");
                LinkConfig gc = new LinkConfig("G");
                gc.endpointFile = f;
                gc.reconnectMs = 30;
                try (Link guest = Link.guest(gc)) {
                    List<LinkEvent> ev = new ArrayList<>();
                    until(300, () -> {
                        ev.addAll(guest.poll());
                        return false;
                    });
                    check(ev.isEmpty(), "no event while there is no file: " + ev);
                    for (int round = 0; round < 2; round++) {
                        LinkConfig hc = hostConfig(null);
                        hc.endpointFile = f;
                        try (Link host = Link.host(hc)) {
                            check(connects(host, guest), "round " + round + ": connected through the file");
                        }
                        // the host closed: its bye ends the session, the file is gone, the next host has a new port and token
                        check(until(3000, () -> {
                            guest.poll();
                            return !guest.isConnected();
                        }), "the guest sees the end");
                    }
                }
            } finally {
                cleanup(dir);
            }
        });

        test("revision 6: unauthorized: the fixed bye, the fixed reasons, and no token anywhere", () -> {
            try (Link host = Link.host(hostConfig(T))) {
                List<LinkEvent> hostEvents = new ArrayList<>();
                String reply = rawHello(host, "\"token\":\"" + W + "\"", hostEvents);
                eq(reply, "{\"type\":\"bye\",\"reason\":\"unauthorized\"}\n", "bye line");
                check(!reply.contains(T) && !reply.contains(W), "no token in the bye");
                boolean sawFailure = false;
                for (LinkEvent e : hostEvents) {
                    if (e instanceof LinkEvent.HandshakeFailed) {
                        sawFailure = true;
                        eq(((LinkEvent.HandshakeFailed) e).reason(), "unauthorized", "host event reason");
                    }
                    check(!e.toString().contains(T) && !e.toString().contains(W), "event text: " + e);
                }
                check(sawFailure, "a HandshakeFailed event");
                // no token, null token, empty token: all the same word
                for (String member : new String[] {null, "\"token\":null", "\"token\":\"\""}) {
                    eq(rawHello(host, member, new ArrayList<LinkEvent>()), "{\"type\":\"bye\",\"reason\":\"unauthorized\"}\n", "member " + member);
                }
                // and the library's own guest, with a wrong token
                LinkConfig gc = new LinkConfig("G");
                gc.controlPort = host.controlPort();
                gc.token = W;
                gc.reconnectMs = 20;
                try (Link guest = Link.guest(gc)) {
                    List<LinkEvent> gev = new ArrayList<>();
                    check(until(3000, () -> {
                        host.poll();
                        gev.addAll(guest.poll());
                        for (LinkEvent e : gev) if (e instanceof LinkEvent.HandshakeFailed) return true;
                        return false;
                    }), "the guest's handshake fails");
                    for (LinkEvent e : gev) {
                        check(!e.toString().contains(T) && !e.toString().contains(W), "guest event text: " + e);
                        if (e instanceof LinkEvent.HandshakeFailed) {
                            check(((LinkEvent.HandshakeFailed) e).reason().contains("unauthorized"), "guest reason: " + e);
                        }
                        check(!(e instanceof LinkEvent.Connected), "no Connected");
                    }
                    check(!guest.isConnected() && !host.isConnected(), "nobody connected");
                    LinkConfig shown = new LinkConfig("G");
                    shown.token = W;
                    check(!shown.toString().contains(W), "config string");
                }
            }
        });

        test("revision 6: a host without a token ignores the field; a guest without one sends none", () -> {
            try (Link host = Link.host(hostConfig(null))) {
                String reply = rawHello(host, "\"token\":\"" + repeat("z", 300) + "\"", new ArrayList<LinkEvent>());
                check(reply.contains("\"type\":\"hello\"") && reply.contains("\"role\":\"host\""), "an ordinary host hello: " + reply);
                check(!reply.contains("token"), "a host never puts token in its hello");
            }
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                LinkConfig gc = new LinkConfig("G");
                gc.controlPort = probe.getLocalPort();
                try (Link guest = Link.guest(gc)) {
                    probe.setSoTimeout(10);
                    Socket accepted = null;
                    long end = System.nanoTime() + 3_000_000_000L;
                    while (accepted == null && System.nanoTime() < end) {
                        guest.poll();
                        try {
                            accepted = probe.accept();
                        } catch (java.net.SocketTimeoutException again) {
                            // poll again
                        }
                    }
                    check(accepted != null, "the guest connected");
                    try (Socket s = accepted) {
                        until(500, () -> {
                            guest.poll();
                            return false;
                        });
                        s.setSoTimeout(2000);
                        byte[] buf = new byte[4096];
                        int n = s.getInputStream().read(buf);
                        check(n > 0 && !new String(buf, 0, n, StandardCharsets.UTF_8).contains("token"), "no token member");
                    }
                }
            }
        });
    }
}
