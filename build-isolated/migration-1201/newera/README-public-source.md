# 独立 Forge 1.20.1 源码（r44）

本目录保存已在本机完成 r44 构建和安装的第一方源码。旧 1.19.2 工程在仓库根目录，两个工程分别构建。当前源码包含机械猫狗、Create 6.0.8 及老吴学 / Biotech 兼容入口、慢速饥饿、互动动画、猫饼复原和内置 AI 知识。

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

r44增加两种动态生物倒影镜子、机械猫哈气、真实IC2普通电池、27格货箱、整套马铠外观、3伤害敌怪防卫，以及猫狗100/人形200饱食与人形进食。猫狗电池活动3小时、饱食4.5小时；人形饱食按原电池续航1.5倍，活动9小时。动物40HP、人形100HP。具体合同和边界见根目录 `docs/migration-1201-build44-mirrors-pets.md`。老吴学适配限已核实动作/猫饼互动，非全部机制复刻。

完整本地JAR 10,252,473字节、SHA256 `70E986C8F605167EDE972349CAAA5F87CD55D6B6B2C134CC96EB05225D6D7831`；mrpack 313,860,791字节、SHA256 `711D2FA3EFCAA1BB1ADCC4FE353290177056CE4228F49FBC585DE0001F242670`。完整二进制含未公开音频/资产，不上传公开仓库。

2026-10-26T23:59:53.9586649+08:00已安装到独立Forge47.4.28实例，仅替换新纪元JAR，旧版有备份。未启动本轮游戏验收；镜面/动作/货箱/装备/迁移/音频与联机仍待实机确认。原猫狗模型保持，镜子源见 `art/furniture-mirrors`，装备预览见 `art/mechanical-pets/voxel/equipment`。

第一方代码遵循仓库根目录 MIT LICENSE。第三方程序、素材与角色设定各按其自身权利和许可证处理，不因本仓库代码许可自动取得其再分发权。
