#!/usr/bin/env python3
"""Check official welcome / pairing presentation on a disposable emulator.

Requires tools/mock_daemon.py on 6768. --reset clears only the app on emulator-5554.
"""
import argparse
from pathlib import Path
import time
import re
import android_smoke as ui


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--output', default='app/build/welcome-smoke')
    parser.add_argument('--reset', action='store_true')
    parser.add_argument('--layout-only', action='store_true', help='Check small-screen layouts without connecting a host')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    if args.reset:
        ui.adb('shell', 'pm', 'clear', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    ui.node('欢迎使用 Paseo')
    ui.node('连接你的电脑即可开始')
    ui.node('Paseo', 'content-desc')
    for label in ['扫描二维码', '直接连接', '粘贴配对链接', '设置', 'paseo.sh']:
        ui.node(label)
    nodes = list(ui.tree().iter('node'))
    assert not any(n.get('class') == 'android.widget.EditText' for n in nodes)
    assert not any(n.get('text') in ['主机', '将工作带在身边', '开始连接'] for n in nodes)
    ui.screenshot('welcome')
    ui.click('设置')
    ui.node('外观')
    ui.click('主题：系统', 'content-desc')
    ui.tap(ui.node('选择主题：Light', 'content-desc', contains=True))
    ui.click('返回', 'content-desc')
    ui.node('欢迎使用 Paseo')
    ui.screenshot('welcome-light')
    if args.layout_only:
        ui.open_appearance()
        ui.click('主题：Light', 'content-desc')
        ui.tap(ui.node('选择主题：Dark', 'content-desc', contains=True))
        ui.click('返回', 'content-desc')
        ui.node('欢迎使用 Paseo')
        ui.screenshot('welcome-dark')
        try:
            for size, name in [('840x1600', 'small-portrait'), ('1920x1080', 'landscape')]:
                ui.adb('shell', 'wm', 'size', size)
                time.sleep(1)
                ui.node('欢迎使用 Paseo')
                ui.screenshot('welcome-' + name)
                # The whole welcome content scrolls when its natural height is
                # taller than the viewport, rather than clipping weighted rows.
                width, height = map(int, size.split('x'))
                ui.adb('shell', 'input', 'swipe', str(width // 2), str(int(height * .8)), str(width // 2), str(int(height * .25)), '350')
                target = ui.node('粘贴配对链接')
                bounds = list(map(int, re.findall(r'\d+', target.get('bounds'))))
                assert bounds[3] > bounds[1] and bounds[2] > bounds[0]
                ui.tap(target)
                ui.node('配对链接', 'content-desc')
                ui.hide_keyboard()
                ui.click('展开连接面板', 'content-desc')
                ui.node('收起连接面板', 'content-desc')
                ui.screenshot('pair-link-' + name)
                ui.click('关闭', 'content-desc')
        finally:
            ui.adb('shell', 'wm', 'size', 'reset')
        print('Small welcome and pairing layouts: passed', flush=True)
        return
    # Scanner asks for permission only when entered; deny once and verify the fallback card.
    ui.click('扫描二维码')
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        nodes = list(ui.tree().iter('node'))
        deny = next((n for n in nodes if n.get('resource-id', '').endswith('/permission_deny_button')), None)
        if deny is not None:
            ui.tap(deny)
            break
        time.sleep(.2)
    ui.node('允许相机访问，以扫描 daemon 提供的配对二维码。')
    ui.screenshot('camera-permission-denied')
    ui.click('返回', 'content-desc')
    ui.click('扫描二维码')
    ui.click('授予权限')
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        nodes = list(ui.tree().iter('node'))
        allow = next((n for n in nodes if n.get('resource-id', '').endswith('/permission_allow_foreground_only_button')), None)
        if allow is not None:
            ui.tap(allow)
            break
        time.sleep(.2)
    ui.node('扫描二维码')
    time.sleep(1)
    permissions = ui.adb('shell', 'dumpsys', 'package', 'sh.paseo.android').decode()
    assert 'android.permission.CAMERA: granted=true' in permissions
    assert not any('无法打开相机' in n.get('text', '') for n in ui.tree().iter('node'))
    deadline = time.monotonic() + 20
    while 'sh.paseo.android' not in ui.adb('shell', 'dumpsys', 'media.camera').decode():
        if time.monotonic() >= deadline: raise AssertionError('Camera client did not become active')
        time.sleep(.2)
    ui.screenshot('camera-preview')
    ui.click('返回', 'content-desc')
    ui.open_pairing()
    ui.node('粘贴来自 server 的配对链接。')
    fields = [n for n in ui.tree().iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(fields) == 1
    ui.screenshot('pair-link-keyboard')
    ui.hide_keyboard()
    ui.click('展开连接面板', 'content-desc')
    ui.node('收起连接面板', 'content-desc')
    ui.screenshot('pair-link-expanded')
    ui.click('收起连接面板', 'content-desc')
    ui.fill(0, 'https://app.paseo.sh/#offer=bad')
    ui.hide_keyboard()
    ui.submit_pairing()
    ui.node('配对链接无效', contains=True)
    ui.screenshot('pair-link-error')
    ui.click('取消')
    ui.node('欢迎使用 Paseo')
    ui.click('直接连接')
    ui.node('输入 Paseo server 的地址。')
    fields = [n for n in ui.tree().iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(fields) == 3
    ui.screenshot('direct-connection')
    ui.fill(0, '10.0.2.2')
    ui.tap(ui.node('端口', 'content-desc'))
    ui.adb('shell', 'input', 'keyevent', '123', '67', '67', '67', '67')
    ui.adb('shell', 'input', 'text', '6768')
    ui.fill(2, 'test')
    ui.hide_keyboard()
    ui.click('连接')
    ui.node('已连接')
    ui.node('Local test fixture')
    ui.screenshot('direct-connected')
    print('Welcome, pairing fields, camera permission and direct connection: passed', flush=True)


if __name__ == '__main__':
    main()
