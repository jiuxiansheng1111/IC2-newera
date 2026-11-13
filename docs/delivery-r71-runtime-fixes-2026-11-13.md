# r71 联合包：启动配方扫描、制作状态与远景资源（2026-11-13）

Minecraft 1.20.1 / Forge 47.4.28 / Java 17。403 个客户端 JAR、345 个服务端 JAR。相对上一次 TaCZ 1.2.0 更新，服务端归档只有下列四个文件及构建清单的内容改变。

| 替换文件 | 目的 | SHA-256 |
| --- | --- | --- |
| `mods/ic2newera-0.2.0-migration1201-all.jar` | MEGA Cells 配方筛查中复用物品替换反向索引，更新助手关于手动等待的说明 | `7dba2477dc2429c4db7fd59465ff178991548f64fac800a042b31223deaed8e5` |
| `mods/extendedae_plus-1.5.5.jar` | 补齐制作 CPU 状态界面方法名称的运行时映射 | `e66afb708b3266ed762d7b55b05cd6c01251a081d02829b3dbc28f0a6515821b` |
| `tacz/Applied Armorer-v1.1.4.1-for114+.zip` | 4 个缺远景资源的显示定义回到原主模型 | `d58729c99bd83eacc17e8b5a76655dbcd246c4a90152db14bc6084f8df4b43b6` |
| `tacz/Create Armorer-v1.1.4-for114+.zip` | 7 个同类定义回到原主模型；含一个非任务型号 | `edaae69114d9c8ec090acf3b95466c2be6dd48bc31dea23e080be1d629323fcb` |

保留看门狗 60 秒阈值。优化只在 MEGA Cells 2.4.6-1.20.1 和 OneEnoughItem 1.0.7-hotfix 同时存在时启用：在每轮配方过滤中第一次查询才建立反向索引，过滤结束即丢弃；其他查询仍走原实现。保留每次返回独立可变集合的语义。

[ExtendedAE Plus 1.5.5](https://modrinth.com/mod/extendedae-plus/version/pN9pMjiW) 与现有 JEI 15.56.0.205 一起保留。修订只改 LGPL-3.0-or-later 的原 JAR 中的 `extendedae_plus-refmap.json`；保留全部 class、许可及其他条目。1.6.4 中的正确方法目标用于核对；该版本声明与 JEI 联动时版本范围为 `[15.74.0.232,)`，故没有直接升级。可通过[上游源码](https://github.com/GaLicn/ExtendedAE_Plus)与[局部映射修订脚本](../scripts/create-delight-integration/repair_extendedae_refmap.py)重建。

两份显示资源只移除指向不存在模型或贴图的可选远景定义，有效的远景优化保留。模型、贴图、索引、玩法数据和配方与原包相同，单独的 Grove 等客户端材质包不进服务端。

独立 PCL 实例与两端发布包均已更新。本机、线上活动目录、Windows 包及服务器包的四个修正文件逐项匹配；线上构建清单也经过重新下载比对。部署前另保留完整服务器备份与原文件。Forge 静态依赖检查错误 0，原有元数据提示 110；任务仍为 61 章、2,676 项。按用户要求没有启动、重启或进入游戏，新版本启动时长、进服和实际界面显示留待手动验证。

- Windows 包 SHA-256：`872383808ea1213b9e3fe68a49774836bf3301a3595481af6f8fb503bab2684f`
- 专用服包 SHA-256：`8b36948fd04e5ebb5e08f91e7574c8e404949ab9f7de9b7477849b1c116357c0`
- 四文件增量包 SHA-256：`6d8ee38f2cd790ffde1f7132b5c440345787a4c470883fdbcd7503726ccf9cbf`
