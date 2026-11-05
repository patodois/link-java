# Pixel readers and presentation migration

Contract revision 7 preserves protocol version 1, datagrams,
file headers, slot offsets or depth layout. Writers retain their mapped publication path.

## Presenting frames

A Frame event is an unvalidated announcement. Use read_latest (Rust/C), ReadLatest (C#),
or readLatest (Java) for presentation. Only a successful current-file copy advances the
number. Raw read APIs remain unordered for inspection and re-reading. Rust accepts an
optional depth destination; the other APIs can omit depth using their existing argument.
Output buffers supplied by callers are valid only on success and may contain partial or
previous bytes after a miss. Clear the displayed image and discard the old reader on a new
channel, session loss or a gone reader, including when opening the replacement fails.
Async results must still belong to the active reader before they are published.

Reader stale counters count duplicate/older nonzero presentation reads: stale_reads() in
Rust, pixel stats.stale in C, SkippedStale in C#, staleReads() in Java. Null frame arguments
are caller misses: C returns RBL_ERR_ARGS, C# and Java return null, without counters or state
changes. Rust's borrowed FrameReady cannot be null. Frame number zero is invalid, not a
presentation-staleness event.

## Unix and Windows I/O

Unix readers use bounded positional I/O and never dereference peer-owned mapped pages.
They validate the header and dimensions, observe the lock, copy requested planes, then read
both lock and frame again. Short reads are completed; EOF and I/O errors reject the entire
copy. The metadata size check also rejects shrinking unused tail bytes. It is an early
rejection, not the protection against later truncation.

Normal cropped planes use up to eight rows per vectored read on macOS/Linux (preadv in
Rust/C/C#, scatter read after positioning the private single-threaded FileChannel in Java).
Data goes directly into owned output in Rust/C/C#; padding goes into reusable scratch and
is discarded. Java reuses packed direct buffers before copying into its array results.
Native Rust and C builds require macOS 11.0 or newer. C on Android uses scalar
positional reads to avoid an API-24 preadv import. Rust on 32-bit Linux uses FileExt
positional reads instead of casting offsets to a narrow off_t. C# pixel APIs require
a 64-bit process and reject other pointer widths before native calls.
This reduces syscall count without a full-plane scratch allocation. Grouping is limited to
strides up to 16 KiB and spans up to four times the useful output. Wider or very sparse files
keep per-row reads: measurements showed that reading their padding costs more than the
saved syscalls. Full-width frames retain contiguous copies. Other Unix targets and a missing
C# preadv binding use bounded 32 KiB packed spans under the same limits, then per-row reads.
Rust/C retry interruptions and continue partial vectored reads, including within a vector.

Java owned direct scratch is reclaimed by the JVM without invoking Unsafe on Unix reader
close. The writer's existing cleaner path is unchanged. C# caches SafeFileHandle and uses
native positional I/O when available; missing or unsupported bindings switch to safe
FileStream copies with explicit positions and buffering disabled. I/O/provider exceptions
inside CopyAt are contained as misses. Both missing pread and missing preadv bindings are covered by fault injection; this
does not establish validation on Unity or IL2CPP. A managed frame-allocation failure
in C# returns null and increments SkippedAllocation without advancing order. This
cannot prevent an operating system from terminating a process under memory pressure;
other allocation policies remain those of each language and API.

Windows keeps a read-only map and the existing atomic lock observations: an active section
prevents ordinary truncation. Creation also checks that the actual mapping/file extent did
not shrink before protection took effect. Rust and managed ports read the header using
bounded I/O before mapping; C rechecks the extent with the section active before touching
it. Windows native tests run in the CI platform matrix; the benchmark tables below measure
macOS only. Java mapped-reader cleanup may depend on GC if the
runtime cannot release its section. Readers are not safe for concurrent close/read; Rust
serializes scratch and presentation state but callers still own reader lifetime.

The revised protocol explicitly distinguishes observation checks from a formal acquire
load: POSIX does not specify pread as an atomic acquire from the writer's memory. Tests
reject detected overlapping writes, not every possible publication violation on every
filesystem. ABA and hostile reuse of lock/frame remain outside that guarantee. I/O can
block. Writers still assume nobody truncates their own mapping. CI covers native Linux, macOS and Windows; WASI checks cover the transport without
pixel files. Mono, Unity and IL2CPP require separate runtime validation.

## Benchmark method

The benchmark repeats raw reads of one valid slot, so it measures copies rather than the
ordered API rejecting duplicates. It does not include presentation-order locking or counters.
Synthetic local files, warm cache, two warm-up reads, 30 samples for normal/cropped frames
and 5 for maximum-sized outputs. Times include header validation, requested planes and final
identity checks; exclude writer, GPU, IPC and rendering. Rust/C reuse destination buffers;
C#/Java allocate results according to their public APIs, with GC requested outside timed
regions. Other processes may compete for CPU and memory. p95 with five samples is only the
largest observed value. Do not compare allocation policies across languages as if identical.

Measured on macOS arm64. All numbers are milliseconds per frame.

| File | Frame | Depth | Before p50 | After p10 | After p50 | After p95 |
|---|---|---|---:|---:|---:|---:|
| 1280x720 | 1280x720 | false | 0.6796 | 0.1911 | 0.1972 | 0.2229 |
| 1280x720 | 1280x720 | true | 2.0903 | 0.5018 | 0.5207 | 0.5960 |
| 1920x1080 | 1920x1080 | false | 2.0253 | 0.5125 | 0.5273 | 0.5697 |
| 1920x1080 | 1920x1080 | true | 3.7480 | 1.0181 | 1.0664 | 1.1962 |
| 3840x2160 | 3840x2160 | false | 6.2174 | 2.0679 | 2.1026 | 2.2580 |
| 3840x2160 | 3840x2160 | true | 12.9632 | 4.2317 | 4.2916 | 4.4816 |
| 8192x8192 | 8192x8192 | false | 62.6748 | 16.9756 | 17.1614 | 17.5032 |
| 8192x8192 | 8192x8192 | true | 96.4237 | 34.3589 | 34.4319 | 34.7297 |
| 16384x1 | 16384x1 | false | 0.0970 | 0.0180 | 0.0222 | 0.0290 |
| 16384x1 | 16384x1 | true | 0.0862 | 0.0243 | 0.0275 | 0.0363 |
| 16384x4096 | 16384x4096 | false | 67.4528 | 16.8792 | 17.0667 | 17.2972 |
| 16384x4096 | 16384x4096 | true | 164.6170 | 34.1543 | 34.4221 | 34.6250 |
| 1920x1080 | 1280x720 | false | 2.8854 | 0.4175 | 0.4606 | 0.5760 |
| 1920x1080 | 1280x720 | true | 4.5684 | 0.7110 | 0.7516 | 0.8130 |
| 3840x2160 | 1920x1080 | false | 4.8938 | 0.7951 | 0.8867 | 2.9129 |
| 3840x2160 | 1920x1080 | true | 9.6532 | 1.6395 | 1.6600 | 1.8350 |
| 16384x4096 | 8192x4096 | false | 54.1134 | 12.2026 | 12.6744 | 12.8815 |
| 16384x4096 | 8192x4096 | true | 126.8897 | 21.5987 | 21.7755 | 22.5060 |
| 16384x4096 | 1x4096 | false | 5.9756 | 1.2792 | 1.3298 | 1.3940 |
| 16384x4096 | 1x4096 | true | 15.7567 | 2.5230 | 2.6086 | 2.7883 |

## Matched cropped-frame comparison

The same API was built with grouping disabled and enabled, one language at a time.
These sequential runs separate the optimization from other reader changes. Scheduling and
memory pressure still vary. Whole-plane scratch and unrestricted grouping were rejected
after measurements showed regressions on wide files. A one-column frame remains per-row.

| File | Frame | Depth | Row-wise p10 / p50 / p95 | Bounded p10 / p50 / p95 |
|---|---|---|---:|---:|
| 1920x1080 | 1280x720 | false | 0.4412 / 0.4969 / 0.6011 | 0.3262 / 0.3528 / 0.5370 |
| 1920x1080 | 1280x720 | true | 0.8979 / 0.9333 / 1.0910 | 0.6859 / 0.7143 / 0.8935 |
| 3840x2160 | 1920x1080 | false | 0.8999 / 0.9293 / 1.0066 | 0.8477 / 0.8956 / 0.9785 |
| 3840x2160 | 1920x1080 | true | 1.9078 / 1.9717 / 2.4370 | 1.7337 / 1.7903 / 1.9178 |
| 16384x4096 | 8192x4096 | false | 12.4120 / 12.5790 / 12.7103 | 12.5602 / 12.7270 / 13.0167 |
| 16384x4096 | 8192x4096 | true | 24.5297 / 24.7050 / 25.3361 | 22.4254 / 22.5095 / 23.0955 |
| 16384x4096 | 1x4096 | false | 1.2666 / 1.2919 / 1.4300 | 1.2761 / 1.2962 / 1.3807 |
| 16384x4096 | 1x4096 | true | 2.5199 / 2.5630 / 2.7410 | 2.5160 / 2.5488 / 2.7248 |
