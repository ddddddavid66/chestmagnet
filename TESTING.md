# NeoForge 移植验证

环境：Minecraft 1.21.1、Java 21、NeoForge 21.1.230；较新 Quark/Lootr 组合单独用 21.1.252。测试使用独立目录和生成存档，旧 Forge 整合包和存档未修改。QA 模组是单独的 JAR；业务逻辑由待发布 JAR 加载。

## 实测最低组合

| 可选联动 | 安装版本 | 核对内容 |
|---|---|---|
| 精妙背包 | 3.26.21.2249 ＋精妙核心 1.5.32.2464 | 两个随身包按顺序接收：第一包 2 件、第二包 9 件；原生销毁升级处理另一批 4 件 |
| 精致存储 | RS 2.0.9 | 普通无线网格同维度 15 件、跨维度 13 件，无线合成网格 7 件、Curios 槽 9 件；实收后扣电，无维度卡或电量不足时不取；防止外部存储回流 |
| 超越维度 | 0.8.9 ＋ CompixelUI 0.1.18 含 Kotlin | 地狱来源进入主网络；两个网络分别接收 6、5 件；非成员不能切换，切换后不串仓 |
| 战利品箱 | 1.11.36.115 | 两名服务端玩家的箱子与矿车个人库存互不影响，无菜单弹出，来源不拆除 |
| Quark | 4.1-482 ＋ Zeta 1.1-40 | 变种双箱收取 12 件；板条箱部分收取 9 件、源余 2 件；储物船；Lootr 的木箱和陷阱变种仍隔离 |

RS 跨维度测试另装 [RSInfinityBooster 2.0.0](https://www.curseforge.com/minecraft/mc-mods/rsinfinitybooster/files/9295492)，无线合成测试另装 [Quartz Arsenal 1.0.8](https://www.curseforge.com/minecraft/mc-mods/refined-storage-quartz-arsenal/files/8103101)，饰品栏测试用 Curios 9.5.1 和 RS Curios Integration 1.0.0。各正式文件和校验值见 [锁定表](docs/neoforge-upstream-lock.json)。

| 补充覆盖 | 验证内容 |
|---|---|
| 无可选模组 | 正式 JAR 单独启动；配方注册、附近完整和部分收取、展示框回退；旧版磁铁及旧缓存数据迁移，无法识别的物品封存 |
| NeoForge 21.1.252 | Quark 4.1-488 ＋ Zeta 1.1-40 ＋ Lootr 1.11.38.127 代表回归 |
| 探险者指南针 | 原版 3.4.0、Edited 3.0.4、Enhance 1.5.9、Enhance 2.0.1 分开安装；自然生成村庄的真实起始区块、深层箱收取 3 件、原生取消；Enhance 重搜刷新目标 |
| Aether / Aquamirae | Aether 1.5.10 收空后保留宝箱及种类并用原生方法解锁；Aquamirae 7.2.13 使用实际战利品表，收空后保留普通箱且不重抽 |
| FTB Quests | 2101.1.38：普通持有任务进度 8、严格数据组件匹配进度 3、消耗任务无进度，背包物品不被扣除；关闭吸附与关闭检测分开 |
| 正式客户端 | 实际附近收取、菜单协议、最近获取页和快捷键激活上下文烟测 |

这里列的是实际装过的组合，不代表后续任意版本均兼容；没有测试低于表中的可选模组版本、第三方组合和性能极限。服端 QA 中的离线玩家和客户端 QA 的集成服务器并不代替多人客户端整合包全量测试。

## 复现

1. 用 Java 21 将官方 NeoForge 安装器的 `--installServer` 输出安装到 `run/neoforge-qa/21.1.230`，较新组使用 `run/neoforge-qa/21.1.252`。
2. 执行 `./prepare-dependencies.ps1 -Name all` 和 `gradlew.bat dist neoforgeQaJar`。不要把 QA JAR 或第三方依赖一起发布。
3. 执行 `python scripts/qa-neoforge.py blank`、`minimal`、`lowest`、`current`、`locked`、`quests`、`compass`、`edited`、`enhance-legacy`、`enhance`。Enhance 可加 `--sync` 使用同步搜索。验证标记为 `QA_NEOFORGE_PASS`，每个案例在 `run/neoforge-qa/` 保存独立日志。
4. 客户端使用独立官方 21.1.230 安装及对应资产，通过 `scripts/qa-neoforge-client.py --world <minimal 案例的 qa-world> --scale 2` 验证；缩放 3 同理。标记为 `QA_NEOFORGE_CLIENT_PASS`。

上述命令均只使用独立测试目录。原分支留下的 Forge QA 工具不用于 NeoForge 验证。


## 本次发行候选证据

2026-11-16 验证。以下均使用 SHA-256 `4c0260fb5d2421b063d71724095a41f5818c8acdff4eb403c0b6a2f90735260c` 的磁铁 JAR；不含 QA 或第三方类。日志在工作区的 `run/neoforge-qa/<实例目录>/console.log`。

| 验证 | 实例目录 |
|---|---|
| 独立启动／基础收取／旧数据 | `blank-1794814089817698900`；`minimal-1794814115156865700` |
| 五项最低组合 | `lowest-1794814281033349000` |
| 较新 Quark／Lootr | `current-1794814108730838100` |
| 两类锁箱／FTB | `locked-1794814148293019100`；`quests-1794813705901708100` |
| 原版／Edited | `compass-1794814173612973200`；`edited-1794814205312807600` |
| Enhance 1.5.9 异步／同步 | `enhance-legacy-1794814236398237000`；`enhance-legacy-1794814209759026600` |
| Enhance 2.0.1 异步／同步 | `enhance-1794814268103422500`；`enhance-1794814240968337000` |
| 客户端 GUI 缩放 2／3 | `client-2-1794814082071371400`；`client-3-1794814118371110700` |

最低组也验证了无线终端权限拒绝时不取、不扣电；客户端回合实际从桶收取 4 个绿宝石，并收到数量正确的历史页。超越维度依赖在最小组合下仍会记录自己的未安装联动路径资源警告；主网络、跨维度存入与切换测试通过。此轮不把 QA 的世界生成耗时当作帧率或性能数据。
