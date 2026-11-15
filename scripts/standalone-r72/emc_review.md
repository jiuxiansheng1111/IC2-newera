# r72 ProjectE runtime audit and cycle closures

## Evidence

The reviewed real-price export is `build-isolated/player-packaging/20261114-r72-standalone/W2/emc-runtime/pass2-record.json`, SHA-256 `CE06BA0161828F6EC8553FF4120EE6908FC8E0BBF8D564E5BAC29F4DC576CAD0` (2,507 item values). The paired `latest.log` SHA-256 is `BDBED8194AF3E060DC639572C92BE27B94AA2316F8B6DC69CB0C4276C24E79A9`. The values below were derived from this real export and the active Forge 47.4.28 recipe/tag data; they are not the earlier draft assumptions. ProjectE is `projecte-1201-PE1.0.1.jar`, SHA-256 `F51F387E56307E7CDA26915CA0C789760D85E97A8B40B8BFA0988BE66371D018`.

## Specific runtime findings and changes

| Target | Runtime observation / recipe arithmetic | Resulting clamp |
|---|---|---:|
| `forge:ingots/iron`, gold, copper | Runtime candidates were 256, 2048, 128; reviewed 9 nuggets = one ingot and 1:2 iron/copper exchange paths | 252, 2016, 126 |
| `forge:nuggets/gold`, iron, copper | Gold nugget must be 224 because 9×224=2016; iron/copper are 28/14 | 224, 28, 14 |
| `forge:gems/diamond`, emerald | Diamond tag candidate 8192 exceeded 4 gold ingots (4×2016=8064); emerald is 2 diamonds | 8064, 16128 |
| `forge:gems/quartz` | Runtime tag candidate 256 is too high; quartz item remains 94 | 94 |
| Quartz blocks/cutting | 4 quartz→block: 376; pillar/brick/chiseled/stair paths are 1:1 at 376; slab=188; smooth quartz remains 376 | no additional item overrides |
| Heart of the Sea | 1 eye (1,520)+4 shards (4×256)+4 shells (4×1,024)=6,640 | 6,640 |
| Trident | Actual reviewed inputs: heart of the sea 6,640 + nautilus shell 1,024 + prismarine shard 256 + 2 blaze powder 2×512 = 8,944. The previous runtime output was 16,398. | 8,944 |
| Netherite ingot | The previous runtime mapping yielded ingot 57,204 and block 514,836. The actual recipe is 2 gold +2 scrap: 2×2,016 +2×12,285=28,602; block=9×28,602 | 28,602; block 257,418 |
| Sentry armor trim template | Duplication consumes 7 diamonds +1 cobblestone =7×8,064+1=56,449; runtime template was 57,345; two outputs would yield 1,792 EMC profit | 56,449 |
| Soul soil and soul sand | Create haunting is dirt 1→soul soil 1; keep soul soil=1. Soul sand remains 49. Root separately changes only Avaritia's 4-output soul sand recipe to 2 soul soil+2 sand+1 quartz→2 soul sand (98 cost / 98 output). | soil 1; sand 49 |
| Andesite crafting | Vanilla 1.20.1 recipe uses 1 cobblestone (1)+1 diorite (16)=17 and outputs 2 andesite (16 each; 32 total). Agreed minimal fix: change only this recipe result count from 2 to 1; output EMC becomes 16, leaving the existing andesite/derivative prices unchanged. | No EMC clamp; recipe output count 1 |
| Create andesite alloy | Mixer cost is 1 andesite (16)+1 iron nugget (28)=44; current alloy EMC was 88. Clamp alloy to44. Storage block is 9 alloys→396; clamp block to396 so both directions remain neutral. Keep all Create recipes unchanged. | alloy44; block396 |
| Andesite derivatives | Vanilla polished-andesite/stairs/wall and Create stonecutting singles are 1:1 at 16; slab recipes output 2×8=16. They are neutral once andesite crafting is 1→1. Keep polished, stair, wall, slab and Create paving EMC unchanged. | unchanged |
| Avaritia music disc 5 | Re-Avaritia overrides the vanilla same-ID recipe. Active output is 1 disc from 4 `avaritia:record_fragment` (4×1,024)+1 `minecraft:echo_shard` (16)=4,112, while the disc is 2,048. Reverse source recipe consumes a disc (2,048)+4 neutron piles (4×512)=4,096 to make four fragments (4×1,024)=4,096. The vanilla 9×`disc_fragment_5` recipe is not active. | no price change |

The other 16 armor trim templates were checked against their actual one-run duplication input costs; none exceeded its input cost. The Create mixer route is clamped to its actual input cost: andesite16 + iron nugget28 = alloy44. The alloy storage block is fixed at 396 for the neutral 9:1 conversion; no Create recipes were changed. The 1.20.1 copper nugget registry has no `minecraft:copper_nugget`: `create:copper_nugget` and `tconstruct:copper_nugget` are the two real members, both valued at 14 by `#forge:nuggets/copper`. The 13 EMC warnings and the earlier 48 ProcessGraph entries were not treated as evidence of a profitable loop without an actual recipe and quantity check.

## ProjectE after-table resource

`build-isolated/migration-1201/newera/src/main/resources/data/ic2newera/pe_custom_conversions/r72_runtime_cycle_closures.json` clamps the actual Forge ingot, nugget, gem, dust and plate tags, and explicitly fixes outputs that could otherwise retain stale mapped EMC. The existing `ingots.json` has matching iron/gold/copper/netherite ingot and block values; this avoids depending on datapack iteration order. The runtime resource is included in the first-party JAR receipt and is verified byte-for-byte by `package.py`.

The quartz washing expectation is preserved: 0.5×94 +0.02×224 =51.48 EMC expected byproduct value against soul sand 49, a 5.06% yield. Do not change quartz, gold nugget, soul sand, Create haunting, or any washing recipe. The controlled 71 EMC=0 intermediates and all unrelated custom entries remain intact.

## Shared config and review boundary

`emc_balance.py` merges the 47 runtime-reviewed item overrides into the frozen r70 72-entry config, retaining the 71 zero values and quartz=94. The copper nugget override targets the registered `create:copper_nugget`; the Forge tag also clamps `tconstruct:copper_nugget` to 14. The final full config has 118 entries (72 baseline plus 46 additions) and SHA-256 `5EC5828F5A6AC2A560E4F4661E5601A44D32A78869DE7A743D2A52F3B1557BCF`. The normal PCL client and both distributed archives were verified byte-for-byte. The after-table digest is `3CEA1738B3621BFCDD024936D1DC4DA10A03470B9EBE9DAC3BD5195BD8C9890E`. The reviewed Create jar is `create-1201-6.0.8.jar`, SHA-256 `6FBB910C367DBCE8E4FC7E5BF64B6EDD4DE980906ED00AF8E47E4AF843C0D9B0`. The stage manifest records these inputs.

After integration, a fresh normal Forge singleplayer session exported 2,506 prices (SHA-256 `B3CBCFF3B6AB6F4CE27FE1D5FCF69AEB4CEC953AD3ACE4A96305FBAF76EBACEF`). All 47 reviewed values match; the nonexistent vanilla copper nugget is absent. The retained washing expectation is 51.48/49. None of 2,243 unconditional, fully priced ordinary crafting, Avaritia shaped-table and stonecutting recipes inspected produced more total EMC than its priced ingredients. This does not cover every machine, NBT-dependent recipe, reclaimed container or chance outcome. See the [runtime record](../../docs/r72-runtime-compatibility-2026-11-16.md) for in-game observations and limitations.
