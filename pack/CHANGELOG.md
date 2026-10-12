# Counterstrain changelog

Versions follow `version` in `pack.toml`; tags are `pack-v<version>`.

## Unreleased

- Faster Strain: Remission config `config/remission-common.toml` spreads about four times faster than Remission's defaults (a pass every 10 s, spread step 4: about 11 minutes from a new nidus to its full 7-chunk reach, against 43), and mobs become hosts about a second after entering festering land (minimum stage 1). The fast infection is a stopgap until contact infection (P4.8).
- JourneyMap as the map and minimap (replaces Xaero's Minimap).
- EMI as the recipe viewer, alongside JEI for plugin compatibility.
- Project scaffold (P0.1): companion mods, empty KubeJS, FTB Quests and datapack folders.
