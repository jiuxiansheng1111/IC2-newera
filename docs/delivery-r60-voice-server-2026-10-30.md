# r60：服务器更新与固定问候语音整合

日期：2026-10-30。用户要求把语音附件和新版服务器文件一并准备，并授权更新指定服务器。Windows 客户端继续使用 r60；本轮不改变游戏 JAR、协议、配方或世界内容，也不启动游戏客户端。

## 本地交付

- `dist/IC2-New-Era-Server-1.20.1-r60-Voice.zip`：r60 完整服务器，根目录加入固定语音 JAR，`user_jvm_args.txt` 追加 `--add-modules=jdk.httpserver` 与 `-javaagent:ic2newera-fixed-voice.jar`。使用包内 Start.sh、Start.bat 或 server.jar 启动。Java17、原内存默认值和 EULA=false 保留。
- `dist/IC2-New-Era-Server-1.20.1-r60-Update.zip`：已配置语音参数的现有服务器使用的补丁，仅包含新纪元 JAR、根目录语音 JAR和说明；不包含世界、其他配置或内存参数。正常停服并备份后解压到根目录覆盖对应两个JAR。
- 完整包26个模组及Forge运行文件与 r60-Complete 相同；仅更新根目录语音组件、启动参数、说明和文件清单。128段固定中日文送行/欢迎语音；非预录动态回答、英语和翻唱不在此附件范围。
- 生成器为 `scripts/package-server-voice-1201.py`；归档内容和清单逐文件回读哈希核对。没有新增或运行自动化测试，没有进行游戏内听音验收。

| 文件 | 大小（字节） | SHA-256 |
| --- | ---: | --- |
| `IC2-New-Era-Server-1.20.1-r60-Voice.zip` | 365,724,358 | `32060AAA5311D58EB922BAC1D0678E5296E95A3036DA90519316039F5FBDEEB6` |
| `IC2-New-Era-Server-1.20.1-r60-Update.zip` | 55,638,127 | `9CEF6FAC23DF2D7D6B461271691E6638AE5E30DFAA78792614E76F5F4DA903A0` |
| `ic2newera-fixed-voice.jar` | 45,937,939 | `D1AC67C04E64A5DE16971CDB22EC7CEB928156C5387C701B6D0FD2EF9EB6CB92` |

## 线上检查与备份

- 活动服务器原新纪元 JAR 为 r49，SHA-256 `62F360868F18BF738E0F5CBD30302F569CDDBA33FAD793CBB15566F6F3962EE9`；服务器已安装固定语音 JAR，SHA-256 与本轮附件相同，原根目录 unix_args.txt 也已经配置两行语音参数，因此无需重复改动参数。
- 当前活动目录有28个模组；除完整包的26项外，用户自行添加的 Cloth Config 和小地图保留。YSM 为用户提供的2.6.3文件；客户端和服务器共有依赖版本从文件清单核对。
- 停服前控制台 list 返回0/20在线；正常停止，等待面板显示已关闭。创建完整备份 `before-r60-voice-20261030`，完成后面板显示SHA-1 `0c99cea1b99468f6b1b76d0a316aa7f37012648c`。
- 旧新纪元 JAR 移至 `/home/container/backup-before-r60-20261030/`；r60 JAR先上传到独立暂存目录并回读，再移入活动mods，移入后再次下载核对，SHA-256为 `6D56CF2F2E25790B647FDBBD431ACDE7151CCB9F6AFAE87281A9A22BAA31828D`，与客户端/交付JAR一致。
- 本地备份、归档核对和实际部署回执保存在 `build-isolated/server-packaging/20261030-r60-voice/`；不把远端日志、凭据、世界或预录声音上传GitHub。

## 本次线上启动结果

- 北京时间2026-10-30 15:52:01.619记录 `Done (10.345s)`，加载4438个原版进度；15:53:25.666的list响应为0/20在线。实际已安装r60，与Windows客户端JAR完全一致。
- 15:52:08.984记录复用本机9881语音协调服务；下载状态文件核对 `ready=true`、`clip_count=128`、`character_count=8`、`audio_integrity_verified=true`。本轮播放请求数为0，因此只确认服务与音频校验，没有代玩家进行听音验收。
- 日志仍记录Valkyrien Skies接口Mixin和专服侧PoseStack可选类的两条既有ERROR，以及若干可选兼容WARN；随后正常完成启动并响应命令。本轮没有更换第三方依赖或把这些信息包装成零错误、全功能验收。
- 沿用原服务器启动方式、语音参数和内存设置。完整备份及旧JAR回退备份都保留。用户明确选择自行验证游戏，本轮未启动本地Minecraft客户端或手工进入现有存档；服务器按授权正常停服、保存与重启。
