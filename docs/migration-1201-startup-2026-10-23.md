# 1.20.1 启动实测及修复（2026-10-23）

用户已明确授权接管桌面，随后在崩溃后要求继续。早先“等待桌面许可”状态已结束。下列均为独立实例 `1.20.1-Forge_47.4.28`，未进入旧世界。

## 实际故障与处理

1. 13:20:28 的 FML 崩溃：依赖声明使用简写版本，实际农夫乐事版本为 `1.20.1-1.3.4`、森罗为 `1.6.0-forge+mc1.20.1`。第21轮改为实际精确版本，32秒构建通过并安装。JAR SHA256 `F48184C6D47552726E6EE5200AA20991447E6DFE2E386B3797D7535672AE5E86`。
2. 修复前发现迁移目录的 `mods.toml` 全为 NUL（1691字节），原因未确定。与第20轮1043份封存源码对照，仅该文件和预期的安装脚本修正不同。保留损坏文件，从同检查点恢复这一份第一方声明，再修改和读回；未恢复任何配置或密钥。
3. 13:43:26 的客户端崩溃：Ad Astra 1.15.21 请求 Create 6 才有的 `CreateRegistries`，当前机械动力为0.5.1j。官方1.15.20更新说明明确切换Create0.6.x。将Ad Astra锁定为仍采用旧机械臂注册API的官方1.15.19，核对官方SHA512和实际Create方法签名；配方、世界生成、模型和中文资源与1.15.21一致。保留原构件及已安装旧包备份。1.15.19不包含后续1.15.21的流体容器修复，不能宣称完全等价。
4. 第22轮35秒构建通过，更新Ad Astra及新纪元2个JAR，14项安装哈希回读一致。JAR SHA256 `2C68D3945CD9EF978580C1EDA05B429A9B2E35C55FCAEE673EF3F79E27707156`。真实到达主菜单，显示MC1.20.1/Forge47.4.28及18个含内嵌模组。
5. 13:49:52 点击单人游戏后崩溃：银矿placed feature使用不存在的 `minecraft:triangle` height provider。对照目标原版 `ore_iron_middle.json`，改为 `minecraft:trapezoid`；原高度、密度、矿脉大小保留，资源生成源同步。第23轮31秒构建通过，仅封存，未安装。
6. 世界生成静态复审发现四种原矿的biome modifier路径缺少Forge注册目录一级。第24轮将镍/铬/钛/钨的四份JSON移至 `data/ic2newera/forge/biome_modifier/`，原配置不改，迁移脚本同步防止旧路径重新进入。第24轮28秒完整构建通过，863份JSON/mcmeta可解析，包内不存在旧路径。

## 当前交付

第24轮JAR `ic2newera-0.2.0-migration1201-all.jar`，3,821,791字节，SHA256 `59DC827E508C51C3C0682A7231F46AE26C125A140551AE889436DB502A3FD67E`。1043份第一方工程、资源生成源、构建日志、包审计及安装回执封存于 `build-isolated/migration-1201/checkpoints/build24-worldgen/`。无Java进程后安装，仅替换新纪元1项，14项回读一致；已通过PCL再次启动，正在复核创建新世界。

没有新增或运行自动化测试、没有clean、没有修改存档文件。世界尚未创建，机器人生产线、料理、网络、战斗、核能等具体功能尚未验收；主菜单成功不能代表功能通过。GitHub尚未同步。

## 官方兼容证据

- [Ad Astra 1.15.20 更新说明](https://modrinth.com/mod/ad-astra/version/Qf7QFXk2)：更新Create0.6.x兼容。
- [选定的Ad Astra 1.15.19发行物](https://modrinth.com/mod/ad-astra/version/ZXcgZ31q)：Minecraft1.20.1 Forge。
- [官方Create6兼容问题](https://github.com/terrarium-earth/Ad-Astra/issues/692)。

## 第25轮：Create原生Classic兼容配方导致的后续崩溃

第24轮已越过银矿注册表错误，但14:09:29仍在准备单人游戏时崩溃；用户提供的 `错误报告-2026-10-23_14.09.54.zip` 中crash报告确认 `Item: ic2:ingot_silver does not exist`。只查看crash报告和压缩包目录，没有读取打包的启动脚本、PCL日志或登录参数。

对安装的Create0.5.1j原生数据逐条检查，12条 `mod_loaded:ic2` 配方原本针对Classic：8个smelting/blasting引用旧的ingot_*，4个splashing引用nugget_*；Refactored沿用同一mod ID使它们错误启用。运行时RecipeGuard发生在配方反序列化之后，无法兜底这类注册项解析崩溃。

修复全部以同一个ProcessGraph为来源：8条单倍早期熔炼按精确原ID和真实银/锡/铝/铀锭生成，保留数量、200/100tick和0.1经验，分类LEGACY_PROCESS，工业规划不使用；4条金属粒水洗在根级 `native_recipe_exclusions` 记录原输入、原输出和原因，并生成同ID的false条件资源，加载时输出审计日志。未放宽任何routes的requireItem或阶段校验，受控矿物粉碎/洗粉工艺保留；没有修改第三方JAR或捏造等价金属粒。

第25轮完整构建40秒通过；875份JSON/mcmeta解析及12条精确覆盖核对通过。JAR3,828,778字节、SHA256 `31F31B1BC786E62F58B4468DC10BC5B88228806D789B3CF4A5B34B8212C0724C`。源码1043文件及日志、包、回执封存 `checkpoints/build25-create-ic2-recipes`。已确认游戏因崩溃关闭并安装，1项更新、14项哈希匹配；已通过PCL重新启动，正在复核。未运行自动化测试、未修改旧实例、未上传GitHub。

## 第26轮：科技任务与原生进度路径

第25轮真实日志已确认19种材料、369条路线及四条精确排除成功加载。下一阶段科技任务表报错，列出Classic青铜盾和精炼铁两个不存在物品，未放宽任务校验。

- 青铜盾任务保留内部节点ID，中文改为“青铜阶段的盾牌防御”，明确说明IC2R缺少Classic青铜盾，使用原版盾牌完成防御教学，不宣称恢复青铜盾物品。
- 旧精炼铁节点改为“IC2钢锭衔接”。核对IC2R真实原生进度 `ic2:ic2/build_generator/build_compressor/acquire_refined_iron`，它实际检测 `ic2:steel_ingot`。节点前置变为已批准低碳钢；ProcessGraph新增1:1低碳钢锭到IC2钢锭同级转换，只用于IC2部件衔接，无逆转换或原矿/铁锭变钢捷径。两者不宣称与Classic精炼铁等价。
- 科技任务内合金炉中文同步成实际“新纪元合金兼容炉”，全部任务的IC2进度路径对照目标JAR不存在缺失。

第26轮35秒完整构建通过，876份JSON/mcmeta；JAR3,829,749字节，SHA256 `4802145222BCDF3A350B2BC48071231B5B8ED5AED148D0AF45893BED97C05E3B`。确认崩溃后游戏关闭并安装，14项回读通过；源码1043文件、资源源文件、构建/包审计与安装回执封存 `checkpoints/build26-technology`。已再次通过PCL启动复测，未运行自动化测试。

## 第28轮：100%世界加载卡住的原生配方缓存问题

第26轮实机已加载19种材料、370条路线、30个科技任务，进入世界创建页面，并通过游戏创建 `IC2-1201-20261023-验收`（创造、允许命令、普通地形）。世界生成达到100%后停在加载界面，尚未进入玩家场景。

只读 `jcmd Thread.print` 显示服务端持续执行 `RecipeGuard.restoreApprovedMaceratorRoutes -> BasicMachineRecipeManager.addRecipe -> MachineRecipeHelper.removeCachedRecipes`，渲染线程等待服务器准备完成。只读取线程栈，未读取启动命令行、令牌或堆内存。目标IC2R JAR字节码核实：varargs/collection的replace=true循环使用新输入键删除旧条目和清缓存；MachineRecipe重载虽删对旧map key，仍以新输入清缓存；输入对象表示不同会持续命中旧缓存。

第27轮仅中间构建审阅，未安装；发现换重载仍不充分后弃用。第28轮改为迭代器按批准输入精确移除匹配旧配方，原生迭代器同时按旧输入清缓存，再使用replace=false加入图中批准配方；所有循环有限，意外冲突明确失败而不循环。没有关闭RecipeGuard、改变批准阶段或替换原生机器。Luna独立只读审阅确认当前图没有重复输入键；未来重叠tag路线仍需复核。

第28轮35秒完整构建通过，876份JSON/mcmeta可解析；与26包相比仅RecipeGuard及其内部类变化。JAR3,830,164字节，SHA256 `451BE1933A2FB6B1FDDFE1F68377E0FAE843530B2B7120DA28D0E1B2D2B3099E`；源码1043文件、资源生成源、日志和包审计保存在 `checkpoints/build28-native-recipe-cache`。**尚未安装、尚未验证该卡住修复**。当前游戏仍运行26版卡住；已经请用户关闭后告知，收到答复才安装，不强行结束进程。

之前可见的ProjectE UUID检查线程有SSL握手失败，但本次主阻塞由线程栈定位到上述配方恢复循环，未将该联网异常误当根因。现有测试世界仅由游戏创建，没有直接修改存档。当前全部具体机器人功能仍为未验收。

## 第28轮安装与重入（14:39 起）

用户明确要求“你帮我结束一下进程吧，我卡死了”，对本次卡住的Minecraft进程授权结束。核实PID14220为java且窗口为Minecraft Forge1.20.1后，仅结束该进程；再次确认没有Java进程，再于14:39:47用既有PCL安装脚本安装第28轮，更新1项、14项哈希回读通过。回执另存于第28轮检查点。没有直接修改存档或操作旧实例。这次授权仅用于已识别的卡住进程，不视为以后任意强关游戏的常驻授权。

已通过本地PCL重新启动修复版，并在游戏中选择已有`IC2-1201-20261023-验收`世界继续加载；未创建重复世界。14:42:04日志再次确认19种材料、370条路线、30个科技任务；正在复核实际进入场景与后续功能。

## 第29轮：登录同步误报“无效的玩家数据”

第28轮越过原来的世界加载死循环，但14:43:06登录被拒绝，界面显示“无效的玩家数据”。日志实质是`ProcessGraphPacket.validate`在编码服务端工艺快照时抛出`Invalid process format`，并非玩家文件解析损坏。ProcessGraph资源装载允许Ad Astra的`alloying`格式作为`BLOCKED_BYPASS`审计记录，网络包却仍使用另一份不含此格式的白名单。

第29轮将格式校验提取为`ProcessGraph.validateFormat(Route)`，由资源加载、包编码、包解码三处复用。`alloying`仍仅接受BLOCKED_BYPASS且机器列表严格为单个`ad_astra:etrionic_blast_furnace`；未批准任何新路线、未放宽阶段/资源/数量/字节限制，未修改图JSON或存档。Luna只读复审确认其他工业消费者仍按APPROVED分类过滤，未发现其他通用格式白名单。

33秒完整离线构建通过，876份JSON/mcmeta可解析；JAR3,829,859字节，SHA256`D771476AD3ADB8A1C1916978419AC9220179399903EFC539DF226BE67FDCDCB8`。与28包仅4个ProcessGraph/Packet相关class不同。1043份第一方文件、包、日志、审计及安装回执封存`checkpoints/build29-process-sync`。在断开连接页面正常退出客户端，确认Java退出后安装，更新1项、14项哈希一致；已重新启动复核。没有再次强杀、没有新增或运行自动化测试。

## 第29轮实际入场结果（14:49）

通过本地游戏UI重新进入已有验收世界成功，已看到玩家场景，F3显示主世界(8.5,74,6.5)。19材料/370工艺/30任务成功加载，日志中批准粉碎配方全部完成恢复；选定日志证据存于第29轮检查点`runtime-loading-evidence.txt`。随后工具报告用户输入，重新观察发现用户已打开科技页面，批准工艺显示正常；代理没有关闭该页面或继续发出键鼠输入。尚未进行机器人放置和各项工作、正常保存重进验证，不将这些项目列为通过。
