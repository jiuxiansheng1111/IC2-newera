IC2：新纪元 四核心测试整合包
版本：0.1.0-test.20261008 / 2026-10-08

直接把这个 ZIP 发给测试玩家。首次导入需要联网下载 Minecraft、Forge 和七个官方模组；
本包已内置最新新纪元 JAR，包含服务器/AI 助手界面修复及终端机。

开始游玩
1. 使用支持 CurseForge ZIP 的启动器，选择“导入整合包/从文件导入”，选中本 ZIP。
   PCL 可将 ZIP 拖入启动器；HMCL、CurseForge 等使用各自的整合包导入入口。
2. 导入为新实例，保持 Minecraft 1.19.2、Forge 43.5.0，选择 Java 17。
   不要把本 ZIP 放入游戏“资源包”菜单。正常导入后共有八个顶层模组 JAR。
3. 等待下载完成，登录自己的 Minecraft 账号，启动并创建测试世界。
   现有世界由玩家自行备份；本包不包含任何玩家存档。
4. 按 I 打开助手，选择 AI 页；配方可在 JEI 查询。详细指南在实例
   IC2-New-Era-Test 文件夹，测试后填写 feedback-template-zh_CN.txt。

四个核心
IC2：新纪元 0.1.0-dev、IC2 Classic 2.1.3.4、Create 0.5.1.i、ProjectE 1.1.0。
另带 Carbon Config 2.0.2、Curios 5.1.6.4、The One Probe 6.2.2、JEI 11.8.1.1034。
Create 已含 Flywheel/Registrate，不要另装重复版本。
七个官方模组按 manifest.json 的固定版本下载，来源链接在 modlist.html 和 mod-lock.json。

测试重点
服务器“状态/生产规划/机器控制”三页，右侧 AI 核心槽，助手按钮/聊天布局；
切换全屏、窗口和 GUI 比例，检查核心空槽/已有核心说明不重叠。
服务器的 AI 聊天不暂停单人世界；真实 API/网络错误按提示排查。
终端机名称只有“终端机”，无附加物品描述，144 格容量（12×3 显示、四页）。
终端配方要求箱子、PCB、集成电路、CPU、GPU、工业 SSD、光纤、黄铜机壳、暗物质。
四矿杂质粉必须清洗；钛/钨净粉需批准的 IC2 高级炉。
完整操作与待验收项目见随包指南。本包已做结构/哈希检查，启动器实际导入和游戏表现待玩家验收。

AI 与多人
外部 AI 默认关闭；每个玩家在“模型设置”填写自己的地址、模型和密钥并自行启用。
本包没有作者/其他玩家的模型密钥、账号、服务器地址或存档。
无需模型 API 时，终端本地托管仍可在同网、服务器装核心且启用控制后运行。
多人请使用相同整合包版本，按服务器规则加入队伍；同名网络不会自动授予权限。
模型生成的终端规则需要玩家审阅确认，服务端会重新校验权限和网络。

安装故障
下载失败：在启动器查看具体文件失败项，按 modlist.html 中固定版本链接重试；
不切换到另一 Minecraft/加载器版本。不要额外安装另一份 IC2/Create/Flywheel。
首次导入需要网络；这是启动器整合包，不含 Minecraft 游戏本体、Java 或启动器。
反馈附复现步骤、界面截图及相关错误片段；发送前移除密钥、账号令牌和服务器凭据。

格式依据
https://support.curseforge.com/support/solutions/articles/9000198500-exporting-a-modpack-for-curseforge-project-submission
https://gdlauncher.com/docs/modpack-manifest-format/
