# Remission changelog

Versions follow `mod_version` in `gradle.properties`; tags are `mod-v<version>`.

## Unreleased

- Project scaffold (P0.1).
- Entity corruption state: the `remission:corruption` attachment (Strain level 0–100 and host stage), saved with the entity and synced to tracking clients, plus the common config `hosts.stageThresholds` (P2.1). Nothing sets it in play yet.
- Debug commands `/remission infect <targets> <level>`, `/remission cleanse <targets>` and `/remission inspect <target>` (permission level 2) to set, clear and read host state (P2.3).
