# Voxel dog source

`dog.bbmodel` is the editable Blockbench Free model used as the mechanical dog source. The activity-ready direction is the vanilla Minecraft cuboid style; the older organic prototype is not used.

- Rig: `body` root; `head` under body; `jaw`, `ear_l`, `ear_r` under head; `tail` and `leg_fl`, `leg_fr`, `leg_bl`, `leg_br` under body.
- Geometry: 20 positive-thickness cubes, no meshes; integer bounds X `-4..4`, Y `0..13`, Z `-9..7`; face points toward `-Z`. Static rotations are zero. The two front paw blocks leave a 2-unit lateral gap; the front paw Z range is -5..-2 and rear paw range is 5..7, leaving 7 units between their inner edges. The torso is a 6×5×10 cuboid (Z -4..6); the front leg pivots and both front leg/paw cuboids were shifted forward by 1 unit to keep the extended torso proportions and leg separation.
- Texture: original embedded 64×64 pixel atlas, also provided as `dog-atlas.png`. Run `python create_voxel_dog_atlas.py` to regenerate the PNG and update the embedded texture in the sibling `dog.bbmodel`.
- `blockbench-operations.json` records the editable MCP hierarchy and geometry operations, plus the `bark` jaw keyframes.
- Animation: `bark` opens the lower jaw around its integer pivot.
- `dog-3q.png`, `dog-front.png`, `dog-side.png`, and `dog-bark-side.png` are actual Blockbench MCP renders of the saved model. `item-mechanical_dog.png` is an actual transparent 128×128 icon render.
- `reference-notes.json` records calibration against the local 1.20.1 WolfModel and the style reference. No vanilla wolf texture was copied.

These files are source assets only; runtime export/integration is handled separately.
