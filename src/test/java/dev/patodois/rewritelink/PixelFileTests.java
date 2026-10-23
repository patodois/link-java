package dev.patodois.rewritelink;

import static dev.patodois.rewritelink.LinkTests.check;
import static dev.patodois.rewritelink.LinkTests.eq;
import static dev.patodois.rewritelink.LinkTests.test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Tests of {@link PixelFile}: layout per CONTRACT.md 9.6, the seqlock, the depth plane and its checks. */
final class PixelFileTests {
    private PixelFileTests() {}

    private static Path temp(String name) throws Exception {
        Path dir = Files.createTempDirectory("pixelfile-test");
        dir.toFile().deleteOnExit();
        return dir.resolve(name);
    }

    /** Flips a filled buffer for writing. Buffer.flip() returns Buffer on Java 8 and ByteBuffer later, hence the helper. */
    private static ByteBuffer flipped(ByteBuffer b) {
        b.flip();
        return b;
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
                    ch.write(flipped(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(3L)), slot1);
                }
                check(r.read(1, 1, false) == null, "odd lock means the slot is being written: skip");
            }
        });

        test("pixel file: malformed files are refused, an unknown depth_format reads as colour only", () -> {
            Path p = temp("d.frames");
            try (PixelFile.Writer w = new PixelFile.Writer(p, 4, 4, 2, true)) {
                w.write(new byte[64], new float[16], 4, 4, 0, 1L);
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.write(flipped(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(9)), 40); // a future depth format
                }
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(!r.hasDepth(), "unknown depth_format is colour only, not an error");
                    check(r.read(1, 1, true).depth() == null, "no depth delivered");
                }
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.write(flipped(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1)), 40);
                    ch.write(flipped(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(13)), 44); // wrong depth_stride
                }
                boolean refused = false;
                try (PixelFile.Reader r = PixelFile.Reader.open(p)) {
                    check(r != null, "unreachable");
                } catch (PixelFile.FormatException e) {
                    refused = true;
                }
                check(refused, "depth_stride != width * 4 is malformed");
                try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                    ch.write(flipped(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16)), 44);
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
                PixelFile.Reader.open(Paths.get("relative.frames"));
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

        test("pixel file: seqlock under contention, a writer thread against a reader thread, a torn copy is never accepted", () -> {
            contention(true);
            contention(false); // the same race with no lock check, to show the writer does tear what is being read
        });
    }

    // ------------------------------------------------------------------ contention

    private static final int CW = 96, CH = 64, CSLOTS = 2; // two slots: the writer laps the reader's slot all the time
    private static final long MS_PER_PHASE = 1500;

    /** Frame {@code f} is {@code f} in every 8 bytes of colour and in every depth float. */
    private static void fill(long f, byte[] px, float[] depth) {
        for (int i = 0; i < px.length; i += 8) {
            for (int b = 0; b < 8; b++) px[i + b] = (byte) (f >>> (8 * b));
        }
        java.util.Arrays.fill(depth, (float) f);
    }

    /** True when a copy holds frame {@code f} entirely and nothing else. */
    private static boolean whole(long f, byte[] px, float[] depth) {
        for (int i = 0; i < px.length; i += 8) {
            for (int b = 0; b < 8; b++) {
                if (px[i + b] != (byte) (f >>> (8 * b))) return false;
            }
        }
        for (float d : depth) {
            if (d != (float) f) return false;
        }
        return true;
    }

    /**
     * One writer thread rewrites a two-slot file as fast as it can; the reader thread reads the newest
     * frame it was told about ({@code announce} stands for the Frame datagram). With {@code seqlock}
     * the reader is {@link PixelFile.Reader}, which must never hand out a torn frame. Without it, the
     * reader copies the slot straight out of its own mapping with no lock check, which shows that the
     * writer really does tear what is being read (printed, not asserted: a very slow machine may not
     * tear in a short run).
     */
    private static void contention(boolean seqlock) throws Exception {
        Path p = temp("contention.frames");
        final PixelFile.Writer w = new PixelFile.Writer(p, CW, CH, CSLOTS, true);
        final PixelFile.Reader r = PixelFile.Reader.open(p);
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        final AtomicLong announce = new AtomicLong(); // volatile, like the datagram: the newest finished frame
        Thread writer = new Thread(() -> {
            try {
                byte[] px = new byte[CW * CH * 4];
                float[] depth = new float[CW * CH];
                long f = 0;
                while (!stop.get()) {
                    fill(f + 1, px, depth);
                    f = w.write(px, depth, CW, CH, (int) (f + 1) * 3, (f + 1) * 1000 + 7);
                    announce.set(f);
                }
            } catch (Throwable t) {
                writerFailure.set(t);
            }
        }, "pixel-writer");
        long accepted = 0, rejected = 0, torn = 0, wrongHeader = 0, attempts = 0;
        ByteBuffer raw;
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            raw = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        long depthOffset = (64 + CW * 4 * CH + 63) / 64 * 64;
        byte[] px = new byte[CW * CH * 4];
        float[] depth = new float[CW * CH];
        writer.start();
        long end = System.nanoTime() + MS_PER_PHASE * 1_000_000L;
        try {
            while (System.nanoTime() < end) {
                long f = announce.get();
                if (f == 0) continue;
                attempts++;
                int slot = (int) (f % CSLOTS);
                if (seqlock) {
                    PixelFile.Frame fr = r.read(f, slot, true);
                    if (fr == null) {
                        rejected++;
                        continue;
                    }
                    accepted++;
                    if (fr.frame() != f || fr.timeUs() != f * 1000 + 7 || fr.cameraSeq() != (int) f * 3
                            || fr.width() != CW || fr.height() != CH) wrongHeader++;
                    if (!whole(f, fr.rgba(), fr.depth())) torn++;
                } else {
                    ByteBuffer v = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    int base = (int) (64 + slot * r.slotSize);
                    if (v.getLong(base + 8) != f) {
                        rejected++; // another frame is in the slot already: late, not torn
                        continue;
                    }
                    v.position(base + 64);
                    v.get(px);
                    v.position((int) (base + depthOffset));
                    v.asFloatBuffer().get(depth);
                    accepted++;
                    if (!whole(f, px, depth)) torn++;
                }
            }
        } finally {
            stop.set(true);
            writer.join(5000);
            r.close();
            w.close();
        }
        check(!writer.isAlive(), "the writer thread ends");
        check(writerFailure.get() == null, "writer failed: " + writerFailure.get());
        long frames = announce.get();
        System.out.println("     " + (seqlock ? "seqlock reader:        " : "unchecked copy (compare): ") + frames
                + " frames written, " + attempts + " reads tried, " + accepted + " accepted, " + rejected + " refused, "
                + torn + " torn" + (seqlock ? " (refused: busy " + r.skippedBusy + ", other frame " + r.skippedOtherFrame
                + ", overwritten while copying " + r.skippedOverwritten + "; wrong header " + wrongHeader + ")" : ""));
        if (seqlock) {
            check(frames > 1000, "the writer made progress: " + frames);
            check(accepted > 0, "the reader got frames through");
            check(r.skippedBusy + r.skippedOverwritten > 0, "the writer never collided with the reader: the test proves nothing");
            check(torn == 0, torn + " torn frames were accepted by the seqlock reader");
            check(wrongHeader == 0, wrongHeader + " accepted frames with a header that does not match the frame");
        }
    }
}
