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
args = parser.parse_args()
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
    deadline = time.monotonic() + 15
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
                    click("Testbox")
                else:
                    tap(allow)
                    print("lan-permission-granted: passed", flush=True)
                deadline = time.monotonic() + 15
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


if args.reset:
    adb("shell", "pm", "clear", "sh.paseo.android")
adb("shell", "am", "start", "-n", "sh.paseo.android/.MainActivity")
click("添加主机")
fill(0, "Testbox")
fill(1, "10.0.2.2:6768")
fill(2, "test")
adb("shell", "input", "keyevent", "4")
click("保存并连接")
node("已连接")
node("Review the native Android client")
screenshot("sessions")
click("Review the native Android client")
node("Native client review")
screenshot("chat")
fill(0, "Check streaming")
click("发送消息", "content-desc")
node("Run a command?")
screenshot("permission")
click("拒绝")
node("Permission result: deny")
screenshot("stream-result")
adb("shell", "input", "keyevent", "4")
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
click("改动")
node('println("Paseo")')
screenshot("diff")
click("终端")
node("/home/dev/paseo")
screenshot("terminal")
# Confirm encrypted host settings survive process death and reconnect.
adb("shell", "am", "force-stop", "sh.paseo.android")
adb("shell", "am", "start", "-n", "sh.paseo.android/.MainActivity")
node("已连接")
node("Review the native Android client")
screenshot("restored-host")
click("新会话", "content-desc")
fill_labeled("会话名称（可选）", "Native smoke")
adb("shell", "input", "keyevent", "4")
click("创建会话")
node("从一个任务开始")
node("Native smoke")
screenshot("created-session")
click("会话选项", "content-desc")
click("归档会话")
click("归档")
node("工作区")
screenshot("archived-session")
if args.deny_lan_once and int(adb("shell", "getprop", "ro.build.version.sdk").strip()) >= 37:
    assert denied_lan_once, "Android 17 LAN permission denial was not exercised"
print("All Android UI smoke checks passed.", flush=True)
