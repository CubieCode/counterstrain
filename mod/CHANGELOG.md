# Remission changelog

Versions follow `mod_version` in `gradle.properties`; tags are `mod-v<version>`.

## Unreleased

- Project scaffold (P0.1).
- Entity corruption state: the `remission:corruption` attachment (Strain level 0–100 and host stage), saved with the entity and synced to tracking clients, plus the common config `hosts.stageThresholds` (P2.1). Nothing sets it in play yet.
- Debug commands `/remission infect <targets> <level>`, `/remission cleanse <targets>` and `/remission inspect <target>` (permission level 2) to set, clear and read host state (P2.3).
- Scanner (`remission:scanner`): use it on a host for a host scan or on a block for a block scan, data items whose `remission:scan_data` component records the entity type and host stage or the block (P6.1). Placeholder textures.
- Festering land groundwork (P4.1): the `remission:nidus` block (placeholder texture, no drops), a per-chunk Strain level (`remission:chunk_corruption` attachment) and a tick-budgeted spread engine that festers chunks outward from each nidus and catches unloaded land up when it loads, with a `[spread]` common config section for rates and budgets. Breaking a nidus stops its spread; nothing is visible on the land yet (P4.2).
