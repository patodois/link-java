package dev.patodois.rewritelink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.Wire.WireException;

/**
 * Runs the shared link test vectors ({@code tests/vectors/link}, see the README there) against this
 * implementation: control lines, datagrams, pixel files and scripted sessions over real sockets. It is a port of
 * the Rust reference runner ({@code tests/link_vectors.rs} of rewrite-bridge) and keeps its behaviour and its
 * step semantics.
 *
 * <p>The vectors are derived from {@code docs/CONTRACT.md} alone and are the same files the Rust, C and C#
 * implementations run, so a vector this class fails is a place where this implementation and the contract (or
 * another implementation) disagree. Every failing vector is listed together, with its id, section and basis, in
 * the one failure of its group.
 *
 * <p>{@link #KNOWN_DISAGREEMENTS} names vectors this implementation is known to fail, with the reason. They are
 * expected to fail: the run also fails when one of them passes, so the list cannot go stale.
 *
 * <p>{@code RBL_VECTORS_DIR} overrides the directory (the default is {@code tests/vectors/link}, found from the
 * working directory upwards); {@code RBL_VECTORS_SKIP_LARGE=1} skips the cases that need a sparse file of up to
 * 1 GiB.
 */
final class VectorTests {
    private VectorTests() {}

    /**
     * SHA-256 of {@code tests/vectors/link/SHA256SUMS}: the vectors can only change together with a deliberate edit
     * of this constant. To update the vectors, run {@code scripts/sync-link-vectors.sh <this repository>} of
     * rewrite-bridge (it copies the directory and prints {@code VECTORS_DIGEST = ...}) and paste the printed digest
     * here.
     */
    static final String VECTORS_DIGEST = "2cb6121126bd62ac753041c73a3ddb8d5c762c7adf0b749f7fa5672e22e90603";

    /** Vectors this implementation fails, id to reason. Empty when it passes them all. */
    static final Map<String, String> KNOWN_DISAGREEMENTS = new LinkedHashMap<>();

    private static void known(String id, String reason) {
        KNOWN_DISAGREEMENTS.put(id, reason);
    }

    private static final long STEP_WAIT_MS = 3000;
    private static final InetAddress LO = loopback();
    private static final Set<String> ALL_IDS = new HashSet<>();

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    /** A vector that failed: carries the text for the report. */
    static final class Mismatch extends Exception {
        private static final long serialVersionUID = 1L;

        Mismatch(String message) {
            super(message);
        }
    }

    static void run() {
        LinkTests.test("vectors: files match SHA256SUMS and the pinned digest", VectorTests::checksums);
        LinkTests.test("vectors: control.json", () -> controlVectors());
        LinkTests.test("vectors: datagrams.json", () -> datagramVectors());
        LinkTests.test("vectors: pixelfiles.json (readers)", () -> pixelReaderVectors());
        LinkTests.test("vectors: pixelfiles.json (writers)", () -> pixelWriterVectors());
        LinkTests.test("vectors: sessions.json", () -> sessionVectors());
        LinkTests.test("vectors: KNOWN_DISAGREEMENTS names existing vectors", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : KNOWN_DISAGREEMENTS.keySet()) {
                if (!ALL_IDS.contains(id)) missing.add(id);
            }
            LinkTests.check(missing.isEmpty(), "KNOWN_DISAGREEMENTS lists ids that no vector has: " + missing);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Loading, reporting

    private static Path dirCache;

    static synchronized Path vectorsDir() {
        if (dirCache != null) return dirCache;
        String env = System.getenv("RBL_VECTORS_DIR");
        if (env != null && !env.isEmpty()) {
            dirCache = Paths.get(env).toAbsolutePath();
            return dirCache;
        }
        Path d = Paths.get("").toAbsolutePath();
        while (d != null) {
            Path c = d.resolve("tests").resolve("vectors").resolve("link");
            if (Files.isDirectory(c)) {
                dirCache = c;
                return c;
            }
            d = d.getParent();
        }
        throw new IllegalStateException("tests/vectors/link not found from " + Paths.get("").toAbsolutePath()
                + " upwards; set RBL_VECTORS_DIR");
    }

    private static boolean skipLarge() {
        return "1".equals(System.getenv("RBL_VECTORS_SKIP_LARGE"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    static String str(Object o) {
        return (String) o;
    }

    /** An integer field: a JSON number. */
    static long num(Object o) {
        if (o instanceof Long) return (Long) o;
        throw new IllegalArgumentException("not an integer: " + o);
    }

    /** A u64: a decimal string (a JSON number is accepted too). */
    static long u64(Object o) {
        if (o instanceof String) return Long.parseUnsignedLong((String) o);
        return num(o);
    }

    static float f32(Object o) {
        if ("inf".equals(o)) return Float.POSITIVE_INFINITY;
        if (o instanceof Number) return ((Number) o).floatValue();
        throw new IllegalArgumentException("not a number: " + o);
    }

    static float[] f32s(Object o, int n) {
        List<Object> a = arr(o);
        if (a.size() != n) throw new IllegalArgumentException("expected " + n + " numbers, got " + a.size());
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = f32(a.get(i));
        return out;
    }

    static boolean isTrue(Object o) {
        return Boolean.TRUE.equals(o);
    }

    static byte[] unhex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 15, 16)).append(Character.forDigit(b[i] & 15, 16));
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static List<Map<String, Object>> load(String name) throws Exception {
        Path path = vectorsDir().resolve(name);
        Object doc = Json.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        Map<String, Object> top = obj(doc);
        LinkTests.eq(top.get("format"), "rbl-link-vectors/1", name + ": format");
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object c : arr(top.get("cases"))) {
            Map<String, Object> m = obj(c);
            cases.add(m);
            ALL_IDS.add(str(m.get("id")));
        }
        return cases;
    }

    static final class Report {
        final long startNs = System.nanoTime();
        int passed;
        final List<String> skipped = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> known = new ArrayList<>();

        /** {@code error} is null when the vector passed. */
        void record(Map<String, Object> c, String error) {
            String id = str(c.get("id"));
            Object b = c.get("basis");
            String basis = b == null ? "MUST" : str(b);
            Object s = c.get("section");
            String section = s == null ? "" : str(s);
            String why = KNOWN_DISAGREEMENTS.get(id);
            if (error == null && why == null) {
                passed++;
            } else if (error == null) {
                failed.add(id + ": now passes, remove it from KNOWN_DISAGREEMENTS (" + why + ")");
            } else if (why == null) {
                failed.add(id + " [section " + section + ", " + basis + "]: " + error);
            } else {
                known.add(id + ": " + error + " (known: " + why + ")");
            }
        }

        void record(Map<String, Object> c, Check check) {
            String error;
            try {
                check.run();
                error = null;
            } catch (Mismatch m) {
                error = m.getMessage();
            } catch (SkipCase skip) {
                skipped.add(c.get("id") + " (" + skip.getMessage() + ")");
                return;
            } catch (Throwable t) {
                StackTraceElement[] st = t.getStackTrace();
                error = "runner or library threw " + t + (st.length > 0 ? " at " + st[0] : "");
            }
            record(c, error);
        }

        void finish(String what) {
            System.out.println("     " + what + ": " + passed + " passed, " + known.size() + " known disagreements, "
                    + skipped.size() + " skipped, " + failed.size() + " failed ("
                    + (System.nanoTime() - startNs) / 1_000_000L + " ms)");
            for (String k : known) System.out.println("       known disagreement: " + k);
            for (String s : skipped) System.out.println("       skipped: " + s);
            if (!failed.isEmpty()) {
                throw new AssertionError(what + ": " + failed.size() + " vectors failed:\n  " + String.join("\n  ", failed));
            }
        }
    }

    @FunctionalInterface
    interface Check {
        void run() throws Exception;
    }

    // ---------------------------------------------------------------------------------------------
    // Checksums

    /** Every file of the vectors directory is what SHA256SUMS says, nothing else is there, and SHA256SUMS is the pinned one. */
    static void checksums() throws Exception {
        Path dir = vectorsDir();
        byte[] sums = Files.readAllBytes(dir.resolve("SHA256SUMS"));
        List<String> problems = new ArrayList<>();
        Set<String> listed = new TreeSet<>();
        for (String line : new String(sums, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            int sep = line.indexOf("  ");
            if (sep != 64) {
                problems.add("malformed SHA256SUMS line: " + line);
                continue;
            }
            String hash = line.substring(0, 64);
            String name = line.substring(66);
            listed.add(name);
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                problems.add(name + " is listed in SHA256SUMS but missing");
            } else if (!sha256(Files.readAllBytes(file)).equals(hash)) {
                problems.add(name + " differs from SHA256SUMS");
            }
        }
        if (listed.size() <= 10) problems.add("SHA256SUMS lists too little (" + listed.size() + " files)");
        // nothing unlisted: every file below the directory is listed, or is SHA256SUMS itself (gen.py only
        // exists in the rewrite-bridge directory, which RBL_VECTORS_DIR may point at)
        Set<String> have = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                have.add(dir.relativize(p).toString().replace('\\', '/'));
            }
        }
        for (String name : have) {
            if (!listed.contains(name) && !name.equals("SHA256SUMS") && !name.equals("gen.py")) {
                problems.add(name + " is in the directory but not listed in SHA256SUMS");
            }
        }
        String digest = sha256(sums);
        if (!digest.equals(VECTORS_DIGEST)) {
            problems.add("SHA256SUMS has digest " + digest + " but VectorTests.VECTORS_DIGEST is " + VECTORS_DIGEST
                    + " (to take new vectors: run scripts/sync-link-vectors.sh of rewrite-bridge and paste the printed digest)");
        }
        LinkTests.check(problems.isEmpty(), "the vectors are not the pinned ones:\n  " + String.join("\n  ", problems));
    }

    // ---------------------------------------------------------------------------------------------
    // control.json

    static Role roleOf(Object v) {
        Role r = Role.parse(str(v));
        if (r == null) throw new IllegalArgumentException("role " + v);
        return r;
    }

    static String optStr(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : str(v);
    }

    static Control controlFrom(Map<String, Object> m) {
        switch (str(m.get("type"))) {
            case "hello":
                return new Control.Hello(roleOf(m.get("role")), str(m.get("game")), (int) num(m.get("protocol")),
                        (int) num(m.get("min_protocol")), (int) num(m.get("udp_port")), num(m.get("heartbeat_ms")),
                        num(m.get("timeout_ms")), m.containsKey("session") ? Long.valueOf(num(m.get("session"))) : null);
            case "spawn":
                return new Control.Spawn(num(m.get("entity")), str(m.get("provider")), str(m.get("asset")), optStr(m, "name"));
            case "despawn":
                return new Control.Despawn(num(m.get("entity")));
            case "input":
                return new Control.Input(roleOf(m.get("focus")), optStr(m, "reason"));
            case "frame_channel":
                return new Control.FrameChannel(str(m.get("path")), num(m.get("width")), num(m.get("height")), num(m.get("slots")));
            case "bye":
                return new Control.Bye(str(m.get("reason")));
            default:
                throw new IllegalArgumentException("vector message of type " + m.get("type"));
        }
    }

    static void checkControl(Map<String, Object> c) throws Exception {
        String line;
        if (c.get("line") != null) {
            line = str(c.get("line"));
        } else {
            try {
                line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(unhex(str(c.get("line_hex"))))).toString();
            } catch (CharacterCodingException notUtf8) {
                return; // the reader refuses a line that is not UTF-8 before it parses it (sessions.json checks that path)
            }
        }
        Control parsed = null;
        WireException err = null;
        try {
            parsed = Control.parse(line);
        } catch (WireException e) {
            err = e;
        }
        switch (str(c.get("expect"))) {
            case "reject":
                if (err == null) throw new Mismatch("accepted " + quoted(line) + " as " + parsed);
                return;
            case "unknown":
                if (!(parsed instanceof Control.Unknown) || !((Control.Unknown) parsed).type().equals(str(c.get("type")))) {
                    throw new Mismatch(quoted(line) + " gave " + (err != null ? err.toString() : String.valueOf(parsed))
                            + ", expected the unknown type " + c.get("type"));
                }
                return;
            case "message": {
                Control want = controlFrom(obj(c.get("message")));
                if (parsed == null || !parsed.equals(want)) {
                    throw new Mismatch(quoted(line) + " gave " + (err != null ? err.toString() : String.valueOf(parsed)) + ", expected " + want);
                }
                if (isTrue(c.get("encode"))) {
                    String out = want.toLine();
                    if (!out.endsWith("\n") || out.substring(0, out.length() - 1).indexOf('\n') >= 0
                            || out.substring(0, out.length() - 1).indexOf('\r') >= 0) {
                        throw new Mismatch("encoded line is not a single line: " + quoted(out));
                    }
                    Object back;
                    try {
                        back = Json.parse(out.substring(0, out.length() - 1));
                    } catch (Json.JsonException e) {
                        throw new Mismatch("encoded " + quoted(out) + ": " + e.getMessage());
                    }
                    if (!c.get("message").equals(back)) {
                        throw new Mismatch("encoded " + quoted(out) + ", expected the object " + c.get("message"));
                    }
                }
                return;
            }
            default:
                throw new IllegalArgumentException("expect " + c.get("expect"));
        }
    }

    static String quoted(String s) {
        return Json.quote(s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }

    static void controlVectors() throws Exception {
        Report r = new Report();
        for (Map<String, Object> c : load("control.json")) r.record(c, () -> checkControl(c));
        r.finish("control.json");
    }

    // ---------------------------------------------------------------------------------------------
    // datagrams.json

    static Wire.Header headerOf(Map<String, Object> h) {
        return new Wire.Header((int) num(h.get("version")), h.containsKey("flags") ? (int) num(h.get("flags")) : 0,
                (int) num(h.get("session")), (int) num(h.get("seq")), u64(h.get("time_us")));
    }

    static Wire.Pose poseOf(Map<String, Object> p) {
        return new Wire.Pose(num(p.get("entity")), (int) num(p.get("flags")), u64(p.get("time_us")), f32s(p.get("translation"), 3),
                f32s(p.get("rotation"), 4), f32s(p.get("scale"), 3), f32s(p.get("velocity"), 3), f32s(p.get("angular_velocity"), 3));
    }

    static Wire.Camera cameraOf(Map<String, Object> b) throws WireException {
        return new Wire.Camera(f32s(b.get("view"), 16), f32s(b.get("proj"), 16), f32(b.get("fov_y")), f32(b.get("near")),
                f32(b.get("far")), (int) num(b.get("viewport_width")), (int) num(b.get("viewport_height")),
                Wire.DepthRange.of((int) num(b.get("depth_range"))));
    }

    static Wire.FrameReady frameReadyOf(Map<String, Object> b) {
        return new Wire.FrameReady(u64(b.get("frame")), (int) num(b.get("slot")), (int) num(b.get("camera_seq")),
                (int) num(b.get("width")), (int) num(b.get("height")));
    }

    static Wire.CollisionChunk collisionOf(Map<String, Object> b) {
        List<float[]> tris = new ArrayList<>();
        for (Object t : arr(b.get("triangles"))) tris.add(f32s(t, 9));
        return new Wire.CollisionChunk((int) num(b.get("query")), (int) num(b.get("chunk_index")), (int) num(b.get("chunk_count")),
                f32s(b.get("center"), 3), f32(b.get("radius")), tris);
    }

    static Wire.Datagram datagramOf(Map<String, Object> b) throws WireException {
        switch (str(b.get("kind"))) {
            case "heartbeat":
                return new Wire.Datagram.Heartbeat();
            case "camera":
                return new Wire.Datagram.CameraMsg(cameraOf(b));
            case "pose": {
                List<Wire.Pose> poses = new ArrayList<>();
                for (Object p : arr(b.get("poses"))) poses.add(poseOf(obj(p)));
                return new Wire.Datagram.PoseMsg(poses);
            }
            case "collision":
                return new Wire.Datagram.CollisionMsg(collisionOf(b));
            case "frame":
                return new Wire.Datagram.FrameMsg(frameReadyOf(b));
            default:
                throw new IllegalArgumentException("body kind " + b.get("kind"));
        }
    }

    static String kindOf(Wire.Datagram d) {
        if (d instanceof Wire.Datagram.Heartbeat) return "heartbeat";
        if (d instanceof Wire.Datagram.CameraMsg) return "camera";
        if (d instanceof Wire.Datagram.PoseMsg) return "pose";
        if (d instanceof Wire.Datagram.CollisionMsg) return "collision";
        if (d instanceof Wire.Datagram.FrameMsg) return "frame";
        return "unknown";
    }

    static void checkDatagram(Map<String, Object> c) throws Exception {
        byte[] bytes = unhex(str(c.get("hex")));
        Wire.Decoded got = null;
        WireException err = null;
        try {
            got = Wire.decode(bytes, bytes.length);
        } catch (WireException e) {
            err = e;
        }
        switch (str(c.get("expect"))) {
            case "reject":
                if (got != null) throw new Mismatch("accepted (" + c.get("why") + "): " + got);
                return;
            case "ignore": {
                if (got == null || !(got.body() instanceof Wire.Datagram.Unknown)
                        || ((Wire.Datagram.Unknown) got.body()).kind() != num(c.get("kind"))
                        || ((Wire.Datagram.Unknown) got.body()).payloadLen() != num(c.get("payload_len"))) {
                    throw new Mismatch("expected an unknown kind " + c.get("kind") + ", got " + (got != null ? got.toString() : String.valueOf(err)));
                }
                return;
            }
            case "accept": {
                // revision 5: a datagram of a known kind whose values the decoder does not check; only the kind is compared
                if (got == null) throw new Mismatch("rejected (" + c.get("why") + "): " + err.getMessage());
                String want = str(c.get("kind"));
                String have = kindOf(got.body());
                if (!want.equals(have)) throw new Mismatch("decoded as " + have + ", expected " + want);
                return;
            }
            case "decode": {
                if (got == null) throw new Mismatch("rejected: " + err.getMessage());
                Map<String, Object> hm = obj(c.get("header"));
                Wire.Header want = headerOf(hm);
                Wire.Header h = got.header();
                if (h.version() != want.version() || h.session() != want.session() || h.seq() != want.seq() || h.timeUs() != want.timeUs()
                        || (hm.containsKey("flags") && h.flags() != want.flags())) {
                    throw new Mismatch("header " + h + ", expected " + want);
                }
                Wire.Datagram wantBody = datagramOf(obj(c.get("body")));
                if (!got.body().equals(wantBody)) throw new Mismatch("body " + got.body() + ", expected " + wantBody);
                if (isTrue(c.get("encode"))) {
                    byte[] out;
                    try {
                        out = Wire.encode(want, wantBody);
                    } catch (WireException e) {
                        throw new Mismatch("encode failed: " + e.getMessage());
                    }
                    if (!Arrays.equals(out, bytes)) throw new Mismatch("encoded " + hex(out) + ", expected " + hex(bytes));
                }
                return;
            }
            default:
                throw new IllegalArgumentException("expect " + c.get("expect"));
        }
    }

    static void datagramVectors() throws Exception {
        Report r = new Report();
        for (Map<String, Object> c : load("datagrams.json")) r.record(c, () -> checkDatagram(c));
        r.finish("datagrams.json");
    }

    // ---------------------------------------------------------------------------------------------
    // pixelfiles.json

    static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all = walk.sorted(Collections.reverseOrder()).collect(Collectors.toList());
            for (Path p : all) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a temporary file that stays is the temp directory's business
                }
            }
        } catch (IOException ignored) {
            // nothing to clean
        }
    }

    static void copyFixture(Map<String, Object> c, Path to) throws IOException {
        Files.copy(vectorsDir().resolve(str(c.get("file"))), to, StandardCopyOption.REPLACE_EXISTING);
    }

    static void makeRecipe(Map<String, Object> recipe, Path to) throws IOException {
        byte[] header = unhex(str(recipe.get("header_hex")));
        try (RandomAccessFile f = new RandomAccessFile(to.toFile(), "rw")) {
            f.setLength(num(recipe.get("length")));
            f.seek(0);
            f.write(header);
        }
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    static void checkRead(PixelFile.Reader reader, Map<String, Object> rd) throws Mismatch {
        boolean wantDepth = isTrue(rd.get("want_depth"));
        PixelFile.Frame got = reader.read(u64(rd.get("frame")), (int) num(rd.get("slot")), (int) num(rd.get("width")),
                (int) num(rd.get("height")), wantDepth);
        switch (str(rd.get("expect"))) {
            case "skip":
                if (got != null) throw new Mismatch("delivered " + got + " (" + rd.get("why") + ")");
                return;
            case "ok": {
                if (got == null) throw new Mismatch("frame " + rd.get("frame") + " slot " + rd.get("slot") + " skipped");
                long[][] pairs = {
                    {u64(rd.get("frame")), got.frame()}, {u64(rd.get("time_us")), got.timeUs()},
                    {num(rd.get("camera_seq")), Integer.toUnsignedLong(got.cameraSeq())},
                    {num(rd.get("width")), got.width()}, {num(rd.get("height")), got.height()}};
                String[] names = {"frame", "time_us", "camera_seq", "width", "height"};
                for (int i = 0; i < pairs.length; i++) {
                    if (pairs[i][0] != pairs[i][1]) {
                        throw new Mismatch(names[i] + " is " + Long.toUnsignedString(pairs[i][1]) + ", expected " + Long.toUnsignedString(pairs[i][0]));
                    }
                }
                if (rd.get("rgba") != null) {
                    if (!hex(got.rgba()).equals(str(rd.get("rgba")))) throw new Mismatch("colour bytes differ");
                } else if (!sha256(got.rgba()).equals(str(rd.get("rgba_sha256")))) {
                    throw new Mismatch("colour bytes differ (sha256)");
                }
                if (rd.get("depth") == null) {
                    if (got.depth() != null && got.depth().length != 0) throw new Mismatch(got.depth().length + " depth values, expected none");
                } else {
                    List<Object> want = arr(rd.get("depth"));
                    float[] d = got.depth();
                    boolean same = d != null && d.length == want.size();
                    for (int i = 0; same && i < d.length; i++) same = Float.floatToRawIntBits(d[i]) == Float.floatToRawIntBits(f32(want.get(i)));
                    if (!same) throw new Mismatch("depth " + (d == null ? "null" : Arrays.toString(d)) + ", expected " + want);
                }
                return;
            }
            default:
                throw new IllegalArgumentException("read expect " + rd.get("expect"));
        }
    }

    /** Opens the case's file as the receiver of a frame_channel would and runs its reads. */
    static void checkPixelReader(Map<String, Object> c) throws Exception {
        Path tmp = Files.createTempDirectory("rbl-vectors-pf-");
        PixelFile.Reader reader = null;
        try {
            Path path = tmp.resolve("pixel.frames");
            Path openPath;
            Object kind = c.get("path_kind");
            if (kind == null) {
                if (c.get("recipe") != null) makeRecipe(obj(c.get("recipe")), path);
                else copyFixture(c, path);
                openPath = path;
            } else {
                switch (str(kind)) {
                    case "relative": {
                        // A valid copy sits in the working directory; only the relative spelling may refuse it.
                        String name = "rbl-vector-relative-" + System.nanoTime() + ".frames";
                        copyFixture(c, Paths.get(name).toAbsolutePath());
                        try {
                            PixelFile.Reader r = PixelFile.Reader.open(Paths.get(name));
                            r.close();
                            throw new Mismatch("opened a relative path");
                        } catch (IOException refused) {
                            return;
                        } finally {
                            Files.deleteIfExists(Paths.get(name).toAbsolutePath());
                        }
                    }
                    case "missing":
                        openPath = tmp.resolve("does-not-exist.frames");
                        break;
                    case "empty":
                        openPath = Paths.get("");
                        break;
                    case "directory":
                        openPath = tmp.resolve("a-directory");
                        Files.createDirectory(openPath);
                        break;
                    case "symlink":
                        copyFixture(c, path);
                        openPath = tmp.resolve("link.frames");
                        try {
                            Files.createSymbolicLink(openPath, path);
                        } catch (UnsupportedOperationException | IOException cannot) {
                            throw new SkipCase("cannot create a symlink here: " + cannot);
                        }
                        break;
                    case "fifo":
                        openPath = tmp.resolve("fifo.frames");
                        if (windows() || !makeFifo(openPath)) throw new SkipCase("cannot create a FIFO here");
                        break;
                    default:
                        throw new IllegalArgumentException("path_kind " + kind);
                }
            }
            PixelFile.Reader opened = null;
            IOException refused = null;
            try {
                opened = PixelFile.Reader.open(openPath);
            } catch (IOException e) {
                refused = e;
            }
            reader = opened;
            switch (str(c.get("open"))) {
                case "refused":
                    if (opened != null) throw new Mismatch("opened the file (" + c.get("why") + ")");
                    return;
                case "ok": {
                    if (opened == null) throw new Mismatch("refused: " + refused);
                    Object infoObj = c.get("info");
                    if (infoObj != null) {
                        Map<String, Object> info = obj(infoObj);
                        long[][] pairs = {{num(info.get("slots")), opened.slots}, {num(info.get("width")), opened.width},
                            {num(info.get("height")), opened.height}, {num(info.get("stride")), opened.stride},
                            {num(info.get("slot_size")), opened.slotSize}};
                        String[] names = {"slots", "width", "height", "stride", "slot_size"};
                        for (int i = 0; i < pairs.length; i++) {
                            if (pairs[i][0] != pairs[i][1]) throw new Mismatch(names[i] + " is " + pairs[i][1] + ", expected " + pairs[i][0]);
                        }
                        if (opened.hasDepth() != isTrue(info.get("has_depth"))) {
                            throw new Mismatch("has_depth is " + opened.hasDepth() + ", expected " + info.get("has_depth"));
                        }
                    }
                    Object reads = c.get("reads");
                    if (reads != null) {
                        for (Object rd : arr(reads)) {
                            Map<String, Object> m = obj(rd);
                            try {
                                checkRead(opened, m);
                            } catch (Mismatch e) {
                                throw new Mismatch("read of frame " + m.get("frame") + " slot " + m.get("slot") + ": " + e.getMessage());
                            }
                        }
                    }
                    Object then = c.get("then");
                    if (then != null) {
                        for (Object st : arr(then)) {
                            Map<String, Object> step = obj(st);
                            switch (str(step.get("op"))) {
                                case "truncate":
                                    try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw")) {
                                        f.setLength(num(step.get("length")));
                                    }
                                    break;
                                case "read":
                                    try {
                                        checkRead(opened, step);
                                    } catch (Mismatch e) {
                                        throw new Mismatch("after truncation: " + e.getMessage());
                                    }
                                    break;
                                default:
                                    throw new IllegalArgumentException("then op " + step.get("op"));
                            }
                        }
                    }
                    return;
                }
                default:
                    throw new IllegalArgumentException("open " + c.get("open"));
            }
        } finally {
            if (reader != null) reader.close();
            deleteTree(tmp);
        }
    }

    /** A case this platform cannot run. */
    static final class SkipCase extends Exception {
        private static final long serialVersionUID = 1L;

        SkipCase(String message) {
            super(message);
        }
    }

    static boolean makeFifo(Path p) {
        try {
            Process pr = new ProcessBuilder("mkfifo", p.toString()).redirectErrorStream(true).start();
            pr.getInputStream().close();
            return pr.waitFor() == 0 && Files.exists(p);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    static void pixelReaderVectors() throws Exception {
        Report r = new Report();
        for (Map<String, Object> c : load("pixelfiles.json")) {
            if (c.get("writer") != null) continue;
            if (isTrue(c.get("large")) && skipLarge()) {
                r.skipped.add(c.get("id") + " (large)");
                continue;
            }
            if (windows() && c.get("skip_on") != null && arr(c.get("skip_on")).contains("windows")) {
                r.skipped.add(c.get("id") + " (windows)");
                continue;
            }
            r.record(c, () -> checkPixelReader(c));
        }
        r.finish("pixelfiles.json (readers)");
    }

    static void checkPixelWriter(Map<String, Object> c) throws Exception {
        Map<String, Object> w = obj(c.get("writer"));
        Path tmp = Files.createTempDirectory("rbl-vectors-pw-");
        PixelFile.Writer writer = null;
        try {
            Path path = tmp.resolve("writer.frames");
            int width = (int) num(w.get("width")), height = (int) num(w.get("height")), slots = (int) num(w.get("slots"));
            boolean withDepth = isTrue(w.get("with_depth"));
            Object expect = c.get("expect");
            if ("create_fails_and_file_untouched".equals(expect)) {
                byte[] precious = "precious".getBytes(StandardCharsets.US_ASCII);
                Files.write(path, precious);
                try {
                    new PixelFile.Writer(path, width, height, slots, withDepth).close();
                    throw new Mismatch("created a writer over an existing file");
                } catch (IOException | IllegalArgumentException refused) {
                    // expected
                }
                if (!Arrays.equals(Files.readAllBytes(path), precious)) throw new Mismatch("the existing file was changed");
                return;
            }
            if ("create_fails".equals(expect)) {
                try {
                    new PixelFile.Writer(path, width, height, slots, withDepth).close();
                } catch (IOException | IllegalArgumentException refused) {
                    if (Files.exists(path)) throw new Mismatch("failed but left a file");
                    return;
                }
                throw new Mismatch("created a writer (" + c.get("why") + ")");
            }
            try {
                writer = new PixelFile.Writer(path, width, height, slots, withDepth);
            } catch (IOException | IllegalArgumentException e) {
                throw new Mismatch("create failed: " + e);
            }
            int i = 0;
            long done = 0;
            for (Object wo : arr(w.get("writes"))) {
                Map<String, Object> wr = obj(wo);
                byte[] rgba = unhex(str(wr.get("rgba")));
                float[] depth = wr.get("depth") == null ? null : f32s(wr.get("depth"), arr(wr.get("depth")).size());
                boolean refused = "refused".equals(wr.get("expect"));
                long frame = 0;
                RuntimeException failure = null;
                try {
                    frame = writer.write(rgba, depth, (int) num(wr.get("width")), (int) num(wr.get("height")),
                            (int) num(wr.get("camera_seq")), u64(wr.get("time_us")));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    failure = e;
                }
                if (refused) {
                    // revision 5: the write must fail and change nothing (no frame number, no slot touched)
                    if (failure == null) throw new Mismatch("write " + i + " was accepted as frame " + frame + " (" + wr.get("why") + ")");
                } else {
                    if (failure != null) throw new Mismatch("write " + i + " failed: " + failure);
                    done++;
                    if (frame != done) throw new Mismatch("write " + i + " numbered the frame " + frame + ", expected " + done);
                }
                i++;
            }
            byte[] got = Files.readAllBytes(path);
            byte[] want = Files.readAllBytes(vectorsDir().resolve(str(c.get("file"))));
            if (!Arrays.equals(got, want)) {
                int at = -1;
                for (int k = 0; k < Math.min(got.length, want.length); k++) {
                    if (got[k] != want[k]) {
                        at = k;
                        break;
                    }
                }
                throw new Mismatch("file bytes differ (lengths " + got.length + " and " + want.length + ", first difference at " + at + ")");
            }
        } finally {
            if (writer != null) writer.close();
            deleteTree(tmp);
        }
    }

    static void pixelWriterVectors() throws Exception {
        Report r = new Report();
        for (Map<String, Object> c : load("pixelfiles.json")) {
            if (c.get("writer") != null) r.record(c, () -> checkPixelWriter(c));
        }
        r.finish("pixelfiles.json (writers)");
    }

    // ---------------------------------------------------------------------------------------------
    // sessions.json

    /** A raw TCP peer connection: reads with a 1 ms timeout so the UT can be polled in between. */
    static final class Raw {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        byte[] buf = new byte[4096];
        int len;
        boolean eof;

        Raw(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(1);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        /** Reads what is available into the buffer. */
        void fill() {
            byte[] tmp = new byte[16384];
            while (!eof) {
                try {
                    int n = in.read(tmp);
                    if (n < 0) {
                        eof = true;
                        return;
                    }
                    if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
                    System.arraycopy(tmp, 0, buf, len, n);
                    len += n;
                } catch (SocketTimeoutException none) {
                    return;
                } catch (IOException reset) {
                    eof = true;
                    return;
                }
            }
        }

        /** The next complete line with its terminator, or null. */
        byte[] takeLine() {
            for (int i = 0; i < len; i++) {
                if (buf[i] == '\n') {
                    byte[] line = Arrays.copyOf(buf, i + 1);
                    System.arraycopy(buf, i + 1, buf, 0, len - i - 1);
                    len -= i + 1;
                    return line;
                }
            }
            return null;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    static final class Ev {
        final String type;
        final Map<String, Object> data;

        Ev(String type, Map<String, Object> data) {
            this.type = type;
            this.data = data;
        }
    }

    @FunctionalInterface
    interface Poll<T> {
        T get() throws Exception;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Ev eventOf(LinkEvent e) {
        if (e instanceof LinkEvent.Connected) {
            LinkEvent.PeerInfo p = ((LinkEvent.Connected) e).peer();
            return new Ev("connected", map("role", p.role().wire, "game", p.game(), "protocol", (long) p.protocol(),
                    "session", Integer.toUnsignedLong(p.session()), "heartbeat_ms", p.heartbeatMs()));
        }
        if (e instanceof LinkEvent.Lost) return new Ev("lost", map());
        if (e instanceof LinkEvent.HandshakeFailed) return new Ev("handshake_failed", map());
        if (e instanceof LinkEvent.Camera) {
            LinkEvent.Camera c = (LinkEvent.Camera) e;
            return new Ev("camera", map("seq", Integer.toUnsignedLong(c.header().seq()),
                    "viewport_width", Integer.toUnsignedLong(c.camera().viewportWidth()),
                    "viewport_height", Integer.toUnsignedLong(c.camera().viewportHeight()),
                    "depth_range", (long) c.camera().depthRange().code));
        }
        if (e instanceof LinkEvent.Poses) {
            List<Wire.Pose> poses = ((LinkEvent.Poses) e).poses();
            List<Object> entities = new ArrayList<>();
            for (Wire.Pose p : poses) entities.add(p.entity());
            return new Ev("poses", map("count", (long) poses.size(), "entities", entities));
        }
        if (e instanceof LinkEvent.Collision) {
            LinkEvent.Collision c = (LinkEvent.Collision) e;
            List<Object> center = new ArrayList<>();
            for (float v : c.center()) center.add((double) v);
            return new Ev("collision", map("query", Integer.toUnsignedLong(c.query()), "triangles", (long) c.triangles().size(),
                    "center", center, "radius", (double) c.radius()));
        }
        if (e instanceof LinkEvent.Frame) {
            Wire.FrameReady f = ((LinkEvent.Frame) e).frame();
            return new Ev("frame", map("frame", Long.toUnsignedString(f.frame()), "slot", Integer.toUnsignedLong(f.slot()),
                    "camera_seq", Integer.toUnsignedLong(f.cameraSeq()), "width", Integer.toUnsignedLong(f.width()),
                    "height", Integer.toUnsignedLong(f.height())));
        }
        if (e instanceof LinkEvent.Spawn) {
            Control.Spawn s = ((LinkEvent.Spawn) e).spawn();
            Map<String, Object> m = map("entity", s.entity(), "provider", s.provider(), "asset", s.asset());
            if (s.name() != null) m.put("name", s.name());
            return new Ev("spawn", m);
        }
        if (e instanceof LinkEvent.Despawn) return new Ev("despawn", map("entity", ((LinkEvent.Despawn) e).entity()));
        if (e instanceof LinkEvent.Focus) {
            LinkEvent.Focus f = (LinkEvent.Focus) e;
            return new Ev("focus", map("focus", f.focus().wire, "requested", f.requested()));
        }
        if (e instanceof LinkEvent.FrameChannel) {
            Control.FrameChannel c = ((LinkEvent.FrameChannel) e).channel();
            return new Ev("frame_channel", map("path", c.path(), "width", c.width(), "height", c.height(), "slots", c.slots()));
        }
        return new Ev("other", map());
    }

    /** {@code pattern} is a literal, or a string {@code $name} pattern. */
    static boolean patternOk(Object actual, Object pattern) {
        if (pattern instanceof String && ((String) pattern).startsWith("$")) {
            Long n = actual instanceof Long ? (Long) actual : null;
            switch ((String) pattern) {
                case "$string":
                    return actual instanceof String;
                case "$int":
                    return n != null && n >= 0;
                case "$u32nz":
                    return n != null && n >= 1 && n <= 4294967295L;
                case "$u16nz":
                    return n != null && n >= 1 && n <= 65535;
                case "$hb":
                    return n != null && n >= 10 && n <= 10000;
                default:
                    throw new IllegalArgumentException("pattern " + pattern);
            }
        }
        if (pattern instanceof Number && actual instanceof Number) return ((Number) pattern).doubleValue() == ((Number) actual).doubleValue();
        if (pattern instanceof List && actual instanceof List) {
            List<?> p = (List<?>) pattern, a = (List<?>) actual;
            if (p.size() != a.size()) return false;
            for (int i = 0; i < p.size(); i++) if (!patternOk(a.get(i), p.get(i))) return false;
            return true;
        }
        return pattern == null ? actual == null : pattern.equals(actual);
    }

    static void matchOk(Map<String, Object> actual, Map<String, Object> match) throws Mismatch {
        for (Map.Entry<String, Object> e : match.entrySet()) {
            if (!actual.containsKey(e.getKey()) || !patternOk(actual.get(e.getKey()), e.getValue())) {
                throw new Mismatch("key " + Json.quote(e.getKey()) + ": expected " + e.getValue() + ", got "
                        + (actual.containsKey(e.getKey()) ? String.valueOf(actual.get(e.getKey())) : "nothing") + " in " + actual);
            }
        }
    }

    static String le32(long v) {
        long x = v & 0xFFFFFFFFL;
        return hex(new byte[] {(byte) x, (byte) (x >> 8), (byte) (x >> 16), (byte) (x >> 24)});
    }

    static final class Session {
        final Link ut;
        final List<Ev> events = new ArrayList<>();
        final Map<String, Raw> conns = new HashMap<>();
        final ServerSocket listener;
        final DatagramSocket udp;
        DatagramSocket other;
        final Map<String, String> vars = new HashMap<>();
        final int utUdp;
        final Map<String, Map<String, Object>> datagrams;

        Session(Map<String, Object> c, Map<String, Map<String, Object>> datagrams) throws Exception {
            Map<String, Object> cfg = c.get("config") == null ? new HashMap<String, Object>() : obj(c.get("config"));
            this.datagrams = datagrams;
            udp = new DatagramSocket(0, LO);
            udp.setSoTimeout(1);
            boolean host = "host".equals(c.get("role_under_test"));
            LinkConfig lc = new LinkConfig("vector ut");
            lc.heartbeatMs = cfg.containsKey("heartbeat_ms") ? num(cfg.get("heartbeat_ms")) : 250;
            lc.timeoutMs = cfg.containsKey("timeout_ms") ? num(cfg.get("timeout_ms")) : 2000;
            lc.reconnectMs = cfg.containsKey("reconnect_ms") ? num(cfg.get("reconnect_ms")) : 200;
            if (host) {
                listener = null;
                lc.controlPort = 0;
                ut = Link.host(lc);
            } else {
                listener = new ServerSocket(0, 50, LO);
                listener.setSoTimeout(1);
                lc.controlPort = listener.getLocalPort();
                ut = Link.guest(lc);
            }
            utUdp = ut.udpPort();
            vars.put("peer_udp_port", String.valueOf(udp.getLocalPort()));
            vars.put("ut_udp_port", String.valueOf(utUdp));
        }

        void close() {
            for (Raw r : conns.values()) r.close();
            conns.clear();
            udp.close();
            if (other != null) other.close();
            try {
                if (listener != null) listener.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            ut.close();
        }

        void pump() {
            for (LinkEvent e : ut.poll()) events.add(eventOf(e));
        }

        /** Polls the UT until {@code f} returns something (not null), for at most {@code ms}; null on timeout. */
        <T> T await(long ms, Poll<T> f) throws Exception {
            long end = System.nanoTime() + ms * 1_000_000L;
            while (true) {
                pump();
                T v = f.get();
                if (v != null) return v;
                if (System.nanoTime() >= end) return null;
                Thread.sleep(1);
            }
        }

        String subst(String s) {
            String out = s;
            for (Map.Entry<String, String> e : vars.entrySet()) out = out.replace("${" + e.getKey() + "}", e.getValue());
            return out;
        }

        long sessionVar() {
            String s = vars.get("session");
            if (s == null) throw new IllegalStateException("a session variable is needed here");
            return Long.parseLong(s);
        }

        String substHex(String s) {
            String out = subst(s);
            if (out.contains("{session_le")) {
                long session = sessionVar();
                out = out.replace("{session_le}", le32(session)).replace("{session_le_plus1}", le32(session + 1))
                        .replace("{session_le_minus1}", le32(session - 1));
            }
            return out;
        }

        static String connName(Map<String, Object> step) {
            return step.get("conn") == null ? "a" : str(step.get("conn"));
        }

        Raw conn(Map<String, Object> step) {
            Raw r = conns.get(connName(step));
            if (r == null) throw new IllegalStateException("no connection " + connName(step));
            return r;
        }

        void writeAll(Map<String, Object> step, byte[] data) throws Mismatch {
            Raw r = conn(step);
            try {
                r.out.write(data);
                r.out.flush();
            } catch (IOException e) {
                throw new Mismatch("write failed: " + e);
            }
        }

        void run(List<Object> steps) throws Exception {
            int n = 0;
            for (Object so : steps) {
                Map<String, Object> step = obj(so);
                String op = str(step.get("op"));
                try {
                    step(op, step);
                } catch (Mismatch m) {
                    throw new Mismatch("step " + n + " (" + op + "): " + m.getMessage());
                }
                n++;
            }
        }

        void step(String op, Map<String, Object> step) throws Exception {
            switch (op) {
                case "connect": {
                    Socket s = new Socket(LO, ut.controlPort());
                    conns.put(connName(step), new Raw(s));
                    break;
                }
                case "accept": {
                    Socket s = await(STEP_WAIT_MS, () -> {
                        try {
                            return listener.accept();
                        } catch (SocketTimeoutException none) {
                            return null;
                        }
                    });
                    if (s == null) throw new Mismatch("the guest did not connect");
                    conns.put(connName(step), new Raw(s));
                    break;
                }
                case "send_line":
                    writeAll(step, subst(str(step.get("text"))).getBytes(StandardCharsets.UTF_8));
                    break;
                case "send_hex":
                    writeAll(step, unhex(str(step.get("hex"))));
                    break;
                case "send_fill": {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    b.write(str(step.get("prefix")).getBytes(StandardCharsets.UTF_8));
                    byte fill = (byte) str(step.get("fill")).charAt(0);
                    for (long i = num(step.get("count")); i > 0; i--) b.write(fill);
                    b.write(str(step.get("suffix")).getBytes(StandardCharsets.UTF_8));
                    writeAll(step, b.toByteArray());
                    break;
                }
                case "send_range": {
                    String t = str(step.get("template"));
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    for (long i = num(step.get("from")); i <= num(step.get("to")); i++) {
                        b.write(subst(t.replace("${i}", Long.toString(i))).getBytes(StandardCharsets.UTF_8));
                    }
                    writeAll(step, b.toByteArray());
                    break;
                }
                case "expect_line":
                    expectLine(step);
                    break;
                case "expect_no_line": {
                    Raw raw = conn(step);
                    String bad = await(num(step.get("ms")), () -> {
                        raw.fill();
                        byte[] l = raw.takeLine();
                        if (l != null) return "a line arrived: " + quoted(new String(l, StandardCharsets.UTF_8));
                        return raw.eof ? "the connection closed" : null;
                    });
                    if (bad != null) throw new Mismatch(bad);
                    break;
                }
                case "expect_closed": {
                    boolean allow = isTrue(step.get("allow_lines"));
                    long within = step.get("within_ms") == null ? 3000 : num(step.get("within_ms"));
                    Raw raw = conn(step);
                    String r = await(within, () -> {
                        raw.fill();
                        if (!allow) {
                            byte[] l = raw.takeLine();
                            if (l != null) return "a line arrived before end of stream: " + quoted(new String(l, StandardCharsets.UTF_8));
                        }
                        return raw.eof ? "" : null;
                    });
                    if (r == null) throw new Mismatch("the connection stayed open");
                    if (!r.isEmpty()) throw new Mismatch(r);
                    break;
                }
                case "close": {
                    Raw raw = conn(step);
                    if (isTrue(step.get("half"))) {
                        try {
                            raw.socket.shutdownOutput();
                        } catch (IOException ignored) {
                            // already closed
                        }
                    } else {
                        raw.close();
                        conns.remove(connName(step));
                    }
                    break;
                }
                case "set":
                    for (Map.Entry<String, Object> e : obj(step.get("vars")).entrySet()) {
                        vars.put(e.getKey(), e.getValue() instanceof Long ? e.getValue().toString() : str(e.getValue()));
                    }
                    break;
                case "sleep":
                    await(num(step.get("ms")), () -> null);
                    break;
                case "send_datagram": {
                    byte[] b = unhex(substHex(str(step.get("hex"))));
                    DatagramSocket from = udp;
                    if ("other".equals(step.get("from"))) {
                        if (other == null) other = new DatagramSocket(0, LO);
                        from = other;
                    }
                    from.send(new DatagramPacket(b, b.length, LO, utUdp));
                    break;
                }
                case "expect_datagram":
                    expectDatagram(step);
                    break;
                case "expect_no_datagram": {
                    byte[] buf = new byte[16384];
                    String bad = await(num(step.get("ms")), () -> {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            udp.receive(p);
                        } catch (SocketTimeoutException none) {
                            return null;
                        }
                        return "a " + p.getLength() + " byte datagram arrived from " + p.getSocketAddress();
                    });
                    if (bad != null) throw new Mismatch(bad);
                    break;
                }
                case "expect_focus": {
                    pump();
                    String got = ut.focus().wire;
                    if (!got.equals(str(step.get("focus")))) throw new Mismatch("focus is " + got + ", expected " + step.get("focus"));
                    break;
                }
                case "ut":
                    utDo(step);
                    break;
                case "expect_event": {
                    String ty = str(step.get("event"));
                    int count = step.get("count") == null ? 1 : (int) num(step.get("count"));
                    Boolean ok = await(STEP_WAIT_MS, () -> {
                        int have = 0;
                        for (Ev e : events) if (e.type.equals(ty)) have++;
                        return have >= count ? Boolean.TRUE : null;
                    });
                    if (ok == null) {
                        List<String> have = new ArrayList<>();
                        for (Ev e : events) have.add(e.type);
                        throw new Mismatch("no " + count + " " + ty + " event(s); queue holds " + have);
                    }
                    int taken = 0;
                    for (int i = 0; taken < count; ) {
                        if (events.get(i).type.equals(ty)) {
                            Ev e = events.remove(i);
                            if (step.get("match") != null) {
                                try {
                                    matchOk(e.data, obj(step.get("match")));
                                } catch (Mismatch m) {
                                    throw new Mismatch(ty + " event " + taken + ": " + m.getMessage());
                                }
                            }
                            taken++;
                        } else {
                            i++;
                        }
                    }
                    break;
                }
                case "expect_no_event": {
                    await(num(step.get("ms")), () -> null);
                    String ty = str(step.get("event"));
                    for (Ev e : events) if (e.type.equals(ty)) throw new Mismatch("unexpected " + ty + " event " + e.data);
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown op " + op);
            }
        }

        void expectLine(Map<String, Object> step) throws Exception {
            Raw raw = conn(step);
            byte[] line = await(STEP_WAIT_MS, () -> {
                raw.fill();
                return raw.takeLine();
            });
            if (line == null) throw new Mismatch("no line from the implementation");
            String text = new String(line, StandardCharsets.UTF_8);
            String trimmed = text.replaceAll("[ \t\r\n]+$", "");
            Object parsed;
            try {
                parsed = Json.parse(trimmed);
            } catch (Json.JsonException e) {
                throw new Mismatch("not JSON: " + quoted(text) + ": " + e.getMessage());
            }
            if (!(parsed instanceof Map)) throw new Mismatch("not an object: " + quoted(text));
            Map<String, Object> v = obj(parsed);
            Map<String, Object> match = obj(step.get("match"));
            try {
                matchOk(v, match);
            } catch (Mismatch m) {
                throw new Mismatch("line " + quoted(text) + ": " + m.getMessage());
            }
            if (isTrue(step.get("exact"))) {
                Set<String> got = new TreeSet<>(v.keySet());
                Set<String> want = new TreeSet<>(match.keySet());
                if (!got.equals(want)) throw new Mismatch("keys " + got + ", expected exactly " + want);
            }
            Object cap = step.get("capture");
            if (cap != null) {
                for (Map.Entry<String, Object> e : obj(cap).entrySet()) {
                    Object val = v.get(str(e.getValue()));
                    if (!(val instanceof Long)) throw new Mismatch("captured value is not an integer");
                    String s = val.toString();
                    if (e.getKey().equals("ut_udp_port") && !s.equals(String.valueOf(utUdp))) {
                        throw new Mismatch("hello announces udp_port " + s + ", the socket is " + utUdp);
                    }
                    vars.put(e.getKey(), s);
                }
            }
        }

        void expectDatagram(Map<String, Object> step) throws Exception {
            long session = sessionVar();
            long timeout = step.get("timeout_ms") == null ? 3000 : num(step.get("timeout_ms"));
            int count = step.get("count") == null ? 1 : (int) num(step.get("count"));
            byte[] like = null;
            if (step.get("like") != null) {
                Map<String, Object> dg = datagrams.get(str(step.get("like")));
                if (dg == null) throw new IllegalArgumentException("no datagram case " + step.get("like"));
                like = unhex(str(dg.get("hex")));
            }
            int kind = -1;
            if (step.get("kind") != null) kind = (int) num(step.get("kind"));
            else if (like != null) kind = like[6] & 0xFF;
            final int wantKind = kind;
            final byte[] wantLike = like;
            List<Long> seqs = new ArrayList<>();
            byte[] buf = new byte[16384];
            String r = await(timeout, () -> {
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        udp.receive(p);
                    } catch (SocketTimeoutException none) {
                        return null;
                    }
                    int n = p.getLength();
                    if (p.getPort() != utUdp) return "datagram from " + p.getSocketAddress() + ", the UT's port is " + utUdp;
                    if (n < 24 || buf[0] != 'R' || buf[1] != 'B' || buf[2] != 'L' || buf[3] != 'K' || buf[4] != 1 || buf[5] != 0) {
                        return "bad header in " + hex(buf, 0, Math.min(n, 24));
                    }
                    long s = (buf[8] & 0xFFL) | (buf[9] & 0xFFL) << 8 | (buf[10] & 0xFFL) << 16 | (buf[11] & 0xFFL) << 24;
                    if (s != session) return "wrong session in a datagram from the UT";
                    if (wantKind >= 0 && (buf[6] & 0xFF) != wantKind) continue;
                    if (wantLike != null) {
                        boolean same = n == wantLike.length;
                        for (int i = 0; same && i < n; i++) {
                            if ((i < 8 || i >= 24) && buf[i] != wantLike[i]) same = false;
                        }
                        if (!same) return "datagram " + hex(buf, 0, n) + " differs from " + hex(wantLike);
                    }
                    seqs.add((buf[12] & 0xFFL) | (buf[13] & 0xFFL) << 8 | (buf[14] & 0xFFL) << 16 | (buf[15] & 0xFFL) << 24);
                    if (seqs.size() >= count) return "";
                }
            });
            if (r == null) throw new Mismatch("got " + seqs.size() + " of " + count + " datagrams");
            if (!r.isEmpty()) throw new Mismatch(r);
            if (isTrue(step.get("seq_consecutive"))) {
                for (int i = 1; i < seqs.size(); i++) {
                    if (seqs.get(i) != ((seqs.get(i - 1) + 1) & 0xFFFFFFFFL)) throw new Mismatch("seq values " + seqs + " are not consecutive");
                }
            }
        }

        Map<String, Object> likeBody(Map<String, Object> step) {
            return obj(datagrams.get(str(step.get("like"))).get("body"));
        }

        void utDo(Map<String, Object> step) throws Exception {
            String what = str(step.get("do"));
            try {
                switch (what) {
                    case "spawn":
                        ut.spawn(new Control.Spawn(num(step.get("entity")), str(step.get("provider")), str(step.get("asset")), optStr(step, "name")));
                        break;
                    case "despawn":
                        ut.despawn(num(step.get("entity")));
                        break;
                    case "set_focus":
                        ut.setFocus(roleOf(step.get("focus")));
                        break;
                    case "announce_frames":
                        ut.announceFrames(new Control.FrameChannel(str(step.get("path")), num(step.get("width")), num(step.get("height")),
                                num(step.get("slots"))));
                        break;
                    case "disconnect":
                        ut.disconnect(str(step.get("reason")));
                        break;
                    case "send_camera":
                        ut.sendCamera(cameraOf(likeBody(step)));
                        break;
                    case "send_poses": {
                        Wire.Datagram d = datagramOf(likeBody(step));
                        ut.sendPoses(((Wire.Datagram.PoseMsg) d).poses());
                        break;
                    }
                    case "send_frame": {
                        Wire.Datagram d = datagramOf(likeBody(step));
                        ut.sendFrame(((Wire.Datagram.FrameMsg) d).frame());
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("ut do " + what);
                }
            } catch (WireException e) {
                throw new Mismatch("the UT refused " + what + ": " + e.getMessage());
            }
        }
    }

    static void sessionVectors() throws Exception {
        Map<String, Map<String, Object>> all = new HashMap<>();
        for (Map<String, Object> c : load("datagrams.json")) all.put(str(c.get("id")), c);
        Report r = new Report();
        for (Map<String, Object> c : load("sessions.json")) {
            r.record(c, () -> {
                Session s = new Session(c, all);
                try {
                    s.run(arr(c.get("steps")));
                } finally {
                    s.close();
                }
            });
        }
        r.finish("sessions.json");
    }
}
