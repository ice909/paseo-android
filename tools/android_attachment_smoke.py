#!/usr/bin/env python3
"""Pick a local text file and image, preview/send them, and inspect fixture payloads.

Run after android_chat_smoke.py against the same --events-file mock. Targets only
emulator-5554; copies fixtures into its disposable Download directory.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import time
import android_smoke as ui
from android_chat_smoke import menu, replace, wait_until


def pick_document(name):
    deadline = time.monotonic() + 25
    searched = False
    while time.monotonic() < deadline:
        nodes = list(ui.tree().iter('node'))
        target = next((n for n in nodes if n.get('class') not in ('android.widget.EditText', 'android.widget.AutoCompleteTextView') and (n.get('text') == name or n.get('content-desc', '').startswith(name + ','))), None)
        if target is not None:
            ui.tap(target)
            return
        if not searched:
            field = next((n for n in nodes if n.get('resource-id', '').endswith('/search_src_text')), None)
            if field is not None:
                ui.tap(field)
                ui.adb('shell', 'input', 'text', name)
                ui.hide_keyboard()
                searched = True
                continue
            search = next((n for n in nodes if n.get('resource-id', '').endswith('/option_menu_search') or n.get('resource-id', '').endswith('/action_menu_search') or n.get('content-desc') in ('Search', '搜索')), None)
            if search is not None:
                ui.tap(search)
                continue
            roots = next((n for n in nodes if n.get('content-desc') in ('Show roots', '显示根目录')), None)
            if roots is not None:
                ui.tap(roots)
                continue
            downloads = next((n for n in nodes if n.get('text') in ('Downloads', '下载')), None)
            if downloads is not None:
                ui.tap(downloads)
                continue
        time.sleep(.3)
    raise AssertionError('Document not found: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--events', required=True)
    parser.add_argument('--output', default='app/build/attachment-smoke')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    events = Path(args.events)
    def requests():
        return [e['message'] for line in events.read_text().splitlines() if (e := json.loads(line)).get('type') == 'request' and e['message']['type'] == 'send_agent_message_request']
    fixtures = [('paseo-chat-fixture.txt', ui.output / 'paseo-chat-fixture.txt', b'Native attachment protocol fixture\n'),
                ('paseo-chat-fixture.png', Path('../paseo/packages/website/public/phone-1.png'), None)]
    for name, source, content in fixtures:
        if content is not None:
            source.write_bytes(content)
        ui.adb('push', str(source.resolve()), '/sdcard/Download/' + name)
        ui.adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/' + name)
    replace('')
    for name, source, _ in fixtures:
        menu(); ui.click('添加图片' if name.endswith('.png') else '添加文件')
        pick_document(name)
        ui.node(name)
        ui.screenshot('image-tray' if name.endswith('.png') else 'file-tray')
        ui.click(name)
        if name.endswith('.png'):
            ui.node('关闭预览', 'content-desc'); ui.screenshot('image-preview'); ui.click('关闭预览', 'content-desc')
        else:
            ui.node('Native attachment protocol fixture', contains=True); ui.click('关闭')
        before = len(requests())
        ui.click('发送消息', 'content-desc')
        ui.hide_keyboard(); ui.node('Run a command?')
        wait_until(lambda: len(requests()) > before, 'Attachment message was not sent')
        message = requests()[-1]
        assert message['text'] == ''
        if name.endswith('.png'):
            image = message['images'][0]
            assert image['mimeType'] == 'image/png'
            assert hashlib.sha256(base64.b64decode(image['data'])).digest() == hashlib.sha256(source.read_bytes()).digest()
        else:
            assert message['attachments'][0]['type'] == 'uploaded_file'
            assert message['attachments'][0]['fileName'] == name
            uploads = [e for line in events.read_text().splitlines() if (e := json.loads(line)).get('type') == 'upload-complete']
            assert uploads[-1]['sha256'] == hashlib.sha256(source.read_bytes()).hexdigest()
        ui.click('拒绝'); ui.node('Permission result: deny')
    print('Document picker, image preview, image bytes and uploaded file payload: passed', flush=True)


if __name__ == '__main__':
    main()
