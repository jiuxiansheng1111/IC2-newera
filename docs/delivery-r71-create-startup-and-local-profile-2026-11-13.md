# r71：JEI 启动崩溃与普通 Forge 实例（2026-11-13）

PCL 现在选中已有的 `1.20.1-Forge_47.4.28`。联合包内容已合入该独立版本目录，旧的 `Create-Delight-New-Era-r71` 已从启动列表移出，原目录作为本地备份保留。两个原有存档的 level.dat 和九张截图在合并前后的大小和时间均一致。

## 根据出错顺序判断

当晚 23:11 的客户端日志中，首先失败的是旧 `jei_optimize 0.5.0` 的两处必需注入：它认不出 JEI 的物品搜索构造方法，也找不到酿造配方方法。Forge 在构造阶段报出两个错误，并跳过模型注册，随后出现盔甲架护甲层找不到模型的错误。单凭最后的渲染堆栈会误判。此次未删除 JEI、Tetra 或 ModernFix。

保留 JEI 15.74.0.234、ExtendedAE Plus 1.6.4，换为作者的 [Just Enough Threads 0.14.9，Forge 1.20.1](https://www.curseforge.com/minecraft/mc-mods/just-enough-threads/files/9568167)。新版的模组 ID 是 `justenoughthreads`。核对了实际 JAR：其物品搜索注入描述符匹配 JEI，旧的酿造方法不再是必需条件。新版只装客户端。两套原实例的 Create 核心 JAR 虽文件名不同，内容相同，合并后只装一份 Create 6.0.8。

| 提示来源 | 本次日志和文件的实际证据 |
| --- | --- |
| Create Better Storages | 确实有旧 MountedStorage 目标不存在的警告；之后日志仍显示兼容补丁执行且仓储方块已注册。保留原模组及 Create 6 桥接，以免丢失已有仓储 ID。运输中的仓储功能尚未实测。 |
| Fruits Delight、妖怪们的归家、越肩视角 | 包里有旧版 Create 分支。前两者还内嵌 6.x 兼容库；越肩视角按版本选择对应分支。这几项均未出现在这次最早的异常中。 |
| Amendments、Create Fluid Stuffs / DragonLib、Vanillin | Amendments 的兼容类有旧 Flywheel 引用；DragonLib 是内嵌库；提示中的 Vanillin 类由 Vanillin 自己提供。不能用全包类名搜索断言这些功能全部可用，也不能据此推断缺少 Create 本体。 |

另在原 Forge 实例的既有日志发现：CMM 包的诡异菌钓竿手持模型有一处错误 JSON 括号。用仅含该模型的客户端资源包修正，保持原资源包原样；已启用 CMM 的本机只调整了这一项资源顺序，其他选项及按键的原始字节没有改变。

## 安装与核对

安装器按模组 ID、版本和文件散列把发布文件合到原实例。保留已有小地图、Freecam、输入法修复、MC 百科搜索及史蒂夫模型。旧的 Oculus Flywheel Compat 与联合包的 Colorwheel 声明不兼容，已备份移出。回退副本、玩家目录、账户、服务器连接及存档不进入公开仓库或分发包。

本机 403 个顶层 JAR 与 Windows 包全部匹配。完整服务端包为 344 个 JAR；线上面板的 344 个活动文件名逐项一致，此次移出的旧优化模组内容和备份吻合，新服务端说明和清单已下载回读并核对散列。没有重启服务器、启动客户端、进入世界或联机验收。

Forge 依赖检查为 0 个错误、105 项元数据警告；61 章、2,676 项任务与 AI 任务索引一致。此记录证明交付的实际文件与已知注入修正对应，不代表所有模组在游戏内的玩法都已实测。

| 本地交付文件 | SHA-256 |
| --- | --- |
| Windows 完整包 | `e68d19e21ccf67029c4d0dcdc39e52ec4979f9294f94689f601ed1642540defb` |
| 服务端完整包 | `c2a2507aefeed5c7f5ea6c5d6daac4514af20f1647e04ad95eb8839c6c0743a7` |
| 已有 r71 玩家的累计更新包 | `d072ac30f4f6a22b839b633091b3c1785b87f6a1a1a14bad3778165d72f1d1cf` |
