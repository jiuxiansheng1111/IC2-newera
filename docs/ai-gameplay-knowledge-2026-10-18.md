# 游戏 AI 的充电、电力与玩法知识同步

## 范围与实现

用户要求助手同步充电设备规则、爆炸条件、变压器与物品玩法。新增随包 `gameplay_energy_zh.md`，按问题检索当前知识；普通 AI 助手和八角色机器人聊天共用。检索保留简短追问的上一条玩家问题，不把模型旧回复当事实。内存中只缓存不可变知识章节和有上限的 IC2 手册索引，输出按字数/条数限制；未建立大型向量库或额外模型。

`InstalledIc2Manual` 运行时读取已安装依赖的四个公开语言资源（wiki 中英、物品和方块中文），优先中文、缺失回退英文。不会把第三方手册或实现代码拷进项目/发行包；不会扫描私人配置、密钥或存档。普通助手尊重原“本机模组读取”开关并显示来源版本和条目；角色聊天在服务器上读取公共手册。新纪元配方仍来自同一 ProcessGraph：普通助手读服务器同步图，机器人使用服务端当前图，新增手机/电池/充电宝等相关路线匹配。没有另写一套配方或更改原 LLM 设置。

## 资料依据与防止误答

固定依赖为 IC2 Classic 2.1.3.4（官方文件 8545995），SHA-256 `F0A99733EF33491ECA418831D681A79902250D341650F652D06F8B88D3627F83`。作者 [Wiki 首页](https://github.com/TinyModularThings/IC2Classic/wiki) 明确在线旧内容多为 1.12，1.19 使用游戏内 Wiki。本轮核对该 JAR 的 `assets/ic2/lang/wiki_zh_cn.json`、`wiki_en_us.json` 和相关类的只读字节码，写入的是自己的中文事实说明。

- `BaseChargingBenchTileEntity` / `BaseBatteryStationTileEntity`：原生目标充电 tier 为 MAX_VALUE；充电台可跨物品等级。电池站只输出 EU，16 格放电供能、独立一格充电，不能从电缆收电。不要混淆机器输入耐压与物品充电等级。
- `BaseChargePadTileEntity` 和四种 Chargepad：按 1/2/3/4 档，默认选中主手及副手/支持装备，升级可扩展扫描范围；只扫描玩家，不等同机器人自动充电。
- `BaseTransformerTileEntity` 和 LV/MV/HV/EV：无红石为正面高压输入/其他面低压输出，红石反向；32↔128、128↔512、512↔2048、2048↔8192。专属正面三点纹理与当前红石模式需要一起判断。
- `EnergyNetLocal`、`IC2Config`：单包过压与总功率不同；easy mode 影响危险路径并关闭变压升级，非反应堆事故开关。导线有独立绝缘/导体阈值。未读取当前世界配置，不声称本服事故开关状态。
- `BaseNuclearReactorTileEntity` / `SteamNuclearReactorTileEntity` / `ReactorHeatVentItem`：实际最大热量和组件/散热、配置决定风险；蒸汽缺水或输出堵塞可能中断冷却，不能承诺布局不会爆炸。
- 新纪元 `RobotRegistry`、`RobotBatteryItem`、`PowerBankItem`、`NuclearPowerBankItem`、`RobotEntity`：四档容量/等级、自动补电、24/6 小时的 20 TPS 条件、五次寿命、核能有限燃料、物品本身没有反应堆热害模拟。

只读依据保存于 `build-isolated/ai-gameplay-knowledge-20261018/` 和此前 `build-isolated/ic2-charging-device-audit-20261018/`。不是实机爆炸/充电验收。未编造可调变压器极限边界或任意物品都已验证的结论。

## 交付状态

源码和 22 节玩法资料已完成。最终离线 `build -x test --offline` 27 秒成功；544 个 JSON/mcmeta 可解析，新指南与源码资源字节一致、ProcessGraph 未改，JLayer 保留。Forge 下跨模组手册使用 `ModList.getModFileById("ic2").getFile().findResource(...)` 定位依赖资源，避免用新纪元自身 Class 去跨模块查找而读不到 IC2 手册。

完整 JAR 1,915,603 字节，SHA-256 `9B25ED609F0D1045056162E57D1F2C435BFADCE1FA0243C8490FBDA1E0767C9D`。2026-10-18 11:04:14 +08:00 在未检测到游戏运行时通过现有 PCL 安装脚本更新，只替换一个新纪元 JAR，8 个模组哈希回读匹配。已停止的隔离服同版 JAR 同步完成，没有启动专服或改配置/世界。

新朋友包 `dist/IC2-New-Era-Test-2026.10.18-AI-Knowledge.zip`，版本 `0.1.0-test.20261018-ai-knowledge`，1,574,032 字节，SHA-256 `AD7D16A3DC0AC6A46DA04782E38F0CF305542FC7AA6B9590E1268BBE860E7B95`。旧包保留为历史；新包一个自有 JAR、七个固定官方依赖引用，开包字节与哈希检查通过，不含私人设置或存档。证据：本阶段 `build.log`、`package-audit.json`、`server-jar-sync.json`，以及 `build/deployment/pcl-installation.json`、`build-isolated/shareable-modpack-audit.json`。

按项目约定没有新增/运行测试、没有调用用户模型、启动/关闭游戏或修改存档。构建/资源审阅不等于真实模型在游戏中的回答或事故行为验收；多人和穿透仍由玩家测试。原 LLM 配置未修改。

既有十小时播放器跟进已独立完成：36,000.002 秒，60 轮通过、0 失败，`ic2` 自动化 PAUSED。这是旧 OGG/WAV 单 JVM 静音组件循环，不代表本轮知识检索或 Minecraft 多人实机测试。详细结果见 [原生媒体交付](native-media-migration-2026-10-18.md)。
