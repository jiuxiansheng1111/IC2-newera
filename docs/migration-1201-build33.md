# 第33轮：TOP探针头盔铁砧空值兼容

## 问题与修复

32轮实机日志中TOP10.0.3三类探针头盔各有29个JEI附魔铁砧组合报错，共87次。实际TOP JAR将修理材料传为null，`TopArmorMaterial.m_6230_()`直接返回null，原版ArmorItem铁砧检查调用Ingredient.test时抛NPE；JEI捕获后继续，没有阻断本次入场。

迁移工程新增共享`TopArmorMaterialMixin`，@Pseudo字符串目标避免硬依赖TOP，精确注入实际锁定JAR的m_6230_返回点，remap=false，仅将null替换Ingredient.EMPTY。保留原本未指定修理材料的语义，不增加配方；同时覆盖客户端JEI与服务端铁砧。TOP缺席可跳过；存在但方法不匹配遵从defaultRequire=1明确失败。只修改本项目Mixin及配置，没有改第三方JAR。

## 构建与边界

`gradlew.bat -Dnet.minecraftforge.gradle.check.certs=false build -x test --offline` 40秒通过，没有clean、没有新增或运行自动化测试。JAR3894249字节，SHA256 `017FC2C0B7A4C8C7FBD5F83423BD7E160309F1424E457EEFF68A84751EFCBFEF`；1023份JSON/mcmeta解析成功。与32包仅`TopArmorMaterialMixin.class`和`ic2newera.mixins.json`两项内容不同，矿物/机器人资源与逻辑均保留。

检查点`build-isolated/migration-1201/checkpoints/build33-top-null-compat/`含JAR、构建日志、包差异、1099份第一方源码归档/哈希；不整树公开发布。**尚未安装或实机验证**，当前游戏仍32；工具检测到用户输入后已停止桌面操作。后续在完成可交付包且用户正常保存关闭后安装33，再检查首进世界不再产生这87项异常、TOP缺席的可选加载和原版铁砧行为。不能把编译成功称为Mixin运行通过。
