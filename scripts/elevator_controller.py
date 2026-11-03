#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""Control the dedicated-server elevator endpoint without a Minecraft client."""
from __future__ import annotations

import argparse
import json
import math
import os
import socket
import time
import uuid


class ModClient:
    def __init__(self, port: int, timeout: float = 10.0, host: str = "127.0.0.1", player: str | None = None):
        self.port = port
        self.timeout = timeout
        self.host = host
        self.player = player

    def request(self, payload: dict) -> dict:
        request_id = str(uuid.uuid4())
        with socket.create_connection((self.host, self.port), timeout=self.timeout) as connection:
            connection.sendall(json.dumps({"id": request_id, **({"player": self.player} if self.player else {}), **payload}).encode() + b"\n")
            with connection.makefile("rb") as stream:
                line = stream.readline(8 * 1024 * 1024 + 1)
        if not line.endswith(b"\n") or len(line) > 8 * 1024 * 1024:
            raise RuntimeError("Mod response is incomplete or too large")
        response = json.loads(line)
        if response.get("id") != request_id:
            raise RuntimeError("Mod response id mismatch")
        if response.get("ok") is not True:
            raise RuntimeError(response.get("error", {}).get("message", "Mod command failed"))
        return response["result"]


class Controller:
    def __init__(self, client: ModClient, dimension: str, position: list[int], door_region: dict | None = None, command_block: dict | None = None):
        self.client = client
        self.address = dict(zip(("x", "y", "z"), position), dimension=dimension)
        if door_region is not None:
            self.address['door_region'] = door_region
        if command_block is not None:
            self.address['command_block'] = command_block

    def get(self) -> dict:
        return self.client.request({"cmd": "elevator_controller_get", **self.address})

    def set(self, powered: bool) -> dict:
        return self.client.request({"cmd": "elevator_controller_set", **self.address, "powered": powered})

    def wait_ticks(self, start: int, ticks: int, timeout: float, canceled=None) -> dict:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if canceled is not None and canceled.is_set():
                raise RuntimeError("elevator operation stopped")
            state = self.get()
            if state["game_time"] >= start + ticks:
                return state
            time.sleep(0.05)
        raise TimeoutError("server ticks did not advance; keep the server running and an integrated world unpaused")

    def pulse(self, ticks: int, timeout: float, canceled=None) -> dict:
        # Wait in game ticks so pause, lag and rapid repeated requests cannot erase the edge.
        state = self.set(False)
        self.wait_ticks(state["game_time"], 2, timeout, canceled)
        try:
            state = self.set(True)
            return self.wait_ticks(state["game_time"], ticks, timeout, canceled)
        finally:
            self.set(False)


def validate_bounds(values: list[float]) -> list[float]:
    if not all(math.isfinite(value) for value in values):
        raise ValueError("bounds must be finite")
    if any(values[i] >= values[i + 3] for i in range(3)):
        raise ValueError("bounds must have min < max on each axis")
    return values


def within(observation: dict, dimension: str, bounds: list[float]) -> bool:
    position = observation["position"]
    return observation["dimension"] == dimension and all(
        bounds[i] <= position[axis] < bounds[i + 3] for i, axis in enumerate(("x", "y", "z"))
    )


def ride(controller: Controller, source: list[float], target: list[float], ticks: int, timeout: float) -> dict:
    """Check actual feet positions before/after command-block transport, not floor variables."""
    validate_bounds(source)
    validate_bounds(target)
    if all(max(source[i], target[i]) < min(source[i + 3], target[i + 3]) for i in range(3)):
        raise ValueError("source and target regions must not overlap")
    dimension = controller.address["dimension"]
    def observe_player():
        result = controller.get()
        if not result.get('player_online') or 'player' not in result:
            raise RuntimeError('named robot player is offline; pass --player for a transport test')
        value = result['player']
        return dict(username=value['name'], uuid=value['uuid'], dimension=value['dimension'],
                    position={axis: value[axis] for axis in ('x', 'y', 'z')})
    before = observe_player()
    if not within(before, dimension, source):
        raise RuntimeError("player is outside the configured source region")
    controller.pulse(ticks, timeout)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        after = observe_player()
        if after["uuid"] != before["uuid"]:
            raise RuntimeError("observed player changed during transport")
        if within(after, dimension, target):
            return {"arrived": True, "username": after["username"], "dimension": dimension,
                    "before": before["position"], "after": after["position"]}
        time.sleep(0.1)
    raise TimeoutError("player did not reach target region; redstone acknowledgement is not arrival")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("list", "get", "on", "off", "pulse", "ride"))
    parser.add_argument("--pos", type=int, nargs=3, metavar=("X", "Y", "Z"))
    parser.add_argument("--dimension", default="minecraft:overworld")
    parser.add_argument("--port", type=int, default=int(os.environ.get("RBNX_MINECRAFT_ELEVATOR_PORT", "25577")))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--player", help="robot player to observe; doors work without an online player")
    parser.add_argument("--client-port", type=int, default=25576, help="client perception port, only used by list")
    parser.add_argument("--command-block", type=int, nargs=3, help="command block X Y Z when connected by wire")
    parser.add_argument("--ticks", type=int, default=4, help="pulse duration in game ticks (2..200)")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--source", type=float, nargs=6, help="source feet bounds: min X Y Z, max X Y Z")
    parser.add_argument("--target", type=float, nargs=6, help="target feet bounds: min X Y Z, max X Y Z")
    parser.add_argument("--door-min", type=int, nargs=3, help="inclusive fill door minimum X Y Z")
    parser.add_argument("--door-max", type=int, nargs=3, help="inclusive fill door maximum X Y Z")
    parser.add_argument("--closed-block", default="minecraft:glass")
    args = parser.parse_args()
    if args.action == "list":
        observation = ModClient(args.client_port, args.timeout).request({"cmd": "observe"})
        objects = [dict(id=block["id"], name=block["name"])
                   for block in observation["semanticBlocks"]
                   if block["name"] in ("robonix_minecraft:elevator_controller", "minecraft:iron_door")]
        print(json.dumps(objects, ensure_ascii=False, indent=2))
        return
    if args.pos is None:
        parser.error("--pos is required for controller operations")
    if not math.isfinite(args.timeout) or not 0.1 <= args.timeout <= 30 or not 2 <= args.ticks <= 200:
        parser.error("timeout must be 0.1..30 seconds and ticks must be 2..200")
    if args.action == "ride" and (args.source is None or args.target is None):
        parser.error("ride requires --source and --target")
    door_region = None
    if args.door_min is not None or args.door_max is not None:
        if args.door_min is None or args.door_max is None:
            parser.error("--door-min and --door-max must be provided together")
        volume = 1
        for lower, upper in zip(args.door_min, args.door_max):
            if lower > upper:
                parser.error("door minimum must not exceed maximum")
            volume *= upper - lower + 1
        if volume > 4096:
            parser.error("door region exceeds 4096 blocks")
        door_region = dict(dimension=args.dimension, min=args.door_min, max=args.door_max,
                           closed_block=args.closed_block)
    command_block = dict(zip(('x', 'y', 'z'), args.command_block)) if args.command_block else None
    controller = Controller(ModClient(args.port, args.timeout, args.host, args.player),
                            args.dimension, args.pos, door_region, command_block)
    try:
        if args.action == "get":
            result = controller.get()
        elif args.action in ("on", "off"):
            result = controller.set(args.action == "on")
        elif args.action == "pulse":
            controller.pulse(args.ticks, args.timeout)
            result = controller.get()
        else:
            result = ride(controller, args.source, args.target, args.ticks, args.timeout)
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
