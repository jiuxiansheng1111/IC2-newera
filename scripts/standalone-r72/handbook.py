#!/usr/bin/env python3
"""Build the standalone r72 quest overlay from the reviewed 258-quest r71 book.

This is deliberately a lossless SNBT edit: quest/task/reward IDs, dependencies,
conditions and coordinates are retained. Only references to content removed from
the standalone pack and the Northstar -> Ad Astra dimension substitution change.
"""
from __future__ import annotations

import json
import io
import re
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGING = ROOT / "build-isolated/player-packaging/20261113-delight-joint/collision-quests-20261114/staging"
OUT = ROOT / "build-isolated/player-packaging/20261114-r72-standalone/handbook-overlay"
QUESTS = STAGING / "config/ftbquests/quests"
CHAPTER_OUT = OUT / "config/ftbquests/quests"
R70_SERVER = ROOT / "W/r70-release/IC2-New-Era-Server-1.20.1-r70-Voice.zip"
REMOVED_NAMESPACES = {
    "northstar", "createdelight", "eclipticseasons", "mynethersdelight",
    "vintagedelight", "createaddition", "vintageimprovements", "fluidlogistics",
}
RETIRED = {
    "northstar": "ad_astra",  # r72 keeps the Ad Astra moon and Mars chapters.
}

# Replacements target matching items, machines, or workstations in the retained r70 stack.
ITEM_FALLBACKS = {
    # Northstar replacements were checked against the actual Ad Astra 1.20.1 r70 jar.
    "northstar:astronomical_reading": "ad_astra:astrodux",
    "northstar:astronomy_table": "ad_astra:nasa_workbench",
    "northstar:large_fan": "ad_astra:fan",
    "northstar:martian_steel_ingot": "ad_astra:desh_ingot",
    "northstar:oxygen_filler": "ad_astra:oxygen_loader",
    "northstar:oxygen_sealer": "ad_astra:oxygen_distributor",
    "northstar:polished_lunar_sapphire": "ad_astra:ice_shard",
    "northstar:rocket_controls": "ad_astra:tier_1_rocket",
    "northstar:rocket_station": "ad_astra:launch_pad",
    "northstar:rocket_thruster": "ad_astra:steel_engine",
    "northstar:space_atlas": "ad_astra:astrodux",
    "northstar:titanium_ingot": "ad_astra:steel_ingot",
    "createdelight:sell_bin": "create:stock_ticker",
    "createdelight:greenhouse_builder": "farmersdelight:rich_soil",
    "eclipticseasons:greenhouse_core_container": "minecraft:composter",
    "eclipticseasons:winter_greenhouse_essence": "farmersdelight:tomato",
    "mynethersdelight:letios_compost": "farmersdelight:organic_compost",
    "vintagedelight:fermenting_jar": "farmersdelight:cooking_pot",
    "createaddition:rolling_mill": "ic2:metal_former",
    "vintageimprovements:spring_coiling_machine": "create:mechanical_crafter",
    "vintageimprovements:curving_press": "create:mechanical_press",
    "vintageimprovements:vacuum_chamber": "ic2:compressor",
    "vintageimprovements:vibrating_table": "create:millstone",
    "fluidlogistics:smart_faucet": "create:mechanical_pump",
}

def safe_text(s: str) -> str:
    # Remove retired-mod branding from descriptions while retaining quest guidance.
    s = s.replace("Northstar 月球", "Ad Astra月球轨道")
    for old, new in (("Northstar", "Ad Astra"), ("northstar", "ad_astra")):
        s = s.replace(old, new)
    for old, new in (
        ("设置收购箱", "开通库存调度"),
        ("装配温室组件", "备妥富饶耕作土"),
        ("校准温室核心", "备妥堆肥桶"),
        ("补充跨季节养分", "交付有机堆肥"),
        ("供一份冬季温室精华", "交付番茄样本"),
        ("登记发酵罐", "登记烹饪锅"),
        ("登记滚轧机", "登记IC2金属成型机"),
        ("登记弹簧卷绕机", "登记机械装配机"),
        ("交付曲面压机", "登记动力辊压机"),
        ("登记真空腔", "登记IC2压缩机"),
        ("登记振动台", "登记石磨"),
        ("登记智能水龙头", "登记动力泵"),
        ("登记星图", "制作并登记星际指南"),
        ("登记天文台", "登记NASA工作台"),
        ("归档观测读数", "交付星际指南"),
        ("登记火箭站", "登记发射台"),
        ("登记控制台", "组装一级火箭"),
        ("登记一级推进器", "登记钢制火箭引擎"),
        ("交付钛锭样本", "交付Ad Astra钢锭样本"),
        ("压制月面蓝宝石", "取得月面冰晶"),
        ("提交火星钢", "提交德希锭"),
        ("登记氧气填充器", "登记氧气装载机"),
        ("登记氧气密封器", "登记氧气分配器"),
        ("登记大型风机", "登记Ad Astra风机"),
        ("出货箱〔create:stock_ticker〕", "库存终端〔create:stock_ticker〕"),
        ("温室搭建机〔farmersdelight:rich_soil〕", "富饶土壤〔farmersdelight:rich_soil〕"),
        ("温室之心容器〔minecraft:composter〕", "堆肥桶〔minecraft:composter〕"),
        ("忘魂肥料〔farmersdelight:organic_compost〕", "有机堆肥〔farmersdelight:organic_compost〕"),
        ("冬季温室心髓〔farmersdelight:tomato〕", "番茄〔farmersdelight:tomato〕"),
        ("腌制罐〔farmersdelight:cooking_pot〕", "烹饪锅〔farmersdelight:cooking_pot〕"),
        ("轧机〔ic2:metal_former〕", "金属成型机〔ic2:metal_former〕"),
        ("卷簧机〔create:mechanical_crafter〕", "机械合成器〔create:mechanical_crafter〕"),
        ("冲压机〔create:mechanical_press〕", "动力辊压机〔create:mechanical_press〕"),
        ("振动台〔create:millstone〕", "石磨〔create:millstone〕"),
        ("智能龙头〔create:mechanical_pump〕", "动力泵〔create:mechanical_pump〕"),
        ("星图〔ad_astra:astrodux〕", "星际指南〔ad_astra:astrodux〕"),
        ("星图台〔ad_astra:nasa_workbench〕", "NASA工作台〔ad_astra:nasa_workbench〕"),
        ("天文观测记录〔ad_astra:astrodux〕", "星际指南〔ad_astra:astrodux〕"),
        ("火箭核心〔ad_astra:launch_pad〕", "发射台〔ad_astra:launch_pad〕"),
        ("火箭控制台〔ad_astra:tier_1_rocket〕", "一级火箭〔ad_astra:tier_1_rocket〕"),
        ("火箭推进器〔ad_astra:steel_engine〕", "钢制火箭引擎〔ad_astra:steel_engine〕"),
        ("钛锭〔ad_astra:steel_ingot〕", "Ad Astra钢锭〔ad_astra:steel_ingot〕"),
        ("磨制月球蓝宝石〔ad_astra:ice_shard〕", "月面冰晶〔ad_astra:ice_shard〕"),
        ("火星钢锭〔ad_astra:desh_ingot〕", "德希锭〔ad_astra:desh_ingot〕"),
        ("氧气填充机〔ad_astra:oxygen_loader〕", "氧气装载机〔ad_astra:oxygen_loader〕"),
        ("氧气供给机〔ad_astra:oxygen_distributor〕", "氧气分配器〔ad_astra:oxygen_distributor〕"),
        ("大型风扇〔ad_astra:fan〕", "Ad Astra风机〔ad_astra:fan〕"),
        ("制作 Ad Astra 星图并在背包中登记，使用图册规划目的地；不代替火箭或着陆，也不消耗图册。", "按 Ad Astra 配方用书、铁锭和蓝色染料制作星际指南并登记；指南用于查阅航天信息，不代替火箭或实际着陆。"),
        ("制作天文台工作台并在背包中登记为后续观测工位；任务不消耗设备。", "按 JEI 制作 NASA 工作台并留作 Ad Astra 火箭组装工位；任务仅检查背包持有，不消耗设备。"),
        ("使用观测工位取得天文读数并交付一份，保留后续研发所需其它样品。", "按 Ad Astra 配方制作一本星际指南并交付；需蓝色染料、铁锭和书，保留后续发射材料。"),
        ("制作火箭站并在背包中登记；出发前仍须自行组装火箭与燃料，任务不消耗设备。", "制作 Ad Astra 发射台并留一件作验收；实际发射需铺设 3×3 发射台、组装火箭并备足燃料。任务只检查背包物品，不消耗设备。"),
        ("在背包中登记火箭控制台；它只是发射设施的一部分，不是完整航天器，也不会被消耗。", "用 NASA 工作台按配方组装一级火箭并持有验收；正式发射仍需发射台、燃料和太空服。"),
        ("按 JEI 制作推进器并登记备用件；下一阶段仍须自行补齐其余火箭部件，任务不消耗推进器。", "制作 Ad Astra 钢制引擎并留一件登记；它是火箭部件，任务只检查库存，不消耗引擎。"),
        ("交出两块钛锭作推进系统结构材料；该包中有对应冶炼/加工路线。", "交付两块 Ad Astra 钢锭；可由九枚钢粒合成，NASA 工作台所需钢板另按 JEI 加工。"),
        ("按月面宝石加工路线取得抛光月球蓝宝石并交付。", "在月球采集冰晶矿石并冶炼出 Ad Astra 月面冰晶，然后交付。"),
        ("取得两块火星钢并作为防护结构材料交付。", "在火星开采并冶炼德希矿，交付两块 Ad Astra 德希锭。"),
        ("制作氧气填充器备用件并登记，实际充氧时仍需氧源与电力；任务不消耗设备。", "制作 Ad Astra 氧气装载机并留一件验收；实际充氧需要接通氧气来源与电力，任务不消耗设备。"),
        ("在背包中登记氧气密封器；任务不代表已密封整个建筑，也不会消耗设备，仍需按模组规则完成封舱。", "制作 Ad Astra 氧气分配器并留一件验收；是否形成可呼吸空间取决于建筑封闭和氧气供应，任务不消耗设备。"),
        ("制作大型风机并登记作舱体设备，不代表它能替代推进器或氧气供应；任务不消耗设备。", "制作 Ad Astra 风机并留一件验收；风机用于相关航天配方，不能替代引擎或氧气来源，任务不消耗设备。"),
        ("制作 Createdelight 收购箱并在背包中登记一只备用设备；无需出售物品或启动自动售货。", "登记 Create 库存终端，用于查看和管理 Create 物流订单；任务只检查设备持有，不要求出售货物。"),
        ("按 JEI 制作温室建造器并在背包中登记；它作为下一处温室工位的工具，不会被任务消耗。", "使用 Farmer's Delight 配方制备富饶土壤并持有；可用于改良耕作土，任务不代表已建成温室。"),
        ("在背包中登记温室核心容器；安装、灌注等操作不由此物品任务代替，也不会消耗该设备。", "备好原版堆肥桶并放置于耕作区处理植物材料；本任务只验收物品，不代表自动温室已运行。"),
        ("按该模组配方制得两份 Letios 堆肥，作为温室耗材交付。", "按 JEI 制得两份 Farmer's Delight 有机堆肥并交付，用于改良耕作土。"),
        ("按季节温室路线取得冬季温室精华并提交一份；不是普通小麦替代品。", "种植并收获一份 Farmer's Delight 番茄作为温室农产品样本，交付时会扣除。"),
        ("制作发酵罐备用件并在背包中登记，供厨房后续加工工位扩充；任务不消耗设备。", "制作 Farmer's Delight 烹饪锅并留一件作为厨房加工工位验收；任务不消耗设备。"),
        ("在背包中登记 Create Crafts & Additions 滚轧机备用件；不要求接电或消耗电网，任务不消耗设备。", "持有 IC2 金属成型机作为金属压制工位；配方按 JEI 核对，验收不要求接电、不消耗设备。"),
        ("制作弹簧卷绕机并登记备用工位；按当前 JEI 配方取件，任务不消耗设备。", "制作 Create 机械合成器作为自动装配工位并持有验收；任务不消耗设备。"),
        ("在背包中登记 Vintage Improvements 曲面压机备用件，不替代现有 IC2 机器，也不会消耗设备。", "持有 Create 动力辊压机作为机械成型工位；任务只验收物品，不要求运行或接入转速。"),
        ("按配方制作真空腔体并在背包中登记为精加工设备工位；任务不消耗设备。", "持有 IC2 压缩机作为压力加工工位；具体配方和耗能按 JEI/机器界面确认，任务不消耗设备。"),
        ("在背包中登记振动台备用件；本任务仅验设备物品，不声称已运行或消耗设备。", "持有 Create 磨石作为机械处理工位；任务只验收物品，不声称设备已运行。"),
        ("在背包中登记 Fluid Logistics 智能水龙头；液体管网需另接，不能将其当成整套管线，任务不消耗水龙头。", "持有 Create 动力泵作为流体输送工位；还需另接流体管道与转动来源，任务不消耗设备。"),
        ("月球基地记录", "月球轨道记录"),
    ):
        s = s.replace(old, new)
    s = s.replace("在 Ad Astra月球轨道拍摄一张真实含主体的照片；记录中的相机维度必须为 ad_astra:moon。",
                  "在 Ad Astra 月球轨道拍摄一张真实含主体的照片；记录中的相机维度必须为 ad_astra:moon_orbit。")
    return s

def transform(text: str) -> str:
    for old, new in ITEM_FALLBACKS.items():
        text = text.replace(old, new)
    for old, new in RETIRED.items():
        text = re.sub(rf"(?<![A-Za-z0-9_.-]){re.escape(old)}:", new + ":", text)
    # The provided shared handbook has individual claims (team_reward:false):
    # shared completion is controlled by FTB Teams/Quests team progress, while each
    # member receives their own reward. Keep that behavior intact.
    return safe_text(text)

def r70_server_catalog() -> tuple[set[str], set[tuple[str, str]], set[tuple[str, str]]]:
    """Read namespace, item model, and dimension IDs from the frozen 35-mod r70 server."""
    if not R70_SERVER.is_file():
        raise SystemExit(f"r70 server reference package missing: {R70_SERVER}")
    mod_ids: set[str] = set()
    item_models: set[tuple[str, str]] = set()
    dimensions: set[tuple[str, str]] = set()
    with zipfile.ZipFile(R70_SERVER) as server:
        for path in server.namelist():
            if "/mods/" not in path or not path.lower().endswith(".jar"):
                continue
            with zipfile.ZipFile(io.BytesIO(server.read(path))) as jar:
                for metadata in (n for n in jar.namelist() if n.endswith("mods.toml")):
                    mod_ids.update(re.findall(r'(?m)^\s*modId\s*=\s*["\']([^"\']+)',
                                              jar.read(metadata).decode("utf-8", errors="ignore")))
                    break
                for asset in jar.namelist():
                    model = re.match(r"assets/([^/]+)/models/item/(.+)\.json$", asset)
                    if model:
                        item_models.add((model.group(1), model.group(2)))
                    dimension = re.match(r"data/([^/]+)/dimension/(.+)\.json$", asset)
                    if dimension:
                        dimensions.add((dimension.group(1), dimension.group(2)))
    return mod_ids, item_models, dimensions

def validate_output() -> None:
    mod_ids, item_models, dimensions = r70_server_catalog()
    refs: set[str] = set()
    missing: list[str] = []
    for path in OUT.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".snbt", ".json"}:
            continue
        text = path.read_text(encoding="utf-8")
        for ns in REMOVED_NAMESPACES:
            if f"{ns}:" in text:
                missing.append(f"retired namespace {ns}: in {path.relative_to(OUT)}")
        refs.update(re.findall(r'(?m)^\s*(?:item|icon): "([a-z0-9_.-]+:[a-z0-9_./-]+)"', text))
    for ref in sorted(refs):
        ns, item = ref.split(":", 1)
        if ns in {"minecraft", "ftbquests"}:
            continue
        if ns not in mod_ids:
            missing.append(f"namespace not loaded by r70: {ref}")
        elif (ns, item) not in item_models:
            missing.append(f"item absent from r70 item assets: {ref}")
    photo = json.loads((OUT / "first-party-jar/data/ic2newera/photography/survey.json").read_text(encoding="utf-8"))
    if len(photo.get("assignments", [])) != 24:
        missing.append("photography survey must have 24 assignments")
    for row in photo.get("assignments", []):
        if row.get("kind") == "dimension":
            ns, dim = row["target"].split(":", 1)
            if (ns, dim) not in dimensions:
                missing.append(f"dimension absent from r70 data: {row['target']}")
    if (OUT / "kubejs").exists():
        missing.append("KubeJS directory must not be shipped")
    if list((CHAPTER_OUT / "reward_tables").glob("*.snbt")):
        missing.append("legacy reward table remains")
    if missing:
        raise SystemExit("r72 handbook validation failed:\n- " + "\n- ".join(missing))
    print(f"Validated {len(refs)} unique item/icon IDs against r70 server assets and {len(photo['assignments'])} photo targets")

def refresh_kill_task_names(knowledge_path: Path) -> None:
    """Use entity registry names (not same-ID food drops) in AI kill-task text."""
    vanilla_names = {
        "minecraft:cow": "牛", "minecraft:zombie": "僵尸",
        "minecraft:skeleton": "骷髅", "minecraft:spider": "蜘蛛",
        "minecraft:creeper": "苦力怕", "minecraft:drowned": "溺尸",
        "minecraft:guardian": "守卫者", "minecraft:witch": "女巫",
        "minecraft:blaze": "烈焰人", "minecraft:ghast": "恶魂",
        "minecraft:wither_skeleton": "凋灵骷髅", "minecraft:enderman": "末影人",
        "minecraft:shulker": "潜影贝", "minecraft:wither": "凋灵",
        "minecraft:ender_dragon": "末影龙", "minecraft:zombified_piglin": "僵尸猪灵",
        "minecraft:iron_golem": "铁傀儡", "minecraft:pig": "猪",
        "minecraft:sheep": "绵羊", "minecraft:chicken": "鸡",
    }
    spawn_zh: dict[str, str] = {}
    with zipfile.ZipFile(R70_SERVER) as server:
        spawn_path = next((p for p in server.namelist()
                           if "/mods/" in p and p.lower().endswith(".jar") and "spawn-" in p.lower()), None)
        if not spawn_path:
            raise SystemExit("Spawn jar missing from r70 server package")
        with zipfile.ZipFile(io.BytesIO(server.read(spawn_path))) as spawn:
            lang_path = "assets/spawn/lang/zh_cn.json"
            if lang_path not in spawn.namelist():
                raise SystemExit("Spawn zh_cn entity translations missing from r70 jar")
            lang = json.loads(spawn.read(lang_path).decode("utf-8-sig"))
            spawn_zh = {k.removeprefix("entity.spawn."): v for k, v in lang.items()
                        if k.startswith("entity.spawn.") and not k.endswith(".description")}

    quest_entities: dict[str, str] = {}
    for chapter in (CHAPTER_OUT / "chapters").glob("*.snbt"):
        text = chapter.read_text(encoding="utf-8")
        start = text.find("    quests: [")
        end = text.find("\n    ]", start)
        if start < 0 or end < 0:
            continue
        for block in re.findall(r"(?ms)^        \{\n(.*?)(?=^        \})", text[start:end]):
            q = re.search(r'(?m)^            id: "([0-9A-F]{16})"', block)
            if not q or 'type: "kill"' not in block:
                continue
            task_section = block.split("tasks: [", 1)[1].split("rewards: [", 1)[0]
            entity = re.search(r'entity: "([a-z0-9_.-]+:[a-z0-9_./-]+)"', task_section)
            if entity:
                quest_entities[q.group(1)] = entity.group(1)

    knowledge = json.loads(knowledge_path.read_text(encoding="utf-8"))
    updated = 0
    missing: list[str] = []
    for quest in knowledge.get("quests", []):
        entity = quest_entities.get(quest.get("id", ""))
        if not entity:
            continue
        namespace, path = entity.split(":", 1)
        label = vanilla_names.get(entity) if namespace == "minecraft" else spawn_zh.get(path) if namespace == "spawn" else None
        if not label:
            missing.append(entity)
            continue
        for i, task in enumerate(quest.get("tasks", [])):
            if task.startswith("击败 "):
                count = re.search(r"( ×\d+)$", task)
                quest["tasks"][i] = f"击败 {label}〔{entity}〕{count.group(1) if count else ''}"
                updated += 1
    if missing:
        raise SystemExit("Missing entity translations for kill tasks: " + ", ".join(sorted(set(missing))))
    knowledge_path.write_text(json.dumps(knowledge, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    if updated != len(quest_entities):
        raise SystemExit(f"Kill task/index mismatch: updated {updated}, expected {len(quest_entities)}")
    print(f"Verified {updated} kill task labels against the r70 entity translations")

def generate_photo_advancements(chapters_dir: Path, survey_path: Path) -> None:
    """Generate one photo advancement per FTB quest and mirror both resources into Gradle."""
    photo_meta: dict[str, dict[str, str]] = {}
    for chapter in chapters_dir.glob("*.snbt"):
        text = chapter.read_text(encoding="utf-8")
        start = text.find("    quests: [")
        end = text.find("\n    ]", start)
        if start < 0 or end < 0:
            continue
        for block in re.findall(r"(?ms)^        \{\n(.*?)(?=^        \})", text[start:end]):
            if 'type: "advancement"' not in block or "ic2newera:photography/survey_" not in block:
                continue
            quest_id = re.search(r'(?m)^            id: "([0-9A-F]{16})"', block)
            title = re.search(r'(?m)^            title: "([^"]+)"', block)
            icon = re.search(r'(?m)^            icon: "([^"]+)"', block)
            adv = re.search(r'advancement: "(ic2newera:photography/survey_[0-9]{2})"', block)
            description = re.search(r'description: \[\s*"([^"]+)"', block)
            if not all((quest_id, title, icon, adv, description)):
                raise SystemExit(f"Incomplete photography quest object in {chapter.name}")
            if adv.group(1) in photo_meta:
                raise SystemExit(f"Duplicate photography advancement in FTB book: {adv.group(1)}")
            photo_meta[adv.group(1)] = {
                "quest_id": quest_id.group(1), "title": title.group(1),
                "icon": icon.group(1), "description": description.group(1),
            }

    survey = json.loads(survey_path.read_text(encoding="utf-8"))
    assignments = survey.get("assignments", [])
    advancements = {row["advancement"] for row in assignments}
    expected = {f"ic2newera:photography/survey_{i:02d}" for i in range(1, 25)}
    if len(assignments) != 24 or advancements != expected or set(photo_meta) != expected:
        raise SystemExit(f"Photography ID mismatch: assignments={len(assignments)}, FTB={len(photo_meta)}")

    output_dirs = [
        OUT / "first-party-jar/data/ic2newera/advancements/photography",
        ROOT / "src/main/resources/data/ic2newera/advancements/photography",
    ]
    for out_dir in output_dirs:
        out_dir.mkdir(parents=True, exist_ok=True)
    for advancement in sorted(expected):
        item = photo_meta[advancement]
        tier = re.match(r"([A-Z]{1,3})级", item["title"])
        if not tier:
            raise SystemExit(f"Photography quest title has no tier: {item['title']}")
        frame = "task" if tier.group(1) in {"E", "D"} else "goal" if tier.group(1) in {"C", "B", "A"} else "challenge"
        resource = {
            "parent": "ic2newera:newera/root",
            "display": {
                "icon": {"item": item["icon"]},
                "title": item["title"],
                "description": item["description"],
                "frame": frame,
                "show_toast": True,
                "announce_to_chat": False,
                "hidden": False,
            },
            "criteria": {"confirmed_photo": {"trigger": "minecraft:impossible"}},
            "requirements": [["confirmed_photo"]],
        }
        name = advancement.rsplit("/", 1)[1] + ".json"
        for out_dir in output_dirs:
            (out_dir / name).write_text(json.dumps(resource, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # Keep the server mapping byte-for-byte aligned with the compiled Gradle resource.
    gradle_photo = ROOT / "src/main/resources/data/ic2newera/photography/survey.json"
    gradle_photo.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(survey_path, gradle_photo)
    if gradle_photo.read_bytes() != survey_path.read_bytes():
        raise SystemExit("survey.json differs between the overlay and Gradle resources")

    for advancement, item in photo_meta.items():
        name = advancement.rsplit("/", 1)[1] + ".json"
        actual = json.loads((output_dirs[0] / name).read_text(encoding="utf-8"))
        if actual["display"]["title"] != item["title"] or "confirmed_photo" not in actual["criteria"]:
            raise SystemExit(f"Generated title/criterion mismatch for {advancement}")
        if (output_dirs[1] / name).read_bytes() != (output_dirs[0] / name).read_bytes():
            raise SystemExit(f"Advancement differs between the overlay and Gradle resources: {advancement}")
    print(f"Generated and mirrored {len(photo_meta)} advancement titles from their FTB quest objects")

def main() -> None:
    if not QUESTS.is_dir():
        raise SystemExit(f"r71 quest staging not found: {QUESTS}")
    knowledge_src = STAGING / "config/ic2newera/quest_knowledge.json"
    source_knowledge = json.loads(knowledge_src.read_text(encoding="utf-8-sig"))
    allowed_quest_ids = {q["id"] for q in source_knowledge.get("quests", [])}
    if CHAPTER_OUT.exists():
        shutil.rmtree(CHAPTER_OUT)
    stale_kubejs = OUT / "kubejs"
    if stale_kubejs.exists():
        shutil.rmtree(stale_kubejs)
    CHAPTER_OUT.mkdir(parents=True)
    for p in QUESTS.rglob("*"):
        rel = p.relative_to(QUESTS)
        if p.is_dir():
            continue
        # Remove the 32 legacy reward tables from the joint pack.
        if rel.parts and rel.parts[0] == "reward_tables":
            continue
        dst = CHAPTER_OUT / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        if p.suffix.lower() in {".snbt", ".json"}:
            raw = p.read_text(encoding="utf-8-sig")
            dst.write_text(transform(raw), encoding="utf-8", newline="\n")
        else:
            shutil.copy2(p, dst)

    # Twenty-four photo targets: vanilla replacements for removed creatures;
    # the Java implementation resolves these targets when registering criteria.
    animals = [
        "minecraft:bee", "minecraft:fox", "minecraft:turtle", "minecraft:allay",
        "minecraft:camel", "spawn:angler_fish", "spawn:clam", "spawn:tuna",
        "spawn:seahorse", "spawn:sea_cow", "spawn:octopus", "spawn:herring",
        "spawn:pilot_fish", "spawn:blenny", "spawn:bluefish", "spawn:sunfish",
        "spawn:barracuda", "spawn:dodo", "spawn:iguana", "spawn:coastal_crab",
        "ic2newera:companion_robot",
    ]
    assignments = [{"kind": "entity", "target": target,
                    "advancement": f"ic2newera:photography/survey_{i:02d}"}
                   for i, target in enumerate(animals, 1)]
    assignments += [
        {"kind": "dimension", "target": "ad_astra:moon", "advancement": "ic2newera:photography/survey_22"},
        {"kind": "dimension", "target": "ad_astra:moon_orbit", "advancement": "ic2newera:photography/survey_23"},
        {"kind": "dimension", "target": "ad_astra:mars", "advancement": "ic2newera:photography/survey_24"},
    ]
    photo = OUT / "first-party-jar/data/ic2newera/photography/survey.json"
    photo.parent.mkdir(parents=True, exist_ok=True)
    photo.write_text(json.dumps({"assignments": assignments}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    generate_photo_advancements(CHAPTER_OUT / "chapters", photo)

    knowledge_dst = OUT / "config/ic2newera/quest_knowledge.json"
    knowledge_dst.parent.mkdir(parents=True, exist_ok=True)
    knowledge_dst.write_text(transform(knowledge_src.read_text(encoding="utf-8-sig")), encoding="utf-8")
    refresh_kill_task_names(knowledge_dst)

    # The removed mod's KubeJS scripts are intentionally not copied.
    chapters = list((CHAPTER_OUT / "chapters").glob("*.snbt"))
    # Use the reviewed index as the canonical playable quest count. Some SNBT
    # chapters include additional generated order templates not in that index.
    knowledge = json.loads(knowledge_dst.read_text(encoding="utf-8"))
    count = len(knowledge.get("quests", []))
    source_quest_ids = set(allowed_quest_ids)
    output_quest_ids: set[str] = set()
    for chapter in chapters:
        chapter_text = chapter.read_text(encoding="utf-8")
        quests_start = chapter_text.find("    quests: [")
        if quests_start < 0:
            continue
        quest_body = chapter_text[quests_start:]
        quests_end = quest_body.find("\n    ]")
        if quests_end >= 0:
            quest_body = quest_body[:quests_end]
        output_quest_ids.update(re.findall(r'(?m)^\s{12}id: "([0-9A-F]{16})"', quest_body))
    if output_quest_ids != source_quest_ids:
        raise SystemExit(f"Quest ID/index mismatch: source={len(source_quest_ids)} output={len(output_quest_ids)}")
    if count != 258:
        raise SystemExit(f"Expected 258 indexed quests, found {count}; output retained for inspection")
    print(f"Built {count} indexed quests in {len(chapters)} chapters at {OUT}")
    validate_output()

if __name__ == "__main__":
    main()
