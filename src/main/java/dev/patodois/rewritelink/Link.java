package dev.patodois.rewritelink;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.LinkEvent.PeerInfo;
import dev.patodois.rewritelink.Wire.Datagram;
import dev.patodois.rewritelink.Wire.Header;
import dev.patodois.rewritelink.Wire.WireException;

/**
 * One side of a passthrough link (host or guest). Create it with {@link #host} or {@link #guest},
 * then call {@link #poll()} regularly; it never blocks. All public methods are synchronized, so a
 * game thread may send while a helper thread polls (see {@link LinkRunner}).
 *
 * <p>Everything binds and connects to 127.0.0.1 only.
 */
public final class Link implements AutoCloseable {
    private static final long CONNECT_TIMEOUT_MS = 1000;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final Role role;
    private final LinkConfig cfg;
    private final ServerSocketChannel listener; // host only
    private final DatagramChannel udp;
    private final SecureRandom random = new SecureRandom();

    private Conn conn;
    private final List<Closing> closing = new ArrayList<>();
    private final List<LinkEvent> events = new ArrayList<>();

    private int seq;
    private int nextQuery = 1;
    private long lastBeat;
    private Role focus = Role.HOST;

    // per-session state
    private Integer lastCamera;
    private final Map<Long, Long> poseTimes = new HashMap<>();
    private Integer lastCollision;
    private Assembly assembly;
    private long lastFrame;

    // diagnostics
    public long datagramsSent, datagramsReceived, malformed, stale, foreign, sendDropped,
            linesSent, linesReceived;

    private final ByteBuffer rx = ByteBuffer.allocate(2 * Wire.MAX_DATAGRAM);

    // ---------------------------------------------------------------- connection states

    private sealed interface Conn {}

    /** No connection. A guest retries at {@code nextTry}; a host waits. */
    private record Idle(long nextTry) implements Conn {}

    /** Guest: a non-blocking connect is in flight. */
    private record Connecting(SocketChannel ch, long since) implements Conn {}

    /** Connected, waiting for the peer's hello. */
    private record Handshake(Ctl ctl, long since) implements Conn {}

    private static final class Live implements Conn {
        final Ctl ctl;
        final PeerInfo peer;
        final long timeoutMs;
        long lastHeard;

        Live(Ctl ctl, PeerInfo peer, long timeoutMs, long lastHeard) {
            this.ctl = ctl;
            this.peer = peer;
            this.timeoutMs = timeoutMs;
            this.lastHeard = lastHeard;
        }
    }

    private static final class Assembly {
        final int query;
        final int count;
        final float[] center;
        final float radius;
        final List<List<float[]>> chunks;
        int received;

        Assembly(int query, int count, float[] center, float radius) {
            this.query = query;
            this.count = count;
            this.center = center;
            this.radius = radius;
            this.chunks = new ArrayList<>(java.util.Collections.nCopies(count, (List<float[]>) null));
        }
    }

    // ---------------------------------------------------------------- control connection

    /** A control connection: non-blocking channel, line reader and write queue. */
    private static final class Ctl {
        final SocketChannel ch;
        byte[] rbuf = new byte[0];
        int rlen;
        byte[] wbuf = new byte[0];
        int wlen;
        private final ByteBuffer scratch = ByteBuffer.allocate(8192);

        Ctl(SocketChannel ch) throws IOException {
            this.ch = ch;
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }

        void queue(Control c) {
            byte[] line = c.toLine().getBytes(StandardCharsets.UTF_8);
            if (wlen + line.length > wbuf.length) wbuf = Arrays.copyOf(wbuf, Math.max(wbuf.length * 2, wlen + line.length));
            System.arraycopy(line, 0, wbuf, wlen, line.length);
            wlen += line.length;
        }

        /** Writes as much as the socket takes without blocking; returns an error reason or null. */
        String flush() {
            try {
                int off = 0;
                while (off < wlen) {
                    int n = ch.write(ByteBuffer.wrap(wbuf, off, wlen - off));
                    if (n <= 0) break;
                    off += n;
                }
                if (off > 0) {
                    System.arraycopy(wbuf, off, wbuf, 0, wlen - off);
                    wlen -= off;
                }
            } catch (IOException e) {
                return "control write failed: " + e.getMessage();
            }
            if (wlen > Wire.MAX_PENDING_CONTROL) return "peer stopped reading the control channel";
            return null;
        }

        /** Result of {@link #readLines}: complete lines, then the end reason (applies after them). */
        record Read(List<String> lines, String end) {}

        Read readLines() {
            List<String> lines = new ArrayList<>();
            String end = null;
            try {
                while (true) {
                    scratch.clear();
                    int n = ch.read(scratch);
                    if (n < 0) {
                        end = "connection closed";
                        break;
                    }
                    if (n == 0) break;
                    if (rlen + n > rbuf.length) rbuf = Arrays.copyOf(rbuf, Math.max(rbuf.length * 2, rlen + n));
                    System.arraycopy(scratch.array(), 0, rbuf, rlen, n);
                    rlen += n;
                    if (rlen > 4 * Wire.MAX_LINE) break; // let the caller process what it has first
                }
            } catch (IOException e) {
                end = "control read failed: " + e.getMessage();
            }
            int start = 0;
            while (true) {
                int nl = -1;
                for (int i = start; i < rlen; i++) {
                    if (rbuf[i] == '\n') {
                        nl = i;
                        break;
                    }
                }
                if (nl < 0) break;
                int len = nl + 1 - start;
                if (len > Wire.MAX_LINE) return new Read(lines, "control line too long");
                try {
                    lines.add(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(rbuf, start, len)).toString());
                } catch (CharacterCodingException e) {
                    return new Read(lines, "control line is not UTF-8");
                }
                start = nl + 1;
            }
            if (start > 0) {
                System.arraycopy(rbuf, start, rbuf, 0, rlen - start);
                rlen -= start;
            }
            if (rlen >= Wire.MAX_LINE) return new Read(lines, "control line too long");
            return new Read(lines, end);
        }

        void closeQuietly() {
            try {
                ch.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /**
     * A control connection being closed gracefully (CONTRACT.md 6.1): flush the last bytes
     * (usually a bye), half-close the write side, then read and discard what the peer still sends
     * until it closes too or 200 ms pass, and only then close the socket. Closing a socket that
     * holds unread data makes the OS send a reset, which on Windows can destroy the bye.
     */
    private static final class Closing {
        final Ctl ctl;
        final long deadline;
        boolean shut;
        private final ByteBuffer junk = ByteBuffer.allocate(4096);

        Closing(Ctl ctl, long now) {
            this.ctl = ctl;
            this.deadline = now + Wire.LINGER_MS;
        }

        /** Makes progress without blocking; false once the socket is closed. */
        boolean pump(long now) {
            if (now >= deadline || ctl.flush() != null) return done();
            try {
                if (!shut) {
                    if (ctl.wlen > 0) return true;
                    ctl.ch.shutdownOutput();
                    shut = true;
                }
                for (int i = 0; i < 64; i++) {
                    junk.clear();
                    int n = ctl.ch.read(junk);
                    if (n < 0) return done();
                    if (n == 0) return true;
                }
                return true;
            } catch (IOException e) {
                return done();
            }
        }

        private boolean done() {
            ctl.closeQuietly();
            return false;
        }
    }

    // ---------------------------------------------------------------- construction

    private Link(Role role, LinkConfig cfg) throws IOException {
        this.role = role;
        this.cfg = cfg;
        long now = now();
        ServerSocketChannel l = null;
        DatagramChannel u = null;
        try {
            u = DatagramChannel.open();
            u.bind(new InetSocketAddress(LOOPBACK, cfg.udpPort));
            u.configureBlocking(false);
            if (role == Role.HOST) {
                l = ServerSocketChannel.open();
                l.bind(new InetSocketAddress(LOOPBACK, cfg.controlPort));
                l.configureBlocking(false);
            }
        } catch (IOException e) {
            if (u != null) u.close();
            if (l != null) l.close();
            throw e;
        }
        this.listener = l;
        this.udp = u;
        this.conn = new Idle(now);
        this.seq = 0;
    }

    /** Listens for one guest on 127.0.0.1:{@code cfg.controlPort}. */
    public static Link host(LinkConfig cfg) throws IOException {
        return new Link(Role.HOST, cfg);
    }

    /** Connects to a host on 127.0.0.1:{@code cfg.controlPort}, retrying until one answers. */
    public static Link guest(LinkConfig cfg) throws IOException {
        return new Link(Role.GUEST, cfg);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    public Role role() {
        return role;
    }

    /** Host: the control port actually bound. */
    public synchronized int controlPort() {
        try {
            return listener == null ? cfg.controlPort : ((InetSocketAddress) listener.getLocalAddress()).getPort();
        } catch (IOException e) {
            return cfg.controlPort;
        }
    }

    public synchronized int udpPort() {
        try {
            return ((InetSocketAddress) udp.getLocalAddress()).getPort();
        } catch (IOException e) {
            return 0;
        }
    }

    public synchronized boolean isConnected() {
        return conn instanceof Live;
    }

    public synchronized PeerInfo peer() {
        return conn instanceof Live l ? l.peer : null;
    }

    public synchronized Role focus() {
        return focus;
    }

    // ---------------------------------------------------------------- polling

    /** Does one round of work and returns what happened. Call every few milliseconds. */
    public synchronized List<LinkEvent> poll() {
        long now = now();
        for (Iterator<Closing> it = closing.iterator(); it.hasNext(); ) {
            if (!it.next().pump(now)) it.remove();
        }
        pollConnect(now);
        pollControl(now);
        pollUdp(now);
        pollTimers(now);
        List<LinkEvent> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    private Control.Hello hello(int protocol, Long session) {
        return new Control.Hello(role, cfg.game, protocol, Wire.MIN_PROTOCOL_VERSION, udpPort(),
                cfg.heartbeatMs, cfg.timeoutMs, session);
    }

    private void pollConnect(long now) {
        if (listener != null) {
            List<Ctl> busy = new ArrayList<>();
            while (true) {
                SocketChannel ch;
                try {
                    ch = listener.accept();
                } catch (IOException e) {
                    break;
                }
                if (ch == null) break;
                Ctl ctl;
                try {
                    ctl = new Ctl(ch);
                } catch (IOException e) {
                    try {
                        ch.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                    continue;
                }
                if (conn instanceof Idle) conn = new Handshake(ctl, now);
                else busy.add(ctl);
            }
            for (Ctl c : busy) retire(c, "busy", now);
            return;
        }
        if (conn instanceof Idle idle && now >= idle.nextTry()) {
            try {
                SocketChannel ch = SocketChannel.open();
                ch.configureBlocking(false);
                ch.connect(new InetSocketAddress(LOOPBACK, cfg.controlPort));
                conn = new Connecting(ch, now);
            } catch (IOException e) {
                conn = new Idle(now + cfg.reconnectMs);
            }
        } else if (conn instanceof Connecting c) {
            try {
                if (c.ch().finishConnect()) {
                    Ctl ctl = new Ctl(c.ch());
                    ctl.queue(hello(Wire.PROTOCOL_VERSION, null));
                    linesSent++;
                    ctl.flush();
                    conn = new Handshake(ctl, now);
                } else if (now - c.since() > CONNECT_TIMEOUT_MS) {
                    abortConnect(c, now);
                }
            } catch (IOException e) {
                abortConnect(c, now);
            }
        }
    }

    private void abortConnect(Connecting c, long now) {
        try {
            c.ch().close();
        } catch (IOException ignored) {
            // nothing to do
        }
        conn = new Idle(now + cfg.reconnectMs);
    }

    /** Closes a control connection gracefully, after an optional bye. */
    private void retire(Ctl ctl, String bye, long now) {
        if (bye != null) {
            ctl.queue(new Control.Bye(bye));
            linesSent++;
        }
        Closing c = new Closing(ctl, now);
        if (c.pump(now)) closing.add(c);
    }

    private Ctl ctl() {
        if (conn instanceof Handshake h) return h.ctl();
        if (conn instanceof Live l) return l.ctl;
        return null;
    }

    private void pollControl(long now) {
        Ctl ctl = ctl();
        if (ctl == null) return;
        String err = ctl.flush();
        if (err != null) {
            end(err, false);
            return;
        }
        Ctl.Read read = ctl.readLines();
        for (String line : read.lines()) {
            linesReceived++;
            String[] fail = onLine(line, now);
            if (fail != null) {
                end(fail[0], fail.length > 1);
                return;
            }
            if (conn instanceof Idle) return;
        }
        // A bye was handled above (it ends the session first), so an end of stream or a reset
        // that follows a bye never reaches this point as a failure.
        if (read.end() != null) end(read.end(), false);
    }

    /** Handles one control line; a non-null result ends the session (length 2 = send a bye). */
    private String[] onLine(String line, long now) {
        Control msg;
        try {
            msg = Control.parse(line);
        } catch (WireException e) {
            return new String[] {"protocol error: " + e.getMessage(), "bye"};
        }
        if (conn instanceof Live l) l.lastHeard = now;
        if (msg instanceof Control.Bye b) return new String[] {"peer said bye: " + b.reason()};
        if (conn instanceof Handshake) {
            if (!(msg instanceof Control.Hello h)) return new String[] {"expected hello", "bye"};
            return onHello(h, now);
        }
        switch (msg) {
            case Control.Hello h -> {
                return new String[] {"second hello", "bye"};
            }
            case Control.Spawn s -> events.add(new LinkEvent.Spawn(s));
            case Control.Despawn d -> {
                poseTimes.remove(d.entity());
                events.add(new LinkEvent.Despawn(d.entity()));
            }
            case Control.Input in -> {
                boolean requested = role == Role.HOST;
                if (!requested) focus = in.focus();
                events.add(new LinkEvent.Focus(in.focus(), requested));
            }
            case Control.FrameChannel f -> events.add(new LinkEvent.FrameChannel(f));
            case Control.Bye b -> { }
            case Control.Unknown u -> { }
        }
        return null;
    }

    private String[] onHello(Control.Hello h, long now) {
        Role expected = role.other();
        if (h.role() != expected) return new String[] {"peer claims role " + h.role().wire, "bye"};
        if (h.udpPort() == 0) return new String[] {"peer announced udp_port 0", "bye"};
        int protocol;
        long session;
        if (role == Role.HOST) {
            int v = Wire.negotiate(Wire.MIN_PROTOCOL_VERSION, Wire.PROTOCOL_VERSION, h.minProtocol(), h.protocol());
            if (v < 0) {
                return new String[] {"unsupported protocol: peer " + h.minProtocol() + ".." + h.protocol()
                        + ", ours " + Wire.MIN_PROTOCOL_VERSION + ".." + Wire.PROTOCOL_VERSION, "bye"};
            }
            protocol = v;
            int s;
            do {
                s = random.nextInt();
            } while (s == 0);
            session = Integer.toUnsignedLong(s);
        } else {
            if (h.protocol() < Wire.MIN_PROTOCOL_VERSION || h.protocol() > Wire.PROTOCOL_VERSION) {
                return new String[] {"host chose unsupported protocol " + h.protocol(), "bye"};
            }
            if (h.session() == null || h.session() == 0) {
                return new String[] {"host hello without a nonzero session", "bye"};
            }
            protocol = h.protocol();
            session = h.session();
        }
        long timeoutMs = Math.max(cfg.timeoutMs, 4 * h.heartbeatMs());
        PeerInfo peer = new PeerInfo(h.game(), h.role(), protocol, (int) session,
                new InetSocketAddress(LOOPBACK, h.udpPort()), h.heartbeatMs(), timeoutMs);
        Ctl ctl = ((Handshake) conn).ctl();
        if (role == Role.HOST) {
            ctl.queue(hello(protocol, session));
            linesSent++;
            ctl.flush();
        }
        resetSession();
        conn = new Live(ctl, peer, timeoutMs, now);
        lastBeat = now;
        events.add(new LinkEvent.Connected(peer));
        try {
            send(new Datagram.Heartbeat());
        } catch (WireException e) {
            return new String[] {e.getMessage(), "bye"};
        }
        return null;
    }

    private void resetSession() {
        focus = Role.HOST;
        lastCamera = null;
        poseTimes.clear();
        lastCollision = null;
        assembly = null;
        lastFrame = 0;
    }

    /** Ends the current connection, reporting it as lost (or as a failed handshake). */
    private void end(String reason, boolean bye) {
        long now = now();
        boolean wasLive = conn instanceof Live;
        Ctl ctl = ctl();
        conn = new Idle(role == Role.GUEST ? now + cfg.reconnectMs : now);
        if (ctl != null) retire(ctl, bye ? reason : null, now);
        resetSession();
        events.add(wasLive ? new LinkEvent.Lost(reason) : new LinkEvent.HandshakeFailed(reason));
    }

    private void pollUdp(long now) {
        for (int n = 0; n < 4096; n++) {
            InetSocketAddress from;
            try {
                rx.clear();
                from = (InetSocketAddress) udp.receive(rx);
            } catch (IOException e) {
                continue; // e.g. ICMP port-unreachable from an earlier send
            }
            if (from == null) break;
            if (!(conn instanceof Live live)) {
                foreign++;
                continue;
            }
            if (!from.equals(live.peer.udpAddr()) && !(from.getAddress().isLoopbackAddress()
                    && from.getPort() == live.peer.udpAddr().getPort())) {
                foreign++;
                continue;
            }
            int len = rx.position();
            Wire.Decoded d;
            try {
                Header h = Wire.decodeHeader(rx.array(), len, null);
                if (h.session() != live.peer.session() || h.version() != live.peer.protocol()) {
                    foreign++;
                    continue;
                }
                d = Wire.decode(rx.array(), len);
            } catch (WireException e) {
                malformed++;
                continue;
            }
            live.lastHeard = now;
            datagramsReceived++;
            onDatagram(d.header(), d.body());
        }
    }

    private void onDatagram(Header header, Datagram body) {
        switch (body) {
            case Datagram.Heartbeat h -> { }
            case Datagram.Unknown u -> { }
            case Datagram.CameraMsg c -> {
                if (lastCamera != null && !Wire.seqNewer(header.seq(), lastCamera)) {
                    stale++;
                    return;
                }
                lastCamera = header.seq();
                events.add(new LinkEvent.Camera(header, c.camera()));
            }
            case Datagram.PoseMsg p -> {
                List<Wire.Pose> fresh = new ArrayList<>(p.poses().size());
                for (Wire.Pose pose : p.poses()) {
                    long last = poseTimes.getOrDefault(pose.entity(), 0L);
                    // time_us is a u64 sampled from the wall clock; it never reaches the sign bit.
                    if (Long.compareUnsigned(pose.timeUs(), last) >= 0) {
                        poseTimes.put(pose.entity(), pose.timeUs());
                        fresh.add(pose);
                    } else {
                        stale++;
                    }
                }
                if (!fresh.isEmpty()) events.add(new LinkEvent.Poses(header, fresh));
            }
            case Datagram.CollisionMsg c -> onCollision(c.chunk());
            case Datagram.FrameMsg f -> {
                if (Long.compareUnsigned(f.frame().frame(), lastFrame) <= 0) {
                    stale++;
                    return;
                }
                lastFrame = f.frame().frame();
                events.add(new LinkEvent.Frame(header, f.frame()));
            }
        }
    }

    private void onCollision(Wire.CollisionChunk c) {
        if (lastCollision != null && !Wire.seqNewer(c.query(), lastCollision)) {
            stale++;
            return;
        }
        if (assembly != null && assembly.query == c.query()) {
            if (assembly.count != c.chunkCount()) {
                malformed++;
                return;
            }
        } else if (assembly != null && Wire.seqNewer(assembly.query, c.query())) {
            stale++;
            return;
        } else {
            assembly = new Assembly(c.query(), c.chunkCount(), c.center(), c.radius());
        }
        Assembly a = assembly;
        if (a.chunks.get(c.chunkIndex()) == null) {
            a.chunks.set(c.chunkIndex(), c.triangles());
            a.received++;
        }
        if (a.received == a.count) {
            assembly = null;
            lastCollision = a.query;
            List<float[]> all = new ArrayList<>();
            for (List<float[]> chunk : a.chunks) all.addAll(chunk);
            events.add(new LinkEvent.Collision(a.query, a.center, a.radius, all));
        }
    }

    private void pollTimers(long now) {
        if (conn instanceof Handshake h && now - h.since() > cfg.timeoutMs) {
            end("no hello before the timeout", true);
        } else if (conn instanceof Live l) {
            long silent = now - l.lastHeard;
            if (silent > l.timeoutMs) {
                end("watchdog: nothing heard for " + silent + " ms", true);
                return;
            }
            if (now - lastBeat >= cfg.heartbeatMs) {
                lastBeat = now;
                try {
                    send(new Datagram.Heartbeat());
                } catch (WireException ignored) {
                    // a heartbeat always encodes
                }
            }
        }
    }

    // ---------------------------------------------------------------- sending

    /** Sends one datagram to the peer and returns its {@code seq}. */
    private int send(Datagram body) throws WireException {
        if (!(conn instanceof Live live)) throw new WireException("not connected");
        Header h = new Header(live.peer.protocol(), 0, live.peer.session(), seq, Wire.nowUs());
        byte[] bytes = Wire.encode(h, body);
        seq++;
        try {
            if (udp.send(ByteBuffer.wrap(bytes), live.peer.udpAddr()) > 0) datagramsSent++;
            else sendDropped++;
        } catch (IOException e) {
            sendDropped++; // refused or would block: the next tick replaces it
        }
        return h.seq();
    }

    /** Sends the camera; returns the datagram's {@code seq} (a guest quotes it in Frame.camera_seq). */
    public synchronized int sendCamera(Wire.Camera camera) throws WireException {
        return send(new Datagram.CameraMsg(camera));
    }

    /** Sends poses, at most {@link Wire#MAX_POSES} per datagram. */
    public synchronized void sendPoses(List<Wire.Pose> poses) throws WireException {
        for (int i = 0; i < poses.size(); i += Wire.MAX_POSES) {
            send(new Datagram.PoseMsg(poses.subList(i, Math.min(poses.size(), i + Wire.MAX_POSES))));
        }
    }

    /** Sends a collision snapshot in as many chunks as it needs; returns its query number. */
    public synchronized int sendCollision(float[] center, float radius, List<float[]> triangles)
            throws WireException {
        int query = nextQuery;
        for (Wire.CollisionChunk c : Wire.chunks(query, center, radius, triangles)) {
            send(new Datagram.CollisionMsg(c));
        }
        nextQuery++;
        return query;
    }

    public synchronized void sendFrame(Wire.FrameReady frame) throws WireException {
        send(new Datagram.FrameMsg(frame));
    }

    public synchronized void spawn(Control.Spawn spawn) throws WireException {
        sendControl(spawn);
    }

    public synchronized void despawn(long entity) throws WireException {
        sendControl(new Control.Despawn(entity));
    }

    /** Host: gives the focus to {@code focus}. Guest: asks the host for it. */
    public synchronized void setFocus(Role focus) throws WireException {
        sendControl(new Control.Input(focus, null));
        if (role == Role.HOST) this.focus = focus;
    }

    private void sendControl(Control c) throws WireException {
        if (!(conn instanceof Live live)) throw new WireException("not connected");
        linesSent++;
        live.ctl.queue(c);
        String err = live.ctl.flush();
        if (err != null) {
            end(err, false);
            throw new WireException("not connected");
        }
    }

    /** Ends the session with a bye (the caller knows; no Lost event is reported for it). */
    public synchronized void disconnect(String reason) {
        if (ctl() != null) {
            end(reason, true);
            if (!events.isEmpty()) events.remove(events.size() - 1);
        }
    }

    /** Says bye and closes gracefully; may block for up to the 200 ms linger. */
    @Override
    public synchronized void close() {
        long now = now();
        Ctl ctl = ctl();
        if (conn instanceof Connecting c) {
            try {
                c.ch().close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
        conn = new Idle(now);
        if (ctl != null) retire(ctl, "closing", now);
        long stop = now + Wire.LINGER_MS + 50;
        while (!closing.isEmpty() && now() < stop) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long t = now();
            closing.removeIf(c -> !c.pump(t));
        }
        for (Closing c : closing) c.ctl.closeQuietly();
        closing.clear();
        try {
            udp.close();
            if (listener != null) listener.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
