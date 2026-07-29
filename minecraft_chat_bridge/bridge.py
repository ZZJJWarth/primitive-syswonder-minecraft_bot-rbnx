#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""Forward addressed Minecraft player chat to Liaison and report Pilot progress."""
from __future__ import annotations

import json
import os
import signal
import socket
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any, Iterable

import grpc
import pilot_pb2
import robonix_contracts_pb2_grpc


MAX_RESPONSE_BYTES = 1024 * 1024
MAX_SERVER_CHAT = 256
EVT_STATUS = 3
EVT_FINAL_TEXT = 4
EVT_NODE_STATE = 5
EVT_TASK_STATE = 6
NODE_RUNNING = 1
NODE_FAILED = 3
NODE_CANCELED = 4
NODE_TIMEOUT = 5


@dataclass(frozen=True)
class ChatRequest:
    """One player-authored instruction captured by the client Mod."""

    sender_uuid: str
    sender_name: str
    text: str


class ModClient:
    """Use the Mod's loopback JSON-lines interface."""

    def __init__(self, host: str, port: int, timeout: float = 5.0) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self._id = 0
        self._lock = threading.Lock()

    def request(self, command: str, **fields: Any) -> dict[str, Any]:
        with self._lock:
            self._id += 1
            request_id = f"chat-{self._id}"
        payload = {"id": request_id, "cmd": command, **fields}
        with socket.create_connection((self.host, self.port), timeout=self.timeout) as connection:
            connection.settimeout(self.timeout)
            connection.sendall(json.dumps(payload, ensure_ascii=False).encode("utf-8") + b"\n")
            response = self._read_response(connection)
        if str(response.get("id")) != request_id:
            raise RuntimeError("Minecraft Mod response id mismatch")
        if not response.get("ok"):
            error = response.get("error")
            if isinstance(error, dict):
                raise RuntimeError(str(error.get("message") or "Minecraft Mod command failed"))
            raise RuntimeError(str(error or "Minecraft Mod command failed"))
        result = response.get("result")
        if not isinstance(result, dict):
            raise RuntimeError("Minecraft Mod result is not an object")
        return result

    @staticmethod
    def _read_response(connection: socket.socket) -> dict[str, Any]:
        data = bytearray()
        while len(data) <= MAX_RESPONSE_BYTES:
            chunk = connection.recv(65536)
            if not chunk:
                break
            data.extend(chunk)
            newline = data.find(b"\n")
            if newline >= 0:
                data = data[:newline]
                break
        if len(data) > MAX_RESPONSE_BYTES:
            raise RuntimeError("Minecraft Mod response is too large")
        parsed = json.loads(data.decode("utf-8"))
        if not isinstance(parsed, dict):
            raise RuntimeError("Minecraft Mod response is not an object")
        return parsed

    def poll_chat(self) -> list[ChatRequest]:
        result = self.request("poll_chat", limit=16)
        messages = result.get("messages")
        if not isinstance(messages, list):
            raise RuntimeError("Minecraft Mod chat result is not a list")
        requests: list[ChatRequest] = []
        for message in messages:
            if not isinstance(message, dict):
                continue
            requests.append(ChatRequest(
                sender_uuid=str(message.get("sender_uuid") or ""),
                sender_name=str(message.get("sender_name") or ""),
                text=str(message.get("text") or ""),
            ))
        return requests

    def send_chat(self, message: str) -> None:
        for line in split_chat_message(message):
            self.request("send_chat", message=line)


def split_chat_message(message: str, limit: int = MAX_SERVER_CHAT) -> list[str]:
    """Split a reply into valid non-empty Minecraft chat messages."""
    normalized = " ".join(message.split())
    if not normalized:
        return []
    parts: list[str] = []
    while len(normalized) > limit:
        split_at = normalized.rfind(" ", 0, limit + 1)
        if split_at <= 0:
            split_at = limit
        parts.append(normalized[:split_at].rstrip())
        normalized = normalized[split_at:].lstrip()
    if normalized:
        parts.append(normalized)
    return parts


class ChatBridge:
    """Poll the Mod, authorize players, and submit their text to Liaison."""

    def __init__(
        self,
        mod: ModClient,
        liaison_endpoint: str,
        allowed_players: set[str],
        poll_interval: float,
        progress_interval: float,
    ) -> None:
        self.mod = mod
        self.liaison_endpoint = liaison_endpoint
        self.allowed_players = {item.casefold() for item in allowed_players}
        self.poll_interval = poll_interval
        self.progress_interval = progress_interval
        self.stop_event = threading.Event()
        self.workers = ThreadPoolExecutor(max_workers=4, thread_name_prefix="minecraft-chat")
        self.last_request: dict[str, float] = {}
        self.request_lock = threading.Lock()
        self.active_players: set[str] = set()
        self.active_lock = threading.Lock()
        self.active_calls: set[Any] = set()
        self.calls_lock = threading.Lock()

    def run(self) -> None:
        """Run until signaled, tolerating Mod startup and transient restarts."""
        print(
            f"[minecraft-chat] listening for addressed chat; liaison={self.liaison_endpoint}",
            flush=True,
        )
        while not self.stop_event.is_set():
            try:
                for request in self.mod.poll_chat():
                    self.handle(request)
            except (OSError, RuntimeError, ValueError) as exc:
                print(f"[minecraft-chat] Mod unavailable: {exc}", flush=True)
                self.stop_event.wait(1.0)
                continue
            self.stop_event.wait(self.poll_interval)
        self.workers.shutdown(wait=True, cancel_futures=True)

    def stop(self) -> None:
        """Wake the poll loop and cancel active Liaison streams for clean shutdown."""
        self.stop_event.set()
        with self.calls_lock:
            calls = list(self.active_calls)
        for call in calls:
            call.cancel()

    def handle(self, request: ChatRequest) -> None:
        """Apply authorization/rate limits before scheduling a Liaison request."""
        identity = request.sender_uuid.casefold()
        name = request.sender_name.casefold()
        if self.allowed_players and identity not in self.allowed_players and name not in self.allowed_players:
            self._reply(request, "你没有控制这个 Bot 的权限。")
            return
        text = request.text.strip()
        if not text:
            self._reply(request, "请在指令前缀后输入任务，例如：@bot 探索附近区域")
            return
        if text.casefold() in {"stop", "cancel", "abort", "停止", "取消"}:
            self.workers.submit(self._abort, request)
            return

        now = time.monotonic()
        with self.request_lock:
            previous = self.last_request.get(identity, 0.0)
            if now - previous < 1.0:
                self._reply(request, "指令过于频繁，请稍后再试。")
                return
            self.last_request[identity] = now

        with self.active_lock:
            is_steer = identity in self.active_players
            if not is_steer:
                self.active_players.add(identity)
        self._reply(request, "已追加指令。" if is_steer else "收到，正在处理。")
        self.workers.submit(self._submit, request, is_steer)

    def _task(self, request: ChatRequest, text: str, context: dict[str, Any]) -> pilot_pb2.Task:
        context = {
            "user_id": f"minecraft:{request.sender_uuid}",
            "user_name": request.sender_name,
            "modality": "minecraft_chat",
            **context,
        }
        return pilot_pb2.Task(
            task_id=str(uuid.uuid4()),
            session_id=f"minecraft:{request.sender_uuid}",
            source=0,
            text=text,
            audio_data=b"",
            context_json=json.dumps(context, ensure_ascii=False),
            timestamp_ms=int(time.time() * 1000),
        )

    def _events(self, task: pilot_pb2.Task) -> Iterable[pilot_pb2.PilotEvent]:
        # Liaison is a deployment-local endpoint. Do not let grpcio route a
        # loopback address through the user's HTTP(S)_PROXY configuration.
        channel = grpc.insecure_channel(
            self.liaison_endpoint,
            options=(("grpc.enable_http_proxy", 0),),
        )
        try:
            stub = robonix_contracts_pb2_grpc.RobonixSystemLiaisonSubmitStub(channel)
            call = stub.SubmitTask(task, timeout=1800)
            with self.calls_lock:
                self.active_calls.add(call)
            if self.stop_event.is_set():
                call.cancel()
            yield from call
        finally:
            if "call" in locals():
                with self.calls_lock:
                    self.active_calls.discard(call)
                call.cancel()
            channel.close()

    def _submit(self, request: ChatRequest, is_steer: bool) -> None:
        last_progress = 0.0
        last_message = ""
        final_seen = False
        try:
            for event in self._events(self._task(request, request.text, {})):
                message, urgent = progress_message(event)
                if not message:
                    continue
                now = time.monotonic()
                if not urgent and now - last_progress < self.progress_interval:
                    continue
                if message == last_message:
                    continue
                self._reply(request, message)
                last_progress = now
                last_message = message
                final_seen = final_seen or event.event_kind == EVT_FINAL_TEXT
            if not final_seen and not is_steer:
                self._reply(request, "任务处理结束。")
        except grpc.RpcError as exc:
            if self.stop_event.is_set():
                return
            detail = exc.details() if hasattr(exc, "details") else str(exc)
            self._reply(request, f"无法处理任务：{detail or exc.code().name}")
        except (OSError, RuntimeError, ValueError) as exc:
            self._reply(request, f"任务处理失败：{exc}")
        finally:
            if not is_steer:
                with self.active_lock:
                    self.active_players.discard(request.sender_uuid.casefold())

    def _abort(self, request: ChatRequest) -> None:
        try:
            task = self._task(request, "", {"abort_turn": True})
            for _ in self._events(task):
                pass
            self._reply(request, "已请求停止当前任务。")
        except grpc.RpcError as exc:
            if self.stop_event.is_set():
                return
            detail = exc.details() if hasattr(exc, "details") else str(exc)
            self._reply(request, f"停止任务失败：{detail or exc.code().name}")

    def _reply(self, request: ChatRequest, text: str) -> None:
        try:
            self.mod.send_chat(f"[Bot → {request.sender_name}] {text}")
        except (OSError, RuntimeError, ValueError) as exc:
            print(f"[minecraft-chat] failed to send reply: {exc}", flush=True)


def progress_message(event: pilot_pb2.PilotEvent) -> tuple[str, bool]:
    """Return one concise player-visible update and whether it bypasses throttling."""
    if event.event_kind == EVT_FINAL_TEXT:
        return event.final_text.strip(), True
    if event.event_kind == EVT_STATUS and event.status.message.strip():
        return event.status.message.strip(), event.status.state in {1, 2}
    if event.event_kind == EVT_TASK_STATE:
        state = event.task_state
        if state.status.strip():
            goal = f"：{state.goal.strip()}" if state.goal.strip() else ""
            return f"{state.status.strip()}{goal}", False
    if event.event_kind == EVT_NODE_STATE:
        state = event.node_state
        description = state.description.strip() or state.operator_detail.strip()
        if not description:
            return "", False
        if state.state == NODE_RUNNING:
            return f"正在执行：{description}", False
        if state.state in {NODE_FAILED, NODE_CANCELED, NODE_TIMEOUT}:
            labels = {
                NODE_FAILED: "步骤失败",
                NODE_CANCELED: "步骤已取消",
                NODE_TIMEOUT: "步骤超时",
            }
            return f"{labels[state.state]}：{description}", True
    return "", False


def env_float(name: str, default: float, minimum: float, maximum: float) -> float:
    value = float(os.environ.get(name, str(default)))
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be from {minimum} through {maximum}")
    return value


def main() -> None:
    enabled = os.environ.get("RBNX_MINECRAFT_CHAT_ENABLED", "1")
    if enabled not in {"0", "1"}:
        raise ValueError("RBNX_MINECRAFT_CHAT_ENABLED must be 0 or 1")
    if enabled == "0":
        print("[minecraft-chat] disabled", flush=True)
        return
    host = os.environ.get("RBNX_MINECRAFT_MOD_HOST", "127.0.0.1")
    port = int(os.environ.get("RBNX_MINECRAFT_MOD_PORT", "25576"))
    endpoint = os.environ.get("ROBONIX_LIAISON_ENDPOINT", "127.0.0.1:50081")
    allowed = {
        value.strip() for value in os.environ.get(
            "RBNX_MINECRAFT_CHAT_ALLOWED_PLAYERS", "",
        ).split(",") if value.strip()
    }
    bridge = ChatBridge(
        ModClient(host, port),
        endpoint,
        allowed,
        env_float("RBNX_MINECRAFT_CHAT_POLL_SEC", 0.25, 0.05, 5.0),
        env_float("RBNX_MINECRAFT_CHAT_PROGRESS_SEC", 5.0, 0.0, 300.0),
    )
    signal.signal(signal.SIGTERM, lambda *_: bridge.stop())
    signal.signal(signal.SIGINT, lambda *_: bridge.stop())
    bridge.run()


if __name__ == "__main__":
    main()
