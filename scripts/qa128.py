"""用正式 1.2.8 JAR 与单独 QA 模组启动隔离的 Forge 实例。"""
from pathlib import Path
import argparse, gzip, json, shutil, subprocess, sys, time

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parents[1]
args = argparse.ArgumentParser()
args.add_argument('case', choices=['minimal','quark','screening','ui','original','enhance','enhance-sync','enhance159','enhance159-sync','edited','edited-fallback','custom'])
args.add_argument('--compass', type=Path)
args.add_argument('--magnet', type=Path, default=root/'dist/chestmagnet-forge-1.20.1-1.2.8.jar')
args.add_argument('--profile', action='store_true', help='在独立测试目录记录 JFR')
args.add_argument('--scale', type=int, default=2)
args.add_argument('--pack', type=Path, default=root.parent/'涟漪之'/'.minecraft')
args.add_argument('--quark-pack', type=Path, default=root.parent/'登神者'/'.minecraft')
args = args.parse_args()

def unique(path, glob):
    values = list(path.glob(glob))
    if len(values) != 1:
        raise RuntimeError(f'Expected one {glob} in {path}: {values}')
    return values[0]

case = root/'run'/f'qa128-{args.case}-{time.time_ns()}'
mods = case/'mods'
mods.mkdir(parents=True)
def mod(src):
    shutil.copy2(src, mods/src.name)

mod(args.magnet.resolve())
mod(root/'run/qa124-artifacts/rsqa124.jar')
jvm = ['-Xmx3G', '-Dchestmagnet.qa128='+ ('enhance' if args.case.startswith('enhance') else args.case)]
if args.profile:
    jvm.append('-XX:StartFlightRecording=filename=scan.jfr,settings=profile,dumponexit=true')
if args.case.startswith('enhance159'):
    jvm.append('-Dchestmagnet.qa128Enhance159=true')
if args.case in ('minimal','quark','screening'):
    server = root/'run/qa127-minimal-server'
    shutil.copytree(server/'libraries', case/'libraries')
    (case/'eula.txt').write_text('eula=true\n')
    (case/'server.properties').write_text('level-name=world\nserver-port=0\nsimulation-distance=4\nview-distance=5\n')
    if args.case == 'quark':
        pack = unique(args.quark_pack/'versions', '*1.6.2*')
        for pattern in ('*Quark-4.0-462.jar', 'Zeta-1.0-30.jar', '*lootr*0.7.35.93.jar'):
            mod(unique(pack/'mods', pattern))
        (case/'config').mkdir()
        shutil.copy2(pack/'config/quark-common.toml', case/'config/quark-common.toml')
    launch = [*jvm, '@libraries/net/minecraftforge/forge/1.20.1-47.2.18/win_args.txt', 'nogui']
else:
    content = unique(args.pack/'versions', '*如涟漪之所见')
    manifest = json.loads((content/(content.name+'.json')).read_text(encoding='utf-8'))
    if args.case == 'ui':
        mod(root/'libs/curios-5.14.1.jar')
        for pattern in ('*Enigmaticlegacy-2.30.1-1.jar','*enigmaticaddons-1.2.5.jar','*Patchouli-1.20.1-84.1-FORGE.jar','caelus-forge-3.2.0*'):
            mod(unique(content/'mods', pattern))
    else:
        mod(args.compass or root/'libs/explorer-1.3.4.jar')
    if args.case.endswith('-sync'):
        jvm.append('-Dchestmagnet.qa128Sync=true')
    dst = case/'saves/Qa128'
    shutil.copytree(root/'run/qa124-minimal-47.4.10/world', dst, ignore=shutil.ignore_patterns('session.lock'))
    if args.case == 'ui':
        datapack = dst/'datapacks/qa128-tag'
        items = datapack/'data/curios/tags/items'
        items.mkdir(parents=True)
        (datapack/'pack.mcmeta').write_text('{"pack":{"pack_format":15,"description":"QA128 tag-only accessory"}}')
        (items/'qa128_unregistered_slot.json').write_text('{"values":["minecraft:paper"]}')
    leveldat = dst/'level.dat'
    data = gzip.decompress(leveldat.read_bytes())
    key = b'\x01\x00\x1dconfirmedExperimentalSettings'
    if key in data:
        offset = data.index(key) + len(key)
        leveldat.write_bytes(gzip.compress(data[:offset] + b'\x01' + data[offset+1:]))
    (case/'natives').mkdir()
    (case/'options.txt').write_text(f'lang:zh_cn\nguiScale:{args.scale}\npauseOnLostFocus:false\nrenderDistance:5\nsimulationDistance:4\nmaxFps:90\n',encoding='utf-8')
    cp=[]
    for lib in manifest['libraries']:
        path=lib.get('downloads',{}).get('artifact',{}).get('path')
        if path and (args.pack/'libraries'/path).exists() and not any(x in path for x in ('natives-linux','natives-macos','linux-aarch','linux-x86','java-objc')):
            cp.append(str(args.pack/'libraries'/path))
    cp.append(str(content/(content.name+'.jar')))
    subs = {'natives_directory':str(case/'natives'),'launcher_name':'qa128','launcher_version':'1',
            'classpath':';'.join(cp),'version_name':content.name,'library_directory':str(args.pack/'libraries'),
            'classpath_separator':';','auth_player_name':'MagnetTest','game_directory':str(case),
            'assets_root':str(args.pack/'assets'),'assets_index_name':'5','auth_uuid':'a943f925d5cb4c28b125ce7369704878',
            'auth_access_token':'0','clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release'}
    def expand(text):
        for key,value in subs.items(): text=text.replace('${'+key+'}',value)
        return text
    for value in manifest['arguments']['jvm']:
        if isinstance(value,dict):
            if not all(rule.get('os',{}).get('name','windows')=='windows' and rule.get('os',{}).get('arch','x86_64')!='x86' for rule in value['rules']):continue
            value=value['value']; jvm.extend(map(expand,value if isinstance(value,list) else [value]))
        else: jvm.append(expand(value))
    jvm.extend([manifest['mainClass'], *(expand(v) for v in manifest['arguments']['game'] if isinstance(v,str)),
                '--width','1100','--height','760','--quickPlaySingleplayer','Qa128'])
    argfile = case/'args.txt'
    argfile.write_text('\n'.join('"'+s.replace('\\','\\\\').replace('"','\\"')+'"' for s in jvm), encoding='gb18030')
    launch = ['@'+str(argfile)]
print(case, flush=True)
java = Path('D:/Java/JDK/JDK17/bin/java.exe')
with (case/'console.log').open('w',encoding='utf-8') as log:
    stdin = subprocess.PIPE if args.case in ('minimal','quark','screening') else None
    proc = subprocess.Popen([str(java), *launch],cwd=case,stdout=log,stderr=subprocess.STDOUT,stdin=stdin)
    try:
        proc.wait(timeout=150)
    except subprocess.TimeoutExpired:
        if stdin and proc.poll() is None:
            proc.stdin.write(b'stop\n')
            proc.stdin.flush()
            try: proc.wait(timeout=10)
            except subprocess.TimeoutExpired: pass
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=20)
text=(case/'console.log').read_text(encoding='utf-8',errors='replace')
print('\n'.join(line for line in text.splitlines() if 'QA128' in line or 'Mixin apply failed' in line))
marker = {'minimal':'QA128_PASS minimal','quark':'QA128_PASS quark','screening':'QA128_PASS screening','ui':'QA128_UI_PASS'}.get(args.case,'QA128_COMPASS_PASS')
complete = marker in text and 'QA128_FAILURE' not in text and proc.returncode == 0
if args.case.startswith('enhance'):
    complete = complete and 'QA128_ENHANCE_NEGATIVES_PASS' in text
if args.case.startswith('enhance159'):
    complete = complete and 'QA128_ENHANCE159_LIFECYCLE_PASS' in text
if args.case == 'edited-fallback':
    complete = complete and 'QA128_EDITED_FALLBACK_PASS' in text
sys.exit(0 if complete else 1)
