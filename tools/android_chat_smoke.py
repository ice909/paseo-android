#!/usr/bin/env python3
"""Chat interaction regression on emulator-5554, after android_smoke.py.

Use mock_daemon.py --events-file /tmp/paseo-chat-events.jsonl. The fixture never
runs an AI agent or shell. --events must name that same local audit file.
"""
import argparse
import json
from pathlib import Path
import re
import time
import android_smoke as ui


def composer():
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        root = ui.tree()
        fields = [n for n in root.iter('node') if n.get('class') == 'android.widget.EditText']
        if fields and any(n.get('content-desc') == '消息输入框' for n in root.iter('node')):
            return fields[-1]
        time.sleep(.2)
    raise AssertionError('Composer editor did not appear')


def text():
    return composer().get('text', '')


def replace(value):
    ui.tap(composer())
    ui.adb('shell', 'input', 'keycombination', '113', '29')
    ui.adb('shell', 'input', 'keyevent', '67')
    # Some Android IMEs consume Ctrl+A after Enter. Verify the edit, then use
    # ordinary caret/delete keys rather than assuming the shortcut selected all.
    remaining = text()
    if remaining:
        keys = ['20'] * (remaining.count('\n') + 2) + ['123'] + ['67'] * (len(remaining) + 2)
        ui.adb('shell', 'input', 'keyevent', *keys)
        assert text() == '', 'Failed to clear editor'
    if value:
        ui.adb('shell', 'input', 'text', value.replace(' ', '%s'))


def long_press(description):
    target = ui.node(description, 'content-desc')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', target.get('bounds')))
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    ui.adb('shell', 'input', 'swipe', x, y, x, y, '850')


def menu():
    ui.hide_keyboard()
    ui.click('添加附件和发送选项', 'content-desc')


def stop():
    ui.hide_keyboard()
    ui.click('停止生成', 'content-desc')


def wait_until(check, message, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.2)
    raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--events', required=True)
    parser.add_argument('--reset', action='store_true', help='Reset only the disposable emulator app before pairing')
    parser.add_argument('--output', default='app/build/chat-smoke')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path(args.output)
    ui.output.mkdir(parents=True, exist_ok=True)
    events = Path(args.events)
    offset = len(events.read_text().splitlines()) if events.exists() else 0
    def messages(kind):
        return [event['message'] for line in events.read_text().splitlines()[offset:] if (event := json.loads(line)).get('type') == 'request' and event['message']['type'] == kind]
    if args.reset:
        ui.adb('shell', 'pm', 'clear', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    if args.reset:
        ui.open_pairing(); ui.fill(0, 'tcp://10.0.2.2:6768?password=test'); ui.hide_keyboard(); ui.submit_pairing()
    ui.node('已连接')
    ui.click('Native Android')
    composer()
    replace('hold original')
    ui.click('发送消息', 'content-desc')
    ui.node('Holding this turn', contains=True)
    replace('steer while running')
    ui.click('发送消息', 'content-desc')
    ui.node('Steering received:', contains=True)
    assert messages('send_agent_message_request')[-1]['activeTurnBehavior'] == 'steer'
    replace('hold queued')
    long_press('发送消息')
    ui.hide_keyboard()
    ui.node('编辑排队消息', 'content-desc')
    ui.screenshot('queue')
    ui.click('编辑排队消息', 'content-desc')
    assert text().lower() == 'hold queued'
    long_press('发送消息')
    stop()
    wait_until(lambda: any(m.get('text', '').lower() == 'hold queued' for m in messages('send_agent_message_request')), 'Queue did not drain')
    ui.node('停止生成', 'content-desc')
    ui.screenshot('queue-drained')
    stop()
    replace('fail-once retry')
    ui.click('发送消息', 'content-desc')
    ui.node('Fixture rejected', contains=True)
    assert text().lower() == 'fail-once retry'
    ui.screenshot('failed-draft-restored')
    ui.click('重试')
    ui.hide_keyboard()
    ui.node('Run a command?')
    attempts = [m for m in messages('send_agent_message_request') if m.get('text', '').lower() == 'fail-once retry']
    assert len(attempts) == 2 and attempts[0]['messageId'] == attempts[1]['messageId']
    # Queue preference must not strand an answer behind a parked permission.
    menu(); ui.click('加入队列')
    replace('permission answer')
    ui.click('发送消息', 'content-desc')
    ui.hide_keyboard()
    ui.node('Run a command?')
    assert messages('send_agent_message_request')[-1]['activeTurnBehavior'] == 'interrupt'
    ui.click('拒绝')
    ui.node('Permission result: deny')
    menu(); ui.click('引导当前任务')
    # File mention replacement uses the official quoted path syntax.
    replace('Inspect @Main')
    ui.click('app/Main.kt')
    assert text() == 'Inspect "app/Main.kt"'
    replace('/co')
    ui.click('/compact')
    assert text() == '/compact '
    replace('draft survives expansion')
    ui.click('展开输入框', 'content-desc')
    assert text().lower() == 'draft survives expansion'
    ui.screenshot('expanded-editor')
    ui.click('收起输入框', 'content-desc')
    assert text().lower() == 'draft survives expansion'
    # Android's Enter inserts a newline, without sending the draft.
    before = len(messages('send_agent_message_request'))
    ui.tap(composer()); ui.adb('shell', 'input', 'keyevent', '66')
    assert len(messages('send_agent_message_request')) == before
    assert '\n' in text()
    replace('')
    menu(); ui.click('Issue / Pull Request')
    ui.node('#42 Fixture issue')
    ui.click('#42 Fixture issue')
    ui.node('#42 Fixture issue')
    ui.screenshot('issue-attachment')
    ui.click('发送消息', 'content-desc')
    ui.hide_keyboard(); ui.node('Run a command?')
    last = messages('send_agent_message_request')[-1]
    assert last['text'] == '' and last['attachments'][0]['type'] == 'github_issue'
    ui.click('拒绝'); ui.node('Permission result: deny')
    # Streaming dictation, permission and draft insertion.
    ui.click('语音输入', 'content-desc')
    for _ in range(10):
        nodes = list(ui.tree().iter('node'))
        allow = next((n for n in nodes if n.get('resource-id', '').endswith('/permission_allow_foreground_only_button')), None)
        if allow is not None:
            ui.tap(allow); break
        if any(n.get('text', '').startswith('录音中') for n in nodes):
            break
        time.sleep(.3)
    ui.node('Dictation fixture…')
    ui.click('转为文字')
    wait_until(lambda: text() == 'Dictation fixture transcript', 'Transcript was not appended')
    ui.screenshot('dictation-transcript')
    replace('')
    menu(); ui.click('语音对话')
    ui.node('静音')
    wait_until(lambda: any(m.get('id') == 'voice-fixture' for m in messages('audio_played')), 'Voice playback acknowledgement missing')
    ui.click('静音'); ui.node('取消静音'); ui.click('取消静音')
    ui.click('取消语音', 'content-desc')
    wait_until(lambda: messages('set_voice_mode')[-1]['enabled'] is False, 'Voice mode did not stop')
    # Independent agent drafts, and encrypted draft recovery after process death.
    replace('persistent first-agent draft')
    ui.hide_keyboard()
    ui.click('切换代理或终端', 'content-desc'); ui.click('Second agent')
    assert text() == ''
    replace('second-agent draft')
    ui.hide_keyboard()
    ui.click('切换代理或终端', 'content-desc'); ui.click('Review the native Android client')
    assert text().lower() == 'persistent first-agent draft'
    time.sleep(.5)
    ui.adb('shell', 'am', 'force-stop', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    ui.node('已连接'); ui.click('Native Android')
    assert text().lower() == 'persistent first-agent draft'
    ui.screenshot('restored-draft')
    replace('hold slow-receipt')
    ui.click('发送消息', 'content-desc')
    wait_until(lambda: any(m.get('text', '').lower() == 'hold slow-receipt' for m in messages('send_agent_message_request')), 'Slow request did not reach fixture')
    ui.adb('shell', 'am', 'force-stop', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    ui.node('已连接'); ui.click('Native Android')
    ui.node('发送结果尚未确认')
    ui.screenshot('unconfirmed-outbox-restored')
    ui.click('重试发送')
    ui.node('停止生成', 'content-desc')
    attempts = [m for m in messages('send_agent_message_request') if m.get('text', '').lower() == 'hold slow-receipt']
    assert len(attempts) == 2 and attempts[0]['messageId'] == attempts[1]['messageId']
    stop()
    errors = ui.adb('logcat', '-d', '-s', 'AndroidRuntime:E').decode()
    assert 'FATAL EXCEPTION' not in errors, errors[-2000:]
    print('Chat send/steer/queue/retry/permissions/autocomplete/attachments/audio/draft recovery: passed', flush=True)


if __name__ == '__main__':
    main()
