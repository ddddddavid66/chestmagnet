# 宝箱磁铁 1.2.8 · Minecraft 1.21.1 NeoForge

这是 [Forge 1.20.1 正式版](https://github.com/ddddddavid66/chestmagnet/releases/tag/v1.2.8) 的移植，不是 1.2.9 功能更新。

## 安装

- Minecraft **1.21.1**、Java **21**。本地降门槛候选 JAR 单装最低实测为 **NeoForge 21.1.1**；已发布的旧 JAR 仍要求 21.1.230。
- 把 `chestmagnet-neoforge-1.21.1-1.2.8.jar` 放进 `mods`。联机时客户端、服务端都安装此版，不能混用 1.20.1 Forge JAR。
- 可选联动不随磁铁一起发货。安装前核对 [本轮实测版本表](docs/neoforge-compat-floor.md)，联动可能需要比磁铁本体更高的 NeoForge。Quark 4.1-488 本身需要 NeoForge 21.1.252。
- 从旧世界迁移时先备份，用副本测试。磁铁保留 `chestmagnet:chest_magnet`、范围、排除、模式和诊断数据。旧缓存里无法解析的物品保留原始数据并封存，不自动补发。
- 本移植不包含 Tetra、自制指南针 1.3.4、Goety: Revelation 专属容器和绯红教堂基座。

## 使用

- 手持磁铁：右键启停，Shift＋左键空气打开配置，Shift＋右键切换来源排除，Ctrl＋右键使用指南针结构收取。可在控制设置中改绑。放进物品栏、快捷栏或副手即可运行。
- 目的地可选物品栏、RS、精妙背包、超越维度。选定目的地不可用或拒收时，不自动切到其他模式。精妙背包按原生升级规则接收，包括可能销毁；历史显示为「已处理」，不等于库存。
- RS 2 需要携带绑定的无线终端；依照原生网络覆盖、权限和终端电量。跨维度需对应原生扩展及已启用的维度覆盖，磁铁不会强加载网络。
- 结构模式检查全部实际分段各自外扩 16 格的并集及维度全部高度，**可能包含重叠建筑或玩家的容器**。二维预览只读已加载区块，不决定范围。
- 任务检测只读当前存储。关闭吸附不影响检测，移走磁铁或手动关闭检测才会停止。与同类任务检测模组同装可能冲突。

## 构建

使用 Java 21 执行 `./gradlew build`（Windows：`gradlew.bat build`）。可选的 `prepare-dependencies.ps1` 从[上游锁定表](docs/neoforge-upstream-lock.json)下载并验证 SHA-256，不接触现有整合包。用 `gradlew.bat dist -PdistDirectory=dist/compat-floor` 生成候选 JAR 和 SHA-256，不覆盖原发行归档。QA 辅助 JAR 不是发布物。

维护分支为 `codex/port-1.21.1-neoforge`，基线 `d74ca02`。移植版使用独立发行标签 `1.21.1-neoforge-v1.2.8`，不替换旧 Forge 的文件或标签。
