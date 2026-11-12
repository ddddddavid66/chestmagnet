"""从 reobf 验证构建提取独立辅助模组，禁止把验证构建当生产磁铁分发。"""
from pathlib import Path
import sys, zipfile
source = Path(sys.argv[1])
out = Path(__file__).resolve().parents[1]/'run/qa124-artifacts/rsqa124.jar'
out.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(source) as archive, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as result:
    files=[name for name in archive.namelist() if name.startswith('dev/rsqa124/') and name.endswith('.class')]
    if len(files)<2: raise RuntimeError('missing QA classes; build with -Pverification first')
    for name in files: result.writestr(name,archive.read(name))
    if 'dev/rsqa124/mixin/EngineCountMixin.class' in files:
        result.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nMixinConfigs: nearbyqa.mixins.json\r\n\r\n')
        result.writestr('nearbyqa.mixins.json','{"required":false,"minVersion":"0.8","package":"dev.rsqa124.mixin","compatibilityLevel":"JAVA_17","mixins":["EngineCountMixin","SourceCountMixin"],"injectors":{"defaultRequire":1}}')
    result.writestr('pack.mcmeta','{"pack":{"pack_format":15,"description":"RS QA only"}}')
    result.writestr('META-INF/mods.toml','modLoader="javafml"\nloaderVersion="[47,)"\nlicense="MIT"\n[[mods]]\nmodId="rsqa124"\nversion="1"\ndisplayName="RS QA (test only)"\n')
print(out)
