# link-java

A pure-Java implementation of the **rewrite-bridge passthrough link protocol v1**, so that a game
with a Java runtime (a Minecraft mod, a Hytale plugin, a Java server) can be a **host** or a
**guest** next to any other game or rewrite that speaks it. The protocol is defined by
`docs/CONTRACT.md` in the `rewrite-bridge` repository (revision 2); this library is one
implementation of it, tested against the Rust reference peers (`fake_host`, `fake_guest`).

- No dependencies, no native code, nothing from any game. Package: `dev.patodois.rewritelink`.
- Java 21 or newer (the code uses sealed interfaces, records and pattern matching for `switch`).
- Everything binds and connects to `127.0.0.1` only.
- Non-blocking: `Link.poll()` never blocks, `Link` methods are synchronized so a game thread may
  send while a helper thread polls (`LinkRunner` is that helper thread).

## What is in it

| Class | Role |
|---|---|
| `Wire` | Binary datagrams (header, Heartbeat, Camera, Pose, Collision, Frame): encode, decode, chunking |
| `Control` | TCP JSON-line messages (`hello`, `spawn`, `despawn`, `input`, `frame_channel`, `bye`) |
| `Json` | A small strict JSON reader/writer for the control lines |
| `Link` | One side of a session: handshake, version negotiation, heartbeats, the monotonic watchdog, stale/duplicate/foreign datagram rules, bye + half-close + drain (CONTRACT 6.1) without blocking |
| `LinkRunner` | A daemon thread that polls a `Link` and hands events to a listener |
| `LinkEvent`, `LinkConfig` | What `poll()` reports, and the settings |
| `Quat` | Quaternion helpers in the link's convention |
| `PixelFile` | The pixel file: `Reader` (read-only mapping, seqlock, depth plane checks) and `Writer` |
| `LinkDemo` | A command-line stand-in host or guest, mirroring the Rust `fake_host` / `fake_guest` |

`PixelFile` is the shared-memory pixel channel of CONTRACT 9.6 (revision 3): a `Reader` that maps the
file read-only, validates it, and copies a slot with the seqlock (colour and the optional linear depth
plane), and a `Writer` for the sending side. `frame_channel` and `Frame` are parsed by `Link` as usual.

## Build and test

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25     # any JDK 21+
./gradlew build --no-daemon                       # build/libs/link-java-<version>.jar, and the tests
./gradlew linkTests --no-daemon                   # just the tests
./gradlew demo --no-daemon --args="guest --port 47800 --orbit"
```

The tests are a plain runner (`LinkTests`, no JUnit): they encode and decode the contract's worked
example byte for byte, check every layout offset and length, reject malformed input (plus a
20,000-case fuzz), and run live sessions in one process (handshake, state flow, stale drops,
`busy`, bad hello, watchdog, clean bye with half-close, foreign addresses).

Consumers either depend on the jar or compile `src/main/java` together with their own sources
(`hytale-link` does the latter, `minecraft-link` includes this project as a Gradle composite build
and bundles it into its mod jar).

## History

This code was written and tested inside `hytale-link` (package `dev.patodois.hytalelink.link`), and
moved here unchanged apart from the package name and the runner thread's name.

## License

MIT OR Apache-2.0, at your option: see `LICENSE-MIT` and `LICENSE-APACHE`.
