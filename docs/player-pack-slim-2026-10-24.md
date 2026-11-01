# 玩家包精简与PCL导入交付（2026-10-24）

## 2026-10-24 最新：语音与安装工具已合为一份，交付文件夹清理完成

用户最新要求合并安装工具与语音、删除交付文件夹里没用的文件，并说明使用老PCL2。当前正式交付仅包括普通mrpack（308,017,629字节，SHA256 11A86DAF9691925DED365D1CE8EECC44A002A6C1D88CD808CEBB30BCC442E79B）、Voice-Bundle合并ZIP（6,778,174,122字节，SHA256 484B3A21648F635ECDA2EFEBC5CC49DE0FEC929F11FCF54D03CADEBFE6E30932）、各自SHA256及说明；两份合计7,086,191,751字节（7.09GB），合计低于10GB。语音完整解压外层后直接运行Install.cmd并选择隔离Forge版本目录，内层Voice-Slim ZIP与工具已放在一起，不再下载/配对分离附件。普通无语音游玩继续只用mrpack。

只读核实桌面PCL2为2.13.0.1，另核对官方2.12.8.2支持mrpack和所选版本目录Java搜索；未知更早版本不冒称已验收。中英文说明加入手动选择整合包、必要时手动选择实例内Java17的方法。普通包仅两份README改动，r41游戏JAR及其他条目保持；合并包内层语音ZIP与上一轮35,915文件哈希核对版本完全一致，八角色/三语/翻唱不改，外层60项回读通过。

已将独立Voice-Slim、独立安装工具及两份旧校验文件共4份（6,778,175,509字节）移入Windows回收站，仅清理dist交付目录。项目源码、原环境/参考、旧实例和存档保持，没有自动化测试、实际安装/推理/游戏操作或GitHub上传，定时任务不恢复。另外观察到百度网盘正在运行，并反复生成旧包名称的.baiduyun.uploading.cfg临时上传状态；未读取其内容，保留供客户端任务使用，未停止上传。原验收待办仍保留；用户要求“就这样吧”，本轮交付完成后停止扩展。详情[语音合并交付](player-pack-bundle-2026-10-24.md)，证据build-isolated/player-packaging/20261024-voice-bundle。下方三件套为历史。

用户要求保留八角色和中日英、运行环境去重、压缩包必须小于10GB，并让不带语音的普通包直接拖入PCL2。当前普通游玩不依赖本地语音后端；语音是另装的附件。自动定时任务仍保持删除。

## 实际导出

| 文件 | 字节数 | SHA256 |
| --- | ---: | --- |
| IC2-New-Era-1.20.1-Forge-47.4.28-r41.mrpack | 308,017,178 | 21D1A90C7DD0C61558DC8A72F6B2516097B875B4A310D849725A1FD69B7DA0A3 |
| IC2-NewEra-Voice-Slim-Win64-2026.10.24.zip | 6,765,991,414 | 07B463DFDA60CF816FB7324903D3275EDB68488B89CE9F29F94F7B6998137F9C |
| IC2-NewEra-Voice-Installer-Win64-2026.10.24.zip | 12,183,870 | D80DF155AF26E0CFCC680E62E8F417E17F4CF738FD816EEFB7C498FA10A08F16 |

三份合计 **7,086,192,462字节（7.09GB）**，同时低于10,000,000,000字节；语音单包6.77GB，比旧11.79GB减少5,027,041,855字节。语音解压后为10,624,079,021字节，安装占用不等于压缩下载大小。各份SHA256 sidecar已生成。

## 普通游戏包

`scripts/package-pcl-import-1201.py`生成Modrinth v1 `.mrpack`：根目录`modrinth.index.json`、Minecraft1.20.1/Forge47.4.28依赖声明、13项作者HTTPS下载地址与SHA1/SHA512/大小。第三方模组JAR不复制进包；自有r41 JAR位于overrides/mods，SHA256仍为56E584D8B03D145EB759808811138C1DF36E3C1B72480E7B94EDA29B7BCC5254。

包内携带Temurin17.0.20+8的492份未修改文件，以及许可证和完整匹配源码；Java位于实例IC2-NewEra-runtime/java17，源码和附加说明在其外，保持未来安装器的目录归属核验。PCL实例配置使用VersionArgumentJavaV2=2在实例内找Java并开启隔离，不修改启动器账户、全局Java或系统环境。含中文options、62项EMC零值、语音enabled=false及中英文说明。

玩家直接将mrpack拖入支持该格式的PCL2，首次导入联网下载依赖即可；普通包不运行Install.cmd，不需要语音Python、Torch或CUDA模型。PCL行为依据此前已取得的官方源码审阅，**尚未执行实际导入或启动验收**。

## 可选语音附件

`scripts/player-pack/collect_voice_slim_payload.py`保留原TTS环境的CPython3.11.9/Torch2.7.0+cu128/torchaudio，RVC与Demucs改用同一解释器。旧TTS/RVC分别携带CUDA12.8/11.8及不同Python，是大体积的主要来源；旧翻唱还有独立Python。新包移除RVC的cp312及第二套Torch/NVIDIA库、翻唱单独解释器和未使用网页工作台/ASR工具。没有修改外部QQ语音工程及原环境。

RVC需要的FAISS1.15.1、Parselmouth0.4.7补入作者公布的cp311 Windows x64轮子并锁定SHA256，不复制原cp312二进制；保留RVC进度/CUDA graph/FAISS支持模块。Demucs只合并独有包，不覆盖共享Torch/numpy/transformers版本；适配第一方configuration.py使旧venv与新便携根目录都能发现Demucs。八音色、已有五索引、日语字典、英文NLTK、基础TTS与翻唱模型、Torch DLL/头文件均保持。CPython标准库中的venv重定向模板仍保留，但不是独立运行环境，三个worker实际都指向同一portable/python-tts/runtime/python.exe。

轻量Voice-Installer ZIP为12.2MB，携带隔离的安装引导Python与四个第一方脚本，不包含Java或模组。Install.cmd可在普通目录运行，选择已导入的Forge版本目录后安装同目录语音ZIP，并仅将该实例语音开关设为true；不覆盖额外/变更的自定义语音文件，也不按临时文件名猜测归属删除用户文件。新实例之外的旧语音目录需要玩家另行处理。原平台NVIDIA/驱动条件保留，新共享环境并未推理验收。

## 已核对与未验收

已完成：10份第一方/封装脚本AST语法审阅；普通包全部492份Java文件回读、索引及自有JAR核对；语音35,915份文件实际解压读取逐项SHA256；8,851份模型/语言/关键Torch文件与旧包精确对照；官方cp311轮子文件逐项对应；八角色/三语/翻唱与同一解释器配置；安装工具逐项回读及不含Java/模组；最终三份完整归档SHA256与总字节预算。

没有新增或运行自动化测试，没有运行安装器、模型推理、启动器、游戏或麦克风，没有触碰存档。旧环境中日英各两句话的实际播放证据仍有效，**不能等同于新共享环境已播放**。既有机器人/工业/锅具/太空等未完验收仍见PLAYTEST-STATUS.txt；没有把打包完成称为整个项目全部完成。

## 清理与交接

新三份产物先核对哈希，再将旧含Java核心ZIP、11.79GB语音ZIP及各自sidecar共4份、12,108,996,687字节移入Windows回收站。原模型、运行环境、Java源缓存、第一方源码、旧1.19.2实例/世界和原始参考未删。此前桌面IC2_New_Era_Codex_Setup.md的回收状态不恢复。

完整证据：build-isolated/player-packaging/20261024-voice-slim/exports-integrity-audit.json、voice-archive-audit.json、original-voice-files.json、superseded-export-cleanup.json；普通包审计在20261024-pcl-import，轻量安装器审计在20261024-r41-slimvoice。原完整语音文件清单留在本地检查点，便于旧ZIP回收后复查。原始语音资产和运行时分发文件均已gitignore；本轮未上传GitHub。自有程序仍MIT，第三方权利/非商业范围未被本次压缩更改。
