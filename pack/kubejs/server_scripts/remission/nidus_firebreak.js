// A destroyed nidus leaves a firebreak: the worked sample of Remission's KubeJS event bindings (P8.3).
// When players fight a nidus down, the Strain cannot spread into the chunk it stood in for FIREBREAK_TICKS, and spreads
// into the eight chunks around it at NEIGHBOUR_RATE. Uses RemissionEvents.nidusDestroyed (react) and
// RemissionEvents.strainSpreadAttempt (cancel and rate multiplier); the full binding list is in the
// remission-data-formats skill, "KubeJS event bindings". Firebreaks live in one compound in the server's persistent
// data, so they survive a restart; expired ones are swept on every new firebreak and every SWEEP_TICKS, and the
// compound is dropped once empty, so spread attempts cost one lookup while no firebreak is active.
// Gate: none; the numbers are the pack's to tune.

const FIREBREAK_TICKS = 12000 // 10 minutes
const NEIGHBOUR_RATE = 0.5
const SWEEP_TICKS = 6000 // 5 minutes
// Compound of "<dimension> <chunk x> <chunk z>" to the game time the firebreak ends.
const FIREBREAKS = 'counterstrain:nidus_firebreaks'

// Game time, shared by every dimension. KubeJS exposes Level.getGameTime() as `time` (getGameTime() is not callable).
function gameTime(server) {
  return server.overworld().time
}

// level.dimension is the dimension ID in KubeJS.
function firebreakKey(level, chunkX, chunkZ) {
  return level.dimension + ' ' + chunkX + ' ' + chunkZ
}

// Drops expired firebreaks, and the compound once it is empty.
function sweepFirebreaks(server) {
  const data = server.persistentData
  if (!data.contains(FIREBREAKS)) {
    return
  }
  const firebreaks = data.getCompound(FIREBREAKS)
  const now = gameTime(server)
  const keys = firebreaks.getAllKeys().toArray()
  for (let i = 0; i < keys.length; i++) {
    if (firebreaks.getLong(keys[i]) <= now) {
      firebreaks.remove(keys[i])
    }
  }
  if (firebreaks.isEmpty()) {
    data.remove(FIREBREAKS)
  }
}

RemissionEvents.nidusDestroyed(event => {
  // Only a nidus fought down by players; one removed by a command or another mod leaves no firebreak.
  if (event.player == null) {
    return
  }
  const server = event.server
  sweepFirebreaks(server)
  const data = server.persistentData
  if (!data.contains(FIREBREAKS)) {
    // getCompound returns a new, detached compound when the key is missing.
    data.put(FIREBREAKS, data.getCompound(FIREBREAKS))
  }
  const chunkX = event.pos.x >> 4
  const chunkZ = event.pos.z >> 4
  const until = gameTime(server) + FIREBREAK_TICKS
  data.getCompound(FIREBREAKS).putLong(firebreakKey(event.level, chunkX, chunkZ), until)
  console.info('Nidus destroyed at ' + event.pos.toShortString() + ' by ' + event.player.username + ' and '
    + (event.contributors.size() - 1) + ' other player(s): firebreak at chunk ' + chunkX + ', ' + chunkZ
    + ' until game time ' + until)
})

ServerEvents.tick(event => {
  if (event.server.tickCount % SWEEP_TICKS == 0) {
    sweepFirebreaks(event.server)
  }
})

RemissionEvents.strainSpreadAttempt(event => {
  const data = event.server.persistentData
  // No active firebreak anywhere: nothing to check.
  if (!data.contains(FIREBREAKS)) {
    return
  }
  const firebreaks = data.getCompound(FIREBREAKS)
  const level = event.level
  const now = gameTime(event.server)
  const to = event.to
  const holds = (chunkX, chunkZ) => firebreaks.getLong(firebreakKey(level, chunkX, chunkZ)) > now
  if (holds(to.x, to.z)) {
    event.cancel()
  }
  for (let dx = -1; dx <= 1; dx++) {
    for (let dz = -1; dz <= 1; dz++) {
      if ((dx != 0 || dz != 0) && holds(to.x + dx, to.z + dz)) {
        event.setRateMultiplier(event.rateMultiplier * NEIGHBOUR_RATE)
        return
      }
    }
  }
})
