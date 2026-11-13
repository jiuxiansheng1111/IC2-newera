# 远征装备联动

本节只说明游戏内配方。TaCZ 与 Immersive Aircraft 是可选模组；缺少对应模组时，Forge 会跳过依赖它的 Create 配方。

TaCZ 外置枪包位于实例 `tacz/ic2newera_fieldwork`。安装后，TaCZ 枪械工作台可制作 `tacz:mk18`（6 个 IC2 高级电路、3 个 Create 精密构件、2 个 IC2 碳板）和 `tacz:ai_awp`（8 个高级电路、4 个精密构件、3 个碳板）。两种成品由 TaCZ 原生枪匠台结果处理器生成。

Create 序列组装另有两条工业配方，成品均为 TaCZ `tacz:modern_kinetic_gun`：一条带 `GunId=tacz:mk18`，另一条带 `GunId=tacz:ai_awp`；初始弹匣为空。配方使用 IC2 高级电路和 Create 精密构件，不包含弹药配方。

远征旋翼机使用 `immersive_aircraft:gyrodyne`，中文名“固定旋翼机”。Create 序列组装使用飞机船体、Create 精密构件和 IC2 高级电路，产出可驾驶的飞机物品。该路线与模组自带的双翼机配方并存，可将固定旋翼机作为工业远征目标。
