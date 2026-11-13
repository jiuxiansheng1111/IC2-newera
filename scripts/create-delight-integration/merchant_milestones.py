"""Map removed Lightman's Currency quest gates to retained New Era milestones."""
from __future__ import annotations

import re


REL = "kubejs/server_scripts/Lightmans Currency/white_list.js"

# Legacy quest ID -> retained milestone. All targets exist in the current retained
# joint_*/newera_*.snbt set. Keeping the original item arrays and login reconciliation
# means already-whitelisted goods stay whitelisted, while new players gain gates from
# quests that remain available after Economic_Playground is retired.
MILESTONE_MAP = {
    "7D54AF3A775B6E9E": "694AF0999C748BB0",  # andesite alloy -> andesite-alloy assembly
    "7A608C48C3947975": "694A25B2E2854006",  # brass -> brass routing
    "027EEF0721C497D3": "694ACC982105F2AE",  # steel -> first steel batch
    "3A1374A5FCBE5558": "694A0E58A046FC77",  # electron tube -> first circuit board
    "2C75BC13E58E3293": "694A2C229B3F96F2",  # precision mechanism -> precision components
    "7794F6910AF7591C": "694AAF4654AF3CCE",  # bronze -> refined copper powder
    "02FD263E4D2BFD82": "694AF2BA356DFB36",  # obdurium -> sustained endgame production
    "256EC76BA5595E4C": "694AF2DBD4AB749A",  # void steel -> launch preparation
    "52CAEFBED4952D92": "694AE2D769B235EB",  # electrum -> low-voltage distribution
    "50B7170A5AAEBA8A": "694A986CDA17EB39",  # logic processor -> processor sampling
    "1219B75C2EB5BDD3": "694A955C4AB67A8E",  # calculation processor -> processor sampling
    "555915045BD9EEC0": "694A533E2E909061",  # accumulation processor -> 1K core
    "5B0583F6E1474359": "694A05812ED58418",  # engineering processor -> processor sampling
    "7781E14E3569EDC6": "694AA5F5F9CB00D6",  # certus quartz -> certus sampling
    "611F3C0D64351D0F": "694A6FD50A6391D8",  # fluix -> fluix batch
    "6098CD2DFB98E121": "694A9B35D2D2AADD",  # annihilation core -> network crafting terminal
    "3042E8CAEFF3F7CE": "694A9B35D2D2AADD",  # formation core -> network crafting terminal
    "4FCBBE103FE7E9C7": "694A61EE95FD848D",  # titanium -> titanium sample
    "057F6F421273C4DC": "694A94B5849A4AE4",  # martian steel -> martian steel delivery
    "63356DE1A41FB7E0": "694A245A794E20C8",  # common resources -> crush ore
    "441F72B76AC3D7AC": "694A938185461A99",  # precious resources -> centrifuge output
    "36114A8D5283E6E5": "694AB78E7CA33361",  # tungsten/debris -> tungsten ingot shipment
}


def remap_merchant_milestones(source: bytes) -> bytes:
    """Return `white_list.js` with only the 22 legacy gate IDs remapped.

    This one-argument callable is pure: it performs no filesystem access. Other JS
    content, including the item grants, event hooks, and login reconciliation, stays
    byte-for-byte intact. The caller should apply its result only at ``REL``.
    """
    encoding = "utf-8-sig" if source.startswith(b"\xef\xbb\xbf") else "utf-8"
    text = source.decode(encoding)
    for old, new in MILESTONE_MAP.items():
        if not re.fullmatch(r"[0-9A-F]{16}", old) or not re.fullmatch(r"[0-9A-F]{16}", new):
            raise ValueError("Malformed quest ID mapping")
        if text.count(f'"{old}"') != 1:
            raise ValueError(f"Expected one legacy gate ID in {REL}: {old}")
    for old, new in MILESTONE_MAP.items():
        text = text.replace(f'"{old}"', f'"{new}"')
    return text.encode(encoding)
