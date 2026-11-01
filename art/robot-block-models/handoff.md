# Sol 手工方块机器人当前交接

## 2026-10-14 最新：用户否定 A6，全部模型迭代停止

用户明确否定 A6 的脖子、脸及五官，要求停止；根已中断 Sol xhigh。当前源与爱音工程同 `iterations/a6-first-complete/`，没有下一轮修改。下文“艺术修正继续”和任何继续建模提示均为失效历史。用户新提原图方形马赛克方法，当前仅完成[原图像素底稿与编辑器验证](../robot-image-reference/README.md)，没有完成新头部、成品 UV 或其余角色。旧心跳仍 PAUSED，禁止自动恢复旧工作。

## 最高优先修订：完整审阅参考，撤销脸部堆块

用户否定A5并明确脸部不应按头发的方块堆叠方法制作。根已亲看本地参考的正/前侧/严格侧/后以及隐藏头发的裸脸，并完整核对皮肤主形、五官薄层、头发、身体服装、UV、骨骼和动画引用；[完整审阅](reference-inspection/full-model-review.md)与[重写提示词](../robot-block-design/prompt-a6-reset.md)为当前依据。A4/A5均为未获接受的问题稿；不继续42条横切皮肤、长斜下颌或瓦片刘海。脸部视觉连续、下颌浅短收口，头发独立分束，服装/肢体各自围合。审阅期间已停止建模，新稿必须由Sol（xhigh）按修订指令实际制作和看图，不把写完提示词当作模型完成。旧心跳PAUSED。

当前有效分工按用户最新要求改为 GPT-6.1 Sol（xhigh）实际建模、五官、原创贴图与忠实导出；根智能体只指导、架构和审阅。Astra 停止，本轮 Sol 独占设计源与管线，不另行委派。旧 V28 圆润雕塑风、B1/B2 机械转换均被用户否定或未获验收，快照保留，不能作为已完成的人物稿。`automation-2` 保持 PAUSED，不自动恢复。

## A6 爱音第一完整原型：已冻结，艺术修正继续

已先分开保留 A5 实际问题输出 `iterations/a5-failed-output/` 和当时在途源 `art/robot-block-design/iterations/a5-source-before-a6/`，二者不混称统一冻结稿。A6 不沿用 A5 下颌或强制四段发束配方：主脸一张连续面，边缘只有短浅约 ±4.5° 收口，颅内承托由头发覆盖；下连接真实搭入主脸背面，与后置颈相接。先亲看纯色结构，再画新的 128 像素眉眼鼻口和连续侧扫刘海。

第一完整稿仅爱音：87 cube、28 个旋转元素、16 组、0 mesh；原样保留 origin/rotation/parent，Euler ZYX、North 相机在 -Z 看 +Z、屏幕右方向 -X。静态记录 `checks/anon.json`；`checks/a6-structure-scope.json` 确认身体/四肢与 A4 完整相同，主脸与下承托 overlap `[4.76,0.635,0.07]`、承托与颈 `[1.72,0.145,1.67]`。这些实体为零旋转，正值是实际体积相交，不是侧发遮罩。源不读取第三方参考工程或贴图。

Sol 已亲看正/左右45°/严格侧/后、普通尺寸及 A4 同灯对照；根已看真实资源图，确认连续脸方法正确、双叉与横切皮肤已去除，但艺术未通过。待改：观者左发际有浅肤色细缝、侧后发主形宽直、发尾过齐、顶盖转面硬；鼻部小弯钩和笑口上缘尖点也需克制修正。第一完整源/输出一致保存在 `iterations/a6-first-complete/`，根用该冻结工程做官方 Blockbench 方向和造型核对，当前此项待查。

图入口：[头六视图](iterations/a6-first-complete/preview/anon-head-six-views.png)、[合理尺寸资源近景](iterations/a6-first-complete/preview/anon-a6-normal-size.png)、[A4/A6 同灯对照](iterations/a6-first-complete/preview/anon-a4-a6-head-comparison.png)。A4 旧预览的相机/UV 镜像是先前审阅漏项；此对照重新渲染 A4 实际资源，不能沿用旧“方向一致”结论。此阶段只有爱音，祥子及其他六人仍为问题/未实现稿；无用户、动画或游戏验收。启动时普通可用、5h 剩54%、周剩28%，后续仍需亲读真实额度。

## A4 两人头脸修正：已输出，待根审阅和用户验收

A3 在途源、JSON、atlas、提示词已保留于 `art/robot-block-design/iterations/a3-inflight/`；A3 仍是旧方盒头，A4 提示词当时尚未实现几何。Sol 在此基础上实际重做两人的头脸，没有回退到旧曲面稿，也没有扩展其余六人。

当前爱音 134 cube、祥子 133 cube，各 11 骨骼、0 mesh；每人脸部为 14 个连接横截面、每层 3 个正厚度长方体，共 42 个前脸段。太阳穴/颊侧浅退后，下颌内收、短而稍宽的下巴与后置窄颈分开；耳前/耳后发覆盖跟随头型，刘海接入顶壳。整脸 UV 按统一世界坐标裁分。爱音去眼下暗点、嘴下孤立像素，保留灰蓝眼与完整小开口笑；祥子保持更平眼型、琥珀眼与克制闭口短线。

第一轮 5 列颊侧产生叠纸条纹，Sol 和根亲看后未保留为当前稿，原型完整保存在 `iterations/a4-head-prototype/`。当前采用 3 列浅退让；Sol 已亲看两人正面、左右真实 45°、严格左右侧、后面及 A2 同灯对照。前版斜前角约 37.2°，当前管线明确使用 ±45°；比较图两排使用相同当前角度、灯光与取景。

静态资源检查通过：正尺寸、原生 cube、骨骼 UUID、六面 UV/PNG、源坐标/UV 忠实、42 段前脸共享 chart、内嵌 PNG 一致。`checks/a4-scope-comparison.json` 另证实身体/四肢与保留 A3 完全一致，头/颈是此次修改范围；A3 服装本身未获艺术验收。完整冻结交付 `iterations/a4/`，源与输出指纹见 `generation-record.json`。根已在官方 Blockbench 本地分别打开当前 A4 两人工程，确认爱音 134 cube、祥子 133 cube、512 贴图，几何/贴图方向与预览一致；截图 `preview/anon-a4-editor.png`、`preview/sakiko-a4-editor.png` 收入冻结快照，单独观察记录见 `checks/a4-editor-observation.json`。动画、游戏、构建、安装及用户验收均未进行。

审阅入口：[爱音头六视图](preview/anon-head-six-views.png)、[祥子头六视图](preview/sakiko-head-six-views.png)、[爱音 A2/A4 对照](preview/anon-a2-head-comparison.png)、[祥子 A2/A4 对照](preview/sakiko-a2-head-comparison.png)。根已亲看以上四张图，确认整方盒脸已消除、下颌和下巴方向改善；斜侧仍有一条颊面阶梯、后发宽平、眉眼气质仍需细化，不能称充分还原。作者已看图不等于独立盲测或玩家认可，不评分、不宣称 99。后续先根据根和用户反馈修正这两人；其余六人继续暂停。发束/编发仍有板块感、服装和裙摆需要后续独立艺术审查，不能把本次头型修正称为八人完成。

额度亲读：开始 ordinaryUsageAllowed=true、5h 剩 25%、周剩 39%；最终导出前仍可用、5h 剩 13%、周剩 37%，全部适用窗口 >1%。后续阶段需重新读取，不能沿用这一历史读数；不购买额度或使用重置券。

## 标准与文件入口

- [详细制作与验收标准](../robot-cuboids/quality-standard.md)：方块、比例、完整五官、像素UV、八人识别点、发型/服装、逐轮检查与不可宣称完成的条件。标准仍适用手工方块新稿；其中 B1/B2 操作叙述是历史，不是继续机械转换的指令。
- 本轮 Sol 独占设计源 `art/sources/robot_character_design.py`、`art/robot-block-design/`、管线 `art/sources/robot_cube_pipeline.py` 和本目录；根不同时编辑设计源。
- 管线读取设计的 `build_character(id)`，保留 Y-up 原始坐标、512×512 atlas、cube 尺寸、UV、骨骼枢轴与 parent 层级，不套旧稿 `24-y`、不自动体素化。
- 可编辑工程：`editable/{id}.bbmodel`；忠实源数据：`models/{id}.json`；原始PNG：`textures/{id}.png`；静态记录与文件指纹：`checks/{id}.json`。
- 六视角：`preview/{id}-six-views.png`；头部正/左右前侧/严格左右侧/后：`preview/{id}-head-{view}.png`。`head-front-albedo` 单独标识，只用于贴图原色核对，不作为有光照结构预览。

## 最新用户反馈：A2头脸被否定，暂停扩展八人

用户指出“完全正方形一点都不像”“太粗糙”，A2头脸外观明确不通过。当前仅做 Sol 手工分层长方体脸型原型，改善脸颊、下颌和下巴收分；仍保持0mesh、每小块正厚度、整脸UV连续裁分。不能以“MC要方块”为理由保留整头大方盒，也不能恢复曲面mesh。原型审查前暂不扩展其余六人。

## A2 已同步的两人原型（头脸被用户否定）

Astra 冻结 A2 后已重新导出：爱音 66 cube、祥子 73 cube，各 11 骨骼，0 mesh。源坐标/UV逐元素保持、有限正尺寸、UUID引用、UV非透明、PNG内嵌一致静态通过，`generation-record.json`明确范围只有爱音/祥子且设计版本统一 A2。实际输出和当时设计/导出源完整保留 `iterations/a2/`。

预览入口：[爱音六视角](iterations/a2/preview/anon-six-views.png)、[祥子六视角](iterations/a2/preview/sakiko-six-views.png)、[爱音五官](iterations/a2/preview/anon-head-front.png)、[祥子五官](iterations/a2/preview/sakiko-head-front.png)、[爱音A1/当前同灯对照](iterations/a2/preview/anon-a1-current-comparison.png)、[祥子A1/当前同灯对照](iterations/a2/preview/sakiko-a1-current-comparison.png)。管线作者已看爱音六视角核对无上下倒置或镜像；这只是导出方向检查。Astra仍需亲看决定艺术下一轮，袖口/胸口等服装细节预计A3继续，不宣称全标准通过。

A1 爱音由主线在官方 Blockbench 本地打开，观察61个cube、512贴图、骨骼、贴图方向与资源预览一致；原始截图和观察记录仅保留在 `iterations/a1/`，不是 A2 导入证明。A2 爱音也由主线在官方网页本地打开，66cube及512贴图方向可见，原始截图`iterations/a2/preview/anon-a2-editor.png`和观察记录保留；祥子A2未在编辑器打开，不冒称其导入已查。爱音A2大方脸被用户明确否定；实机/动画仍未进行。

## 当前原型与检查边界

A1 爱音 61 cube、祥子 70 cube，各 11 骨骼；已经真实输出 JSON/PNG/.bbmodel。原生 cube、有限正尺寸、骨骼 UUID 引用、全六面UV非透明、独立和内嵌PNG一致及SHA256静态通过。全部 A1 实际资源、设计源与导出源留于 `iterations/a1/`。

Astra 亲看 A1 后未通过艺术自查：眉毛拱形像玩偶、祥子眼型不够沉静、长发像宽帘、格裙横层明显，需要 A2 手工修正。其余六人尚未设计，不能把两人原型称为完整八人阶段。

首轮预览环境光偏低曾导致肤色灰暗，管线已统一改为 ambient 0.77、实际面法线主灯/补灯 0.22/0.12；新旧方块对照均按同一灯组重渲染。此为预览灯配置修正，不是几何或相似度改善。每个角色艺术修改前后用相同灯光，不固定脸部亮度。Albedo 图明确无光照，仅用于原色核对。

官方 Blockbench 网页仅由主线在本地打开检查，禁止上传分享；编辑器显示检查单独记录。静态通过不代表相似度、动画、性能、游戏或用户验收通过。游戏功能、Java、构建、安装、测试继续暂停，不操作Minecraft/存档/密钥，不发布。

每个源码阶段先亲读全部真实额度，ordinaryUsageAllowed=true 且所有窗口明确剩余大于1%才继续。用户回复“继续”允许前台工作继续，不等于恢复小时自动续作。
