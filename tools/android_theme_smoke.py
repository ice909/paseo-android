#!/usr/bin/env python3
"""Check native themes on a disposable API 37+ emulator (emulator-5554).

Requires an installed APK. --with-host uses tools/mock_daemon.py on port 6768.
--reset clears this app's data on the emulator; never use with personal profiles.
Only Python's standard library is required.
"""
import argparse
from pathlib import Path
import struct
import time
import android_smoke as ui

# Official surface0 colors, deliberately independent of the Kotlin resolver.
THEMES = [
    ("Light", "FFFFFF"), ("Dark", "181B1A"), ("系统", "FFFFFF"),
    ("Zinc", "18181B"), ("Midnight", "161820"), ("Claude", "1F1F1E"),
    ("Ghostty", "282C34"), ("纯黑", "000000"),
]


def open_settings():
    ui.open_appearance()

def select_theme(label):
    trigger = next(n for n in ui.tree().iter("node") if n.attrib.get("content-desc", "").startswith("主题："))
    ui.tap(trigger)
    ui.tap(ui.node("选择主题：" + label, "content-desc", contains=True))
    ui.node("主题：" + label, "content-desc")


def check_background(hex_color):
    expected = bytes.fromhex(hex_color)
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        # The API 37 emulator emits tightly packed RGBA8888 with a 16-byte
        # header. A point outside settings' horizontal padding avoids text.
        raw = ui.adb("exec-out", "screencap")
        width, height, fmt = struct.unpack_from("<III", raw)
        assert fmt in (1, 2), f"Unsupported screencap pixel format {fmt}"
        offset = len(raw) - width * height * 4
        assert offset in (12, 16), f"Unexpected screencap header {offset}"
        index = offset + (int(height * .23) * width + 2) * 4
        actual = raw[index:index + 3]
        if actual == expected:
            return
        time.sleep(.2)
    raise AssertionError(f"Expected background #{hex_color}, got #{actual.hex()}")


def restart():
    ui.adb("shell", "am", "force-stop", "sh.paseo.android")
    ui.adb("shell", "am", "start", "-n", "sh.paseo.android/.MainActivity")
    open_settings()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", default="app/build/theme-smoke")
    parser.add_argument("--reset", action="store_true")
    parser.add_argument("--with-host", action="store_true")
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    original_night = ui.adb("shell", "cmd", "uimode", "night").decode().strip().split()[-1]
    try:
        ui.adb("shell", "cmd", "uimode", "night", "no")
        if args.reset:
            ui.adb("shell", "pm", "clear", "sh.paseo.android")
        ui.adb("shell", "am", "start", "-n", "sh.paseo.android/.MainActivity")
        if args.with_host:
            ui.open_pairing()
            ui.fill(0, "tcp://10.0.2.2:6768?password=test")
            ui.hide_keyboard()
            ui.submit_pairing()
            ui.node("已连接")
            ui.click("Native Android")
            ui.node("Native client review")
            ui.fill(0, "Theme draft retained")
            ui.hide_keyboard()
        open_settings()
        if args.reset:
            ui.node("主题：系统", "content-desc")
        for label, background in THEMES:
            select_theme(label)
            check_background(background)
            ui.screenshot("theme-" + background.lower())
        if args.with_host:
            ui.click("返回", "content-desc")
            ui.node("Theme draft retained")
            ui.screenshot("pure-black-chat")
            open_settings()
        # Explicit dark choice survives restart while the device is light.
        restart()
        ui.node("主题：纯黑", "content-desc")
        check_background("000000")
        print("manual-dark-persistence: passed", flush=True)
        # And explicit Light stays light while the device is dark.
        ui.adb("shell", "cmd", "uimode", "night", "yes")
        ui.node("主题：纯黑", "content-desc")
        select_theme("Light")
        check_background("FFFFFF")
        restart()
        ui.node("主题：Light", "content-desc")
        check_background("FFFFFF")
        ui.screenshot("manual-light-on-dark-system")
        print("manual-light-persistence: passed", flush=True)
        select_theme("系统")
        check_background("181B1A")
        ui.screenshot("auto-dark")
        ui.adb("shell", "cmd", "uimode", "night", "no")
        ui.node("主题：系统", "content-desc")
        check_background("FFFFFF")
        print("system-theme-change: passed", flush=True)
        if args.with_host:
            # Return to the original agent and preserve its unsent text, then
            # switch themes from the diff and terminal without losing the pane.
            ui.click("返回", "content-desc")
            ui.click("打开侧栏", "content-desc")
            ui.click("Native Android")
            ui.node("Native client review")
            ui.fill(0, "Theme draft retained")
            ui.hide_keyboard()
            open_settings()
            select_theme("Claude")
            ui.click("返回", "content-desc")
            ui.node("Theme draft retained")
            ui.screenshot("claude-chat")
            ui.click("打开文件和改动", "content-desc")
            ui.node('println("Paseo")')
            open_settings()
            select_theme("Zinc")
            ui.click("返回", "content-desc")
            ui.node('println("Paseo")')
            ui.screenshot("zinc-diff")
            ui.click("关闭文件面板", "content-desc")
            ui.click("切换代理或终端", "content-desc")
            ui.click("终端")
            ui.node("/home/dev/paseo", contains=True)
            open_settings()
            select_theme("Light")
            ui.click("返回", "content-desc")
            ui.node("/home/dev/paseo", contains=True)
            ui.screenshot("light-terminal")
            ui.click("关闭终端", "content-desc")
            ui.node("关闭终端？")
            ui.screenshot("light-dialog")
            ui.click("取消")
            print("connected-workspace-theme-change: passed", flush=True)
        print("theme smoke: passed", flush=True)
    finally:
        if original_night in ("yes", "no", "auto"):
            ui.adb("shell", "cmd", "uimode", "night", original_night)


if __name__ == "__main__":
    main()
