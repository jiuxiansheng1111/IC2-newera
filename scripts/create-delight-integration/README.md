# 齿轮盛宴 × IC2 新纪元联合包

r71 使用玩家提供的 Create-Delight-Remake 导入包和新纪元 r70 的两端发行文件。运行环境为 Minecraft 1.20.1、Forge 47.4.28、Java 17。任务合并使用 AutoFTBQ 的 SNBT 解析器。通过环境变量 `AUTOFTBQ_PATH` 可指定 AutoFTBQ 源码目录，`NEWERA_R70_PROFILE` 可指定已安装并与 r70 清单对应的游戏实例。

## 数据来源与生成

1. 将 r70 的服务端 ZIP 和 Windows MRPack 放在 `dist/IC2-New-Era-r70`，准备玩家提供的原始导入包。运行 `acquire_mods.py --base <原始ZIP> --profile <r70实例>` 获取原清单与原任务，逐文件核对下载的 SHA-1、SHA-512 及长度。
2. 构建新纪元 Forge 发布 JAR。运行 `build_joint.py select --built <新纪元发布JAR>` 生成两端去重清单。默认工作目录为 `build-isolated/player-packaging/20261113-delight-joint`。
3. 运行 `merge_quests.py --base-quests <工作目录>/quests-work/base-extracted --newera-quests scripts/field-guide/config/ftbquests/quests --output <工作目录>/quests-work/merged`。原章节、奖励表和既有进度 ID 沿用底包与 r70。新增任务来自 `merge_quests.py`、`extra_chapters.py` 与 `extra_routes_b.py`。各任务写明持有检测或实际交货，匠魂交货通过注册物品匹配不同合法材料组合。
4. 用原始包、r70 两端文件与已获取模组运行 `tacz_compat.py`。从作者仓库获取带历史的源码后，运行 `repair_ldlib.py --work <工作目录> --upstream <齿轮盛宴Git仓库>` 恢复 PCL 导出漏掉的机器定义及其随附资源。脚本将附件内的 MBD2 脚本与上游历史逐个核对。[作者仓库](https://github.com/Jasons-impart/Create-Delight-Remake)
5. 运行 `acquire_visuals.py --work <工作目录> --profile <r70实例>`，再运行 `build_joint.py overlay` 生成游戏目录。AI 任务索引在最后合并的章节上生成，路径为 `config/ic2newera/quest_knowledge.json`。
6. 运行 `audit_mods.py --plan <工作目录>/selection.json --report <工作目录>/deps.json` 核对两端 Forge 依赖和内嵌库。运行 `build_joint.py export` 生成 PCL 联合导入包，运行 `build_server.py --work <工作目录> --output <新服务端ZIP> --baseline <r70服务端ZIP>` 生成对应的服务端。
7. 本机独立安装使用 `install_joint.py --work <工作目录> --profile <r70实例> --name Create-Delight-New-Era-r71`。随后运行 `verify_release.py` 核对已安装客户端、两个归档、共享模组、任务关系、物品定义和 AI 索引的一致性。

## 专用服务端

`server-sides.json` 固定本次附件的两端分类。`server-config-policy.json` 定义玩法配置与客户端专用文件的处理。专用服需要 KubeJS 的启动和服务端脚本、数据包、MBD2 机器定义、服务器加载的卫星导航界面以及两端同一份任务知识。家庭实例的存档与账户数据不属于发布包。

本次交付的任务数、检索示例与实机验收范围见 [r71 交付记录](../../docs/delivery-r71-delight-quests-2026-11-13.md)。
