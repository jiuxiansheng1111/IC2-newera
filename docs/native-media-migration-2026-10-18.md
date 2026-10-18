# 原生点歌、机器人对话与独立语音迁移

2026-10-18，用户要求把功能迁进 IC2 New Era，不再经过 QQ，并同意首次启动隔离专服。后续又明确将独立语音模型的启动空闲内存门槛改为 **3.5 GiB**。本记录是当前实现与验收依据；此前 8012/AstrBot 桥接交付保留为历史。

## 已完成的迁移

手机点歌的公开搜索、公开歌单导入、手工歌单解析、完整音源获取、LLM 意图解析和真实候选选择，均在游戏服务端的 Java 代码中执行。机器人文字对话同样直接使用模组内模型传输，八份原项目自有角色设定已作为模组资源迁入。手机界面、个人歌单、权限、电量和 Minecraft 分块传输沿用此前实现。

音乐不再需要 QQ、AstrBot、Python、8012 或 FFmpeg。公开 MP3 由 JLayer 1.0.1 在客户端逐帧解码为 PCM16，复用原有暂停、音量、取消和文件清理。原始库以 Forge Jar-in-Jar 捆绑，未复制库实现到项目源码，许可证及准确源码链接随包提供。只获取平台允许匿名访问的完整公开音源，付费/会员/试听限制仍会明确拒绝。

角色语音生成需要用户现有的 GPT-SoVITS/RVC 模型与环境，已迁为本项目 `tools/robot_voice/` 下的独立可选工作进程，不再导入 QQ 的 `app.*`、配置或 AstrBot。模型和虚拟环境作为外部资产显式引用，不放进游戏 JAR；参考录音、权重和环境可以移动到其他目录后更新独立路径配置。语音运行时仍调用已有 FFmpeg 完成必要的音频转换，音乐播放本身不调用它。

```text
手机音乐 / 机器人文字
  → 已认证的 Minecraft 连接
  → 游戏服务端：玩家权限、电量、Wi-Fi / 机器人距离
  → Java 公开音乐接口 / 共用 LLM HTTP 传输
  → 对应玩家的歌单、回复和音频分块

可选角色语音
  → 同一游戏服务端
  → 同主机 127.0.0.1:9881 独立模型工作进程
  → 一次性 TTS 完全退出 → 一次性 RVC → PCM WAV
  → Minecraft 分块 → 请求玩家的客户端播放
```

内网穿透只需转发 Minecraft 游戏端口（默认 TCP 25565）；9881 保持本机回环。远程玩家不需要 Python、模型、音源 URL、API Key 或独立的 HTTP 穿透端口。

## 单人游戏

原来的助手模型设置原样保留，机器人文字和点歌直接复用它。机器人页面的“模型设置”按钮打开原有设置页；点击发送或点歌才调用模型。无需重载 AstrBot。导入歌单和按编号播放公开歌曲不调用 LLM；自然语言点歌需要启用模型连接。

手机配方仍为中后期电子材料加工机路线，使用机器人控制核心、工业 SSD×2、GPU、UESC、复杂电路×2和高级充电宝，2400 tick、256 EU/t。音乐仍需携带有电手机和有权限的交换机 Wi-Fi；背包手机可支持自动下一首。

## 独立服务器

Forge 会生成 `config/ic2newera-media-common.toml`。专服默认不启用模型，由服主填写自己的配置：

```toml
[server_ai]
enabled = false
baseUrl = ""
model = ""
apiKey = ""
timeoutSeconds = 60
maxTokens = 1024

[voice]
workerUrl = "http://127.0.0.1:9881"
```

填入服务商支持的 HTTPS API 地址、模型 ID 和必要凭据后，显式启用。HTTP 仅允许本机回环模型服务。该文件采用 Forge `COMMON` 类型，不同步给玩家；不要改成自动同步的 `SERVER` 类型。没有从旧 QQ 或玩家配置读取/恢复/复制密钥。多人模式下机器人页面会说明模型由服主管理，个人助手仍使用原个人设置。

运行机器需要 Java 17、Minecraft 1.19.2、Forge 43.5.0及相同 BOTH 模组集。完整包为 `build/libs/ic2newera-0.1.0-dev-all.jar`，`releases/ic2newera-0.1.0-dev.jar` 是它重命名后的稳定安装入口。无 `-all` 的构建 JAR 未包含 MP3 库，不作为完整交付包。媒体通道现为版本 2；客户端和服务器应一起更新。

`scripts/prepare-dedicated-server.ps1` 已同步七个服务端模组和独立语音工具，保留既有 EULA、模型配置、服务器属性和实际语音路径配置。测试服务器只使用 `build-isolated/dedicated-server-20261018/world/`，没有使用玩家原存档。首次启动与重启验收均限制为 2 GiB 堆。

## 独立语音与内存门槛

入口：

```powershell
.\tools\robot_voice\start_voice.ps1 -CheckOnly
.\tools\robot_voice\start_voice.ps1
```

`ic2newera-voice-runtime.json` 只允许语音环境、模型/索引、参考录音和采样参数，没有 LLM、QQ 登录或 `.env` 字段。基础 TTS 使用已核实的丛雨权重和匹配参考录音，随后转换目标角色的 RVC 音色；不使用外部 YAML 当前另一个角色的配置冒充基础声音。当前路径指向本机已有资产，换机器时必须按实际路径设置。

用户确认后的门槛为：启动前至少 **3.5 GiB 空闲物理内存**，运行期间至少 **1.5 GiB**；系统提交量 85%、磁盘剩余 10 GiB、一次只运行一条语音的保护保留。协调进程不常驻模型；缓存仅保存有界的小 WAV。TTS 退出后才允许 RVC 加载，取消/超时只回收本次创建的进程树。GET `/health` 显示门槛、文件存在、资源情况与忙闲，文件存在不能代替真实推理验收。

首次真实推理还定位到日语前端缺少 `pyopenjtalk`。已通过明确单包适配引用现有 CPU 环境的 `pyopenjtalk-prebuilt 0.3.0` 和本地字典，没有把整个 CPU site-packages 加入路径，没有安装依赖或修改共享环境，保留准确日语参考与转录。主 TTS Python 的轻量前端核对后，独立 9881 接口于 10:24 完成八角色真实合成。换机器时须提供匹配 TTS Python/系统/架构的前端 C 扩展、字典与现有语音资产，详见 `tools/robot_voice/README.md`。

八份样本均为“你好，可以开始工作了。”、44.1 kHz 单声道 PCM16、3.18 秒、280,554 字节，角色响应头匹配，八份哈希不同。高松灯首条 36.906 秒，余下七条 11.594–12.500 秒；实际只加载一次基础 TTS，退出后逐个加载八套 RVC。所有工作进程均已退出，协调器空闲且不驻留模型。阶段快照最高工作集约 2.705 GiB、最高私有字节约 4.712 GiB，观测到的系统最低空闲约 2.016 GiB；这些是阶段采样，并非整棵进程树连续测量的绝对峰值。协调器查询时工作集约 4.25 MiB。

Java 语音 HTTP 上限 300 秒，服务器任务 330 秒、客户端无进展 350 秒；工作进程完整请求上限 250 秒。角色、PCM16、90 秒、8 MiB 与 SHA-256 分块校验保持。语音仍为默认可选功能，失败保留文字。

## 已完成验收

| 项目 | 当前证据 |
| --- | --- |
| Java 构建/打包 | 最终离线 build -x test 成功，29 秒。完整 JAR 1,895,385 字节，SHA-256 `4DCEB9D0FF3154480DCD7DED7CCA539B57C263B0D6A26BCD753C2218B747D6B1`。544 份 JSON/mcmeta 可解析，8 份角色设定，244 条工艺路线。 |
| MP3 依赖 | Jar-in-Jar 元数据指定 JLayer 1.0.1，嵌入文件 SHA-256 与 Maven Central 原始库一致；专服实际识别第四个嵌入依赖。许可证和源码链接随包。 |
| 实际安装 | 2026-10-18 10:06:45 PCL 安装完成，只更新新纪元一个 JAR；专服包随后同步同版。 |
| 纯 Java 公开音乐 | 实际公开搜索取得 10 个候选；热歌榜真实 200 首按顺序导入前 100；手工去重示例保留 2 首。实际下载完整《Pretty》MP3，5,028,928 字节，未用 QQ/Python/FFmpeg。 |
| 新 MP3 播放器 | 使用真实 Windows Java Sound 输出线路、软件音量 0，完整播放 314.291 秒。暂停位置保持、继续推进、取消与完成均删除播放器持有的临时文件。此处只确认静音线路写入，没有试听验收。 |
| 模型传输与角色资源 | 本地真实 HTTP/SSE 夹具调用生产代码，8 份角色设定、流式回复、认证失败、真实曲目候选限制通过。外部真实 LLM 未在这轮调用，不能把夹具写成模型端到端验收。 |
| 隔离专服 | 用户同意 EULA 后首次 09:46:47 到达 Done，数据包重载与 8 个角色生成/回读成功。迁移新包 10:08:19 再次 Done，8 个角色重启持久化回读成功；均正常保存停止，没有启动客户端或改玩家旧存档。 |
| 独立语音 | 3.5 GiB 门槛生效，修复日语前端依赖后，独立 HTTP 接口八角色 TTS/RVC 全部成功，PCM 格式、角色头、时长、非静音与资源退出校验通过。独立工具已同步专服副本，八角色路径检查无缺失。 |
| 新版语音播放器 | 八份新样本直接从 PCL 已安装 JAR 加载 `MediaAudioPlayer(true)`，通过真实 Windows Java Sound 线路，软件音量 0；全部完成并清理持有的临时文件。报告保存实际类来源。构建类与发布类因 Forge 重映射字节不同，不把两者哈希写成相同。仍不等于游戏内可闻或音色质量验收。 |

证据统一在 `build-isolated/native-media-migration-20261018/`：`package-audit.json`、`build-final.log`、`native-music-result.json`、`native-model-fixture-result.json`、`server-first-boot-result.json`、`server-native-reboot-result.json`、两次专服日志、`standalone-eight-voices.json`、八份样本/阶段审计、`standalone-voice-memory-result.json`、`native-voice-playback-result.json`、`native-voice-player-identity.json`。原日语依赖失败保存在 `voice-failure-missing-pyopenjtalk.json`，未覆盖证据。安装回执为 `build/deployment/pcl-installation.json`。最近独立语音审计为 `tools/robot_voice/runtime/last-request.json`。

既有十小时 OGG/WAV 单 JVM 检查已于 2026-10-18 10:47:21（北京时间）完成：60 轮通过、0 失败，实际持续 36,000.002 秒。heap used、direct buffer 与 JVM 线程范围和末值见 [手机音乐/专服交付](phone-music-server-delivery-2026-10-18.md) 及 `build-isolated/native-media-migration-20261018/native-media-delivery.json`；这些是 JVM 指标，不是系统 RSS。该检查仍只覆盖迁移前 OGG/WAV 组件的短生命周期静音循环，不能覆盖新 MP3、模型迁移或独立语音。真实游戏 GUI、玩家可闻效果、外部 LLM 点歌、两名玩家权限隔离和实际公网隧道仍须实机验收。
