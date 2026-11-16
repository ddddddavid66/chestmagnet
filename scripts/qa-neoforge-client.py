"""Run production client and its UI smoke test with a separate Mojang/NeoForge installation."""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def allowed(rules, features):
    if not rules:
        return True
    value = False
    for rule in rules:
        system = rule.get('os', {})
        if system.get('name', 'windows') != 'windows':
            continue
        if 'arch' in system and system['arch'] != 'x86_64':
            continue
        if 'version' in system and not re.search(system['version'], platform.version()):
            continue
        if any(features.get(key, False) != value for key, value in rule.get('features', {}).items()):
            continue
        value = rule['action'] == 'allow'
    return value


def sha1(path):
    return hashlib.sha1(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, default=ROOT / 'run/neoforge-client-launcher')
    parser.add_argument('--assets', type=Path, default=Path.home() / '.gradle/caches/neoformruntime/assets')
    parser.add_argument('--java', type=Path, default=Path('D:/Java/JDK/JDK21/bin/java.exe'))
    parser.add_argument('--world', type=Path, required=True, help='an isolated QA world produced by qa-neoforge.py')
    parser.add_argument('--scale', type=int, choices=(2, 3), default=2)
    parser.add_argument('--neoforge', default='21.1.230')
    parser.add_argument('--mods', nargs='*', default=[])
    args = parser.parse_args()
    home = args.launcher.resolve()
    vanilla = json.loads((home / 'versions/1.21.1/1.21.1.json').read_text(encoding='utf-8'))
    ver = 'neoforge-' + args.neoforge
    neo = json.loads((home / f'versions/{ver}/{ver}.json').read_text(encoding='utf-8'))
    assets = args.assets.resolve()
    asset_index = vanilla['assetIndex']['id']
    if not (assets / 'indexes' / f'{asset_index}.json').is_file():
        raise RuntimeError('Run gradlew downloadAssets before launching the client')
    runroot = ROOT / 'run/neoforge-qa'
    if not args.world.resolve().is_relative_to(runroot):
        raise RuntimeError('Only QA fixture worlds are allowed')
    case = runroot / f'client-{args.scale}-{time.time_ns()}'
    case.mkdir(parents=True)
    print(case, flush=True)
    (case / 'mods').mkdir()
    (case / 'natives').mkdir()
    shutil.copytree(args.world, case / 'saves/QaClient', ignore=shutil.ignore_patterns('session.lock'))
    shutil.copy2(ROOT / 'build/libs/chestmagnet-neoforge-1.21.1-1.2.8.jar', case / 'mods')
    shutil.copy2(ROOT / 'build/qa/chestmagnet-neoforge-qa.jar', case / 'mods')
    pins = {p['name']: p for p in json.loads((ROOT / 'docs/neoforge-upstream-lock.json').read_text(encoding='utf-8'))['dependencies']}
    for name in args.mods:
        jar = ROOT / f'libs/neoforge/{name}.jar'
        if hashlib.sha256(jar.read_bytes()).hexdigest() != pins[name]['sha256']:
            raise RuntimeError(f'Wrong SHA-256 for {name}')
        shutil.copy2(jar, case / 'mods')
    (case / 'options.txt').write_text(f'lang:zh_cn\nguiScale:{args.scale}\nrenderDistance:4\nsimulationDistance:5\n'
                                    'maxFps:45\npauseOnLostFocus:false\n', encoding='utf-8')
    feat = {'has_custom_resolution': True, 'has_quick_plays_support': True, 'is_quick_play_singleplayer': True}
    libs = home / 'libraries'
    modules = {}
    for lib in (*vanilla['libraries'], *neo['libraries']):
        if not allowed(lib.get('rules'), {}):
            continue
        artifact = lib.get('downloads', {}).get('artifact')
        if not artifact:
            continue
        name = lib['name'].split(':')
        modules[(name[0], name[1], *name[3:])] = artifact
    cp = []
    for artifact in modules.values():
        path = libs / artifact['path']
        if not path.exists() or (artifact.get('sha1') and sha1(path) != artifact['sha1']):
            path.parent.mkdir(parents=True, exist_ok=True)
            if not artifact.get('url'):
                raise RuntimeError(f'missing installer-generated library: {path}')
            subprocess.run(['curl.exe', '-fsSL', '--retry', '2', '--max-time', '90', artifact['url'], '-o', str(path)], check=True)
            if artifact.get('sha1') and sha1(path) != artifact['sha1']:
                raise RuntimeError(f'wrong SHA-1: {path}')
        cp.append(str(path))
    values = dict(zip(
        ('auth_player_name', 'auth_uuid', 'auth_access_token', 'clientid', 'auth_xuid', 'user_type', 'version_type', 'version_name',
         'launcher_name', 'launcher_version', 'assets_index_name', 'resolution_width', 'resolution_height', 'quickPlaySingleplayer',
         'classpath_separator', 'classpath', 'library_directory', 'game_directory', 'natives_directory', 'assets_root', 'quickPlayPath'),
        ('MagnetQaNeo', '51d3e752592645af8e322354cf8f7803', '0', '0', '0', 'legacy', 'release', ver,
         'magnet-qa', '1', asset_index, '1140', '760', 'QaClient', os.pathsep, os.pathsep.join(cp), str(libs),
         str(case), str(case/'natives'), str(assets), str(case/'quickpath.json')), strict=True))

    def flags(node, group):
        for item in node['arguments'][group]:
            if not isinstance(item, str):
                if not allowed(item.get('rules'), feat):
                    continue
                selected = item['value']
            else:
                selected = item
            for arg in ([selected] if isinstance(selected, str) else selected):
                yield re.sub(r'\$\{([^}]+)\}', lambda m: values[m[1]], arg)

    launch = ['-Xmx3G', '-Dfile.encoding=UTF-8', '-Dchestmagnet.qaClient=true',
              *flags(vanilla, 'jvm'), *flags(neo, 'jvm'), neo['mainClass'], *flags(vanilla, 'game'), *flags(neo, 'game')]
    argfile = case / 'launch.args'
    argfile.write_text('\n'.join('"' + v.replace('\\', '\\\\').replace('"', '\\"') + '"' for v in launch), encoding='gb18030')
    with (case / 'console.log').open('wb') as logfile:
        proc = subprocess.run([str(args.java), '@' + str(argfile)], cwd=case, timeout=155, stdout=logfile, stderr=subprocess.STDOUT)
    log = (case / 'console.log').read_bytes()
    print('client passed' if b'QA_NEOFORGE_CLIENT_PASS' in log else 'client failed', flush=True)
    return 0 if proc.returncode == 0 and b'QA_NEOFORGE_CLIENT_PASS' in log and b'QA_NEOFORGE_CLIENT_FAILURE' not in log else 1


if __name__ == '__main__':
    raise SystemExit(main())
