#!/usr/bin/env python3
"""Render streamed assistant images on a disposable emulator and local daemon fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import android_smoke as ui
from android_chat_smoke import replace, text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--events', required=True)
    parser.add_argument('--output', default='app/build/markdown-image-smoke')
    parser.add_argument('--reset', action='store_true')
    parser.add_argument('--quick', action='store_true', help='Verify a host image, its preview and the explorer in the installed build')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    if args.reset:
        ui.adb('shell', 'pm', 'clear', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    if args.reset:
        ui.open_pairing()
        ui.fill(0, 'tcp://10.0.2.2:6768?password=test')
        ui.hide_keyboard()
        ui.submit_pairing()
    ui.node('已连接')
    ui.click('Native Android')
    ui.node('Native client review')

    def send(command):
        replace(command)
        ui.hide_keyboard()
        ui.click('发送消息', 'content-desc')

    send('image-local')
    ui.node('图片：Image', 'content-desc')
    ui.node('前文')
    ui.node('后文')
    ui.screenshot('assistant-file-image')
    ui.click('图片：Image', 'content-desc')
    ui.node('关闭图片预览', 'content-desc')
    ui.screenshot('image-fullscreen-preview')
    ui.click('关闭图片预览', 'content-desc')
    if args.quick:
        replace('Retained draft')
        ui.click('打开文件和改动', 'content-desc')
        ui.node('关闭文件面板', 'content-desc')
        assert b'mInputShown=true' not in ui.adb('shell', 'dumpsys', 'input_method')
        ui.adb('shell', 'input', 'keyevent', '4')
        ui.node('消息输入框', 'content-desc')
        assert text() == 'Retained draft', text()
        print('Installed APK: host image, fullscreen preview, explorer and retained draft: passed', flush=True)
        return
    for command, label in [('image-relative', 'Relative'), ('image-outside', 'Outside'), ('image-http', 'Web'), ('image-data', 'Data')]:
        send(command)
        ui.node('图片：'+label, 'content-desc')
    send('image-retry')
    ui.tap(ui.node('Retry加载失败', contains=True))
    ui.node('图片：Retry', 'content-desc')
    send('image-user')
    ui.node('图片：图片', 'content-desc')
    ui.screenshot('received-user-image')
    send('image-code')
    ui.node('![NotAnImage]', contains=True)
    records = [json.loads(line) for line in Path(args.events).read_text().splitlines()]
    reads = [r for r in records if r.get('type') == 'image-read']
    assert any(r['cwd'] == '/home/dev/paseo' and r['path'] == '/home/dev/paseo/assets/phone image.png' for r in reads), reads
    assert any(r['cwd'] == '/home/dev/paseo' and r['path'] == 'assets/phone image.png' for r in reads), reads
    assert any(r['cwd'] == '/' and r['path'] == '/tmp/fixture-image.png' for r in reads), reads
    assert max(r['attempt'] for r in reads if r['path'].endswith('retry.png')) == 2
    assert not any('not-loaded.png' in r['path'] for r in reads)
    expected = hashlib.sha256(Path('../paseo/packages/website/public/phone-1.png').read_bytes()).hexdigest()
    assert all(r['sha256'] == expected for r in reads)
    assert any(r.get('type') == 'image-http' and r.get('sha256') == expected for r in records)
    print('Markdown host, relative, outside-workspace, HTTP, data and received images; retry, preview and fenced code: passed', flush=True)


if __name__ == '__main__':
    main()
