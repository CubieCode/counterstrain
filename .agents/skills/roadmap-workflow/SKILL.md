---
name: roadmap-workflow
description: Pick up, scope, deliver and close one Counterstrain/Remission ROADMAP.md item, keeping its status, owner, PR and exit evidence current. Use for an explicit implementation assignment.
---

# One roadmap item

Read AGENTS.md, the item in ROADMAP.md, its phase goals and exit criteria, docs/DESIGN.md and docs/ARCHITECTURE.md. Scaffold, docs or "look into" requests do not authorise implementation. Confirm every `Depends on` item is `done` on current main and the item has no owner or open PR (`gh pr list --search "<ID>"`). If not, report and stop.

Claim: branch `feat/<id-lower>-<slug>` from main (e.g. `feat/p2.1-corruption-attachment`), set Status `in-progress` and Owner to the branch in your first commit. Change only your item's fields; never edit another item's status. Follow the item's Skills list plus any specialised skill the change touches (neoforge-mod-dev for Java, remission-data-formats for contracts, visual-checks for anything rendered).

Scope: do exactly the Scope line. If acceptance needs a design decision the docs do not settle (a schema shape, sync mechanism, balance default), write the options in the PR and ask; continue independent parts. Split oversized work by proposing new items (next free `P<phase>.<n>`) in a separate `Roadmap: ...` PR, rather than widening yours. Mod and pack changes for one item may share a PR only if the item says so.

Evidence: run `./gradlew build runData runGameTestServer` in `mod/` (no generated drift), `tools/pack.sh check` if `pack/` changed, `git diff --check`. Items with visual exit criteria need the named harness scenes or, before P1.1, a documented manual scene: GPU-verified run, screenshots collected and reviewed (visual-checks). Record measured numbers (tick cost, FPS) in the item when asked.

Close: set Status `review`, commit `<ID>: <result>`, push, `gh pr create --title "<ID>: <title>" --body-file <tmp>` with scope, checks run, evidence (screenshot paths or artifacts), open decisions. Record the PR URL in the item. After an authorised merge, a small follow-up commit `<ID>: Mark accepted <topic> work complete` sets `done` and adds a one-line `- Validation:` note under the item. Update phase exit criteria text only when the whole phase is accepted. Never mark done before merge, never skip a gate silently, and do not start the next item unless assigned.
