# 完整项目 GitHub 公开交付（2026-10-08）

用户明确要求按原文件夹名称完整打包项目并上传 GitHub，并选择公开仓库。这次授权优先于此前开发约定中的“不上传 GitHub、不发布”；原有记录保留为历史阶段记录。

## 内容与名称

- 仓库名称及完整项目 ZIP 顶层文件夹：IC2-newera。
- 目标地址：https://github.com/jiuxiansheng1111/IC2-newera。
- 首页完整说明 IC2 Classic、Create 与可选 ProjectE 的分工、现代材料、服务器硬件中间体、144 格终端机、箱子及暗物质配方、工业网络、中文 AI 助手、多人权限、导入方法和实际验收边界。
- 保留全部活动源码与运行资源、原创美术及生成脚本、规范和阶段文档、Gradle Wrapper、安装/打包脚本、最新新纪元 JAR 与四核心测试整合包。
- 当前自有 JAR 复制到 releases/ic2newera-0.1.0-dev.jar，SHA-256 为 DBECDC802518A1F00B4CD794F67D6A2F0C716298652038BBAC17D253401F0613；运行锁改为此可分享路径。Create 改用已固定版本的 Gradle 原始依赖缓存，避免公开包依赖本机私有 build-isolated/downloads 路径。
- 原文件夹内的缓存、运行目录、私人模型配置、日志与安装备份保留在本机并排除公开交付。来源未核实的 art/references/reagent-bottle-reference.png 不纳入公开仓库或源码 ZIP。
- 完整项目包 dist/IC2-newera.zip 包含 IC2-newera/ 顶层目录，排除自身及自身哈希文件以避免递归；其他公开文件逐项回读核对。哈希在同目录 .zip.sha256；本地详细回读记录在 build-isolated/github-source-package-audit.json。

## 当前阶段

项目内容已准备，仓库创建与上传结果需由 GitHub 返回及远端提交核实后补记。此阶段只整理公开交付与导出路径，不修改 Java/运行资源、不重新构建、不运行测试、不启动游戏。最新 JAR 的已有构建安装证据见 server-ai-ui-fix-2026-10-08.md；实机验收仍待测试玩家反馈。
