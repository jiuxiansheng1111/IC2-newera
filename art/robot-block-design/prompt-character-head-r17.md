# MyGO / Ave Mujica character heads R17

R17 is an isolated, editable head refinement based on the accepted R16 character
models. The task is to make the eight fringe silhouettes, eyebrow expressions,
face widths, jawlines, and side framing visibly individual while preserving the
alignment and geometry of the eyes, nose, and mouth. Small changes to face-skin
and jaw blocks are allowed to give each character a distinct face shape. Keep
head scale, full body, clothing, and accessories frozen; R16 files remain
untouched.

The forehead hair must read as a few intentional, connected Minecraft-style
locks, not a solid cap, repeated sawtooth row, or evenly spaced fence. Use
rotated cuboid locks whose rear roots sit inside the existing crown and whose
lower tips project to the forehead plane. Roots must extend forward far enough
to physically overlap both the tips and the old crown arch in three dimensions,
including after rotation. Compact bridge cubes may sit inside the old crown to
join the rotated volumes. Side and three-quarter views must show continuous hair
with no background slit through the crown-to-fringe transition. Keep the overlap
inside the hairline so the roots blend into the crown without exposed forehead
patches or repeated root/tip seams.
Preserve open space above the eyes; keep both eyebrows and both eyes readable.
The main hair swatch continues the existing hair color. Eyebrows use the
character's adjacent, slightly darker hair swatch. Each pair stays balanced
across the face but has its own length, pitch, and restrained curve.

## Character directions

- **Anon:** rose-pink side sweep with a clear part and a sloping fringe edge;
  gently rounded lower face and asymmetric side framing.
- **Tomori:** short gray-brown bob with a rounded, slightly shorter face and
  blunt, shallow fringe; avoid a long curtain.
- **Soyo:** open central part with both main locks sweeping away from the middle;
  narrower cheeks and a tapered jaw, with forehead visible.
- **Taki:** dark plum-brown nearly level fringe, narrower face and jaw, and long
  straight side fall.
- **Rana:** rounded silver cap with two separated wisps and a softly broader
  rounded face; preserve the left-gold/right-blue eyes.
- **Sakiko:** blue-gray side part swept across one side, with a narrower tapered
  jaw and longer side section; preserve the braid and black tie.
- **Uika:** neat cream-gold bob with a shallow connected fringe curve and a
  gently tapered lower face; keep the pale pink inner layer.
- **Umiri:** lifted gray-black side part, narrow face and jaw, and shoulder-length
  side framing; retain the turned-out tips.

Do not move or reshape eyes, nose, or mouth. Do not modify body proportions,
clothing, animations, or runtime resources. Do not import reference pixels or
third-party model data.
Keep actual Blockbench renders for front, right-side, and three-quarter views,
individual head closeups, a full overview, and an R16/R17 comparison. Record
source hashes and verify that the non-head geometry fingerprint is unchanged.
Visual acceptance remains with the user.

## Current review status (2026-10-22)

The current R17 geometry was reviewed and rejected by the project owner. The
right-side renders still show a thin dark slit through the crown connection,
and the three-quarter renders make several forehead locks look too thick and
plate-like, especially Tomori, Uika, and Taki. Keep this work as a draft and
retain the accepted R16 models for runtime use. The next revision should thin
the projecting fringe and make the connection inside the crown without adding
an exposed bridge block.
