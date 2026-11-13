"""Compare the distributed and installed bytes, quest references, and source item data."""
import hashlib
import json
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

from build_joint import NAME, OUT, PROFILE, W, digest, read, write
from merge_quests import read as read_snbt


def exactly(value, expected, label):
    if value != expected:
        raise ValueError(f"{label}: {value!r} != {expected!r}")


def main():
    selections = read(W / "selection.json")
    installed = PROFILE.parent / NAME
    client = next(OUT.glob("*Windows.mrpack"))
    server = next(OUT.glob("*Server.zip"))
    byname = {r["name"]: r for r in selections}
    with ZipFile(client) as c, ZipFile(server) as s:
        if c.testzip() or s.testzip(): raise ValueError("Damaged compressed payload")
        cn, sn = set(c.namelist()), set(s.namelist())
        exactly(len(cn), len(c.namelist()), "client duplicate zip members")
        exactly(len(sn), len(s.namelist()), "server duplicate zip members")
        roots = {n.split("/")[0] for n in sn}
        exactly(len(roots), 1, "server root count")
        prefix = next(iter(roots)) + "/"
        index = json.loads(c.read("modrinth.index.json"))
        exactly(index["dependencies"], {"minecraft": "1.20.1", "forge": "47.4.28"}, "launcher versions")
        downloads = {i["path"]: i for i in index["files"]}
        files = {n.removeprefix("overrides/") for n in cn if n.startswith("overrides/mods/")}
        files.update(n for n in downloads if n.startswith("mods/"))
        exactly(files, {"mods/" + n for n in byname}, "client mods")
        exactly({p.name for p in (installed / "mods").glob("*.jar")}, set(byname), "installed mods")
        exactly({n.removeprefix(prefix + "mods/") for n in sn if n.startswith(prefix + "mods/")},
                {r["name"] for r in selections if r["server"]}, "server mods")
        for row in selections:
            rel = "mods/" + row["name"]
            exactly(digest(installed / rel), row["sha256"], "installed " + rel)
            if rel in downloads:
                exactly(digest(installed / rel, "sha512"), downloads[rel]["hashes"]["sha512"], rel)
            else:
                exactly(hashlib.sha256(c.read("overrides/" + rel)).hexdigest(), row["sha256"], rel)
            if row["server"]:
                exactly(hashlib.sha256(s.read(prefix + rel)).hexdigest(), row["sha256"], "server " + rel)
        for visual in read(W / "visuals.json"):
            rel = visual["path"]
            for algorithm, expected in downloads[rel]["hashes"].items():
                exactly(digest(installed / rel, algorithm), expected, rel)
        for n in sn:
            rel = n.removeprefix(prefix)
            if rel.startswith(("world/", "saves/", "resourcepacks/", "shaderpacks/", "PCL/", "kubejs/client_scripts/")):
                raise ValueError("Unexpected dedicated-server file " + rel)
        for file in (W / "client-overlay").rglob("*"):
            if file.is_file():
                rel = file.relative_to(W / "client-overlay").as_posix()
                if rel.startswith(("config/ftbquests/", "config/ic2newera/quest_knowledge.json", "kubejs/data/",
                                   "kubejs/server_scripts/", "kubejs/startup_scripts/", "tacz/")) or rel.endswith(
                                       ("greenhouse_builder.sm", "sell_bin.sm", "satellite_navigation_data_card.ui")):
                    expected = file.read_bytes()
                    if c.read("overrides/" + rel) != expected or s.read(prefix + rel) != expected or (installed / rel).read_bytes() != expected:
                        raise ValueError("Static game data differ: " + rel)
        for rel in ("server.jar", "start-server.sh", "Start-Server.bat", "eula.txt", "server.properties",
                    "ic2newera-fixed-voice.jar", "LICENSE-Create-Delight-Remake.txt", "SERVER-BUILD-MANIFEST.json"):
            if prefix + rel not in sn: raise ValueError(rel)
    quests = installed / "config/ftbquests/quests"
    files = [(f, read_snbt(f)) for f in (quests / "chapters").glob("*.snbt")]
    all_quests = {q["id"]: q for _, ch in files for q in ch["quests"]}
    index = read(installed / "config/ic2newera/quest_knowledge.json")
    exactly({r["id"] for r in index["quests"]}, set(all_quests), "AI task IDs")
    done, visiting = set(), set()
    def walk(ident):
        if ident in done: return
        if ident in visiting: raise ValueError("Cyclic dependency: " + ident)
        visiting.add(ident)
        for dep in all_quests[ident].get("dependencies", []): walk(dep)
        visiting.remove(ident)
        done.add(ident)
    for ident in all_quests: walk(ident)
    new = [q for f, ch in files if f.stem.startswith(("joint_", "delight_")) for q in ch["quests"]]
    exactly(len(new), 120, "new task count")
    wanted = {entry["item"] for q in new for entry in q["tasks"] + q["rewards"] if isinstance(entry.get("item"), str)}
    pending = set(wanted)
    for f in [installed / (NAME + ".jar"), *sorted((installed / "mods").glob("*.jar"))]:
        with ZipFile(f) as jar:
            keys = set(jar.namelist())
            for item in pending.copy():
                ns, key = item.split(":")
                if f"assets/{ns}/models/item/{key}.json" in keys: pending.remove(item)
    # These two are registered by MBD2 from the matched LDLib project.
    dynamic = {"createdelight:" + n.stem for n in (installed / "ldlib/assets/mbd2/machine").glob("*.sm")}
    exactly(pending - dynamic, set(), "unsubstantiated new task/reward items")
    deps = read(W / "deps.json")["summary"]
    exactly(deps["issues"]["error"], 0, "dependency errors")
    report = {"release": "r71", "minecraft": "1.20.1", "forge": "47.4.28", "clientJars": len(selections),
              "serverJars": sum(r["server"] for r in selections), "allSharedJarHashesMatch": True,
              "chapters": len(files), "handbookQuests": len(done), "newQuests": len(new),
              "verifiedDistinctNewTaskAndRewardItems": len(wanted), "dynamicItems": sorted(pending),
              "aiQuestCount": index["count"], "aiSha256": digest(installed / "config/ic2newera/quest_knowledge.json"),
              "forgeDependencyErrors": 0, "forgeMetadataWarnings": deps["issues"]["warning"],
              "minecraftOrDedicatedServerStarted": False,
              "artifacts": {f.name: {"bytes": f.stat().st_size, "sha256": digest(f)} for f in (client, server)}}
    write(W / "verified-release.json", report)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
