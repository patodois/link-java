package dev.patodois.rewritelink;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.patodois.rewritelink.Control.Role;
import dev.patodois.rewritelink.LinkEvent.PeerInfo;
import dev.patodois.rewritelink.Wire.Datagram;
import dev.patodois.rewritelink.Wire.Header;
import dev.patodois.rewritelink.Wire.WireException;

/**
 * One side of a passthrough link (host or guest). Create it with {@link #host} or {@link #guest},
 * then call {@link #poll()} regularly; it does not wait for the network or the peer (a guest with an
 * endpoint file reads that file before each connection attempt). All public methods are synchronized, so a
 * game thread may send while a helper thread polls (see {@link LinkRunner}).
 *
 * <p>Everything binds and connects to 127.0.0.1 only.
 *
 * <p>A pending interrupt of the calling thread is not an error: on Java 8 every NIO channel operation, a non-blocking
 * one too, closes the channel when the thread's interrupt status is set, so {@link #poll()}, the send methods,
 * {@link #disconnect} and {@link #close()} clear the status while they work and set it again before they return.
 */
public final class Link implements AutoCloseable {
    private static final long CONNECT_TIMEOUT_MS = 1000;
    /**
     * Host: connections held at most while no session is live, each until its first complete line (CONTRACT.md 2,
     * revision 8). A new connection that finds this many displaces the oldest.
     */
    static final int MAX_CANDIDATES = 4;
    /**
     * Host: connections accepted at most per {@link #poll()} (CONTRACT.md 2, revision 8: the reference value, which the
     * shared tests assume). The others wait in the system's backlog for the next poll; a connection the host has not
     * accepted costs it nothing.
     */
    static final int MAX_ACCEPTS_PER_POLL = 8;
    /**
     * Sockets held at most while they close (the drain of CONTRACT.md 6.1; section 2, revision 8). When one more has to
     * be closed, the oldest is closed at once, without the rest of its drain.
     */
    static final int MAX_CLOSING = 16;
    /**
     * Datagrams one {@link #poll()} reads at most while a session is live (CONTRACT.md 2, revision 8: the reference
     * value); the rest wait in the socket for the next poll. This bounds how much of a tick datagrams from other senders
     * can take where the socket is not connected to the peer. A side that polls much less often than its peer sends
     * needs more than this between two polls, or its buffer fills: normal traffic is a collision snapshot of 32
     * datagrams a poll, a heartbeat, a camera and a few pose datagrams.
     */
    static final int MAX_DATAGRAMS_PER_POLL = 1024;
    /**
     * Datagrams one {@link #poll()} reads, and discards, at most while no session is live (CONTRACT.md 2, revision 8).
     * None of them can be the peer's, so the rest may stay in the socket's buffer, where the system drops what does not
     * fit.
     */
    static final int MAX_DATAGRAMS_WITHOUT_SESSION = 64;
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
    /** The settings of {@link LinkConfig}, copied when the link is created: later changes to it change nothing. */
    private final String game;
    private final int configuredPort;
    private final long timeoutMs;
    private final long reconnectMs;
    private final ServerSocketChannel listener; // host only
    /**
     * This side's UDP socket. During a session it is connected to the peer's address where that is allowed (CONTRACT.md
     * 2, revision 8), so the system drops every other sender's datagrams before they are queued. It is never
     * disconnected: on macOS dissolving the association rebinds the socket to every address, and elsewhere it can
     * release an automatic port. With port 0 configured the socket of a session that was connected is replaced by a new
     * one, on a new automatic port, when the session ends; the next hello announces it. With a fixed port the socket is
     * never connected: Java's {@link DatagramChannel} cannot connect a socket again to another address without
     * disconnecting it first, so the next session could not have it. There the address rule and the per-poll bound
     * are what keep other senders' datagrams out, as the contract says for a socket that cannot be connected.
     */
    private DatagramChannel udp;
    /** The address {@link #udp} is connected to (the live session's peer, or an old one it could not be freed of), or null. */
    private InetSocketAddress udpConnectedTo;
    /** The configured UDP port: 0 lets the socket be replaced, with its connection, for each session. */
    private final int configuredUdpPort;
    private final SecureRandom random = new SecureRandom();
    /**
     * Host: the token a guest must send, as UTF-8 bytes (null = accept any guest). Guest: the configured token, which
     * an endpoint file overrides for each attempt. Never printed, never put into an event or a bye.
     */
    private final String token;
    private final byte[] tokenBytes;
    private final Path endpointFile;
    /** Host: whether this link wrote {@link #endpointFile} and has not removed it yet. */
    private boolean endpointWritten;
    /** Host: the control port written into {@link #endpointFile}. */
    private int endpointPort;

    private Conn conn;
    /** When the previous {@link #poll()} began, to tell a pause of this side's own from the peer's silence (CONTRACT.md 7, revision 8). */
    private long lastPoll;
    /** Host: the connections held while no session is live, oldest first (CONTRACT.md 2, revision 8). */
    private final ArrayDeque<Candidate> candidates = new ArrayDeque<>();
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
    /**
     * The peer's live spawned entities, each with the time_us of the last pose used for it: 0 before the first, and
     * again after a second spawn of the entity (CONTRACT.md 7, revision 8).
     */
    private final Map<Long, Long> entities = new HashMap<>();
    /**
     * The time_us of the last pose this side sent for each of its own entities, which the next one may not go below
     * (CONTRACT.md 5, revision 8). Forgotten on spawn, despawn and at the end of the session.
     */
    private final Map<Long, Long> sentPoseTimes = new HashMap<>();
    private boolean spawnIgnoredLogged;
    private Integer lastCollision;
    private Assembly assembly;

    // diagnostics
    public long datagramsSent, datagramsReceived, malformed, stale, foreign, sendDropped,
            linesSent, linesReceived;
    /** Pose records for an entity the peer never spawned (or already despawned); not delivered. */
    public long unknownEntity;
    /** Spawns ignored because the peer already had {@link Wire#MAX_ENTITIES} live entities. */
    public long spawnIgnored;
    /**
     * Poses this side was asked to send with a time_us lower than the last one it sent for the entity, and sent with that
     * time plus one microsecond instead (CONTRACT.md 5, revision 8).
     */
    public long poseTimeRaised;

    private final ByteBuffer rx = ByteBuffer.allocate(2 * Wire.MAX_DATAGRAM);

    // ---------------------------------------------------------------- connection states

    private interface Conn {}

    /** No connection. A guest retries at {@code nextTry}; a host waits. */
    private static final class Idle implements Conn {
        final long nextTry;

        Idle(long nextTry) {
            this.nextTry = nextTry;
        }
    }

    /** Guest: a non-blocking connect is in flight. */
    private static final class Connecting implements Conn {
        final SocketChannel ch;
        final long since;
        /** The token this attempt sends (from the endpoint file, if there is one), or null. */
        final String token;

        Connecting(SocketChannel ch, long since, String token) {
            this.ch = ch;
            this.since = since;
            this.token = token;
        }
    }

    /**
     * Guest: connected, waiting for the host's hello. (A host holds its connections that have not sent a first line as
     * {@link Candidate}s instead.)
     */
    private static final class Handshake implements Conn {
        final Ctl ctl;
        /** When its wait for the host's hello began: at the connection, or later if this side was not running. */
        long since;

        Handshake(Ctl ctl, long since) {
            this.ctl = ctl;
            this.since = since;
        }
    }

    /**
     * Host: a connection that has not yet delivered a complete first line, while no session is live (CONTRACT.md 2,
     * revision 8). At most {@link #MAX_CANDIDATES} are held; the first whose first line is an acceptable hello becomes
     * the session.
     */
    private static final class Candidate {
        final Ctl ctl;
        /** When its wait for a first line began: when it was accepted, or later if this side was not running. */
        long since;

        Candidate(Ctl ctl, long since) {
            this.ctl = ctl;
            this.since = since;
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

        boolean queue(Control c) {
            byte[] line = c.toLine().getBytes(StandardCharsets.UTF_8);
            if (line.length > Wire.MAX_LINE) return false;
            if (wlen + line.length > wbuf.length) wbuf = Arrays.copyOf(wbuf, Math.max(wbuf.length * 2, wlen + line.length));
            System.arraycopy(line, 0, wbuf, wlen, line.length);
            wlen += line.length;
            return true;
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

        /**
         * Result of {@link #readLines}: complete lines, then the end reason (applies after them). A protocol error
         * (a line over 65536 bytes, or not UTF-8) is answered with a bye (CONTRACT 8 and 6.1); a connection that
         * merely ended is not.
         */
        static final class Read {
            private final List<String> lines;
            private final String end;
            private final boolean bye;

            public Read(List<String> lines, String end, boolean bye) {
                this.lines = lines;
                this.end = end;
                this.bye = bye;
            }

            public boolean bye() {
                return bye;
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
                if (len > Wire.MAX_LINE) return new Read(lines, "control line too long", true);
                try {
                    lines.add(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(rbuf, start, len)).toString());
                } catch (CharacterCodingException e) {
                    return new Read(lines, "control line is not UTF-8", true);
                }
                start = nl + 1;
            }
            if (start > 0) {
                System.arraycopy(rbuf, start, rbuf, 0, rlen - start);
                rlen -= start;
            }
            if (rlen >= Wire.MAX_LINE) return new Read(lines, "control line too long", true);
            return new Read(lines, end, false);
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

    private boolean closed;
    private String lastError;

    /**
     * Latest local error, or null after a successful TCP connection: a refused or missing endpoint file (the reason, never
     * its contents), a closed UDP channel or listener, a control line of our own that is too long. Never a token.
     */
    public synchronized String lastError() { return lastError; }

    private Link(Role role, LinkConfig cfg) throws IOException {
        this.role = role;
        if (cfg.game == null) throw new IllegalArgumentException("game must not be null");
        requireRange("controlPort", cfg.controlPort, 0, 65535);
        requireRange("udpPort", cfg.udpPort, 0, 65535);
        requireRange("timeoutMs", cfg.timeoutMs, 0, 0xFFFFFFFFL); // a hello carries it as a u32
        requireRange("reconnectMs", cfg.reconnectMs, 0, 0xFFFFFFFFL);
        this.game = cfg.game;
        this.configuredPort = cfg.controlPort;
        this.configuredUdpPort = cfg.udpPort;
        this.timeoutMs = cfg.timeoutMs;
        this.reconnectMs = cfg.reconnectMs;
        this.heartbeatMs = Math.max(Wire.MIN_HEARTBEAT_MS, Math.min(Wire.MAX_HEARTBEAT_MS, cfg.heartbeatMs));
        if (cfg.token != null) Tokens.requireUsable(cfg.token);
        // A host with an endpoint file but no token makes its own, 64 hex digits, new each time it starts.
        this.token = cfg.token != null ? cfg.token : (role == Role.HOST && cfg.endpointFile != null ? Tokens.generate() : null);
        this.tokenBytes = token == null ? null : token.getBytes(StandardCharsets.UTF_8);
        this.endpointFile = cfg.endpointFile;
        long now = now();
        InetSocketAddress udpAddr = new InetSocketAddress(LOOPBACK, cfg.udpPort);
        InetSocketAddress controlAddr = new InetSocketAddress(LOOPBACK, cfg.controlPort);
        ServerSocketChannel l = null;
        DatagramChannel u = null;
        try {
            u = openUdp(udpAddr);
            if (role == Role.HOST) {
                l = ServerSocketChannel.open();
                l.bind(controlAddr);
                l.configureBlocking(false);
            }
            if (role == Role.HOST && endpointFile != null) {
                // Only after the bind: a host that lost the port to someone else writes nothing (CONTRACT.md 6.2).
                int bound = ((InetSocketAddress) l.getLocalAddress()).getPort();
                Endpoint.write(endpointFile, bound, token);
                endpointPort = bound;
                endpointWritten = true;
            }
        } catch (IOException | RuntimeException e) {
            if (u != null) u.close();
            if (l != null) l.close();
            throw e;
        }
        this.listener = l;
        this.udp = u;
        this.conn = new Idle(now);
        this.lastPoll = now;
    }

    /** A non-blocking UDP socket bound to {@code addr}, with the receive buffer the contract asks for. */
    private static DatagramChannel openUdp(InetSocketAddress addr) throws IOException {
        DatagramChannel u = DatagramChannel.open();
        try {
            try {
                // Collision snapshots arrive in bursts (CONTRACT.md 2); the OS may grant less, or refuse.
                u.setOption(StandardSocketOptions.SO_RCVBUF, Wire.RECV_BUFFER_BYTES);
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
            u.bind(addr);
            u.configureBlocking(false);
            return u;
        } catch (IOException | RuntimeException e) {
            u.close();
            throw e;
        }
    }

    private static void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be " + min + " to " + max + ", not " + value);
    }

    /**
     * Listens for one guest on 127.0.0.1:{@code cfg.controlPort}. The settings are checked and copied: later changes to
     * {@code cfg} do not affect the link.
     *
     * <p>Any local process can connect to that port, so a connection proves nothing until its first line has been read
     * (CONTRACT.md 2, revision 8). While no session is live the host holds up to four connections that have not sent a
     * complete line yet, and the first whose first line is an acceptable hello becomes the session; the others are then
     * told {@code busy}. A fifth connection displaces the oldest, which is closed with a bye of its own reason (no event
     * reports it). Each connection is read as soon as it is accepted, and the ones held are read, oldest first, before
     * new ones are accepted, so a guest that sends its hello as soon as it is connected is never the one displaced. A
     * connection that is refused (any other first line) is reported as {@link LinkEvent.HandshakeFailed} and does not
     * affect the others; one that sends no complete line within {@code timeoutMs} is closed and reported the same way.
     * While a session is live every other connection is told {@code busy}, as before.
     *
     * @throws IllegalArgumentException if {@code game} is null, a port is outside 0 to 65535, {@code timeoutMs} or
     *         {@code reconnectMs} outside 0 to 4294967295, or the token unusable (see {@link LinkConfig#token})
     */
    public static Link host(LinkConfig cfg) throws IOException {
        return new Link(Role.HOST, cfg);
    }

    /**
     * Connects to a host on 127.0.0.1:{@code cfg.controlPort}, retrying until one answers. The settings are checked and
     * copied as for {@link #host}.
     *
     * @throws IllegalArgumentException as for {@link #host}
     */
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
            return listener == null ? configuredPort : ((InetSocketAddress) listener.getLocalAddress()).getPort();
        } catch (IOException e) {
            return configuredPort;
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
        if (closed) return java.util.Collections.emptyList();
        boolean interrupted = Thread.interrupted(); // see the class comment
        try {
            long now = now();
            long sinceLast = now - lastPoll;
            lastPoll = now;
            afterAPause(sinceLast, now);
            pumpClosing(now);
            pollConnect(now);
            pollControl(now);
            pollUdp(now);
            pumpCollision();
            pollTimers(now);
            List<LinkEvent> out = new ArrayList<>(events);
            events.clear();
            return out;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * CONTRACT.md 7, revision 8: a side that finds more than a timeout between two of its own polls was not running
     * (suspended, stopped in a debugger, a machine that slept) and cannot judge the other side's silence by that timeout.
     * Every wait by which it judges the other side starts again at this poll: the watchdog, a host's wait for a
     * candidate's first line and a guest's wait for the host's hello. A shorter gap counts as it always did. (A side that
     * polls less often than its timeout is not conforming: its peer hears no heartbeat for that long.)
     */
    private void afterAPause(long sinceLast, long now) {
        if (sinceLast > timeoutMs) {
            for (Candidate c : candidates) c.since = now;
            if (conn instanceof Handshake) ((Handshake) conn).since = now;
        }
        if (conn instanceof Live && sinceLast > ((Live) conn).timeoutMs) ((Live) conn).lastHeard = now;
    }

    /** Makes progress on the connections being closed and forgets the ones that are done. */
    private void pumpClosing(long now) {
        closing.removeIf(c -> !c.pump(now));
    }

    /** Only a guest's hello carries a token (CONTRACT.md 6.2). */
    private Control.Hello hello(int protocol, Long session, String guestToken) {
        return new Control.Hello(role, game, protocol, Wire.MIN_PROTOCOL_VERSION, udpPort(),
                heartbeatMs, timeoutMs, session, role == Role.GUEST ? guestToken : null);
    }

    private void pollConnect(long now) {
        if (listener != null) {
            // The candidates already held are read first, oldest first, before anything new is accepted (CONTRACT.md 2,
            // revision 8).
            List<Candidate> held = new ArrayList<>(candidates);
            candidates.clear();
            for (Candidate c : held) {
                if (conn instanceof Live) {
                    retire(c.ctl, "busy", now);
                } else {
                    Candidate still = readCandidate(c, now);
                    if (still != null) candidates.addLast(still);
                }
            }
            for (int accepted = 0; accepted < MAX_ACCEPTS_PER_POLL; accepted++) {
                SocketChannel ch;
                try {
                    ch = listener.accept();
                } catch (ClosedChannelException e) {
                    lastError = "the control listener is closed";
                    break;
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
                if (conn instanceof Live) {
                    retire(ctl, "busy", now); // answered before anything of it is read (sections 2 and 8, bye)
                    continue;
                }
                if (candidates.size() >= MAX_CANDIDATES) {
                    // Its own reason, not busy: no session is live, and the peer may connect again.
                    retire(candidates.pollFirst().ctl, "no first line before newer connections arrived", now);
                }
                // Read at once, before the next connection is accepted.
                Candidate still = readCandidate(new Candidate(ctl, now), now);
                if (still != null) candidates.addLast(still);
            }
            return;
        }
        if (conn instanceof Idle && now >= ((Idle) conn).nextTry) {
            int port = configuredPort;
            String attemptToken = token;
            if (endpointFile != null) {
                // Read before every attempt: the host may have restarted with another port and token. No file, or a
                // refused one: no attempt this time, no event, try again after the reconnect delay; lastError says why.
                try {
                    Endpoint ep = Endpoint.read(endpointFile);
                    port = ep.controlPort();
                    attemptToken = ep.token();
                } catch (IOException | RuntimeException e) {
                    lastError = endpointError(e);
                    conn = new Idle(now + reconnectMs);
                    return;
                }
            }
            InetSocketAddress addr = new InetSocketAddress(LOOPBACK, port); // before the channel, which it cannot leak
            try {
                SocketChannel ch = SocketChannel.open();
                ch.configureBlocking(false);
                ch.connect(addr);
                conn = new Connecting(ch, now, attemptToken);
            } catch (IOException e) {
                conn = new Idle(now + reconnectMs);
            }
        } else if (conn instanceof Connecting) {
            Connecting c = (Connecting) conn;
            try {
                if (c.ch.finishConnect()) {
                    Ctl ctl = new Ctl(c.ch);
                    if(!ctl.queue(hello(Wire.PROTOCOL_VERSION, null, c.token))) {
                        lastError="local hello control line too long";
                        events.add(new LinkEvent.HandshakeFailed(lastError));
                        ctl.closeQuietly();conn=new Idle(now+reconnectMs);return;
                    }
                    lastError=null;
                    linesSent++;
                    ctl.flush();
                    conn = new Handshake(ctl, now);
                } else if (now - c.since > CONNECT_TIMEOUT_MS) {
                    abortConnect(c, now);
                }
            } catch (IOException e) {
                abortConnect(c, now);
            }
        }
    }

    /**
     * Why the endpoint file was not used, for {@link #lastError()}. {@link Endpoint#read} words its own refusals without
     * quoting the file; other exceptions (from the file system) are named by their class only, since their messages hold
     * the path.
     */
    private static String endpointError(Exception e) {
        if (e instanceof java.nio.file.NoSuchFileException) return "the endpoint file does not exist";
        if (e.getClass() == IOException.class && e.getMessage() != null) return e.getMessage();
        return "the endpoint file cannot be read (" + e.getClass().getSimpleName() + ")";
    }

    private void abortConnect(Connecting c, long now) {
        try {
            c.ch.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        conn = new Idle(now + reconnectMs);
    }

    /** Closes a control connection gracefully, after an optional bye. */
    private void retire(Ctl ctl, String bye, long now) {
        if (bye != null) {
            if(ctl.queue(new Control.Bye(bye))) linesSent++;
            else lastError="local bye control line too long";
        }
        Closing c = new Closing(ctl, now);
        if (c.pump(now)) {
            // At most MAX_CLOSING (CONTRACT.md 2 and 6.1, revision 8): the oldest is closed at once, without the rest of
            // its drain, so connections that never read cannot cost a socket each for the linger time.
            if (closing.size() >= MAX_CLOSING) closing.remove(0).ctl.closeQuietly();
            closing.add(c);
        }
    }

    /** For the tests: how many sockets are closing. */
    synchronized int closingCount() {
        return closing.size();
    }

    private Ctl ctl() {
        if (conn instanceof Handshake) return ((Handshake) conn).ctl;
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
        if (read.end() != null) end(read.end(), read.bye());
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
        if (msg instanceof Control.Bye) return new String[] {peerSaidBye(((Control.Bye) msg).reason())};
        if (conn instanceof Handshake) {
            // Guest: the host's hello. (A host reads the first line of a candidate in readCandidate.)
            if (!(msg instanceof Control.Hello)) return new String[] {"expected hello as the first line", "bye"};
            Checked checked = checkHello((Control.Hello) msg);
            if (checked.refusal != null) return new String[] {checked.refusal, "bye"};
            String err = startSession(((Handshake) conn).ctl, checked.peer, now);
            return err == null ? null : new String[] {err, "bye"};
        }
        if (msg instanceof Control.Hello) {
            return new String[] {"second hello", "bye"};
        } else if (msg instanceof Control.Spawn) {
            Control.Spawn sp = (Control.Spawn) msg;
            // A second spawn of a live id replaces the first; a new id needs a free place.
            if (!entities.containsKey(sp.entity()) && entities.size() >= Wire.MAX_ENTITIES) {
                spawnIgnored++;
                if (!spawnIgnoredLogged) {
                    spawnIgnoredLogged = true;
                    System.err.println("link-java: the peer has " + Wire.MAX_ENTITIES
                            + " live entities; further spawns are ignored (entity " + sp.entity() + " and later ones)");
                }
                return null;
            }
            // A new entity has no pose yet, and neither has one that replaces a live entity: its next pose is used
            // whatever its time (CONTRACT.md 7 and 8, spawn, revision 8).
            entities.put(sp.entity(), 0L);
            events.add(new LinkEvent.Spawn(sp.name() == null ? sp
                    : new Control.Spawn(sp.entity(), sp.provider(), sp.asset(), forPeople(sp.name()))));
        } else if (msg instanceof Control.Despawn) {
            Control.Despawn d = (Control.Despawn) msg;
            if (entities.remove(d.entity()) != null) events.add(new LinkEvent.Despawn(d.entity()));
        } else if (msg instanceof Control.Input) {
            Control.Input in = (Control.Input) msg;
            boolean requested = role == Role.HOST;
            if (!requested) focus = in.focus();
            events.add(new LinkEvent.Focus(in.focus(), requested));
        } else if (msg instanceof Control.FrameChannel) {
            // The consumer replaces its reader and presentation state for this channel.
            events.add(new LinkEvent.FrameChannel((Control.FrameChannel) msg));
        }
        // Bye was handled above; an Unknown type is ignored.
        return null;
    }

    /**
     * Text from the peer meant for people (the reason of its bye, the game of its hello, a spawn name), as the
     * application gets it: every char below U+0020 and U+007F replaced by a space, so a peer cannot write a forged log
     * line or a terminal escape through it (CONTRACT.md 8, bye, revision 8). Those are single UTF-8 bytes, never part
     * of a longer character. Identifiers ({@code provider}, {@code asset}, {@code path}) are handed on as sent.
     */
    static String forPeople(String s) {
        char[] c = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                if (c == null) c = s.toCharArray();
                c[i] = ' ';
            }
        }
        return c == null ? s : new String(c);
    }

    /** The reason of the Lost or HandshakeFailed event that a bye from the peer gives: its own text, cleaned. */
    private static String peerSaidBye(String reason) {
        return "peer said bye: " + forPeople(reason);
    }

    /**
     * Host: reads candidate {@code c} and acts on its first line, if a complete one has arrived (CONTRACT.md 2, revision
     * 8). Returns the candidate if it still is one. A first line that is an acceptable hello makes it the session, and
     * every other candidate is answered busy; any other first line is refused as section 6 says, and the other
     * candidates are not affected.
     */
    private Candidate readCandidate(Candidate c, long now) {
        Ctl.Read read = c.ctl.readLines();
        List<String> lines = read.lines();
        if (lines.isEmpty()) {
            if (read.end() == null) return c;
            refuse(c.ctl, read.end(), read.bye(), now);
            return null;
        }
        linesReceived++;
        Control msg;
        try {
            msg = Control.parse(lines.get(0));
        } catch (WireException e) {
            refuse(c.ctl, "protocol error: " + e.getMessage(), true, now);
            return null;
        }
        if (msg instanceof Control.Bye) {
            refuse(c.ctl, peerSaidBye(((Control.Bye) msg).reason()), false, now);
            return null;
        }
        if (!(msg instanceof Control.Hello)) {
            refuse(c.ctl, "expected hello as the first line", true, now);
            return null;
        }
        Checked checked = checkHello((Control.Hello) msg);
        if (checked.refusal != null) {
            refuse(c.ctl, checked.refusal, true, now);
            return null;
        }
        String err = startSession(c.ctl, checked.peer, now);
        if (err != null) {
            refuse(c.ctl, err, true, now);
            return null;
        }
        for (Candidate other : candidates) retire(other.ctl, "busy", now);
        candidates.clear();
        // What came after the hello in the same read belongs to the session.
        for (int i = 1; i < lines.size(); i++) {
            if (!(conn instanceof Live)) return null;
            linesReceived++;
            String[] fail = onLine(lines.get(i), now);
            if (fail != null) {
                end(fail[0], fail.length > 1);
                return null;
            }
        }
        if (read.end() != null && conn instanceof Live) end(read.end(), read.bye());
        return null;
    }

    /** Host: closes a candidate whose handshake failed, with a bye when {@code bye} is set, and reports it. */
    private void refuse(Ctl ctl, String reason, boolean bye, long now) {
        retire(ctl, bye ? reason : null, now);
        events.add(new LinkEvent.HandshakeFailed(reason));
    }

    /** What {@link #checkHello} found: the peer, or why its hello is refused. */
    private static final class Checked {
        final PeerInfo peer;
        final String refusal;

        Checked(PeerInfo peer, String refusal) {
            this.peer = peer;
            this.refusal = refusal;
        }
    }

    /**
     * The checks of CONTRACT.md 6 on the peer's hello, the token first (6.2). A refusal is a fixed text that quotes
     * nothing from the line (section 8, bye, revision 8): it names the rule, never the value.
     */
    private Checked checkHello(Control.Hello h) {
        // A host with a token checks it first, before any other check, in constant time. The answer is the fixed word
        // and nothing about which check failed; neither token is ever put into it.
        if (role == Role.HOST && tokenBytes != null
                && (h.token() == null || !Tokens.equal(h.token().getBytes(StandardCharsets.UTF_8), tokenBytes))) {
            return new Checked(null, "unauthorized");
        }
        Role expected = role.other();
        if (h.role() != expected) return new Checked(null, "role is not " + expected.wire);
        if (h.udpPort() == 0) return new Checked(null, "udp_port outside 1 to 65535");
        if (h.heartbeatMs() < Wire.MIN_HEARTBEAT_MS || h.heartbeatMs() > Wire.MAX_HEARTBEAT_MS) {
            return new Checked(null, "heartbeat_ms outside " + Wire.MIN_HEARTBEAT_MS + " to " + Wire.MAX_HEARTBEAT_MS);
        }
        int protocol;
        long session;
        if (role == Role.HOST) {
            int v = Wire.negotiate(Wire.MIN_PROTOCOL_VERSION, Wire.PROTOCOL_VERSION, h.minProtocol(), h.protocol());
            if (v < 0) {
                return new Checked(null, "unsupported protocol: the version ranges do not overlap");
            }
            protocol = v;
            int s;
            do {
                s = random.nextInt();
            } while (s == 0);
            session = Integer.toUnsignedLong(s);
        } else {
            if (h.protocol() < Wire.MIN_PROTOCOL_VERSION || h.protocol() > Wire.PROTOCOL_VERSION) {
                return new Checked(null, "unsupported protocol: the host chose a version outside this side's range");
            }
            if (h.session() == null || h.session() == 0) {
                return new Checked(null, "host hello without a nonzero session");
            }
            protocol = h.protocol();
            session = h.session();
        }
        long watchdogMs = Math.max(timeoutMs, 4 * h.heartbeatMs());
        return new Checked(new PeerInfo(forPeople(h.game()), h.role(), protocol, (int) session,
                new InetSocketAddress(LOOPBACK, h.udpPort()), h.heartbeatMs(), watchdogMs), null);
    }

    /**
     * Makes {@code ctl} the session's connection: queues a host's reply, reports {@link LinkEvent.Connected} and sends
     * the first heartbeat. Returns null, or why the session could not start ({@code ctl} is then not taken).
     */
    private String startSession(Ctl ctl, PeerInfo peer, long now) {
        if (role == Role.HOST) {
            if (!ctl.queue(hello(peer.protocol(), Integer.toUnsignedLong(peer.session()), null))) {
                lastError = "local hello control line too long";
                return lastError;
            }
            linesSent++;
        }
        resetSession();
        // Before the reply goes out, so that none of the peer's first datagrams can arrive before the connect.
        connectUdp(peer.udpAddr());
        if (role == Role.HOST) ctl.flush();
        conn = new Live(ctl, peer, peer.timeoutMs(), now);
        lastError = null;
        lastBeat = now;
        events.add(new LinkEvent.Connected(peer));
        try {
            send(new Datagram.Heartbeat());
        } catch (WireException e) {
            end(e.getMessage(), true);
        }
        return null;
    }

    private void resetSession() {
        focus = Role.HOST;
        lastCamera = null;
        entities.clear();
        sentPoseTimes.clear();
        spawnIgnoredLogged = false;
        collisionQueue.clear();
        lastCollision = null;
        assembly = null;

    }

    /** Ends the current connection, reporting it as lost (or as a failed handshake). */
    private void end(String reason, boolean bye) {
        long now = now();
        boolean wasLive = conn instanceof Live;
        Ctl ctl = ctl();
        conn = new Idle(role == Role.GUEST ? now + reconnectMs : now);
        if (ctl != null) retire(ctl, bye ? reason : null, now);
        resetSession();
        if (wasLive) renewUdp();
        events.add(wasLive ? new LinkEvent.Lost(reason) : new LinkEvent.HandshakeFailed(reason));
    }

    /**
     * Connects the UDP socket to the session's peer (CONTRACT.md 2, revision 8, a SHOULD), where the socket may be
     * replaced afterwards (port 0 configured; see {@link #udp}). Best effort: a socket that cannot be connected stays as
     * it was, and the address rule and the per-poll bound still hold.
     */
    private void connectUdp(InetSocketAddress peer) {
        if (configuredUdpPort != 0) return;
        if (udpConnectedTo != null) renewUdp(); // the previous session's socket could not be replaced then
        if (udpConnectedTo != null) return;
        try {
            udp.connect(peer);
            udpConnectedTo = peer;
        } catch (IOException | RuntimeException e) {
            // not connected: the address rule decides
        }
    }

    /**
     * After a session on a connected socket, a new socket on a new automatic port, so that the old one never has to be
     * disconnected (CONTRACT.md 2, revision 8). If no new socket can be had, the old one is kept, and the next session
     * tries again.
     */
    private void renewUdp() {
        if (udpConnectedTo == null) return;
        boolean interrupted = Thread.interrupted(); // see the class comment
        try {
            DatagramChannel fresh = openUdp(new InetSocketAddress(LOOPBACK, 0));
            DatagramChannel old = udp;
            udp = fresh;
            udpConnectedTo = null;
            old.close();
        } catch (IOException | RuntimeException e) {
            // keep the old socket
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void pollUdp(long now) {
        // Bounded per poll (CONTRACT.md 2, revision 8): with no session none of them can be the peer's.
        int budget = conn instanceof Live ? MAX_DATAGRAMS_PER_POLL : MAX_DATAGRAMS_WITHOUT_SESSION;
        for (int n = 0; n < budget; n++) {
            InetSocketAddress from;
            try {
                rx.clear();
                from = (InetSocketAddress) udp.receive(rx);
            } catch (ClosedChannelException e) {
                lastError = "the UDP channel is closed";
                break;
            } catch (IOException e) {
                // An unreachable peer port (PortUnreachableException: ECONNREFUSED, or WSAECONNRESET on Windows, which a
                // connected socket in particular reports for an earlier send) is a lost datagram, not the end of the
                // session; the watchdog decides (CONTRACT.md 2, revision 8). Any other error costs one of the budget.
                continue;
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
            // In record order (CONTRACT.md 9.4): a pose older than the last one used is dropped, unless it has the
            // TELEPORT flag, which starts the entity's order again at its own time (section 7, revision 8).
            for (Wire.Pose pose : poses) {
                Long last = entities.get(pose.entity());
                if (last == null) {
                    unknownEntity++; // never spawned (or despawned): no state is kept for it
                    continue;
                }
                // time_us is a u64 sampled from the wall clock; it never reaches the sign bit.
                if (Long.compareUnsigned(pose.timeUs(), last) >= 0 || (pose.flags() & Wire.POSE_TELEPORT) != 0) {
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
            // Slot validation and presentation order belong to the pixel consumer.

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
        // Host: a candidate that nothing displaced has the host's own timeout for its first line (CONTRACT.md 2 and 6,
        // revision 8).
        for (java.util.Iterator<Candidate> it = candidates.iterator(); it.hasNext(); ) {
            Candidate c = it.next();
            if (now - c.since > timeoutMs) {
                it.remove();
                refuse(c.ctl, "no hello before the timeout", true, now);
            }
        }
        if (conn instanceof Handshake && now - ((Handshake) conn).since > timeoutMs) {
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
        boolean interrupted = Thread.interrupted(); // see the class comment
        try {
            // A connected socket is connected to this very address; write() needs no destination check.
            int n = live.peer.udpAddr().equals(udpConnectedTo) ? udp.write(ByteBuffer.wrap(bytes)) : udp.send(ByteBuffer.wrap(bytes), live.peer.udpAddr());
            if (n > 0) datagramsSent++;
            else sendDropped++;
        } catch (ClosedChannelException e) {
            sendDropped++;
            lastError = "the UDP channel is closed";
        } catch (IOException e) {
            sendDropped++; // refused (an unreachable port is a lost datagram) or would block: the next tick replaces it
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        return h.seq();
    }

    /** Sends the camera; returns the datagram's {@code seq} (a guest quotes it in Frame.camera_seq). */
    public synchronized int sendCamera(Wire.Camera camera) throws WireException {
        return send(new Datagram.CameraMsg(camera));
    }

    /**
     * Sends poses, at most {@link Wire#MAX_POSES} per datagram.
     *
     * <p>An entity's pose times never go back (CONTRACT.md 5, revision 8): a pose whose {@code time_us} is lower than the
     * last one sent for its entity is sent with that time plus one microsecond instead (the largest 64-bit value stays
     * itself), and counted in {@link #poseTimeRaised}; a wall clock that steps back is carried that way until it has
     * caught up. A pose with {@link Wire#POSE_TELEPORT}, and the first pose after {@link #spawn} of the entity, may carry
     * any time and start the entity's order again; the receiver does the same. Times compare as unsigned 64-bit values.
     *
     * @throws WireException if not connected or an entity is outside 0..4294967295 (nothing is sent then)
     */
    public synchronized void sendPoses(List<Wire.Pose> poses) throws WireException {
        for (Wire.Pose p : poses) u32("entity", p.entity()); // all of them first: no datagram goes out for a bad list
        if (poses.isEmpty()) return;
        if (!(conn instanceof Live)) throw new WireException("not connected");
        List<Wire.Pose> ordered = new ArrayList<>(poses.size());
        for (Wire.Pose p : poses) {
            Long last = sentPoseTimes.get(p.entity());
            if (last != null && (p.flags() & Wire.POSE_TELEPORT) == 0 && Long.compareUnsigned(p.timeUs(), last) < 0) {
                long raised = last == -1L ? last : last + 1; // the largest u64 stays itself
                p = new Wire.Pose(p.entity(), p.flags(), raised, p.translation(), p.rotation(), p.scale(), p.velocity(),
                        p.angularVelocity());
                poseTimeRaised++;
            }
            sentPoseTimes.put(p.entity(), p.timeUs());
            ordered.add(p);
        }
        for (int i = 0; i < ordered.size(); i += Wire.MAX_POSES) {
            send(new Datagram.PoseMsg(ordered.subList(i, Math.min(ordered.size(), i + Wire.MAX_POSES))));
        }
    }

    /**
     * Queues a collision snapshot, in as many chunks as it needs, and returns its query number. The
     * chunks go out {@link Wire#COLLISION_PER_POLL} per {@link #poll()}, so a big snapshot never
     * overflows the receiver's socket buffer; a snapshot queued while an older one still has chunks
     * waiting replaces them (the receiver would discard the old query anyway).
     *
     * <p>A snapshot has at most {@link Wire#MAX_COLLISION_CHUNKS} chunks, {@code 4096 * 226 = 925696}
     * triangles (CONTRACT.md 9.5, revision 5). A bigger one is refused with a {@link WireException} before
     * anything is copied or queued, and the queue and the query number stay as they were; send the
     * triangles nearest to {@code center} instead.
     *
     * @throws WireException if the link is not connected, the snapshot is over the limit, or {@code center} is not 3
     *         floats or a triangle not 9; nothing is queued then
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

    /**
     * Announces one of this side's entities. A spawn of a live entity replaces it, and the entity's next pose may carry
     * any time (CONTRACT.md 5 and 8, revision 8).
     *
     * @throws WireException if not connected or the entity is outside 0..4294967295 (nothing is sent then)
     */
    public synchronized void spawn(Control.Spawn spawn) throws WireException {
        sendControl(spawn);
        sentPoseTimes.remove(spawn.entity());
    }

    /** @throws WireException if not connected or the entity is outside 0..4294967295 (nothing is sent then) */
    public synchronized void despawn(long entity) throws WireException {
        sendControl(new Control.Despawn(entity));
        sentPoseTimes.remove(entity);
    }

    /** Host: gives the focus to {@code focus}. Guest: asks the host for it. */
    public synchronized void setFocus(Role focus) throws WireException {
        sendControl(new Control.Input(focus, null));
        if (role == Role.HOST) this.focus = focus;
    }

    /**
     * Tells the peer about a pixel file (the {@code frame_channel} line of CONTRACT.md section 8).
     *
     * @throws WireException if not connected or width, height or slots is outside 0..4294967295 (nothing is sent then)
     */
    public synchronized void announceFrames(Control.FrameChannel channel) throws WireException {
        sendControl(channel);
    }

    /** Tells the peer about the file a {@link PixelFile.Writer} fills, with the absolute path it was created at. */
    public synchronized void announceFrames(PixelFile.Writer writer) throws WireException {
        sendControl(new Control.FrameChannel(writer.path().toAbsolutePath().toString(), writer.width, writer.height, writer.slots));
    }

    private void sendControl(Control c) throws WireException {
        if (!(conn instanceof Live)) throw new WireException("not connected");
        // The receiver refuses these numbers outside a u32 and ends the session: refuse them here, with nothing sent.
        if (c instanceof Control.Spawn) u32("entity", ((Control.Spawn) c).entity());
        else if (c instanceof Control.Despawn) u32("entity", ((Control.Despawn) c).entity());
        else if (c instanceof Control.FrameChannel) {
            Control.FrameChannel f = (Control.FrameChannel) c;
            u32("width", f.width());
            u32("height", f.height());
            u32("slots", f.slots());
        }
        Live live = (Live) conn;
        if (!live.ctl.queue(c)) throw new WireException("control line too long");
        linesSent++;
        boolean interrupted = Thread.interrupted(); // see the class comment
        String err;
        try {
            err = live.ctl.flush();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (err != null) {
            end(err, false);
            throw new WireException("not connected");
        }
    }

    private static void u32(String name, long value) throws WireException {
        if (value < 0 || value > 0xFFFFFFFFL) throw new WireException("bad value: " + name + " " + value + " is outside 0..4294967295");
    }

    /**
     * Ends the session with a bye (the caller knows; no Lost event is reported for it). A null {@code reason} sends
     * the empty reason: every close says bye (CONTRACT.md 6.1).
     */
    public synchronized void disconnect(String reason) {
        boolean interrupted = Thread.interrupted(); // see the class comment
        try {
            // A host with no session closes the connections it holds that have not sent their hello yet, with the same bye.
            long now = now();
            for (Candidate c : candidates) retire(c.ctl, reason == null ? "" : reason, now);
            candidates.clear();
            if (ctl() != null) {
                end(reason == null ? "" : reason, true);
                if (!events.isEmpty()) events.remove(events.size() - 1);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Says bye and closes gracefully; may block for up to the 200 ms linger. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        boolean interrupted = Thread.interrupted(); // see the class comment
        try {
            closeChannels();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void closeChannels() {
        long now = now();
        Ctl ctl = ctl();
        if (conn instanceof Connecting) {
            try {
                ((Connecting) conn).ch.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
        conn = new Idle(now);
        for (Candidate c : candidates) retire(c.ctl, "closing", now);
        candidates.clear();
        if (ctl != null) retire(ctl, "closing", now);
        long stop = now + Wire.LINGER_MS + 50;
        while (!closing.isEmpty() && now() < stop) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            pumpClosing(now());
        }
        for (Closing c : closing) c.ctl.closeQuietly();
        closing.clear();
        try {
            udp.close();
            if (listener != null) listener.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        if (endpointWritten) {
            // Only if it is still this host's file: another host may have written the same path since.
            endpointWritten = false;
            Endpoint.deleteIfOwn(endpointFile, endpointPort, tokenBytes);
        }
    }
}
