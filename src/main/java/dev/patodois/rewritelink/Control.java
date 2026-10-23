package dev.patodois.rewritelink;

import java.math.BigInteger;
import java.util.Map;

import dev.patodois.rewritelink.Wire.WireException;

/** Control-channel messages: one JSON object per line (CONTRACT.md section 8). */
public sealed interface Control {

    enum Role {
        HOST("host"), GUEST("guest");

        public final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        public Role other() {
            return this == HOST ? GUEST : HOST;
        }

        static Role parse(String s) {
            for (Role r : values()) {
                if (r.wire.equals(s)) return r;
            }
            return null;
        }
    }

    /** {@code session} is null in a guest hello and a nonzero u32 (in a long) in a host hello. */
    record Hello(Role role, String game, int protocol, int minProtocol, int udpPort,
                 long heartbeatMs, long timeoutMs, Long session) implements Control {}

    record Spawn(long entity, String provider, String asset, String name) implements Control {}

    record Despawn(long entity) implements Control {}

    record Input(Role focus, String reason) implements Control {}

    record FrameChannel(String path, long width, long height, long slots) implements Control {}

    record Bye(String reason) implements Control {}

    /** A {@code type} this version does not know; ignored by receivers. */
    record Unknown(String type) implements Control {}

    /** The message as one JSON line, newline included. */
    default String toLine() {
        StringBuilder b = new StringBuilder();
        switch (this) {
            case Hello h -> {
                b.append("{\"type\":\"hello\",\"role\":").append(Json.quote(h.role().wire))
                        .append(",\"game\":").append(Json.quote(h.game()))
                        .append(",\"protocol\":").append(h.protocol())
                        .append(",\"min_protocol\":").append(h.minProtocol())
                        .append(",\"udp_port\":").append(h.udpPort())
                        .append(",\"heartbeat_ms\":").append(h.heartbeatMs())
                        .append(",\"timeout_ms\":").append(h.timeoutMs());
                if (h.session() != null) b.append(",\"session\":").append(h.session());
                b.append('}');
            }
            case Spawn s -> {
                b.append("{\"type\":\"spawn\",\"entity\":").append(s.entity())
                        .append(",\"provider\":").append(Json.quote(s.provider()))
                        .append(",\"asset\":").append(Json.quote(s.asset()));
                if (s.name() != null) b.append(",\"name\":").append(Json.quote(s.name()));
                b.append('}');
            }
            case Despawn d -> b.append("{\"type\":\"despawn\",\"entity\":").append(d.entity()).append('}');
            case Input in -> {
                b.append("{\"type\":\"input\",\"focus\":").append(Json.quote(in.focus().wire));
                if (in.reason() != null) b.append(",\"reason\":").append(Json.quote(in.reason()));
                b.append('}');
            }
            case FrameChannel f -> b.append("{\"type\":\"frame_channel\",\"path\":").append(Json.quote(f.path()))
                    .append(",\"width\":").append(f.width()).append(",\"height\":").append(f.height())
                    .append(",\"slots\":").append(f.slots()).append('}');
            case Bye y -> b.append("{\"type\":\"bye\",\"reason\":").append(Json.quote(y.reason())).append('}');
            case Unknown u -> b.append("{\"type\":").append(Json.quote(u.type())).append('}');
        }
        return b.append('\n').toString();
    }

    /**
     * Parses one line (with or without its line ending). Unknown types give {@link Unknown}; a
     * known type with a missing or out-of-range field is a {@link WireException}.
     */
    static Control parse(String line) throws WireException {
        while (line.endsWith("\n") || line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (line.length() >= Wire.MAX_LINE) throw new WireException("bad control line: line too long");
        Object v;
        try {
            v = Json.parse(line);
        } catch (Json.JsonException e) {
            throw new WireException("bad control line: " + e.getMessage());
        }
        if (!(v instanceof Map<?, ?> raw)) throw new WireException("bad control line: not an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) raw;
        if (!(o.get("type") instanceof String type)) {
            throw new WireException("bad control line: no string \"type\"");
        }
        return switch (type) {
            case "hello" -> {
                Role role = Role.parse(str(o, "role"));
                if (role == null) throw new WireException("bad control line: role");
                yield new Hello(role, str(o, "game"),
                        (int) bounded(o, "protocol", 0xFFFF), (int) bounded(o, "min_protocol", 0xFFFF),
                        (int) bounded(o, "udp_port", 0xFFFF),
                        bounded(o, "heartbeat_ms", 0xFFFFFFFFL), bounded(o, "timeout_ms", 0xFFFFFFFFL),
                        optBounded(o, "session", 0xFFFFFFFFL));
            }
            case "spawn" -> new Spawn(bounded(o, "entity", 0xFFFFFFFFL), str(o, "provider"), str(o, "asset"),
                    optStr(o, "name"));
            case "despawn" -> new Despawn(bounded(o, "entity", 0xFFFFFFFFL));
            case "input" -> {
                Role focus = Role.parse(str(o, "focus"));
                if (focus == null) throw new WireException("bad control line: focus");
                yield new Input(focus, optStr(o, "reason"));
            }
            case "frame_channel" -> new FrameChannel(str(o, "path"), bounded(o, "width", 0xFFFFFFFFL),
                    bounded(o, "height", 0xFFFFFFFFL), bounded(o, "slots", 0xFFFFFFFFL));
            case "bye" -> {
                String r = optStr(o, "reason");
                yield new Bye(r == null ? "" : r);
            }
            default -> new Unknown(type);
        };
    }

    private static String str(Map<String, Object> o, String k) throws WireException {
        if (o.get(k) instanceof String s) return s;
        throw new WireException("bad control line: missing string \"" + k + "\"");
    }

    private static String optStr(Map<String, Object> o, String k) throws WireException {
        Object v = o.get(k);
        if (v == null) return null;
        return str(o, k);
    }

    private static long bounded(Map<String, Object> o, String k, long max) throws WireException {
        Object v = o.get(k);
        if (v instanceof Long l && l >= 0 && l <= max) return l;
        if (v instanceof BigInteger) throw new WireException("bad control line: \"" + k + "\" out of range");
        throw new WireException("bad control line: missing or out-of-range integer \"" + k + "\"");
    }

    private static Long optBounded(Map<String, Object> o, String k, long max) throws WireException {
        if (o.get(k) == null) return null;
        return bounded(o, k, max);
    }
}
