package dev.patodois.rewritelink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Fuzzes the endpoint file (CONTRACT.md 6.2): {@link Endpoint#read} on arbitrary file contents, and the
 * constructor and {@link Endpoint#write} on arbitrary ports and tokens. Allowed outcome: an {@link Endpoint} or an
 * {@link IOException} (for the constructor and {@code write}: an {@link IllegalArgumentException}); the token must
 * never appear in a message.
 */
class EndpointFuzz {
    private static Path dir;
    private static Path file;

    private static synchronized Path file() throws IOException {
        if (file == null) {
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            dir = posix ? Files.createTempDirectory("link-fuzz-ep", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                    : Files.createTempDirectory("link-fuzz-ep");
            file = dir.resolve("endpoint.json");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // a temporary directory
                }
            }));
        }
        return file;
    }

    private static void put(byte[] content) throws IOException {
        Path f = file();
        Files.write(f, content);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @FuzzTest(maxDuration = "30m")
    void readRaw(byte[] content) throws Exception {
        check(content);
    }

    /** A file that is almost right: the two fields, with the values and the framing the fuzzer picks. */
    @FuzzTest(maxDuration = "30m")
    void readShaped(FuzzedDataProvider d) throws Exception {
        String token = d.consumeBoolean() ? ControlFuzz.MARK + d.consumeString(40) : d.consumeString(300);
        String port = d.pickValue(new String[] {"47800", "0", "1", "65535", "65536", "-1", "1.0", "1e3", "\"47800\"", "null", "4294967296", "99999999999999999999"});
        String tok;
        switch (d.consumeInt(0, 6)) {
            case 0: tok = "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; break;
            case 1: tok = Json.quote(token); break;
            case 2: tok = "\"" + token + "\""; break;
            case 3: tok = "null"; break;
            case 4: tok = "[" + Json.quote(token) + "]"; break;
            case 5: tok = "\"\\u0000\""; break;
            default: tok = "\"\""; break;
        }
        String end = d.pickValue(new String[] {"", "\n", "\r\n", "\n\n", "\r", " ", "\n\r"});
        String extra = d.consumeBoolean() ? ",\"x\":" + d.consumeString(20) : "";
        String text = d.consumeBoolean() ? "{\"control_port\":" + port + ",\"token\":" + tok + extra + "}" + end
                : "{\"token\":" + tok + ",\"control_port\":" + port + extra + "}" + end;
        check(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The raw text between the quotes after the first {@code "token":} (a scan; a regular expression recurses per character). */
    private static String tokenText(String text) {
        int k = text.indexOf("\"token\"");
        if (k < 0) return null;
        int i = k + 7;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i >= text.length() || text.charAt(i) != ':') return null;
        i++;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i >= text.length() || text.charAt(i) != '"') return null;
        int start = ++i;
        while (i < text.length() && text.charAt(i) != '"') i += text.charAt(i) == '\\' ? 2 : 1;
        return i <= text.length() ? text.substring(start, Math.min(i, text.length())) : null;
    }

    private static void check(byte[] content) throws Exception {
        put(content);
        final Endpoint[] got = new Endpoint[1];
        String secret = null;
        secret = tokenText(new String(content, StandardCharsets.ISO_8859_1));
        try {
            Guard.within("Endpoint.read", 1500, 32L << 20, () -> got[0] = Endpoint.read(file()));
        } catch (IOException e) {
            if (secret != null && secret.length() >= 4 && String.valueOf(e.getMessage()).contains(secret)) {
                throw new AssertionError("the token is in an IOException message: " + e.getMessage());
            }
            return;
        }
        Endpoint ep = got[0];
        if (ep.controlPort() < 1 || ep.controlPort() > 65535) throw new AssertionError("port " + ep.controlPort());
        String t = ep.token();
        int n = t.getBytes(StandardCharsets.UTF_8).length;
        if (n < 1 || n > Tokens.MAX_BYTES) throw new AssertionError("token of " + n + " bytes accepted");
        if (t.indexOf('\0') >= 0) throw new AssertionError("a token with U+0000 accepted");
        if (ep.toString().contains(t) && t.length() >= 8) throw new AssertionError("toString shows the token");
        // What was read can be written and read back.
        Endpoint.write(file(), ep.controlPort(), t);
        if (!Endpoint.read(file()).equals(ep)) throw new AssertionError("write then read changed the endpoint");
    }

    @FuzzTest(maxDuration = "30m")
    void construct(FuzzedDataProvider d) throws Exception {
        int port = d.consumeInt();
        String token = d.consumeBoolean() ? ControlFuzz.MARK + d.consumeString(300) : d.consumeString(300);
        try {
            Endpoint ep = new Endpoint(port, token);
            Endpoint.write(file(), ep.controlPort(), token);
            if (!Endpoint.read(file()).equals(ep)) throw new AssertionError("write then read changed the endpoint");
        } catch (IllegalArgumentException e) {
            if (token.length() >= 8 && String.valueOf(e.getMessage()).contains(token)) {
                throw new AssertionError("the token is in an IllegalArgumentException message: " + e.getMessage());
            }
        } catch (IOException e) {
            // read back a token the reader refuses is checked just above; here only a failing disk gets here
            throw new AssertionError("a valid endpoint was written but not read: " + e, e);
        }
        // The constant-time comparison agrees with ordinary equality.
        byte[] a = token.getBytes(StandardCharsets.UTF_8);
        byte[] b = d.consumeBytes(300);
        if (Tokens.equal(a, b) != (a.length <= Tokens.MAX_BYTES && java.util.Arrays.equals(a, b))) {
            throw new AssertionError("Tokens.equal disagrees with Arrays.equals");
        }
        if (!d.consumeBoolean()) return;
        String other = d.consumeString(300);
        if (Tokens.equal(token, other) != (a.length <= Tokens.MAX_BYTES && token.equals(other))) {
            throw new AssertionError("Tokens.equal(String) disagrees with equals");
        }
    }
}
