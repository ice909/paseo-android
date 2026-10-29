#!/usr/bin/env python3
"""Check the compact home UI and creation payloads on a disposable fixture emulator.

Run mock_daemon.py --port 6768 --events-file <events> first. --reset only clears
the isolated emulator's app data. No real shell or AI commands run in the fixture.
"""
import argparse
import json
from pathlib import Path
import re
import android_smoke as ui
from android_attachment_smoke import pick_document


def bounds(element):
    return list(map(int, re.findall(r"\d+", element.attrib["bounds"])))


def check_keyboard_gap():
    # The IME window includes a transparent area above the actual keyboard.
    windows = ui.adb("shell", "dumpsys", "window", "windows").decode()
    ime = re.search(r"Window #\d+ Window\{[^}]+ InputMethod\}:(.*?)(?=\n  Window #|\Z)", windows, re.S).group(1)
    inset = int(re.search(r"mGivenContentInsets=\[\d+,(\d+)\]", ime).group(1))
    frame = int(re.search(r"Frames:.*? frame=\[\d+,(\d+)\]", ime).group(1))
    _, top, _, bottom = bounds(ui.node("创建工作区", "content-desc"))
    density = int(re.findall(r"\d+", ui.adb("shell", "wm", "density").decode())[-1]) / 160
    gap = (frame + inset - (top + bottom) / 2) / density
    assert 30 <= gap <= 45, f"Composer is too far from or overlaps the keyboard: {gap:.1f} dp"
    print(f"Keyboard spacing: {gap:.1f} dp from send center, passed", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", default="app/build/home-smoke")
    parser.add_argument("--events", required=True)
    parser.add_argument("--reset", action="store_true")
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    if args.reset:
        ui.adb("shell", "pm", "clear", "sh.paseo.android")
        ui.adb("shell", "am", "start", "-n", "sh.paseo.android/.MainActivity")
        ui.open_pairing()
        ui.fill(0, "tcp://10.0.2.2:6768?password=test")
        ui.hide_keyboard()
        ui.submit_pairing()
        ui.node("已连接")

    def home(project=False):
        nodes = list(ui.tree().iter("node"))
        if not any(n.get("text") == "新建工作区" for n in nodes):
            ui.click("打开侧栏", "content-desc")
        ui.click("在 paseo 新建工作区", "content-desc") if project else ui.click("新建工作区")
        ui.node("项目", "content-desc")

    def field(label):
        ui.click(label, "content-desc")

    def input_text(value):
        ui.fill(0, value)
        ui.hide_keyboard()

    home()
    nodes = list(ui.tree().iter("node"))
    assert not any(n.get("text") in ("新建工作区", "名称（可选）", "工作目录") for n in nodes)
    assert len([n for n in nodes if n.get("class") == "android.widget.EditText"]) == 1
    density = int(re.findall(r"\d+", ui.adb("shell", "wm", "density").decode())[-1]) / 160
    for label in ("项目", "主机", "What to launch"):
        x1, y1, x2, y2 = bounds(ui.node(label, "content-desc"))
        assert abs((y2 - y1) / density - 56) <= 1, (label, y2 - y1, density)
        assert abs(x1 / density - 16) <= 1, (label, x1, density)
    ui.node("添加附件", "content-desc")
    ui.node("语音输入", "content-desc")
    ui.screenshot("home-unselected-light")
    field("项目")
    ui.node("搜索项目", "content-desc")
    ui.click("添加项目")
    ui.node("主机上的绝对目录", "content-desc")
    ui.screenshot("project-directory-sheet")
    ui.click("关闭选择面板", "content-desc")
    field("项目")
    ui.click("paseo")
    ui.node("隔离", "content-desc")
    field("隔离")
    ui.click("新建 worktree")
    field("分支")
    ui.click("develop")
    ui.node("develop")
    ui.screenshot("home-worktree-light")
    input_text("Home layout smoke")
    text_file = ui.output / "paseo-home-fixture.txt"
    text_file.write_text("Native home attachment fixture\n")
    for name, source, kind in [(text_file.name, text_file, "文件"), ("paseo-home-fixture.png", Path("../paseo/packages/website/public/phone-1.png"), "图片")]:
        ui.adb("push", str(source.resolve()), "/sdcard/Download/" + name)
        ui.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Download/" + name)
        ui.click("添加附件", "content-desc")
        ui.click(kind)
        pick_document(name)
        ui.node(name)
    ui.adb("shell", "pm", "grant", "sh.paseo.android", "android.permission.RECORD_AUDIO")
    ui.click("语音输入", "content-desc")
    ui.node("转为文字")
    ui.click("转为文字")
    ui.node("Dictation fixture transcript", contains=True)
    ui.screenshot("home-attachments-dictation")
    ui.click("创建工作区", "content-desc")
    ui.node("New agent")
    ui.node("Home layout smoke", contains=True)
    ui.screenshot("created-chat")
    home(project=True)
    field("What to launch")
    ui.click("Terminal")
    input_text("pwd")
    ui.click("创建工作区", "content-desc")
    ui.node("Fixture shell")
    ui.screenshot("created-terminal")
    home(project=True)
    # The launch selection is retained; switch back to Chat for an empty workspace.
    field("What to launch")
    ui.click("Chat")
    ui.click("创建工作区", "content-desc")
    ui.node("工作区已就绪")
    ui.screenshot("created-empty-workspace")
    home(project=True)
    ui.open_appearance()
    ui.click("主题：系统", "content-desc")
    ui.click("选择主题：Ghostty", "content-desc")
    ui.node("主题：Ghostty", "content-desc")
    ui.click("返回", "content-desc")
    ui.node("项目", "content-desc")
    ui.screenshot("home-ghostty")
    ui.fill(0, "Keyboard layout")
    ui.node("创建工作区", "content-desc")
    check_keyboard_gap()
    ui.screenshot("home-keyboard")
    ui.hide_keyboard()
    records = [json.loads(line) for line in Path(args.events).read_text().splitlines()]
    messages = [r["message"] for r in records if r.get("type") == "request"]
    creates = [m for m in messages if m.get("type") == "workspace.create.request"]
    assert len(creates) == 3, len(creates)
    chat, terminal, empty = creates
    assert chat["source"]["kind"] == "worktree" and chat["source"]["baseBranch"] == "develop", chat["source"]
    assert chat["agent"]["initialPrompt"].casefold() == "home layout smoke dictation fixture transcript", chat["agent"]["initialPrompt"]
    assert len(chat["agent"]["images"]) == 1 and chat["agent"]["images"][0]["mimeType"] == "image/png"
    assert len(chat["agent"]["attachments"]) == 1 and chat["agent"]["attachments"][0]["fileName"] == text_file.name
    assert not terminal.get("agent") and not empty.get("agent")
    assert any(m.get("type") == "create_terminal_request" for m in messages)
    assert any(m.get("type") == "terminal_input" and m["message"]["data"].strip() == "pwd" for m in messages)
    print("Compact home geometry, project/branch selection, initial attachments/dictation, chat, terminal, empty workspace, themes and keyboard: passed", flush=True)


if __name__ == "__main__":
    main()
