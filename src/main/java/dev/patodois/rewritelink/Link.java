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
import java.util.ArrayDeque;
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
    /**
     * 127.0.0.1 itself, as the contract requires. {@code InetAddress.getLoopbackAddress()} is
     * {@code ::1} when the JVM runs with {@code -Djava.net.preferIPv6Addresses=system} (as the
     * NeoForge and Forge launchers do), and a 127.0.0.1 peer then never connects.
     */
    private static final InetAddress LOOPBACK = ipv4Loopback();

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError("a 4-byte address is always valid", e);
        }
    }

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
    /** Our heartbeat period: {@link LinkConfig#heartbeatMs} within what a hello may announce. */
    private final long heartbeatMs;
    /** Collision chunks waiting to be sent, {@link Wire#COLLISION_PER_POLL} per poll. */
    private final ArrayDeque<Wire.CollisionChunk> collisionQueue = new ArrayDeque<>();

    // per-session state
    private Integer lastCamera;
    /** The peer's live spawned entities, each with the time_us of its newest pose. */
    private final Map<Long, Long> entities = new HashMap<>();
    private boolean spawnIgnoredLogged;
    private Integer lastCollision;
    private Assembly assembly;
    private long lastFrame;

    // diagnostics
    public long datagramsSent, datagramsReceived, malformed, stale, foreign, sendDropped,
            linesSent, linesReceived;
    /** Pose records for an entity the peer never spawned (or already despawned); not delivered. */
    public long unknownEntity;
    /** Spawns ignored because the peer already had {@link Wire#MAX_ENTITIES} live entities. */
    public long spawnIgnored;

    private final ByteBuffer rx = ByteBuffer.allocate(2 * Wire.MAX_DATAGRAM);

    // ---------------------------------------------------------------- connection states

    private interface Conn {}

    /** No connection. A guest retries at {@code nextTry}; a host waits. */
    private static final class Idle implements Conn {
        private final long nextTry;

        Idle(long nextTry) {
            this.nextTry = nextTry;
        }

        public long nextTry() {
            return nextTry;
        }
    }

    /** Guest: a non-blocking connect is in flight. */
    private static final class Connecting implements Conn {
        private final SocketChannel ch;
        private final long since;

        Connecting(SocketChannel ch, long since) {
            this.ch = ch;
            this.since = since;
        }

        public SocketChannel ch() {
            return ch;
        }

        public long since() {
            return since;
        }
    }

    /** Connected, waiting for the peer's hello. */
    private static final class Handshake implements Conn {
        private final Ctl ctl;
        private final long since;

        Handshake(Ctl ctl, long since) {
            this.ctl = ctl;
            this.since = since;
        }

        public Ctl ctl() {
            return ctl;
        }

        public long since() {
            return since;
        }
    }

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
        static final class Read {
            private final List<String> lines;
            private final String end;

            public Read(List<String> lines, String end) {
                this.lines = lines;
                this.end = end;
            }

            public List<String> lines() {
                return lines;
            }

            public String end() {
                return end;
            }
        }

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
        this.heartbeatMs = Math.max(Wire.MIN_HEARTBEAT_MS, Math.min(Wire.MAX_HEARTBEAT_MS, cfg.heartbeatMs));
        long now = now();
        ServerSocketChannel l = null;
        DatagramChannel u = null;
        try {
            u = DatagramChannel.open();
            try {
                // Collision snapshots arrive in bursts (CONTRACT.md 2); the OS may grant less, or refuse.
                u.setOption(StandardSocketOptions.SO_RCVBUF, Wire.RECV_BUFFER_BYTES);
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
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
        Conn c = conn;
        return c instanceof Live ? ((Live) c).peer : null;
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
        pumpCollision();
        pollTimers(now);
        List<LinkEvent> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    private Control.Hello hello(int protocol, Long session) {
        return new Control.Hello(role, cfg.game, protocol, Wire.MIN_PROTOCOL_VERSION, udpPort(),
                heartbeatMs, cfg.timeoutMs, session);
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
        if (conn instanceof Idle && now >= ((Idle) conn).nextTry()) {
            try {
                SocketChannel ch = SocketChannel.open();
                ch.configureBlocking(false);
                ch.connect(new InetSocketAddress(LOOPBACK, cfg.controlPort));
                conn = new Connecting(ch, now);
            } catch (IOException e) {
                conn = new Idle(now + cfg.reconnectMs);
            }
        } else if (conn instanceof Connecting) {
            Connecting c = (Connecting) conn;
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
        if (conn instanceof Handshake) return ((Handshake) conn).ctl();
        if (conn instanceof Live) return ((Live) conn).ctl;
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
        if (conn instanceof Live) ((Live) conn).lastHeard = now;
        if (msg instanceof Control.Bye) return new String[] {"peer said bye: " + ((Control.Bye) msg).reason()};
        if (conn instanceof Handshake) {
            if (!(msg instanceof Control.Hello)) return new String[] {"expected hello", "bye"};
            return onHello((Control.Hello) msg, now);
        }
        if (msg instanceof Control.Hello) {
            return new String[] {"second hello", "bye"};
        } else if (msg instanceof Control.Spawn) {
            Control.Spawn sp = (Control.Spawn) msg;
            // A second spawn of a live id replaces the first; a new id needs a free place.
            if (!entities.containsKey(sp.entity())) {
                if (entities.size() >= Wire.MAX_ENTITIES) {
                    spawnIgnored++;
                    if (!spawnIgnoredLogged) {
                        spawnIgnoredLogged = true;
                        System.err.println("link-java: the peer has " + Wire.MAX_ENTITIES
                                + " live entities; further spawns are ignored (entity " + sp.entity() + " and later ones)");
                    }
                    return null;
                }
                entities.put(sp.entity(), 0L);
            }
            events.add(new LinkEvent.Spawn(sp));
        } else if (msg instanceof Control.Despawn) {
            Control.Despawn d = (Control.Despawn) msg;
            if (entities.remove(d.entity()) != null) events.add(new LinkEvent.Despawn(d.entity()));
        } else if (msg instanceof Control.Input) {
            Control.Input in = (Control.Input) msg;
            boolean requested = role == Role.HOST;
            if (!requested) focus = in.focus();
            events.add(new LinkEvent.Focus(in.focus(), requested));
        } else if (msg instanceof Control.FrameChannel) {
            // A new pixel file numbers its frames from 1 again (CONTRACT.md 8).
            lastFrame = 0;
            events.add(new LinkEvent.FrameChannel((Control.FrameChannel) msg));
        }
        // Bye was handled above; an Unknown type is ignored.
        return null;
    }

    private String[] onHello(Control.Hello h, long now) {
        Role expected = role.other();
        if (h.role() != expected) return new String[] {"peer claims role " + h.role().wire, "bye"};
        if (h.udpPort() == 0) return new String[] {"peer announced udp_port 0", "bye"};
        if (h.heartbeatMs() < Wire.MIN_HEARTBEAT_MS || h.heartbeatMs() > Wire.MAX_HEARTBEAT_MS) {
            return new String[] {"peer announced heartbeat_ms " + h.heartbeatMs() + ", outside " + Wire.MIN_HEARTBEAT_MS
                    + ".." + Wire.MAX_HEARTBEAT_MS, "bye"};
        }
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
        entities.clear();
        spawnIgnoredLogged = false;
        collisionQueue.clear();
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
            if (!(conn instanceof Live)) {
                foreign++;
                continue;
            }
            Live live = (Live) conn;
            if (!isPeerAddress(from, live.peer.udpAddr())) {
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

    /** Exactly 127.0.0.1 and the peer's announced UDP port (CONTRACT.md 2): not another loopback address, not ::1. */
    static boolean isPeerAddress(InetSocketAddress from, InetSocketAddress peer) {
        return from != null && from.getAddress() != null && from.getPort() == peer.getPort()
                && Arrays.equals(from.getAddress().getAddress(), LOOPBACK.getAddress());
    }

    private void onDatagram(Header header, Datagram body) {
        if (body instanceof Datagram.CameraMsg) {
            if (lastCamera != null && !Wire.seqNewer(header.seq(), lastCamera)) {
                stale++;
                return;
            }
            lastCamera = header.seq();
            events.add(new LinkEvent.Camera(header, ((Datagram.CameraMsg) body).camera()));
        } else if (body instanceof Datagram.PoseMsg) {
            List<Wire.Pose> poses = ((Datagram.PoseMsg) body).poses();
            List<Wire.Pose> fresh = new ArrayList<>(poses.size());
            for (Wire.Pose pose : poses) {
                Long last = entities.get(pose.entity());
                if (last == null) {
                    unknownEntity++; // never spawned (or despawned): no state is kept for it
                    continue;
                }
                // time_us is a u64 sampled from the wall clock; it never reaches the sign bit.
                if (Long.compareUnsigned(pose.timeUs(), last) >= 0) {
                    entities.put(pose.entity(), pose.timeUs());
                    fresh.add(pose);
                } else {
                    stale++;
                }
            }
            if (!fresh.isEmpty()) events.add(new LinkEvent.Poses(header, fresh));
        } else if (body instanceof Datagram.CollisionMsg) {
            onCollision(((Datagram.CollisionMsg) body).chunk());
        } else if (body instanceof Datagram.FrameMsg) {
            Wire.FrameReady f = ((Datagram.FrameMsg) body).frame();
            if (Long.compareUnsigned(f.frame(), lastFrame) <= 0) {
                stale++;
                return;
            }
            lastFrame = f.frame();
            events.add(new LinkEvent.Frame(header, f));
        }
        // A Heartbeat or an Unknown kind only counts as the peer being alive (done by the caller).
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
        if (conn instanceof Handshake && now - ((Handshake) conn).since() > cfg.timeoutMs) {
            end("no hello before the timeout", true);
        } else if (conn instanceof Live) {
            Live l = (Live) conn;
            long silent = now - l.lastHeard;
            if (silent > l.timeoutMs) {
                end("watchdog: nothing heard for " + silent + " ms", true);
                return;
            }
            if (now - lastBeat >= heartbeatMs) {
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
        if (!(conn instanceof Live)) throw new WireException("not connected");
        Live live = (Live) conn;
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

    /**
     * Queues a collision snapshot, in as many chunks as it needs, and returns its query number. The
     * chunks go out {@link Wire#COLLISION_PER_POLL} per {@link #poll()}, so a big snapshot never
     * overflows the receiver's socket buffer; a snapshot queued while an older one still has chunks
     * waiting replaces them (the receiver would discard the old query anyway).
     */
    public synchronized int sendCollision(float[] center, float radius, List<float[]> triangles)
            throws WireException {
        if (!(conn instanceof Live)) throw new WireException("not connected");
        int query = nextQuery;
        List<Wire.CollisionChunk> chunks = Wire.chunks(query, center, radius, triangles);
        collisionQueue.clear();
        collisionQueue.addAll(chunks);
        nextQuery++;
        return query;
    }

    /** Sends up to {@link Wire#COLLISION_PER_POLL} queued collision chunks. */
    private void pumpCollision() {
        for (int i = 0; i < Wire.COLLISION_PER_POLL && !collisionQueue.isEmpty(); i++) {
            try {
                send(new Datagram.CollisionMsg(collisionQueue.poll()));
            } catch (WireException e) {
                collisionQueue.clear(); // not connected any more
            }
        }
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

    /** Tells the peer about a pixel file (the {@code frame_channel} line of CONTRACT.md section 8). */
    public synchronized void announceFrames(Control.FrameChannel channel) throws WireException {
        sendControl(channel);
    }

    /** Tells the peer about the file a {@link PixelFile.Writer} fills, with the absolute path it was created at. */
    public synchronized void announceFrames(PixelFile.Writer writer) throws WireException {
        sendControl(new Control.FrameChannel(writer.path().toAbsolutePath().toString(), writer.width, writer.height, writer.slots));
    }

    private void sendControl(Control c) throws WireException {
        if (!(conn instanceof Live)) throw new WireException("not connected");
        Live live = (Live) conn;
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
        if (conn instanceof Connecting) {
            try {
                ((Connecting) conn).ch().close();
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
