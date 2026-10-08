# 四核心测试整合包交付（2026-10-08）

## 2026-10-08 最新分享交付：四核心测试整合包

- 按用户上下文，四核心为新纪元、IC2 Classic、Create、ProjectE；同时保留当前Carbon Config、Curios、The One Probe、JEI，共8个顶层模组。版本Minecraft1.19.2 / Forge43.5.0 / Java17。
- 已生成标准CurseForge ZIP：dist/IC2-New-Era-Test-2026.10.08.zip，585060字节；SHA-256 24E70C8547F3F18924D17C0EF4AFF93FECF988FC05F5319C853436BCE6CB1881；包版本0.1.0-test.20261008。
- 包内直接提供最新新纪元修复JAR（DBECDC802518A1F00B4CD794F67D6A2F0C716298652038BBAC17D253401F0613）；其余7个官方模组按manifest.json固定projectID/fileID由接收方启动器下载，首次导入需要联网。Create原JAR已包含Flywheel/Registrate，不重复添加。
- 按允许清单打包，只读取8个原JAR、运行锁定清单及公开项目指南；不遍历/读取玩家配置，不包含账号、API Key、存档、日志、服务器地址、Minecraft本体或启动器。新增中文默认语言/自动GUI比例，AI沿用首次生成的关闭默认值。
- 压缩包包含玩家指南、多人/模型设置说明、版本哈希锁定表、原作者来源链接和问题反馈模板。打包脚本scripts/package-shareable-modpack.py；静态回读/源哈希/必需依赖ID核对通过，记录build-isolated/shareable-modpack-audit.json。
- 此阶段只做分享包导出，不修改已安装模组、不构建新JAR，不上传/发布、不启动游戏、未新增/运行测试。启动器实际导入和朋友游玩效果尚未观察。说明docs/shareable-test-pack-2026-10-08.md。

## 分享和导入

把dist/IC2-New-Era-Test-2026.10.08.zip发给测试玩家即可。不要把整个.minecraft目录或私人config目录一起发送。ZIP内置README-zh_CN.txt，dist/README-zh_CN.txt也可单独查看。

接收方使用PCL、HMCL或支持CurseForge格式的启动器，选择从文件导入整合包，保持Minecraft1.19.2和Forge43.5.0并使用Java17。首次导入联网下载七个官方模组及游戏依赖，最新新纪元JAR已在overrides/mods。不是游戏“资源包”菜单中的贴图包。没有内置Java、启动器或Minecraft本体。

按清单下载后，8个顶层模组应与当前开发实例相同。CurseForge文件的原始名称可能与本地安装脚本命名不同，以mod-lock.json中的modId、版本和SHA-256识别。服务器/玩家需要使用相同整合包版本；玩家自行配置外部模型。外部AI默认关闭，无密钥时终端的本地网络托管仍按当前已实现规则工作。

## 来源和格式依据

- [CurseForge官方导出结构](https://support.curseforge.com/support/solutions/articles/9000198500-exporting-a-modpack-for-curseforge-project-submission)：ZIP根目录manifest.json，配置/自有内容放overrides；官方托管模组通过manifest清单引用。
- [GDLauncher官方格式说明](https://gdlauncher.com/docs/modpack-manifest-format/)：minecraft版本、加载器ID、项目/文件ID与overrides字段。
- [PCL官方导入代码](https://raw.githubusercontent.com/Hex-Dragon/PCL2/main/Plain%20Craft%20Launcher%202/Modules/Minecraft/ModModpack.vb)：读取CurseForge清单、Forge版本、官方文件ID并复制overrides。只是格式核实，没有在当前启动器中实际导入本包。

| 模组 | 版本 | 项目ID / 文件ID | 官方来源 |
| --- | --- | --- | --- |
| IC2：新纪元 | 0.1.0-dev；当前UI修复哈希 | 包内自有JAR | 本项目开发组 |
| IC2 Classic | 2.1.3.4 | 242942 / 8545995 | [作者文件页](https://www.curseforge.com/minecraft/mc-mods/ic2-classic/files/8545995) |
| Create | 0.5.1.i | 328085 / 5797604 | [作者文件页](https://www.curseforge.com/minecraft/mc-mods/create/files/5797604) |
| ProjectE | 1.1.0 | 226410 / 4860858 | [作者文件页](https://www.curseforge.com/minecraft/mc-mods/projecte/files/4860858) |
| Carbon Config | 2.0.2 | 898104 / 8018088 | [作者文件页](https://www.curseforge.com/minecraft/mc-mods/carbon-config/files/8018088) |
| Curios API | 5.1.6.4 | 309927 / 5843737 | [作者文件页](https://www.curseforge.com/minecraft/mc-mods/curios/files/5843737) |
| The One Probe | 6.2.2 | 245211 / 3965693 | [作者文件页](https://www.curseforge.com/minecraft/mc-mods/the-one-probe/files/3965693) |
| JEI | 11.8.1.1034 | 238222 / 5846858 | [作者文件页](https://www.curseforge.com/minecraft/mc-mods/jei/files/5846858) |

## 当前证据

已验证8个源JAR符合当前runtime-mods.json的SHA-256及声明ID，Create嵌套库可读取，必需依赖ID都由Forge/Minecraft、清单或嵌套JAR覆盖。ZIP逐项回读与原始字节一致，内置新纪元与当前已安装版本相同。清单和公开来源固定文件版本，没有升级模组或重复添加Flywheel。

只采用明确列出的项目公开文件和新生成的语言选项；未读取私人配置或玩家API Key。压缩包的文件列表保存在审计JSON。当前仅能确认格式、依赖及字节；首次下载成功、启动器导入和游戏表现仍待接收方验收，不能写成已实测通过。
