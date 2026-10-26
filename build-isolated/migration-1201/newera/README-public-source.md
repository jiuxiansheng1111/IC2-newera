# 独立 Forge 1.20.1 源码（r43）

本目录保存已在本机完成 r43 构建和安装的第一方源码。旧 1.19.2 工程在仓库根目录，两个工程分别构建。当前源码包含机械猫狗、Create 6.0.8 及老吴学 / Biotech 兼容入口、慢速饥饿、互动动画、猫饼复原和内置 AI 知识。

公开源码删去了未授权公开的音频与角色资产，不能当作本机完整安装 JAR 的字节副本。`sounds/pet/dog_call.ogg`、机器人角色网格/纹理/人偶图标、从原版纹理重映射的锭图标及其物品模型没有随本目录公开。公开构建可能显示缺失纹理，狗的指定音频按钮没有声音；需要使用者自行提供有权使用的本地资源覆盖。角色定义和声音事件引用保留，以便授权覆盖正确对接，不伪称这些功能在删减版中完整可用。原始猫参考照片、第三方模组 JAR、运行配置、密钥与存档均不包含。猫狗 cuboid、像素图集和模型渲染图为本轮另行制作的资源。

## 构建

需要 Java 17、Python 3 和首次联网。保持此目录层级，在当前目录执行：

```powershell
python scripts/bootstrap-public-dependencies.py
.\gradlew.bat build -x test
```

引导程序只在相邻 `../verified-maven` 下载锁定作者构件并校验 SHA256；从已校验的 Create JAR 提取三个编译 API。不上传这些 JAR，也不复制其他未使用的旧 Maven 缓存。其余依赖由 Gradle 从已有仓库解析。联网依赖未缓存时不能使用 `--offline`。

已有本地构件可以只核验、不联网：

```powershell
python scripts/bootstrap-public-dependencies.py --verify-only
```

本次本机完整资源构建使用 `gradlew.bat '-Dnet.minecraftforge.gradle.check.certs=false' build -x test --offline` 成功。没有新增或运行测试；公开删减树没有重新构建，不宣称与完整本地 JAR 相同。模型源及导出脚本位于仓库根目录 `art/mechanical-pets/voxel` 和 `scripts/export-mechanical-pet-mesh.py`。源码被刻意按清单追踪，其他 `build-isolated` 缓存继续忽略。

## 当前交付与验收

r43修复了r42遗留的Ad Astra精确版本声明：旧声明[1.15.19]拒绝整合包实际安装的1.15.21，现改为[1.15.21]。封装器同时核对新纪元声明的精确依赖版本，发现与锁定包不一致时拒绝发行。

完整本地 JAR：10,178,595 字节，SHA256 `053FBBDDB88665CB242A7ED8D8EC5D489A5F8CE59C8395A10EFFB9DBAECCDAC2`。

本地 r43 mrpack：313,792,542 字节，SHA256 `60B0E7DB63EB1C7E8B7CDDFB23A88E67EA93FEF4B1A6038024A6D49B2E537F27`。这些完整二进制含未公开音频/资源，没有上传到公开 GitHub。

2026-10-26 已通过既有脚本安装到独立 Forge 47.4.28 实例并备份旧 JAR。尚未启动本版游戏；共同加载、动作穿模、多人长音频及真实风场复原仍待实机验收。白羽翼与猫躯干侧投影各重叠 83.33%，保留尾焰，四腿分开；狗躯干已由8单位加长至10单位。最终猫稿仍待用户审美确认。

第一方代码遵循仓库根目录 MIT LICENSE。第三方程序、素材与角色设定各按其自身权利和许可证处理，不因本仓库代码许可自动取得其再分发权。
