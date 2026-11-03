# r61：UltraTerraForged、Grove与石英EMC

日期：2026-11-03。用户最终选择UltraTerraForged 1.20.1 Forge，允许服务器新建地图，并要求清理旧发布版本与之前的更新附件。旧地图与完整备份保留；材质、光影只安装客户端。

## 实现与构建

- 地形为维护者官方`UltraTerraForged 1.0.3+1.20.1 Forge`，注册ID `ultraterraforged`；没有混装原版ReTerraForged。官方Maven发布提交为`d50a181c2f4e018aa18e401c02fa6f43e8faeedb`，构件同时通过发布分支SHA-256、SHA-512、ZIP CRC与Java17字节码核对，强制依赖仅Forge47.1+与MC1.20.1。完整MIT版权通知随客户端与服务器分发。
- Grove为作者发布的Soartex Grove 1.0.4，原ZIP大小53,102,397字节，pack_format=15。客户端默认选择`file/Soartex Grove 1.0.4.zip`；ITT 3.2与其余既有依赖保留。Grove通过作者CDN清单下载，不嵌入或更改第三方材质ZIP。
- 客户端ProjectE配置合并石英94 EMC并保留原71项。配方仍为每份灵魂沙12.5%产4石英、2%产1金粒。按灵魂沙49、金粒227 EMC计，平均产出`0.5×94+0.02×227=51.54` EMC，平均利润约5.18%。用户明确保留水洗和沙子转灵魂沙配方，未处理另外的便宜沙子收益路径。
- EMC唯一配置清单为迁移工程`scripts/projecte-emc-1201-balance.json`；PCL合并安装器与服务器打包器都读取它，不覆盖已有无关自定义条目。与游戏JAR中的中间物EMC零值IMC兼容。
- 新纪元内置AI同步地形、材质和EMC说明，纠正目标Create版本为6.0.8。与r60逐个JAR成员比较，仅`data/ic2newera/ai/migration_runtime_zh.md`内容变化；没有改动Java类、协议、ProcessGraph或配方。
- 最终构建使用`gradlew.bat '-Dnet.minecraftforge.gradle.check.certs=false' build -x test --offline`，37秒成功；未运行自动化测试，未启动本地Minecraft。归档CRC、成员哈希和依赖元数据为静态交付核对。

## 本地安装与包体

PCL安装器实际更新2个文件，共38个客户端模组。Grove已安装并设为默认，安装后SHA-256与作者原包一致。客户端仍待用户自行启动验证材质、光影和联机。

| 文件 | 大小（字节） | SHA-256 |
| --- | ---: | --- |
| `IC2-New-Era-Windows-1.20.1-r61-Grove.mrpack` | 308,416,808 | `98E6FAAA34FC3700643843606653668A358E6A67138321C26D460BE2E16754C6` |
| `IC2-New-Era-Server-1.20.1-r61-UltraTerra-Voice.zip` | 365,766,222 | `D87DE8595233D6523C5C55D1531FB58AD74BF2C3BF61A37933A902C0CC179A3B` |
| `IC2-New-Era-Server-1.20.1-r61-Update.zip` | 11,358,925 | `FF94D7195A7976C302ECAF2E44267F32FD2C885DA3CFF4AAD97B71A93A75FA9D` |
| `ic2newera-0.2.0-migration1201-all.jar` | 10,470,342 | `AD47906AB753069F6D4299E3CA5D4332C78F7300C5BFE0217CD74604D137F0C6` |

UltraTerraForged JAR为1,884,145字节，SHA-256 `E1F14634F13968EB14B12644E8D92932D26992EE44DADBB587A67C28164AFA82`。Grove ZIP SHA-256 `33CF7B9E47580A9138123F0377B4BBB4A031757E1B389E00485ECFD743547EAB`。

Windows包含38个模组，36个原有第三方模组、ITT和Grove仍按清单下载；新纪元及MIT许可的UltraTerraForged JAR嵌入。完整服务器含27个模组、Forge运行文件、原固定语音JAR和启动参数，128段预录语音保留。更新附件只有两个模组JAR、MIT通知和说明，不含材质、光影、世界或配置覆盖。

## 指定服务器部署

- 停服前`list`返回0/20；正常保存关闭后创建完整备份`before-r61-ultraterra-20261103`，面板完成校验SHA-1 `3efbb44fc6f0901dbd32ef6c0e1fbe256d339709`。根目录无`.pteroignore`文件。
- 服务器原ProjectE文件先备份，再只追加石英94 EMC，保存后回读为72条；重新加载映射。原71项保留，实际数值仍以服务器映射和玩家提示为准。
- 上传r61更新ZIP到暂存目录后下载回读，SHA-256与本地交付一致；核实归档内没有resourcepacks或shaderpacks。正常停服期间解压并移动两个JAR进活动mods，原r60 JAR留在暂存目录作为回退副本。
- 活动服务器29个模组，完整包外原有Cloth Config和Xaero保留；新纪元只有一份。语音JAR、启动参数、内存、用户配置和旧世界不变。
- 只修改server.properties的两项：`level-name=world-ultraterra-r61-20261103`、`level-type=ultraterraforged:ultraterraforged`；保存后全文回读确认其他属性完全一致。原`world`目录未删除。
- 北京时间15:24:31写入新世界的`ultraterraforged-preset.zip`，15:25:58.204记录`Done (75.873s)`。首次整段模组加载耗时较长；随后正常保存关闭，未使用强制停止。重开后15:39:53的`list`返回0/20在线，15:40:13的`datapack list enabled`返回37个启用数据包，明确包含`file/ultraterraforged-preset.zip (world)`。
- 语音日志继续复用本机9881协调服务，语音JAR和参数未改动。仍有r60已记录的Valkyrien Skies接口Mixin、专服侧PoseStack两条非致命ERROR，以及可选兼容、更新检查和启动时的WARN；没有把本次正常启动解释为零日志告警或全部玩法通过。未进行本地游戏画面、多人实际加入、机器运行或听音验收。

## 清理与公开同步

已从本机dist清理9项旧包及校验文件：5项过时附件删除，两份r60复现构建输入及校验文件转入私有构建备份；dist只展示r61与仍使用的独立JAR。远端`update-r49`、`update-r60-20261030`、`backup-before-r49-20261029`、`backup-before-r60-20261030`移入面板回收站，24小时内可恢复；旧世界与面板完整备份保留。公开同步包含本项目源码、内置AI、构建/安装/打包清单及产物校验记录。完整all.jar内含第三方嵌套运行库，因此保留本机交付，不作为这次公开同步文件；第三方模组、材质、语音资产、真实服务器配置、日志和世界也不上传。

官方来源：[UltraTerraForged发布](https://github.com/Pandaismyname1/ultraterraforged/releases/tag/1.0.3%2B1.20.1)，[Grove 1.0.4发布](https://www.curseforge.com/minecraft/texture-packs/grove-vanilla/files/5043512)。
