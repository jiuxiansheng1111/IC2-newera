# r71：进服版本不匹配与主菜单红字（2026-11-14）

这是同一次启动中的两个独立问题。旧版 JEI 优化模组的启动故障没有在这次复现：普通 Forge 1.20.1 实例在 23:59 已经到达主菜单。

## 00:00:35：服务器拒绝连接

Forge 的握手记录显示，客户端从服务器读到 `extendedae_plus:main=1`，而本地 ExtendedAE Plus 1.6.4 双向要求 `2`。服务器也没有报告新 JEI 内置的 `mezz_config:server_config` 和新版 ExtendedAE Plus 内置的 `configuration:network`。随即出现 `ModMismatchDisconnectedScreen`。三条记录属于同一次连接；无需另装两个配置库。

从面板实际文件目录核对到 JEI 15.74.0.234、ExtendedAE Plus 1.6.4。它们于 11 月 13 日 22:53 已换好并下载复核；次日凌晨观察到的服务器进程已连续运行超过两小时，启动早于换包。该进程仍按旧版协议应答。服主在面板正常重启，服务端就绪后再连接；此次排查不代为重启，不涉及地图或配置删除。

## 23:59:41：主菜单 KubeJS 提示

PonderJS 生成教学场景的本地化条目时会先编译演示。Create More Recipes 1.2.1-fix1 的注液器演示在 `CustomPonderScenes.blockSpout:334` 立即读取预览世界的随机数源；语言生成时还没有预览世界。日志记在 KubeJS 客户端通道，但堆栈里出错的是该模组的 Java 方法，非任务脚本。

本项目的客户端修复复用演示原本已创建的随机数源，水花和教学步骤保留。匹配目标版本、方法和指令后才应用，不修改原模组文件、不添加网络通道。服务器无需安装。按 [Forge 的两端说明](https://docs.minecraftforge.net/en/1.20.x/concepts/sides/) 将该模组标为允许对方没有它。

玩家正常退出后，已在普通 Forge 实例安装 `newera-ponder-fix-1.20.1-1.0.0.jar`；两个 CrashAssistant 清单只补这一项，保留运行时生成的模组识别信息。完成态回执核对本机 404 个 JAR、完整 Windows 包与 344 个服务端 JAR，未重新启动游戏、服务器或进入世界。单独给朋友的 JAR、大包里的 JAR 与本机实际文件均为 SHA-256 `7bdd637bb655c4de34dcd942a281f8a9726d336cbedfe0c6bc17321669018323`。

Windows 包与已有 r71 的玩家更新包均已包含补丁。服务器 ZIP 的散列保持 `c2a2507aefeed5c7f5ea6c5d6daac4514af20f1647e04ad95eb8839c6c0743a7`；无需再次上传。游戏运行时的教学展示以及服务端重启后的握手留给玩家验收。
