新纪元 r48 Linux 固定问候语音包

用途
保留离家送行、回家欢迎两类固定问候，覆盖八种角色音色、中文/日语、
陌生/熟悉/亲近/亲密四个阶段，共128个真实预生成的PCM WAV片段。
按角色ID与完整台词精确匹配，不用另一角色或无关音频替代缺失语音。
普通聊天继续显示原有文字；未预录的动态回答、英语语音和翻唱不提供音频。

运行要求
Java 17或更高；当前服务器使用Java 21。服务器端不需要Python、FFmpeg、
CUDA、语音模型或额外公网端口。播放器只绑定127.0.0.1:9881。
客户端继续使用配套r48新纪元模组，音频通过现有Minecraft媒体协议传送。
本JAR是启动附加组件，不是Forge模组，不放进mods目录。

当前面板的安装方法
1. 将ic2newera-fixed-voice.jar放入服务器根目录/home/container。
2. 在根目录unix_args.txt中，于原有Forge参数最前面加入两行：
   --add-modules=jdk.httpserver
   -javaagent:ic2newera-fixed-voice.jar
   保留原Forge参数的全部内容。不能只保留这两行。
3. 正常重启服务器。控制台应出现[IC2 Fixed Voice] READY与片段数量，
   新纪元随后应输出reusing the existing local coordinator on port 9881。
4. ic2newera-fixed-voice-status.json记录片段数量、索引校验、成功播放请求数。
   audio_integrity_verified表示文件校验完成，不表示玩家已经实际听到。

玩家使用
在机器人对话界面开启语音，并确保游戏“主音量”和“语音”音量大于0。
机器人需要有电、设置了家/工作地点，并处于待命、做饭、烧矿或种地模式。
在家附近停留约5秒后出门，会触发送行；离家24格外约10秒后回到角色8格内、
能互相看到，会触发欢迎。战斗、没电或语音开关关闭时不会正常播放问候。
上述触发条件来自r48源码；玩家实际往返及听感仍应在游戏中确认。

回退
正常停止服务器后，把本次新增的根目录unix_args.txt改名保留。
当前面板会恢复使用libraries下原Forge参数。保留的original-forge-unix-args.txt
为本次安装前读取的原参数，只适用于本服务器的Forge 47.4.28。
若其他服务器原本已有根unix_args.txt，应恢复它自己的安装前备份。
不要改动world、玩家数据或API配置。

内容与隐私
发行JAR只包含本项目播放组件、预录音频、台词索引和生成清单。
不包含模型、参考录音、Python环境、账户凭据、API Key或存档。
音频使用本机现有的GPT-SoVITS与对应角色RVC生成；生成环境路径只留在本地。
运行时不连接外部语音服务，不提供任意文件读取、上传或执行命令接口。
Javaagent仅启动播放器，不修改Minecraft/Forge字节码。

开发文件
FixedVoiceAgent.java：仅依赖Java标准库的播放器。
package_fixed_voice.py：检查生成完整性与哈希，编译并封装JAR。
../fixed_greetings_generate.py：读取当前源码台词并调用现有本机语音流水线。
未新增或运行自动化测试；构建、文件完整性检查和实际部署回执单独记录。
