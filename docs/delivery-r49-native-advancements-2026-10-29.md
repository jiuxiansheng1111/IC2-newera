# r49：新纪元原生进度

日期：2026-10-29。目标为独立Minecraft 1.20.1 / Forge 47.4.28；原1.19.2源码和世界未改。

## 变更

- 删除独立科技树界面、树图排版/样式、TechnologyService、TechnologyTree和派生目录、TechnologyDataPacket及34份旧任务资源。客户端助手只保留AI功能，并可跳转原版进度。
- 原版「进度」内新增「IC2：新纪元」独立分页，共33个精选里程碑。5组内容：矿物/合金加工、电子材料、工业网络、机器人宠物与电源、虫洞。只引用本模组的真实物品模型/注册内容；没有收录其他模组的全物品或配方树。
- 根节点使用minecraft:tick，物品节点使用minecraft:inventory_changed。节点连线只组织显示，不是配方前置；没有新增配方门禁、奖励、物品消耗或全服聊天刷屏。ProcessGraph、原有配方与依赖模组进度保持。
- 原研究能力NBT继续注册保存，避免丢弃历史数据，但不迁移旧科技统计为新原生进度；新进度由原版库存变化判定。内置AI知识已同步。
- 主网络协议由33升级为34，两端必须使用新JAR；其他机器人/语音协议保持。当前Linux固定语音附件未改，继续配套，不加入翻唱或英文语音。

## 验证和产物

未新增或运行自动化测试。执行离线clean build -x test及资源更新后的build -x test，均成功；编译保留既有弃用警告。静态解析包内JSON，确认旧树类/资源不再入包、33个父节点及条件均完整且目标均为ic2newera。参考本地Create6.0.8的原生root.json以及Forge官方进度资源文档：https://docs.minecraftforge.net/en/1.20.x/resources/server/advancements/ 。

| 产物 | 字节数 | SHA256 |
|---|---:|---|
| ic2newera-0.2.0-migration1201-all.jar | 10401538 | 62F360868F18BF738E0F5CBD30302F569CDDBA33FAD793CBB15566F6F3962EE9 |
| dist/IC2-New-Era-1.20.1-Forge-47.4.28-r49.mrpack | 314011531 | B8DF785FC99FC66838B37EB158201113720413C1B58AC71D317842BD6554E4AD |
| dist/IC2-New-Era-Server-1.20.1-r49-Full.zip | 259505919 | E4DA909EF73D0DCC45CA50FC1C72CD7C8863E5D153554C36AB0A1FA76E29B4DE |

完整服务端仍含官方Forge核心、libraries、25个模组及根目录启动器。已有服务器只替换新纪元JAR，不将初始世界/配置覆盖到现场。新版归档回读和本地复制哈希通过后，dist旧r48完整服务端ZIP已移至回收站；固定语音仍是有效附件，保留。

## 部署状态（已完成安装）

- 用户确认正常退出MC；15:20:56用既有PCL安装脚本完成本机独立1.20.1更新。31个锁定JAR中仅新纪元需要复制，安装后SHA256与构建一致。旧JAR备份在newera/build/deployment/backups/20261029-152055-819，回执pcl-installation-1201.json已复制到本轮检查点。
- 面板正常停止服务器，确认已关闭后，将旧新纪元JAR移至/home/container/backup-before-r49-20261029，再将update-r49中的新版移入活动mods。活动JAR再次下载回读，SHA256仍为62F360868F18BF738E0F5CBD30302F569CDDBA33FAD793CBB15566F6F3962EE9，与本机一致。
- 重新开机通过：北京时间2026-10-29 15:25:47（服务端日志07:25:47）出现Done (9.579s)；共加载4403个advancements，未见ic2newera进度解析失败。15:28:47控制台list正常响应，在线0/20。
- 固定语音仍使用现有本地9881协调服务，日志明确报告reusing the existing local coordinator。世界、API配置、固定语音agent及启动参数未改；本轮未做实际听音验收。
- 根目录旧IC2-New-Era-Server-1.20.1-r48.tar.gz及确认已空的同名解压目录已移入面板回收站，面板标示保留24小时。回退JAR另存备份目录，不在活动mods内。
- 日志含Valkyrien Skies可选兼容/mixin错误及启动后一次Can't keep up提示，但服务随后成功启动并响应命令。本轮不以启动通过宣称所有依赖功能均无缺陷。
- 新原生进度分页、物品触发和实际客户端重新入服尚未实机验收；未新增或运行自动化测试。
- GitHub源码及内置AI说明已同步；本次安装状态记录另作后续提交，见检查点public-source-receipt.json。第三方JAR、声音、角色资产、密钥和存档不上传。

## 回退与工作点

工作点build-isolated/migration-1201/checkpoints/build49-native-advancements包含before/src、旧JAR、构建日志、产物与清理回执。已使用现有scripts/install-pcl-mods-1201.ps1安装。需要回退时先正常停服，使用远端backup-before-r49-20261029及本机部署备份恢复两端旧JAR。禁止热替换活动JAR。
