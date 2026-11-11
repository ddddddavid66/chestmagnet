# 宝箱磁铁 1.2.3

Forge 1.20.1 模组，最低 Forge `47.4.16`。本版本以 `dist/source-1.2.2.zip` 的完整源码为基线，新增建筑指南针适配和 Goety: Revelation 容器适配。

## 安装

将 `dist/chestmagnet-forge-1.20.1-1.2.3.jar` 放入客户端和服务器的 `mods`。不会修改原整合包或存档；首次使用建议先备份存档。

可选依赖会按运行时是否存在自动启用：Explorer's Compass、Lootr、精妙背包、精妙核心、Refined Storage、RS Addons。精妙背包最低版本为 `3.23.15.1236`，精妙核心最低版本为 `1.2.55.975`。

## 1.2.3 变更

- 指南针结果由磁铁内部统一接入，支持 Explorer's Compass 1.4.0、Unified 1.3.3、1.3.4 API、整合包 Unified 1.2.6；未安装指南针时不加载适配。
- 新增 `StructureTargetProvider` 注册接口。未知指南针不会根据附近建筑猜测；生物群系等非建筑结果不会触发结构收取。
- 支持 Goety: Revelation 的 `lofty_chest`、`crypt_chest`、`pithos`，以及酿造台。带锁容器仍由“收取带锁容器”开关控制，库存收完后才清理锁状态。
- 战利品使用真实服务端玩家和原生解包流程，已生成库存不重新抽奖，幸运值和数据包修改保持原版链路。

完整操作说明见 `docs/使用说明-1.2.3.md`，测试边界见 `docs/测试记录-1.2.3.md`。
