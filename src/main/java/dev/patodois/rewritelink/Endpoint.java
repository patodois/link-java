package dev.patodois.rewritelink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The endpoint file of contract revision 6 (CONTRACT.md 6.2): how a host hands its control port and its token to a
 * guest that runs as the same user. The file holds {@code {"control_port":N,"token":"..."}}. A host that is given an
 * {@link LinkConfig#endpointFile} writes it after binding and removes it when it closes; a guest given one reads it
 * before every connection attempt. These helpers are public for applications that want to do it themselves.
 *
 * <p>Nothing here puts the token into an exception message, and {@link #toString()} hides it.
 */
public final class Endpoint {
    /** The largest endpoint file, in bytes. */
    public static final int MAX_FILE_BYTES = 4096;

    private final int controlPort;
    private final String token;

    /**
     * @param controlPort 1 to 65535
     * @param token 1 to 256 bytes of UTF-8, without U+0000
     * @throws IllegalArgumentException if one is outside its range
     */
    public Endpoint(int controlPort, String token) {
        if (controlPort < 1 || controlPort > 65535) throw new IllegalArgumentException("control_port must be 1 to 65535");
        if (token == null) throw new IllegalArgumentException("token must not be null");
        Tokens.requireUsable(token);
        this.controlPort = controlPort;
        this.token = token;
    }

    public int controlPort() {
        return controlPort;
    }

    public String token() {
        return token;
    }

    @Override
    public String toString() {
        return "Endpoint[controlPort=" + controlPort + ", token=<redacted>]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Endpoint && ((Endpoint) o).controlPort == controlPort && Tokens.equal(((Endpoint) o).token, token);
    }

    @Override
    public int hashCode() {
        return controlPort;
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    // ---------------------------------------------------------------------------------------------
    // Writing

    /**
     * Writes the endpoint file atomically: a temporary file in the same directory, created exclusively and, where the
     * file system has POSIX permissions, owner-only ({@code rw-------}) from the moment it exists, then renamed over
     * {@code path}, so a reader finds the old file or the complete new one. The directory must exist (it is not
     * created); on an error the temporary file is removed.
     *
     * @throws IllegalArgumentException if the port or the token is outside its range
     * @throws IOException if the file cannot be written; the message does not contain the token
     */
    public static void write(Path path, int controlPort, String token) throws IOException {
        Endpoint ep = new Endpoint(controlPort, token); // validates
        byte[] bytes = ("{\"control_port\":" + ep.controlPort + ",\"token\":" + Json.quote(ep.token) + "}\n")
                .getBytes(StandardCharsets.UTF_8);
        Path abs = path.toAbsolutePath();
        Path dir = abs.getParent();
        Path name = abs.getFileName();
        if (dir == null || name == null) throw new IOException("not a file path");
        Path tmp = dir.resolve("." + name + "." + processId() + "." + randomHex() + ".tmp");
        boolean created = false;
        try {
            Set<StandardOpenOption> opts = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            SeekableByteChannel ch = posix()
                    ? Files.newByteChannel(tmp, opts, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.newByteChannel(tmp, opts);
            created = true;
            try {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) ch.write(buf);
            } finally {
                ch.close();
            }
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("the file system cannot rename atomically", e);
            }
            created = false; // renamed: nothing left to remove
        } finally {
            if (created) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static String processId() {
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
            int at = n.indexOf('@');
            String pid = at > 0 ? n.substring(0, at) : n;
            for (int i = 0; i < pid.length(); i++) {
                if (pid.charAt(i) < '0' || pid.charAt(i) > '9') return "0";
            }
            return pid.isEmpty() ? "0" : pid;
        } catch (RuntimeException | LinkageError e) {
            return "0";
        }
    }

    private static String randomHex() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder(16);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Reading

    /**
     * Reads and checks an endpoint file. The path must name a regular file (symbolic links are followed; this is
     * checked before it is opened, since opening a FIFO would block); where the file system has POSIX permissions the
     * file must grant nothing to the group or to others; it has at most 4096 bytes and is one control-line JSON object
     * (CONTRACT.md 8, "Syntax of a line"), optionally followed by one line ending, with {@code control_port} an integer
     * 1 to 65535 and {@code token} a string of 1 to 256 bytes of UTF-8. Other fields are ignored. Where there are no
     * POSIX permissions (Windows) the permission check is skipped; the user's directory ACL protects the file.
     *
     * @throws IOException if the file is missing or refused; the message never contains the file's contents
     */
    public static Endpoint read(Path path) throws IOException {
        boolean regular;
        Set<PosixFilePermission> perms = null;
        if (posix()) {
            PosixFileAttributes a = Files.readAttributes(path, PosixFileAttributes.class);
            regular = a.isRegularFile();
            perms = a.permissions();
        } else {
            regular = Files.readAttributes(path, BasicFileAttributes.class).isRegularFile();
        }
        if (!regular) throw new IOException("the endpoint file is not a regular file");
        if (perms != null) {
            for (PosixFilePermission p : perms) {
                String n = p.name();
                if (n.startsWith("GROUP_") || n.startsWith("OTHERS_")) {
                    throw new IOException("the endpoint file is accessible to the group or to others");
                }
            }
        }
        byte[] raw = readAtMost(path, MAX_FILE_BYTES + 1);
        if (raw.length > MAX_FILE_BYTES) throw new IOException("the endpoint file is larger than " + MAX_FILE_BYTES + " bytes");
        int n = raw.length;
        if (n > 0 && raw[n - 1] == '\n') {
            n--;
            if (n > 0 && raw[n - 1] == '\r') n--;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw, 0, n)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("the endpoint file is not UTF-8");
        }
        if (text.indexOf('\n') >= 0) throw new IOException("the endpoint file is more than one line");
        Object v;
        try {
            v = Json.parse(text);
        } catch (Json.JsonException e) {
            throw new IOException("the endpoint file is not a JSON object"); // the parser's message could quote a character
        }
        if (!(v instanceof Map)) throw new IOException("the endpoint file is not a JSON object");
        Map<?, ?> o = (Map<?, ?>) v;
        Object port = o.get("control_port");
        if (!(port instanceof Long) || (Long) port < 1 || (Long) port > 65535) {
            throw new IOException("the endpoint file has no valid control_port");
        }
        Object token = o.get("token");
        if (!(token instanceof String) || !Tokens.validLength((String) token)) {
            throw new IOException("the endpoint file has no valid token");
        }
        return new Endpoint(((Long) port).intValue(), (String) token);
    }

    private static byte[] readAtMost(Path path, int max) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (out.size() < max) {
                int got = in.read(buf, 0, Math.min(buf.length, max - out.size()));
                if (got < 0) break;
                out.write(buf, 0, got);
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Removing

    /** Removes the endpoint file; best effort (a missing file or an error is ignored). */
    public static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }
}
