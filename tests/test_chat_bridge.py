#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""Unit tests for the Minecraft chat frontend."""
from __future__ import annotations

import json
import socket
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor

import grpc
import pilot_pb2
import robonix_contracts_pb2_grpc

from minecraft_chat_bridge.bridge import (
    ChatBridge,
    ChatRequest,
    EVT_FINAL_TEXT,
    EVT_NODE_STATE,
    MAX_SERVER_CHAT,
    ModClient,
    NODE_FAILED,
    NODE_RUNNING,
    progress_message,
    split_chat_message,
)


class ModServer:
    """Serve one protocol-valid response with the request's generated id."""

    def __init__(self, result: dict[str, object]) -> None:
        self.result = result
        self.request: dict[str, object] = {}
        self.socket = socket.socket()
        self.socket.bind(("127.0.0.1", 0))
        self.socket.listen(1)
        self.port = self.socket.getsockname()[1]
        self.thread = threading.Thread(target=self.serve, daemon=True)

    def serve(self) -> None:
        connection, _ = self.socket.accept()
        with connection:
            self.request = json.loads(connection.makefile("r", encoding="utf-8").readline())
            response = {"id": self.request["id"], "ok": True, "result": self.result}
            connection.sendall(json.dumps(response).encode("utf-8") + b"\n")
        self.socket.close()

    def __enter__(self) -> "ModServer":
        self.thread.start()
        return self

    def __exit__(self, *args: object) -> None:
        _ = args
        self.thread.join(timeout=2)
        self.socket.close()


class LiaisonServicer(robonix_contracts_pb2_grpc.RobonixSystemLiaisonSubmitServicer):
    """Return one final Pilot event for an integration-style bridge test."""

    def SubmitTask(self, request: pilot_pb2.Task, context: grpc.ServicerContext):
        _ = context
        yield pilot_pb2.PilotEvent(
            event_kind=EVT_FINAL_TEXT,
            session_id=request.session_id,
            final_text=f"完成：{request.text}",
        )


class RecordingMod:
    """Record server-chat output without a running Minecraft client."""

    def __init__(self) -> None:
        self.messages: list[str] = []

    def send_chat(self, message: str) -> None:
        self.messages.append(message)


class ChatFormattingTests(unittest.TestCase):
    def test_mod_client_polls_addressed_messages(self) -> None:
        result = {"messages": [{
            "sender_uuid": "e6b04a70-80e7-4c9a-901f-f18e689b55fc",
            "sender_name": "operator",
            "text": "探索附近",
        }]}
        with ModServer(result) as server:
            messages = ModClient("127.0.0.1", server.port).poll_chat()
        self.assertEqual(messages[0].sender_name, "operator")
        self.assertEqual(messages[0].text, "探索附近")
        self.assertEqual(server.request["cmd"], "poll_chat")
        self.assertEqual(server.request["limit"], 16)

    def test_bridge_streams_liaison_final_text_back_to_minecraft(self) -> None:
        server = grpc.server(ThreadPoolExecutor(max_workers=1))
        robonix_contracts_pb2_grpc.add_RobonixSystemLiaisonSubmitServicer_to_server(
            LiaisonServicer(), server,
        )
        port = server.add_insecure_port("127.0.0.1:0")
        server.start()
        mod = RecordingMod()
        bridge = ChatBridge(mod, f"127.0.0.1:{port}", set(), 0.25, 5.0)  # type: ignore[arg-type]
        request = ChatRequest("player-uuid", "operator", "寻找箱子")
        try:
            bridge._submit(request, False)
        finally:
            bridge.workers.shutdown(wait=False, cancel_futures=True)
            server.stop(0).wait()
        self.assertEqual(mod.messages, ["[Bot → operator] 完成：寻找箱子"])

    def test_split_chat_message_preserves_all_text(self) -> None:
        text = "word " * 100
        parts = split_chat_message(text)
        self.assertTrue(parts)
        self.assertTrue(all(0 < len(part) <= MAX_SERVER_CHAT for part in parts))
        self.assertEqual(" ".join(parts), " ".join(text.split()))

    def test_split_chat_message_hard_splits_long_token(self) -> None:
        parts = split_chat_message("x" * 600)
        self.assertEqual([len(part) for part in parts], [256, 256, 88])

    def test_final_text_is_urgent(self) -> None:
        event = pilot_pb2.PilotEvent(event_kind=EVT_FINAL_TEXT, final_text="完成")
        self.assertEqual(progress_message(event), ("完成", True))

    def test_running_node_is_throttled_progress(self) -> None:
        event = pilot_pb2.PilotEvent(
            event_kind=EVT_NODE_STATE,
            node_state=pilot_pb2.RtdlNodeState(
                state=NODE_RUNNING,
                description="探索附近区域",
            ),
        )
        self.assertEqual(progress_message(event), ("正在执行：探索附近区域", False))

    def test_warning_async_event_bypasses_progress_throttle(self) -> None:
        event = pilot_pb2.PilotEvent(
            event_kind=EVT_NODE_STATE,
            node_state=pilot_pb2.RtdlNodeState(
                state=NODE_RUNNING,
                description="在房间 room-a 发现僵尸 zombie-1",
                operator_detail="warning:ZOMBIE_DETECTED",
            ),
        )
        self.assertEqual(
            progress_message(event),
            ("正在执行：在房间 room-a 发现僵尸 zombie-1", True),
        )

    def test_failed_node_is_urgent(self) -> None:
        event = pilot_pb2.PilotEvent(
            event_kind=EVT_NODE_STATE,
            node_state=pilot_pb2.RtdlNodeState(
                state=NODE_FAILED,
                description="导航到目标",
            ),
        )
        self.assertEqual(progress_message(event), ("步骤失败：导航到目标", True))


if __name__ == "__main__":
    unittest.main()
