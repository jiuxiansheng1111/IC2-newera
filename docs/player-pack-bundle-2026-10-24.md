# 语音与安装工具合并交付（2026-10-24）

用户要求将安装工具与语音包放在一起、清理交付文件夹无用文件，并说明使用老PCL2；本轮只处理交付与说明，完成后停止本轮工作，不延伸游戏验收或恢复定时任务。

最终正式交付为两份包、两份SHA256和README。普通包：IC2-New-Era-1.20.1-Forge-47.4.28-r41.mrpack，308,017,629字节，SHA256 11A86DAF9691925DED365D1CE8EECC44A002A6C1D88CD808CEBB30BCC442E79B。可选语音合并包：IC2-NewEra-Voice-Bundle-Win64-2026.10.24.zip，6,778,174,122字节，SHA256 484B3A21648F635ECDA2EFEBC5CC49DE0FEC929F11FCF54D03CADEBFE6E30932。两份合计7,086,191,751字节（7.09GB），合计小于10,000,000,000字节。

## 合并方式与使用

新增第一方封装脚本scripts/package-voice-bundle-1201.py，将已核对的小安装工具解包到同一根目录，内置原6,765,991,414字节Voice-Slim ZIP，以ZIP_STORED写入外层，避免重复压缩模型。合并根目录为IC2-NewEra-Voice-Bundle-Win64，Install.cmd、bootstrap、installer、install-manifest.json和内层语音ZIP都在其下。

玩家完整解压外层后直接运行Install.cmd，选择已有1.20.1/Forge47.4.28隔离版本目录即可。内层ZIP保持原样，原安装器默认查找逻辑会从同目录找到它；不再下载/配对两份附件，也不需要把安装工具搬入游戏目录。不重装Java或游戏模组。普通无语音游玩仍只需mrpack。

原TTS/RVC/Demucs共享运行环境、八角色、三语、音色/索引与翻唱均未改。内层ZIP SHA256仍为07B463DFDA60CF816FB7324903D3275EDB68488B89CE9F29F94F7B6998137F9C，与上一轮逐项核对35,915份文件的版本完全一致。本次封装60项（含内层ZIP和清单）全部实际流式回读大小/SHA256、清单与路径核对通过，没有再次把相同模型解压/推理。

## 老PCL2核对

只读Windows文件版本显示桌面Plain Craft Launcher 2.exe为2.13.0.1，没有运行启动器或读取账户设置。另从作者2.12.8.2源码核实：ModModpack支持mrpack选择及根modrinth.index.json；ModJava会搜索所选实例目录，并以runtime/java/bin等关键词继续查找。源码证据和SHA256在检查点pcl-compatibility-evidence.json，第三方代码仅作本地阅读，没有放入发布包。

参考：[旧版整合包入口](https://github.com/Meloong-Git/PCL/blob/2.12.8.2/Plain%20Craft%20Launcher%202/Modules/Minecraft/ModModpack.vb)、[旧版Java搜索](https://github.com/Meloong-Git/PCL/blob/2.12.8.2/Plain%20Craft%20Launcher%202/Modules/Minecraft/ModJava.vb)。未知更早版本未逐版验收，不宣称所有老PCL均已实测。说明补充：拖入无反应可手动选择整合包；如未找到Java17，可选择该版本IC2-NewEra-runtime/java17/bin/javaw.exe，无需另下载。

普通mrpack为同步中英文说明重新封装，新旧仅两份README条目的CRC/大小不同；所有其余条目相同，r41 JAR SHA256仍为56E584D8B03D145EB759808811138C1DF36E3C1B72480E7B94EDA29B7BCC5254。新普通包已完成492个Java文件逐项回读及原有索引/JAR/配置静态检查；没有改变游戏功能。

## 清理与边界

新两份产物先核对完整归档SHA256；再在明确的dist目录中按精确文件名回收独立Voice-Slim、独立Voice-Installer及两份sidecar，共4份、6,778,175,509字节。没有清理项目源码、构建研究检查点、外部语音环境、原始参考、存档或1.19.2实例。合并包受现有Voice-* gitignore规则保护，没有公开上传模型或产物。

没有新增或运行自动化测试，没有执行安装器、模型推理、启动器或游戏；实际安装/新共享环境播放及原功能验收待办保留。哈希与旧环境三语播放证据不等同于新环境实测。详细证据为build-isolated/player-packaging/20261024-voice-bundle下bundle-audit.json、final-delivery-audit.json、cleanup-receipt.json；普通包检查点为20261024-pcl-import-bundledvoice。此前分离三件套已被本轮两件套替代，旧文档只作历史。

交付目录另有百度网盘可能反复生成的旧包上传进度文件IC2-NewEra-Voice-Win64-2026.10.24.zip.baiduyun.uploading.cfg；只观察名称/元数据与网盘进程，没有读取配置内容，未删除该客户端状态或操作上传任务。它不属于需要分发的包，不应把它当成剩余可用旧ZIP；上传应选择新的Voice-Bundle包。
