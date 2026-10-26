# Furniture mirror assets

Editable models were authored through the connected Blockbench MCP model tools and rendered with the Blockbench MCP headless renderer. The `.bbmodel` sources contain only cuboids with integer coordinates, no meshes, no bevels, and zero static rotations. Canonical front is north (`-Z`); the visible mirror face is always at local `z=7`.

## Files

- `floor_mirror.bbmodel`: one full 32-unit tall assembly; the resource export splits its lower and upper halves into separate 0–16-unit block models.
- `dressing_mirror.bbmodel`: one-block tabletop mirror with drawers, legs, a simple pixel pane, and brass trim.
- `generate_models.py`: repeats the JSON and texture export from the embedded Blockbench source geometry and textures into the isolated 1.20.1 resources.
- `previews/`: saved front, back, side, top, and three-quarter MCP renders.
- The four material PNGs: embedded Blockbench source textures and pixel palette.

The pane texture is intentionally a static pale blue-gray pixel surface. The runtime block renderer supplies the moving reflection. Frame colors use cream oak, warm wood, and restrained brass; the vanity is visually wider and more furniture-like than the narrow floor mirror.

## Geometry and calibration

All dimensions are in the 16-unit Minecraft block grid. Floor mirror: pane `x=3..13`, `z=7..8`, lower `y=4..16`, upper `y=16..30`; frame rails span `x=2..14`, `y=3..31`; feet occupy `y=0..3`. Dressing mirror: pane `x=4..12`, `y=9..15`, `z=7..8`; frame `x=3..13`, `y=8..16`; tabletop `x=1..15`, `y=0..7`, `z=4..13`. These project-authored integer block coordinates are the calibration source for these new furniture models.

Resources are exported to `build-isolated/migration-1201/newera/src/main/resources/assets/ic2newera/blockstates/`, `models/block/`, `models/item/`, and `textures/block/`, plus `data/ic2newera/loot_tables/blocks/`. The lower half alone drops the two-block floor mirror item so breaking both halves does not duplicate it.
