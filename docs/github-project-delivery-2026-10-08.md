# 完整项目 GitHub 公开交付（2026-10-08）

## 2026-10-08 最新公开更新：玩家包完整整合与GitHub提交

- 用户明确要求将全部当前改动纳入新版玩家包并提交GitHub，旧分享包直接替换；本次授权优先于此前阶段“不上传”的约定。
- 包版本`0.1.0-test.20261008-player-update`，玩家ZIP与完整项目ZIP沿用固定文件名，完整项目顶层仍为`IC2-newera/`。目录只保留当前两份分享ZIP及其哈希。
- 提交范围包括EU电动机及原创模型/贴图、WiFi增幅器、三档权限/授权管理、服务器概览与联网设备、按设备行控制、运行/故障统计、AI读取同一名单及只读诊断、全部实际配方和中文说明。
- 新版JAR为716900字节，SHA-256 `01E3557708E8D157626244875EBF6BC692452901BDB5C916EED092E816C1586B`；沿用已构建并安装版本，8个安装模组哈希已核对。本次不改Java、不重建、不新增/运行测试、不启动游戏、不读取密钥。
- 更新清单随玩家包提供，见[player-update-2026-10-08.md](player-update-2026-10-08.md)；公开仓库保持`jiuxiansheng1111/IC2-newera`的`main`，新提交使用当前完整内容。远端SHA和文件树核对回执保存在本机`build-isolated/github-publication-audit.json`；实际游戏、多人、启动器导入及模型API仍待验收。
- 下方未上传、旧包哈希及旧界面描述均属于此前阶段记录。

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

公开仓库已创建，GitHub返回名称 jiuxiansheng1111/IC2-newera、private=false；连接器复核 visibility=public、默认分支 main。首次完整上传已成功，远端 refs/heads/main 与本地首提交同为 790d6eaf9413c607ec37701f9a32f49b886e0893。该提交包含 479 个公开文件；完整源码 ZIP 内为 477 个文件，另两项是该 ZIP 自身与其哈希文件。随后同步本交付记录并重导出源码 ZIP，最终远端逐项核对结果保存在本机 build-isolated/github-publication-audit.json。

此阶段只整理公开交付与导出路径，不修改 Java/运行资源、不重新构建、不运行测试、不启动游戏。新纪元公开 JAR 与先前已安装修复 JAR 字节/哈希一致；原四核心测试包保持原有 SHA-256。最新 JAR 的已有构建安装证据见 server-ai-ui-fix-2026-10-08.md；实机验收仍待测试玩家反馈。
