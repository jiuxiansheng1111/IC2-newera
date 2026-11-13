# 齿轮盛宴 × IC2 新纪元联合包

r71 使用玩家提供的 Create-Delight-Remake 导入包和新纪元 r70 的两端发行文件。运行环境为 Minecraft 1.20.1、Forge 47.4.28、Java 17。任务合并使用 AutoFTBQ 的 SNBT 解析器。通过环境变量 `AUTOFTBQ_PATH` 可指定 AutoFTBQ 源码目录，`NEWERA_R70_PROFILE` 可指定已安装并与 r70 清单对应的游戏实例。

## 数据来源与生成

1. 准备玩家提供的原始导入包和 r70 服务端 ZIP、Windows MRPack。本机归档在 `W/r70-release`，其他位置可通过 `NEWERA_R70_RELEASE` 指定。运行 `acquire_mods.py --base <原始ZIP> --profile <r70实例>` 获取原清单与原任务，并核对下载字节和锁定散列。
2. 构建新纪元 Forge 发布 JAR。运行 `build_joint.py select --built <新纪元发布JAR>` 生成两端去重清单。默认工作目录为 `build-isolated/player-packaging/20261113-delight-joint`。
3. 运行 `merge_quests.py --base-quests <工作目录>/quests-work/base-extracted --newera-quests scripts/field-guide/config/ftbquests/quests --output <工作目录>/quests-work/merged`。原章节、奖励表和既有进度 ID 沿用底包与 r70。新增任务来自 `merge_quests.py`、`extra_chapters.py` 与 `extra_routes_b.py`。各任务写明持有检测或实际交货，匠魂交货通过注册物品匹配不同合法材料组合。
4. 用原始包、r70 两端文件与已获取模组运行 `tacz_compat.py`。从作者仓库获取带历史的源码后，运行 `repair_ldlib.py --work <工作目录> --upstream <齿轮盛宴Git仓库>` 恢复 PCL 导出漏掉的机器定义及其随附资源。脚本将附件内的 MBD2 脚本与上游历史逐个核对。[作者仓库](https://github.com/Jasons-impart/Create-Delight-Remake)
5. 运行 `acquire_visuals.py --work <工作目录> --profile <r70实例>`，再运行 `build_joint.py overlay` 生成游戏目录。AI 任务索引在最后合并的章节上生成，路径为 `config/ic2newera/quest_knowledge.json`。
6. 运行 `audit_mods.py --plan <工作目录>/selection.json --report <工作目录>/deps.json` 核对两端 Forge 依赖和内嵌库。运行 `build_joint.py export` 生成 PCL 联合导入包，运行 `build_server.py --work <工作目录> --output <新服务端ZIP> --baseline <r70服务端ZIP>` 生成对应的服务端。
7. 本机独立安装使用 `install_joint.py --work <工作目录> --profile <r70实例> --name Create-Delight-New-Era-r71`。随后运行 `verify_release.py` 核对已安装客户端、两个归档、共享模组、任务关系、物品定义和 AI 索引的一致性。

## 对已验收 r71 的兼容性补丁

`hotfix-lock-r71.json` 锁定 SuperMartijn642 核心库、食物品质、TaCZ 1.2.0、TaCZ addon 1.2.0 和 TaCZ JS 1.5.0。锁中记录发布平台的文件标识、目标文件名、SHA-512、字节数、端侧、被替换文件和需排除的旧 Hotai 补丁。TaCZ JS 来自 CurseForge 文件 9503464，其余锁定构件来自 Modrinth。获取工具核对发布元数据和实际下载字节，已缓存且散列匹配的文件可离线复用。选择步骤依据模组 ID 和版本锁去重，生成游戏目录时排除旧补丁。

1. 运行 `acquire_mods.py --base <原始包ZIP> --profile <r70实例>`，再运行 `build_joint.py select --built <r71新纪元JAR>` 和 `audit_mods.py`。
2. 运行 `build_joint.py overlay` 和 `build_joint.py export`。
3. 对已停止运行且回执完整的独立实例，逐个运行 `patch_installed_mod.py --work <工作目录> --profile <r70源实例目录> --name Create-Delight-New-Era-r71 --mod <新JAR文件名>`。工具逐文件核对现有回执、确认新旧 JAR 的 mod ID 一致、备份被替换文件，并按锁更新已存在的覆写项或移除明确列出的过时覆写项。
4. 完整重建包时运行 `build_joint.py export` 和 `build_server.py`。如果版本锁只要求移除已发布包中的特定覆写，可运行 `patch_release_archives.py`，它流式重写已有 ZIP、更新服务端内外清单，并逐个原子替换客户端和服务端文件。最后运行 `verify_release.py`，核对 `r71-release-manifest.json` 中两端 JAR 数、共享模组字节、锁定补丁 SHA-512、锁定覆写排除项和 `minecraftOrDedicatedServerStarted` 状态。

服务端生成器保留旧 ZIP 直到新 ZIP 和审计回执都完整写入同目录临时文件，并通过 `os.replace` 原子替换。磁盘不足时压缩会失败并清理一次性暂存文件，旧 ZIP 与旧回执保持原样；请清理有授权的临时文件后重试。生成器仍使用短路径临时解包目录，结束或失败都会删除该目录。归档定点修补器不解包整服，写出 CRC 验证通过的新 ZIP 后才逐个替换。该流程只改独立 r71 实例，不启动游戏、不操作存档，也不修改服务器。

`advisory_lock.py` 提供系统级文件锁。客户端包导出、服务端包生成、定点归档修补和独立实例补丁共用一个发布锁；安装器额外在写入前再次核对实例回执与所有登记文件。各归档替换前也会比对开始处理时的 SHA-256。锁等待期间不会覆盖另一进程刚完成的新版本，异常退出时系统自动释放锁。

## 专用服务端

`server-sides.json` 固定本次附件的两端分类。`server-config-policy.json` 定义玩法配置与客户端专用文件的处理。专用服需要 KubeJS 的启动和服务端脚本、数据包、MBD2 机器定义、服务器加载的卫星导航界面以及两端同一份任务知识。家庭实例的存档与账户数据不属于发布包。

本次交付的任务数、检索示例与实机验收范围见 [r71 交付记录](../../docs/delivery-r71-delight-quests-2026-11-13.md)。

夜间第一轮 r71 更新为 MEGA/OEI 配方扫描和两份 TaCZ 外观包提供修正；保留的 1.5.5 refmap 工具仅用于复现[历史交付](../../docs/delivery-r71-runtime-fixes-2026-11-13.md)。当前使用作者发布的 JEI 15.74.0.234 和 ExtendedAE Plus 1.6.4。运行 `build_joint.py select` 会按来源和散列选择新版 JEI，优先于旧 r70 实例的 JEI；PCL 从作者 CDN 获取这两个文件。按既有流程导出和安装后，`verify_release.py` 核对两端与下载清单，`build_r71_player_patch.py` 输出适合已有 r71 的累计覆盖包和校验文件。[当前版本记录](../../docs/delivery-r71-jei-extendedae-2026-11-13.md)
