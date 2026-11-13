"""Compile the installed FTB book into the Chinese assistant's searchable catalogue."""
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from zipfile import ZipFile

from merge_quests import read


def plain(value):
    if isinstance(value, list):
        return " ".join(plain(v) for v in value)
    if isinstance(value, dict):
        return (str(value.get("text", value.get("translate", ""))) + plain(value.get("extra", []))).strip()
    if isinstance(value, str) and value.lstrip().startswith("{"):
        try:
            component = json.loads(value)
            if isinstance(component, dict): return plain(component)
        except ValueError:
            pass
    return re.sub(r"[§&][0-9a-fklmnor]", "", str(value or "")).strip()


def build_catalogue(quests, mod_paths, vanilla, lang_assets, destination):
    lang = {}
    for path in [vanilla, *mod_paths]:
        if not path or not Path(path).is_file():
            continue
        with ZipFile(path) as jar:
            for f in jar.namelist():
                if re.fullmatch(r"assets/[\w.-]+/lang/zh_cn\.json", f):
                    try:
                        lang.update(json.loads(jar.read(f).decode("utf-8-sig")))
                    except (ValueError, UnicodeError):
                        pass
    for path in lang_assets:
        try:
            lang.update(json.loads(path.read_text("utf-8-sig")))
        except (ValueError, UnicodeError):
            continue
    def label(identifier):
        if ":" not in identifier:
            return identifier
        ns, path = identifier.split(":", 1)
        name = next((lang[k] for prefix in ("item", "block", "entity", "biome", "fluid")
                     if (k := f"{prefix}.{ns}.{path.replace('/', '.')}") in lang), "")
        return f"{plain(name)}〔{identifier}〕" if name else identifier

    def stack(value, match_nbt=True):
        if isinstance(value, dict):
            ident = str(value.get("id", value.get("item", "")))
            tag = value.get("tag") or value.get("nbt")
            # The book holds the complete stack. Preserve short variant constraints
            # for tagged tools and packs without reproducing pages of item lore.
            if ident == "itemfilters:tag" and isinstance(tag, dict):
                return "任一标签物品 #" + str(tag.get("value", ""))
            state = ""
            if match_nbt and tag:
                keys = ("GunId", "gateway", "Potion", "variant", "Material", "tconstruct:materials")
                variants = [f"{k}={plain(tag[k])}" for k in keys if isinstance(tag, dict) and k in tag]
                state = "（" + ("、".join(variants) or "需符合任务页的物品数据") + "）"
            return label(ident) + state
        return label(str(value or ""))

    chapter_files = sorted((quests / "chapters").glob("*.snbt"))
    config = read(quests / "data.snbt")
    lookup, pending, edges = {}, {}, defaultdict(list)
    counts = Counter()
    tables = {}
    for path in (quests / "reward_tables").glob("*.snbt"):
        table = read(path)
        tables[int(str(table["id"]), 16)] = table
    for path in chapter_files:
        ch = read(path)
        title = plain(ch.get("title")) or path.stem
        for q in ch.get("quests", []):
            ident = q["id"]
            tasks, rewards = [], []
            for task in q.get("tasks", []):
                typ = str(task.get("type", "")).removeprefix("ftbquests:")
                target = stack(task.get("item"), task.get("match_nbt", False))
                count = int(task.get("count", task.get("value", 1))) if isinstance(task.get("count", task.get("value", 1)), (int, float)) else 1
                if typ == "item":
                    consumes = task.get("consume_items", config.get("default_consume_items", False))
                    tasks.append(f"{'提交并扣除' if consumes else '持有检测'} {target} ×{count}")
                elif typ == "advancement":
                    criterion = task.get("criterion")
                    tasks.append("完成进度 " + str(task.get("advancement", "")) + (f" 的条件 {criterion}" if criterion else ""))
                elif typ in ("kill", "questsadditions:killnbt"):
                    tasks.append(f"击败 {stack(task.get('entity'))} ×{count}")
                elif typ == "checkmark":
                    tasks.append("手动点击确认")
                elif typ == "observation":
                    tasks.append("观察 " + label(str(task.get("to_observe", ""))))
                elif typ in ("dimension", "biome", "structure"):
                    area = {"dimension":"进入维度", "biome":"抵达生物群系", "structure":"找到结构"}[typ]
                    tasks.append(area + " " + label(str(task.get(typ, ""))))
                elif typ == "stat":
                    tasks.append(f"统计 {task.get('stat', '')} 达到 {count}")
                elif typ == "fluid":
                    tasks.append(f"流体 {stack(task.get('fluid'))} ×{task.get('amount', 0)} mB")
                elif typ == "questsadditions:days":
                    tasks.append(f"天数达到 {task.get('days', 0)}")
                elif typ == "questsadditions:time":
                    tasks.append(f"完成计时目标（任务页计时值 {task.get('elapsedTime', 0)}）")
                else:
                    tasks.append(plain(task.get("title")) or ("按任务页完成 " + typ))
            for reward in q.get("rewards", []):
                typ = str(reward.get("type", "")).removeprefix("ftbquests:")
                if typ == "item":
                    mode = "每人领取" if not reward.get("team_reward", config.get("default_reward_team", False)) else "团队共享"
                    rewards.append(f"{stack(reward.get('item'))} ×{reward.get('count', 1)}（{mode}）")
                elif typ in ("random", "choice", "loot", "all_table"):
                    mode = {"random":"随机抽取","choice":"可选择","loot":"战利品抽取","all_table":"发放奖励表内容"}[typ]
                    table = tables.get(int(reward.get("table_id", 0)) & ((1 << 64) - 1), {})
                    rewards.append(mode + " · " + (plain(table.get("title")) or "见任务奖励表"))
                elif typ in ("xp", "xp_levels"):
                    rewards.append(("经验点" if typ == "xp" else "经验等级") + f" ×{reward.get(typ, 1)}")
                elif typ == "custom":
                    rewards.append("整合包脚本奖励" + ("（" + "、".join(reward["tags"]) + "）" if reward.get("tags") else ""))
                elif typ == "command":
                    rewards.append("由服务器执行此节点配置的奖励")
                else:
                    rewards.append(plain(reward.get("title")) or typ)
            name = plain(q.get("title")) or plain(q.get("subtitle")) or (tasks[0] if tasks else ident)
            lookup[ident] = name
            deps = q.get("dependencies", [])
            for dep in deps:
                edges[dep].append(ident)
            own = path.stem.startswith(("joint_", "delight_", "newera_"))
            text = plain(q.get("description", "")) if own else ""
            pending[ident] = (title, name, tasks, rewards, deps, own, text, q.get("min_required_dependencies", 0), q.get("dependency_requirement", "all_completed"))
            counts[path.stem] += 1
    rows = []
    for ident, (chapter, name, tasks, rewards, deps, own, text, minimum, requirement) in pending.items():
        rows.append({"id": ident, "chapter": chapter, "title": name, "own": own,
                     "tasks": tasks, "rewards": rewards, "previous": [lookup[k] for k in deps],
                     "minimum": int(minimum), "requirement": requirement, "next": [lookup[k] for k in edges[ident]],
                     "hint": text[:620]})
    out = {"format": 1, "chapters": len(chapter_files), "count": len(rows), "quests": rows}
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return dict(chapters=out["chapters"], quests=len(rows), own=sum(row["own"] for row in rows))
