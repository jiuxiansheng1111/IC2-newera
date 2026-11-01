# 1.20.1 第12轮：八人微笑、料理锅选方与依赖锁

2026-10-23，独立迁移目录 `build-isolated/migration-1201/newera/`。原1.19.2实例和存档未动。用户最新要求完成后手动逐项实测，并同步GitHub与内置AI；这次授权已写入项目约定和持续心跳。GitHub尚未同步，不将开发包作为已验收正式版发布。

## 本轮实际完成

- 主代理看过八人R20正面、三分之四视和R19/R20嘴型对照。保持R19刘海、脸型、同发色眉毛及既有身体服装；微笑只缩短中线并稍上扬两端，各角色幅度略有区别。保留此前审阅中指出的发型轮廓限制，不打相似度分数。
- `export_robot_expression_r20.py` 使用原UV/旋转导出器，检查源SHA、纹理、组和非口部元素一致后，导出九分件 `NER20M01`：原七骨架加中性嘴、微笑嘴。游戏每帧只渲染一种嘴型，并使用头部完整变换；没有按顶点坐标猜嘴部，也不改原BBModel。
- 服务端同步四秒表情：主人近身操作、获准归家/送行、聊天回复触发。战斗、受击、没电或到期清除；每台实体独立，表情不存入世界或娃娃。机器人协议12，联机双方需同版。不是语音音节口型。
- FD原生锅会优先使用缓存配方。预投料和运行检查现在拒绝同组食材匹配多道菜，保留订单并提示换料；不操作其私有缓存。实收成品后先清批次、再计订单，避免最终完成提示被覆盖。含桶瓶余物的FD配方及森罗到FD反向桥仍未完成安全回收。
- 全部实际下载依赖按目标1.20.1 Forge重核：Create 0.5.1j、JEI 15.56.0.205、IC2R 2.10.33-ex120、FD1.3.4、森罗1.6.0及Ad Astra前置等。`dependencies/runtime-lock-1201.json`记录13个外部构件实际SHA与依赖关系；本项目产物另有检查点。CarbonConfig是现构建携带的非必需依赖，不误称IC2R要求。
- 工业控制服务器所需通用机器壳精确映射到IC2R原生 `ic2:machine`，不把其他机器注册名当作等价设备。内置AI更新版本、锅具、微笑及未验收边界。

## 构建与资源静态证据

`build -x test --offline` 39秒成功，14条javac弃用警告；没有新增或运行自动化测试。完整JAR3,721,521字节，SHA256 `E003E0A9F3E1F416F77F8B59F6187A1865591EE0B609996189D8F63BE12D8253`。

检查点 `build-isolated/migration-1201/checkpoints/build12-expressions-fd-selection/` 保存JAR、日志、963个第一方文件哈希及源码包。803个JSON/mcmeta可解析；八人网格都是九分件，各自中性嘴24顶点、微笑嘴72顶点，法线/边界/有限数与字节长度静态核对通过。只有ZXing和JLayer两个既有嵌套库，无GameTest夹具。所有源文件时间不晚于产物时刻。

**尚未安装或实机验证。** ProcessGraph仍引用缺失合金炉、高级炉、银铝等材料，严格加载会拒绝此图。补齐两台兼容设备及缺失材料路线涉及原设备上限，范围问题仍待用户决定；不能删除路线或放宽校验伪造可玩。手动验收步骤见 `migration-1201-manual-acceptance-plan.md`。

## 第12轮之后、尚未再次构建的改动

- 两个制作配方的 `ic2:lapatron_crystal` 改为目标原生 `ic2:lapotron_crystal`，各1个。旧Classic容量100万EU/三级/传输600，目标1000万EU/四级/传输8092；两条路线只消费制作材料，不读取它的电量，不改变新纪元成品容量。明确接受目标原生材料属性，不宣称数值等价或迁移旧NBT。收据 `verified-equivalent-machine-shell-mapping.json`。
- 近身与手机的烹饪提示补上森罗汤锅、汤底和锅盖；AI补充储能晶体差异。后续必须重新构建后才能把这些内容计入交付。

## 视觉材料

- `art/robot-mcp-workspace/characters/head-r20-smile/review/all-eight-r20-front-candidate.png`
- `art/robot-mcp-workspace/characters/head-r20-smile/review/all-eight-r20-three-quarter-candidate.png`
- 同目录 `render-receipts.json`、`mouth-element-uuids.json` 记录实际MCP渲染返回路径、源hash、逐人嘴型UUID。

R20素材属于建模预览和本轮包内候选；上述静态检查不能替代Minecraft内转头、行走、多人和光照验收。
