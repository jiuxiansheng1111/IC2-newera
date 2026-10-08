# PCL2 实例说明

## 状态记录

PCL2 隔离实例 `C:\Users\罗斌\Desktop\.minecraft\versions\1.19.2-Forge_43.5.0` 已安装 2026-10-07 的成就中文与助手资料查询更新。`build/deployment/pcl-installation.json` 回执包含 8 个已验哈希模组；本次仅替换新纪元 1 个 JAR，SHA-256：`F844615EC795697BCA4535C085D67A2A913F763664305D7221A6A3DE44AC37B4`。

IC2C Extras 仍未安装。历史安装回执、旧模组和撤下的 Extras 已保存在 `build-isolated/deployment-history/` 或本次 `build/deployment/backups/`，不在运行实例的 `mods` 目录里。AI 预设、玩家密钥与启用状态本次未修改；新增资料开关在游戏内设置。

本次安装前确认该 Minecraft 实例未运行，安装后没有自动启动游戏；包内加载、世界与 JEI 配方还没有实机结果。

## 手动验收

启动目标实例后依照 [人工验收清单](morning-acceptance.md) 核对科技树、AI 界面、JEI 配方和 IC2/Create 物料联动。此项目接受旧世界不兼容；若继续使用旧世界，先备份，并自行评估升级风险。

若遇到加载失败，可提供 `logs/latest.log` 中相关错误片段。分享前应移除 API Key、访问令牌、账号信息和其他凭据。
