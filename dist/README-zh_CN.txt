IC2：新纪元 r48 — Forge 1.20.1 / 47.4.28 / Java 17

客户端：IC2-New-Era-1.20.1-Forge-47.4.28-r48.mrpack
拖入 PCL2 安装为独立实例。包含新纪元本体，启动器按锁定URL下载30个第三方模组；附Windows便携Java17及匹配源码。请勿覆盖1.19.2旧世界。

Linux服务端：IC2-New-Era-Server-1.20.1-r48.tar.gz
包含官方Forge服务端核心及libraries、25个配套模组、默认配置与中英启动说明，不是只有mods的附件。
完整解压到独立目录，准备Java17，阅读并自行同意Minecraft EULA后将eula.txt改为true，再运行 ./start-server.sh。
默认内存2G–6G、TCP25565、online-mode=true。启动说明见归档README.zh-CN.md；支持目标是glibc Linux，包含x86_64/AArch64原生库，不用于Alpine/musl。实际Linux运行与多人联机尚未验收。

本轮更新
- AE2原生ME仓储、终端、样板自动合成；IC2R自带EU接入AE2 Energy Acceptor，静态核对已完成。
- 手机本轮先不改，后续再做AE对接；机器人、工业监控与已有AI互联网保留。旧新纪元终端仅保留旧物品取回。
- 中文FTB Ultimine（默认按住~，每次上限64）和中键整理。
- Create: Propulsion + Valkyrien Skies工业飞行方案。是1.20.1替代方案，不是Create Aeronautics；Propulsion仍处早期开发阶段。
- 客户端专用Embeddium/Oculus/Flywheel兼容桥及ETF/EMF/Fusion材质前置。未附实际光影或材质包；兼容桥2.0.3为beta。
- JEI两端均保留。自动中文补丁新增324个差异键。新增功能未实机验收。

可选语音
IC2-NewEra-Voice-Bundle-Win64-2026.10.24.zip与校验文件原样保留，不能直接用于Linux服务器；Linux AI语音后端尚未部署。
机械狗面板完整叫声音频已内置，不依赖Voice-Bundle。原猫狗模型、贴图和音频没有改动。

验证与交付
r48源码离线构建成功，本机独立1.20.1实例已更新31个JAR；客户端/服务端包已回读核对。
服务端仅执行官方 --initSettings，eula=false，未生成世界；不代表全模组启动完成或Linux验收。未新增/运行自动化测试。
详细版本、哈希和未验收项目见 docs/delivery-r48-server-ae-2026-10-28.md。第三方原JAR不上传公开GitHub，客户端按作者URL下载。
