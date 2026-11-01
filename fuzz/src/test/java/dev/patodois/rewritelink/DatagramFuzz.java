package dev.patodois.rewritelink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.code_intelligence.jazzer.api.BugDetectors;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Fuzzes what a live link does with the datagrams of its peer (poses, camera, collision snapshots, frames, anything
 * else), through the real UDP socket and {@link Link#poll()}: the header check, the session and version filter, the
 * staleness rules, the entity table and the collision reassembly. A fake guest does the handshake, spawns a few
 * entities, then sends the fuzzed datagrams from the UDP port it announced.
 */
class DatagramFuzz {
    private static Link host;
    private static DatagramSocket peerUdp;

    private static synchronized void init() throws IOException {
        if (host == null) {
            LinkConfig cfg = new LinkConfig("fuzz-udp-host");
            cfg.controlPort = 0;
            cfg.timeoutMs = 10_000;
            host = Link.host(cfg);
            peerUdp = new DatagramSocket(new InetSocketAddress(HandshakeFuzz.LO, 0));
            final Link h = host;
            Runtime.getRuntime().addShutdownHook(new Thread(h::close));
        }
    }

    @FuzzTest(maxDuration = "30m")
    void datagrams(FuzzedDataProvider d) throws Exception {
        BugDetectors.allowNetworkConnections();
        init();
        int entities = d.consumeInt(0, 4);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(HandshakeFuzz.LO, host.controlPort()), 2000);
        s.setSoTimeout(2000);
        List<LinkEvent> events = new ArrayList<>();
        try {
            StringBuilder hello = new StringBuilder("{\"type\":\"hello\",\"role\":\"guest\",\"game\":\"g\",\"protocol\":1,\"min_protocol\":1,\"udp_port\":")
                    .append(peerUdp.getLocalPort()).append(",\"heartbeat_ms\":250,\"timeout_ms\":10000}\n");
            for (int i = 1; i <= entities; i++) {
                hello.append("{\"type\":\"spawn\",\"entity\":").append(i).append(",\"provider\":\"p\",\"asset\":\"a\"}\n");
            }
            s.getOutputStream().write(hello.toString().getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            long deadline = System.nanoTime() + 3_000_000_000L;
            int spawned = 0;
            boolean connected = false;
            while (!connected || spawned < entities) {
                for (LinkEvent e : host.poll()) {
                    events.add(e);
                    if (e instanceof LinkEvent.Connected) connected = true;
                    if (e instanceof LinkEvent.Spawn) spawned++;
                }
                if (System.nanoTime() > deadline) throw new AssertionError("no hello accepted: " + events);
                Thread.yield();
            }
            int session = (int) session(s.getInputStream());
            long before = host.datagramsReceived + host.malformed + host.foreign;
            int sent = 0;
            long alloc0 = ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                    .getThreadAllocatedBytes(Thread.currentThread().getId());
            int count = d.consumeInt(1, 24);
            for (int i = 0; i < count && d.remainingBytes() > 0; i++) {
                byte[] dg = datagram(d, session);
                if (dg.length > 60_000) continue;
                peerUdp.send(new DatagramPacket(dg, dg.length, new InetSocketAddress(HandshakeFuzz.LO, host.udpPort())));
                sent++;
            }
            // Wait until every datagram is accounted for; a lost one (a full buffer) is not an error.
            deadline = System.nanoTime() + 600_000_000L;
            long accounted = 0;
            while (accounted < sent && System.nanoTime() < deadline) {
                events.addAll(host.poll());
                accounted = host.datagramsReceived + host.malformed + host.foreign - before;
                Thread.yield();
            }
            long alloc = ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                    .getThreadAllocatedBytes(Thread.currentThread().getId()) - alloc0;
            if (alloc > 512L << 20) throw new AssertionError(sent + " datagrams made the link allocate " + alloc + " bytes");
            checkEvents(events, entities);
            s.shutdownOutput();
            deadline = System.nanoTime() + 5_000_000_000L;
            boolean ended = false;
            while (!ended) {
                for (LinkEvent e : host.poll()) {
                    if (e instanceof LinkEvent.Lost || e instanceof LinkEvent.HandshakeFailed) ended = true;
                }
                if (System.nanoTime() > deadline) throw new AssertionError("the session did not end");
                Thread.yield();
            }
            drain(s);
        } finally {
            s.close();
        }
    }

    /** The host's hello carries the session (a nonzero u32). */
    private static long session(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') out.write(b);
        Control c = Control.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        if (!(c instanceof Control.Hello) || ((Control.Hello) c).session() == null) throw new AssertionError("no host hello: " + c);
        return ((Control.Hello) c).session();
    }

    private static void drain(Socket s) throws IOException {
        byte[] buf = new byte[4096];
        try {
            while (s.getInputStream().read(buf) >= 0) {
                // discard
            }
        } catch (IOException ignored) {
            // a reset
        }
    }

    private static byte[] datagram(FuzzedDataProvider d, int session) {
        int mode = d.consumeInt(0, 3);
        if (mode == 0) return d.consumeBytes(d.consumeInt(0, Wire.MAX_DATAGRAM + 16)); // raw
        int kind = d.consumeInt(0, 7);
        int bodyLen;
        switch (kind) {
            case Wire.KIND_CAMERA: bodyLen = Wire.CAMERA_LEN; break;
            case Wire.KIND_FRAME: bodyLen = Wire.FRAME_LEN; break;
            case Wire.KIND_HEARTBEAT: bodyLen = 0; break;
            case Wire.KIND_POSE: bodyLen = Wire.POSE_HEADER_LEN + Wire.POSE_LEN * d.consumeInt(1, Wire.MAX_POSES); break;
            case Wire.KIND_COLLISION: bodyLen = Wire.COLLISION_HEADER_LEN + Wire.TRIANGLE_LEN * d.consumeInt(0, Wire.MAX_TRIANGLES); break;
            default: bodyLen = d.consumeInt(0, 64); break;
        }
        if (mode == 3) bodyLen = d.consumeInt(0, Wire.MAX_DATAGRAM - Wire.HEADER_LEN);
        byte[] buf = new byte[Wire.HEADER_LEN + bodyLen];
        byte[] fill = d.consumeBytes(Math.min(bodyLen, 256));
        System.arraycopy(fill, 0, buf, Wire.HEADER_LEN, fill.length);
        // The rest of the body repeats the fuzzed bytes, which keeps the counts and the floats inside a pattern.
        for (int i = Wire.HEADER_LEN + fill.length; fill.length > 0 && i < buf.length; i++) buf[i] = fill[(i - Wire.HEADER_LEN) % fill.length];
        buf[0] = 'R';
        buf[1] = 'B';
        buf[2] = 'L';
        buf[3] = 'K';
        buf[4] = 1;
        buf[5] = 0;
        buf[6] = (byte) kind;
        buf[7] = (byte) d.consumeInt(0, 255);
        int sess = d.consumeInt(0, 15) == 0 ? d.consumeInt() : session;
        for (int i = 0; i < 4; i++) buf[8 + i] = (byte) (sess >>> (8 * i));
        int seq = d.pickValue(new int[] {0, 1, 2, 3, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, d.consumeInt()});
        for (int i = 0; i < 4; i++) buf[12 + i] = (byte) (seq >>> (8 * i));
        if (kind == Wire.KIND_POSE && buf.length >= Wire.HEADER_LEN + 8 + 4) {
            // Point most poses at the spawned entities so that the state machine sees them.
            int poseCount = (bodyLen - Wire.POSE_HEADER_LEN) / Wire.POSE_LEN;
            for (int i = 0; i < 4; i++) buf[Wire.HEADER_LEN + i] = (byte) (poseCount >>> (8 * i));
            for (int p = 0; p < poseCount && p < 8; p++) {
                int off = Wire.HEADER_LEN + Wire.POSE_HEADER_LEN + p * Wire.POSE_LEN;
                buf[off] = (byte) d.consumeInt(0, 5);
                buf[off + 1] = buf[off + 2] = buf[off + 3] = 0;
            }
        }
        if (kind == Wire.KIND_COLLISION && buf.length >= Wire.HEADER_LEN + Wire.COLLISION_HEADER_LEN) {
            int tris = (bodyLen - Wire.COLLISION_HEADER_LEN) / Wire.TRIANGLE_LEN;
            int o = Wire.HEADER_LEN;
            int query = d.consumeInt(0, 6);
            int count = d.pickValue(new int[] {1, 1, 2, 3, 4096, 4097, 0});
            int index = d.consumeInt(0, 4);
            for (int i = 0; i < 4; i++) buf[o + i] = (byte) (query >>> (8 * i));
            buf[o + 4] = (byte) index;
            buf[o + 5] = (byte) (index >>> 8);
            buf[o + 6] = (byte) count;
            buf[o + 7] = (byte) (count >>> 8);
            for (int i = 0; i < 4; i++) buf[o + 24 + i] = (byte) (tris >>> (8 * i));
        }
        if (kind == Wire.KIND_FRAME && buf.length == Wire.HEADER_LEN + Wire.FRAME_LEN) {
            long frame = d.consumeInt(0, 3);
            for (int i = 0; i < 8; i++) buf[Wire.HEADER_LEN + i] = (byte) (frame >>> (8 * i));
        }
        return buf;
    }

    private static void checkEvents(List<LinkEvent> events, int entities) {
        for (LinkEvent e : events) {
            e.toString();
            if (e instanceof LinkEvent.Poses) {
                for (Wire.Pose p : ((LinkEvent.Poses) e).poses()) {
                    if (p.entity() < 1 || p.entity() > entities) throw new AssertionError("a pose for entity " + p.entity() + " was delivered");
                }
            } else if (e instanceof LinkEvent.Collision) {
                LinkEvent.Collision c = (LinkEvent.Collision) e;
                for (float[] t : c.triangles()) if (t.length != 9) throw new AssertionError("triangle length");
            }
        }
    }
}
