# r50 Windows视觉客户端交付（2026-10-29）

用户要求先给出含新增模组的Windows包，随后安装C开头光影和平滑材质。已先交付r49基础Windows mrpack；本次在其上生成r50视觉包，无须更换服务器JAR。

## 包体与模组

- Windows x64 / Minecraft 1.20.1 / Forge 47.4.28 / 便携Temurin Java17。保留r49新纪元JAR及全部30个第三方模组，共31个JAR，包括FTB Ultimine、AE2、GuideME、Create、JEI、中键整理和六个客户端渲染前置。
- 新纪元仍为r49 JAR，SHA256 62F360868F18BF738E0F5CBD30302F569CDDBA33FAD793CBB15566F6F3962EE9，匹配服务器主协议34。服务端仍为r49。
- 文件：dist/IC2-New-Era-Windows-1.20.1-r50-Visuals.mrpack，306695405字节，SHA256 D49AD74AE7644146F42359ABD1E0F22B0B9B1DFEE9566E1AED91E3277ED53014。
- Modrinth清单包含30个模组依赖与2个视觉资源下载项。新纪元本体和Windows Java及其对应源码保留在overrides。归档完整性、无重复路径、清单回读、新纪元JAR哈希及复制到dist后的SHA256均通过；未新增或运行自动化测试。

## 视觉资源与安装

- Complementary Shaders - Reimagined r5.9.3，Complementary Development，官方1.20.x支持。保持原始ZIP，外部配置采用MEDIUM参数；可选Iris扩展不作为必需特性。
- Bare Bones 1.1.7 / Bare Bones 1.20.zip，RobotPants，16x简洁平滑宣传片风格，pack_format 15符合MC1.20.1。主要覆盖原版材质，模组设备继续使用各自资源。
- 来源：https://modrinth.com/shader/complementary-reimagined/version/r5.9.3 、https://modrinth.com/resourcepack/bare-bones/version/1.1.7 。
- 两个ZIP通过官方Modrinth网页下载，检查归档和本地SHA1/SHA256/SHA512后写入scripts/player-visuals-lock-1201.json。分发包使用Modrinth索引下载，不将第三方视觉ZIP写入overrides或上传GitHub。Complementary原始内容不修改，默认启用时注明作者与来源；整合兼容问题由本项目负责。
- 本机游戏已关闭，2026-10-29T20:04:43.686941+08:00完成5项文件安装并校验：两个ZIP、oculus.properties、独立光影参数、options资源包启用项。保留其他游戏设置及既有资源包选择。备份：C:\Users\罗斌\Desktop\IC2-newera\build-isolated\player-packaging\20261029-r50-windows-visuals\installation-backups\20261029-200443。
- 本轮没有启动游戏或进入存档。光影编译、实际画面、Create渲染、帧率及玩家重新入服仍待实机验收，不以已安装冒称视觉验收。

## 连接版本提示

用户后续截图标题为Minecraft26.1.1，报客户端必须安装Forge。该截图客户端与服务器1.20.1/Forge47.4.28不匹配，应在PCL导入mrpack并选择其生成的1.20.1 Forge实例启动。

## 工作点

scripts/package-windows-visuals-1201.py负责从锁定r49基包派生、验证及安装；build-isolated/player-packaging/20261029-r50-windows-visuals保存源文件、下载记录、归档检查和安装回执。r49基础客户端保留，供用户优先获取以及视觉回退；Linux服务端与固定语音附件保留。
