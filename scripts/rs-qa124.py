"""隔离发行版 RS 客户端验证。仅读取原整合包，在 run 下复制测试世界。"""
from pathlib import Path
import argparse, gzip, json, shutil, subprocess, time, re, sys

sys.stdout.reconfigure(encoding='utf-8')

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--pack', type=Path, required=True, help='包含 versions/libraries/assets 的 .minecraft 目录')
parser.add_argument('--magnet', type=Path, help='省略表示无磁铁；提供正式 JAR 路径')
parser.add_argument('--mode', choices=['off', 'idle', 'active'], default='off')
parser.add_argument('--suite', choices=['rs124', 'bench', 'compat', 'rs125', 'ftb126', 'chapel126'], default='rs124')
parser.add_argument('--ink-probe', action='store_true')
parser.add_argument('--quest127', action='store_true')
parser.add_argument('--gui-scale', type=int, default=2)
parser.add_argument('--ftb-legacy', action='store_true')
parser.add_argument('--ftb-rsi', action='store_true')
parser.add_argument('--chapel-mods', type=Path, help='Read-only directory containing exactly one Goety and Revelation JAR')
parser.add_argument('--legacy-storage', action='store_true')
parser.add_argument('--disk', default='', help='空为普通存储块，否则为磁盘物品注册 ID')
parser.add_argument('--java', type=Path, default=Path(r'D:/Java/JDK/JDK17/bin/java.exe'))
parser.add_argument('--template', type=Path, default=root/'run/qa124-minimal-47.4.10/world')
args = parser.parse_args()
version = next(p for p in (args.pack/'versions').iterdir() if '涟漪之篇' in p.name)
manifest = json.loads((version/(version.name+'.json')).read_text(encoding='utf-8'))
case = root/'run'/(('qa124-production-' if args.suite == 'rs124' else ('qa126-' if args.suite.endswith('126') else 'qa125-')+args.suite+'-')+str(time.time_ns()))
(case/'mods').mkdir(parents=True)
(case/'natives').mkdir()
shutil.copytree(args.template, case/'saves/RsQa124', ignore=shutil.ignore_patterns('session.lock'))
if args.suite != 'rs124':
    # 确认的仅是刚创建的 QA 副本；不修改模板或原整合包。
    level = case/'saves/RsQa124/level.dat'
    nbt = gzip.decompress(level.read_bytes())
    key = b'\x01\x00\x1dconfirmedExperimentalSettings'
    if nbt.count(key) != 1:
        raise RuntimeError('unexpected QA level.dat format')
    at = nbt.index(key) + len(key)
    if nbt[at] not in (0, 1):
        raise RuntimeError('unexpected QA confirmation flag')
    temp = level.with_suffix('.tmp')
    temp.write_bytes(gzip.compress(nbt[:at] + b'\x01' + nbt[at + 1:]))
    temp.replace(level)
# 只复制验证链路必需的实际依赖，不改原包。
patterns = ['*refinedstorage-1.12.4.jar', '*refinedstorageaddons-0.10.0.jar',
            'rs_integration-1.5.1.jar', '*curios-forge-5.14.1*.jar', '*RSInfinityBooster*.jar',
            '*jei-1.20.1-forge*.jar', '*ExtraDisks*.jar']
if args.suite != 'rs124':
    patterns = []
    versions = ('lootr-0.7.31.77.jar','backpacks-3.20.17.1150.jar','core-0.7.8.786.jar') if args.legacy_storage else (
        'lootr-0.7.35.94.jar','backpacks-3.24.35.1675.jar','core-1.3.21.1676.jar')
    for name in (*versions, 'rs-1.12.4.jar','rsaddons-0.10.0.jar','curios-5.14.1.jar','rsbooster-41.jar'):
        shutil.copy2(root/'libs'/name, case/'mods'/name)
if args.suite == 'ftb126':
    for name in ('beyonddimensions-0.8.9.jar', 'compixel-0.1.18.jar', 'architectury-9.2.14-forge.jar',
                 'ftb-teams-forge-2001.3.1.jar', 'ftb-xmod-compat-forge-2.1.3.jar',
                 'ftb-quests-forge-' + ('2001.4.13' if args.ftb_legacy else '2001.4.22') + '.jar',
                 'ftb-library-forge-' + ('2001.2.9' if args.ftb_legacy else '2001.2.12') + '.jar'):
        shutil.copy2(root/'libs'/name, case/'mods'/name)
    if args.ink_probe:
        patterns += ['*irons_spellbooks-1.20.1-3.15.4.jar', '*player-animation-lib-forge*.jar', '*geckolib-forge-1.20.1*.jar']
    if args.quest127:
        for name in ('tetra-6.9.0.jar', 'mutil-6.2.0.jar'):
            shutil.copy2(root/'libs'/name, case/'mods'/name)
        patterns += ['*item-filters-forge-2001.1.0-build.59.jar']
    if args.ftb_rsi:
        patterns += ['rs_integration-1.5.1.jar', '*jei-1.20.1-forge*.jar']
if args.suite == 'chapel126':
    if not args.chapel_mods: parser.error('--chapel-mods required for chapel126')
    for pattern in ('*goety-2.5*.jar', '*Goety*Revelation*.jar'):
        found = list(args.chapel_mods.glob(pattern))
        if len(found) != 1: raise RuntimeError(f'{pattern}: {found}')
        shutil.copy2(found[0], case/'mods'/found[0].name)
    for lib in args.chapel_mods.glob('*EndingLibrary*.jar'):
        shutil.copy2(lib, case/'mods'/lib.name)
    shutil.copy2(root/'libs/explorer-1.3.4.jar', case/'mods/explorer-1.3.4.jar')
for pattern in patterns:
    matches = list((version/'mods').glob(pattern))
    if len(matches) != 1: raise RuntimeError(f'{pattern}: expected one JAR, got {matches}')
    shutil.copy2(matches[0], case/'mods'/matches[0].name)
if args.magnet: shutil.copy2(args.magnet.resolve(), case/'mods'/args.magnet.name)
shutil.copy2(root/'run/qa124-artifacts/rsqa124.jar', case/'mods/rsqa124.jar')
if args.suite == 'rs124':
    shutil.copytree(version/'config/rs_integration', case/'config/rs_integration')
options = f'lang:zh_cn\nguiScale:{args.gui_scale}\npauseOnLostFocus:false\n'
if args.suite != 'rs124':
    options += 'renderDistance:5\nsimulationDistance:5\nenableVsync:false\nmaxFps:120\n'
(case/'options.txt').write_text(options,encoding='utf-8')
cp = []
for lib in manifest['libraries']:
    path = lib.get('downloads',{}).get('artifact',{}).get('path')
    if path and (args.pack/'libraries'/path).exists() and not any(x in path for x in ['natives-linux','natives-macos','linux-aarch','linux-x86','java-objc']):
        cp.append(str(args.pack/'libraries'/path))
cp.append(str(version/(version.name+'.jar')))
subs = {'natives_directory':str(case/'natives'),'launcher_name':'rsqa124','launcher_version':'1',
        'classpath':';'.join(cp),'version_name':version.name,'library_directory':str(args.pack/'libraries'),
        'classpath_separator':';','auth_player_name':'MagnetTest','game_directory':str(case),
        'assets_root':str(args.pack/'assets'),'assets_index_name':'5',
        'auth_uuid':'a943f925d5cb4c28b125ce7369704878','auth_access_token':'0','clientid':'',
        'auth_xuid':'','user_type':'legacy','version_type':'release'}
def expand(text):
    for key,value in subs.items(): text=text.replace('${'+key+'}',value)
    return text
jvm=['-Xmx3G']
if args.ink_probe:
    jvm += ['-Dchestmagnet.inkProbe=true']
if args.quest127:
    jvm += ['-Dchestmagnet.qa127=true']
if args.suite == 'rs124':
    jvm += ['-Dchestmagnet.rsClient=true','-Dchestmagnet.qaMode='+args.mode,'-Dchestmagnet.qaDisk='+args.disk]
else:
    jvm += ['-Dchestmagnet.qa125='+args.suite,'-Dchestmagnet.qaDisk='+args.disk]
for value in manifest['arguments']['jvm']:
    if isinstance(value,dict):
        if not all(rule.get('os',{}).get('name','windows')=='windows' and rule.get('os',{}).get('arch','x86_64')!='x86' for rule in value['rules']):continue
        value=value['value']; jvm.extend(map(expand,value if isinstance(value,list) else [value]))
    else:jvm.append(expand(value))
jvm.append(manifest['mainClass'])
jvm.extend(expand(v) for v in manifest['arguments']['game'] if isinstance(v,str))
jvm += ['--width','1100','--height','760','--quickPlaySingleplayer','RsQa124']
argfile=case/'launch.args'
argfile.write_text('\n'.join('"'+s.replace('\\','\\\\').replace('"','\\"')+'"' for s in jvm),encoding='gb18030')
print(case,flush=True)
with (case/'console.log').open('w',encoding='utf-8') as log:
    process=subprocess.Popen([str(args.java),'@'+str(argfile)],cwd=case,stdout=log,stderr=subprocess.STDOUT)
    try: process.wait(timeout=720)
    except subprocess.TimeoutExpired:
        process.terminate();process.wait();print('INCOMPLETE: client timeout; inspect console.log',flush=True)
    print('exit',process.returncode,flush=True)
text=(case/'console.log').read_text(encoding='utf-8',errors='replace')
if args.suite != 'rs124':
    print('\n'.join(line for line in text.splitlines() if 'QA125_' in line or 'QA126_' in line or 'QA127_' in line))
    marker = {'bench':'QA125_BENCH_PASS', 'compat':'QA125_COMPAT_PASS', 'rs125':'QA125_RS_PASS', 'ftb126':'QA126_FTB_PASS', 'chapel126':'QA126_CHAPEL_PASS'}[args.suite]
    extra = args.suite != 'ftb126' or 'QA126_BD_PASS' in text
    if args.quest127:
        extra = extra and 'QA127_QUEST_PASS' in text and 'QA127_RECENT_UI_PASS' in text
    sys.exit(0 if marker in text and extra and process.returncode == 0 else 1)
print('\n'.join(line for line in text.splitlines() if 'RS_CLIENT_FINISHED' in line or 'RS_CLIENT_SETUP_FAILED' in line))

match = re.search(r'RS_CLIENT_FINISHED opens=20 failures=(\d+)', text)
sys.exit(2 if match is None else (0 if int(match.group(1)) == 0 else 1))
