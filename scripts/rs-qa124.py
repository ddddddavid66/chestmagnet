"""隔离发行版 RS 客户端验证。仅读取原整合包，在 run 下复制测试世界。"""
from pathlib import Path
import argparse, json, shutil, subprocess, time, re, sys

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--pack', type=Path, required=True, help='包含 versions/libraries/assets 的 .minecraft 目录')
parser.add_argument('--magnet', type=Path, help='省略表示无磁铁；提供正式 JAR 路径')
parser.add_argument('--mode', choices=['off', 'idle', 'active'], default='off')
parser.add_argument('--disk', default='', help='空为普通存储块，否则为磁盘物品注册 ID')
parser.add_argument('--java', type=Path, default=Path(r'D:/Java/JDK/JDK17/bin/java.exe'))
parser.add_argument('--template', type=Path, default=root/'run/qa124-minimal-47.4.10/world')
args = parser.parse_args()
version = next(p for p in (args.pack/'versions').iterdir() if '涟漪之篇' in p.name)
manifest = json.loads((version/(version.name+'.json')).read_text(encoding='utf-8'))
case = root/'run'/('qa124-production-'+str(time.time_ns()))
(case/'mods').mkdir(parents=True)
(case/'natives').mkdir()
shutil.copytree(args.template, case/'saves/RsQa124', ignore=shutil.ignore_patterns('session.lock'))
# 只复制验证链路必需的实际依赖，不改原包。
patterns = ['*refinedstorage-1.12.4.jar', '*refinedstorageaddons-0.10.0.jar',
            'rs_integration-1.5.1.jar', '*curios-forge-5.14.1*.jar', '*RSInfinityBooster*.jar',
            '*jei-1.20.1-forge*.jar', '*ExtraDisks*.jar']
for pattern in patterns:
    matches = list((version/'mods').glob(pattern))
    if len(matches) != 1: raise RuntimeError(f'{pattern}: expected one JAR, got {matches}')
    shutil.copy2(matches[0], case/'mods'/matches[0].name)
if args.magnet: shutil.copy2(args.magnet.resolve(), case/'mods'/args.magnet.name)
shutil.copy2(root/'run/qa124-artifacts/rsqa124.jar', case/'mods/rsqa124.jar')
shutil.copytree(version/'config/rs_integration', case/'config/rs_integration')
(case/'options.txt').write_text('lang:zh_cn\nguiScale:2\npauseOnLostFocus:false\n',encoding='utf-8')
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
jvm=['-Xmx3G','-Dchestmagnet.rsClient=true','-Dchestmagnet.qaMode='+args.mode,'-Dchestmagnet.qaDisk='+args.disk]
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
print('\n'.join(line for line in text.splitlines() if 'RS_CLIENT_FINISHED' in line or 'RS_CLIENT_SETUP_FAILED' in line))

match = re.search(r'RS_CLIENT_FINISHED opens=20 failures=(\d+)', text)
sys.exit(2 if match is None else (0 if int(match.group(1)) == 0 else 1))
