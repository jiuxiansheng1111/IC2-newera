# 玩家安装包：内置Java17修订（2026-10-24）

## 交付

`dist/IC2-NewEra-Player-1.20.1-r41-Java17-2026.10.24.zip`，315,963,195字节（约316.0MB），SHA256 `49A278E9EAE11C0DEA37AC3F4CD63FD82E75E146C6546837B2AF9398D6329E77`。旁有`.sha256`文件。以此含Java17的新文件替代早先15.8MB核心包；独立语音ZIP沿用原文件，SHA256 `16DB8E93E3262BE396BEFA5EBEF2A4626873CB3A2C02055C312B0D6BF85E5371`。两个文件放在一起，只解压新Player，运行`Install.cmd`。首次13个模组依赖下载仍需联网，Java17本身无需另行下载。

## 实现与范围

- 便携Eclipse Temurin OpenJDK17.0.20+8 Windows x64，492个文件，解压317,543,400字节；原始二进制、法律声明及完整对应源码均入包。二进制SHA256 `418497BE5CF585BDD2203D6486A565D66D3F5E992D5630D45104CB873FAB8122`，源码SHA256 `A9FADD969738666D59A241BC6FB01D0963154489D934EE387630DD4DCA5E8F54`，均与作者发布值匹配。OpenJDK自身GPLv2+Classpath Exception保留，与本项目MIT代码许可分开。
- 候选优先当前版本明确设置，再JAVA_HOME/PATH及限定的启动器/厂商Java目录；不整盘搜索、不读取账户或API Key。探测子进程只传必要Windows环境、去除JVM注入变量，强制UTF-8读中文路径，检查返回码、真实Java版本、64位和java.home/bin/java[w].exe。探测输出不回传原文。
- 当前版本已配置的合格Java17直接复用；PCL外部JDK无法安全登记到新版全局Java名单时使用包内隔离版，避免安装器修改全局名单。自有便携目录复用前逐项核实完整文件清单。路径为该版本`IC2-NewEra-runtime/java17`，逐文件校验提交，冲突不覆盖。无需管理员或系统环境变量配置。
- PCL旧JSON为Path/VersionString/IsJre/Is64Bit/IsUserImport五字段，Path落到bin末尾反斜杠，旧版版本映射17.0.20→1.17.0.20。同时写`VersionArgumentJavaV2:2`。新版PCL首次从旧设置迁移时会自行Check、登记并转为模式3；已迁移版本走目录扫描。只写当前实例`PCL/Setup.ini`并保留其它内容和备份；提示重开启动器重新加载缓存。
- 四段17.0.20.1在旧PCL的实际Check会被误读，所以最终包采用标准三段17.0.20。旧模式识别到已选四段Java时回退到内置兼容版；新版明确选择仍按其正常能力处理。
- HMCL已有`.hmcl/config/instance-game-settings.json`且schema1.0.x时，只修改overrideProperties/javaType/customJavaPath，保留schema、父预设和其它属性。旧或未知配置不会被伪造为自动成功；推荐PCL2作为一键入口。

## 检查证据

最终包66项逐项哈希回读，三份安装器模块与源文件一致，引导CPython的`._pth`加入`../installer`；内层JDK的根目录、文件清单/大小、release的17.0.20与x86_64、二进制和源码SHA256一致。游戏JAR仍41，SHA256 `56E584D8B03D145EB759808811138C1DF36E3C1B72480E7B94EDA29B7BCC5254`；语音ZIP未改。检查点`build-isolated/player-packaging/20261024-r41-java17`保留审计和原厂来源。

按项目要求未新增或运行自动化测试，也没有执行新安装器、Java版本探测或游戏；实际玩家机器上的安装、PCL/HMCL读入和语音仍待验收。前一版已通过的游戏/语音证据及未完成项目继续以`PLAYTEST-STATUS.txt`和迁移交接为准。旧1.19.2、存档和当前安装未改，GitHub未同步，不恢复持续心跳。

## 参考

- Eclipse官方二进制与来源：https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20%2B8
- 原厂完整源码已随包，源参考：https://github.com/adoptium/jdk17u/tree/jdk-17.0.20+8_adopt
- PCL旧Java选择实现：https://github.com/Meloong-Git/PCL/blob/2.12.8.2/Plain%20Craft%20Launcher%202/Modules/Minecraft/ModJava.vb
- PCL新选择与迁移：https://github.com/Meloong-Git/PCL/blob/main/Plain%20Craft%20Launcher%202/Modules/Minecraft/ModJava.vb
- HMCL实例字段与schema：https://github.com/HMCL-dev/HMCL/blob/v3.17.0.355/HMCL/src/main/java/org/jackhuang/hmcl/setting/GameSettings.java

## 旧包清理

用户随后要求清理旧压缩包，并确认桌面IC2_New_Era_Codex_Setup.md无用则一起删除。已核实该文件是2026-10-05的1.19.2初始化稿、已被现行规范替代且没有项目引用；连同旧发布ZIP/校验文件、两份临时核心包、旧引导ZIP和弃用微软JDK/源码缓存共18个文件（358,560,892字节）移至Windows回收站。dist现仅保留最新含Java17核心包、完整语音包、各自校验文件和更新后的说明；清理前重新核对两个保留ZIP的SHA256一致。当前构建所需Temurin资源、项目源码、文档审计及游戏/存档保持。记录build-isolated/player-packaging/cleanup-20261024.json。
