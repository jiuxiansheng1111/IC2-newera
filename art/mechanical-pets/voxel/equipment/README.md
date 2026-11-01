# Pet equipment visual review assets

These are separate Blockbench MCP copies of the approved cat and dog models. The original `cat.bbmodel` / `dog.bbmodel` and their activity meshes were not edited. Each copy adds only a named `equipment_preview` group made from integer cuboids, with zero static rotation; the base body, head, paws, wings, and tail remain the approved voxel geometry.

The render set is for visual review of diamond-colored horse armor plus small wooden side packs. `PetEquipmentLayer.java` selects the horse-armor material at runtime and draws the same cuboid positions through the animated body, head, and limb bone matrices. Chest packs remain an independent overlay and can render together with armor. Cat packs are connected to the top plate by a center spine post and cross-yoke; they sit above the wing tips and ahead of the tail flame. Cat shin guards project one unit in front of each leg, with no side shell across the wings. Dog leg guards project out from the front/rear and outside surfaces, with the paw ends and gaps between legs clear.

`index.json` lists the exact runtime cuboid bounds and matches them to the named cuboids in each preview `.bbmodel`. Coordinates use the 16-unit pet mesh grid with integer `from` / `to` values. The preview models preserve the full base geometry and animations; only the additional preview group differs.

Rendered multi-view images are saved next to the editable sources: front, three-quarter, left, right, and back. The embedded pet texture and equipment textures are retained in each `.bbmodel`; material source PNGs are included for editing.
