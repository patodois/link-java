# Fuzzing link-java

Jazzer (JUnit integration) targets for every decoder of bytes that come from a peer or from the endpoint file, and for the
handshake. This directory is a Gradle build of its own: the library's `./gradlew check`, its jar, JitPack and CI never
build or run it, the library stays Java 8, and it gains no runtime dependency. Only this build needs Jazzer, JUnit and a
JDK 21 (the toolchain is set in `build.gradle`).

| Class | Target | What it feeds |
| --- | --- | --- |
| `ControlFuzz` | `parseLine` | arbitrary bytes as one control line (`Control.parse`, `Json`) |
| | `parseHelloWithToken` | hello lines with a token marker and corrupted fields; the token must not appear in any message |
| `WireFuzz` | `decodeRaw` | arbitrary bytes as a UDP datagram (`Wire.decode`) |
| | `decodeBody` | a valid header and a fuzzed body, with the counts that hurt |
| `EndpointFuzz` | `readRaw`, `readShaped` | arbitrary and almost-right endpoint files (`Endpoint.read`) |
| | `construct` | `Endpoint` constructor, `Endpoint.write`, `Tokens.equal` |
| `PixelFileFuzz` | `open` | arbitrary pixel files (`PixelFile.Reader.open` and `read`) |
| `HandshakeFuzz` | `hostRaw`, `hostHello` | arbitrary control bytes at a live host with no token, an ASCII token, a 256-byte UTF-8 token, a one-character token |
| | `guest` | arbitrary control bytes from a fake host at a live guest with and without a token |
| `DatagramFuzz` | `datagrams` | arbitrary datagrams at a live link: header filter, entity table, staleness, collision reassembly |

Besides "no uncaught exception other than the documented checked ones" the targets check: a time and allocation budget per
call (`Guard`), that what decodes encodes again to the same message, that a host with a token accepts a hello only if it
carries exactly that token, and that the token is in no exception message, event, `toString` or line sent to the peer.

## Running

From the repository root:

    ./gradlew -p fuzz test                  # regression: replays the committed corpus, seconds
    ./gradlew -p fuzz test -Pfuzz           # fuzzing: every target for 30 minutes (maxDuration), one after the other

To run one target in its own JVM, so that several can fuzz side by side (a crash is written to the current directory):

    ./gradlew -p fuzz compileTestJava
    CP="$(./gradlew -p fuzz -q printClasspath)"
    JAZZER_FUZZ=1 java -Xmx1g -Djazzer.instrument='dev.patodois.rewritelink.**' -cp "$CP" \
        org.junit.platform.console.ConsoleLauncher execute \
        --select-method 'dev.patodois.rewritelink.ControlFuzz#parseLine(byte[])'

(the parameter list is `byte[]` for `parseLine`, `decodeRaw` and `readRaw`, and
`com.code_intelligence.jazzer.api.FuzzedDataProvider` for the others). Do not rebuild the library while a fuzz JVM runs: it
has the jar open.

The corpus the regression run replays is in `src/test/resources/dev/patodois/rewritelink/<Class>Inputs/<target>/`
(Jazzer's own layout): the minimised corpus of the last runs plus a few hand-made seeds. Every bug the fuzzing found also has a
regular test in `src/test/java/dev/patodois/rewritelink/FuzzFindingTests.java` of the library.
