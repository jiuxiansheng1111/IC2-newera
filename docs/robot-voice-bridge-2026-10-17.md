# 机器人可选语音桥：2026-10-17

**后续迁移已替代本页桥接操作：** 用户要求不经过 QQ。当前机器人文字与点歌在 Mod 内执行，语音入口为本项目 `tools/robot_voice/start_voice.ps1`，回环端口 9881；启动门槛已按用户要求改为 3.5 GiB，原 LLM 配置不变。当前使用方法、真实运行进度与证据见 [原生迁移交付](native-media-migration-2026-10-18.md)。本页保留旧桥接设计与交付历史，不再要求重载 AstrBot。

后续采用一次性工作进程；早期 TTS/RVC 执行与内存实证见[语音内存与验收](robot-voice-memory-2026-10-17.md)，这些记录是历史音频后端证据，不代表当前 Minecraft 端到端验收通过。2026-10-18 更新了游戏媒体传输架构：ChatRobot 请求由游戏服务器访问服务器主机的 loopback 桥，语音音频再经 Minecraft 分块数据包单播给请求玩家。当前 AstrBot 实例尚未重载新的 music 路由，6185 管理面板登录返回 401；新通道在线状态待正常重载和用户回应后再核实。

本轮源码在已有 ChatRobot 文字对话后增加可选语音。文字先完整显示；语音合成、传输或播放失败只更新语音状态，保留文字。玩家默认关闭语音，可在机器人页面独立开启；设置保存在本机游戏实例的 `config/ic2newera-robot-voice.json`。模型、权重、语言与 LLM 沿用 ChatRobot，不把配置或密钥放进存档、服务器包或网络报文。

## 实际项目与入口

- 本地 ChatRobot：`C:/Users/罗斌/Desktop/qq-chatrobot-all/qq-chatrobot`。
- 已有语音环境：`C:/Users/罗斌/Desktop/qq-chatrobot-all/qq-chatrobot-voice`，含 GPT-SoVITS 与 RVC。
- [公开项目仓库](https://github.com/jiuxiansheng1111/qq-chat-robot)已联网核实为公开；本轮改动未上传，实际使用本地源码。
- 详细服务协议和启动说明：`qq-chatrobot/docs/MINECRAFT_ROBOT_BRIDGE.md`。
- 按需运行：语音生成运行于承载 Minecraft 服务端和 ChatRobot 的主机，依次启动一次性 TTS 与 RVC，前一进程退出后再加载下一阶段；不要求每位客户端安装 Python，也不需要常驻 9880 侧车。QQ 仓库的 `scripts/start_minecraft_voice.ps1` 默认只检查并记录非秘密运行路径，显式 `-PersistentSidecar` 才启动旧诊断侧车。内存方案见该仓库 `docs/MINECRAFT_VOICE_MEMORY.md`。客户端和服务器共用 Minecraft 模组版本；客户端只负责可选设置与本机音频播放。

## 源码链路

玩家聊天时，客户端通过 `MediaPackets` 把文字历史送到游戏服务器；`MediaServer` 检查机器人归属、距离和电量，再从服务器主机访问 `http://127.0.0.1:8012/minecraft/bridge/chat`。文字回复只发回请求玩家。客户端 `RobotVoiceClient` 后续只发送语音请求和机器人 ID，不上传任意文本；服务器从该玩家最近授权的回复取文本，再调用服务器主机上的 `/voice` 接口。完整音频以有限大小的 Minecraft 分块数据包只传给请求玩家，客户端不直接访问 8012，也不要求安装 Python。桥复用现有 `Settings` 内存副本；不修改原有 LLM、QQ 语音总开关或 `.env`。

八个角色当前只有 RVC 模型，不能直接读文字。实际路线为当前默认 GPT-SoVITS TTS（丛雨）生成基础语音，再调用已有 `scripts/run_rvc_model.py` 转换目标角色音色，使用既有 CUDA、模型/索引、GPU 锁和资源保护。独立 TTS 角色可直接使用对应 TTS；账号受限音色不会在游戏中解除授权。缺基础 TTS、参考录音、模型、CUDA或可用资源会明确回退文字，不伪装为其他音色。

服务返回不超过 8 MiB/90 秒的 PCM16 WAV。服务器校验协议、角色、大小及 WAV 标识后通过分块中继；客户端 `MediaClient` 校验块序号、长度和 SHA-256，再由 `MediaAudioPlayer` 后台播放。声音只在发出请求的玩家本机播放，不广播给多人，也不模拟空间声源。`RobotScreen.tick()` 每 tick 同步主音量与语音音量，音乐播放每 tick 同步主音量与音乐音量；取消对话、离开页面或关闭语音会取消当前传输/播放。

当前链路实现位于 `media/MediaPackets.java`、`media/MediaServer.java`、`client/media/MediaClient.java`、`client/media/MediaAudioPlayer.java` 和 `client/robot/voice/RobotVoiceClient.java`。服务器媒体请求固定 loopback 地址，不接受客户端给出的 URL；包按连接发给请求玩家。可选开关不改变服务器端机器人所有权、聊天能耗、机器人动作或工业网络权限。

## 当前验证边界

- QQ Python 源码的 `py_compile`、Ruff 与独立启动脚本解析历史检查通过；不代表当前运行中的 AstrBot 已加载最新桥接端点。当前 6185 管理面板登录返回 401，music 路由尚未重载；需正常重载后由主任务继续端到端核验。没有新增/运行测试；最终 Java 构建与安装以主任务最新回执为准。
- 主 TTS 环境为已有 `.venv`，torch 2.7.0+cu128；RVC 为已有独立环境，torch 2.7.1+cu118；两者检测到 CUDA。CPU备用环境存在 torchcodec 解码问题，未作为交付依赖。
- 已为主环境补充其缺失的 `opencc-python-reimplemented==0.1.7`，与已有 CPU 环境相同，下载 481 kB 依赖，无模型下载。自有启动包装器的显式离线参数强制使用已安装 fast-langdetect 捆绑 lite 模型，阻止 split-lang 尝试大模型下载。
- 真实基础 TTS 短句“你好，我是爱音。”已生成：**这是丛雨基础音色，不是爱音音色**。PCM16 mono 32 kHz，1.42秒、90,924字节，暖启动生成2.047秒，SHA-256 `2ea4231b810ae91281221539cf021037f21954015afc3db2d750f87fec3b75b6`。
- 完整爱音链在基础 TTS 后被既有资源保护拦住：可用物理内存393,531,392字节（约0.37GiB），RVC启动门槛4GiB。未降低保护、未关闭玩家程序。只关闭本次创建的临时TTS后可用约3.59GiB，仍低于门槛，不再推理。
- 样本与实证：`build-isolated/robot-voice-20261017/sample-base-murasame.wav`、`base-generation-audit.json`（基础成功）、`generation-audit.json`（角色链未完成）、各侧车日志。临时侧车已关闭。
- 早期记录中的基础 TTS 样本和资源保护门槛仍是后端历史证据；本次媒体传输不等于新 Minecraft 包已在多人环境运行。当前桥接端点实际加载、游戏服务器到服务端 8012 的连接、请求玩家单播、内网穿透、Java Sound 设备和游戏内音量调节仍未验收。未改模型/API 设置或密钥，没有启动/关闭游戏，也未上传。

健康诊断分别报告配置存在、模型文件存在与按需运行环境，始终不把这些标志当作推理成功。游戏按需语音无需常驻TTS侧车，需在足够可用内存的环境实际发送短消息验收。

## 2026-10-18 最新回复授权与重试边界

服务端把可合成语音绑定到玩家最近一次成功取得的机器人文字回复：授权有效期为 5 分钟、同一回复仅可尝试合成一次；玩家开始新对话时，旧回复立即失效，并取消该玩家未完成的旧语音任务或音频传输。语音合成失败、取消或服务繁忙时，这次尝试仍会消耗一次机会；当前 UI 没有重试同一回复的入口，需先完成新对话取得新回复。该行为来自服务端源码策略，尚不代表游戏内多人/失败恢复实机验收。
