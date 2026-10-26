package dev.patodois.rewritelink;

import java.math.BigInteger;
import java.util.Map;

import dev.patodois.rewritelink.Wire.WireException;

/** Control-channel messages: one JSON object per line (CONTRACT.md section 8). */
public interface Control {

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
    final class Hello implements Control {
        private final Role role;
        private final String game;
        private final int protocol;
        private final int minProtocol;
        private final int udpPort;
        private final long heartbeatMs;
        private final long timeoutMs;
        private final Long session;

        public Hello(Role role, String game, int protocol, int minProtocol, int udpPort, long heartbeatMs, long timeoutMs, Long session) {
            this.role = role;
            this.game = game;
            this.protocol = protocol;
            this.minProtocol = minProtocol;
            this.udpPort = udpPort;
            this.heartbeatMs = heartbeatMs;
            this.timeoutMs = timeoutMs;
            this.session = session;
        }

        public Role role() {
            return role;
        }

        public String game() {
            return game;
        }

        public int protocol() {
            return protocol;
        }

        public int minProtocol() {
            return minProtocol;
        }

        public int udpPort() {
            return udpPort;
        }

        public long heartbeatMs() {
            return heartbeatMs;
        }

        public long timeoutMs() {
            return timeoutMs;
        }

        public Long session() {
            return session;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Hello)) return false;
            Hello that = (Hello) o;
            return java.util.Objects.equals(role, that.role)
                    && java.util.Objects.equals(game, that.game)
                    && protocol == that.protocol
                    && minProtocol == that.minProtocol
                    && udpPort == that.udpPort
                    && heartbeatMs == that.heartbeatMs
                    && timeoutMs == that.timeoutMs
                    && java.util.Objects.equals(session, that.session);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(role);
            h = 31 * h + java.util.Objects.hashCode(game);
            h = 31 * h + Integer.hashCode(protocol);
            h = 31 * h + Integer.hashCode(minProtocol);
            h = 31 * h + Integer.hashCode(udpPort);
            h = 31 * h + Long.hashCode(heartbeatMs);
            h = 31 * h + Long.hashCode(timeoutMs);
            h = 31 * h + java.util.Objects.hashCode(session);
            return h;
        }

        @Override
        public String toString() {
            return "Hello[role=" + role + ", game=" + game + ", protocol=" + protocol + ", minProtocol=" + minProtocol + ", udpPort=" + udpPort + ", heartbeatMs=" + heartbeatMs + ", timeoutMs=" + timeoutMs + ", session=" + session + "]";
        }
    }

    final class Spawn implements Control {
        private final long entity;
        private final String provider;
        private final String asset;
        private final String name;

        public Spawn(long entity, String provider, String asset, String name) {
            this.entity = entity;
            this.provider = provider;
            this.asset = asset;
            this.name = name;
        }

        public long entity() {
            return entity;
        }

        public String provider() {
            return provider;
        }

        public String asset() {
            return asset;
        }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Spawn)) return false;
            Spawn that = (Spawn) o;
            return entity == that.entity
                    && java.util.Objects.equals(provider, that.provider)
                    && java.util.Objects.equals(asset, that.asset)
                    && java.util.Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Long.hashCode(entity);
            h = 31 * h + java.util.Objects.hashCode(provider);
            h = 31 * h + java.util.Objects.hashCode(asset);
            h = 31 * h + java.util.Objects.hashCode(name);
            return h;
        }

        @Override
        public String toString() {
            return "Spawn[entity=" + entity + ", provider=" + provider + ", asset=" + asset + ", name=" + name + "]";
        }
    }

    final class Despawn implements Control {
        private final long entity;

        public Despawn(long entity) {
            this.entity = entity;
        }

        public long entity() {
            return entity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Despawn)) return false;
            Despawn that = (Despawn) o;
            return entity == that.entity;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Long.hashCode(entity);
            return h;
        }

        @Override
        public String toString() {
            return "Despawn[entity=" + entity + "]";
        }
    }

    final class Input implements Control {
        private final Role focus;
        private final String reason;

        public Input(Role focus, String reason) {
            this.focus = focus;
            this.reason = reason;
        }

        public Role focus() {
            return focus;
        }

        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Input)) return false;
            Input that = (Input) o;
            return java.util.Objects.equals(focus, that.focus)
                    && java.util.Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(focus);
            h = 31 * h + java.util.Objects.hashCode(reason);
            return h;
        }

        @Override
        public String toString() {
            return "Input[focus=" + focus + ", reason=" + reason + "]";
        }
    }

    final class FrameChannel implements Control {
        private final String path;
        private final long width;
        private final long height;
        private final long slots;

        public FrameChannel(String path, long width, long height, long slots) {
            this.path = path;
            this.width = width;
            this.height = height;
            this.slots = slots;
        }

        public String path() {
            return path;
        }

        public long width() {
            return width;
        }

        public long height() {
            return height;
        }

        public long slots() {
            return slots;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FrameChannel)) return false;
            FrameChannel that = (FrameChannel) o;
            return java.util.Objects.equals(path, that.path)
                    && width == that.width
                    && height == that.height
                    && slots == that.slots;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(path);
            h = 31 * h + Long.hashCode(width);
            h = 31 * h + Long.hashCode(height);
            h = 31 * h + Long.hashCode(slots);
            return h;
        }

        @Override
        public String toString() {
            return "FrameChannel[path=" + path + ", width=" + width + ", height=" + height + ", slots=" + slots + "]";
        }
    }

    final class Bye implements Control {
        private final String reason;

        public Bye(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Bye)) return false;
            Bye that = (Bye) o;
            return java.util.Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(reason);
            return h;
        }

        @Override
        public String toString() {
            return "Bye[reason=" + reason + "]";
        }
    }

    /** A {@code type} this version does not know; ignored by receivers. */
    final class Unknown implements Control {
        private final String type;

        public Unknown(String type) {
            this.type = type;
        }

        public String type() {
            return type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Unknown)) return false;
            Unknown that = (Unknown) o;
            return java.util.Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(type);
            return h;
        }

        @Override
        public String toString() {
            return "Unknown[type=" + type + "]";
        }
    }

    /**
     * The message as one JSON line, newline included. A U+0000 in a string is written as U+FFFD (CONTRACT 8,
     * "Syntax of a line", 4): a line with a NUL escape is a protocol error for every receiver.
     */
    default String toLine() {
        StringBuilder b = new StringBuilder();
        if (this instanceof Hello) {
            Hello h = (Hello) this;
            b.append("{\"type\":\"hello\",\"role\":").append(Json.quote(h.role().wire))
                    .append(",\"game\":").append(Json.quote(ControlFields.clean(h.game())))
                    .append(",\"protocol\":").append(h.protocol())
                    .append(",\"min_protocol\":").append(h.minProtocol())
                    .append(",\"udp_port\":").append(h.udpPort())
                    .append(",\"heartbeat_ms\":").append(h.heartbeatMs())
                    .append(",\"timeout_ms\":").append(h.timeoutMs());
            if (h.session() != null) b.append(",\"session\":").append(h.session());
            b.append('}');
        } else if (this instanceof Spawn) {
            Spawn s = (Spawn) this;
            b.append("{\"type\":\"spawn\",\"entity\":").append(s.entity())
                    .append(",\"provider\":").append(Json.quote(ControlFields.clean(s.provider())))
                    .append(",\"asset\":").append(Json.quote(ControlFields.clean(s.asset())));
            if (s.name() != null) b.append(",\"name\":").append(Json.quote(ControlFields.clean(s.name())));
            b.append('}');
        } else if (this instanceof Despawn) {
            b.append("{\"type\":\"despawn\",\"entity\":").append(((Despawn) this).entity()).append('}');
        } else if (this instanceof Input) {
            Input in = (Input) this;
            b.append("{\"type\":\"input\",\"focus\":").append(Json.quote(in.focus().wire));
            if (in.reason() != null) b.append(",\"reason\":").append(Json.quote(ControlFields.clean(in.reason())));
            b.append('}');
        } else if (this instanceof FrameChannel) {
            FrameChannel f = (FrameChannel) this;
            b.append("{\"type\":\"frame_channel\",\"path\":").append(Json.quote(ControlFields.clean(f.path())))
                    .append(",\"width\":").append(f.width()).append(",\"height\":").append(f.height())
                    .append(",\"slots\":").append(f.slots()).append('}');
        } else if (this instanceof Bye) {
            b.append("{\"type\":\"bye\",\"reason\":").append(Json.quote(ControlFields.clean(((Bye) this).reason()))).append('}');
        } else if (this instanceof Unknown) {
            b.append("{\"type\":").append(Json.quote(ControlFields.clean(((Unknown) this).type()))).append('}');
        } else {
            throw new IllegalStateException("not a control message of this library: " + this);
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
        if (!(v instanceof Map)) throw new WireException("bad control line: not an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) v;
        if (!(o.get("type") instanceof String)) {
            throw new WireException("bad control line: no string \"type\"");
        }
        String type = (String) o.get("type");
        switch (type) {
            case "hello": {
                Role role = Role.parse(ControlFields.str(o, "role"));
                if (role == null) throw new WireException("bad control line: role");
                return new Hello(role, ControlFields.str(o, "game"),
                        (int) ControlFields.bounded(o, "protocol", 0xFFFF), (int) ControlFields.bounded(o, "min_protocol", 0xFFFF),
                        (int) ControlFields.bounded(o, "udp_port", 0xFFFF),
                        ControlFields.bounded(o, "heartbeat_ms", 0xFFFFFFFFL), ControlFields.bounded(o, "timeout_ms", 0xFFFFFFFFL),
                        ControlFields.optBounded(o, "session", 0xFFFFFFFFL));
            }
            case "spawn":
                return new Spawn(ControlFields.bounded(o, "entity", 0xFFFFFFFFL), ControlFields.str(o, "provider"),
                        ControlFields.str(o, "asset"), ControlFields.optStr(o, "name"));
            case "despawn":
                return new Despawn(ControlFields.bounded(o, "entity", 0xFFFFFFFFL));
            case "input": {
                Role focus = Role.parse(ControlFields.str(o, "focus"));
                if (focus == null) throw new WireException("bad control line: focus");
                return new Input(focus, ControlFields.optStr(o, "reason"));
            }
            case "frame_channel":
                return new FrameChannel(ControlFields.str(o, "path"), ControlFields.bounded(o, "width", 0xFFFFFFFFL),
                        ControlFields.bounded(o, "height", 0xFFFFFFFFL), ControlFields.bounded(o, "slots", 0xFFFFFFFFL));
            case "bye": {
                String r = ControlFields.optStr(o, "reason");
                return new Bye(r == null ? "" : r);
            }
            default:
                return new Unknown(type);
        }
    }
}

/** Field access for {@link Control#parse} (an interface cannot have private methods before Java 9). */
final class ControlFields {
    private ControlFields() {}

    /** A string as the wire carries it: U+0000 becomes U+FFFD (CONTRACT 8, "Syntax of a line", 4). */
    static String clean(String s) {
        return s.indexOf('\0') < 0 ? s : s.replace('\0', '\uFFFD');
    }

    static String str(Map<String, Object> o, String k) throws WireException {
        Object v = o.get(k);
        if (v instanceof String) return (String) v;
        throw new WireException("bad control line: missing string \"" + k + "\"");
    }

    static String optStr(Map<String, Object> o, String k) throws WireException {
        Object v = o.get(k);
        if (v == null) return null;
        return str(o, k);
    }

    static long bounded(Map<String, Object> o, String k, long max) throws WireException {
        Object v = o.get(k);
        if (v instanceof Long) {
            long l = (Long) v;
            if (l >= 0 && l <= max) return l;
        }
        if (v instanceof BigInteger) throw new WireException("bad control line: \"" + k + "\" out of range");
        throw new WireException("bad control line: missing or out-of-range integer \"" + k + "\"");
    }

    static Long optBounded(Map<String, Object> o, String k, long max) throws WireException {
        if (o.get(k) == null) return null;
        return bounded(o, k, max);
    }
}
