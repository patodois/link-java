package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.test;
import static dev.patodois.rewritelink.LinkTests.until;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@code LinkDemo --endpoint-file}: a demo host and guest as subprocesses, with the right token, none and a wrong one. */
final class DemoTests {
    private DemoTests() {}

    private static final String WRONG = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    /** A LinkDemo process; its stdout and stderr are collected line by line. */
    private static final class Peer implements AutoCloseable {
        final Process process;
        final List<String> lines = new CopyOnWriteArrayList<>();

        Peer(String... args) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add("dev.patodois.rewritelink.LinkDemo");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) lines.add(l);
                } catch (IOException ignored) {
                    // the process ended
                }
            });
            t.setDaemon(true);
            t.start();
        }

        boolean saw(String text) {
            for (String l : lines) if (l.contains(text)) return true;
            return false;
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private static Path tempDir() throws Exception {
        Path d = Files.createTempDirectory("demo-test");
        d.toFile().deleteOnExit();
        return d;
    }

    private static void cleanup(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : s.collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(p);
        }
        Files.deleteIfExists(dir);
    }

    static void run() {
        test("LinkDemo --endpoint-file: right token: host and guest link up, file is written owner-only", () -> {
            Path dir = tempDir();
            Path file = dir.resolve("endpoint.json");
            try (Peer host = new Peer("host", "--port", "0", "--seconds", "20", "--endpoint-file", file.toString())) {
                check(until(10000, () -> Files.exists(file)), "the host wrote the endpoint file");
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    check(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)).equals("rw-------"), "mode 0600");
                }
                try (Peer guest = new Peer("guest", "--seconds", "20", "--endpoint-file", file.toString())) {
                    check(until(15000, () -> guest.saw("connected: host") && host.saw("connected: guest")),
                            "both sides connected; host: " + host.lines + " guest: " + guest.lines);
                    check(!guest.saw("handshake failed"), "no failed handshake");
                }
            }
            cleanup(dir);
        });

        test("LinkDemo --endpoint-file: a guest with no token gets unauthorized", () -> {
            Path dir = tempDir();
            Path file = dir.resolve("endpoint.json");
            try (Peer host = new Peer("host", "--port", "0", "--seconds", "20", "--endpoint-file", file.toString())) {
                check(until(10000, () -> Files.exists(file)), "the host wrote the endpoint file");
                int port = Endpoint.read(file).controlPort();
                try (Peer guest = new Peer("guest", "--port", String.valueOf(port), "--seconds", "20")) {
                    check(until(15000, () -> guest.saw("handshake failed") && host.saw("handshake failed: unauthorized")),
                            "host: " + host.lines + " guest: " + guest.lines);
                    check(guest.saw("unauthorized"), "the guest was told unauthorized: " + guest.lines);
                    check(!guest.saw("connected: host") && !host.saw("connected: guest"), "never connected");
                }
            }
            cleanup(dir);
        });

        test("LinkDemo --endpoint-file: a guest with a wrong token gets unauthorized", () -> {
            Path dir = tempDir();
            Path file = dir.resolve("endpoint.json");
            Path wrong = dir.resolve("wrong.json");
            try (Peer host = new Peer("host", "--port", "0", "--seconds", "20", "--endpoint-file", file.toString())) {
                check(until(10000, () -> Files.exists(file)), "the host wrote the endpoint file");
                Endpoint.write(wrong, Endpoint.read(file).controlPort(), WRONG);
                try (Peer guest = new Peer("guest", "--seconds", "20", "--endpoint-file", wrong.toString())) {
                    check(until(15000, () -> guest.saw("unauthorized") && host.saw("handshake failed: unauthorized")),
                            "host: " + host.lines + " guest: " + guest.lines);
                    check(!guest.saw("connected: host") && !host.saw("connected: guest"), "never connected");
                }
            }
            cleanup(dir);
        });

        test("LinkDemo without --endpoint-file: unchanged, links up with no token", () -> {
            try (Peer host = new Peer("host", "--port", "0", "--seconds", "20")) {
                check(until(10000, () -> host.lines.stream().anyMatch(l -> l.contains("listening on 127.0.0.1:"))), "listening");
                String l = host.lines.stream().filter(x -> x.contains("listening on 127.0.0.1:")).findFirst().get();
                String port = l.substring(l.lastIndexOf(':') + 1).trim();
                try (Peer guest = new Peer("guest", "--port", port, "--seconds", "20")) {
                    check(until(15000, () -> guest.saw("connected: host") && host.saw("connected: guest")),
                            "host: " + host.lines + " guest: " + guest.lines);
                }
            }
        });
    }
}
