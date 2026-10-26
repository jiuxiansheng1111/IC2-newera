IC2: New Era 1.20.1 — Player Playtest Pack r45

Status
r45 targets Minecraft 1.20.1 / Forge 47.4.28 / Java 17. The final source build succeeded in 33 seconds, and static resource checks are complete. This revision has not been playtested in game. The existing installer updated the local isolated 1.20.1 profile on 2026-10-27, preserving the previous JAR backup. That profile can be launched normally without reimporting. The distributable filename is IC2-New-Era-1.20.1-Forge-47.4.28-r45.mrpack. Public-source synchronization is recorded in the project delivery notes and public branch.

Import and launch
Import the .mrpack into PCL2 as a separate Modrinth profile. If drag-and-drop is unavailable, select it through the modpack-install entry. Portable Java 17 and its matching source are bundled; the launcher downloads 15 locked dependencies from their authors. If necessary, select IC2-NewEra-runtime/java17/bin/javaw.exe inside the new profile. Use a new 1.20.1 world; the old 1.19.2 world is not migrated.

Included features
- Create 6.0.8, Meowchanics 2.3.1, Biotech 1.3.0, and Ad Astra 1.15.21; the 15 locked third-party dependencies are retained.
- The mechanical cat now extends vanilla Cat and connects to Meowchanics gameplay: gas production, 27-slot shared logistics, accessories and trait effects, all 13 career appearances and abilities, launchers, breeding, fusion, and splitting. The Meowchanics menu has a new mechanical-cat entry. The cat keeps its approved cuboid body, face, white wings, and tail flame; the dog keeps its existing shape and audio. These source integrations still need acceptance in the target instance.
- Cats and dogs have 40 HP / 100 hunger and use the ordinary IC2 re_battery recipe and replacement, preserving charge. At 20 TPS, a full battery lasts about 3 active hours and full hunger about 4.5 hours; idle durations are 30 / 45 hours. Paused and unloaded time does not count.
- Installing a normal chest through the item and equipment page unlocks 27 cargo slots. Empty it before removal. Cargo follows its owner. The mechanical cat's 27-slot shared logistics connects to Meowchanics.
- One vanilla leather, iron, gold, or diamond horse armor displays the full pet armor set. Armor and cargo visuals can be shown together.
- Ordinary cat and dog defense starts at 3 damage. Dogs pursue and bite; cats use native Meowchanics career weapons and attributes, and swipe when no career ability applies. Existing cat-pancake launch audio behavior is retained. Cats and dogs still play non-lethally with each other; peaceful coexistence can be enabled.
- Fish feed cats; meat and ordinary cat pancakes feed dogs. Ordinary pancakes can tame wild wolves, heal owned wolves, or start eligible breeding. Mechanical cat pancakes cannot be fed. Anvils and mechanical rollers flatten the cat; its owner can shovel it up, and real Create airflow restores it with its mechanical identity. Newborns do not copy parental batteries or cargo.
- Floor and dressing mirrors show dynamic reflections of nearby players, mechanical cats and dogs, and vanilla cats and wolves—not full building reflections. A mechanical cat approaches and hisses when it sees the front of a floor mirror.
- Humanoid robots have 100 HP / 200 hunger. Their existing batteries are unchanged; full hunger lasts about 9 active / 36 idle hours, 1.5 times battery runtime. Holding food and right-clicking plays the dedicated continuous eating motion. Zero hunger causes ongoing damage and can kill.
- The dog panel's “大狗到底叫不叫” retains the full 622.714-second user audio, with manual play/stop and dancing. This audio is bundled in the JAR and does not require Voice-Bundle. Dog attacks use wolf growls, not the cat-pancake launch sound.

Optional robot AI voice
The existing `IC2-NewEra-Voice-Bundle-Win64-2026.10.24.zip` remains separate and unchanged for the existing robot AI voice setup; r45 does not replace or repackage it.
1. Import the ordinary mrpack and close Minecraft normally.
2. Download and extract the outer Voice-Bundle ZIP. Its folder already contains `Install.cmd` and the unchanged inner Voice-Slim ZIP; no separate tool download or manual pairing is needed.
3. Run `Install.cmd` and select the newly imported 1.20.1 / Forge 47.4.28 profile directory containing its matching version JSON. Do not select mods, saves, or the shared .minecraft directory. The installer targets a fresh separate profile and refuses to overwrite an existing custom voice folder.
4. Voice inference requires an NVIDIA GPU/driver compatible with CUDA 12.8; at least 16 GB RAM is suggested. Allow about 11 GB unpacked space plus temporary space; the archive exceeds 4 GB and needs ZIP64 support. For multiplayer, install voice on the server host; audio uses the existing game connection and the backend listens only on loopback.
Choose Chinese, Japanese, or English voice on the robot Settings page. Voice generation, installation, and inference on a new machine remain pending acceptance. Ordinary text play does not need this attachment; robot AI voice stays off by default. The pet dog call is a separate New Era sound and does not require Voice-Bundle.

Scope and notes
Minecraft, accounts, API keys, and saves are not included. Free-form LLM chat requires the player's own service setup. Do not add third-party mod JARs or the user's audio with unverified public redistribution rights to the public source repository. See THIRD-PARTY-NOTICES.txt for sources and license boundaries, and PLAYTEST-STATUS.txt for r45 acceptance status.

The r43 hotfix corrected an Ad Astra dependency pin. r45 keeps the locked Ad Astra 1.15.21 dependency. A successful build and static inspection do not mean in-game acceptance has passed.
