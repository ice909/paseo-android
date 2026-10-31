#!/usr/bin/env python3
"""Check compact header and model/effort settings against mock_daemon.py --agent-controls.

Run on a disposable emulator-5554; --events must match the fixture audit path.
"""
import argparse
import json
from pathlib import Path
import re
import android_smoke as ui
from android_chat_smoke import replace, text
from android_theme_smoke import select_theme


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--events', required=True)
    parser.add_argument('--reset', action='store_true')
    parser.add_argument('--layout-only', action='store_true', help='Resume on the current conversation to check fullscreen and themes')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path('app/build/agent-controls-smoke')
    ui.output.mkdir(parents=True, exist_ok=True)
    if args.reset:
        ui.adb('shell', 'pm', 'clear', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    if args.layout_only:
        ui.node('已连接')
        ui.click('Native Android')
        check_layout()
        return
    if args.reset:
        ui.open_pairing()
        ui.fill(0, 'tcp://10.0.2.2:6768?password=test')
        ui.hide_keyboard()
        ui.submit_pairing()
    ui.node('已连接')
    ui.click('Native Android')
    ui.node('Native Android')
    ui.click('切换代理或终端', 'content-desc')
    ui.click('Review the native Android client')
    ui.node('High')  # Null configured model resolves the catalog default, not the first model.
    icons = [bounds(ui.node(label, 'content-desc')) for label in ('打开侧栏', '切换代理或终端', '工作区选项', '打开文件和改动')]
    assert max((rect[1] + rect[3]) / 2 for rect in icons) - min((rect[1] + rect[3]) / 2 for rect in icons) < 8, icons
    assert not any(n.get('text') == 'Review the native Android client' for n in ui.tree().iter('node'))
    ui.screenshot('compact-header-default-effort')
    title_rect = bounds(ui.node('Native Android'))
    ui.adb('shell', 'input', 'swipe', '500', '500', '500', '1350', '500')
    assert bounds(ui.node('Native Android')) == title_rect
    ui.screenshot('floating-header-scrolled')
    ui.click('模型和思考强度', 'content-desc')
    ui.click('思考强度')
    ui.node('High')
    ui.screenshot('thinking-options')
    ui.click('Low')
    ui.node('模型设置')
    ui.click('关闭模型设置', 'content-desc')
    ui.node('Low')
    requests = [json.loads(line)['message'] for line in Path(args.events).read_text().splitlines() if json.loads(line).get('type') == 'request']
    assert any(r.get('type') == 'set_agent_thinking_request' and r.get('thinkingOptionId') == 'low' for r in requests)
    ui.click('模型和思考强度', 'content-desc')
    ui.click('思考强度')
    ui.click('Rejected (fixture)')
    ui.node('Unsupported effort (fixture)')
    ui.node('Low')
    ui.screenshot('rejected-effort')
    ui.click('模型')
    ui.click('Opus (fixture)')
    ui.node('模型设置')
    assert not any(n.get('text') == '思考强度' for n in ui.tree().iter('node'))
    ui.click('关闭模型设置', 'content-desc')
    ui.node('Opus (fixture)')
    print('default-model, reasoning RPC, rejection, model-without-reasoning: passed', flush=True)

    replace('retained-draft')
    ui.hide_keyboard()
    ui.click('切换代理或终端', 'content-desc')
    ui.click('Second agent')
    ui.node('Sonnet (fixture)')
    ui.node('Low')  # Runtime alias overrides unknown configured model.
    ui.click('切换代理或终端', 'content-desc')
    ui.click('Review the native Android client')
    assert text() == 'retained-draft'
    ui.click('打开文件和改动', 'content-desc')
    ui.node('关闭文件面板', 'content-desc')
    ui.adb('shell', 'input', 'keyevent', '4')
    assert text() == 'retained-draft'
    ui.screenshot('retained-draft-after-explorer')
    print('runtime-alias, tab-switch, explorer, draft: passed', flush=True)
    check_layout()


def check_layout():
    # A long draft makes the fullscreen composer affordance available.
    replace(' '.join(['long-draft'] * 30))
    ui.hide_keyboard()
    ui.click('展开输入框', 'content-desc')
    assert not any(n.get('content-desc') == '打开侧栏' for n in ui.tree().iter('node'))
    ui.node('收起输入框', 'content-desc')
    ui.screenshot('fullscreen-composer')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.node('打开侧栏', 'content-desc')
    assert text().startswith('long-draft')
    print('fullscreen header and draft: passed', flush=True)
    ui.open_appearance()
    select_theme('Ghostty')
    ui.click('返回', 'content-desc')
    ui.node('模型和思考强度', 'content-desc')
    ui.screenshot('ghostty-compact-header')
    ui.click('模型和思考强度', 'content-desc')
    ui.node('模型设置')
    ui.screenshot('ghostty-model-settings')
    ui.click('关闭模型设置', 'content-desc')


if __name__ == '__main__':
    main()
