# 2026-10-18公开同步与CurseForge材料

用户明确要求给朋友测试包、同步既有GitHub并提交CurseForge，随后说明LLM由玩家自行配置，语音可选；此授权覆盖早期“不上传”阶段约定。

## 当前交付

- 公开目标：既有 `jiuxiansheng1111/IC2-newera` 的 `main`。实际提交SHA、远端文件树与安装包哈希核对写入本机 `build-isolated/publication-20261018/`，不得在未核实前宣称已推送。
- 完整模组：`releases/ic2newera-0.1.0-dev.jar`，2,025,342字节，SHA256 `0CEB7B30161CA4668B34B02C97728380C2C8AA6DDF3A06C80205BE59DE8C5770`。
- 朋友包：`dist/IC2-New-Era-Test-2026.10.18-Phone-Chat.zip`，1,686,184字节，SHA256 `2782A85D28BD13F9536EC1ABFAC09F669F80FAF1B50299453A5E500838421348`。
- 当前源码ZIP：`dist/IC2-newera.zip`，由已审阅暂存文件生成并逐项回读。源ZIP不递归包含自己，只含当前朋友包，不夹带历次包或本机数据。
- 模型：八个已批准、贴图内嵌的最终工程保存在 `art/robot-models/current/`，总计5,629,771字节，逐一匹配导出器批准SHA256。导出器路径已同步。
- 可选语音源码及可编辑示例路径公开；本机生产配置、模型权重、运行音频/日志、LLM配置和密钥不在公开范围。原玩家配置哈希保持不变。

## 公开验收说明

最新自然语言采集已实现、构建和安装；用户停止Computer Use后先交包，因此该新路径尚未实机完成。此前八角色语音、1000格联系/定量GUI采集为各自已记录证据，不能扩写成新聊天派单、双玩家和公网穿透全部通过。只以Alpha测试版介绍，见[交付记录](chat-collection-delivery-2026-10-18.md)。

## CurseForge

已准备英文/中文介绍、MIT许可、依赖、Alpha文件文案、原创512×512项目图标和选定游戏截图，见[提交清单](curseforge-submission-2026-10-18.md)。本机可交付材料ZIP：`dist/IC2-NewEra-CurseForge-Submission-2026.10.18.zip`。该材料包不是启动器整合包；Mods项目上传其中完整JAR。当前没有已确认的作者提交连接/会话，未提交审核；不能把材料准备写成发布或审核通过。

## 历史稿清理

用户明确授权删除历史稿。先保全当前八个最终工程，核对删除目标都位于项目art目录内且无外部链接，然后请求删除历史目录。批量命令和随后精确单目录命令均被自动审批拦截，仅返回`blocked by policy`；没有执行删除，也未改用其他工具绕过。`art/robot-rework`、`art/robot-mcp-workspace`、`art/robot-cuboids`、`art/robot-reference-head-prototype`仍留在本机，均排除公开文件。原始参考照片保留本机，非本次历史稿删除目标。
