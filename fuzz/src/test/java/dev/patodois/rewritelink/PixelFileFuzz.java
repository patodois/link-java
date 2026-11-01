package dev.patodois.rewritelink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/** Fuzzes {@link PixelFile.Reader} on arbitrary file contents (CONTRACT.md 9.6). Allowed outcome: an IOException or null/frame. */
class PixelFileFuzz {
    private static Path file;

    private static synchronized Path file() throws IOException {
        if (file == null) {
            file = Files.createTempFile("link-fuzz-px", ".bin").toAbsolutePath();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // a temporary file
                }
            }));
        }
        return file;
    }

    @FuzzTest(maxDuration = "30m")
    void open(FuzzedDataProvider d) throws Exception {
        byte[] content;
        if (d.consumeBoolean()) {
            // A header that is right, with the numbers the fuzzer picks, then slot bytes.
            byte[] head = new byte[PixelFile.HEADER_LEN];
            byte[] raw = d.consumeBytes(PixelFile.HEADER_LEN);
            System.arraycopy(raw, 0, head, 0, raw.length);
            System.arraycopy(new byte[] {'R', 'B', 'F', 'R', 'A', 'M', 'E', 0, 1, 0, 0, 0}, 0, head, 0, 12);
            if (d.consumeBoolean()) {
                head[12] = (byte) d.consumeInt(0, 17);
                head[13] = head[14] = head[15] = 0;
                head[24] = head[25] = head[26] = head[27] = 0;
                head[16] = (byte) d.consumeInt(0, 40);
                head[20] = (byte) d.consumeInt(0, 40);
                head[17] = head[18] = head[19] = head[21] = head[22] = head[23] = 0;
                head[24] = (byte) (4 * head[16]);
                head[28] = 1;
                head[29] = head[30] = head[31] = 0;
            }
            byte[] rest = d.consumeBytes(1 << 16);
            content = new byte[head.length + rest.length];
            System.arraycopy(head, 0, content, 0, head.length);
            System.arraycopy(rest, 0, content, head.length, rest.length);
        } else {
            content = d.consumeRemainingAsBytes();
        }
        Files.write(file(), content);
        final PixelFile.Reader[] r = new PixelFile.Reader[1];
        try {
            Guard.within("PixelFile.Reader.open", 2000, 64L << 20, () -> r[0] = PixelFile.Reader.open(file()));
        } catch (IOException expected) {
            return;
        }
        try (PixelFile.Reader reader = r[0]) {
            if (reader.width < 1 || reader.height < 1 || reader.slots < 1 || reader.slots > 16) throw new AssertionError("reader dimensions");
            for (int i = 0; i < 4; i++) {
                long frame = d.pickValue(new long[] {0, 1, 2, -1, Long.MAX_VALUE, d.consumeLong()});
                int slot = d.pickValue(new int[] {0, 1, reader.slots - 1, reader.slots, -1, Integer.MIN_VALUE});
                int w = d.consumeInt(-2, reader.width + 2);
                int h = d.consumeInt(-2, reader.height + 2);
                boolean depth = d.consumeBoolean();
                final PixelFile.Frame[] f = new PixelFile.Frame[1];
                Guard.within("PixelFile.Reader.read", 2000, 256L << 20, () -> f[0] = reader.read(frame, slot, w, h, depth));
                if (f[0] != null) {
                    PixelFile.Frame x = f[0];
                    if (x.rgba().length != x.width() * x.height() * 4) throw new AssertionError("rgba size");
                    if (x.depth() != null && x.depth().length != x.width() * x.height()) throw new AssertionError("depth size");
                    if (x.width() > reader.width || x.height() > reader.height) throw new AssertionError("frame larger than the file");
                    if (x.frame() != frame) throw new AssertionError("frame number");
                }
                Guard.within("PixelFile.Reader.read(3)", 2000, 256L << 20, () -> reader.read(frame, slot, depth));
            }
        }
        Arrays.fill(content, (byte) 0);
    }
}
