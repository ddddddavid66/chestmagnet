"""用正式 1.2.8 JAR 与单独 QA 模组启动隔离的 Forge 实例。"""
from pathlib import Path
import argparse, gzip, json, shutil, subprocess, sys, time, zipfile, tomllib

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parents[1]
args = argparse.ArgumentParser()
repair = ('lootr', 'legacy', 'mirage', 'pets', 'pets-baseline', 'repair-screen', 'ancient', 'ancient-baseline', 'boss', 'buried', 'buried-baseline', 'buried-off', 'crypt', 'aether-boss', 'structure-protection', 'buried-original', 'lootr-structure')
args.add_argument('case', choices=['minimal','quark','screening','policy-screening','ui','track-current','track-legacy','track-minimal','original','enhance','enhance-sync','enhance159','enhance159-sync','edited','edited-fallback','custom','compass-lootr', *repair])
args.add_argument('--compass', type=Path)
args.add_argument('--qa', type=Path, default=root/'run/qa124-artifacts/rsqa124.jar')
args.add_argument('--magnet', type=Path, default=root/'dist/chestmagnet-forge-1.20.1-1.2.8.jar')
args.add_argument('--lootr', type=Path, help='为远古城市回归指定实装的 Lootr JAR')
args.add_argument('--boss-jar', type=Path)
args.add_argument('--original-scale', action='store_true')
args.add_argument('--template-world', type=Path)
args.add_argument('--player-name', default='MagnetTest')
args.add_argument('--player-uuid', default='a943f925d5cb4c28b125ce7369704878')
args.add_argument('--full-pack', action='store_true', help='buried only: isolated copy of all installed mods/config/KubeJS')
args.add_argument('--profile', action='store_true', help='在独立测试目录记录 JFR')
args.add_argument('--scale', type=int, default=2)
args.add_argument('--pack', type=Path, default=root.parent/'涟漪之'/'.minecraft')
args.add_argument('--quark-pack', type=Path, default=root.parent/'登神者'/'.minecraft')
args = args.parse_args()
if args.lootr and args.case not in ('ancient', 'ancient-baseline', 'buried', 'buried-baseline', 'buried-off', 'lootr-structure'):
    args.error('--lootr is only supported for the Ancient City comparison')

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
mod(args.qa.resolve())
property = ('-Dchestmagnet.repair128='+args.case if args.case in repair
            else '-Dchestmagnet.qa128='+ ('enhance' if args.case.startswith('enhance') else args.case))
jvm = ['-Xmx3G', property]
if args.case.startswith('track-'):
    jvm = ['-Xmx3G', '-Dchestmagnet.trackQa='+args.case.removeprefix('track-')]
if args.case == 'policy-screening':
    jvm = ['-Xmx2G', '-Dchestmagnet.policyScreen=true']
if args.original_scale: jvm.append('-Dchestmagnet.originalScale=true')
if args.profile:
    jvm.append('-XX:StartFlightRecording=filename=scan.jfr,settings=profile,dumponexit=true')
if args.case.startswith('enhance159'):
    jvm.append('-Dchestmagnet.qa128Enhance159=true')
dedicated = not args.full_pack and args.case in ('minimal','quark','screening','policy-screening',*repair)
if dedicated:
    server = root/('run/production-121' if args.case == 'mirage' else 'run/qa127-minimal-server')
    shutil.copytree(server/'libraries', case/'libraries')
    (case/'eula.txt').write_text('eula=true\n')
    (case/'server.properties').write_text('level-name=world\nlevel-seed=81933858\nserver-port=0\nsimulation-distance=4\nview-distance=5\n')
    if args.case == 'quark':
        pack = unique(args.quark_pack/'versions', '*1.6.2*')
        for pattern in ('*Quark-4.0-462.jar', 'Zeta-1.0-30.jar', '*lootr*0.7.35.93.jar'):
            mod(unique(pack/'mods', pattern))
        (case/'config').mkdir()
        shutil.copy2(pack/'config/quark-common.toml', case/'config/quark-common.toml')
    elif args.case in repair:
        pack = unique(args.quark_pack/'versions', '*1.6.2*')/'mods'
        patterns = {
            'lootr': ('*lootr*0.7.35.93.jar',),
            'legacy': (),
            'mirage': ('*lootr*0.7.35.93.jar','*goetyawaken-1.3.8.jar','*goety-2.5.56.0.jar',
                       'curios-forge-5.14.1*','*Patchouli-1.20.1-85-FORGE.jar'),
            'pets': ('*inventorypets-1.20.1-2.2.8.jar','curios-forge-5.14.1*'),
            'pets-baseline': ('*inventorypets-1.20.1-2.2.8.jar','curios-forge-5.14.1*'),
            'repair-screen': (),
            'ancient': ('*lootr*0.7.35.93.jar',),
            'ancient-baseline': ('*lootr*0.7.35.93.jar',),
            'buried': ('*lootr*0.7.35.93.jar',),
            'buried-baseline': ('*lootr*0.7.35.93.jar',),
            'buried-off': ('*lootr*0.7.35.93.jar',),
            'aether-boss': ('*aether-1.20.1-1.5.2-neoforge.jar', 'curios-forge-5.14.1*'),
            'lootr-structure': ('*lootr*0.7.35.93.jar',),
            'structure-protection': ('*goety-2.5.56.0.jar', 'curios-forge-5.14.1*', '*Patchouli-1.20.1-85-FORGE.jar'),
            'crypt': ('*goety-2.5.56.0.jar', 'curios-forge-5.14.1*', '*Patchouli-1.20.1-85-FORGE.jar'),
            'boss': ('*goety-2.5.56.0.jar', 'curios-forge-5.14.1*', '*Patchouli-1.20.1-85-FORGE.jar'),
        }
        for pattern in patterns[args.case]:
            mod(args.boss_jar if args.boss_jar and pattern.startswith('*goety-') else args.lootr if args.lootr and pattern.startswith('*lootr') else unique(pack, pattern))
        if args.case == 'legacy':
            mod(root/'libs/lootr-0.7.31.77.jar')
    forge = '47.4.16' if args.case == 'mirage' else '47.2.18'
    launch = [*jvm, f'@libraries/net/minecraftforge/forge/1.20.1-{forge}/win_args.txt', 'nogui']
else:
    content = unique(args.pack/'versions', '*如涟漪之所见')
    manifest = json.loads((content/(content.name+'.json')).read_text(encoding='utf-8'))
    if args.full_pack:
        if not args.case.startswith('buried'): args.error('--full-pack only supports buried scenarios')
        for jar in (content/'mods').glob('*.jar'):
            with zipfile.ZipFile(jar) as z:
                info = tomllib.loads(z.read('META-INF/mods.toml').decode('utf8')) if 'META-INF/mods.toml' in z.namelist() else {}
            if any(m.get('modId') in ('chestmagnet','rsqa124') for m in info.get('mods', [])): continue
            mod(jar)
        for directory in ('config','defaultconfigs','kubejs'):
            if (content/directory).exists(): shutil.copytree(content/directory,case/directory)
        jvm[0]='-Xmx8G'
    elif args.case.startswith('track-'):
        from shutil import copy2
        if args.case != 'track-minimal':
            for jar in ('curios-5.14.1.jar', 'tetra-6.9.0.jar', 'mutil-6.2.0.jar'):
                mod(root/'libs'/jar)
            if args.case == 'track-current':
                for jar in ('backpacks-3.24.35.1675.jar', 'core-1.3.21.1676.jar', 'qajei-15.jar',
                            'rs-1.12.4.jar', 'beyonddimensions-0.8.9.jar', 'compixel-0.1.18.jar'):
                    mod(root/'libs'/jar)
            else:
                for jar in ('backpacks-3.20.17.1150.jar', 'core-0.7.8.786.jar'):
                    mod(root/'libs'/jar)
    elif args.case == 'ui':
        mod(root/'libs/curios-5.14.1.jar')
        for pattern in ('*Enigmaticlegacy-2.30.1-1.jar','*enigmaticaddons-1.2.5.jar','*Patchouli-1.20.1-84.1-FORGE.jar','caelus-forge-3.2.0*'):
            mod(unique(content/'mods', pattern))
        if args.qa.name == 'repair128-qa.jar':
            for pattern in ('*EnchantmentDescriptions-Forge-1.20.1-17.1.21.jar','Bookshelf-Forge-1.20.1-20.2.13.jar'):
                mod(unique(content/'mods', pattern))
            (case/'config').mkdir()
            (case/'config/enchdesc.json').write_text(json.dumps({
                'enableMod': True, 'requireKeybindPress': True, 'onlyDisplayOnBooks': True
            }), encoding='utf8')
    else:
        mod(args.compass or root/'libs/explorer-1.3.4.jar')
        if args.case == 'compass-lootr': mod(root/'libs/lootr-0.7.35.94.jar')
    if args.case.endswith('-sync'):
        jvm.append('-Dchestmagnet.qa128Sync=true')
    dst = case/'saves/Qa128'
    shutil.copytree(args.template_world or root/'run/qa124-minimal-47.4.10/world', dst, ignore=shutil.ignore_patterns('session.lock'))
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
            'classpath_separator':';','auth_player_name':args.player_name,'game_directory':str(case),
            'assets_root':str(args.pack/'assets'),'assets_index_name':'5','auth_uuid':args.player_uuid.replace('-',''),
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
    stdin = subprocess.PIPE if dedicated else None
    proc = subprocess.Popen([str(java), *launch],cwd=case,stdout=log,stderr=subprocess.STDOUT,stdin=stdin)
    try:
        proc.wait(timeout=900 if args.full_pack else 420 if args.case in ('mirage', 'pets', 'pets-baseline', 'ancient', 'ancient-baseline', 'buried', 'buried-baseline', 'buried-off', 'crypt', 'aether-boss', 'structure-protection', 'buried-original') else 150)
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
print('\n'.join(line for line in text.splitlines() if 'QA128' in line or 'REPAIR128' in line or 'Mixin apply failed' in line or 'TRACK_' in line))
marker = ('REPAIR128_PASS '+args.case if args.case in repair else
          {'minimal':'QA128_PASS minimal','quark':'QA128_PASS quark','screening':'QA128_PASS screening',
           'policy-screening':'QA128_POLICY_PASS','ui':'QA128_UI_PASS'}.get(args.case,'QA128_COMPASS_PASS'))
if args.case.startswith('track-'): marker = 'TRACK_CLIENT_PASS '+args.case.removeprefix('track-')
complete = marker in text and 'QA128_FAILURE' not in text and proc.returncode == 0
if args.case == 'track-minimal' and complete:
    reopen = case/'reopen-args.txt'
    reopen.write_text(argfile.read_text(encoding='gb18030').replace('-Dchestmagnet.trackQa=minimal', '-Dchestmagnet.trackQa=reopen'), encoding='gb18030')
    with (case/'reopen.log').open('w',encoding='utf8') as log:
        result = subprocess.run([str(java),'@'+str(reopen)],cwd=case,stdout=log,stderr=subprocess.STDOUT,timeout=150)
    complete = result.returncode == 0 and 'TRACK_REOPEN_PASS' in (case/'reopen.log').read_text(encoding='utf8',errors='replace')
if args.case.startswith('buried') and complete and dedicated:
    reopen = [s.replace('-Dchestmagnet.repair128='+args.case,'-Dchestmagnet.repair128=buried-reopen') for s in launch]
    with (case/'reopen.log').open('w',encoding='utf8') as log:
        result = subprocess.run([str(java),*reopen],cwd=case,stdout=log,stderr=subprocess.STDOUT,timeout=150)
    complete = result.returncode == 0 and 'BURIED_REOPEN_PASS' in (case/'reopen.log').read_text(encoding='utf8',errors='replace')
if args.case == 'crypt' and complete:
    reopen = [s.replace('-Dchestmagnet.repair128=crypt','-Dchestmagnet.repair128=crypt-reopen') for s in launch]
    with (case/'reopen.log').open('w',encoding='utf8') as log:
        result = subprocess.run([str(java),*reopen],cwd=case,stdout=log,stderr=subprocess.STDOUT,timeout=150)
    complete = result.returncode == 0 and 'CRYPT_REOPEN_PASS' in (case/'reopen.log').read_text(encoding='utf8',errors='replace')
if args.case == 'pets' and complete:
    reopen = [s.replace('-Dchestmagnet.repair128=pets','-Dchestmagnet.repair128=pets-reopen') for s in launch]
    with (case/'reopen.log').open('w', encoding='utf8') as log:
        reloaded = subprocess.run([str(java), *reopen], cwd=case, stdout=log, stderr=subprocess.STDOUT, timeout=150)
    result = (case/'reopen.log').read_text(encoding='utf8', errors='replace')
    print('\n'.join(line for line in result.splitlines() if 'REPAIR128' in line))
    complete = 'REPAIR128_PASS pets-reopen' in result and reloaded.returncode == 0
if args.case.startswith('enhance'):
    complete = complete and 'QA128_ENHANCE_NEGATIVES_PASS' in text
if args.case.startswith('enhance159'):
    complete = complete and 'QA128_ENHANCE159_LIFECYCLE_PASS' in text
if args.case == 'edited-fallback':
    complete = complete and 'QA128_EDITED_FALLBACK_PASS' in text
sys.exit(0 if complete else 1)
