// Remission machine recipes from KubeJS: a sample (P7.3), not part of the pack.
// Adds, removes and replaces recipes of each Remission recipe type through the schemas Remission ships in
// data/remission/kubejs/recipe_schema/, then logs what the recipe manager holds after the reload.
// Use: copy into a dev run's kubejs/server_scripts/ (mod/run/server or mod/run/client), start the game or /reload,
// and read logs/kubejs/server.log for the "[remission sample]" lines.

ServerEvents.recipes(event => {
  // remission:analyzing
  // Add: rotten flesh into a little miasma (no item result).
  event.recipes.remission.analyzing([], ['minecraft:rotten_flesh'])
    .fluidResult(Fluid.of('remission:miasma', 25))
    .energy(1000)
    .time(60)
    .id('remission_sample:analyzing/rotten_flesh')
  // Remove: a default by id.
  event.remove({ id: 'remission:analyzing/host_tissue' })
  // Replace: every analyzing recipe that takes festered soil takes coarse dirt instead.
  event.replaceInput({ type: 'remission:analyzing' }, 'remission:festered_soil', 'minecraft:coarse_dirt')

  // remission:sequencing
  // Add: two scans of any kind and host tissue, in more miasma, make two strain sequences.
  event.recipes.remission.sequencing(['2x remission:strain_sequence'], ['2x #remission:scans', 'remission:host_tissue'])
    .fluidIngredient(Fluid.of('remission:miasma', 500))
    .energy(10000)
    .time(300)
    .id('remission_sample:sequencing/double_sequence')
  // Replace: the default strain sequence recipe gives a block scan back as a second result.
  event.remove({ id: 'remission:sequencing/strain_sequence' })
  event.recipes.remission.sequencing(['remission:strain_sequence', 'remission:block_scan'], ['#remission:scans', 'remission:host_tissue'])
    .fluidIngredient(Fluid.of('remission:miasma', 250))
    .energy(6000)
    .time(200)
    .id('remission_sample:sequencing/strain_sequence')

  // remission:synthesizing
  // Remove: every synthesizing recipe that makes host tissue.
  event.remove({ type: 'remission:synthesizing', output: 'remission:host_tissue' })
  // Replace: serums use honey bottles instead of glass bottles.
  event.replaceInput({ id: 'remission:synthesizing/serum' }, 'minecraft:glass_bottle', 'minecraft:honey_bottle')
  // Add: a cheaper prototype from festered soil, for testing.
  event.recipes.remission.synthesizing(['remission:counterstrain_prototype'], ['remission:strain_sequence', '4x remission:festered_soil'])
    .fluidIngredient(Fluid.of('remission:miasma', 500))
    .energy(8000)
    .time(200)
    .id('remission_sample:synthesizing/prototype_from_soil')
})

ServerEvents.loaded(event => {
  const Recipe = Java.loadClass('net.minecraft.world.item.crafting.Recipe')
  const JsonOps = Java.loadClass('com.mojang.serialization.JsonOps')
  const ops = event.server.registryAccess().createSerializationContext(JsonOps.INSTANCE)
  const ids = [
    'remission_sample:analyzing/rotten_flesh',
    'remission:analyzing/host_tissue',
    'remission:analyzing/festered_soil',
    'remission_sample:sequencing/double_sequence',
    'remission:sequencing/strain_sequence',
    'remission_sample:sequencing/strain_sequence',
    'remission:synthesizing/host_tissue',
    'remission:synthesizing/serum',
    'remission_sample:synthesizing/prototype_from_soil'
  ]
  ids.forEach(id => {
    const holder = event.server.recipeManager.byKey(id).orElse(null)
    const json = holder == null ? 'absent' : Recipe.CODEC.encodeStart(ops, holder.value()).getOrThrow()
    console.info(`[remission sample] ${id}: ${json}`)
  })
})
