# 独立服务器准备与部署审计（2026-10-18）

## 当前状态：已获同意并完成两次启动

用户已明确同意 Minecraft EULA。隔离服使用项目内新建世界，先于 09:46:47 首次 Done，完成数据包重载和八角色生成/回读；原生媒体包于 10:08:19 再次 Done，八角色重启持久化回读通过，两次均正常保存停止。未启动玩家客户端，未使用或修改玩家原存档。当前完整包 SHA-256 为 `4DCEB9D0FF3154480DCD7DED7CCA539B57C263B0D6A26BCD753C2218B747D6B1`。专服原生模型配置、可选独立语音及最新证据见 [原生迁移交付](native-media-migration-2026-10-18.md)。真实玩家联机、界面和穿透尚未验收。下文未启动/未接受说明仅记录首次准备时的状态。

## 首次准备状态（历史）

已准备隔离的 Forge 专用服务器目录：`build-isolated/dedicated-server-20261018/`。准备脚本为 [`scripts/prepare-dedicated-server.ps1`](../scripts/prepare-dedicated-server.ps1)，可重复读取目标 PCL 客户端的 JAR 元数据、核查服务器必需依赖、下载并校验 Forge 官方安装器、生成 Forge 服务端文件并复制双方共用模组。

这次只运行了 Forge 安装器的 `--installServer` 安装步骤；**没有运行 Minecraft 服务端，没有创建世界或触碰真实存档，没有接受 EULA**。目录内 `eula.txt` 仍为 `eula=false`，`world/` 不存在；`start-server.bat` 会在 EULA 未由用户手动同意时直接退出。`server-preparation-audit.json` 保存本次源目录、模组 ID/哈希、Forge 安装器哈希和准备状态。

最终 JAR 已于 2026-10-18 00:58:22 安装到 PCL，00:58:36 同步到本隔离专服。SHA-256 为 `6066C28B77F862883F95E3C0CC7FE3CA1141E4C4DE5C94932EE408EB45D7D7AC`，1,702,180 字节；客户端八个、服务端七个 JAR 的回读哈希均匹配。当前安装回执为 `build/deployment/pcl-installation.json`；历史回执/旧包备份在 `build-isolated/deployment-history/` 及安装器备份目录。

## 真实依赖证据与服务器模组清单

逐个读取了目标实例 `C:/Users/罗斌/Desktop/.minecraft/versions/1.19.2-Forge_43.5.0/mods/*.jar` 内 `META-INF/mods.toml`，并以其中实际依赖关系生成包，而不是把客户端锁定清单整份复制到服务器：

| Mod ID | 版本/本地文件 | 服务端判定 | 证据 |
| --- | --- | --- | --- |
| `ic2newera` | 0.1.0-dev | 必需 | 项目 `mods.toml` 对 Forge、Minecraft、`ic2`、`create` 声明 `mandatory=true, side=BOTH`。 |
| `ic2` | IC2 Classic 2.1.3.4 | 必需 | 新纪元依赖精确版本；IC2 自身 `mods.toml` 将 `carbonconfig` 标记为 BOTH 必需。 |
| `carbonconfig` | 2.0.2 | 必需 | IC2 Classic 的强制依赖。 |
| `create` | 0.5.1.i | 必需 | 新纪元声明 BOTH 必需；Create 的 `flywheel` 依赖标记为客户端侧，服务端清单不单独复制 Flywheel。 |
| `projecte` | PE1.1.0 | 可选但本包纳入 | 新纪元将其声明为 BOTH 可选；目标客户端已安装，且部分配方依赖 ProjectE 内容，因此包内保留供客户端/服务器共同使用。 |
| `curios` | 5.1.6.4 | 可选但本包纳入 | IC2 声明为 BOTH 可选；目标客户端已安装，随当前兼容集保持一致。 |
| `theoneprobe` | 6.2.2 | 可选但本包纳入 | IC2 声明为 BOTH 可选；目标客户端已安装，随当前兼容集保持一致。 |
| `jei` | 11.8.1.1034 | **不复制到专服** | 新纪元将 JEI 声明为 `mandatory=false, side=CLIENT`，它属于玩家查询界面集成。保留在客户端即可。 |

包内七个 JAR 的完整 SHA-256 在 `build-isolated/dedicated-server-20261018/server-preparation-audit.json`。脚本遇到重复 ID、缺少强制依赖或未知的已安装模组 ID 会停止并要求人工分类，不会默默把新客户端模组塞进专服。

## Forge 安装器与可重复准备

本机只有 Forge 43.5.0 的开发库和 client/universal JAR，没有专用服务器安装器。准备脚本从 Forge 官方 Maven 下载固定版本的安装器：

- Forge 官方 1.19.2 下载页将 43.5.0 列为推荐版本，并给出该安装器 SHA-1：[`Forge 1.19.2 downloads`](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.19.2.html)
- 安装器固定 URL：[`forge-1.19.2-43.5.0-installer.jar`](https://maven.minecraftforge.net/net/minecraftforge/forge/1.19.2-43.5.0/forge-1.19.2-43.5.0-installer.jar)
- 官方页面 SHA-1：`0a45a1d96388403c5f16556df81165ba4d96987a`；本次文件 SHA-256：`4869E60456321E99EB5120AE39171C382C27A05858CDFD4B90FF123E3750E681`。

安装器初次访问 Forge 镜像索引时记录 TLS 握手失败；之后使用官方 Forge Maven 与 Mojang 下载源继续工作，安装日志显示所需下载均经校验，最终报告 `The server installed successfully`。脚本会固定核对官方 SHA-1；重跑时若 Forge 运行参数文件已存在则跳过安装，不启动服务端。输出目录固定在项目 `build-isolated/` 子目录；现有 EULA、服务器属性和内存参数会保留，替换不同哈希的受管模组 JAR 前会在输出目录内留备份。

运行准备命令：

```powershell
.\scripts\prepare-dedicated-server.ps1
```

可选参数 `-ClientModsDirectory` 指向另一个 1.19.2 Forge 43.5.0 客户端 `mods` 目录；`-ForgeInstallerPath` 仅接受已存在且 SHA-1 与官方发布值一致的安装器。脚本要求 `java -version` 为 Java 17。它只读来源 JAR 的公开 `mods.toml` 和哈希，不扫描配置、世界或密钥。

## 生成目录配置与安全默认值

- Forge 官方 `run.bat`/`run.sh`、参数文件与 libraries 已生成；推荐从 `start-server.bat` 启动，它会先检查 EULA 文件及 Java 17，再调用 Forge 的 `run.bat ... nogui`。
- JVM 默认参数为 `-Xms2G -Xmx6G -XX:+UseG1GC`。可在隔离服务器目录 `user_jvm_args.txt` 按实际主机内存调整；脚本仅补缺少的参数，不覆盖用户已设置的 Xms/Xmx/GC 选项。
- `server.properties` 初始设置 `online-mode=true`、`white-list=true`、`enforce-whitelist=true`、`enforce-secure-profile=true`，RCON/query/命令方块关闭，人数上限 8；`server-ip=` 留空由操作系统绑定地址，端口 25565。第一次开放前应只向需要的可信客户端开放端口，并先用控制台配置白名单与管理员。
- 若走内网穿透，只转发 Minecraft 游戏端口并遵循隧道服务商对 TCP 的配置；不要额外暴露 8012。当前 Java 媒体通道由游戏服务器访问服务器主机自己的 `127.0.0.1:8012` ChatRobot 桥，再通过 Minecraft 分块数据包只发送给发起请求的玩家。客户端不需要安装 Python，也不需要直连或穿透 8012；语音和音乐均在请求客户端本机播放。
- Java 端已经包含机器人聊天、语音和手机音乐的服务器中继源码。它要求承载游戏服务端的同一主机运行 ChatRobot/AstrBot 与桥接插件，并加载对应新端点。当前 AstrBot 运行实例尚未重载新的 music 路由；6185 管理面板使用已保存登录凭据返回 HTTP 401，因此不能确认路由已在线或功能可用。用户答复/正常重载之前，音乐桥只算源码集成，尚未完成端到端验证。原有 LLM 配置不需要修改。

## 下一步部署及验收

1. 用户先阅读 Minecraft EULA；仅在本人同意后，手动将隔离目录 `eula.txt` 改为 `eula=true`。准备器从不自动同意。
2. 首次运行 `start-server.bat`，确认 Forge 完成专服加载、注册表和数据包没有错误并到达 `Done`。这一步目前尚未执行。
3. 两个不同客户端使用 Minecraft 1.19.2、Forge 43.5.0、同一新纪元版本和相同 BOTH 模组集连接。分别核对机器人归属/多人命令、工业手机、断线重连和服务端重启后的持久化。JEI 可只装客户端。
4. 通过目标内网穿透地址让两端连接，确认不需要额外暴露游戏外 HTTP 端口。音乐与语音需在桥接路由加载后检查请求只返回给发起玩家，并验证手机/Wi-Fi/权限、多人隔离及本机音频播放。

当前交付只证明：本地模组依赖元数据审计通过、Forge 专服安装器成功准备了隔离文件、受管 JAR 哈希匹配、EULA 仍未接受且世界未创建。**它不代表服务端已启动成功、Java 核心已通过专服实测、多人运行或内网穿透验收已通过。**
