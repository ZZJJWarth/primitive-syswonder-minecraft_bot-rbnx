# SPDX-License-Identifier: MulanPSL-2.0
"""Score held-out consumer conversions against separate capture reference evidence."""
from __future__ import annotations

import argparse
from collections import Counter
import json
import math
from pathlib import Path


def identity(record: dict) -> tuple:
    """Join by device/capture identity, never by fitted or reference timestamps."""
    return tuple(record[key] for key in ("domain_id", "captureSessionId", "device_generation", "sequence_id"))


def summarize(conversions: list[dict], scores: list[dict]) -> dict:
    """Use held-out frames only; retain extrapolation and evidence limitations."""
    evidence = {}
    for score in scores:
        key = identity(score)
        if key in evidence:
            raise ValueError("duplicate scoring identity")
        if score["reference_time_scale"] != "linux_system" or score["reference_verified"] is not False:
            raise ValueError("expected explicitly unverified Linux capture evidence")
        if type(score["reference_timestamp_ns"]) is not int or score["captureReferenceUncertaintyNanos"] < 1_000_000:
            raise ValueError("invalid or underreported reference evidence")
        evidence[key] = score
    errors = []
    reference_uncertainties = []
    within_validity = 0
    unbounded = 0
    eligible = 0
    rejection_reasons = Counter()
    groups = {}
    seen = set()
    for conversion in conversions:
        key = identity(conversion)
        if key in seen:
            raise ValueError("duplicate conversion identity")
        seen.add(key)
        if key not in evidence:
            raise ValueError("conversion has no matching independent capture evidence")
        if conversion.get("rejection"):
            # Interval rejections differ only in the remaining ns; preserve
            # exact details in conversion JSONL and count their shared reason.
            rejection_reasons[conversion["rejection"].split(";")[0]] += 1
        if conversion.get("held_out") is not True:
            continue
        eligible += 1
        if "reference_timestamp_ns" not in conversion:
            continue
        if type(conversion["reference_timestamp_ns"]) is not int:
            raise ValueError("converted reference must be integer nanoseconds")
        errors.append(conversion["reference_timestamp_ns"] - evidence[key]["reference_timestamp_ns"])
        group_key = (conversion["captureSessionId"], conversion["device_generation"], conversion.get("reference_generation"))
        groups.setdefault(group_key, []).append(errors[-1])
        reference_uncertainties.append(evidence[key]["captureReferenceUncertaintyNanos"])
        within_validity += conversion["within_validity"] is True
        unbounded += conversion["uncertainty_ns"] == (1 << 64) - 1
    if not errors:
        raise ValueError("no held-out conversions with a usable model")
    absolute = sorted(abs(error) for error in errors)
    def percentile(p: float) -> int:
        """Use nearest-rank percentiles so the error tail is not interpolated away."""
        return absolute[max(0, math.ceil(len(absolute) * p) - 1)]
    return {
        "reference_time_scale": "linux_system", "reference_verified": False,
        "held_out_frames": eligible, "converted_frames": len(errors),
        "coverage": len(errors) / eligible,
        "bias_ns": sum(errors) / len(errors),
        "abs_error_p50_ns": percentile(0.5), "abs_error_p95_ns": percentile(0.95),
        "abs_error_p99_ns": percentile(0.99), "abs_error_max_ns": absolute[-1],
        "reference_evidence_uncertainty_max_ns": max(reference_uncertainties),
        "within_validity_frames": within_validity, "unbounded_uncertainty_frames": unbounded,
        "rejection_reasons": dict(rejection_reasons),
        "groups": [{"captureSessionId": key[0], "device_generation": key[1],
            "reference_generation": key[2], "converted_frames": len(values),
            "bias_ns": sum(values) / len(values), "abs_error_max_ns": max(map(abs, values))}
            for key, values in groups.items()],
    }


def main() -> None:
    """Read post-run files only; optional limits apply to the measured absolute tail."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--conversions", type=Path, required=True)
    parser.add_argument("--scores", type=Path, required=True)
    parser.add_argument("--max-error-ms", type=float, default=None)
    args = parser.parse_args()
    if args.max_error_ms is not None and (not math.isfinite(args.max_error_ms) or args.max_error_ms <= 0):
        parser.error("error limit must be finite and positive")
    conversions = [json.loads(line) for line in args.conversions.read_text().splitlines()]
    scores = [json.loads(line) for line in args.scores.read_text().splitlines()]
    result = summarize(conversions, scores)
    print(json.dumps(result, indent=2))
    if args.max_error_ms is not None and result["abs_error_max_ns"] > args.max_error_ms * 1_000_000:
        raise SystemExit("conversion error exceeds the explicitly selected limit")


if __name__ == "__main__":
    main()
