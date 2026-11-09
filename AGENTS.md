# Agent guide

link-java implements rewrite-bridge's passthrough link protocol for Java game mods and plugins.
It supplies host and guest roles for protocol v1, contract revision 7, and is bundled by minecraft-link.

## Toolchain, build and CI

- The README/build script require JDK 9+ to compile with `javac --release 8`; the jar runs on Java 8+.
- CI runs the Gradle wrapper with Temurin JDK 21 on Linux, macOS and Windows.
- The library has no dependencies or native code.
- Exact CI verification command:

```sh
./gradlew --no-daemon check
```

`check` runs `linkTests` and `linkTestsPreferIpv6`, the plain test runner under both JVM configurations.
The README builds the jar and runs the checks with `./gradlew build --no-daemon`.
Do not rely on Gradle's `test` task alone: it is disabled because the tests do not use JUnit.

Gate/workflow regression tests in CI:

```sh
python3 -m venv .workflow-tests
.workflow-tests/bin/python -m pip install -r tools/requirements-test.txt
.workflow-tests/bin/python -m unittest discover -s tools -p 'test_*.py'
```

## Code map

- `src/main/java/dev/patodois/rewritelink/`: public library package.
- `Link`, `LinkRunner`, `Control`, `Wire`, `Json`: sessions, polling and codecs.
- `PixelFile`, `Tokens`, `Endpoint`: pixel I/O and authentication.
- `LinkDemo`: standalone command-line host/guest.
- `src/test/java/`, `tests/vectors/link/`: plain runners, interop peer and shared vectors.
- `PIXEL-IO.md`, `fuzz/`: pixel-reader rules and fuzzing support.

## Project rules

- Preserve Java 8 API compatibility, including library calls, through `--release 8`.
- Bind and connect only to `127.0.0.1`; `Link.poll()` must not block.
- Follow rewrite-bridge's contract; vectors come from it and are pinned by digest.
- Present with `readLatest`, close obsolete readers and discard results from replaced channels.
- Shared-vector tests can use sparse files up to 1 GiB; `RBL_VECTORS_SKIP_LARGE=1` skips those cases.
- Tests need no game files; game integration claims are limited to what the README records.

## Public files

- Never commit game files, extracted assets, renders, decompiled code or secrets.
- The public-tree gate needs Python 3.9+ and Git; run it from the repository root:
  `python3 tools/check_public_tree.py` (checks Git's index, including staged new files).
- CI checks committed changes with `python3 tools/check_public_tree.py --range BASE..HEAD`.

## Contributing, license and AI

- Open an issue to discuss a problem or send a pull request with the change.
- Before a pull request, run the applicable CI commands above and the public-tree gate.
- License: MIT OR Apache-2.0, at your option; see `LICENSE-MIT` and `LICENSE-APACHE`.

The code was written with AI coding agents (Anthropic's Claude and OpenAI's GPT models) under the maintainer's direction.
AI-assisted contributions are welcome when their use is disclosed and the changes are reviewed.
