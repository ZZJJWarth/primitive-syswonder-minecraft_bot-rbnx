#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""Deterministic RealSense-uptime and LiDAR-PTP clock emulation."""
from __future__ import annotations

import math
import threading
from dataclasses import asdict, dataclass
from typing import Any


_MASK_64 = (1 << 64) - 1


@dataclass(frozen=True)
class ClockSample:
    """One sensor timestamp together with its hidden benchmark truth."""

    sensor: str
    state: str
    sequence_id: int
    capture_truth_ns: int
    device_timestamp_ns: int
    injected_offset_ns: int
    drift_ppm: float

    def payload(self) -> dict[str, Any]:
        """Return a JSON-ready diagnostic payload."""
        return asdict(self)


@dataclass(frozen=True)
class _RealSenseConfig:
    initial_uptime_ns: int
    drift_ppm: float
    jitter_ns: int
    reset_at_ns: int | None


@dataclass(frozen=True)
class _PtpConfig:
    initial_offset_ns: int
    oscillator_drift_ppm: float
    locked_offset_ns: int
    locked_jitter_ns: int
    lock_after_ns: int
    lose_lock_at_ns: int | None
    relock_at_ns: int | None
    relock_duration_ns: int
    relock_mode: str


@dataclass(frozen=True)
class _MappingTestConfig:
    primary_timestamp_mode: str
    camera_offset_ns: int
    lidar_offset_ns: int


class ClockEmulator:
    """Map Minecraft truth into reproducible device-clock domains.

    The first observed truth stamp is the scenario origin. LiDAR PTP remains
    in the Minecraft/Grandmaster domain, while the RealSense clock reports
    device uptime and can reset independently.
    """

    def __init__(
        self,
        *,
        enabled: bool,
        seed: int,
        realsense: _RealSenseConfig,
        lidar_ptp: _PtpConfig,
        mapping_test: _MappingTestConfig,
    ) -> None:
        self.enabled = enabled
        self.seed = seed & _MASK_64
        self.realsense = realsense
        self.lidar_ptp = lidar_ptp
        self.mapping_test = mapping_test
        self._origin_truth_ns: int | None = None
        self._lock = threading.Lock()

    @classmethod
    def from_config(cls, provider_config: dict[str, Any]) -> "ClockEmulator":
        """Parse the optional nested ``clock_emulation`` provider config."""
        raw = provider_config.get("clock_emulation") or {}
        if not isinstance(raw, dict):
            raise ValueError("clock_emulation must be a mapping")
        enabled = _boolean(raw.get("enabled", False), "clock_emulation.enabled")
        seed = _integer(raw.get("seed", 1), "clock_emulation.seed", minimum=0)

        rs_raw = raw.get("realsense") or {}
        if not isinstance(rs_raw, dict):
            raise ValueError("clock_emulation.realsense must be a mapping")
        realsense = _RealSenseConfig(
            initial_uptime_ns=_seconds_ns(
                rs_raw.get("initial_uptime_sec", 5.0),
                "clock_emulation.realsense.initial_uptime_sec",
            ),
            drift_ppm=_number(
                rs_raw.get("drift_ppm", 50.0),
                "clock_emulation.realsense.drift_ppm",
                minimum=-10_000.0,
                maximum=10_000.0,
            ),
            jitter_ns=_milliseconds_ns(
                rs_raw.get("jitter_ms", 1.0),
                "clock_emulation.realsense.jitter_ms",
            ),
            reset_at_ns=_optional_seconds_ns(
                rs_raw.get("reset_at_sec"),
                "clock_emulation.realsense.reset_at_sec",
            ),
        )

        ptp_raw = raw.get("lidar_ptp") or {}
        if not isinstance(ptp_raw, dict):
            raise ValueError("clock_emulation.lidar_ptp must be a mapping")
        lock_after_ns = _seconds_ns(
            ptp_raw.get("lock_after_sec", 0.0),
            "clock_emulation.lidar_ptp.lock_after_sec",
        )
        lose_lock_at_ns = _optional_seconds_ns(
            ptp_raw.get("lose_lock_at_sec"),
            "clock_emulation.lidar_ptp.lose_lock_at_sec",
        )
        relock_at_ns = _optional_seconds_ns(
            ptp_raw.get("relock_at_sec"),
            "clock_emulation.lidar_ptp.relock_at_sec",
        )
        if lose_lock_at_ns is not None and lose_lock_at_ns < lock_after_ns:
            raise ValueError("lidar PTP loss must occur after initial lock")
        if relock_at_ns is not None and (
            lose_lock_at_ns is None or relock_at_ns <= lose_lock_at_ns
        ):
            raise ValueError("lidar PTP relock requires an earlier lose_lock_at_sec")
        relock_mode = str(ptp_raw.get("relock_mode", "slew")).strip().lower()
        if relock_mode not in {"slew", "step"}:
            raise ValueError("clock_emulation.lidar_ptp.relock_mode must be slew or step")
        lidar_ptp = _PtpConfig(
            initial_offset_ns=_milliseconds_ns(
                ptp_raw.get("initial_offset_ms", 30.0),
                "clock_emulation.lidar_ptp.initial_offset_ms",
                signed=True,
            ),
            oscillator_drift_ppm=_number(
                ptp_raw.get("oscillator_drift_ppm", 20.0),
                "clock_emulation.lidar_ptp.oscillator_drift_ppm",
                minimum=-10_000.0,
                maximum=10_000.0,
            ),
            locked_offset_ns=_microseconds_ns(
                ptp_raw.get("locked_offset_us", 100.0),
                "clock_emulation.lidar_ptp.locked_offset_us",
                signed=True,
            ),
            locked_jitter_ns=_microseconds_ns(
                ptp_raw.get("locked_jitter_us", 50.0),
                "clock_emulation.lidar_ptp.locked_jitter_us",
            ),
            lock_after_ns=lock_after_ns,
            lose_lock_at_ns=lose_lock_at_ns,
            relock_at_ns=relock_at_ns,
            relock_duration_ns=_seconds_ns(
                ptp_raw.get("relock_duration_sec", 10.0),
                "clock_emulation.lidar_ptp.relock_duration_sec",
                strictly_positive=True,
            ),
            relock_mode=relock_mode,
        )
        mapping_raw = raw.get("mapping_test") or {}
        if not isinstance(mapping_raw, dict):
            raise ValueError("clock_emulation.mapping_test must be a mapping")
        primary_timestamp_mode = str(
            mapping_raw.get("primary_timestamp_mode", "truth")
        ).strip().lower()
        if primary_timestamp_mode not in {"truth", "corrupted"}:
            raise ValueError(
                "clock_emulation.mapping_test.primary_timestamp_mode "
                "must be truth or corrupted"
            )
        if primary_timestamp_mode == "corrupted" and not enabled:
            raise ValueError("corrupted primary timestamps require clock_emulation.enabled=true")
        mapping_test = _MappingTestConfig(
            primary_timestamp_mode=primary_timestamp_mode,
            camera_offset_ns=_milliseconds_ns(
                mapping_raw.get("camera_offset_ms", 100.0),
                "clock_emulation.mapping_test.camera_offset_ms",
                signed=True,
            ),
            lidar_offset_ns=_milliseconds_ns(
                mapping_raw.get("lidar_offset_ms", 0.0),
                "clock_emulation.mapping_test.lidar_offset_ms",
                signed=True,
            ),
        )
        return cls(
            enabled=enabled,
            seed=seed,
            realsense=realsense,
            lidar_ptp=lidar_ptp,
            mapping_test=mapping_test,
        )

    @property
    def corrupt_primary_timestamps(self) -> bool:
        """Whether established capability topics should carry bad common-domain stamps."""
        return self.mapping_test.primary_timestamp_mode == "corrupted"

    def primary_sample(
        self,
        sensor: str,
        truth_ns: int,
        sequence_id: int,
    ) -> ClockSample:
        """Create a truth or deliberately biased common-domain primary stamp."""
        if sensor not in {"camera", "lidar"}:
            raise ValueError("primary clock sensor must be camera or lidar")
        if isinstance(truth_ns, bool) or not isinstance(truth_ns, int) or truth_ns < 0:
            raise ValueError("capture truth must be a non-negative integer nanosecond stamp")
        if (
            isinstance(sequence_id, bool)
            or not isinstance(sequence_id, int)
            or sequence_id < 0
        ):
            raise ValueError("capture sequence must be a non-negative integer")
        offset_ns = (
            self.mapping_test.camera_offset_ns
            if sensor == "camera"
            else self.mapping_test.lidar_offset_ns
        )
        if not self.corrupt_primary_timestamps:
            offset_ns = 0
        timestamp_ns = truth_ns + offset_ns
        if timestamp_ns < 0:
            raise ValueError("corrupted primary timestamp cannot be negative")
        return ClockSample(
            sensor=f"{sensor}_primary",
            state="CORRUPTED" if self.corrupt_primary_timestamps else "TRUTH",
            sequence_id=sequence_id,
            capture_truth_ns=truth_ns,
            device_timestamp_ns=timestamp_ns,
            injected_offset_ns=offset_ns,
            drift_ppm=0.0,
        )

    def realsense_sample(self, truth_ns: int, sequence_id: int) -> ClockSample:
        """Return a RealSense-style hardware-uptime timestamp."""
        elapsed_ns = self._elapsed(truth_ns)
        config = self.realsense
        if config.reset_at_ns is not None and elapsed_ns >= config.reset_at_ns:
            uptime_base_ns = elapsed_ns - config.reset_at_ns
            state = "RESET"
        else:
            uptime_base_ns = config.initial_uptime_ns + elapsed_ns
            state = "RUNNING"
        drift_ns = _ppm_delta(uptime_base_ns, config.drift_ppm)
        jitter_ns = self._noise_ns("realsense", sequence_id, config.jitter_ns)
        device_ns = max(0, uptime_base_ns + drift_ns + jitter_ns)
        return ClockSample(
            sensor="realsense",
            state=state,
            sequence_id=sequence_id,
            capture_truth_ns=truth_ns,
            device_timestamp_ns=device_ns,
            injected_offset_ns=device_ns - truth_ns,
            drift_ppm=config.drift_ppm,
        )

    def lidar_ptp_sample(self, truth_ns: int, sequence_id: int) -> ClockSample:
        """Return a PTP-disciplined LiDAR timestamp and servo state."""
        elapsed_ns = self._elapsed(truth_ns)
        config = self.lidar_ptp
        offset_ns, state, active_drift_ppm = self._ptp_offset(elapsed_ns)
        jitter_ns = self._noise_ns("lidar_ptp", sequence_id, config.locked_jitter_ns)
        device_ns = max(0, truth_ns + offset_ns + jitter_ns)
        return ClockSample(
            sensor="lidar_ptp",
            state=state,
            sequence_id=sequence_id,
            capture_truth_ns=truth_ns,
            device_timestamp_ns=device_ns,
            injected_offset_ns=device_ns - truth_ns,
            drift_ppm=active_drift_ppm,
        )

    def _elapsed(self, truth_ns: int) -> int:
        if isinstance(truth_ns, bool) or not isinstance(truth_ns, int) or truth_ns < 0:
            raise ValueError("capture truth must be a non-negative integer nanosecond stamp")
        with self._lock:
            if self._origin_truth_ns is None:
                self._origin_truth_ns = truth_ns
            return max(0, truth_ns - self._origin_truth_ns)

    def _ptp_offset(self, elapsed_ns: int) -> tuple[int, str, float]:
        config = self.lidar_ptp
        if config.lock_after_ns > 0 and elapsed_ns < config.lock_after_ns:
            ratio = elapsed_ns / config.lock_after_ns
            offset = round(
                config.initial_offset_ns
                + ratio * (config.locked_offset_ns - config.initial_offset_ns)
            )
            return offset, "INITIALIZING", config.oscillator_drift_ppm

        loss_ns = config.lose_lock_at_ns
        if loss_ns is None or elapsed_ns < loss_ns:
            return config.locked_offset_ns, "LOCKED", 0.0

        relock_ns = config.relock_at_ns
        holdover_elapsed_ns = elapsed_ns - loss_ns
        holdover_offset_ns = config.locked_offset_ns + _ppm_delta(
            holdover_elapsed_ns, config.oscillator_drift_ppm)
        if relock_ns is None or elapsed_ns < relock_ns:
            return holdover_offset_ns, "HOLDOVER", config.oscillator_drift_ppm

        if config.relock_mode == "step":
            return config.locked_offset_ns, "LOCKED", 0.0
        relock_end_ns = relock_ns + config.relock_duration_ns
        if elapsed_ns >= relock_end_ns:
            return config.locked_offset_ns, "LOCKED", 0.0
        offset_at_relock_ns = config.locked_offset_ns + _ppm_delta(
            relock_ns - loss_ns, config.oscillator_drift_ppm)
        ratio = (elapsed_ns - relock_ns) / config.relock_duration_ns
        offset_ns = round(
            offset_at_relock_ns
            + ratio * (config.locked_offset_ns - offset_at_relock_ns)
        )
        return offset_ns, "RELOCKING", config.oscillator_drift_ppm

    def _noise_ns(self, sensor: str, sequence_id: int, amplitude_ns: int) -> int:
        if amplitude_ns == 0:
            return 0
        if isinstance(sequence_id, bool) or not isinstance(sequence_id, int) or sequence_id < 0:
            raise ValueError("capture sequence must be a non-negative integer")
        salt = 0xA0761D6478BD642F if sensor == "realsense" else 0xE7037ED1A0B428DB
        value = (self.seed ^ salt ^ sequence_id) & _MASK_64
        value = (value + 0x9E3779B97F4A7C15) & _MASK_64
        value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & _MASK_64
        value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & _MASK_64
        value ^= value >> 31
        unit = value / _MASK_64
        return round((unit * 2.0 - 1.0) * amplitude_ns)


def _boolean(value: Any, name: str) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.strip().lower() in {"true", "1"}:
        return True
    if isinstance(value, str) and value.strip().lower() in {"false", "0"}:
        return False
    raise ValueError(f"{name} must be a boolean")


def _integer(value: Any, name: str, *, minimum: int) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{name} must be an integer")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if parsed != value or parsed < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}")
    return parsed


def _number(
    value: Any,
    name: str,
    *,
    minimum: float,
    maximum: float,
) -> float:
    if isinstance(value, bool):
        raise ValueError(f"{name} must be numeric")
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be numeric") from exc
    if not math.isfinite(parsed) or not minimum <= parsed <= maximum:
        raise ValueError(f"{name} must be within [{minimum}, {maximum}]")
    return parsed


def _scaled_ns(
    value: Any,
    name: str,
    scale: int,
    *,
    signed: bool = False,
    strictly_positive: bool = False,
) -> int:
    minimum = -1_000_000_000.0 if signed else 0.0
    parsed = _number(value, name, minimum=minimum, maximum=1_000_000_000.0)
    if strictly_positive and parsed <= 0.0:
        raise ValueError(f"{name} must be positive")
    return round(parsed * scale)


def _seconds_ns(
    value: Any,
    name: str,
    *,
    strictly_positive: bool = False,
) -> int:
    return _scaled_ns(value, name, 1_000_000_000, strictly_positive=strictly_positive)


def _optional_seconds_ns(value: Any, name: str) -> int | None:
    return None if value is None else _seconds_ns(value, name)


def _milliseconds_ns(value: Any, name: str, *, signed: bool = False) -> int:
    return _scaled_ns(value, name, 1_000_000, signed=signed)


def _microseconds_ns(value: Any, name: str, *, signed: bool = False) -> int:
    return _scaled_ns(value, name, 1_000, signed=signed)


def _ppm_delta(duration_ns: int, drift_ppm: float) -> int:
    return round(duration_ns * drift_ppm / 1_000_000.0)
