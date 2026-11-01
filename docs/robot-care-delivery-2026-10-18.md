# 机器人采集、烹饪与音乐扩展交付记录

状态：最终源码构建成功，已安装本机PCL，Robot-Care朋友包已生成并回读校验。此轮遵守项目“不新增/运行测试”规则，仅源代码审阅、依赖核实、构建与安装/包校验。安装前核实游戏及语音协调器已退出；没有启动/关闭Minecraft、没有更改存档或原LLM配置。

## 本轮要求与实现

- 采集按真实原生掉落预览选择方块及工具，进度仅统计实际进入机器人货物槽的目标物品。草方块/泥土等不再被矿石/原木白名单排除。主手、副手和货物自动选工具，保留原工具、NBT、电量及耐久；电动工具还校验单次能量和范围采掘模式。
- 分批扫描附近已加载区域，检查可达站位与视线，原生FakePlayer破坏保留事件/保护/耐久/掉落，缺工具、无资源、路径、权限、容量等失败通知主人。普通“挖矿”仍只找附近露出矿石，不全图找矿或自动挖通隧道。
- “挖矿/伐木/跟随/待命/做饭/取消烹饪”固定短命令独立于模型调用，定量采集仍走原LLM和服务端数量、物品、所有权校验。
- 料理锅复用 Farmer's Delight 1.19.2-1.2.4 的原生配方、热源、计时和物品能力。机器人到锅边后投自己的货物原料与餐具，完成后收菜。独占标记按服务器/维度/机器人隔离；第三方自动化仍可能改锅，快照不符即停止。投入采用先扣货物、持久化待投原料、逐槽核对，避免故障退款复制；取消只返还可核实未投入物料，锅内内容保留。
- 模型提示明确当前可选语音能力，旧历史否认语音不能覆盖现状；TTS清理常见Markdown、默认基础语速0.92。不能以这两项修改宣称八角色主观口齿已验收。
- Audius匿名公开曲库与手机角色翻唱入口；翻唱本地FFmpeg解码→已存在单模型htdemucs→角色RVC分段→与伴奏混音。16MiB/360秒输入、80MiB输出，严格串行FIFO、共用GPU锁/资源保护，排队取消与退出清理。模型文件不随包分发、不自动下载。
- 网易云房主专属二维码登录与仅本地长期凭证存储已实现。单人/局域网仅开房者，专服仅配置的唯一房主UUID（留空关闭）；其他OP不自动授权，TCP穿透规则相同。默认记住登录，可忘记本机凭证或退出清除。Windows用DPAPI当前用户加密，Linux只接受POSIX私有目录0700/文件0600；安全存储失败则仅内存，显示实际状态。凭证不进入LLM、游戏包、NBT或其他玩家同步。退出/刷新二维码/变更房主按epoch和owner校验避免旧请求恢复会话，网络临时异常保留文件，不误删成过期。扫码结果和当前账号状态只调用网易云第一方接口，原曲访问权限仍由平台决定。

## 依赖与数据边界

Farmer's Delight官方文件5051241（项目398521），2,769,499字节，SHA256 `A766849A5F61B9520C1C2ABA86A9652A392C9E276FAF5BB388B989BD65477F5F`。开发侧compileOnly，运行包通过官方清单下载；不复制其实现或资产到新纪元JAR。二维码使用官方ZXing core3.5.4在客户端本地编码，内嵌原始库610,364字节，SHA256 `71DE5D89341B5FCF5DD89DA7F44E84D825D0E084CDF3EC77C9ABE26B0F0CEB13`，Apache2许可证/NOTICE随JAR保留；二维码内容不送第三方图片服务。Audius仅匿名公开接口，来源节点与格式、大小/时长不符合限定时拒绝并提示；尚未实际播放验证其各节点兼容性。

参考：[Farmer's Delight官方文件](https://www.curseforge.com/minecraft/mc-mods/farmers-delight/files/5051241)、[官方1.19源码](https://github.com/vectorwing/FarmersDelight/tree/1.19)、[Audius官方API](https://docs.audius.co/api/)。

## 验证边界

最终执行`gradlew.bat -Dnet.minecraftforge.gradle.check.certs=false build -x test --offline`，27秒成功，日志`build-isolated/cooking-integration-20261018/final-build-r4.log`。初次两个不同provider三元表达式错误和登录整合缺少导入已修复，失败日志`integration-build.log`、`final-build.log`仍保留。Python源码AST/公开JSON静态解析通过；没有启动模型或运行测试。

完整发布JAR2,690,598字节，SHA256 `2AE6B25FC88960FBF827B0EABBA5F4DEAB959B62890DA0FD2C94BD90AA479A3F`。朋友包`dist/IC2-New-Era-Test-2026.10.18-Robot-Care.zip`，2,391,913字节，SHA256 `182BC254C1DF4C27F97CCB02CDDA3CD3663BD435BF88D37E34ED5E261E4A05C4`。包内1个自有JAR、8项固定官方依赖、12个公开语音运行时/示例文件；未打入登录文件、私人配置、训练模型、音频或存档。ZIP重开逐项字节一致、必需依赖覆盖、内嵌JLayer/ZXing原始库哈希一致。robots/media协议6，联机两端都需更新。

现有PCL安装器更新2个JAR（新纪元与Farmer's Delight），9个模组哈希全部匹配；12个本机语音运行时代码文件同步。独立语音配置仅增加`singing`，与安装前备份比较，其余TTS/RVC/角色映射字段完全相同。客户端LLM、COMMON模型设置及旧语音配置3个文件前后SHA256不变。旧JAR和部署回执保留在`build-isolated/cooking-integration-20261018/pre-install-20261018-213921/`；独立语音配置备份在`build/deployment/voice-config-backups/`。机器可读回执见`build-isolated/cooking-integration-20261018/delivery.json`。本轮未上传GitHub/CurseForge，旧源代码ZIP和历史发布均不能当作本轮版本。

这轮没有新游戏GUI、真实扫码登录/重启恢复、翻唱音质、料理锅产出或多人/公网穿透验收。此前八角色播放与本机TCP转发证据属于旧版，不能覆盖本轮新增路径；语速/文本清理也不代表口齿主观听感已通过。下一次实机应重点检查泥土/草方块与自动换工具、受保护/缺资源提示、料理锅原料守恒与取消恢复、扫码后记住/清除、原曲/翻唱取消及角色音色。
