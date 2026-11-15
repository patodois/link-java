package dev.patodois.rewritelink;

import java.nio.file.Path;

/**
 * Settings for {@link Link#host} and {@link Link#guest}. Everything is loopback only. Creating a link checks the settings
 * (an {@link IllegalArgumentException} names the one out of range) and copies them: changing this object afterwards does
 * not affect a link already created.
 */
public final class LinkConfig {
    /** This side's game, sent in {@code hello}; not null. */
    public String game;
    /** Host: TCP port to listen on (0 = any free port). Guest: the host's port to connect to. 0 to 65535. */
    public int controlPort = Wire.DEFAULT_CONTROL_PORT;
    /** This side's UDP port (0 = any free port); announced in {@code hello}. 0 to 65535. */
    public int udpPort = 0;
    /** Heartbeat period; a hello carries it, and a peer accepts 10 to 10000 only, so the link clamps it to that range. */
    public long heartbeatMs = 250;
    /** Watchdog timeout, 0 to 4294967295 (a {@code hello} carries it); the effective one is at least four peer heartbeats. */
    public long timeoutMs = 2000;
    /** Guest: how often to retry connecting while no host answers, 0 to 4294967295. */
    public long reconnectMs = 500;

    /**
     * Contract revision 6 (CONTRACT.md 6.2), default null. Host: the token a guest's {@code hello} must carry (1 to 256
     * bytes of UTF-8, no U+0000); null means any guest is accepted, unless {@link #endpointFile} is set, which makes the
     * link generate one. Guest: the token to send. Creating a link with a token outside that range throws
     * {@link IllegalArgumentException}. Never printed by {@link #toString()}.
     */
    public String token;
    /**
     * Contract revision 6, default null. Host: after binding, the link writes this file with the control port it bound and
     * the token (see {@link Endpoint}), and removes it when it closes if the file still holds that port and token (another
     * host's file stays); if the file cannot be written, creating the host fails. Guest: before every connection attempt the link reads the file and uses its port and token instead of
     * {@link #controlPort} and {@link #token}; if the file cannot be read it makes no attempt that time.
     */
    public Path endpointFile;

    public LinkConfig(String game) {
        this.game = game;
    }

    /** The settings, without the token. */
    @Override
    public String toString() {
        return "LinkConfig[game=" + game + ", controlPort=" + controlPort + ", udpPort=" + udpPort + ", heartbeatMs=" + heartbeatMs
                + ", timeoutMs=" + timeoutMs + ", reconnectMs=" + reconnectMs + ", token=" + (token == null ? "null" : "<redacted>")
                + ", endpointFile=" + endpointFile + "]";
    }
}
