# r71：JEI 与 ExtendedAE Plus 同步升级（2026-11-13）

本次在 Minecraft 1.20.1 / Forge 47.4.28 上同时更换两只 JAR。AE2 本体保留 15.4.11，ExtendedAE 保留 1.20-1.4.18-forge。

| 模组 | 上一版 r71 | 当前作者原包 | 版本与来源 |
| --- | --- | --- | --- |
| JEI | 15.56.0.205 | `jei-1.20.1-forge-15.74.0.234.jar` | [15.74.0.234，Forge 1.20.1，作者标记为 Beta](https://modrinth.com/mod/jei/version/6E4ps2ER) |
| ExtendedAE Plus | 1.5.5 本地映射修正版 | `extendedae_plus-1.6.4.jar` | [1.6.4，Forge 1.20.1，Release](https://modrinth.com/mod/extendedae-plus/version/auasuDQR) |

1.6.4 自带制作 CPU 手动等待状态的生产环境方法映射，并声明客户端 JEI 兼容范围从 15.74.0.232 起；本次选择满足要求的 15.74.0.234。完整 Windows 包从作者 CDN 下载两只 JAR，并核对文件长度、SHA-1 和 SHA-512；不再内嵌旧 1.5.5。保留上一轮配方扫描和远景资源修正。

本机已有 PCL 实例和线上服务器活动 mods 目录均已替换。服务端相比上一份已核验包仅新增上述两只 JAR、移出两只旧 JAR，另更新构建清单及来源说明。线上重新下载的模组及两份清单与本地服务端字节相符；替换前保存服务器备份。完整服务端归档只交付到本地，线上仅传增量。未动地图，未启动游戏、重启服务器或进服验收。

整包静态核验：客户端 403、服务端 345 个模组 JAR；61 章、2,676 项任务，助手索引 SHA-256 保持 `ac87cfbcb08a1eeb0ad4699434ddda032b927358cc1194e24b1ed8114eb7d310`。两端共用 JAR 全部匹配，Forge 依赖检查错误 0，元数据提示仍为 110。

| 本地交付 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `Create-Delight-New-Era-r71-Windows.mrpack` | 599409832 | `e96574da3f0bfce33cf2d9dc42c062419147745a571f9d1e22a64bb43c1b47c3` |
| `Create-Delight-New-Era-r71-Server.zip` | 1437526718 | `8fb2ee4160466efa7568a80fc4f00ae3a1b514b4ab01220106654a8b5c978939` |
| `Create-Delight-New-Era-r71-Player-Update.zip` | 31132728 | `33bfb2ebae8c3812b0a620f9d83dfbd03b9f45291056c9d6557e455a547399a5` |

增量用于已有 r71 / TaCZ 1.2.0 玩家；先将旧 JEI 和旧 ExtendedAE Plus 移出 mods，再按包内说明合并文件。第一次安装导入完整 Windows 包。旧增量另行归档，避免回装旧版。
