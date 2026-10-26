IC2: New Era 1.20.1 — Player Playtest Pack r44

Status
r44 targets Minecraft 1.20.1 / Forge 47.4.28 / Java 17. The final build and packaged assets were checked, and r44 was installed into the development machine's isolated 1.20.1 profile. It has not been launched or playtested in game. Older r40/r41 acceptance results do not verify r44. Filename: IC2-New-Era-1.20.1-Forge-47.4.28-r44.mrpack.

Import and launch
Import the .mrpack into PCL2 as a separate Modrinth profile. If drag-and-drop is unavailable, select it through the modpack-install entry. Portable Java 17 and its matching source are bundled; the launcher downloads 15 locked dependencies from their authors. If necessary, select IC2-NewEra-runtime/java17/bin/javaw.exe inside the new profile. Use a new 1.20.1 world.

Included features
- Cats/dogs: 40 HP, 100 hunger, real IC2 re_battery with NBT-preserving assembly and replacement. At 20 TPS a full battery lasts about 3 active hours, full hunger 4.5 hours; idle 30/45 hours. Unloaded and paused time does not count.
- A normal chest unlocks 27 cargo slots. Empty it before removal. One vanilla leather/iron/gold/diamond horse armor equips a full visible set. Cargo is carried while following; no automatic route between chests is implemented.
- Hostile combat uses 3 base damage. Cats swipe and reference Meowchanics launch audio; dogs bite with wolf growls. Cats and dogs still play non-lethally with each other. The owner's full 622.714-second dog audio remains a separate manual play/stop button.
- Fish/meat/ordinary cat pancakes feed mechanical pets. Ordinary pancakes can tame wolves, heal owned wolves or initiate eligible breeding. Mechanical cat pancakes are never food. Saved cats retain equipment and cargo through shovel pickup and actual Create airflow restoration.
- Floor and dressing mirrors show perspective-dependent nearby players and pets, not the whole world. Mechanical cats react to the front of a floor mirror with a hiss.
- Humanoid robots: 100 HP, 200 hunger, dedicated interpolated eating motion. Their existing batteries are unchanged; full hunger lasts about 9 active/36 idle hours. Zero hunger causes real damage.
- Meowchanics adaptation covers verified hiss/cat-pancake/fan interactions, not all of that mod's accessory, gas, logistics or weapon systems. All new in-game interactions remain pending acceptance.

Optional robot AI voice
The existing `IC2-NewEra-Voice-Bundle-Win64-2026.10.24.zip` is preserved unchanged and remains separate for the existing robot AI voice environment. r44 source changes do not add new installation, inference, or voice-effect verification.
1. Import the ordinary mrpack and close Minecraft normally.
2. Download and extract the outer Voice-Bundle ZIP. Its folder already contains `Install.cmd` and the unchanged inner Voice-Slim ZIP; no separate tool download or manual pairing is needed.
3. Run `Install.cmd` and select the newly imported 1.20.1 / Forge 47.4.28 profile directory containing its matching version JSON. Do not select mods, saves, or the shared .minecraft directory. The installer targets a fresh separate profile and refuses to overwrite an existing custom voice folder.
4. Voice inference requires an NVIDIA GPU/driver compatible with CUDA 12.8 and at least 16 GB RAM is suggested. Allow about 11 GB unpacked space plus temporary space; the archive exceeds 4 GB and needs ZIP64 support. For multiplayer, install voice on the server host; audio uses the existing game connection and the backend listens only on loopback.
Choose Chinese, Japanese, or English voice on the robot Settings page. Voice generation, installation, and inference on a new machine remain pending acceptance. Ordinary text play does not need this attachment; robot AI voice stays off by default. The pet dog call is a separate New Era sound and does not require Voice-Bundle.

Scope and notes
Minecraft, accounts, API keys, and saves are not included. Free-form LLM chat requires the player's own service setup. Do not add third-party mod JARs or the user's audio with unverified public redistribution rights to the public source repository. See THIRD-PARTY-NOTICES.txt for sources and license boundaries, and PLAYTEST-STATUS.txt for acceptance status.

r44 hotfix: r42 incorrectly required Ad Astra 1.15.19 while bundling 1.15.21, preventing Forge from loading the mods. r44 aligns that declaration and verifies exact dependency pins during packaging. Launch and gameplay acceptance remain pending.
