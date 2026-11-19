"""用原包的客户端正常搜索和传送；每个臂从同种子全新创造世界开始。"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import tomllib
import zipfile

sys.stdout.reconfigure(encoding="utf-8")
root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("arm", choices=("none", "off", "nearby", "structure"))
parser.add_argument("--magnet", type=Path)
parser.add_argument("--compass", type=Path)
parser.add_argument("--qa-build", type=Path, default=root/"build/libs/chestmagnet-forge-1.20.1-1.2.8.jar")
parser.add_argument("--pack", type=Path, default=root.parent/"涟漪之"/".minecraft")
parser.add_argument("--first", type=int, default=0)
parser.add_argument("--count", type=int, default=6)
parser.add_argument("--seed", type=int, default=-3563551871153301544)
parser.add_argument("--reopen", action="store_true", help="结束后重启刚创建的同一测试存档")
parser.add_argument("--lean", action="store_true", help="仅用于先校准测试，不作为整包结论")
args = parser.parse_args()
if (args.magnet is None) != (args.arm == "none"):
    parser.error("不装磁铁组不带 --magnet；其他组必须指定 JAR")
if args.first < 0 or not 1 <= args.count <= 12:
    parser.error("无效的测试区间")

pack = args.pack.resolve()
version = next(p for p in (pack/"versions").iterdir() if p.name.endswith("如涟漪之所见"))
manifest = json.loads((version/(version.name + ".json")).read_text(encoding="utf-8"))
case = root/"run"/f"qa-fresh-treasure-{args.arm}-{time.time_ns()}"
mods = case/"mods"
mods.mkdir(parents=True)
(case/"natives").mkdir()

if args.lean:
    for source in [args.compass or root/"libs/explorer-1.3.4.jar", root/"libs/lootr-0.7.35.94.jar"]:
        shutil.copy2(source, mods/source.name)
else:
    for source in (version/"mods").glob("*.jar"):
        with zipfile.ZipFile(source) as jar:
            raw = jar.read("META-INF/mods.toml") if "META-INF/mods.toml" in jar.namelist() else b""
        ids = {m.get("modId") for m in tomllib.loads(raw.decode("utf-8")).get("mods", [])}
        if ids & {"chestmagnet", "rsqa124"} or args.compass and "explorerscompass" in ids:
            continue
        shutil.copy2(source, mods/source.name)
    if args.compass:
        shutil.copy2(args.compass, mods/args.compass.name)
    # 只复制全局配置与数据包，不读取旧存档的区块、玩家或探索历史。
    for name in ("config", "defaultconfigs", "kubejs", "moonlight-global-datapacks",
                 "openloader", "global_packs", "datapacks", "paxi", "DLC", "data"):
        if (version/name).is_dir():
            shutil.copytree(version/name, case/name)
if args.magnet:
    shutil.copy2(args.magnet, mods/args.magnet.name)

with zipfile.ZipFile(args.qa_build) as build, zipfile.ZipFile(mods/"fresh-treasure-qa.jar", "w", zipfile.ZIP_DEFLATED) as qa:
    classes = [n for n in build.namelist() if n.endswith(".class") and
               (n.startswith("dev/rsqa124/FreshTreasure") or n.startswith("dev/rsqa124/mixin/FreshTreasure"))]
    if len(classes) < 4:
        raise RuntimeError("先构建 -Pverification QA 工件")
    for name in [*classes, "dev/rsqa124/RsQaBootstrap.class"]:
        qa.writestr(name, build.read(name))
    qa.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMixinConfigs: fresh-treasure.mixins.json\r\n\r\n")
    qa.writestr("META-INF/mods.toml", 'modLoader="javafml"\nloaderVersion="[47,)"\nlicense="MIT"\n'
                '[[mods]]\nmodId="rsqa124"\nversion="1"\ndisplayName="Fresh treasure QA only"\n')
    qa.writestr("fresh-treasure.mixins.json", json.dumps({
        "required": True, "minVersion": "0.8", "package": "dev.rsqa124.mixin",
        "compatibilityLevel": "JAVA_17",
        "mixins": ["FreshTreasureChunkMixin", "FreshTreasureTableMixin"],
        "injectors": {"defaultRequire": 1},
    }))
    qa.writestr("pack.mcmeta", '{"pack":{"pack_format":15,"description":"Fresh treasure QA only"}}')

hashes = {p.name: hashlib.file_digest(p.open("rb"), "sha256").hexdigest()
          for p in mods.glob("*.jar") if any(k in p.name.lower() for k in ("lootr", "explorer", "chestmagnet", "qa"))}
(case/"input-hashes.json").write_text(json.dumps(hashes, indent=2, ensure_ascii=False), encoding="utf-8")
(case/"options.txt").write_text("lang:zh_cn\nguiScale:2\npauseOnLostFocus:false\n"
                               "renderDistance:8\nsimulationDistance:8\nmaxFps:90\n", encoding="utf-8")
cp = []
for lib in manifest["libraries"]:
    name = lib.get("downloads", {}).get("artifact", {}).get("path")
    if name and (pack/"libraries"/name).exists() and not any(
            s in name for s in ("natives-linux", "natives-macos", "linux-aarch", "linux-x86", "java-objc")):
        cp.append(str(pack/"libraries"/name))
cp.append(str(version/(version.name+".jar")))
variables = {
    "natives_directory": str(case/"natives"), "launcher_name": "fresh-treasure-qa", "launcher_version": "1",
    "classpath": ";".join(cp), "version_name": version.name, "library_directory": str(pack/"libraries"),
    "classpath_separator": ";", "auth_player_name": "MagnetTest", "game_directory": str(case),
    "assets_root": str(pack/"assets"), "assets_index_name": "5", "auth_uuid": "a943f925d5cb4c28b125ce7369704878",
    "auth_access_token": "0", "clientid": "", "auth_xuid": "", "user_type": "legacy", "version_type": "release",
}

def expand(value):
    for key, item in variables.items():
        value = value.replace("${"+key+"}", item)
    return value

jvm = [("-Xmx3G" if args.lean else "-Xmx8G"), f"-Dqa.freshTreasure={args.arm}",
       f"-Dqa.freshCount={args.count}", f"-Dqa.freshFirst={args.first}", f"-Dqa.freshSeed={args.seed}"]
for value in manifest["arguments"]["jvm"]:
    if isinstance(value, str):
        jvm.append(expand(value))
    elif all(rule.get("os", {}).get("name", "windows") == "windows"
             and rule.get("os", {}).get("arch", "x86_64") != "x86" for rule in value["rules"]):
        entries = value["value"]
        jvm.extend(map(expand, entries if isinstance(entries, list) else [entries]))
jvm.extend([manifest["mainClass"], *(expand(v) for v in manifest["arguments"]["game"] if isinstance(v, str)),
            "--width", "1100", "--height", "760"])
argfile = case/"args.txt"
def write_args(path, values):
    path.write_text("\n".join('"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"' for s in values), encoding="gb18030")

write_args(argfile, jvm)
print(case, flush=True)
with (case/"console.log").open("w", encoding="utf-8") as log:
    process = subprocess.Popen(["D:/Java/JDK/JDK17/bin/java.exe", "@"+str(argfile)], cwd=case,
                               stdout=log, stderr=subprocess.STDOUT)
    try:
        process.wait(timeout=1200)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait()
        print("INCOMPLETE: client timeout", flush=True)
trace = (case/"console.log").read_text(encoding="utf-8", errors="replace")
print("\n".join(line for line in trace.splitlines() if any(x in line for x in (
    "FRESH_ORIGIN", "FRESH_SEARCH", "FRESH_LANDING", "FRESH_SAMPLE", "FRESH_RESULT", "FRESH_COMPLETE", "FRESH_FAILED",
    "FRESH_TELEPORT_FAILURE"))))
complete = process.returncode == 0 and "FRESH_COMPLETE" in trace and "FRESH_FAILED" not in trace
if args.reopen and complete:
    coords = re.findall(r"FRESH_RESULT .* result=landed xz=(-?\d+),(-?\d+)", trace)
    if not coords:
        sys.exit("未成功到站，不能验证重启")
    x, z = coords[-1]
    reopening = jvm[:4] + ["-Dqa.freshReopen=true", f"-Dqa.freshReopenX={x}", f"-Dqa.freshReopenZ={z}"] + jvm[4:]
    reopening += ["--quickPlaySingleplayer", "FreshBuriedQA"]
    write_args(case/"reopen-args.txt", reopening)
    with (case/"reopen.log").open("w", encoding="utf-8") as log:
        reload = subprocess.run(["D:/Java/JDK/JDK17/bin/java.exe", "@"+str(case/"reopen-args.txt")],
                                cwd=case, stdout=log, stderr=subprocess.STDOUT, timeout=720)
    reopened = (case/"reopen.log").read_text(encoding="utf-8", errors="replace")
    print("\n".join(line for line in reopened.splitlines() if "FRESH_" in line))
    complete = reload.returncode == 0 and "FRESH_REOPEN_COMPLETE" in reopened
sys.exit(0 if complete else 1)
