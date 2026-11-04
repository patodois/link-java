package dev.patodois.rewritelink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import dev.patodois.rewritelink.Wire.Datagram;
import dev.patodois.rewritelink.Wire.Decoded;
import dev.patodois.rewritelink.Wire.Header;
import dev.patodois.rewritelink.Wire.WireException;

/**
 * Fuzzes the UDP datagram decoder (CONTRACT.md 9: header, heartbeat, camera, poses, collision, frame). Allowed
 * outcome: a {@link Decoded} or a {@link WireException}; whatever decodes must encode again and decode to the same
 * message.
 */
class WireFuzz {
    /** Raw bytes, exactly as a socket hands them over. */
    @FuzzTest(maxDuration = "30m")
    void decodeRaw(byte[] data) throws Exception {
        decodeAndCheck(data, data.length);
    }

    /** A valid header (right magic and version) followed by fuzzed bytes, so the bodies get exercised deeply. */
    @FuzzTest(maxDuration = "30m")
    void decodeBody(FuzzedDataProvider d) throws Exception {
        int kind = d.consumeInt(0, 7);
        byte[] body = d.consumeBytes(Wire.MAX_DATAGRAM);
        // The size the kind expects, most of the time, so that the fields are reached and not just the length check.
        if (d.consumeInt(0, 3) != 0) {
            int want = kind == Wire.KIND_CAMERA ? Wire.CAMERA_LEN : kind == Wire.KIND_FRAME ? Wire.FRAME_LEN : kind == Wire.KIND_HEARTBEAT ? 0
                    : kind == Wire.KIND_POSE ? Wire.POSE_HEADER_LEN + Wire.POSE_LEN * d.consumeInt(0, Wire.MAX_POSES + 1)
                    : Wire.COLLISION_HEADER_LEN + Wire.TRIANGLE_LEN * d.consumeInt(0, Wire.MAX_TRIANGLES + 1);
            body = Arrays.copyOf(body, Math.min(want, Wire.MAX_DATAGRAM - Wire.HEADER_LEN));
        }
        byte[] buf = new byte[Wire.HEADER_LEN + body.length];
        buf[0] = 'R';
        buf[1] = 'B';
        buf[2] = 'L';
        buf[3] = 'K';
        buf[4] = 1;
        buf[5] = 0;
        buf[6] = (byte) kind;
        System.arraycopy(body, 0, buf, Wire.HEADER_LEN, body.length);
        // Counts that would make a naive decoder allocate: patch them in half of the cases.
        if (d.consumeBoolean() && buf.length >= Wire.HEADER_LEN + 4) {
            int big = d.pickValue(new int[] {0, 1, 102, 103, 226, 227, 0x7FFFFFFF, 0x80000000, -1, 0x10000, 4096, 4097});
            for (int i = 0; i < 4; i++) buf[Wire.HEADER_LEN + (kind == Wire.KIND_COLLISION ? 24 : 0) + i < buf.length
                    ? Wire.HEADER_LEN + (kind == Wire.KIND_COLLISION ? 24 : 0) + i : buf.length - 1] = (byte) (big >>> (8 * i));
        }
        decodeAndCheck(buf, buf.length);
    }

    private static void decodeAndCheck(byte[] buf, int len) throws Exception {
        final int[] kind = new int[1];
        final Decoded[] out = new Decoded[1];
        try {
            Guard.within("Wire.decode", 500, 8L << 20, () -> {
                try {
                    Wire.decodeHeader(buf, len, kind);
                } catch (WireException ignored) {
                    // checked below by decode
                }
                out[0] = Wire.decode(buf, len);
            });
        } catch (WireException e) {
            if (e.getMessage() == null) throw new AssertionError("a WireException without a message");
            return;
        }
        Decoded dec = out[0];
        if (dec == null || dec.header() == null || dec.body() == null) throw new AssertionError("decode returned an empty result");
        Header h = dec.header();
        Datagram body = dec.body();
        dec.toString();
        if (h.version() != Wire.PROTOCOL_VERSION) throw new AssertionError("a decoded header has version " + h.version());
        if (body instanceof Datagram.Unknown) {
            if (((Datagram.Unknown) body).payloadLen() != len - Wire.HEADER_LEN) throw new AssertionError("unknown payload length");
            return;
        }
        if (body instanceof Datagram.PoseMsg) {
            List<Wire.Pose> p = ((Datagram.PoseMsg) body).poses();
            if (p.isEmpty() || p.size() > Wire.MAX_POSES) throw new AssertionError("pose count " + p.size());
            for (Wire.Pose x : p) {
                if (x.entity() < 0 || x.entity() > 0xFFFFFFFFL) throw new AssertionError("entity " + x.entity());
                if (x.translation().length != 3 || x.rotation().length != 4 || x.scale().length != 3 || x.velocity().length != 3
                        || x.angularVelocity().length != 3) throw new AssertionError("pose array lengths");
            }
        } else if (body instanceof Datagram.CollisionMsg) {
            Wire.CollisionChunk c = ((Datagram.CollisionMsg) body).chunk();
            if (c.chunkCount() < 1 || c.chunkCount() > Wire.MAX_COLLISION_CHUNKS || c.chunkIndex() >= c.chunkCount() || c.chunkIndex() < 0) {
                throw new AssertionError("chunk " + c.chunkIndex() + " of " + c.chunkCount());
            }
            if (c.triangles().size() > Wire.MAX_TRIANGLES) throw new AssertionError("triangles " + c.triangles().size());
            for (float[] t : c.triangles()) if (t.length != 9) throw new AssertionError("triangle length");
        } else if (body instanceof Datagram.FrameMsg) {
            if (((Datagram.FrameMsg) body).frame().frame() == 0) throw new AssertionError("frame 0 decoded");
        }
        // Encode what was decoded and decode that again: the same message (a reserved field the sender left non-zero is
        // not carried over, so the bytes may differ, the message may not).
        byte[] enc;
        try {
            enc = Wire.encode(h, body);
        } catch (WireException e) {
            throw new AssertionError("a decoded datagram does not encode: " + e.getMessage() + " " + dec, e);
        }
        Decoded again;
        try {
            again = Wire.decode(enc, enc.length);
        } catch (WireException e) {
            throw new AssertionError("an encoded datagram does not decode: " + e.getMessage(), e);
        }
        if (!again.equals(dec)) throw new AssertionError("round trip changed the datagram");
        if (enc.length > Wire.MAX_DATAGRAM) throw new AssertionError("encoded datagram of " + enc.length + " bytes");
        List<Integer> sink = new ArrayList<>();
        sink.add(enc.length);
    }
}
