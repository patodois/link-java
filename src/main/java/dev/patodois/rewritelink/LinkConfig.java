package dev.patodois.rewritelink;

/** Settings for {@link Link#host} and {@link Link#guest}. Everything is loopback only. */
public final class LinkConfig {
    /** This side's game, sent in {@code hello}. */
    public String game;
    /** Host: TCP port to listen on (0 = any free port). Guest: the host's port to connect to. */
    public int controlPort = Wire.DEFAULT_CONTROL_PORT;
    /** This side's UDP port (0 = any free port); announced in {@code hello}. */
    public int udpPort = 0;
    public long heartbeatMs = 250;
    /** Watchdog timeout; the effective one is at least four peer heartbeats. */
    public long timeoutMs = 2000;
    /** Guest: how often to retry connecting while no host answers. */
    public long reconnectMs = 500;

    public LinkConfig(String game) {
        this.game = game;
    }
}
