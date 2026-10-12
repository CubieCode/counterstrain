// FTB Teams: teammates reclaim each other's revenants (ROADMAP P5.3).
// A slain revenant's items can be picked up only by its owner until Remission's
// [revenants] pickupTimeoutTicks frees them; Remission asks the public
// RevenantPickupEvent who else may. This handler allows players in the owner's
// FTB Teams party, so a team can recover a fallen member's gear (and earns the
// remission:revenant_reclaimed trigger and stat for killing it). Players outside
// any party are only in their own team, so strangers stay locked out.
// NeoForge event listening through NativeEvents (KubeJS drops the listener on
// /reload and registers it again). KubeJS remaps Entity#getUUID to `uuid`.

function counterstrainRevenantTeams() {
  let teams = Java.loadClass('dev.ftb.mods.ftbteams.api.FTBTeamsAPI')
  let pickupEvent = Java.loadClass('com.cubiecode.remission.api.RevenantPickupEvent')
  NativeEvents.onEvent(pickupEvent, event => {
    if (event.allowed || !teams.api().isManagerLoaded()) {
      return
    }
    if (teams.api().getManager().arePlayersInSameTeam(event.entity.uuid, event.owner)) {
      event.allowed = true
    }
  })
}

if (Platform.isLoaded('ftbteams')) {
  counterstrainRevenantTeams()
}
