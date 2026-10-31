#!/usr/bin/env python3
"""Sidebar UI/usage checks against mock_daemon.py --sidebar, on emulator-5554 only."""
import argparse
import json
from pathlib import Path
import re
import android_smoke as ui
from android_theme_smoke import select_theme


def rect(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--reset', action='store_true')
    parser.add_argument('--usage-only', action='store_true')
    parser.add_argument('--unavailable', action='store_true')
    parser.add_argument('--events-file')
    args = parser.parse_args()
    ui.args.adb = args.adb
    ui.output = Path('app/build/sidebar-smoke')
    ui.output.mkdir(parents=True, exist_ok=True)
    if args.reset:
        ui.adb('shell', 'pm', 'clear', 'sh.paseo.android')
    ui.adb('shell', 'am', 'force-stop', 'sh.paseo.android')
    ui.adb('shell', 'am', 'start', '-n', 'sh.paseo.android/.MainActivity')
    if args.reset:
        ui.open_pairing()
        ui.fill(0, 'tcp://10.0.2.2:6768?password=test')
        ui.hide_keyboard()
        ui.submit_pairing()
    ui.node('已连接')
    if not any(n.get('content-desc') == '关闭侧栏' for n in ui.tree().iter('node')):
        ui.click('打开侧栏', 'content-desc')
    if args.unavailable:
        ui.node('暂无可用额度数据')
        assert not any(n.get('text') == '0%' for n in ui.tree().iter('node'))
        ui.click('用量', 'content-desc')
        ui.node('Codex account is not signed in')
        ui.screenshot('usage-unavailable')
        print('Unavailable quota is not zero: passed', flush=True)
        return
    ui.node('76%')
    ui.node('wk')
    if args.usage_only:
        ui.screenshot('sidebar-legacy')
    ui.click('用量', 'content-desc')
    ui.node('76% 已用')
    ui.node('12% 已用')
    ui.click('显示：已用')
    ui.node('24% 剩余')
    ui.node('88% 剩余')
    ui.click('刷新用量', 'content-desc')
    ui.node('24% 剩余')
    ui.screenshot('usage-details')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.node('24%')
    if args.usage_only:
        print('Legacy quota summary, details, remaining switch and refresh: passed', flush=True)
        return
    ui.click('用量', 'content-desc')
    ui.click('显示：剩余')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.node('76%')
    ui.click('Native Android')
    ui.click('打开侧栏', 'content-desc')
    width = int(re.findall(r'(\d+)x\d+', ui.adb('shell', 'wm', 'size').decode())[0])
    close = rect(ui.node('关闭侧栏', 'content-desc'))
    assert close[2] < width * .94, (close, width)
    assert close[2] > width * .65, (close, width)
    status = rect(ui.node('正在运行', 'content-desc'))
    title = rect(ui.node('Native Android'))
    assert status[2] <= title[0], (status, title)
    ui.node('等待输入', 'content-desc')
    ui.click('Native Android 工作区菜单', 'content-desc')
    ui.node('重命名')
    ui.node('固定')
    ui.node('归档')
    ui.adb('shell', 'input', 'keyevent', '4')
    ui.click('paseo 项目菜单', 'content-desc')
    ui.click('折叠项目')
    assert not any(n.get('text') == 'Native Android' for n in ui.tree().iter('node'))
    ui.click('paseo 项目菜单', 'content-desc')
    ui.click('展开项目')
    ui.node('Native Android')
    ui.screenshot('sidebar-current-theme')
    ui.click('历史')
    ui.node('Archived review')
    ui.fill(0, 'Archived')
    ui.hide_keyboard()
    ui.node('Archived review')
    assert not any(n.get('text') == 'Second agent' for n in ui.tree().iter('node'))
    ui.click('Archived review')
    ui.node('Archived review completed.')
    ui.screenshot('history-preview')
    ui.click('关闭历史', 'content-desc')
    ui.open_appearance()
    select_theme('Dark')
    ui.click('返回', 'content-desc')
    ui.click('打开侧栏', 'content-desc')
    ui.node('76%')
    ui.screenshot('sidebar-dark')
    ui.open_appearance()
    select_theme('Light')
    ui.click('返回', 'content-desc')
    ui.click('打开侧栏', 'content-desc')
    ui.node('76%')
    ui.screenshot('sidebar-light')
    ui.open_appearance()
    select_theme('Ghostty')
    ui.click('返回', 'content-desc')
    ui.click('打开侧栏', 'content-desc')
    ui.node('76%')
    ui.screenshot('sidebar-ghostty')
    ui.click('关闭侧栏', 'content-desc')
    ui.node('打开侧栏', 'content-desc')
    if args.events_file:
        messages = [json.loads(line).get('message', {}) for line in Path(args.events_file).read_text().splitlines()]
        assert any(m.get('type') == 'usage.list_reports.request' and m.get('forceRefresh') is True for m in messages)
        assert any(m.get('type') == 'fetch_agent_history_request' and m.get('search') == 'Archived' for m in messages)
    print('Width preserved, status placement, menus, history, themes, streamed quota and refresh: passed', flush=True)


if __name__ == '__main__':
    main()
