# r62：全地形均衡、生机遍布、无尽贪婪、匠魂与怪物娘化

日期：2026-11-03。用户要求重新创建地形种类完整、分布更均衡的UltraTerraForged世界；随后要求加入Spawn、无尽贪婪和匠魂，并明确怪物娘化只改原版外观、沿用Grove、仅安装客户端。保留旧世界及完整备份，未启动本地Minecraft或运行自动化测试。

## 已完成的文件与构建工作

- 基于已部署UltraTerraForged 1.0.3+1.20.1的实际源码Codec及服务器默认JSON制作「新纪元·全地形均衡」。9类基础地形均有正权重，40项特色地貌全部启用；大陆尺度2400、地形区域900、生物群系尺度200，冷热干湿完整范围。增加平原/草原权重，适当降低火山和崎岖山地权重；保持山脉、峡谷、海岸、河流、湖泊、湿地、洞穴、海底特征。权重不等于实测面积比例，不保证出生点附近包含全部特征。
- 世界高度512、深度64、海平面63，矿物、洞穴、结构及各特色地貌内部参数沿用原预设。客户端增加modpack-presets及modpack.json，保留全部内置预设和自定义入口。
- Spawn采用正式版4.0.8-1.20.1；其Forge构件强制依赖仅Forge47.1+与Minecraft1.20.1，不要求另外安装Teal Lib。无尽贪婪采用Re:Avaritia正式版1.4.2。匠魂采用正式版3.11.2.166与Mantle正式版1.11.104，满足Mantle最低1.11.97及可选JEI最低15.20.0.103；既有JEI为15.56.0.205。
- 全部新增JAR逐项核对Modrinth官方SHA-1/SHA-512、大小、ZIP CRC、Java17字节码及mods.toml。锁定文件为scripts/world-content-lock-1201.json和player-enhancements-lock-1201.json。第三方JAR原样保留；本轮没有更改其原生合成路线。匠魂熔铸与新增生物的完整玩法、性能及跨模组平衡仍需玩家验收，不宣称实现了机器人自动使用新增机器或所有熔液流程的工艺适配。
- 怪物娘化采用作者1.20–1.20.1的CMMRP Release 1.0与同版本EMF修复包，均为pack_format15，原包哈希已验证。资源包顺序为原版、Grove、CMM主包、CMM EMF修复包；EMF/ETF沿用原已装版本，未混入OptiFine或仅支持OptiFine的末影龙附件。旧版EMF存在末影龙/潜影贝等作者标注的支持限制，不保证覆盖Spawn等模组生物。资源包不新增生物行为或交互。
- 新纪元内置AI同步上述内容与地形说明。首次离线构建后，开服暴露Spawn与UTF预设导出冲突；增加两个Mixin和限定线程范围的导出助手，再次离线build -x test成功（42秒）。协议、ProcessGraph和配方保持。没有运行自动化测试。
- 现有PCL安装脚本安装5个变化文件（4个新增第三方JAR和新纪元JAR），客户端共42个模组。CMM安装脚本先备份options.txt，仅修改resourcePacks一项，其余画质、内存和按键保持；Grove、ITT和原有语音资源保留。已核对安装文件哈希，未启动客户端验收画面。

## 交付文件

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| IC2-New-Era-Windows-1.20.1-r62-Content-Grove-CMM.mrpack | 308426832 | FE455B3A83F431B4210D02B669E1449443DB9BBC384CE68041A16B6E48823B8F |
| IC2-New-Era-Server-1.20.1-r62-Content-Voice.zip | 467552911 | 1F4B67C982C8BB0C2DCF33731A3A298F141DEF4553F5FE5B83AD0FC13F02C612 |
| IC2-New-Era-Client-r62-Update.zip | 112616526 | 02CCA048F6D36D060D59B67BB0547D5384A1D5EC50903882554C5E74DDE72AC3 |
| IC2-New-Era-Server-r62-Update.zip | 111461657 | C2D047A05883944AB6A183BE812A79A533A1BAA8D1FC661C652E8CDF19B868EF |
| ic2newera-0.2.0-migration1201-all.jar | 10474894 | F09DE5752410C79075BB817BFBD1A0D0D86D58AA57616BA5F24BA2A1129C5C29 |

所有归档CRC及全部成员SHA-256回读一致。Windows mrpack通过作者原CDN下载新增模组和CMM；完整服务器包31个模组，不含Grove、CMM或光影。固定语音JAR、128段问候及原启动参数沿用。老客户端更新附件不覆盖options.txt，须按说明在游戏内启用娘化；本机已由安装脚本完成启用。完整服务器包用于全新部署，不直接覆盖现有真实配置/世界。

## 指定服务器部署

停服前0/20在线，原世界gamerule只读确认keepInventory=true、mobGriefing=false、doFireTick=false。正常停服后完成全量备份before-r62-balanced-20261103，506.34MiB，SHA-1 adfe7a4ee66ef4759ea4a549f6e2df693ded12a6。

服务器预设保存并全文回读匹配。server.properties全文比对只改变level-name和level-seed，新世界world-ultraterra-balanced-r62-20261103，种子2026110362；已有UltraTerraForged生成器和其他配置保持。旧world及world-ultraterra-r61-20261103均保留。

4个新增官方JAR及更新的新纪元JAR已上传到活动mods，指定服务器共33个活动JAR（原有Cloth Config和Xaero继续保留）。面板URL下载Spawn为异步任务，未完成时曾隔离为非JAR扩展名，再用已校验的本地完整副本上传；活动副本显示79.73MiB。修复版已完成启动；本轮产生的非JAR临时下载副本和压缩核对附件已移入面板24小时回收站，活动mods恰为33个JAR。

本机完整包与安装副本哈希已验证；本轮面板下载事件超时，尚未取得远端活动JAR的二进制回读，不把文件大小核对称为远端哈希校验。完整备份和本地r61构件可用于回退。

## 已修复的首次开服冲突

首次启动在UTC15:26:56因UTF调用VanillaRegistries.createLookup失败：Spawn向OverworldBiomeBuilder加入了10种群系键，而这个导出专用的原版注册表尚未加载Spawn的JSON数据，导致Unreferenced key及Errors during registry creation。不是预设JSON损坏，也没有把生成器降级为原版来绕过问题。

新纪元只重定向UTF ServerPresets.installIfNewWorld中的这一处查找表创建，使用try/finally清理ThreadLocal导出范围。在该范围内，OverworldBiomeBuilder的消费端跳过spawn命名空间的参数；普通世界加载直接返回原消费端，保留Spawn群系。UTF不导出多噪声群系参数表，后续世界注册从原数据包正常解析。未改第三方JAR。相比r61，新纪元JAR只新增三个兼容类，并改变Mixin清单与内置AI资料；没有删除成员或改动其他Java类。

修复版UTC15:37:11记录仅在临时查找表跳过155条Spawn气候参数，随后UTF明确记录成功创建预设；UTC15:37:27开始准备指定r62新世界，15:37:31的Spawn日志已找到Dodo Island坐标7912,6696。这些记录证明新世界没有全局禁用Spawn群系；完整启动结果见下方最终回执。

## 最终部署结果

北京时间2026-11-03 23:38:36（日志UTC15:38:36）出现Done (71.112s)。23:39:17的list返回0/20在线，41个启用数据包包含mod:spawn、mod:avaritia、mod:tconstruct、mod:mantle及file/ultraterraforged-preset.zip。23:40:17记录所有维度已保存和Saved the game，回读keepInventory=true、mobGriefing=false、doFireTick=false；23:41:24再次list正常响应0/20，服务器保持运行。

本次启动没有再次出现UTF注册表创建失败、Mixin apply failed或服务端tick异常。日志仍包含既有可选兼容目标缺失提示、Valkyrien Skies物理队列警告，以及首次生成/强制保存期间的落后tick提示；完成启动不等于所有玩法、联机画面或负载表现均已验收。未启动本地Minecraft或运行自动化测试。

四个最终归档、dist单独新纪元JAR、本地实例及服务器上传副本均来自同一次兼容修复构建；本地与归档哈希已回读验证，远端哈希限制如上。旧r61及本轮修复前草稿移出dist保存在私有构建目录，完整旧世界备份保留。公开同步只使用明确的16个第一方源码/资源/说明路径，保留用户原工作分支、HEAD及主索引。

## 来源与公开范围

[Spawn 4.0.8 Forge](https://modrinth.com/mod/spawn-mod/version/Iw5SeMQX)，[Re:Avaritia 1.4.2](https://modrinth.com/mod/re-avaritia/version/trZJ7YBv)，[匠魂3.11.2.166](https://modrinth.com/mod/tinkers-construct/version/kJ9LD194)，[Mantle1.11.104](https://modrinth.com/mod/mantle/version/SOJzuYxC)，[CMM主包和修复包](https://modrinth.com/resourcepack/cute-mob-models-resource-pack/version/edCc04l8)。Re:Avaritia代码MIT、资产CC BY-NC-SA 4.0；Spawn保留所有权利；匠魂/Mantle为MIT，CMM为GPL-3.0-only。第三方资产、原始源码审计副本、用户世界、日志、真实server.properties及凭证不公开上传。公开同步限本项目兼容修复源码、预设、安装/打包脚本、下载清单、内置AI和交付说明。
