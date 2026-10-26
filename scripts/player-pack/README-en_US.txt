IC2: New Era 1.20.1 — Player Playtest Pack r43

Status
r43 targets Minecraft 1.20.1 / Forge 47.4.28 / Java 17. The final build and packaged assets were checked, and r43 was installed into the development machine's isolated 1.20.1 profile. It has not been launched or playtested in game. Older r40/r41 acceptance results do not verify r43. Filename: IC2-New-Era-1.20.1-Forge-47.4.28-r43.mrpack.

Import and launch
Import the .mrpack into PCL2 as a separate Modrinth profile. If drag-and-drop is unavailable, select it through the modpack-install entry. Portable Java 17 and its matching source are bundled; the launcher downloads 15 locked dependencies from their authors. If necessary, select IC2-NewEra-runtime/java17/bin/javaw.exe inside the new profile. Use a new 1.20.1 world.

Included pet features
- Create 6.0.8, Create: Meowchanics 2.3.1, Create: Biotech 1.3.0, and Ad Astra 1.15.21. The pack contains New Era plus 15 third-party runtime mods; the final mrpack manifest is authoritative for dependency details.
- Mechanical pets use voxel models with four separated legs and smooth motions. Hunger starts at 20 and drops by one every two loaded minutes; zero hunger causes damage and can kill. Feed fish to the cat, meat or ordinary cat cake to the dog; an owned tame wolf can also be healed with ordinary cake. Mechanical cat pancakes are never food. Cat/dog play is non-lethal.
- The mechanical dog panel can play the full 10-minute 22.714-second user-supplied audio. Clicking again or interrupting the action stops it. This pet sound ships with the New Era pack and does not depend on the optional Voice-Bundle.
- An anvil or Create roller flattens the mechanical cat. Its owner can pick it up with a shovel, then drop its saved pancake into real Create fan airflow to restore that same cat. This interaction still needs in-game r43 acceptance.

Optional robot AI voice
The existing `IC2-NewEra-Voice-Bundle-Win64-2026.10.24.zip` is preserved unchanged and remains separate for the existing robot AI voice environment. r43 source changes do not add new installation, inference, or voice-effect verification.
1. Import the ordinary mrpack and close Minecraft normally.
2. Download and extract the outer Voice-Bundle ZIP. Its folder already contains `Install.cmd` and the unchanged inner Voice-Slim ZIP; no separate tool download or manual pairing is needed.
3. Run `Install.cmd` and select the newly imported 1.20.1 / Forge 47.4.28 profile directory containing its matching version JSON. Do not select mods, saves, or the shared .minecraft directory. The installer targets a fresh separate profile and refuses to overwrite an existing custom voice folder.
4. Voice inference requires an NVIDIA GPU/driver compatible with CUDA 12.8 and at least 16 GB RAM is suggested. Allow about 11 GB unpacked space plus temporary space; the archive exceeds 4 GB and needs ZIP64 support. For multiplayer, install voice on the server host; audio uses the existing game connection and the backend listens only on loopback.
Choose Chinese, Japanese, or English voice on the robot Settings page. Voice generation, installation, and inference on a new machine remain pending acceptance. Ordinary text play does not need this attachment; robot AI voice stays off by default. The pet dog call is a separate New Era sound and does not require Voice-Bundle.

Scope and notes
Minecraft, accounts, API keys, and saves are not included. Free-form LLM chat requires the player's own service setup. Do not add third-party mod JARs or the user's audio with unverified public redistribution rights to the public source repository. See THIRD-PARTY-NOTICES.txt for sources and license boundaries, and PLAYTEST-STATUS.txt for acceptance status.

r43 hotfix: r42 incorrectly required Ad Astra 1.15.19 while bundling 1.15.21, preventing Forge from loading the mods. r43 aligns that declaration and verifies exact dependency pins during packaging. Launch and gameplay acceptance remain pending.
