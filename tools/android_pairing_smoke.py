#!/usr/bin/env python3
"""Verify link-only pairing on a disposable emulator against the official relay crypto fixture."""
import argparse
import json
from pathlib import Path
import time
import android_smoke as ui

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb',default='adb')
parser.add_argument('--fixture',default='/tmp/paseo-relay-fixture.json')
parser.add_argument('--output',default='app/build/smoke-pairing')
args=parser.parse_args()
ui.args.adb=args.adb
ui.output=Path(args.output)
ui.output.mkdir(parents=True,exist_ok=True)
link=json.loads(Path(args.fixture).read_text())['androidLinks']['password']

def fields():
    return [n for n in ui.tree().iter('node') if n.attrib.get('class')=='android.widget.EditText']

def wait_password():
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        nodes=list(ui.tree().iter('node'))
        allow=next((n for n in nodes if n.attrib.get('resource-id','').endswith('/permission_allow_button')),None)
        if allow is not None:
            ui.tap(allow)
        elif any(n.attrib.get('text')=='主机需要密码' for n in nodes):
            return
        time.sleep(.2)
    raise AssertionError('Expected an encrypted password challenge')

ui.adb('shell','pm','clear','sh.paseo.android')
ui.adb('shell','am','start','-n','sh.paseo.android/.MainActivity')
ui.open_pairing()
assert len(fields())==1, 'Pairing should require just one link field'
assert not any('主机名称' in n.attrib.get('text','') for n in ui.tree().iter('node'))
ui.fill(0,'https://app.paseo.sh/#offer=bad')
ui.hide_keyboard()
ui.submit_pairing()
ui.node('配对链接无效',contains=True)
ui.screenshot('invalid-offer-not-saved')
ui.click('取消')
assert not any(n.attrib.get('text')=='Relay fixture' for n in ui.tree().iter('node'))
ui.open_pairing()
assert len(fields())==1
ui.fill(0,link)
ui.hide_keyboard()
ui.screenshot('link-only')
ui.submit_pairing()
wait_password()
assert len(fields())==2, 'Only add a password field when requested by the host'
ui.fill(1,'bad')
ui.hide_keyboard()
ui.submit_pairing()
ui.node('主机密码不正确')
ui.screenshot('incorrect-password')
ui.tap(fields()[1])
ui.adb('shell','input','keyevent','123')
ui.adb('shell','input','keyevent','67','67','67')
ui.adb('shell','input','text','test')
ui.hide_keyboard()
ui.submit_pairing()
ui.node('已连接')
ui.node('Relay workspace')
ui.node('Relay fixture')
ui.screenshot('paired-auto-hostname')
ui.click('Relay workspace')
ui.node('工作区已就绪')
ui.click('打开文件和改动','content-desc')
ui.click('文件')
ui.click('README.md')
ui.node('Official relay library connection',contains=True)
ui.screenshot('encrypted-file-request')
ui.adb('shell','am','force-stop','sh.paseo.android')
ui.adb('shell','am','start','-n','sh.paseo.android/.MainActivity')
ui.node('已连接')
ui.node('Relay workspace')
ui.screenshot('restored-relay-host')
info=json.loads(Path(args.fixture).read_text())
assert info['audit']['authorizationHeaders']==0
assert info['audit']['plaintextApplications']==0
print('All Android pairing smoke checks passed.',flush=True)
