#!/usr/bin/env python3
"""Local protocol fixture for manual Android smoke tests; no agent or shell is executed.

Run: python3 tools/mock_daemon.py --port 6768
Connect from an Android emulator: 10.0.2.2:6768 (password: test)
The production app never loads this fixture automatically.
"""
import argparse
import base64
import hashlib
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NOW = "2026-10-29T00:00:00Z"
AGENT = dict(id="fixture-agent", provider="claude", cwd="/home/dev/paseo", model="claude-sonnet-4-6",
             title="Review the native Android client", status="idle", createdAt=NOW, updatedAt=NOW,
             lastUserMessageAt=None, currentModeId=None, availableModes=[], pendingPermissions=[],
             persistence=None, capabilities={}, workspaceId="fixture-workspace")
HISTORY = [dict(type="user_message", text="Review the native Android client.", messageId="u1"),
           dict(type="assistant_message", text="## Native client review\n\nThe connection is working. This is a **local protocol fixture**.\n\n```kotlin\nLazyColumn {\n    items(messages, key = { it.id }) { Message(it) }\n}\n```\n\nSend a message to test streaming and tool approval.", messageId="a1")]
FILES = {"README.md": "# Paseo Native\n\nA Kotlin and Jetpack Compose client.\n", "app/Main.kt": "fun main() {\n    println(\"Paseo\")\n}\n"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.headers.get("Upgrade", "").lower() != "websocket":
            self.send_error(400, "WebSocket required")
            return
        key = self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", base64.b64encode(hashlib.sha1(key.encode()).digest()).decode())
        self.end_headers()
        self.lock = threading.Lock()
        self.history = list(HISTORY)
        self.agent = dict(AGENT)
        self.output = "$ pwd\n/home/dev/paseo\n$ "
        try:
            while True:
                frame = self.read_frame()
                if frame is None:
                    break
                self.handle_frame(json.loads(frame))
        except (ConnectionError, OSError, ValueError):
            pass
        self.close_connection = True

    def read_exact(self, n):
        value = self.rfile.read(n)
        if len(value) != n:
            raise ConnectionError("closed")
        return value

    def read_frame(self):
        first, second = self.read_exact(2)
        opcode = first & 15
        size = second & 127
        if size == 126:
            size = struct.unpack("!H", self.read_exact(2))[0]
        elif size == 127:
            size = struct.unpack("!Q", self.read_exact(8))[0]
        if size > 2_000_000:
            raise ValueError("oversized fixture message")
        mask = self.read_exact(4) if second & 128 else None
        data = self.read_exact(size)
        if mask:
            data = bytes(v ^ mask[i % 4] for i, v in enumerate(data))
        if opcode == 8:
            self.write_frame(data, 8)
            return None
        if opcode == 9:
            self.write_frame(data, 10)
            return self.read_frame()
        return data.decode() if opcode == 1 else self.read_frame()

    def write_frame(self, data, opcode=1):
        if isinstance(data, str):
            data = data.encode()
        size = len(data)
        header = bytes([128 | opcode]) + (bytes([size]) if size < 126 else b"\x7e" + struct.pack("!H", size) if size < 65536 else b"\x7f" + struct.pack("!Q", size))
        with self.lock:
            self.connection.sendall(header + data)

    def send(self, message_type, **payload):
        self.write_frame(json.dumps(dict(type="session", message=dict(type=message_type, payload=payload))))

    def item(self, item):
        self.history.append(item)
        self.send("agent_stream", agentId=self.agent["id"], epoch="fixture-epoch", seq=len(self.history),
                  timestamp=NOW, event=dict(type="timeline", provider="claude", turnId="fixture-turn", item=item))

    def handle_frame(self, frame):
        if frame.get("type") == "hello":
            if frame.get("auth", {}).get("password") != "test":
                self.write_frame(json.dumps(dict(type="hello.rejected", reason="incorrect_password", accepts=["password"])))
                return
            self.send("status", status="server_info", serverId="fixture-server", hostname="Local test fixture",
                      features=dict(ownedSubscriptions=True, selectiveAgentTimeline=True))
            return
        message = frame.get("message", {})
        kind = message.get("type", "")
        req = message.get("requestId", "")
        print(kind, flush=True)
        if kind == "fetch_agents_request":
            self.send("fetch_agents_response", requestId=req, subscriptionId="fixture-catalog",
                      entries=[dict(agent=self.agent, project={})], pageInfo=dict(nextCursor=None, prevCursor=None, hasMore=False))
        elif kind == "list_available_providers_request":
            self.send("list_available_providers_response", requestId=req, providers=[dict(provider="claude", available=True)])
        elif kind == "list_provider_models_request":
            self.send("list_provider_models_response", requestId=req, models=[dict(id="claude-sonnet-4-6", label="Sonnet (fixture)")])
        elif kind == "agent.timeline.set_subscription.request":
            self.send("agent.timeline.set_subscription.response", requestId=req, subscriptionId="fixture-timeline", agentIds=message["agentIds"])
        elif kind == "subscription.release.request":
            self.send("subscription.release.response", requestId=req, subscriptionId=message["subscriptionId"])
        elif kind == "fetch_agent_timeline_request":
            self.send("fetch_agent_timeline_response", requestId=req, agentId=self.agent["id"], agent=self.agent,
                      epoch="fixture-epoch", direction=message.get("direction", "tail"), projection="canonical", reset=False, staleCursor=False, gap=False,
                      window=dict(minSeq=1, maxSeq=len(self.history), nextSeq=len(self.history) + 1),
                      startCursor=dict(epoch="fixture-epoch", seq=1), endCursor=dict(epoch="fixture-epoch", seq=len(self.history)),
                      hasOlder=False, hasNewer=False, error=None,
                      entries=[dict(item=item, seqStart=i, seqEnd=i, timestamp=NOW, provider="claude", sourceSeqRanges=[dict(startSeq=i, endSeq=i)], collapsed=[]) for i, item in enumerate(self.history, 1)])
        elif kind == "send_agent_message_request":
            self.send("send_agent_message_response", requestId=req, agentId=self.agent["id"], accepted=True, error=None)
            self.item(dict(type="user_message", text=message["text"], messageId=message["messageId"]))
            self.send("agent_stream", agentId=self.agent["id"], timestamp=NOW, event=dict(type="turn_started", provider="claude"))
            for chunk in ["This ", "response ", "arrives ", "as ", "streamed ", "chunks.\n\n", "Please approve the sample command below."]:
                self.item(dict(type="assistant_message", text=chunk, messageId="reply-" + req))
                time.sleep(.04)
            permission = dict(id="fixture-permission", provider="claude", name="shell", kind="tool", title="Run a command?", detail=dict(type="shell", command="git status --short"))
            self.send("agent_permission_request", agentId=self.agent["id"], request=permission)
        elif kind == "agent_permission_response":
            self.send("agent_permission_resolved", agentId=self.agent["id"], requestId=req, resolution=message["response"])
            self.item(dict(type="assistant_message", text="Permission result: " + message["response"]["behavior"], messageId="permission-result"))
            self.send("agent_stream", agentId=self.agent["id"], timestamp=NOW, event=dict(type="turn_completed", provider="claude"))
        elif kind == "create_agent_request":
            self.agent.update(id="fixture-created", title=message["config"].get("title", "New fixture session"), cwd=message["config"]["cwd"])
            self.history = []
            self.send("status", requestId=req, status="agent_created", agentId=self.agent["id"], agent=self.agent)
        elif kind == "file_explorer_request":
            path = message.get("path", "")
            file = None
            directory = None
            if message["mode"] == "file":
                file = dict(path=path, kind="text", encoding="utf-8", content=FILES.get(path, ""), size=len(FILES.get(path, "")), modifiedAt=NOW, revision="fixture-v1")
            else:
                entries = [dict(name="README.md", path="README.md", kind="file"), dict(name="app", path="app", kind="directory")] if not path else [dict(name="Main.kt", path="app/Main.kt", kind="file")]
                directory = dict(path=path, entries=[dict(**entry, size=0, modifiedAt=NOW) for entry in entries])
            self.send("file_explorer_response", requestId=req, cwd=self.agent["cwd"], path=path, mode=message["mode"], file=file, directory=directory, error=None)
        elif kind == "fs.file.write.request":
            FILES[message["path"]] = message["content"]
            self.send("fs.file.write.response", requestId=req, result=dict(status="written", modifiedAt=NOW, revision="fixture-v2", size=len(message["content"])))
        elif kind == "checkout.diff.get.request":
            self.send("checkout.diff.get.response", requestId=req, cwd=self.agent["cwd"], error=None, files=[dict(path="Main.kt", additions=1, deletions=1, isNew=False, isDeleted=False, hunks=[dict(oldStart=1, newStart=1, oldCount=1, newCount=1, lines=[dict(type="remove", content='println("old")'), dict(type="add", content='println("Paseo")')])])])
        elif kind == "list_terminals_request":
            self.send("list_terminals_response", requestId=req, terminals=[dict(id="fixture-terminal", name="Fixture shell", cwd=self.agent["cwd"])])
        elif kind == "create_terminal_request":
            self.send("create_terminal_response", requestId=req, terminal=dict(id="fixture-terminal", name="Fixture shell", cwd=self.agent["cwd"]), error=None)
        elif kind == "capture_terminal_request":
            self.send("capture_terminal_response", requestId=req, terminalId=message["terminalId"], lines=self.output.splitlines(), totalLines=len(self.output.splitlines()))
        elif kind == "terminal_input":
            self.output += message["message"]["data"].replace("\r", "\n") + "[fixture] Command received\n$ "
        elif kind == "update_agent_request":
            self.agent["title"] = message["name"]
            self.send("agent_update", kind="upsert", agent=self.agent)
            self.send("status", requestId=req, status="agent_updated", agentId=self.agent["id"])
        elif kind in ("archive_agent_request", "cancel_agent_request"):
            self.send("status" if kind.startswith("archive") else "cancel_agent_response", requestId=req, agentId=self.agent["id"], status="agent_archived", error=None)
        else:
            self.send("rpc_error", requestId=req, error=f"Fixture does not implement {kind}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=6768)
    args = parser.parse_args()
    print(f"Local fixture: ws://127.0.0.1:{args.port}/ws; password: test", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
