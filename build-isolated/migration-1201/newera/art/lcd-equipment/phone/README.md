# Industrial phone asset source

`generate_phone.py` is the reproducible source for the active Minecraft Java item model and its two PNG atlases. The geometry uses clipped stepped corners and thin cuboids, with a brushed-metal rail, separate LCD and rear glass, two stepped rear lenses, side keys, and a bottom port/speaker row.

The modeled body is 8.3 × 15.5 × 1.2 model units, preserving the roughly 1:2 width-to-height proportion of the cited 150.9 × 75.7 × 8.3 mm reference while keeping the design unbranded. The 256 × 512 LCD is a static launcher thumbnail composed from the actual wallpaper crop `[19,20,365,650]`, the eight actual 96 × 96 app icons, and the clock glyph sprites. It keeps the launcher’s 3×2 grid and two-icon dock, with a complete `09:41` clock and no substitute/cropped labels. Its Minecraft face UV spans `[0,0,16,16]`; atlas subregions are normalized to each active PNG’s dimensions. The LCD face emits block and sky light at 12 and disables ambient occlusion. Display transforms rotate GUI/fixed/first-person views 180° around Y, presenting the screen toward the camera; both hand transforms match.

`industrial_phone.bbmodel` is an editable Blockbench Java Block copy of the exact 27 cuboids and UV faces produced by the generator. It has per-texture UV dimensions set to 64 × 96 for the metal/back atlas and 256 × 512 for the LCD. `preview_front.png`, `preview_back.png`, `preview_left.png`, `preview_right.png`, `preview_top.png`, and `preview_three-quarter.png` are orthographic Blockbench renders of that geometry.

Active game assets are under `src/main/resources/assets/ic2newera/`: `models/item/industrial_phone.json`, `textures/item/industrial_phone.png`, and `textures/item/industrial_phone_screen.png`.
