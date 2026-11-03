# Simulated camera clock for Chronos

The camera can generate device uptime from a host elapsed clock independently
of Minecraft game ticks. This provides a stationary clock test before motion
or mapping experiments. `clock_emulation.realsense.time_base` accepts `game`
(the existing default) and `host_monotonic`; the example is
[`config/camera-clock-host.yaml`](../config/camera-clock-host.yaml).

## Capture and timestamp meaning

The Mod assigns one host marker at the canonical RGB-D capture stage, after
level rendering and before buffer readback/encoding. RGB and depth retain the
same marker through asynchronous encoding:

| Field | Meaning |
| --- | --- |
| `captureSessionId` | Random session identity, replaced when the coordinator/Mod restarts |
| `captureElapsedNanos` | Midpoint of a monotonic bracket, elapsed since coordinator creation |
| `captureReferenceMs` | Independent `System.currentTimeMillis()` read within that bracket |
| `captureReferenceUncertaintyNanos` | Half-bracket rounded up plus 1 ms quantization |

This is a simulated acquisition marker, not a real exposure midpoint. The
reference bound covers marker read placement and quantization; it does not
assert UTC accuracy or bound rendering/readback/encoding/network delay. Run the
device and Chronos on the same Linux host for the first test. The Mod's absolute
`System.nanoTime()` value is never treated as the Chronos monotonic clock.

Host device uptime is `initial_uptime + captureElapsedNanos`, with configured
drift and sequence-seeded jitter. After `reset_at_sec`, it restarts from elapsed
time minus that reset point. Reset timing is relative to **coordinator creation**,
not collector startup. Repeated frames return the same timestamp. Device reset
and session changes advance `device_generation` within a producer run; the
session ID is required across producer/Mod restarts. Consumers use the pair,
not the generation number alone.

Game `simTimeNanos`, `/clock` and LiDAR PTP remain on their existing game scale.
Game pause or tick speed changes cannot alter host-driven camera uptime. No new
frames during pause means no observations even though elapsed time continues.
This test does not convert Linux timestamps into `/clock` or fix sim-time maps.

## Device output and independent score output

The read-only collector uses the existing `rgbd_snapshot` command. It imports no
Robonix provider, needs no ROS/Atlas, sends no movement request, and emits only
one observation per unique RGB-D pair. Duplicate cached frames are suppressed;
out-of-order/reused identities and stale sessions fail explicitly.

Each observation is a JSONL object with exactly:

```json
{"domain_id":"minecraft_realsense","captureSessionId":"<Mod session>","device_generation":1,"sequence_id":23,"device_timestamp_ns":5000250000}
```

Neither reference truth, game truth, injection coefficients nor the collector's
receive monotonic time is a fitting input. Score JSONL is written separately and
contains the matching identity, independent Linux capture evidence, game truth,
injection diagnostics and local collector receive monotonic time. In host-mode
diagnostics, `injected_offset_ns` compares the device value to host elapsed time;
it is not a game-to-Linux offset. The scorer uses only capture reference evidence
for the error and never subtracts injected offsets to claim correction.

Use one producer for a domain. Do not combine the collector's observations with
another concurrently configured emulator or multiply observations by RGB,
depth and CameraInfo copies. The normal driver also suppresses repeated cached
captures when clock emulation is enabled and uses the shared elapsed marker for
its raw camera stamps. Existing provider emulation still requires
`use_sim_time=true` because the LiDAR/game paths share that initialization;
this requirement does not make the host camera stamp a game timestamp.

## Build and verify without starting Minecraft

From this package, build the Mod with the configured JDK 17:

```bash
JAVA_HOME="$HOME/.local/share/jdks/jdk-17.0.19+10" ./gradlew build --offline
```

The existing clock contracts and Atlas time metadata have been restored in the
sibling Robonix source tree. Rebuild Atlas, rbnx and codegen from that tree, then
regenerate this package's stubs before using the normal provider. An installed
old Atlas executable is not updated by rebuilding this package. The standalone
collector and Rust probe do not need Atlas.

```bash
cargo build --manifest-path ../minecraft-time-sync-experiment/chronos-camera-probe/Cargo.toml
PYTHONPATH=. rbnx-build/codegen-venv/bin/python3 tests/integration_camera_clock.py -v
```

The integration test binds an ephemeral loopback socket as a **synthetic Mod**,
runs the actual collector and current Chronos Rust runtime for 16 seconds, and
checks game-time independence, model acquisition, emission pause, holdover,
unsynced state, recovery, device reset, session restart and integer conversion.
It never contacts the real Mod or starts a deployment. Its 50 ms failure limit
is specific to this synthetic test, not a hardware/Minecraft accuracy promise.

## Stationary Minecraft run, started manually

Install the rebuilt Mod and enter the selected world before running the
following. This is a read-only collection command; it does not launch Minecraft
or the bot deployment. From this package:

```bash
set -o pipefail
RUN_DIR=$(mktemp -d /tmp/minecraft-camera.XXXXXX)
PYTHONPATH=. rbnx-build/codegen-venv/bin/python3 -m minecraft_mod_driver.camera_clock_device \
  --config config/camera-clock-host.yaml \
  --observations - --scores "$RUN_DIR/scores.jsonl" \
  --duration-sec 30 --hz 10 --pause-at-sec 10 --pause-duration-sec 4 \
  | ../minecraft-time-sync-experiment/chronos-camera-probe/target/debug/chronos-camera-probe \
      ../minecraft-time-sync-experiment/chronos-camera-probe/chronos.yaml \
      > "$RUN_DIR/conversions.jsonl" 2> "$RUN_DIR/quality.log"
PYTHONPATH=. rbnx-build/codegen-venv/bin/python3 -m minecraft_mod_driver.camera_clock_score \
  --conversions "$RUN_DIR/conversions.jsonl" --scores "$RUN_DIR/scores.jsonl"
```

Files are newly created and existing runs are not overwritten. To capture raw
observations alone, pass a new file path to `--observations` instead of `-`.
Replay from a saved file is useful for schema/identity checks, but cannot validate
live host-receive pairing: Chronos would sample replay arrival time.

The probe samples its own `ObservationSnapshot` at JSONL ingress, uses only the
raw observation schema (unknown fields are rejected), and resets the domain on
confirmed device identity change. It converts using earlier cached coefficients
and withholds every fifth frame from fitting. It checks the half-away-from-zero
integer formula against `ClockModel::convert`, and never uses retained models
whose domain/reference generation differs from current status. Quality traces
continue while input is stopped; Linux reference verification stays false.

The example uses `fixed_delay_ns=0` and a conservative observation uncertainty.
Rendering, encoding, cached-pair waiting, transport and pipe scheduling add a
systematic bias and variable latency. Measure them independently before choosing
fixed delay and an acceptance threshold; do not tune against score truth and
then score the same run. The report includes held-out coverage, bias, error tail,
reference uncertainty, validity/unbounded counts, rejection reasons and errors
grouped by device/reference identity. A missing model is reported through
coverage rather than scored as zero error.

For reset testing, copy the device YAML and set `reset_at_sec`; start capture
soon enough after Mod launch to observe both sides of that boundary. For Mod
restart testing, run two sessions and inspect identity/generation changes;
the collector intentionally exits on a broken TCP request instead of hiding a
disconnect. Start a new collector connection manually. To test holdover, stop
**emission** with the pause options while the probe stays alive; this differs
from game pause or stopping Chronos itself.

## Current process boundary

This is a device output plus an in-process Rust-API test harness. The existing
`robonix-chronos` gRPC process still has no remote observation ingress. A future
Chronos adapter should consume this truth-free observation schema, take its own
snapshot at receipt and submit through a bounded queue; sender monotonic values
cannot replace that token. The two existing gRPC quality/model contracts and
their lifecycle remain unchanged. Their stream tests are separate from this
camera test; live Minecraft-to-gRPC model publication has not been validated.
