package me.bigratenthusiast.crypt.interactables

import me.bigratenthusiast.crypt.Crypt
import me.bigratenthusiast.crypt.util.Keys
import me.bigratenthusiast.crypt.util.alivePlayers
import me.bigratenthusiast.crypt.util.sendActionBarMsg
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Fireball
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID

class InteractableListener(private val plugin: Crypt) : Listener {

    private val im get() = plugin.interactableManager

    // Cooldowns (per player, millis)
    private val portalCooldowns = mutableMapOf<UUID, Long>()
    private val pondPortalCooldowns = mutableMapOf<UUID, Long>()
    private val jumpPadCooldowns = mutableMapOf<UUID, Long>() // prevent double-launch

    // Lava trap active state
    private val activeLavaTraps = mutableSetOf<String>()

    private fun isPlayable(player: Player): Boolean =
        player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.CREATIVE

    // --- Jump Pad ---

    @EventHandler
    fun onJumpPadStep(event: PlayerMoveEvent) {
        val player = event.player
        if (!isPlayable(player)) return

        // Cooldown to prevent double-launch (200ms)
        val now = System.currentTimeMillis()
        val lastLaunch = jumpPadCooldowns[player.uniqueId] ?: 0
        if (now - lastLaunch < 200) return

        val blockBelow = player.location.clone().subtract(0.0, 1.0, 0.0).block
        if (blockBelow.type != Material.EMERALD_BLOCK) return

        for ((_, data) in im.jumpPads) {
            if (data.location.blockX == blockBelow.x &&
                data.location.blockY == blockBelow.y &&
                data.location.blockZ == blockBelow.z &&
                data.location.world == blockBelow.world) {
                if (!im.isActive(data.activeSwitches)) return

                // Launch in direction player is facing + upward
                val dir = player.location.direction.clone()
                dir.y = 0.0
                if (dir.lengthSquared() > 0.01) dir.normalize().multiply(0.8) else dir.zero()
                player.velocity = Vector(dir.x, 1.2, dir.z)
                player.fallDistance = 0f // prevent fall damage from launch

                player.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.5f, 1.5f)
                player.world.spawnParticle(Particle.HAPPY_VILLAGER, player.location, 20, 0.5, 0.2, 0.5)

                jumpPadCooldowns[player.uniqueId] = now
                return
            }
        }
    }

    // Cancel fall damage if player recently used a jump pad
    @EventHandler
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        val lastLaunch = jumpPadCooldowns[player.uniqueId] ?: return
        // Cancel fall damage for 10s after launch
        if (System.currentTimeMillis() - lastLaunch < 10000) {
            event.isCancelled = true
        }
    }

    // --- Protect interactable blocks from breaking in survival ---

    @EventHandler
    fun onInteractableBlockBreak(event: BlockBreakEvent) {
        if (event.player.gameMode == GameMode.CREATIVE) return

        val block = event.block
        if (isInteractableBlock(block.location)) {
            event.isCancelled = true
        }
    }

    private fun isInteractableBlock(loc: org.bukkit.Location): Boolean {
        fun matchesBlock(target: org.bukkit.Location): Boolean =
            target.blockX == loc.blockX && target.blockY == loc.blockY &&
            target.blockZ == loc.blockZ && target.world == loc.world

        for ((_, data) in im.jumpPads) if (matchesBlock(data.location)) return true
        for ((_, data) in im.bells) if (matchesBlock(data.location)) return true
        for ((_, data) in im.crystals) if (matchesBlock(data.location)) return true
        for ((_, data) in im.portals) if (matchesBlock(data.location)) return true
        // Also protect switch blocks and their pressure plates
        for ((_, data) in im.switches) {
            if (matchesBlock(data.location)) return true
            val plateAbove = data.location.clone().add(0.0, 1.0, 0.0)
            if (matchesBlock(plateAbove)) return true
        }
        return false
    }

    // --- Bounce Stand (Pig) Death + Respawn ---

    @EventHandler
    fun onBouncePigDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity !is Pig) return
        if (!entity.persistentDataContainer.has(Keys.BOUNCE_STAND, PersistentDataType.BYTE)) return

        event.drops.clear()
        event.droppedExp = 0
        // Polling loop in SwitchListener handles respawning automatically
    }

    // --- Bell ---

    @EventHandler
    fun onBellHit(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BELL) return

        for ((_, data) in im.bells) {
            if (data.location.blockX == block.x &&
                data.location.blockY == block.y &&
                data.location.blockZ == block.z &&
                data.location.world == block.world) {
                if (!im.isActive(data.activeSwitches)) {
                    event.player.sendMessage("§7This bell is dormant.")
                    return
                }

                val ringer = event.player

                // Damage the ringer for ringing the bell: 2-3 hearts (4-6 HP)
                val selfDamage = 4.0 + Math.random() * 2.0
                ringer.damage(selfDamage)
                ringer.sendMessage("§6Lightning will strike a random player in 10 seconds!")

                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (!im.isActive(data.activeSwitches)) return@Runnable
                    val targets = alivePlayers
                    if (targets.isEmpty()) return@Runnable
                    val target = targets.random()
                    target.world.strikeLightning(target.location)
                    Bukkit.broadcast(net.kyori.adventure.text.Component.text("§eLightning strikes from above!"))
                }, 200L)

                return
            }
        }
    }

    // --- Crystal Launcher (proximity-based) ---

    @EventHandler
    fun onCrystalUse(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        if (!isPlayable(player)) return

        for ((_, data) in im.crystals) {
            if (!im.isActive(data.activeSwitches)) continue
            val crystalLoc = data.location.clone().add(0.5, 0.5, 0.5)
            if (crystalLoc.world != player.world) continue
            if (player.location.distance(crystalLoc) > 4.0) continue // within 4 blocks

            val fireball = player.world.spawnEntity(
                player.eyeLocation.add(player.location.direction.multiply(1.5)),
                EntityType.FIREBALL
            ) as Fireball
            fireball.direction = player.location.direction
            fireball.shooter = player
            fireball.yield = 2.0f // explosion power
            fireball.persistentDataContainer.set(Keys.CRYSTAL_FIREBALL, PersistentDataType.BYTE, 1)

            player.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.5f)
            event.isCancelled = true
            return
        }
    }

    @EventHandler
    fun onCrystalExplode(event: EntityExplodeEvent) {
        val fireball = event.entity
        if (!fireball.persistentDataContainer.has(Keys.CRYSTAL_FIREBALL, PersistentDataType.BYTE)) return

        event.blockList().clear() // no block damage

        // Deal damage to nearby players
        val radius = 4.0
        val damage = 6.0
        val loc = fireball.location
        for (entity in loc.world!!.getNearbyEntities(loc, radius, radius, radius)) {
            if (entity is Player && isPlayable(entity)) {
                val dist = entity.location.distance(loc)
                val scaledDamage = damage * (1.0 - dist / radius).coerceAtLeast(0.0)
                if (scaledDamage > 0) entity.damage(scaledDamage)
            }
        }
    }

    // --- Water Arc (particles + riptide) ---

    // Right-click riptide trident near a water arc to launch
    @EventHandler
    fun onRiptideTridentUseInArc(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val item = player.inventory.itemInMainHand
        if (item.type != Material.TRIDENT) return
        if (!item.containsEnchantment(Enchantment.RIPTIDE)) return

        // Slow falling IS the cooldown — can't riptide again while it's active
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return

        for ((_, data) in im.waterArcs) {
            if (!im.isActive(data.activeSwitches)) continue
            if (!isNearArc(player.location, data.pos1, data.pos2, 4.0)) continue

            // Launch player in their look direction
            val direction = player.location.direction
            val level = item.getEnchantmentLevel(Enchantment.RIPTIDE)
            val speed = 1.5 + level * 0.5
            player.velocity = direction.multiply(speed)
            player.playSound(player.location, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0f, 1.0f)

            // Riptide spin animation (Pose.SPIN_ATTACK) for ~1.5s
            startSpinAnimation(player, 30) // 30 ticks = 1.5s

            // Give slow falling 1 for 5s after 1s delay
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                player.addPotionEffect(PotionEffect(
                    PotionEffectType.SLOW_FALLING, 100, 0, false, false, true // 5 seconds
                ))
            }, 20L)

            event.isCancelled = true
            return
        }
    }

    // Spin animation via NMS startAutoSpinAttack — sets the riptide flag + auto-spin ticks internally
    private fun startSpinAnimation(player: Player, durationTicks: Int) {
        try {
            val getHandle = player.javaClass.getMethod("getHandle")
            val nmsPlayer = getHandle.invoke(player)

            // Find startAutoSpinAttack by name — signature may vary between versions
            val method = nmsPlayer.javaClass.methods.firstOrNull { it.name == "startAutoSpinAttack" }
            if (method != null) {
                when (method.parameterCount) {
                    1 -> method.invoke(nmsPlayer, durationTicks)
                    3 -> method.invoke(nmsPlayer, durationTicks, 0f, null)
                    else -> plugin.logger.warning("[WaterArc] startAutoSpinAttack has ${method.parameterCount} params — unsupported")
                }
            } else {
                // Fallback: try setting riptide flag directly via entity data
                trySetRiptideFlag(nmsPlayer, durationTicks)
            }
        } catch (e: Exception) {
            plugin.logger.warning("[WaterArc] Spin animation failed: ${e.message}")
        }
    }

    // Fallback: directly set the living entity riptide flag (flag index 4 on LivingEntity shared flags)
    private fun trySetRiptideFlag(nmsPlayer: Any, durationTicks: Int) {
        try {
            // LivingEntity.setLivingEntityFlag(int flag, boolean value) — flag 4 = riptide
            val setFlag = nmsPlayer.javaClass.methods.firstOrNull {
                it.name == "setLivingEntityFlag" && it.parameterCount == 2
            }
            if (setFlag != null) {
                setFlag.invoke(nmsPlayer, 4, true)
                // Schedule removal after duration
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    try { setFlag.invoke(nmsPlayer, 4, false) } catch (_: Exception) {}
                }, durationTicks.toLong())
            } else {
                plugin.logger.warning("[WaterArc] Could not find setLivingEntityFlag — no spin animation")
            }
        } catch (e: Exception) {
            plugin.logger.warning("[WaterArc] Riptide flag fallback failed: ${e.message}")
        }
    }

    // Check if a location is near the parabolic arc between pos1 and pos2
    private fun isNearArc(loc: org.bukkit.Location, pos1: org.bukkit.Location, pos2: org.bukkit.Location, margin: Double): Boolean {
        if (loc.world != pos1.world) return false
        val arcPoints = computeArcPoints(pos1, pos2, 20)
        for (point in arcPoints) {
            val dx = loc.x - point.x
            val dy = loc.y - point.y
            val dz = loc.z - point.z
            if (dx * dx + dy * dy + dz * dz <= margin * margin) return true
        }
        return false
    }

    // Compute points along a parabolic arc between two endpoints
    private fun computeArcPoints(pos1: org.bukkit.Location, pos2: org.bukkit.Location, steps: Int): List<org.bukkit.Location> {
        val world = pos1.world ?: return emptyList()
        val points = mutableListOf<org.bukkit.Location>()
        val dx = pos2.x - pos1.x
        val dy = pos2.y - pos1.y
        val dz = pos2.z - pos1.z
        val horizontalDist = Math.sqrt(dx * dx + dz * dz)
        // Arc peak height above the midpoint — proportional to horizontal distance
        val arcHeight = (horizontalDist / 3.0).coerceAtLeast(3.0)

        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = pos1.x + dx * t
            val z = pos1.z + dz * t
            // Parabola: y = lerp(y1, y2, t) + height * 4t(1-t)
            val y = pos1.y + dy * t + arcHeight * 4.0 * t * (1.0 - t)
            points.add(org.bukkit.Location(world, x, y, z))
        }
        return points
    }

    // --- Lava Trap ---

    @EventHandler
    fun onLavaTrapTrigger(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        if (!block.type.name.contains("PRESSURE_PLATE")) return

        for ((name, data) in im.lavaTraps) {
            if (data.trigger.blockX == block.x &&
                data.trigger.blockY == block.y &&
                data.trigger.blockZ == block.z &&
                data.trigger.world == block.world) {
                if (!im.isActive(data.activeSwitches)) return
                if (name in activeLavaTraps) return

                activeLavaTraps.add(name)

                im.forEachBlockInRegion(data.pos1, data.pos2) { loc ->
                    loc.block.type = Material.LAVA
                }

                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    im.forEachBlockInRegion(data.pos1, data.pos2) { loc ->
                        if (loc.block.type == Material.LAVA) loc.block.type = Material.AIR
                    }
                    activeLavaTraps.remove(name)
                }, 160L) // 8 seconds

                return
            }
        }
    }

    // --- Portal (Random Player Teleporter) ---

    @EventHandler
    fun onPortalUse(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        val block = event.clickedBlock ?: return
        val player = event.player
        if (!isPlayable(player)) return

        for ((_, data) in im.portals) {
            if (data.location.blockX == block.x &&
                data.location.blockY == block.y &&
                data.location.blockZ == block.z &&
                data.location.world == block.world) {
                if (!im.isActive(data.activeSwitches)) {
                    player.sendMessage("§cThis portal is inactive.")
                    return
                }

                val now = System.currentTimeMillis()
                val lastUse = portalCooldowns[player.uniqueId] ?: 0
                if (now - lastUse < 3000) {
                    player.sendMessage("§cPlease wait before using this portal again.")
                    return
                }
                portalCooldowns[player.uniqueId] = now

                Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                    "§e${player.name} might be teleported to a random player..."
                ))

                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (!player.isOnline) return@Runnable
                    if (player.gameMode == GameMode.SPECTATOR) return@Runnable

                    if (Math.random() < 0.5) {
                        val targets = alivePlayers.filter { it.uniqueId != player.uniqueId }
                        if (targets.isNotEmpty()) {
                            val target = targets.random()
                            player.teleport(target.location)
                            Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                                "§e${player.name} has been teleported to ${target.name}!"
                            ))
                        } else {
                            player.sendMessage("§7No valid players to teleport to.")
                        }
                    } else {
                        player.sendMessage("You have not been teleported!")
                    }
                }, 200L)

                return
            }
        }
    }

    // --- Pond Portal ---

    @EventHandler
    fun onPondPortalStep(event: PlayerMoveEvent) {
        val player = event.player
        if (!isPlayable(player)) return

        val playerLoc = player.location

        for ((name, data) in im.pondPortals) {
            if (!im.isActive(data.activeSwitches)) continue
            if (data.stops.isEmpty()) continue
            if (!im.isInRegion(playerLoc, data.pos1, data.pos2)) continue

            val now = System.currentTimeMillis()
            val lastTp = pondPortalCooldowns[player.uniqueId] ?: 0
            if (now - lastTp < 3000) return // 3s cooldown
            pondPortalCooldowns[player.uniqueId] = now

            val currentStop = plugin.pondPortalTask.getCurrentStopIndex()
            val stopIndex = currentStop % data.stops.size
            val dest = data.stops[stopIndex]

            // Announce
            Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                "§e${player.name} has been sent somewhere by the pond..."
            ))

            // Teleport SOON, play SFX at destination so player hears it
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                player.teleport(dest)
                player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
            }, 1L)

            return
        }
    }

    // --- Particle Tick (called from repeating task) ---

    fun tickParticles() {
        // Jump pad particles (active only)
        for ((_, data) in im.jumpPads) {
            if (!im.isActive(data.activeSwitches)) continue
            val loc = data.location.clone().add(0.5, 1.2, 0.5)
            loc.world?.spawnParticle(Particle.HAPPY_VILLAGER, loc, 3, 0.3, 0.3, 0.3)
        }

        // Bell particles (active only)
        for ((_, data) in im.bells) {
            if (!im.isActive(data.activeSwitches)) continue
            val loc = data.location.clone().add(0.5, 0.5, 0.5)
            loc.world?.spawnParticle(Particle.ENCHANT, loc, 5, 0.3, 0.3, 0.3)
        }

        // Crystal launcher particle columns (active only)
        for ((_, data) in im.crystals) {
            if (!im.isActive(data.activeSwitches)) continue
            val baseLoc = data.location.clone().add(0.5, 1.0, 0.5)
            for (y in 0..4) {
                val loc = baseLoc.clone().add(0.0, y.toDouble(), 0.0)
                loc.world?.spawnParticle(Particle.END_ROD, loc, 2, 0.1, 0.1, 0.1, 0.0)
            }
        }

        // Pond portal particles (active only, end rod in region)
        for ((_, data) in im.pondPortals) {
            if (!im.isActive(data.activeSwitches)) continue
            if (data.stops.isEmpty()) continue
            val world = data.pos1.world ?: continue
            val minX = minOf(data.pos1.blockX, data.pos2.blockX)
            val maxX = maxOf(data.pos1.blockX, data.pos2.blockX)
            val minY = minOf(data.pos1.blockY, data.pos2.blockY)
            val maxY = maxOf(data.pos1.blockY, data.pos2.blockY)
            val minZ = minOf(data.pos1.blockZ, data.pos2.blockZ)
            val maxZ = maxOf(data.pos1.blockZ, data.pos2.blockZ)
            for (i in 0..5) {
                val x = minX + Math.random() * (maxX - minX + 1)
                val y = minY + Math.random() * (maxY - minY + 1)
                val z = minZ + Math.random() * (maxZ - minZ + 1)
                world.spawnParticle(Particle.END_ROD, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }

        // Water arc particles (active only — arc-shaped)
        for ((_, data) in im.waterArcs) {
            if (!im.isActive(data.activeSwitches)) continue
            val world = data.pos1.world ?: continue
            val arcPoints = computeArcPoints(data.pos1, data.pos2, 30)
            for (point in arcPoints) {
                // Spread particles around each arc point for volume
                val spread = 0.6
                world.spawnParticle(Particle.FALLING_WATER, point.x, point.y, point.z, 3, spread, spread, spread)
                world.spawnParticle(Particle.SPLASH, point.x, point.y, point.z, 1, spread * 0.5, spread * 0.5, spread * 0.5)
            }
            // Dripping water at the two endpoints
            world.spawnParticle(Particle.DRIPPING_WATER, data.pos1.x + 0.5, data.pos1.y + 0.5, data.pos1.z + 0.5, 2, 0.3, 0.3, 0.3)
            world.spawnParticle(Particle.DRIPPING_WATER, data.pos2.x + 0.5, data.pos2.y + 0.5, data.pos2.z + 0.5, 2, 0.3, 0.3, 0.3)
        }

    }

    // --- Reset ---

    fun resetAll() {
        portalCooldowns.clear()
        pondPortalCooldowns.clear()
        jumpPadCooldowns.clear()
        activeLavaTraps.clear()
    }
}
