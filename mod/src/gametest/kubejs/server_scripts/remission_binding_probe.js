// Gametest fixture for KubeJSBindingTests (P8.3); never part of the pack. build.gradle syncs it, with the pack's
// server_scripts/remission/ samples, into run/gametest/kubejs/server_scripts/ before runGameTestServer.
// Counts every RemissionEvents call in the server's persistent data and acts on the probe values the tests post.

const PROBE = 'remission_probe:'
const PROBE_PLAYER = '00000000-0000-0000-0000-00000000a5a5'

function seen(server, name) {
  const data = server.persistentData
  data.putInt(PROBE + name, data.getInt(PROBE + name) + 1)
}

RemissionEvents.hostCorrupted(event => {
  seen(event.server, 'hostCorrupted')
  // Bound queries answer from scripts.
  event.server.persistentData.putBoolean(PROBE + 'isHost', CorruptionQueries.isHost(event.entity))
})
RemissionEvents.hostCleansed(event => seen(event.server, 'hostCleansed'))
RemissionEvents.blockFestered(event => seen(event.server, 'blockFestered'))
RemissionEvents.blockPurified(event => seen(event.server, 'blockPurified'))
RemissionEvents.chunkStageChanged(event => seen(event.server, 'chunkStageChanged'))
RemissionEvents.chunkEnteredRemission(event => seen(event.server, 'chunkEnteredRemission'))
RemissionEvents.strainSpreadAttempt(event => {
  seen(event.server, 'strainSpreadAttempt')
  if (event.to.x == 30000) {
    event.cancel()
  }
  if (event.to.x == 30001) {
    event.rateMultiplier = event.rateMultiplier * 0.25
  }
})
RemissionEvents.nidusDamaged(event => {
  seen(event.server, 'nidusDamaged')
  if (event.amount == 13) {
    event.cancel()
  }
  if (event.amount == 7) {
    event.amount = event.amount * 2
  }
})
RemissionEvents.nidusDestroyed(event => {
  seen(event.server, 'nidusDestroyed')
  // The pack sample (nidus_firebreak.js, loaded first) makes a firebreak wherever players destroy a nidus, which would
  // change spread in other tests' structures nearby. Keep only the ones KubeJSBindingTests makes (chunk z 40000).
  const chunkZ = event.pos.z >> 4
  if (chunkZ != 40000) {
    event.server.persistentData.remove('counterstrain:nidus_firebreak ' + event.level.dimension + ' ' + (event.pos.x >> 4)
      + ' ' + chunkZ)
  }
})
RemissionEvents.surgeStarted(event => seen(event.server, 'surgeStarted'))
RemissionEvents.surgeEnded(event => seen(event.server, 'surgeEnded'))
RemissionEvents.revenantRisen(event => seen(event.server, 'revenantRisen'))
RemissionEvents.revenantSlain(event => seen(event.server, 'revenantSlain'))
RemissionEvents.viralLoadChanged(event => seen(event.server, 'viralLoadChanged'))
RemissionEvents.strainHazard('remission:kubejs_probe', event => {
  seen(event.server, 'strainHazard')
  event.cancel()
})
RemissionEvents.countermeasureGranted('remission:kubejs_probe', event => seen(event.server, 'countermeasureGranted'))
RemissionEvents.teamLookup(event => {
  seen(event.server, 'teamLookup')
  if (String(event.first) == PROBE_PLAYER || String(event.second) == PROBE_PLAYER) {
    event.setTeammates(true)
  }
})
