// A destroyed nidus leaves a firebreak: the worked sample of Remission's KubeJS event bindings (P8.3).
// When players fight a nidus down, the Strain cannot spread into the chunk it stood in for FIREBREAK_TICKS, and spreads
// into the eight chunks around it at NEIGHBOUR_RATE. Uses RemissionEvents.nidusDestroyed (react) and
// RemissionEvents.strainSpreadAttempt (cancel and rate multiplier); the full binding list is in the
// remission-data-formats skill, "KubeJS bindings". Firebreaks live in the server's persistent data, so they survive a
// restart. Gate: none; the numbers are the pack's to tune.

const FIREBREAK_TICKS = 12000 // 10 minutes
const NEIGHBOUR_RATE = 0.5
const FIREBREAK_PREFIX = 'counterstrain:nidus_firebreak '

function firebreakKey(level, chunkX, chunkZ) {
  return FIREBREAK_PREFIX + level.dimension + ' ' + chunkX + ' ' + chunkZ
}

// Whether a firebreak holds at a chunk; forgets it once it has expired.
function firebreakHolds(level, chunkX, chunkZ) {
  const data = level.server.persistentData
  const key = firebreakKey(level, chunkX, chunkZ)
  const until = data.getLong(key)
  if (until == 0) {
    return false
  }
  if (level.time >= until) {
    data.remove(key)
    return false
  }
  return true
}

RemissionEvents.nidusDestroyed(event => {
  // Only a nidus fought down by players; one removed by a command or another mod leaves no firebreak.
  if (event.player == null) {
    return
  }
  const level = event.level
  const chunkX = event.pos.x >> 4
  const chunkZ = event.pos.z >> 4
  // level.time is the level's game time (KubeJS remaps Level.getGameTime()).
  const until = level.time + FIREBREAK_TICKS
  level.server.persistentData.putLong(firebreakKey(level, chunkX, chunkZ), until)
  console.info('Nidus destroyed at ' + event.pos.toShortString() + ' by ' + event.player.username + ' and '
    + (event.contributors.size() - 1) + ' other player(s): firebreak at chunk ' + chunkX + ', ' + chunkZ + ' until game time ' + until)
})

RemissionEvents.strainSpreadAttempt(event => {
  const level = event.level
  const to = event.to
  if (firebreakHolds(level, to.x, to.z)) {
    event.cancel()
  }
  for (let dx = -1; dx <= 1; dx++) {
    for (let dz = -1; dz <= 1; dz++) {
      if ((dx != 0 || dz != 0) && firebreakHolds(level, to.x + dx, to.z + dz)) {
        event.rateMultiplier = event.rateMultiplier * NEIGHBOUR_RATE
        return
      }
    }
  }
})
