"""Run a production Chest Magnet NeoForge JAR in a fresh, loopback-only server."""
import argparse
import hashlib
import json
import os
import queue
import shutil
import subprocess
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'docs/neoforge-upstream-lock.json').read_text(encoding='utf-8'))
PINS = {entry['name']: entry for entry in LOCK['dependencies']}
QA = ROOT / 'run/neoforge-qa'
PACKS = {
    'blank': (),
    'minimal': (),
    'lowest': ('backpacks', 'core', 'rs', 'curios', 'rs-curios', 'quartz', 'booster',
               'beyond', 'compixel', 'lootr', 'quark', 'zeta'),
    'current': ('lootr-current', 'quark-current', 'zeta'),
    'quests': ('ftbquests', 'ftblibrary', 'ftbteams', 'architectury', 'backpacks', 'core'),
    'locked': ('aether', 'aquamirae', 'fragmentum', 'geckolib', 'curios', 'owo'),
    'compass': ('compass',),
    'enhance-legacy': ('enhance-legacy',),
    'enhance': ('enhance',),
    'edited': ('edited',),
}


def copy_library(source, target):
    try:
        os.link(source, target)
    except OSError:
        shutil.copy2(source, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('case', choices=PACKS)
    parser.add_argument('--java', type=Path, default=Path('D:/Java/JDK/JDK21/bin/java.exe'))
    parser.add_argument('--neoforge')
    parser.add_argument('--boot-only', action='store_true', help='do not run in-game assertions')
    parser.add_argument('--sync', action='store_true', help='force synchronous Enhance search')
    args = parser.parse_args()
    forge = args.neoforge or ('21.1.252' if args.case == 'current' else LOCK['neoForge'])
    test = args.case != 'blank' and not args.boot_only
    d = QA / f'{args.case}-{time.time_ns()}'
    (d / 'mods').mkdir(parents=True)
    print(d, flush=True)
    base = QA / forge / 'libraries'
    if not base.is_dir():
        raise RuntimeError(f'Missing official installer output: {base}')
    shutil.copytree(base, d / 'libraries', copy_function=copy_library)
    (d / 'eula.txt').write_text('eula=true\n', encoding='ascii')
    terrain = 'level-type=minecraft:normal\n' if args.case in ('compass', 'edited', 'enhance', 'enhance-legacy') else (
        'level-type=minecraft:flat\n'
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":4}]}\n')
    (d / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=qa-world\n'
        + terrain +
        'view-distance=3\nsimulation-distance=3\nspawn-protection=0\nlevel-seed=20261116\n', encoding='ascii')
    shutil.copy2(ROOT / 'build/libs/chestmagnet-neoforge-1.21.1-1.2.8.jar', d / 'mods')
    if test:
        shutil.copy2(ROOT / 'build/qa/chestmagnet-neoforge-qa.jar', d / 'mods')
    for name in PACKS[args.case]:
        jar = ROOT / f'libs/neoforge/{name}.jar'
        if hashlib.sha256(jar.read_bytes()).hexdigest() != PINS[name]['sha256']:
            raise RuntimeError(f'Wrong SHA-256 for {name}')
        shutil.copy2(jar, d / 'mods')
    if args.case in ('lowest', 'current'):
        (d / 'config').mkdir()
        (d / 'config/quark-common.toml').write_text('[Oddities]\n\t"Crate" = true\n', encoding='ascii')
    command = [str(args.java), '-Xmx3G', '-Dfile.encoding=UTF-8',
               f'-Dchestmagnet.qa={args.case}', f'-Dchestmagnet.qa.compassSync={str(args.sync).lower()}',
               f'@libraries/net/neoforged/neoforge/{forge}/win_args.txt', 'nogui']
    signal = queue.Queue()
    with subprocess.Popen(command, cwd=d, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as p:
        def read_log():
            with (d / 'console.log').open('wb') as log:
                for line in p.stdout:
                    log.write(line)
                    log.flush()
                    if b'QA_NEOFORGE_' in line:
                        print(line.decode('utf-8', 'replace').rstrip(), flush=True)
                    if b'For help, type "help"' in line and not test:
                        signal.put(True)
                    if b'QA_NEOFORGE_PASS' in line:
                        signal.put(True)
                    if b'QA_NEOFORGE_FAILURE' in line:
                        signal.put(False)
                signal.put(False)
        thread = threading.Thread(target=read_log, daemon=True)
        thread.start()
        try:
            success = signal.get(timeout=250)
            if p.poll() is None:
                p.stdin.write(b'stop\n')
                p.stdin.flush()
            code = p.wait(timeout=45)
            thread.join(5)
        finally:
            if p.poll() is None:
                p.terminate()
    log = (d / 'console.log').read_bytes()
    if b'Parsing error loading recipe chestmagnet:' in log or b'Mixin apply for mod chestmagnet failed' in log:
        success = False
    print(f'{args.case}: marker={success}, exit={code}', flush=True)
    return 0 if success and code == 0 else 1


if __name__ == '__main__':
    raise SystemExit(main())
