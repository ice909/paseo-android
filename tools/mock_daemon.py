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
import copy
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NOW = "2026-10-29T00:00:00Z"
AGENT = dict(id="fixture-agent", provider="claude", cwd="/home/dev/paseo", model="claude-sonnet-4-6",
             title="Review the native Android client", status="idle", createdAt=NOW, updatedAt=NOW,
             lastUserMessageAt=None, currentModeId=None, availableModes=[], pendingPermissions=[],
             persistence=None, capabilities=dict(supportsRewindConversation=True, supportsRewindFiles=True, supportsRewindBoth=True), workspaceId="fixture-workspace")
MODES = [dict(id="default", label="Default"), dict(id="plan", label="Plan")]
AGENT.update(currentModeId="default", availableModes=MODES)
WORKSPACE = dict(id="fixture-workspace", projectId="fixture-project", projectDisplayName="paseo", projectRootPath="/home/dev/paseo", workspaceDirectory="/home/dev/paseo", projectKind="git", workspaceKind="local_checkout", name="Native Android", status="done", activityAt=NOW, gitRuntime=None)
EMPTY_WORKSPACE = dict(WORKSPACE, id="fixture-empty", name="Empty workspace")
HISTORY = [dict(type="user_message", text="Review the native Android client.", messageId="u1"),
           dict(type="assistant_message", text="## Native client review\n\nThe connection is working. This is a **local protocol fixture**.\n\n```kotlin\nLazyColumn {\n    items(messages, key = { it.id }) { Message(it) }\n}\n```\n\nSend a message to test streaming and tool approval.", messageId="a1")]
ICON_GALLERY = False
EVENTS_FILE = None
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
        self.agent = copy.deepcopy(AGENT)
        self.agents = [self.agent, dict(copy.deepcopy(AGENT), id="fixture-second", title="Second agent")]
        self.workspaces = [dict(WORKSPACE), dict(EMPTY_WORKSPACE)]
        self.histories = {a["id"]: copy.deepcopy(HISTORY) for a in self.agents}
        self.history = self.histories[self.agent["id"]]
        self.terminal_exists = True
        self.receipts = set()
        self.rejected_ids = set()
        self.uploads = {}
        self.voice_enabled = False
        self.voice_played = False
        self.dictations = {}
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
        if size > 8_000_000:
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
        if opcode == 2:
            self.handle_binary(data)
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

    def audit(self, kind, **data):
        if EVENTS_FILE:
            with open(EVENTS_FILE, "a") as log:
                log.write(json.dumps(dict(type=kind, **data)) + "\n")

    def handle_binary(self, data):
        opcode, length = data[0], data[1]
        req = data[2:2+length].decode()
        body = data[2+length:]
        upload = self.uploads[req]
        if opcode == 0x10:
            size = int.from_bytes(body[:2], "big")
            upload["metadata"] = json.loads(body[2:2+size])
        elif opcode == 0x11:
            upload["bytes"].extend(body)
        elif opcode == 0x12:
            raw = bytes(upload["bytes"])
            self.audit("upload-complete", requestId=req, size=len(raw), sha256=hashlib.sha256(raw).hexdigest())
            self.send("file.upload.response", requestId=req, error=None, file=dict(type="uploaded_file", id=req, fileName=upload["fileName"], mimeType=upload["mimeType"], size=len(raw), path="/fixture/uploads/" + req))

    def handle_frame(self, frame):
        if frame.get("type") == "hello":
            if frame.get("auth", {}).get("password") != "test":
                self.write_frame(json.dumps(dict(type="hello.rejected", reason="incorrect_password", accepts=["password"])))
                return
            self.send("status", status="server_info", serverId="fixture-server", hostname="Local test fixture",
                      features=dict(ownedSubscriptions=True, selectiveAgentTimeline=True, creationLifecycle=True, rewind=True), capabilities=dict(voice=dict(dictation=dict(enabled=True, reason="ready"), voice=dict(enabled=True, reason="ready"))))
            return
        message = frame.get("message", {})
        kind = message.get("type", "")
        req = message.get("requestId", "")
        print(kind, flush=True)
        if kind not in ("dictation_stream_chunk", "voice_audio_chunk"):
            self.audit("request", message=message)
        if kind == "fetch_agents_request":
            self.send("fetch_agents_response", requestId=req, subscriptionId="fixture-catalog",
                      entries=[dict(agent=a, project={}) for a in self.agents], pageInfo=dict(nextCursor=None, prevCursor=None, hasMore=False))
        elif kind == "fetch_workspaces_request":
            self.send("fetch_workspaces_response", requestId=req, subscriptionId="fixture-workspaces", entries=self.workspaces,
                      emptyProjects=[dict(projectId="empty-project", projectDisplayName="Empty project", projectRootPath="/home/dev/empty", projectKind="directory")], pageInfo=dict(nextCursor=None, hasMore=False))
        elif kind == "list_available_providers_request":
            self.send("list_available_providers_response", requestId=req, providers=[dict(provider="claude", available=True)])
        elif kind == "list_provider_models_request":
            self.send("list_provider_models_response", requestId=req, models=[dict(id="claude-sonnet-4-6", label="Sonnet (fixture)", thinkingOptions=[dict(id="low", label="Low"), dict(id="high", label="High")]), dict(id="claude-opus-4-6", label="Opus (fixture)")])
        elif kind == "list_provider_modes_request":
            self.send("list_provider_modes_response", requestId=req, modes=MODES)
        elif kind == "list_commands_request":
            self.send("list_commands_response", requestId=req, commands=[dict(name="compact", description="Compact context", kind="command")])
        elif kind in ("forge.search.request", "github_search_request"):
            self.send("forge.search.response" if kind.startswith("forge") else "github_search_response", requestId=req, error=None,
                      items=[dict(kind="issue", number=42, title="Fixture issue", url="https://github.com/getpaseo/paseo/issues/42", state="open", body="Issue context", labels=[], forge="github")])
        elif kind == "directory_suggestions_request":
            self.send("directory_suggestions_response", requestId=req, entries=[dict(path=path, kind="file") for path in FILES if message.get("includeFiles") and message.get("query", "").lower() in path.lower()][:8], directories=[])
        elif kind == "set_agent_thinking_request":
            agent = next(a for a in self.agents if a["id"] == message["agentId"])
            agent["thinkingOptionId"] = message["thinkingOptionId"]
            self.send("set_agent_thinking_response", requestId=req, accepted=True, error=None)
            self.send("agent_status", agentId=agent["id"], info=agent)
        elif kind in ("set_agent_model_request", "set_agent_mode_request"):
            agent = next(a for a in self.agents if a["id"] == message["agentId"])
            agent["model" if kind == "set_agent_model_request" else "currentModeId"] = message.get("modelId", message.get("modeId"))
            self.send(kind.replace("request", "response"), requestId=req, accepted=True, error=None)
            self.send("agent_status", agentId=agent["id"], info=agent)
        elif kind == "agent.timeline.set_subscription.request":
            self.send("agent.timeline.set_subscription.response", requestId=req, subscriptionId="fixture-timeline", agentIds=message["agentIds"])
        elif kind == "subscription.release.request":
            self.send("subscription.release.response", requestId=req, subscriptionId=message["subscriptionId"])
        elif kind == "fetch_agent_timeline_request":
            self.agent = next(a for a in self.agents if a["id"] == message["agentId"])
            self.history = self.histories[self.agent["id"]]
            self.send("fetch_agent_timeline_response", requestId=req, agentId=self.agent["id"], agent=self.agent,
                      epoch="fixture-epoch", direction=message.get("direction", "tail"), projection="canonical", reset=False, staleCursor=False, gap=False,
                      window=dict(minSeq=1, maxSeq=len(self.history), nextSeq=len(self.history) + 1),
                      startCursor=dict(epoch="fixture-epoch", seq=1), endCursor=dict(epoch="fixture-epoch", seq=len(self.history)),
                      hasOlder=False, hasNewer=False, error=None,
                      entries=[dict(item=item, seqStart=i, seqEnd=i, timestamp=NOW, provider="claude", sourceSeqRanges=[dict(startSeq=i, endSeq=i)], collapsed=[]) for i, item in enumerate(self.history, 1)])
        elif kind == "send_agent_message_request":
            self.agent = next(a for a in self.agents if a["id"] == message["agentId"])
            self.history = self.histories[self.agent["id"]]
            mid = message["messageId"]
            if message["text"].lower().startswith("fail-once") and mid not in self.rejected_ids:
                self.rejected_ids.add(mid)
                time.sleep(.8)
                self.send("send_agent_message_response", requestId=req, accepted=False, error="Fixture rejected this message; retry is safe")
                return
            if "slow-receipt" in message["text"]:
                time.sleep(6)
            self.send("send_agent_message_response", requestId=req, agentId=self.agent["id"], accepted=True, error=None)
            if mid in self.receipts:
                return
            self.receipts.add(mid)
            self.item(dict(type="user_message", text=message["text"], messageId=mid, attachments=message.get("attachments", [])))
            if self.agent["status"] == "running" and message.get("activeTurnBehavior") == "steer":
                self.item(dict(type="assistant_message", text="Steering received: " + message["text"], messageId="steer-" + mid))
                return
            for permission in self.agent["pendingPermissions"]:
                self.send("agent_permission_resolved", agentId=self.agent["id"], requestId=permission["id"])
            self.agent["pendingPermissions"] = []
            self.agent["status"] = "running"
            self.agent["activeTurn"] = dict(turnId="turn-" + mid, startedAt=NOW)
            self.send("agent_status", agentId=self.agent["id"], info=self.agent)
            self.send("agent_stream", agentId=self.agent["id"], timestamp=NOW, event=dict(type="turn_started", turnId="turn-" + mid, provider="claude"))
            if message["text"].lower().startswith("hold"):
                self.item(dict(type="assistant_message", text="Holding this turn until Stop. You can steer or queue messages.", messageId="hold-" + mid))
                return
            for chunk in ["This ", "response ", "arrives ", "as ", "streamed ", "chunks.\n\n", "Please approve the sample command below."]:
                self.item(dict(type="assistant_message", text=chunk, messageId="reply-" + req))
                time.sleep(.04)
            permission = dict(id="fixture-permission", provider="claude", name="shell", kind="tool", title="Run a command?", detail=dict(type="shell", command="git status --short"))
            if "question" in message["text"].lower():
                permission = dict(id="fixture-question", name="AskUserQuestion", kind="question", title="Choose a stack", input=dict(context="preserve", questions=[dict(header="Stack", question="Which language?", options=[dict(label="Kotlin", description="Native Android"), dict(label="Java")], multiSelect=False, allowOther=True)]))
            self.agent["pendingPermissions"] = [permission]
            self.send("agent_permission_request", agentId=self.agent["id"], request=permission)
        elif kind == "agent_permission_response":
            self.agent = next(a for a in self.agents if a["id"] == message["agentId"])
            self.history = self.histories[self.agent["id"]]
            self.agent["pendingPermissions"] = []
            self.agent.update(status="idle", activeTurn=None)
            self.send("agent_permission_resolved", agentId=self.agent["id"], requestId=req, resolution=message["response"])
            answer = message["response"].get("updatedInput", {}).get("answers", {}).get("Stack")
            self.item(dict(type="assistant_message", text=("Question answer: " + answer) if answer else "Permission result: " + message["response"]["behavior"], messageId="permission-result-" + req))
            self.send("agent_stream", agentId=self.agent["id"], timestamp=NOW, event=dict(type="turn_completed", provider="claude"))
        elif kind == "file.upload.request":
            self.uploads[req] = dict(message, bytes=bytearray())
        elif kind == "agent.rewind.request":
            agent = next(a for a in self.agents if a["id"] == message["agentId"])
            if message["mode"] != "files":
                history = self.histories[agent["id"]]
                index = next((i for i, item in enumerate(history) if item.get("messageId") == message["messageId"]), len(history))
                del history[index:]
                self.send("agent.timeline.replacement", agentId=agent["id"])
            self.send("agent.rewind.response", requestId=req, agentId=agent["id"], ok=True, error=None)
        elif kind == "dictation_stream_start":
            self.dictations[message["dictationId"]] = []
            self.send("dictation_stream_ack", dictationId=message["dictationId"], ackSeq=-1)
        elif kind == "dictation_stream_chunk":
            self.dictations[message["dictationId"]].append(message["seq"])
            self.send("dictation_stream_ack", dictationId=message["dictationId"], ackSeq=message["seq"])
            if message["seq"] == 0:
                self.send("dictation_stream_partial", dictationId=message["dictationId"], text="Dictation fixture…")
        elif kind == "dictation_stream_finish":
            self.send("dictation_stream_finish_accepted", dictationId=message["dictationId"], timeoutMs=30000)
            self.send("dictation_stream_final", dictationId=message["dictationId"], text="Dictation fixture transcript")
        elif kind == "dictation_stream_cancel":
            pass
        elif kind == "set_voice_mode":
            self.voice_enabled = message["enabled"]
            self.voice_played = False
            self.send("set_voice_mode_response", requestId=req, accepted=True, enabled=message["enabled"], agentId=message.get("agentId"), error=None)
        elif kind == "voice_audio_chunk":
            if self.voice_enabled and not self.voice_played:
                self.voice_played = True
                self.send("audio_output", id="voice-fixture", audio=base64.b64encode(bytes(6400)).decode(), format="audio/pcm;rate=16000;bits=16", isVoiceMode=True)
        elif kind in ("audio_played", "abort_request"):
            pass
        elif kind in ("workspace.create.request", "agent.create.request"):
            if kind == "workspace.create.request":
                source = message["source"]
                cwd = source.get("path", source.get("cwd"))
                workspace = dict(WORKSPACE, id="fixture-w-" + str(len(self.workspaces)), workspaceDirectory=cwd,
                                 name=message.get("title", "New workspace"))
                self.workspaces.append(workspace)
                if not message.get("agent"):
                    self.send("workspace.create.response", requestId=req, workspace=workspace, agent=None, error=None,
                              creation=dict(phase="completed", workspace=workspace, agent=None, error=None))
                    self.send("workspace_update", kind="upsert", workspace=workspace)
                    return
                config = message["agent"]["config"]
                prompt = message["agent"].get("initialPrompt")
            else:
                workspace = next(w for w in self.workspaces if w["id"] == message["workspaceId"])
                config = message["config"]
                prompt = message.get("initialPrompt")
            self.agent = dict(copy.deepcopy(AGENT), id=str(uuid.uuid4()), workspaceId=workspace["id"],
                              title=config.get("title", "New agent"), cwd=workspace["workspaceDirectory"],
                              currentModeId=config.get("modeId", "default"), model=config.get("model", AGENT["model"]))
            self.agents.append(self.agent)
            self.history = []
            self.histories[self.agent["id"]] = self.history
            if prompt:
                self.history.append(dict(type="user_message", text=prompt, messageId=message.get("idempotencyKey")))
            self.send(kind.replace("request", "response"), requestId=req, workspace=workspace, agent=self.agent, error=None,
                      creation=dict(phase="completed", workspace=workspace, agent=self.agent, error=None))
            self.send("workspace_update", kind="upsert", workspace=workspace)
            self.send("agent_update", kind="upsert", agent=self.agent)
        elif kind in ("workspace.title.set.request", "workspace.pin.set.request", "archive_workspace_request"):
            workspace = next(w for w in self.workspaces if w["id"] == message["workspaceId"])
            if kind == "archive_workspace_request":
                self.workspaces.remove(workspace)
                self.send("workspace_update", kind="remove", id=workspace["id"])
            elif "title" in kind:
                workspace.update(name=message["title"], title=message["title"])
                self.send("workspace_update", kind="upsert", workspace=workspace)
            else:
                workspace["pinnedAt"] = NOW if message["pinned"] else None
                self.send("workspace_update", kind="upsert", workspace=workspace)
            self.send("status", requestId=req, status="updated")
        elif kind == "file_explorer_request":
            path = message.get("path", "")
            file = None
            directory = None
            if message["mode"] == "file":
                file = dict(path=path, kind="text", encoding="utf-8", content=FILES.get(path, ""), size=len(FILES.get(path, "")), modifiedAt=NOW, revision="fixture-v1")
            else:
                entries = [dict(name="README.md", path="README.md", kind="file"), dict(name="app", path="app", kind="directory")] if not path else [dict(name="Main.kt", path="app/Main.kt", kind="file")]
                if ICON_GALLERY:
                    prefix = path.rstrip("/") + "/" if path else ""
                    found = {}
                    for name in FILES:
                        if name.startswith(prefix):
                            remaining = name[len(prefix):]
                            child = remaining.split("/", 1)[0]
                            found[child] = dict(name=child, path=prefix + child, kind="directory" if "/" in remaining else "file")
                    entries = [found[key] for key in sorted(found)]
                directory = dict(path=path, entries=[dict(**entry, size=0, modifiedAt=NOW) for entry in entries])
            self.send("file_explorer_response", requestId=req, cwd=self.agent["cwd"], path=path, mode=message["mode"], file=file, directory=directory, error=None)
        elif kind == "fs.file.write.request":
            FILES[message["path"]] = message["content"]
            self.send("fs.file.write.response", requestId=req, result=dict(status="written", modifiedAt=NOW, revision="fixture-v2", size=len(message["content"])))
        elif kind == "checkout.diff.get.request":
            self.send("checkout.diff.get.response", requestId=req, cwd=self.agent["cwd"], error=None, files=[dict(path="Main.kt", additions=1, deletions=1, isNew=False, isDeleted=False, hunks=[dict(oldStart=1, newStart=1, oldCount=1, newCount=1, lines=[dict(type="remove", content='println("old")'), dict(type="add", content='println("Paseo")')])])])
        elif kind == "list_terminals_request":
            self.send("list_terminals_response", requestId=req, terminals=[dict(id="fixture-terminal", name="Fixture shell", cwd=self.agent["cwd"])] if self.terminal_exists else [])
        elif kind == "branch_suggestions_request":
            self.send("branch_suggestions_response", requestId=req, cwd=message.get("cwd"), branches=[b for b in ["main", "develop", "feature/ui"] if message.get("query", "") in b])
        elif kind == "create_terminal_request":
            self.terminal_exists = True
            self.send("create_terminal_response", requestId=req, terminal=dict(id="fixture-terminal", name="Fixture shell", cwd=self.agent["cwd"]), error=None)
        elif kind == "kill_terminal_request":
            self.terminal_exists = False
            self.send("kill_terminal_response", requestId=req, terminalId=message["terminalId"], error=None)
        elif kind == "capture_terminal_request":
            self.send("capture_terminal_response", requestId=req, terminalId=message["terminalId"], lines=self.output.splitlines(), totalLines=len(self.output.splitlines()))
        elif kind == "terminal_input":
            self.output += message["message"]["data"].replace("\r", "\n") + "[fixture] Command received\n$ "
        elif kind == "update_agent_request":
            self.agent = next(a for a in self.agents if a["id"] == message["agentId"])
            self.agent["title"] = message["name"]
            self.send("agent_update", kind="upsert", agent=self.agent)
            self.send("status", requestId=req, status="agent_updated", agentId=self.agent["id"])
        elif kind in ("archive_agent_request", "cancel_agent_request"):
            if kind == "archive_agent_request":
                self.agents = [a for a in self.agents if a["id"] != message["agentId"]]
                self.send("agent_update", kind="remove", agentId=message["agentId"])
            if kind == "cancel_agent_request":
                self.agent = next(a for a in self.agents if a["id"] == message["agentId"])
                self.agent.update(status="idle", activeTurn=None)
                for permission in self.agent["pendingPermissions"]:
                    self.send("agent_permission_resolved", agentId=self.agent["id"], requestId=permission["id"])
                self.agent["pendingPermissions"] = []
                self.send("agent_stream", agentId=self.agent["id"], timestamp=NOW, event=dict(type="turn_canceled", provider="claude", reason="Stopped"))
            self.send("status" if kind.startswith("archive") else "cancel_agent_response", requestId=req, agentId=self.agent["id"], status="agent_archived", error=None)
        else:
            self.send("rpc_error", requestId=req, error=f"Fixture does not implement {kind}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=6768)
    parser.add_argument("--icon-gallery", action="store_true", help="Include sample files for native icon rendering smoke")
    parser.add_argument("--events-file", help="Record protocol assertions for interaction smoke")
    args = parser.parse_args()
    EVENTS_FILE = args.events_file
    if args.icon_gallery:
        ICON_GALLERY = True
        for name in ["Main.kt", "App.tsx", "README.md", "script.py", "build.gradle", "package.json", "shell.sh", "sample.rs", "sample.svg", "LICENSE", "unknown.custom"]:
            FILES["icon-gallery/" + name] = "Icon rendering fixture\n"
    print(f"Local fixture: ws://127.0.0.1:{args.port}/ws; password: test", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
