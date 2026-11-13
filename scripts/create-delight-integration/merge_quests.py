#!/usr/bin/env python3
"""Merge Create-Delight Remake and New Era FTB Quests without altering inputs."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import sys
from pathlib import Path

AUTOFTBQ = Path(os.environ.get("AUTOFTBQ_PATH") or Path(__file__).resolve().parents[2] / "build-isolated/player-packaging/20261113-r69-expedition/autoftbq")
sys.path.insert(0, str(AUTOFTBQ))
from snbt_parser import parse_snbt, to_snbt  # noqa: E402


def read(path: Path):
    return parse_snbt(path.read_text(encoding="utf-8-sig"))


def write(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(to_snbt(value) + "\n", encoding="utf-8")


def stable_id(text: str, used: set[str]) -> str:
    i = 0
    while True:
        value = hashlib.sha256(f"create-delight-joint:{text}:{i}".encode()).hexdigest()[:16].upper()
        if value not in used:
            used.add(value)
            return value
        i += 1


def reward_id(text: str, used: set[str]) -> str:
    """New reward IDs use r70's 694A prefix for per-player claim protection."""
    i = 0
    while True:
        value = "694A" + hashlib.sha256(f"create-delight-reward:{text}:{i}".encode()).hexdigest()[:12].upper()
        if value not in used:
            used.add(value)
            return value
        i += 1


def walk_ids(chapters: list[dict]) -> set[str]:
    found = set()
    def add(value, context):
        ident = value.get("id") if isinstance(value, dict) else None
        if not isinstance(ident, str): return
        if ident in found: raise ValueError(f"重复的 {context} id: {ident}")
        found.add(ident)
    for chapter in chapters:
        add(chapter, "chapter")
        for key in ("quests", "quest_links", "images"):
            for entry in chapter.get(key, []):
                add(entry, key)
                if key == "quests":
                    for nested in ("tasks", "rewards"):
                        for detail in entry.get(nested, []): add(detail, nested)
    return found


def make_chapter(used: set[str], key: str, title: str, icon: str, specs: list[dict], order: int) -> dict:
    chapter_id = stable_id(f"{key}-chapter", used)
    quests = []
    for n, spec in enumerate(specs):
        qid = stable_id(f"{key}-quest-{n}", used)
        task_id = stable_id(f"{key}-task-{n}", used)
        tasks = []
        for task_spec in spec["tasks"]:
            task = {"id": task_id if not tasks else stable_id(f"{key}-task-{n}-{len(tasks)}", used),
                    **task_spec}
            tasks.append(task)
        rewards = []
        for ri, (item, count) in enumerate(spec["rewards"]):
            rewards.append({"id": reward_id(f"{key}-reward-{n}-{ri}", used), "type": "item",
                            "item": item, "count": count, "team_reward": False})
        q = {"id": qid, "title": spec["title"], "subtitle": spec["subtitle"],
             "description": spec["description"], "icon": spec.get("icon", next((task["item"] for task in tasks if isinstance(task.get("item"), str)), icon)),
             "x": (n % 5) * 3.5, "y": (n // 5) * 3.5, "shape": "square",
             "tasks": tasks, "rewards": rewards}
        if n: q["dependencies"] = [quests[-1]["id"]]
        quests.append(q)
    return {"id": chapter_id, "filename": key, "title": title, "icon": icon,
            "group": "DELIGHT_JOINT_GROUP", "order_index": order, "x": 0.0, "y": 0.0,
            "quest_links": [], "quests": quests}


def item_task(item: str, count: int = 1, consume: bool = True) -> dict:
    task = {"type": "item", "item": item, "count": count, "consume_items": consume}
    if item.startswith("tconstruct:"):
        # FTB Quests 2001.4.22 `match_nbt: false` explicitly accepts valid
        # TConstruct items across their recipe-generated material NBT.
        task["match_nbt"] = False
    return task


def advancement_task(name: str) -> dict:
    return {"type": "advancement", "advancement": f"ic2newera:newera/{name}", "criterion": ""}


def build_additions(used: set[str], base_root: str, newera_root: str) -> list[dict]:
    eco = [
        {"title":"先读生物图鉴","subtitle":"从底包生物章开始","description":["由《Animal Companions／各种各样的生物》入口打开 Alex's Mobs 动物图鉴。","先准备野外工具，再开始记录。"],"tasks":[item_task("alexsmobs:animal_dictionary",consume=False)],"rewards":[("minecraft:torch",4)]},
        {"title":"给牛群留一份记录","subtitle":"主世界牧场观察","description":["在主世界亲自击杀一头牛，确认野外采集条件确实完成。","收下少量皮革，交给下一站做工具鞘料。"],"tasks":[{"type":"kill","entity":"minecraft:cow","value":1}],"rewards":[("minecraft:leather",2)]},
        {"title":"交一份牛皮样本","subtitle":"消耗型交货","description":["交出 2 张皮革作观察记录。任务会消耗这份样本；请留有备用皮革。","个人补给：一张地图用于标记下一处观察点。"],"tasks":[item_task("minecraft:leather",2)],"rewards":[("minecraft:map",1)]},
        {"title":"捉到一尾鮟鱇鱼","subtitle":"Spawn 深海样本","description":["用桶实际收集一条 Spawn 鮟鱇鱼，并交出鮟鱇鱼桶。物品带有生物数据，任务按物品注册 ID 检查，不要求固定变种。","提交后补回一个空桶，避免把容器损失误作奖励。"],"tasks":[item_task("spawn:angler_fish_bucket")],"rewards":[("minecraft:bucket",1)]},
        {"title":"手机拍到野外目标","subtitle":"服务端记录的快门条件","description":["使用工业手机或 Exposure 相机拍摄 Alex's Mobs 浣熊、海豹、走鹃，或 Alex's Caves 三叶虾。","任务通过服务端 `ic2newera:newera/delight_wildlife_photo` 里程碑确认目标照片在快门时成功记录；无需再公开发布照片。"],"tasks":[advancement_task("delight_wildlife_photo")],"rewards":[("minecraft:spyglass",1)]},
        {"title":"交回响尾蛇响环","subtitle":"Alex's Mobs 特有战利品","description":["从底包 Alex's Mobs 响尾蛇取得并交出 1 个响环。该物品是生物战利品，不接受普通线或其他替代物。","个人补给：四支箭，供后续野外作业。"],"tasks":[item_task("alexsmobs:rattlesnake_rattle")],"rewards":[("minecraft:arrow",4)]},
        {"title":"交回走鹃羽毛","subtitle":"Alex's Mobs 特有战利品","description":["从走鹃取得并交出 1 根走鹃羽毛；任务会消耗羽毛，请先确认有备用。","个人补给：一条面包，作为继续出发的口粮。"],"tasks":[item_task("alexsmobs:roadrunner_feather")],"rewards":[("minecraft:bread",1)]},
        {"title":"交回三叶虾尾","subtitle":"Alex's Caves 特有战利品","description":["从三叶虾取得并交出 1 条三叶虾尾。按真实物品 ID 交货，不把照片里出现目标当成掉落物。","个人补给：一枚指南针，供返回营地定位。"],"tasks":[item_task("alexscaves:trilocaris_tail")],"rewards":[("minecraft:compass",1)]},
        {"title":"登记鮟鱇鱼观察点","subtitle":"物品交付与可选导航","description":["交出一张纸作为路线卡。可选：在手机任务路线中加入拍摄点并按导航前往；该路线行为没有单独任务检测条件。","个人补给：一只船，便于水域往返。"],"tasks":[item_task("minecraft:paper")],"rewards":[("minecraft:oak_boat",1)]},
        {"title":"联合生态档案结案","subtitle":"完成交接","description":["完成本章前置的照片发布与战利品交货后，交出一部备用工业手机作为档案设备。工业手机不会被任务直接从手上取走，交货会消耗一部备用设备。","所有奖励均按个人领取；本节点不发动物、不创建刷怪蛋。"],"tasks":[item_task("ic2newera:industrial_phone")],"rewards":[("minecraft:name_tag",1)]},
    ]
    shop = [
        {"title":"配齐火泥原料","subtitle":"匠魂炉从常见方块起步","description":["交出 4 份匠魂火泥（TConstruct 注册物品 `tconstruct:grout`），由沙砾、沙子与黏土按 JEI 配方制作。","个人补给：少量焦黑砖原料，下一站仍需自行补齐炉体材料。"],"tasks":[item_task("tconstruct:grout",4)],"rewards":[("tconstruct:seared_brick",2)]},
        {"title":"烧成焦黑砖","subtitle":"先做铸造炉体","description":["交出 4 块烧制后的焦黑砖 `tconstruct:seared_brick`。材料按普通物品 ID 检查。","个人补给：一枚焦黑砖，下一步制作加热器还需自行补齐其它配方材料。"],"tasks":[item_task("tconstruct:seared_brick",4)],"rewards":[("tconstruct:seared_brick",1)]},
        {"title":"交付焦黑加热器","subtitle":"炉体第一件设备","description":["按 JEI 配方制作并交出 1 台 `tconstruct:seared_heater`。任务只收一台，不会免费提供整套熔化炉。","个人补给：一块煤，供下一站烧制。"],"tasks":[item_task("tconstruct:seared_heater")],"rewards":[("minecraft:coal",1)]},
        {"title":"交付焦黑熔化炉","subtitle":"搭起基础熔炼","description":["按 JEI 配方制作并交出 1 台 `tconstruct:seared_melter`。请用自制备用设备提交，不会消耗工作中的炉体。","个人补给：一份黏土球，补充浇铸工位原料。"],"tasks":[item_task("tconstruct:seared_melter")],"rewards":[("minecraft:clay_ball",1)]},
        {"title":"装好浇注口","subtitle":"熔液流向铸台","description":["制作并交出 1 个 `tconstruct:seared_faucet`。必须使用匠魂配方产物，非同名物不能替代。","个人补给：一块焦黑砖，留作工位扩建材料。"],"tasks":[item_task("tconstruct:seared_faucet")],"rewards":[("tconstruct:seared_brick",1)]},
        {"title":"备妥浇铸台","subtitle":"模具在工位成形","description":["制作并交出 1 个 `tconstruct:seared_table`。取一个备用件提交，已安装在熔化炉旁的工位不会被任务移动。","个人补给：两枚金粒，作为模具与浇铸材料储备，不足以直接完成下一项。"],"tasks":[item_task("tconstruct:seared_table")],"rewards":[("minecraft:gold_nugget",2)]},
        {"title":"备齐模具与木柄","subtitle":"工具组件交付","description":["分别制作并交出一个匠魂空白模具 `tconstruct:pattern` 和木质手柄部件 `tconstruct:tool_handle`。两项都须提交；任务只检查注册 ID，不比较木材的动态材料数据。","个人补给：两块铁锭供后续工业试制，仍需自行加工。"],"tasks":[item_task("tconstruct:pattern"), item_task("tconstruct:tool_handle")],"rewards":[("minecraft:iron_ingot",2)]},
        {"title":"交一枚镐头","subtitle":"浇铸或部件工位出件","description":["用匠魂模具和有效材料制作并提交 `tconstruct:pick_head`。镐头可按当前 JEI 使用部件工位或浇铸路线制成；只按物品注册 ID 验证，以兼容真实材料 NBT。","个人补给：一枚金粒，补一点模具材料，不会直接完成整把工具。"],"tasks":[item_task("tconstruct:pick_head")],"rewards":[("minecraft:gold_nugget",1)]},
        {"title":"提交一把备用镐","subtitle":"带材料的可用工具","description":["在匠魂工站实际组装一把带有效材料的 `tconstruct:pickaxe`，确认耐久大于零，再提交一把备用镐。任务不要求固定材料 NBT，避免与合法材料配方不匹配；请勿提交唯一主工具。","个人补给：两块铜锭，供下一站工业原料交货。"],"tasks":[item_task("tconstruct:pickaxe")],"rewards":[("minecraft:copper_ingot",2)]},
        {"title":"交付铜锭","subtitle":"工业材料入库","description":["交出 2 块铜锭 `minecraft:copper_ingot`。本任务实际消耗物品，留好电缆与工具所需库存。","个人补给：一份粗铅矿石，带回矿场继续加工。"],"tasks":[item_task("minecraft:copper_ingot",2)],"rewards":[("ic2:raw_lead",1)]},
        {"title":"提交铅矿样本","subtitle":"矿物识别与生产线","description":["交出 1 块 IC2 铅矿石 `ic2:lead_ore`，作为矿物识别样本；这会消耗一块矿石，请先留出打粉用矿石。","个人补给：一份含杂质铅粉，供下一项核对打粉结果。"],"tasks":[item_task("ic2:lead_ore")],"rewards":[("ic2newera:impure_lead_dust",1)]},
        {"title":"检验含杂质铅粉","subtitle":"粉碎工序样品","description":["交出 2 份含杂质铅粉 `ic2newera:impure_lead_dust`；必须来自现有铅粉碎路线，不接受成品铅粉替代。","个人补给：一份 IC2 铅粉作为后续处理参考，不会绕过本次样品任务。"],"tasks":[item_task("ic2newera:impure_lead_dust",2)],"rewards":[("ic2:lead_dust",1)]},
        {"title":"送检离心后的铅粉","subtitle":"离心成品抽检","description":["把杂质铅粉直接离心，或先水洗再离心。交出 2 份成品铅粉，其余可熔炼成铅锭。","个人补给：一枚铁锭，供后续合金配料。"],"tasks":[item_task("ic2:lead_dust",2)],"rewards":[("minecraft:iron_ingot",1)]},
        {"title":"交付低碳钢锭","subtitle":"工业合金终点","description":["在新纪元合金炉用 IC2 煤粉和铁粉配出低碳钢粉，按 JEI 熔炼成锭后交出一块。","本章交付完成。个人奖励为少量现场补给，不提供跳级设备或稀有矿物。"],"tasks":[item_task("ic2newera:tempered_steel_ingot")],"rewards":[("minecraft:torch",4)]},
    ]
    ecology = make_chapter(used, "delight_ecology", "生态协作 · 野外观察", "alexsmobs:animal_dictionary", eco, 900)
    workshop = make_chapter(used, "delight_workshop", "工坊交付 · 匠魂与工业委托", "tconstruct:seared_melter", shop, 901)
    ecology["quest_links"] = [{"id": stable_id("creature-fieldwork-link-base", used), "linked_quest": base_root, "x": -2.0, "y": 0.0}]
    workshop["quest_links"] = [{"id": stable_id("workshop-link-newera", used), "linked_quest": newera_root, "x": -2.0, "y": 0.0}]
    return [ecology, workshop]


def optional_extra_chapters(base_chapters: list[dict], newera_chapters: list[dict], used: set[str]):
    """Load the root agent's companion chapter module when it is delivered."""
    module_path = Path(__file__).with_name("extra_chapters.py")
    if not module_path.is_file(): return [], []
    spec = importlib.util.spec_from_file_location("delight_extra_chapters", module_path)
    if spec is None or spec.loader is None: raise RuntimeError(f"无法加载扩展章节模块：{module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    groups, chapters = module.build_extra_chapters(base_chapters, newera_chapters, used)
    if not isinstance(groups, list) or not isinstance(chapters, list):
        raise TypeError("build_extra_chapters 必须返回 (extra_groups, extra_chapters) 两个 list")
    return groups, chapters


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base-quests", required=True, type=Path, help="底包 config/ftbquests/quests 目录")
    ap.add_argument("--newera-quests", required=True, type=Path, help="新纪元任务目录，含 chapters/ 和 data.snbt")
    ap.add_argument("--output", required=True, type=Path, help="输出任务目录")
    args = ap.parse_args()
    base, newera, out = (p.resolve() for p in (args.base_quests, args.newera_quests, args.output))
    if not (base / "chapters").is_dir() or not (newera / "chapters").is_dir():
        ap.error("两个输入目录都必须包含 chapters/")
    if out == base or out == newera or base in out.parents or newera in out.parents or out in base.parents or out in newera.parents:
        ap.error("输出目录及输入目录必须互不嵌套，避免覆盖源任务")
    if out.exists(): shutil.rmtree(out)
    shutil.copytree(base, out)
    base_files = sorted((base / "chapters").glob("*.snbt"))
    new_files = sorted((newera / "chapters").glob("*.snbt"))
    base_chapters = [read(p) for p in base_files]
    new_chapters = [read(p) for p in new_files]
    base_ids = walk_ids(base_chapters)
    new_ids = walk_ids(new_chapters)
    collisions = base_ids & new_ids
    if collisions: raise ValueError(f"新纪元与底包 ID 冲突: {sorted(collisions)}")
    used = base_ids | new_ids
    base_group_data = read(base / "chapter_groups.snbt")
    newera_group_data = read(newera / "chapter_groups.snbt") if (newera / "chapter_groups.snbt").exists() else {"chapter_groups": []}
    existing_groups = base_group_data.get("chapter_groups", []) + newera_group_data.get("chapter_groups", [])
    seen_group_ids = {}
    for group in existing_groups:
        gid = group.get("id")
        if gid in seen_group_ids and seen_group_ids[gid] != group.get("title"):
            raise ValueError(f"底包/新纪元章节组 ID 冲突：{gid}")
        seen_group_ids[gid] = group.get("title")
        used.add(gid)
    # Identify the biological introduction and New Era's fieldwork chapter by
    # their source filenames and validated quest IDs.
    bio_file = next((p for p in base_files if p.stem == "Animal_Companions"), None)
    field_file = next((p for p in new_files if p.stem == "newera_field"), None)
    if not bio_file or not field_file: raise ValueError("缺少底包 Animal_Companions 或新纪元 newera_field")
    bio = read(bio_file); field = read(field_file)
    bio_root = bio["quests"][0]["id"]
    field_root = field["quests"][0]["id"]
    # Append to base theme, retaining its settings, 41 chapters, reward tables,
    # translations and all original IDs. Copy New Era chapters as independent files.
    additions = build_additions(used, bio_root, field_root)
    extra_groups, extra_chapters = optional_extra_chapters(base_chapters, new_chapters, used)
    all_additions = additions + extra_chapters
    joint_group_id = stable_id("delight-joint-group", used)
    for chapter in additions: chapter["group"] = joint_group_id
    bio.setdefault("quest_links", []).append({"id": stable_id("animal-to-ecology-link", used),
        "linked_quest": additions[0]["quests"][0]["id"], "x": 14.0, "y": 0.0})
    write(bio_file if out == base else out / "chapters" / bio_file.name, bio)
    # Make both handbooks cross-navigable from the New Era field chapter too.
    field.setdefault("quest_links", []).append({"id": stable_id("newera-to-ecology-link", used),
        "linked_quest": additions[0]["quests"][4]["id"], "x": 12.0, "y": 0.0})
    write(out / "chapters" / field_file.name, field)
    for src in new_files:
        if src.name != field_file.name: shutil.copy2(src, out / "chapters" / src.name)
    for chapter, name in zip(additions, ("delight_ecology.snbt", "delight_workshop.snbt")):
        write(out / "chapters" / name, chapter)
    for chapter in extra_chapters:
        filename = chapter.get("filename") or f"extra_{chapter['id'].lower()}"
        chapter["filename"] = filename
        write(out / "chapters" / f"{filename}.snbt", chapter)
    groups_path = out / "chapter_groups.snbt"
    groups = read(groups_path)
    groups.setdefault("chapter_groups", []).append({"id": joint_group_id, "title": "联合工单 · 生态与工坊"})
    for group in extra_groups:
        if group not in groups["chapter_groups"]: groups["chapter_groups"].append(group)
    # Include the New Era visible section title without replacing the base book title/settings.
    for group in newera_group_data.get("chapter_groups", []):
        if group not in groups["chapter_groups"]: groups["chapter_groups"].append(group)
    write(groups_path, groups)

    # Verify all quest links and dependencies across all emitted chapters.
    emitted_files = sorted((out / "chapters").glob("*.snbt"))
    emitted = [read(p) for p in emitted_files]
    all_ids = walk_ids(emitted)
    output_group_ids = {g.get("id") for g in groups.get("chapter_groups", []) if g.get("id")}
    if len(output_group_ids) != len(groups.get("chapter_groups", [])):
        raise ValueError("输出章节组 ID 有缺失或冲突")
    if output_group_ids & all_ids:
        raise ValueError(f"章节组 ID 与其它任务 ID 冲突：{sorted(output_group_ids & all_ids)}")
    all_ids |= output_group_ids
    quest_ids = {q["id"] for c in emitted for q in c.get("quests", [])}
    missing = []
    for c in emitted:
        for q in c.get("quests", []):
            for dep in q.get("dependencies", []):
                if dep not in quest_ids: missing.append((q["id"], "dependency", dep))
        for link in c.get("quest_links", []):
            if link.get("linked_quest") not in quest_ids: missing.append((c.get("id"), "link", link.get("linked_quest")))
    if missing: raise ValueError(f"悬空内部/跨章链接: {missing}")
    base_rewards = sum(len(q.get("rewards", [])) for c in base_chapters for q in c.get("quests", []))
    newera_rewards = sum(len(q.get("rewards", [])) for c in new_chapters for q in c.get("quests", []))
    newera_individual = sum(1 for c in new_chapters for q in c.get("quests", []) for r in q.get("rewards", []) if r.get("team_reward") is False)
    added_rewards = [r for c in all_additions for q in c.get("quests", []) for r in q.get("rewards", [])]
    if any(not r["id"].startswith("694A") or r.get("team_reward") is not False for r in added_rewards):
        raise ValueError("新增奖励必须用 694A 前缀并逐项设置个人领取")
    counts = {"base_chapters": len(base_chapters), "newera_chapters": len(new_chapters),
              "added_chapters": len(all_additions), "merged_chapters": len(emitted),
              "base_quests": sum(len(c.get("quests", [])) for c in base_chapters),
              "newera_quests": sum(len(c.get("quests", [])) for c in new_chapters),
              "added_quests": sum(len(c["quests"]) for c in all_additions), "added_ecology_quests": len(additions[0]["quests"]),
              "added_workshop_quests": len(additions[1]["quests"]), "companion_quests": sum(len(c.get("quests", [])) for c in extra_chapters), "merged_quests": len(quest_ids),
              "base_rewards_preserved": base_rewards, "newera_rewards_preserved": newera_rewards,
              "newera_individual_rewards": newera_individual, "added_individual_rewards": len(added_rewards),
              "reward_tables_preserved": len(list((out / "reward_tables").glob("*.snbt"))) if (out / "reward_tables").exists() else 0,
              "added_groups": 1 + len(extra_groups),
              "group_count": len(groups.get("chapter_groups", [])),
              "merged_group_titles": [g.get("title", "") for g in groups.get("chapter_groups", [])],
              "added_items": sorted({t.get("item") for c in all_additions for q in c.get("quests", []) for t in q.get("tasks", []) if t.get("item")}),
              "added_rewards": sorted({r["item"] for r in added_rewards}),
              "added_consumed_item_requirements": [
                  {"item": t["item"], "count": t.get("count", 1)}
                  for c in all_additions for q in c.get("quests", []) for t in q.get("tasks", [])
                  if t.get("type") == "item" and t.get("consume_items") is True],
              "added_advancement_targets": sorted({
                  t.get("advancement") for c in all_additions for q in c.get("quests", []) for t in q.get("tasks", [])
                  if t.get("type") == "advancement"}),
              "added_kill_targets": sorted({
                  t.get("entity") for c in all_additions for q in c.get("quests", []) for t in q.get("tasks", [])
                  if t.get("type") == "kill"}),
              "checked_ids": len(all_ids), "checked_links": sum(len(c.get("quest_links", [])) for c in emitted),
              "checked_dependencies": sum(len(q.get("dependencies", [])) for c in emitted for q in c.get("quests", [])),
              "missing_dependencies_or_links": 0}
    (out / "merge_report.json").write_text(json.dumps(counts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(counts, ensure_ascii=True, indent=2))


if __name__ == "__main__": main()
