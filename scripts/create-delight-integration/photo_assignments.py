#!/usr/bin/env python3
"""Generate the 24-entry server-verified field photography chapter.

Outputs are isolated under build-isolated/player-packaging/20261113-delight-joint/
photo-assignment-generated. Real server-side Exposure frames and private New Era
phone photo rows both complete the exact target assignment.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build-isolated/player-packaging/20261113-delight-joint/photo-assignment-generated"
CHAPTER_ID = "694A3F6A8A12C401"

# Entity assignments require the exact entity registry ID in server-verified
# frame data or a New Era phone row. Dimension assignments also require an
# actual subject entity, and the photo's camera dimension must match.
ASSIGNMENTS = [
    ("entity", "minecraft:bee", "花间传粉者", "拍摄蜜蜂本体；照片实体记录必须精确为 minecraft:bee。", "minecraft:honeycomb"),
    ("entity", "minecraft:fox", "林缘赤狐", "拍摄赤狐本体；狐狸物品、画廊图片和空镜不计。", "minecraft:sweet_berries"),
    ("entity", "minecraft:turtle", "海岸归巢", "拍摄海龟本体；服务器照片实体记录必须为 minecraft:turtle。", "minecraft:seagrass"),
    ("entity", "minecraft:allay", "悦灵回声", "拍摄悦灵本体；服务器照片实体记录必须为 minecraft:allay。", "minecraft:amethyst_shard"),
    ("entity", "minecraft:camel", "沙海驼影", "拍摄骆驼本体；掉落物或物品图标不计。", "minecraft:leather"),
    ("entity", "spawn:angler_fish", "鮟鱇鱼观察", "拍摄 Spawn 鮟鱇鱼本体；手机照片不要求公开相册。", "minecraft:spyglass"),
    ("entity", "spawn:clam", "潮间带蛤蜊", "拍摄 Spawn 蛤蜊本体；手机照片不要求公开相册。", "minecraft:nautilus_shell"),
    ("entity", "spawn:tuna", "远洋金枪鱼", "拍摄 Spawn 金枪鱼本体；手机照片不要求公开相册。", "minecraft:golden_carrot"),
    ("entity", "spawn:seahorse", "海马巡游", "拍摄 Spawn 海马本体；鱼桶或生物蛋不计。", "minecraft:nautilus_shell"),
    ("entity", "spawn:sea_cow", "海牛近岸", "拍摄 Spawn 海牛本体；实体 ID 必须精确匹配。", "minecraft:kelp"),
    ("entity", "spawn:octopus", "章鱼伪装", "拍摄 Spawn 章鱼本体；掉落物和图鉴条目不计。", "minecraft:ink_sac"),
    ("entity", "spawn:herring", "鲱群银光", "拍摄 Spawn 鲱鱼本体；服务器确认画面中的真实实体。", "minecraft:cooked_cod"),
    ("entity", "spawn:pilot_fish", "领航鱼伴游", "拍摄 Spawn 领航鱼本体；必须出现在服务器照片记录中。", "minecraft:prismarine_crystals"),
    ("entity", "spawn:blenny", "礁石鳚鱼", "拍摄 Spawn 鳚鱼本体；实体注册 ID 精确核验。", "minecraft:prismarine_shard"),
    ("entity", "spawn:bluefish", "蓝鱼逐浪", "拍摄 Spawn 蓝鱼本体；鱼肉样本不替代照片。", "minecraft:salmon"),
    ("entity", "spawn:sunfish", "翻车鱼巡游", "拍摄 Spawn 翻车鱼本体；鱼肉样本不替代照片。", "minecraft:gold_ingot"),
    ("entity", "spawn:barracuda", "梭鱼掠影", "拍摄 Spawn 梭鱼本体；服务器需识别目标实体。", "minecraft:gold_ingot"),
    ("entity", "spawn:dodo", "渡渡鸟踪迹", "拍摄 Spawn 渡渡鸟本体；服务器照片记录核验实体类型。", "minecraft:feather"),
    ("entity", "spawn:iguana", "岩岸鬣蜥", "拍摄 Spawn 鬣蜥本体；物品和图鉴图片不计。", "minecraft:leather"),
    ("entity", "spawn:coastal_crab", "潮线蟹影", "拍摄 Spawn 海岸蟹本体；实体 ID 精确匹配。", "minecraft:nautilus_shell"),
    ("entity", "ic2newera:companion_robot", "同行机器人肖像", "拍摄新纪元同行机器人本体；不是机器人娃娃物品或界面截图。", "minecraft:redstone"),
    ("dimension", "ad_astra:moon", "静海月面", "在 Ad Astra 月球拍摄一张真实含主体的照片；记录中的相机维度必须为 ad_astra:moon。", "minecraft:glowstone_dust"),
    ("dimension", "northstar:moon", "月球基地记录", "在 Northstar 月球拍摄一张真实含主体的照片；记录中的相机维度必须为 northstar:moon。", "minecraft:experience_bottle"),
    ("dimension", "northstar:mars", "火星远征纪实", "在 Northstar 火星拍摄一张真实含主体的照片；记录中的相机维度必须为 northstar:mars。", "minecraft:diamond"),
]
DIFFICULTY = {
    "minecraft:bee": "E", "minecraft:fox": "E", "minecraft:turtle": "E",
    "spawn:sea_cow": "D", "spawn:herring": "D", "spawn:bluefish": "D",
    "spawn:clam": "C", "spawn:tuna": "C", "spawn:blenny": "C", "spawn:coastal_crab": "C",
    "spawn:seahorse": "B", "spawn:sunfish": "B", "spawn:barracuda": "B", "spawn:dodo": "B", "spawn:octopus": "B",
    "spawn:angler_fish": "A", "minecraft:allay": "A", "minecraft:camel": "A", "spawn:iguana": "A",
    "spawn:pilot_fish": "S", "ic2newera:companion_robot": "S", "ad_astra:moon": "S",
    "northstar:moon": "SS", "northstar:mars": "SSS",
}
REWARD_COUNT = {"E": 1, "D": 1, "C": 2, "B": 2, "A": 3, "S": 3, "SS": 4, "SSS": 4}
DIFFICULTY_NAME = {"E": "入门", "D": "简单", "C": "普通", "B": "进阶", "A": "稀有", "S": "高阶", "SS": "远征", "SSS": "极限"}


def stable_id(label: str, used: set[int]) -> str:
    # The 694A prefix keeps IDs within signed-positive 64-bit space and follows
    # the existing r71 quest/reward ID convention.
    salt = 0
    while True:
        digest = hashlib.sha256(f"photo-survey:{label}:{salt}".encode()).hexdigest()[:12].upper()
        value = int("694A" + digest, 16)
        if value not in used and value < 2**63:
            used.add(value)
            return f"{value:016X}"
        salt += 1


def quote(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def main() -> None:
    if len(ASSIGNMENTS) != 24 or len({a[1] for a in ASSIGNMENTS if a[0] == "entity"}) != 21:
        raise ValueError("Photo assignment set must contain exactly 24 unique targets")
    used: set[int] = set()
    chapter_lines = [
        "{", '    default_hide_dependency_lines: false', '    default_quest_shape: "square"',
        '    filename: "joint_photo_survey"', '    group: "694A8F2A1E08B488"',
        '    icon: "ic2newera:industrial_phone"', f'    id: "{CHAPTER_ID}"',
        '    order_index: 8', '    title: "野外摄影调查 · 24 项"',
        '    quests: [',
    ]
    advances: dict[str, dict] = {}
    mapping = []
    for idx, (kind, target, title, description, reward) in enumerate(ASSIGNMENTS, 1):
        slug = f"survey_{idx:02d}"
        tier_label = DIFFICULTY[target]
        reward_count = REWARD_COUNT[tier_label]
        qid, tid, rid = (stable_id(f"{slug}:{part}", used) for part in ("quest", "task", "reward"))
        adv_id = f"ic2newera:photography/{slug}"
        mapping.append({"kind": kind, "target": target, "advancement": adv_id})
        advances[f"{slug}.json"] = {
            "criteria": {"server_verified_photo": {"trigger": "minecraft:impossible"}},
            "requirements": [["server_verified_photo"]],
        }
        x = ((idx - 1) % 6) * 2.0
        y = ((idx - 1) // 6) * 2.0
        chapter_lines.extend([
            "        {", f'            id: "{qid}"', f'            title: {quote(f"{tier_label}级·{DIFFICULTY_NAME[tier_label]} · {title}")}',
            '            icon: "ic2newera:industrial_phone"',
            f'            x: {x:.1f}d', f'            y: {y:.1f}d', '            shape: "square"',
            '            tasks: [', '                {', f'                    id: "{tid}"',
            '                    type: "advancement"', f'                    advancement: {quote(adv_id)}',
            '                    criterion: ""', '                }', '            ]', '            rewards: [', '                {',
            f'                    id: "{rid}"', '                    type: "item"', f'                    item: {quote(reward)}',
            f'                    count: {reward_count}', '                    team_reward: false', '                }', '            ]',
            f'            subtitle: {quote("摄影员个人档案 · " + title)}', '            description: [',
            f'                {quote(description)}',
            '                "手持新纪元工业手机，拍摄时勾选保存到服务器即可；照片保存在本人私有相册，无需公开。Exposure 相机也可完成实体目标。"',
            f'                {quote("难度 " + tier_label + "级（" + DIFFICULTY_NAME[tier_label] + "）；对应个人补给 ×" + str(reward_count) + "。")}',
            '            ]', '        }',
        ])
    chapter_lines.extend(["    ]", "    quest_links: []", "}"])
    (OUT / "config/ftbquests/quests/chapters").mkdir(parents=True, exist_ok=True)
    (OUT / "kubejs/data/ic2newera/advancements/photography").mkdir(parents=True, exist_ok=True)
    (OUT / "kubejs/server_scripts").mkdir(parents=True, exist_ok=True)
    (OUT / "config/ftbquests/quests/chapters/joint_photo_survey.snbt").write_text("\n".join(chapter_lines) + "\n", encoding="utf-8")
    for filename, data in advances.items():
        (OUT / "kubejs/data/ic2newera/advancements/photography" / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "photo_assignments.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "kubejs/server_scripts/photo_survey.js").write_text(kubejs_source(mapping), encoding="utf-8")
    print(f"Generated {len(ASSIGNMENTS)} photo assignments at {OUT}")


def kubejs_source(mapping: list[dict]) -> str:
    entries = json.dumps(mapping, ensure_ascii=False, separators=(",", ":"))
    return f'''// Generated by scripts/create-delight-integration/photo_assignments.py
const PHOTO_SURVEY = {entries}
const PHOTO_SURVEY_BY_TARGET = {{}}
const PHOTO_SURVEY_BY_DIMENSION = {{}}
PHOTO_SURVEY.forEach(row => {{
  if (row.kind === "entity") PHOTO_SURVEY_BY_TARGET[row.target] = row.advancement
  if (row.kind === "dimension") PHOTO_SURVEY_BY_DIMENSION[row.target] = row.advancement
}})
const PhotoSurveyForgeRegistries = Java.loadClass("net.minecraftforge.registries.ForgeRegistries")
const PhotoSurveyPhoneData = Java.loadClass("com.ic2newera.field.camera.PhoneCameraServerData")
const PhotoSurveyPhoneItem = Java.loadClass("com.ic2newera.robot.IndustrialPhoneItem")
const PHOTO_SURVEY_BASELINE = "ic2newera_photo_survey_baseline_ms"
const PHOTO_SURVEY_SEEN = "ic2newera_photo_survey_seen_photo_ids"

function photoSurveyGrant(player, advancement) {{
  player.server.runCommandSilent(`execute as ${{player.uuid}} run advancement grant @s only ${{advancement}}`)
}}

function photoSurveyEntityId(entity) {{
  try {{ return String(PhotoSurveyForgeRegistries.ENTITY_TYPES.getKey(entity.getType())) }}
  catch (ignored) {{ return "" }}
}}

ExposureEvents.modifyFrameData(event => {{
  const player = event.player
  if (player == null) return
  const seen = new Set()
  event.entitiesInFrame.forEach(entity => seen.add(photoSurveyEntityId(entity)))
  seen.forEach(id => {{
    const advancement = PHOTO_SURVEY_BY_TARGET[id]
    if (advancement) photoSurveyGrant(player, advancement)
  }})
  if (event.entitiesInFrame.isEmpty()) return
  const dimension = String(player.level.dimension().location())
  const dimensionAdvancement = PHOTO_SURVEY_BY_DIMENSION[dimension]
  if (dimensionAdvancement) photoSurveyGrant(player, dimensionAdvancement)
}})

// New Era phone photos are owner-private by default. Use page zero (the newest
// 24 rows), and require a real saved row captured after this player's baseline.
PlayerEvents.loggedIn(event => {{
  const player = event.player
  if (player.persistentData.getLong(PHOTO_SURVEY_BASELINE) <= 0) {{
    player.persistentData.putLong(PHOTO_SURVEY_BASELINE, Date.now())
  }}
}})

PlayerEvents.tick(event => {{
  const player = event.player
  if (player.tickCount % 200 !== 0 || !PhotoSurveyPhoneItem.hasCarriedPhone(player)) return
  let baseline = player.persistentData.getLong(PHOTO_SURVEY_BASELINE)
  if (baseline <= 0) {{
    baseline = Date.now()
    player.persistentData.putLong(PHOTO_SURVEY_BASELINE, baseline)
    return
  }}
  const seen = new Set(String(player.persistentData.getString(PHOTO_SURVEY_SEEN) || "").split("|").filter(Boolean))
  let changed = false
  try {{
    const photos = PhotoSurveyPhoneData.list(player, false, 0)
    for (let i = 0; i < photos.size(); i++) {{
      const photo = photos.get(i)
      const id = String(photo.id())
      if (seen.has(id) || Number(photo.capturedAtMillis()) < baseline) continue
      const species = String(photo.speciesId() || "")
      const targetAdvancement = PHOTO_SURVEY_BY_TARGET[species]
      if (targetAdvancement) photoSurveyGrant(player, targetAdvancement)
      const dimension = String(photo.camera().dimension().location())
      const dimensionAdvancement = PHOTO_SURVEY_BY_DIMENSION[dimension]
      if (species && dimensionAdvancement) photoSurveyGrant(player, dimensionAdvancement)
      seen.add(id)
      changed = true
    }}
    if (changed) player.persistentData.putString(PHOTO_SURVEY_SEEN, Array.from(seen).slice(-96).join("|"))
  }} catch (error) {{
    console.error(`[Photo Survey] Phone album evidence check failed: ${{error}}`)
  }}
}})
'''


if __name__ == "__main__":
    main()
