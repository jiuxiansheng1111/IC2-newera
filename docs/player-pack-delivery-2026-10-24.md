> 历史交付记录：本页早期核心ZIP已被含Java17新版替代并按用户要求移至回收站，当前交付见 [Java17修订](player-pack-java17-2026-10-24.md)。独立语音ZIP继续有效。

# 玩家游玩测试包交付：2026-10-24 / r41

本次按用户新要求先交付可移机的安装包，桌面验收暂停在物理Esc后的世界列表状态；没有重启定时任务、操作存档或执行自动化测试。

## 实际文件

| 文件 | 压缩大小（字节） | SHA256 |
| --- | ---: | --- |
| `dist/IC2-NewEra-Player-1.20.1-r41-2026.10.24.zip` | 15,817,559 | `3C7A12B3ECE4910A70F48A978B91A9B22AFF173506C8C391AB92B688C569713D` |
| `dist/IC2-NewEra-Voice-Win64-2026.10.24.zip` | 11,793,033,269 | `16DB8E93E3262BE396BEFA5EBEF2A4626873CB3A2C02055C312B0D6BF85E5371` |

对应`.sha256`文件同目录。核心包约15.8MB，语音包约11.8GB，语音解压约18.62GiB，目标盘还需至少10GiB剩余空间。两个ZIP一起交给测试玩家；只解压核心包，语音ZIP保持压缩。

## 安装

1. PCL2/HMCL新建独立Minecraft 1.20.1 / Forge 47.4.28 / Java 17实例，开启版本隔离；不要导入1.19.2世界。
2. 两个ZIP放在一起，展开Player，双击`Install.cmd`，填写含同名Forge版本JSON的版本目录。Windows系统未启用长路径时选较短目录；安装器在修改前报告具体不符合项，不修改系统注册表。
3. 安装器用包内CPython运行，不要求额外安装Python。首次需联网下载13个锁定构件；核心ZIP只内置本项目JAR，第三方JAR按作者来源取得并检查size/SHA256。下载不可用时可将同名同哈希构件放入核心目录`downloads/`再运行。
4. 安装回执在目标实例`IC2-NewEra-install/install-state.json`。安装器完成后由玩家从启动器启动，新建测试世界。不会自动启动游戏；不会复制账号、API Key、存档或原有LLM设置。

## 独立语音内容

82,906项文件；19,998,258,371字节原始资源。三个完整Python布局保留TTS 3.11.9、RVC 3.12.15、翻唱3.11.9各自依赖，翻唱通过相对.pth复用TTS GPU依赖；没有复制venv启动重定向器或原环境pyvenv.cfg。八角色模型与已有可选索引、基础GPT/SoVITS、BERT/CNHubert、单份必要参考音频、日语pyopenjtalk、两份英语NLTK资源、htdemucs checkpoint、FFmpeg/FFprobe及FFprobe七项共享DLL均随附。没有复制整套训练录音、QQ工程配置或音乐帐号。

运行配置、GPU锁、模型和Python路径均相对`tools/robot_voice/`，第一方协调服务只监听本机9881，由游戏按需启动。新实例默认语音开启；三语在机器人回复设置选择。自由LLM聊天仍由玩家在游戏内自行填入其模型服务，包内不含制作人的密钥。当前RVC要求Windows x64和NVIDIA CUDA显卡；包内TTS/RVC为CUDA 12.8/11.8构建，显卡与驱动兼容性需在玩家机器确认。真实麦克风需要Windows对应语言识别器，尚待验收。

## 证据范围

- 第一方JAR仍为41，SHA256 `56E584D8B03D145EB759808811138C1DF36E3C1B72480E7B94EDA29B7BCC5254`，未重建/改动游戏源码或更换活动依赖。
- 核心ZIP60项逐项读回哈希，安装器与冻结源码相同，便携CPython标准库入口齐全；绑定的语音大小/哈希匹配实际ZIP。
- 语音ZIP82,906项加独立清单，目录、大小、重复项及配置静态审阅通过；27处配置路径、5份Python路径文件在包内闭合，七个共享音频DLL齐全，未夹带旧venv配置/direct_url.json/账号或存档。
- IC2R作者构件HEAD读取返回200，最终GitHub官方Release资产内容长度7,693,644，与锁定构件一致；未输出签名查询参数。
- 原同一电脑此前三语两句TTS/RVC/客户端播放证据保留；本次没有运行新安装器、任何模型推理、游戏或自动化测试。不能将封装完整称为已在另一台电脑实测。
- 机器人太空/跨维、全生产线和锅具、真实麦克风/LLM/联机等未验收项目列在包内`PLAYTEST-STATUS.txt`，后续继续原任务。

构建与封装审计在`build-isolated/player-packaging/20261024-r41/`：`voice-archive-audit.json`、`voice-portability-review.json`、`player-archive-audit.json`、`delivery-final-review.json`。中英文安装说明、Minecraft非官方声明、IC2原团队来源及权利边界随包提供。自有代码MIT与角色/音色非商业测试范围分开；第三方模型、参考音频和部分二进制公开再分发授权未确证，附件不进入公开GitHub，两个大ZIP已忽略。GitHub同步仍待完成，只可发布本项目可分享内容。
