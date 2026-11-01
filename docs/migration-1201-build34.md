# 第34轮：物品说明移交内置AI

用户最新要求“先不管了后面再测试”，并取消物品长篇描述，解释交给内置AI。本轮停止桌面操作，不再按先前授权自动接管游戏。第34轮移除矿物全局悬停工艺/ID/产量长文及重复JEI物品说明页，保留实际JEI配方与用量；移除AI核心和兼容材料介绍，核能充电宝只保留状态及短操作提示，电量/寿命/槽位说明保留。解释补入LLMKnowledgeBase实际加载的migration_runtime_zh.md，系统提示同步按需回答。43秒离线构建通过（-x test），1023JSON/mcmeta可解析，配方和ProcessGraph与33轮无差异；SHA256 8A412FD1AB2582D01B57D50E3EFAEC29FA59E179B49960EE19AEE32D7CA3323C，3886710字节。34包含已构建33轮TOP修复，但均未安装；游戏已知仍32，不替换运行JAR、不催用户退出。未运行自动化测试、未同步GitHub。

## 变更与边界

- 删除MineralProcessTooltip及其JEI说明注册；未过滤第三方tooltip，也未改实际矿物配方、产量或注册物品。
- AI核心、兼容制作组件回到普通物品显示；核能电源保留热量、燃料、保护状态和一行换棒操作。
- NativeSlotHelp与机器人寿命提示保持，电池和手机原生电量信息保持。
- 活动AI知识库补齐纯净碎矿/洗后粉/净粉区别、兼容材料和AI核心用途；精确配方仍由ApprovedProcessKnowledge从服务端图检索。
- AI文本同步局部已安装/已验收范围，不将未完成项目写成通过。

## 验证

`gradlew.bat -Dnet.minecraftforge.gradle.check.certs=false build -x test --offline`：43秒成功；无clean，build/deployment未清理。构件静态审阅：1023JSON/mcmeta可解析，旧全局tooltip class已不在包内；与33轮的配方/工艺资源零差异。这里只是构建与静态检查，没有新增或运行自动化测试，没有游戏悬停或声音验收。

产物SHA256：`8A412FD1AB2582D01B57D50E3EFAEC29FA59E179B49960EE19AEE32D7CA3323C`。完整回执见`build-isolated/migration-1201/checkpoints/build34-item-tooltip-cleanup/package-audit.json`。

## 实测工作点（暂缓）

上一轮实测补充：游戏内keep扩台(80..115,139,65..100)，新增1170石块；玩家(97.5,140,89.5)，朝向-18/10。爱音最后实读(97,140,91)、约417992EU，货格原3泥土并装入250石砖（64/64/64/58）。随后手机联系人变0且原位置未看到角色，原因和角色/货物去向未确认。未开始新施工，不能称完整建线通过；Esc停止桌面后用户明确延后测试。后续获得新的实测指令先核对实体和货物，不重复创建角色、重发材料或直接修改存档。语音仅看到离家日语文字，尚未听验合成、播放或回家问候。
