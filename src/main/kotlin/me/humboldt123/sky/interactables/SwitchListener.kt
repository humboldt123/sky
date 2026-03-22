package me.humboldt123.sky.interactables

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.Keys
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.Pig
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask

class SwitchListener(private val plugin: Sky) : Listener {

    private var switchPending = false
    private var bouncePigTask: BukkitTask? = null
    // Track each bounce stand's pig by UUID so we never spawn duplicates
    private val bouncePigUUIDs = mutableMapOf<String, java.util.UUID>()

    @EventHandler
    fun onPressurePlate(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        if (!block.type.name.contains("PRESSURE_PLATE")) return

        val manager = plugin.interactableManager

        val switchBelow = block.location.clone().subtract(0.0, 1.0, 0.0)
        var matchedName: String? = null
        for ((name, data) in manager.switches) {
            if (data.location.blockX == switchBelow.blockX &&
                data.location.blockY == switchBelow.blockY &&
                data.location.blockZ == switchBelow.blockZ &&
                data.location.world == switchBelow.world) {
                matchedName = name
                break
            }
        }
        if (matchedName == null) return

        if (manager.activeSwitch == matchedName) return
        if (switchPending) return

        switchPending = true

        val playerName = event.player.name
        val displayName = matchedName.replaceFirstChar { it.uppercase() }
        val switchLoc = event.player.location

        // Instant feedback: firework SFX + particles
        event.player.world.playSound(switchLoc, org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f)
        event.player.world.spawnParticle(org.bukkit.Particle.FIREWORK, switchLoc.clone().add(0.0, 1.0, 0.0), 20, 0.5, 0.5, 0.5, 0.1)

        Bukkit.broadcast(net.kyori.adventure.text.Component.text("§e$displayName switch was activated by $playerName."))

        // Activate after 2 seconds with pling SFX
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            activateSwitch(matchedName)
            // Play pling at the switch location so nearby players hear it
            switchLoc.world?.playSound(switchLoc, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f)
            switchPending = false
        }, 40L)
    }

    fun activateSwitch(name: String) {
        val manager = plugin.interactableManager
        val newSwitch = manager.switches[name] ?: return

        // Deactivate old switch: block → obsidian, respawn pressure plate
        val oldName = manager.activeSwitch
        if (oldName != null) {
            val oldSwitch = manager.switches[oldName]
            if (oldSwitch != null) {
                oldSwitch.location.block.type = Material.OBSIDIAN
                oldSwitch.location.clone().add(0.0, 1.0, 0.0).block.type = Material.STONE_PRESSURE_PLATE
            }
        }

        // Activate new switch: restore block material, remove pressure plate
        newSwitch.location.block.type = newSwitch.material
        newSwitch.location.clone().add(0.0, 1.0, 0.0).block.type = Material.AIR

        manager.activeSwitch = name
        manager.save() // persist activeSwitch

        updateAllInteractables()
    }

    fun updateAllInteractables() {
        val manager = plugin.interactableManager

        // Jump pads
        for ((_, data) in manager.jumpPads) {
            val active = manager.isActive(data.activeSwitches)
            data.location.block.type = if (active) Material.EMERALD_BLOCK else Material.COAL_BLOCK
        }

        // Bounce stands — kill all, polling loop will respawn active ones
        killAllBouncePigs()
        startBouncePigPolling()

        // Water arcs — no block placement (particles only now)

        // Lava traps
        for ((_, data) in manager.lavaTraps) {
            val active = manager.isActive(data.activeSwitches)
            if (active) {
                data.trigger.block.type = Material.STONE_PRESSURE_PLATE
            } else {
                data.trigger.block.type = Material.AIR
                manager.forEachBlockInRegion(data.pos1, data.pos2) { loc ->
                    if (loc.block.type == Material.LAVA) loc.block.type = Material.AIR
                }
            }
        }

        // Snake blocks
        plugin.snakeBlockTask.updateAll()

        // Happy ghast
        updateGhast()
    }

    // --- Bounce Pig Polling ---

    private fun killAllBouncePigs() {
        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                if (entity is Pig && !entity.isDead &&
                    entity.persistentDataContainer.has(Keys.BOUNCE_STAND, PersistentDataType.BYTE)) {
                    entity.remove()
                }
            }
        }
        bouncePigUUIDs.clear()
    }

    fun startBouncePigPolling() {
        bouncePigTask?.cancel()
        killAllBouncePigs()
        val manager = plugin.interactableManager
        if (manager.bounceStands.isEmpty()) return

        // Check every 10 ticks (0.5s) — if a pig is dead/gone, respawn it
        bouncePigTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for ((name, data) in manager.bounceStands) {
                if (!manager.isActive(data.activeSwitches)) continue

                // Check tracked pig by UUID — only spawn if truly gone
                val existingUUID = bouncePigUUIDs[name]
                if (existingUUID != null) {
                    val existing = Bukkit.getEntity(existingUUID) as? Pig
                    if (existing != null && !existing.isDead) {
                        // Pig is alive — teleport it back to prevent drift
                        existing.teleport(data.location)
                        continue
                    }
                }
                // Pig is dead or missing — spawn a new one
                spawnBouncePig(name, data)
            }
        }, 5L, 10L)
    }

    fun stopBouncePigPolling() {
        bouncePigTask?.cancel()
        bouncePigTask = null
        killAllBouncePigs()
    }

    private fun spawnBouncePig(name: String, data: InteractableManager.BounceStandData) {
        val loc = data.location
        val world = loc.world ?: return
        val pig = world.spawnEntity(loc, EntityType.PIG) as Pig
        pig.setGravity(false)
        pig.isSilent = true
        pig.setAI(false)
        pig.isInvulnerable = false
        pig.isPersistent = true
        pig.setSaddle(true)
        pig.persistentDataContainer.set(Keys.BOUNCE_STAND, PersistentDataType.BYTE, 1)

        pig.addPotionEffect(PotionEffect(
            PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false
        ))

        pig.lootTable = null
        bouncePigUUIDs[name] = pig.uniqueId
    }

    private fun updateGhast() {
        val manager = plugin.interactableManager
        val stable = manager.ghastStable ?: return
        val active = manager.isActive(manager.ghastSwitches)
        val world = stable.world ?: return

        // Find existing managed ghast
        val existing = world.entities.firstOrNull {
            it.persistentDataContainer.has(Keys.MANAGED_GHAST, PersistentDataType.BYTE)
        }

        if (active) {
            if (existing == null) {
                try {
                    val ghastType = EntityType.valueOf("HAPPY_GHAST")
                    val ghast = world.spawnEntity(stable, ghastType)
                    ghast.persistentDataContainer.set(Keys.MANAGED_GHAST, PersistentDataType.BYTE, 1)

                    // Apply brown harness via BODY equipment slot + set flying speed
                    val livingGhast = ghast as? org.bukkit.entity.LivingEntity
                    if (livingGhast != null) {
                        try {
                            val harnessItem = ItemStack(Material.valueOf("BROWN_HARNESS"))
                            livingGhast.equipment?.setItem(EquipmentSlot.BODY, harnessItem)
                        } catch (e: Exception) {
                            plugin.logger.warning("Could not apply harness: ${e.message}")
                        }
                        try {
                            val flyingSpeed = Attribute.valueOf("FLYING_SPEED")
                            livingGhast.getAttribute(flyingSpeed)?.baseValue = 0.1
                        } catch (_: Exception) {
                            try {
                                val movementSpeed = Attribute.valueOf("GENERIC_FLYING_SPEED")
                                livingGhast.getAttribute(movementSpeed)?.baseValue = 0.1
                            } catch (e: Exception) {
                                plugin.logger.warning("Could not set flying speed: ${e.message}")
                            }
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    val ghast = world.spawnEntity(stable, EntityType.GHAST)
                    ghast.persistentDataContainer.set(Keys.MANAGED_GHAST, PersistentDataType.BYTE, 1)
                    plugin.logger.warning("HAPPY_GHAST entity type not found, using regular GHAST")
                }
            }
        } else {
            existing?.remove()
        }
    }

    fun resetSwitches() {
        val manager = plugin.interactableManager

        // Restore all switch blocks to their configured material + plate
        for ((_, data) in manager.switches) {
            data.location.block.type = data.material
            data.location.clone().add(0.0, 1.0, 0.0).block.type = Material.STONE_PRESSURE_PLATE
        }

        manager.activeSwitch = null
        updateAllInteractables()
    }
}
