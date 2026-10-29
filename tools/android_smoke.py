#!/usr/bin/env python3
"""Exercise the native UI against tools/mock_daemon.py on a disposable emulator.

Starts with a clean app data directory when --reset is passed. Never use --reset
on a device containing host settings you want to keep.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--output", default="app/build/smoke")
parser.add_argument("--reset", action="store_true")
parser.add_argument("--deny-lan-once", action="store_true", help="On Android 17, deny the first LAN permission request, then retry and grant it")
args = parser.parse_args() if __name__ == "__main__" else parser.parse_args([])
denied_lan_once = False
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)


def adb(*command):
    return subprocess.check_output([args.adb, "-s", "emulator-5554", *command])


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/paseo-smoke.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/paseo-smoke.xml"))


def node(value, attribute="text", contains=False):
    global denied_lan_once
    deadline = time.monotonic() + 20
    last_sidebar_attempt = 0.0
    while time.monotonic() < deadline:
        try:
            nodes = list(tree().iter("node"))
        except (ET.ParseError, subprocess.CalledProcessError):
            time.sleep(.3)
            continue
        if value == "已连接":
            allow = next((n for n in nodes if n.attrib.get("resource-id", "").endswith("/permission_allow_button")), None)
            if allow is not None:
                if args.deny_lan_once and not denied_lan_once:
                    deny = next(n for n in nodes if n.attrib.get("resource-id", "").endswith("/permission_deny_button"))
                    tap(deny)
                    denied_lan_once = True
                    node("连接局域网主机需要", contains=True)
                    print("lan-permission-denied: passed", flush=True)
                    submit_pairing()
                else:
                    tap(allow)
                    print("lan-permission-granted: passed", flush=True)
                deadline = time.monotonic() + 15
                continue
            if not any(n.attrib.get("text") in ("已连接", "管理主机") for n in nodes) and time.monotonic() - last_sidebar_attempt > 3:
                menu = next((n for n in nodes if n.attrib.get("content-desc") == "打开侧栏"), None)
                if menu is not None:
                    tap(menu)
                    last_sidebar_attempt = time.monotonic()
                    continue
        for element in nodes:
            actual = element.attrib.get(attribute, "")
            if (value in actual) if contains else (actual == value):
                return element
        time.sleep(.2)
    raise AssertionError(f"UI element not found: {attribute}={value}")


def tap(element):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", element.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def click(value, attribute="text"):
    tap(node(value, attribute))




def open_pairing():
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        try:
            nodes = list(tree().iter("node"))
            if any(n.attrib.get("content-desc") == "配对链接" for n in nodes):
                return
            welcome = any(n.attrib.get("text") == "欢迎使用 Paseo" for n in nodes)
            label = "粘贴配对链接" if welcome else "添加主机"
            target = next((n for n in nodes if n.attrib.get("text") == label or n.attrib.get("content-desc") == label), None)
            if target is not None:
                tap(target)
        except (ET.ParseError, subprocess.CalledProcessError):
            pass
        time.sleep(.2)
    raise AssertionError("Add-host action did not appear")



def open_appearance():
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        try:
            nodes = list(tree().iter("node"))
            settings = next((n for n in nodes if n.get("text") == "设置"), None)
            if settings is not None:
                tap(settings)
                node("外观")
                return
            menu = next((n for n in nodes if n.get("content-desc") == "打开侧栏"), None)
            if menu is not None:
                tap(menu)
                click("设置")
                node("外观")
                return
        except (ET.ParseError, subprocess.CalledProcessError):
            pass
        time.sleep(.2)
    raise AssertionError("Appearance settings action did not appear")


def submit_pairing():
    nodes = list(tree().iter("node"))
    if any(n.attrib.get("text") == "配对" for n in nodes):
        click("配对")
    else:
        click("连接")


def click_revealed(value):
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size").decode())[0])
    for _ in range(5):
        for element in tree().iter("node"):
            if element.attrib.get("text") == value:
                bounds = list(map(int, re.findall(r"\d+", element.attrib["bounds"])))
                if bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    tap(element)
                    return
        # Scroll the pending permission card above the composer.
        adb("shell", "input", "swipe", str(int(width * .82)), str(int(height * .79)), str(int(width * .82)), str(int(height * .60)), "350")
    raise AssertionError(f"Scrollable control not found: {value}")


def fill(index, value):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            fields = [n for n in tree().iter("node") if n.attrib.get("class") == "android.widget.EditText"]
            if len(fields) > index:
                tap(fields[index])
                adb("shell", "input", "text", value.replace(" ", "%s"))
                return
        except (ET.ParseError, subprocess.CalledProcessError):
            pass
        time.sleep(.2)
    raise AssertionError(f"Editable field {index} not found")


def hide_keyboard():
    # Back also dismisses a dialog when the IME has not appeared yet. Wait for
    # the tap/focus transition, and send Back only if the IME is actually shown.
    time.sleep(.5)
    status = adb("shell", "dumpsys", "input_method").decode()
    if "mInputShown=true" in status:
        adb("shell", "input", "keyevent", "4")
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if "mInputShown=true" not in adb("shell", "dumpsys", "input_method").decode():
                break
            time.sleep(.1)


def screenshot(name):
    (output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    print(name + ": passed", flush=True)


def fill_labeled(label, value):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        for element in tree().iter("node"):
            if element.attrib.get("class") == "android.widget.EditText" and any(n.attrib.get("text") == label for n in element.iter("node")):
                tap(element)
                adb("shell", "input", "text", value.replace(" ", "%s"))
                return
        time.sleep(.2)
    raise AssertionError(f"Labeled field not found: {label}")


def main():
    if args.reset:
        adb("shell", "pm", "clear", "sh.paseo.android")
    adb("shell", "am", "start", "-n", "sh.paseo.android/.MainActivity")
    open_pairing()
    assert len([n for n in tree().iter("node") if n.attrib.get("class") == "android.widget.EditText"]) == 1
    fill(0, "tcp://10.0.2.2:6768?password=test")
    hide_keyboard()
    submit_pairing()
    node("已连接")
    node("Native Android")
    node("Empty project")
    screenshot("workspace-sidebar")
    click("Native Android")
    node("Native client review")
    screenshot("chat")
    # Two agents share a workspace, but have independent timelines.
    click("切换代理或终端", "content-desc")
    click("Second agent")
    node("Native client review")
    screenshot("second-agent")
    click("Sonnet (fixture)")
    click("Opus (fixture)")
    node("Opus (fixture)")
    click("Default")
    click("Plan")
    node("Plan")
    screenshot("agent-options")
    fill(0, "Check streaming")
    click("发送消息", "content-desc")
    hide_keyboard()
    node("Run a command?")
    screenshot("permission")
    click("拒绝")
    node("Permission result: deny")
    screenshot("stream-result")
    fill(0, "Ask a question")
    click("发送消息", "content-desc")
    hide_keyboard()
    node("Which language?")
    tap(node("Kotlin", contains=True))
    click_revealed("提交回答")
    node("Question answer: Kotlin")
    screenshot("question-answer")
    click("打开文件和改动", "content-desc")
    node('println("Paseo")')
    screenshot("diff")
    click("文件")
    node("README.md")
    click("README.md")
    node("# Paseo Native", contains=True)
    screenshot("file")
    click("编辑文件", "content-desc")
    fill(0, "SMOKE")
    click("保存文件")
    node("编辑文件", "content-desc")
    node("SMOKE", contains=True)
    screenshot("saved-file")
    click("关闭文件面板", "content-desc")
    click("切换代理或终端", "content-desc")
    click("终端")
    node("/home/dev/paseo", contains=True)
    fill(0, "pwd")
    click("执行命令", "content-desc")
    node("[fixture] Command received", contains=True)
    hide_keyboard()
    screenshot("terminal")
    click("关闭终端", "content-desc")
    click("关闭")
    node("还没有终端")
    # Empty workspaces support browsing and terminals without a fabricated agent.
    click("打开侧栏", "content-desc")
    click("Empty workspace")
    node("工作区已就绪")
    click("打开文件和改动", "content-desc")
    click("文件")
    node("README.md")
    screenshot("empty-workspace-files")
    click("关闭文件面板", "content-desc")
    # Confirm encrypted host settings survive process death and reconnect.
    adb("shell", "am", "force-stop", "sh.paseo.android")
    adb("shell", "am", "start", "-n", "sh.paseo.android/.MainActivity")
    node("已连接")
    node("Native Android")
    screenshot("restored-host")
    click("在 paseo 新建工作区", "content-desc")
    screenshot("new-workspace")
    click("创建工作区", "content-desc")
    node("工作区已就绪")
    node("New workspace")
    screenshot("created-workspace")
    click("新建代理")
    fill(0, "Second smoke")
    hide_keyboard()
    click("创建代理", "content-desc")
    node("Second smoke")
    screenshot("created-agent")
    click("工作区选项", "content-desc")
    click("归档会话")
    click("归档")
    node("New workspace")
    click("工作区选项", "content-desc")
    click("归档工作区")
    click("归档")
    click("打开侧栏", "content-desc")
    node("Native Android")
    assert not any(n.attrib.get("text") == "New workspace" for n in tree().iter("node"))
    screenshot("archived-workspace")
    if args.deny_lan_once and int(adb("shell", "getprop", "ro.build.version.sdk").strip()) >= 37:
        assert denied_lan_once, "Android 17 LAN permission denial was not exercised"
    print("All Android UI smoke checks passed.", flush=True)


if __name__ == "__main__":
    main()
