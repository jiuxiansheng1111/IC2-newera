# 独立可选机器人语音运行时

这套本机运行时由 IC2 New Era 自己启动，不经过 QQ、AstrBot 或 8012。Minecraft 服务端请求 `http://127.0.0.1:9881/voice`，再经已有游戏连接把音频只发给请求玩家。朋友无需运行 Python；独立服在服务端主机准备本目录和已有模型环境。只转发 Minecraft 游戏端口，9881 保持 loopback，不开放远程 HTTP。

八个角色目前使用已有 RVC 音色。RVC 不能把文字直接变成语音，因此每条请求按“丛雨基础 GPT-SoVITS TTS → 等待整个 TTS 进程退出 → 本次角色 RVC → 轻量 PCM 转换”执行。HTTP 协调进程只用 Python 标准库，不导入 Torch、不缓存模型。没有 LLM、QQ 登录、配置读取或消息发送代码。

## 配置与已有资产

`ic2newera-voice-runtime.json` 是独立、非秘密的实际配置，`ic2newera-voice-runtime.example.json` 是同结构示例。允许显式列出已有 Python 虚拟环境、GPT-SoVITS/RVC 根目录、权重、索引、参考录音及少量采样参数；未知字段会拒绝。不能填入 LLM/API Key/QQ 账号/.env。所有相对路径以该 JSON 所在目录为基准，绝对路径也支持；生成目录必须是配置所在目录内的专用子目录。

已核实的外部环境为 `C:/Users/罗斌/Desktop/qq-chatrobot-all/qq-chatrobot-voice/GPT-SoVITS/.venv/Scripts/python.exe` 与 `RVC-upstream-20261012/.venv/Scripts/python.exe`。运行时调用这些现有第三方环境公开的 `TTS_Config` / `TTS.run`、`Config` / `VC.get_vc` / `VC.vc_single`，不复制第三方实现、不安装依赖或下载模型。模型不放入游戏 JAR。

基础模型取自此前本项目 `build-isolated/robot-voice-20261017/tts_infer.yaml` 的非秘密验收快照：v2 GPT 基础检查点配丛雨 `murasame_voice_mixed_e13_s1430.pth`。参考录音使用已存在的 `murasame_0001.mp3`，转录从同一数据集 `murasame.list` 第一条逐字核对：`子供扱いするでない。吾輩の方がはるかに年上であるのに。`，参考语言日语。采样采用原代码可核实默认值：TTS seed 17、top_k 15、temperature 0.8、repetition_penalty 1.35，RVC seed 20261004、索引比例 0.75、speaker 0、移调 0。没有读取旧 `.env`，不能声称这些字段与旧私有配置逐项相同；这份独立配置还需实际语音质量验收。不会使用当前外部 YAML 中另一个角色 roka 来冒充丛雨。

| 游戏角色 | 已有 RVC 文件名 |
| --- | --- |
| tomori / 高松灯 | mygo_deng |
| anon / 千早爱音 | mygo_aiyi |
| rana / 要乐奈 | mygo_lenai |
| soyo / 长崎爽世 | mygo_sushi |
| taki / 椎名立希 | mygo_lixi |
| sakiko / 丰川祥子 | mujica_xiangzi |
| uika / 三角初华 | mujica_chuhua |
| umiri / 八幡海铃 | mujica_hailin |

五个 MyGO 模型有现成索引，使用0.75；三个 Ave Mujica 模型当前没有对应索引，配置明确留空并使用0，不虚构文件。更换资产时应同时核对模型和索引是否配对。

旧 QQ 目录下的参考录音只是被显式引用的资产文件，不需要任何 QQ Python 模块、配置或运行中的 QQ 服务。可以把模型、参考录音及环境保存在独立目录，再修改这份路径配置；不要在删除旧目录时一并删除仍被引用的资产。

### 日语前端的显式单包兼容

首条真实调用的失败已由有界traceback确认：参考录音/转录为日语，现有主TTS环境缺少 `pyopenjtalk`，错误出现在前端，模型加载与内存保护不是该次根因。独立配置现在显式指定 `base_tts.japanese_frontend_package`，仅引用现有 `.venv_cpu/Lib/site-packages/pyopenjtalk` 这一包。安装元数据为 `pyopenjtalk-prebuilt 0.3.0`，其 `METADATA` 标注上游 [r9y9/pyopenjtalk](https://github.com/r9y9/pyopenjtalk)；实际以本机已安装包、C扩展和本地字典为准。本轮未下载或安装依赖。

`japanese_frontend.py` 通过明确的单包 `importlib` spec 加载它，不把 CPU 环境的整个 `site-packages` 放入 `sys.path`，不复制第三方源代码，不修改主/CPU语音环境或其包文件；显式加载阶段关闭字节码写入。主环境自己的 NumPy、six、tqdm、setuptools/pkg_resources 仍由主环境提供，不引入 CPU Torch。适配器在导入神经TTS前检查本地字典和扩展ABI，缺失时提前给出错误，不下载字典。Windows下用已核实字典的ASCII相对路径，避免Unicode绝对路径导致旧C扩展无法打开字典，不复制字典。

目前两套环境均为64位 Windows CPython 3.11.9，已有 `openjtalk.cp311-win_amd64.pyd` / `htsengine.cp311-win_amd64.pyd` 和 `open_jtalk_dic_utf_8-1.11` 字典。迁移到别的机器时必须保留/提供与实际TTS Python版本、操作系统、架构匹配的包和C扩展（cp311文件不能混入cp312环境），并核对NumPy及包依赖；本版旧包还需要可提供 `pkg_resources` 的 setuptools。配置必须指向包目录，不是整个环境目录；留空仅表示尝试主TTS环境中已正常安装的包，不代表自动安装。原CPU环境可以不运行，但引用的包目录及字典必须保留。

此次保留真实日语参考录音与对应日语转录，没有把日语标成中文。混合数据集虽有真实中文zh01/zh05等片段，但历史记录明确只有机器ASR复核、部分独立结果不一致；它们不被冒充为已经人工核实的准确中文参考。若以后选用中文替代参考，应先确认音频/转录配对，再显式修改独立配置。

轻量前端核对证据在 `runtime/japanese-frontend-check.json`：在主TTS Python中实际调用 `pyopenjtalk.g2p("こんにちは。", join=False)` 得到 `k o N n i ch i w a`，未导入Torch/Transformers、未启动TTS/RVC或下载模型，也未加入整个CPU site-packages。第三方依赖导入可能调整其自身主环境路径；“单包加载”不等同保证任何传递依赖都完全不触碰 `sys.path`。后续完整神经合成已由主任务串行验收，结果见文末。

## 手动入口

只检查配置、文件与系统资源，不监听端口、不导入模型：

```powershell
.\tools\robot_voice\start_voice.ps1 -CheckOnly
```

确认有足够空闲内存后，显式启动独立 HTTP 协调进程（隐藏后台窗口）：

```powershell
.\tools\robot_voice\start_voice.ps1
```

或用 `-Foreground` 前台运行，以 Ctrl+C 结束这次协调进程并回收其未完成请求。脚本不会关闭或重启现有服务；端口已被占用时保留原监听，不自动替换。`runtime/http.pid` 只记录本脚本新建进程，日志在 `runtime/http.*.log`。游戏开关仍需由玩家显式启用。

Java 游戏服务端可在自动启动协调器时传入 `server.py --parent-pid <游戏服务端PID>`。该可选参数必须是正整数且不能等于协调器自身 PID；若指定进程启动时已不存在，协调器会明确报错退出。协调器只监测这个 PID，父进程退出后关闭 HTTP 监听、取消当前及排队请求，等待本次语音工作进程树清理，然后删除专用 `runtime/audio_cache` 中本服务生成的哈希 WAV 和 `runtime/job-*` 临时目录。手动模式正常退出也执行相同清理；模型、配置、锁文件及其他服务数据不在清理范围内。Windows 使用仅供等待的进程句柄，Linux 使用 `kill(pid, 0)`；不传参数时仍按原有方式手动常驻运行。启动脚本应把实际游戏服务端 PID 原样传给此参数。

## 固定 HTTP 合约

`GET /health` 返回 `protocol:1`、`backend:ic2newera_standalone_voice`、文件存在/缺失项、各角色配置、资源保护、是否繁忙。`environment_and_base_files_present` 只表示文件存在，不等同依赖导入、CUDA 或推理成功。`models_resident_in_http_process:false` 表示 HTTP 进程不载模型；忙时模型是否已进入工作进程为未知。`inference_verified:false` 不冒充当前运行时已经过独立推理验收；成功请求可记录 `last_audio_validated:true`，仍不等于游戏播放验收。

`POST /voice`：`Content-Type: application/json`，只接受以下三个字段：

```json
{"protocol":1,"character":"anon","text":"你好，可以开始工作了。"}
```

角色仅接受上表八个 ID，文本 1 至 360 字符；JSON 正文不超过 20 KiB。成功返回 `audio/wav`：16 位有符号 little endian PCM、8–48 kHz、单/双声道、最长 90 秒、最多 8 MiB，并带：

```text
X-IC2NE-Protocol: 1
X-IC2NE-Character: anon
X-IC2NE-Voice-Route: tts_then_rvc
```

游戏必须核对协议、请求角色、长度和 PCM WAV 格式。失败为 JSON `{"protocol":1,"error":"voice_unavailable","message":"中文原因，文字回复已保留。"}`。非法请求为 400/413/415，超过队列上限或连接过多为 503，超时为 504。不会把失败音频替换成别的角色或清除原文字。客户端取消 HTTP 请求、切换回复或关闭语音时应中断连接；服务端检测断开后取消排队/当前任务、等待本次子进程树退出、清理作业目录。

协调进程只绑定 `127.0.0.1`，再次检查真实 TCP 对端，拒绝浏览器 Origin 和 HTTP 分块请求，不提供跨域接口、远程认证或任意文件/URL 参数。采用一请求一连接，响应包含 `Connection: close`。同一运行时严格单线程式串行处理：最多1条运行、16条FIFO等待；等待队列满时返回503。整条请求从入队起最多600秒，包含排队、资源等待和生成；等待资源会每2秒异步重查，超时后保留文字回复。各工作阶段上限仍为TTS 110秒、RVC 100秒、PCM转换15秒，且受600秒整单时限约束。HTTP服务在610秒处兜底返回504；Minecraft Java HTTP调用超时应同步设为至少620秒，并在超时/断连时保留文字结果并中断连接。

## 内存与取消

2026-10-18 用户明确要求后，模型开始每个阶段前等待可用物理内存达到1.0 GiB；这是开始加载阶段的最低门槛，不保证1 GiB足以容纳模型或完成推理，仍可能因85%提交量、磁盘余量、进程错误或后续运行硬线而失败。排队与资源等待包含在600秒整单时限中。运行期间仅在物理余量低于0.25 GiB时触发硬保护，不会刚降至1.0 GiB就终止；85%系统提交量和项目盘剩余10 GiB仍为硬保护。`/health` 的 `memory_start_threshold_gib` 显示1.0，`memory_running_threshold_gib` 显示运行硬线0.25。缓存命中只读小音频，不加载模型，不要求新的模型启动资源。

保留此前游戏专用优化：`is_g2pw=false` 使用现成 pypinyin（多音字读音可能不同）、单 batch、关闭并行/CUDA graph、片段写 PCM；CUDA 且原本最终 half 时，BERT/HuBERT 使用公开 `from_pretrained(dtype=float16, local_files_only=True)`，已有 Accelerate 时直接映射 CUDA，无依赖时只用半精度加载。没有改权重、全局默认 dtype 或 `torch.load`/mmap，也不修改第三方源码或原 YAML。

`runtime/audio_cache/` 只缓存生成的小 WAV：总计最多 8 MiB、32 条、单条最多 2 MiB，键含语句、参数、资产签名与自有 worker 版本；不缓存模型。当前请求的阶段读数、退出状态和资源原因存入 `runtime/last-request.json`，常规审计不记录文本、凭据或 LLM 配置。Windows 使用有界临时日志和受控 `Popen`；自有 `tts_worker.py` / `rvc_worker.py` 非零退出时，按本轮排错授权，审计额外保留 stderr 最后3000字符、stdout最后1000字符，总计最多4000字符。失败尾日志可能包含该次语音文字和本机资产路径，不通过HTTP返回，也不收集QQ/LLM配置；其他进程不保留该诊断。收尾后删除临时完整日志，取消/超时只处理本次创建的 PID 树。

`gpu_lock_file` 默认位于本目录专用生成目录。多个独立实例需使用相同锁文件才能跨进程串行；其他训练或旧语音服务只有主动使用同一锁时才会配合。运行时不接管、不停止那些服务；并行外部模型仍可能触发内存保护。

## 来源与交付边界

迁入的是用户自有代码：QQ 仓库的 `app/minecraft_voice_runtime.py`、`app/services/singing_resources.py`、`scripts/minecraft_tts_worker.py`、`scripts/run_rvc_model.py` 和 `scripts/run_gpt_sovits.py` 的小型离线语言适配器，分别整理为本目录 `runtime_support.py`、`resources.py`、`tts_worker.py`、`rvc_worker.py`。新增 `configuration.py`、`engine.py`、`server.py` 解除了 `Settings`、`voice_profiles`、`app.*`、AstrBot 和 QQ 依赖。第三方模型实现仍位于原外部环境，不随本目录复制或再分发。

迁移实现只新增本目录源码、脚本、配置和说明；静态解析/配置路径核对是源码交付证据。语音协作者没有运行测试套件、构建或真实TTS/RVC，没有启动/停止原有服务或模型，没有读取 `.env`/API Key，也没有修改原QQ项目。主任务实际启动独立9881协调器，首条tomori在加载模型后、前端阶段退出1；有界traceback定位为缺少pyopenjtalk。实现单包适配并完成轻量核对后，仅重启本任务创建的空闲协调器，再进行下述真实验收。不能把此前八角色在旧桥的历史音频成功替代独立运行时的新验收。

### 2026-10-18 10:24 独立运行时实际结果

新独立接口八角色全部成功，语句为“你好，可以开始工作了。”，每份为44.1kHz单声道PCM16、3.18秒、280,554字节，角色响应头匹配，八份SHA-256不同。首条高松灯36.906秒；其余七条11.594–12.500秒。共一次基础TTS、八次独立RVC，所有工作进程正常退出，轻量协调器保留且空闲，没有常驻模型。

阶段读数的最高工作集约2.705GiB、最高私有字节约4.712GiB；系统可用物理内存最低采样约2.016GiB。它们是工作进程阶段快照和系统资源采样，不是进程树持续采样的绝对峰值，也不与Java heap/direct-buffer指标混用。协调器查询时工作集约4.25MiB；该次验收时门槛为3.5/1.5GiB，当前资源策略已按2026-10-18新要求调整为1GiB启动等待和0.25GiB运行硬线。原LLM配置未改，没有调用外部LLM。

八份新样本已由当前生产播放器执行软件音量0的Windows Java Sound线路输出，全部完成并清理临时文件；实际游戏内传输、可闻效果、音色质量及真实多人仍待玩家验收。证据与音频在项目 `build-isolated/native-media-migration-20261018/standalone-eight-voices.json`、八份 `standalone-*-audit.json`、`standalone-voice-memory-result.json` 和 `native-voice-playback-result.json`。旧失败证据另存，未覆盖。
