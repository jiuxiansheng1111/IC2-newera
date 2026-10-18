# 八人模型游戏接入与建模插件交付（2026-10-17）

用户授权由 GPT-6.1 Sol 实现实际游戏接入，主智能体负责接口协调、审阅与集成；并将建模经验制作成插件上传 GitHub。用户随后确认插件公开，以及与「添加8种机器人图纸与棉花娃娃」任务协调共用实体。

## 游戏接入

- 模型来源：爱音 `anon-body-balance-r16` 与其余七人 `{id}-r16-standard`，八份源工程保留原 SHA-256；不采用旧占位稿。
- 运行时转换：4672 cube / 28032 quad / 112128 vertex，16 张原 atlas 的 RGBA 像素保留。资源在 `assets/ic2newera/robot_models/r16/` 与 `textures/entity/robot/r16/`。
- 统一使用另一任务的 `RobotEntity` / `RobotRegistry.ROBOT`，实体 ID `ic2newera:companion_robot`，角色数据为 `RobotCharacter`。模型侧撤销临时实体与生成蛋，不保留两套注册。
- 主智能体已亲看从运行时网格生成的八人正/侧/背离线图，外形完整。离线图不能替代游戏截图。
- 模型外观与资源缓存由 Sol 负责；娃娃、配方、跟随/待命/收回和 ChatRobot 接口由另一任务负责。构建、安装由本任务统一执行。
- 构建：`gradlew.bat -Dnet.minecraftforge.gradle.check.certs=false clean build -x test --offline` 成功，37 秒、9 个任务；34 项弃用警告。构建前后 577 份输入指纹一致，未新增或运行测试。
- 安装：现有 `scripts/install-pcl-mods.ps1` 已更新本地 PCL 的 `1.19.2-Forge_43.5.0` 隔离目录，复制 1 个新纪元 JAR，其余 7 个依赖保持；8 个安装文件哈希全部一致。安装前未发现 Minecraft 进程，未启动游戏。
- 产物：`releases/ic2newera-0.1.0-dev.jar`，1,545,321 字节，SHA-256 `e33c9bfa72b1fc9d17a91553665083595b4912d74b092e8d3a0867d1fef3d768`。构建产物、release 与安装文件一致。
- 回执：`build-isolated/robot-models-final-build.log`、`build-isolated/robot-models-package-audit.json`、`build/deployment/pcl-installation.json`。clean 前的部署回执/备份已逐文件哈希保留到 `build-isolated/deployment-preserved-20261017-model-integration` 并恢复；旧 release 另存 `build-isolated/release-before-robot-integration.jar`。
- JAR 内核对：24 份模型/贴图/采样资源逐字节哈希一致，8 张娃娃图标、9 条机器人配方及必要实体/客户端类均存在。实机与多人验收仍待用户操作。

## 已发布插件

- 仓库：https://github.com/jiuxiansheng1111/blockbench-character-workflow （公开）
- 本地目录：`C:\Users\罗斌\Desktop\blockbench-character-workflow`
- 版本：0.2.0；提交 `85b38b88f03b5bf5be17928854eb28cbdc5658a0`。
- 22 个文件的远端 tree/blob/mode 与本地提交一致；未将当前 IC2 工作区提交到 GitHub。
- 类型：Codex 工作流插件，配合 Blockbench 使用；不是 Blockbench 内部 JavaScript 扩展。
- 包含参考拆解、本轮提示词、脸型/五官、规整分束长发、身体比例、锁定已认可部件、真实多视图审阅、格式与游戏导出边界。
- 附两个原创工具：`bbmodel_audit.py` 只读结构审计/组冻结对照；`contact_sheet.py` 排列真实渲染并记录来源哈希。
- 技能静态验证、官方 portable manifest schema、JSON/脚本语法通过；八个真实模型审计成功，实际执行身体/裙子指纹和正侧背图排布。未新增/运行测试套件。
- 按用户后续要求，README 与插件简介明确“基于 Minecraft 方块与像素风格”，新增 `README.en.md` 英文说明、八人全身/头部/运行时正侧背三张实际模型展示图及来源哈希。图片字节与原渲染一致；没有以概念图冒充模型或游戏截图。
- 当前版本改为自定义非商用条款，中英写明“严禁商用，使用后果自行承担”，并保留依法不能排除的责任及第三方 IP 边界。初始 0.1.0 曾按 MIT 发布，这一历史事实在 NOTICE.md 明确记载，不声称追溯撤销既有授权。
- 公开仓库不含原始第三方参考、可编辑角色模型、贴图/动画、用户聊天记录、游戏包或凭证。
- 未在当前应用安装该插件，未声称所有客户端已经运行验证。仓库 README 有安装与调用方式。

## 验收边界

工程可编辑、运行时资源导出、Java 构建、模组安装、游戏中显示和角色相似度，是不同的检查阶段。模型本身没有完整行走或衣发动画，不用主观相似度百分比宣称完工。
