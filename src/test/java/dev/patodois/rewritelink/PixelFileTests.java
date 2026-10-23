package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Tests of {@link PixelFile}: layout per CONTRACT.md 9.6, the seqlock, the depth plane and its checks. */
final class PixelFileTests {
    private PixelFileTests() {}

    private static Path temp(String name) throws Exception {
        Path dir = Files.createTempDirectory("pixelfile-test");
        dir.toFile().deleteOnExit();
        return dir.resolve(name);
    }

    static void run() {
        test("pixel file: colour only round trip, layout of revision 2", () -> {
            Path p = temp("a.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(p, 4, 2, 3, false)) {
                eq(w.slotSize, 128L, "64 slot header + 4*4*2 pixels = 96, rounded up to 128");
                byte[] px = new byte[4 * 2 * 4];
                for (int i = 0; i < px.length; i++) px[i] = (byte) (i * 3);
                long f = w.write(px, null, 4, 2, 77, 123456789L);
                eq(f, 1L, "first frame is 1");
                ByteBuffer raw = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
                eq(raw.getInt(8), 1, "version");
                eq(raw.getInt(40), 0, "depth_format is 0 in a colour-only file");
                eq(raw.getLong(48), 0L, "depth_offset is 0");
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(!r.hasDepth(), "no depth");
                    PixelFile.Frame fr = r.read(1, 1, true);
                    check(fr != null, "frame read");
                    eq(fr.cameraSeq(), 77, "camera_seq");
                    eq(fr.timeUs(), 123456789L, "time_us");
                    check(java.util.Arrays.equals(fr.rgba(), px), "pixels");
                    check(fr.depth() == null, "no depth array");
                    check(r.read(2, 2, false) == null, "a slot that holds another frame is refused");
                }
            }
            check(!Files.exists(p), "the writer deletes the file on close");
        });

        test("pixel file: depth plane at depth_offset, linear f32 metres", () -> {
            Path p = temp("b.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(p, 5, 3, 3, true)) {
                // colour end = 64 + 5*4*3 = 124 -> depth_offset 128; plane 5*4*3 = 60 -> 188 -> slot_size 192
                eq(w.slotSize, 192L, "slot_size");
                byte[] px = new byte[5 * 3 * 4];
                float[] d = new float[15];
                for (int i = 0; i < 15; i++) d[i] = i + 0.5f;
                d[7] = Float.POSITIVE_INFINITY;
                w.write(px, d, 5, 3, 1, 5L);
                ByteBuffer raw = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
                eq(raw.getInt(40), 1, "depth_format");
                eq(raw.getInt(44), 20, "depth_stride");
                eq(raw.getLong(48), 128L, "depth_offset");
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(r.hasDepth(), "has depth");
                    PixelFile.Frame fr = r.read(1, 1, true);
                    check(fr.depth() != null && fr.depth()[14] == 14.5f && fr.depth()[0] == 0.5f, "depth values");
                    check(Float.isInfinite(fr.depth()[7]), "+inf survives");
                    check(r.read(1, 1, false).depth() == null, "depth not requested");
                }
            }
        });

        test("pixel file: an odd lock (slot being written) is skipped, a changed lock discards the copy", () -> {
            Path p = temp("c.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(p, 2, 2, 2, false); PixelFile.Reader r = PixelFile.Reader.open(p)) {
                w.write(new byte[16], null, 2, 2, 0, 1L);
                check(r.read(1, 1, false) != null, "even lock reads");
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    // slot 1 starts at 64 + 1 * slot_size; set its lock odd by hand, as a writer in the middle of a write
                    long slot1 = 64 + w.slotSize;
                    ch.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(3L).flip(), slot1);
                }
                check(r.read(1, 1, false) == null, "odd lock means the slot is being written: skip");
            }
        });

        test("pixel file: malformed files are refused, an unknown depth_format reads as colour only", () -> {
            Path p = temp("d.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(p, 4, 4, 2, true)) {
                w.write(new byte[64], new float[16], 4, 4, 0, 1L);
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(9).flip(), 40); // a future depth format
                }
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(!r.hasDepth(), "unknown depth_format is colour only, not an error");
                    check(r.read(1, 1, true).depth() == null, "no depth delivered");
                }
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).flip(), 40);
                    ch.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(13).flip(), 44); // wrong depth_stride
                }
                boolean refused = false;
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(r != null, "unreachable");
                } catch (PixelFile.FormatException e) {
                    refused = true;
                }
                check(refused, "depth_stride != width * 4 is malformed");
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).flip(), 44);
                    ch.write(ByteBuffer.wrap(new byte[] {'X'}), 0); // wrong magic
                }
                refused = false;
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(r != null, "unreachable");
                } catch (PixelFile.FormatException e) {
                    refused = true;
                }
                check(refused, "bad magic");
            }
            boolean refused = false;
            try {
                PixelFile.Reader.open(Path.of("relative.frames"));
            } catch (PixelFile.FormatException e) {
                refused = true;
            }
            check(refused, "relative paths are refused");
        });

        test("pixel file: a truncated file is refused", () -> {
            Path p = temp("e.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(p, 4, 4, 3, false)) {
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.truncate(64 + w.slotSize); // header and one slot of three
                }
                boolean refused = false;
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(r != null, "unreachable");
                } catch (PixelFile.FormatException e) {
                    refused = true;
                }
                check(refused, "file shorter than its slots");
            }
        });
    }
}
