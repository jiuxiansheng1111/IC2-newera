# r53：五个客户端模组、iterationT 光影与 Xaero 小地图

日期：2026-10-29。范围为独立 Minecraft 1.20.1 / Forge 47.4.28 Windows 客户端；用户此前明确表示服务器自行维护，本轮没有操作或核验服务器。

## 模组与光影

- 按用户提供的文件安装 Search on MCMOD 0.1.0、Freecam 1.2.1、IMBlocker 5.6.2.1、Cloth Config 11.1.136，并把 YSM 从 2.6.5 替换为用户指定的 2.6.3。五份文件的 SHA-1、SHA-512、大小均与各自 Modrinth 发布一致；旧 YSM JAR 已由安装脚本备份。
- 按用户后续要求安装 Xaero's Minimap 26.6.0，官方 Forge 1.20.1 文件大小 2,196,194 字节，SHA-256 `9B39CD06094D7631C8614D172D0BA8399C71C981530AF417F12A7909FCB855BD`，SHA-1 / SHA-512 与[作者发布](https://modrinth.com/mod/xaeros-minimap/version/dB6E0CY9)一致。所需 XaeroLib 已包含在该 JAR 的 JarJar 内容中；世界地图为可选模组，本轮未安装。小地图正常客户端使用不要求服务器安装。
- 单独提供 `dist/xaerominimap-forge-1.20.1-26.6.0.jar`，与本机安装文件哈希一致，仅作为用户所需的本地文件，不上传公共源码仓库。
- 更换至 iterationT 3.2.0，Medium 中档参数取自原 ZIP 的 `profile.Medium`，Oculus 配置已选择该光影。ZIP 为 42,846,966 字节、SHA-256 `C0735D5F1DE0EDCA2B1C155DAE1C650DDB8D2D04A3EBDD3D2FF74DD7A19DB116`。取得来源是[公开下载页](https://texture-packs.com/shaders/iteration/)提供的 CurseForge CDN 原文件，压缩包完整性、作者/版本标识通过；[作者发布页](https://www.minegraph.cn/shaderpacks/12)说明 1.20.1/Oculus 兼容，但未找到作者发布的校验值，不能声称与作者源文件哈希比对过。光影原 ZIP 通过清单下载，不重新上传。
- MC百科搜索默认在物品界面按 B；小地图默认游戏内 B 新建路径点、U 打开路径点列表、Z 放大；Freecam 默认 F4；YSM 默认 Alt+Y。B 有不同使用场景，如实际冲突可在控制设置调整。

## 构建、打包与安装

- `clean build -x test --offline` 及后续仅资源变化的 `build -x test --offline` 均成功；没有新增或运行自动化测试。更新内置 AI 的客户端模组说明，最终新纪元 JAR SHA-256 `AB37FDC221399C2FD31AA6B3F0CA85A89A8D06369181DBB5CC20669F21AC0DDF`。与前版逐文件比较只有 AI 知识文档改变；游戏代码、工艺及网络协议保持。清理前的部署目录回执和备份全部保留并逐项核对。
- 本机于 20:40:23 安装五个用户文件，于 20:43:11 安装并选择 iterationT，于 20:46:47 安装 Xaero 与最终新纪元 JAR。最终 37 个实际模组均与安装清单 SHA-256 一致；YSM 2.6.5 不再位于活动 mods。现有存档及服务器未触碰。
- 客户端 `dist/IC2-New-Era-Windows-1.20.1-r53-Minimap.mrpack`，306,726,330 字节，SHA-256 `F5B9D50E2BAA58E51A871AD13A0E0C78BBC6D836728C5A2744B1108D53D1B5AA`。包体和 Modrinth 清单回读校验通过；38 个下载项包括 35 个第三方模组、1 个小地图、1 个光影和 1 个材质包（总计 37 个模组含新纪元本体）。第三方 JAR/视觉 ZIP 未嵌入，首次 PCL 导入需联网。
- 根据“替换之前的”要求，r50、r51、r52 三个旧客户端包及校验文件已移入 `build-isolated/player-packaging/20261029-r53-minimap/previous-clients/`，逐项核对哈希，作为回退保留；完整服务器包和固定语音附件保持。

## 尚待验收

没有启动或操作游戏，也没有新增或运行自动化测试。模组共同加载、YSM 2.6.3 与服务器版本配合、小地图、光影编译和实际 FPS 尚未实机确认。用户指定的零雾构想材质仍未提供，当前仍为 Bare Bones，不能称为已替换为零雾构想。YSM 多人完整同步需要两端安装，建议核对服务器是否同为 2.6.3；用户之前说服务器已安装，但本轮未检查其版本。
