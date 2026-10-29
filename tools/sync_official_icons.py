#!/usr/bin/env python3
"""Import the pinned official app's icon geometry and file palette as Android vectors.

Uses only Python stdlib plus Node 24+ (to run the official pure TypeScript colour
function). No Android runtime SVG parser or icon font is needed. Run from repo root.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

LUCIDE = {
    'Plus':'plus', 'Sparkles':'sparkles', 'Check':'check', 'CircleCheck':'circle-check',
    'PanelRight':'panel-right', 'X':'x', 'Monitor':'monitor', 'ClipboardPaste':'clipboard-paste',
    'Copy':'copy', 'Trash2':'trash', 'Server':'server', 'Pencil':'pencil', 'Ellipsis':'ellipsis',
    'SquareTerminal':'square-terminal', 'ChevronsUpDown':'chevrons-up-down', 'Eye':'eye', 'EyeOff':'eye-off',
    'ArrowDown':'arrow-down', 'Brain':'brain', 'Circle':'circle', 'Shield':'shield', 'ChevronUp':'chevron-up',
    'ChevronDown':'chevron-down', 'FolderOpen':'folder-open', 'Folder':'folder', 'ArrowLeft':'arrow-left',
    'ArrowRight':'arrow-right', 'FileText':'file-text', 'File':'file', 'RefreshCw':'refresh-cw',
    'CornerDownLeft':'corner-down-left', 'FolderGit2':'folder-git-2', 'ChevronRight':'chevron-right',
    'FolderPlus':'folder-plus', 'Pin':'pin', 'Search':'search', 'Settings':'settings', 'Sun':'sun', 'Moon':'moon',
    'Square':'square', 'ArrowUp':'arrow-up', 'QrCode':'qr-code', 'Link':'link', 'Link2':'link-2',
    'ExternalLink':'external-link', 'SquarePlus':'square-plus', 'SquareMinus':'square-minus', 'SquareDot':'square-dot',
    'CircleDot':'circle-dot', 'GitPullRequest':'git-pull-request', 'MessageSquareCode':'message-square-code', 'Summary':'summary', 'Paperclip':'paperclip', 'ImagePlus':'image-plus', 'Maximize2':'maximize-2', 'Minimize2':'minimize-2',
    'Undo2':'undo-2', 'MessageSquare':'message-square', 'Layers':'layers', 'ListEnd':'list-end', 'Mic':'mic', 'Volume2':'volume-2', 'Pause':'pause', 'Play':'play',
    'Bot':'bot', 'Wrench':'wrench', 'MicVocal':'mic-vocal', 'ListTodo':'list-todo',
}
BRANDS = ['antigravity','claude','codex','copilot','kiro','minimax','omp','opencode','pi']
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
ET.register_namespace('android', ANDROID)
ET.register_namespace('aapt', AAPT)
def attrs(**values): return {f'{{{ANDROID}}}{key}':str(value) for key,value in values.items()}
def vector(): return ET.Element('vector', attrs(width='24dp',height='24dp',viewportWidth=24,viewportHeight=24))
def path(parent, d, **values): return ET.SubElement(parent,'path',attrs(pathData=d,**values))
def save(root, target):
    ET.indent(root,space='    ')
    target.write_text('<!-- Imported from the pinned official Paseo app. See NOTICE and tools/sync_official_icons.py. -->\n'+ET.tostring(root,encoding='unicode')+'\n')
def geometry(tag,a):
    if tag=='path':return a['d']
    if tag=='line':return f'M{a["x1"]},{a["y1"]}L{a["x2"]},{a["y2"]}'
    if tag in ('polyline','polygon'):return 'M'+re.sub(r'\s+','L',a['points'].strip())+('Z' if tag=='polygon' else '')
    if tag=='circle':
        x,y,r=map(float,[a['cx'],a['cy'],a['r']]);return f'M{x-r},{y}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0'
    if tag=='rect':
        x,y,w,h=map(float,[a.get('x',0),a.get('y',0),a['width'],a['height']]);rx=min(float(a.get('rx',0)),w/2);ry=min(float(a.get('ry',rx)),h/2)
        return f'M{x+rx},{y}H{x+w-rx}A{rx},{ry} 0 0 1 {x+w},{y+ry}V{y+h-ry}A{rx},{ry} 0 0 1 {x+w-rx},{y+h}H{x+rx}A{rx},{ry} 0 0 1 {x},{y+h-ry}V{y+ry}A{rx},{ry} 0 0 1 {x+rx},{y}Z' if rx else f'M{x},{y}h{w}v{h}h{-w}Z'
    raise ValueError('Unsupported SVG shape: '+tag)
def normalized_group(root,box):
    x,y,w,h=map(float,box.split());scale=24/max(w,h)
    return ET.SubElement(root,'group',attrs(scaleX=scale,scaleY=scale,translateX=(24-w*scale)/2-x*scale,translateY=(24-h*scale)/2-y*scale))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--paseo',type=Path,default=Path('../paseo'))
    parser.add_argument('--lucide-dir',type=Path)
    args=parser.parse_args();source=args.paseo/'packages/app/src';out=Path('app/src/main/res/drawable')
    if args.lucide_dir: lucide=args.lucide_dir
    else:
        entry=json.loads((args.paseo/'package-lock.json').read_text())['packages']['packages/app/node_modules/lucide-react-native']
        data=urllib.request.urlopen(entry['resolved'],timeout=30).read()
        assert base64.b64encode(hashlib.sha512(data).digest()).decode()==entry['integrity'].split('-',1)[1]
        lucide=Path(tempfile.mkdtemp(prefix='paseo-lucide-'))
        with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
            for member in archive.getmembers():
                if member.isfile() and (member.name.startswith('package/dist/cjs/icons/') or member.name=='package/LICENSE'):
                    target=lucide/Path(member.name).relative_to('package');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.extractfile(member).read())
    declarations={}
    for key,name in LUCIDE.items():
        text=(lucide/'dist/cjs/icons'/f'{name}.js').read_text()
        node=json.JSONDecoder().raw_decode(re.sub(r'([a-zA-Z][a-zA-Z0-9]*):',r'"\1":',text.split('node: ',1)[1]))[0]
        root=vector()
        for tag,a in node:path(root,geometry(tag,a),fillColor='@android:color/transparent',strokeColor='#FF000000',strokeWidth=2,strokeLineCap='round',strokeLineJoin='round')
        name='lucide_'+name.replace('-','_');save(root,out/(name+'.xml'));declarations[key]=name
    root=ET.parse(out/'lucide_square.xml').getroot()
    for p in root.findall('path'):p.set(f'{{{ANDROID}}}fillColor','#FF000000')
    save(root,out/'lucide_square_filled.xml');declarations['SquareFilled']='lucide_square_filled'
    root=ET.parse(out/'lucide_panel_right.xml').getroot()
    for p in root.findall('path'):p.set(f'{{{ANDROID}}}strokeWidth','1.5')
    save(root,out/'paseo_explorer_toggle.xml');declarations['Explorer']='paseo_explorer_toggle'
    root=vector();root.set(f'{{{ANDROID}}}viewportWidth','16');root.set(f'{{{ANDROID}}}viewportHeight','12')
    root.set(f'{{{ANDROID}}}width','16dp');root.set(f'{{{ANDROID}}}height','12dp')
    for y,w in [(0,16),(5.25,16),(10.5,8)]:path(root,geometry('rect',dict(x=0,y=y,width=w,height=1.5,rx=.75)),fillColor='#FF000000')
    save(root,out/'paseo_mobile_menu.xml');declarations['MobileMenu']='paseo_mobile_menu'
    root=vector()
    for d in [geometry('rect',dict(x=3,y=3,width=18,height=18,rx=2)),'M9,9.5H15','M12,6.5V12.5','M9,16H15']:
        path(root,d,fillColor='@android:color/transparent',strokeColor='#FF000000',strokeWidth=2,strokeLineCap='round',strokeLineJoin='round')
    save(root,out/'paseo_source_control.xml');declarations['SourceControl']='paseo_source_control'
    for brand in BRANDS:
        text=(source/'components/icons'/f'{brand}-icon.tsx').read_text();root=vector()
        group=normalized_group(root,re.search(r'viewBox="([^"]+)"',text).group(1))
        for part in re.findall(r'<Path\b(.*?)/>',text,re.S):
            a=dict(re.findall(r'(\w+)="([^"]*)"',part));parent=group
            if 'transform' in a:
                assert a['transform']=='translate(12 12) scale(0.24) translate(-55.738 -56.945)',a['transform']
                parent=ET.SubElement(group,'group',attrs(scaleX=.24,scaleY=.24,translateX=12-.24*55.738,translateY=12-.24*56.945))
            opacity=re.search(r'opacity=\{([\d.]+)\}',part)
            path(parent,a['d'],fillColor='#FF000000',fillAlpha=opacity.group(1) if opacity else 1,fillType='evenOdd' if 'fillRule="evenodd"' in text else 'nonZero')
        save(root,out/f'provider_{brand}.xml');declarations['Provider'+brand.title()]='provider_'+brand
    text=(source/'components/material-file-icons.ts').read_text()
    icons=dict(re.findall(r'\s+(\w+): `(<svg.*?</svg>)`',text,re.S))
    # Reuse the official OKLab implementation, including its rounding, rather than reimplementing it.
    js='import {desaturateHexColor} from '+json.dumps((source/'utils/color.ts').resolve().as_uri())+';let s="";for await(const c of process.stdin)s+=c;const icons=JSON.parse(s);for(const k in icons)icons[k]=icons[k].replace(/#[0-9a-fA-F]+/g,h=>desaturateHexColor(h,.65));process.stdout.write(JSON.stringify(icons));'
    icons=json.loads(subprocess.run(['node','--input-type=module','-e',js],input=json.dumps(icons),text=True,capture_output=True,check=True).stdout)
    for name,svg in icons.items():
        svg=ET.fromstring(svg);root=vector();parent=normalized_group(root,svg.get('viewBox'))
        gradients={e.get('id'):e for e in svg.iter() if e.tag.endswith('linearGradient')}
        def emit(e,parent,inherited):
            tag=e.tag.split('}')[-1];a={**inherited,**e.attrib}
            if tag in ('defs','linearGradient','stop'):return
            if tag in ('svg','g'):
                for child in e:emit(child,parent,a)
                return
            fill=a.get('fill','#000000');p=path(parent,geometry(tag,a),fillType='evenOdd' if a.get('fill-rule')=='evenodd' else 'nonZero',fillAlpha=a.get('opacity',a.get('fill-opacity',1)))
            if fill.startswith('url('):
                g=gradients[fill[5:-1]];coords=[float(g.get(k,0)) for k in ['x1','y1','x2','y2']]
                if g.get('gradientTransform'):
                    assert g.get('gradientTransform')=='translate(1.306 1.129)scale(.89324)'
                    coords=[coords[i]*.89324+([1.306,1.129][i%2]) for i in range(4)]
                wrapper=ET.SubElement(p,f'{{{AAPT}}}attr',{'name':'android:fillColor'})
                grad=ET.SubElement(wrapper,'gradient',attrs(type='linear',startX=coords[0],startY=coords[1],endX=coords[2],endY=coords[3],tileMode='clamp'))
                for stop in g:ET.SubElement(grad,'item',attrs(color=stop.get('stop-color'),offset=stop.get('offset')))
            else:p.set(f'{{{ANDROID}}}fillColor','@android:color/transparent' if fill=='none' else fill)
        emit(svg,parent,{})
        save(root,out/('filetype_'+name.lstrip('_')+'.xml'))
    extensions=dict(re.findall(r'\s+(\w+): "(\w+)"',text[text.index('const EXTENSION_TO_ICON'):]))
    kt=Path('app/src/main/kotlin/sh/paseo/android/ui/PaseoIcons.kt')
    rows=['package sh.paseo.android.ui','', 'import androidx.annotation.DrawableRes','import sh.paseo.android.R','', '/** Official Lucide 1.50.0 and Paseo custom assets. Generated by tools/sync_official_icons.py. */','object PaseoIcons {']
    rows += [f'    @get:DrawableRes val {key}: Int get() = R.drawable.{name}' for key,name in declarations.items()]
    rows += ['    @get:DrawableRes val Paseo: Int get() = R.drawable.paseo_logo','', '    @DrawableRes fun provider(provider: String): Int = when (provider.lowercase()) {']
    rows += [f'        "{b}" -> Provider{b.title()}' for b in BRANDS]
    rows += ['        else -> Bot','    }','','    @DrawableRes fun file(name: String): Int = when (name.substringAfterLast(\'.\', "").lowercase()) {']
    rows += [f'        "{ext}" -> R.drawable.filetype_{name.lstrip("_")}' for ext,name in extensions.items()]
    rows += ['        else -> R.drawable.filetype_default','    }','}','']
    kt.write_text('\n'.join(rows))
    print(f'Imported {len(LUCIDE)} Lucide icons, 9 brands, 54 file icons and Paseo custom geometry.')

if __name__=='__main__':main()
