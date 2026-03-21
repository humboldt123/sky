package me.bigratenthusiast.crypt.abilities

import me.bigratenthusiast.crypt.Crypt
import me.bigratenthusiast.crypt.util.Keys
import me.bigratenthusiast.crypt.util.hasPluginName
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class NecromancerManager(private val plugin: Crypt) : Listener {

    companion object {
        const val MARK_SCALE_FACTOR = 1.0       // seconds per block distance // TODO: TEST
        const val MINION_RESPAWN_TICKS = 600L   // 30 seconds // TODO: TEST
        const val NECROMANCER_ANCHOR = "Necromancer Robe" // display name to detect necromancer kit
        const val CLOUD_RADIUS = 1.5
        const val CLOUD_DURATION_TICKS = 20     // 1 second
        const val CLOUD_MARK_FRACTION = 3.0     // 1/3 of normal duration
    }

    // Necromancer UUID -> list of minion entity UUIDs
    private val minions = mutableMapOf<UUID, MutableList<UUID>>()

    // Marked entity UUID -> MarkData (multiple targets per necromancer allowed)
    data class MarkData(val necromancerUUID: UUID, val expiryTimeMs: Long)
    private val marks = mutableMapOf<UUID, MarkData>()

    // Pending egg return tasks (necromancer UUID -> tasks)
    private val pendingEggTasks = mutableMapOf<UUID, MutableList<BukkitTask>>()

    // --- Spawn Egg Usage (summon minion) ---

    @EventHandler
    fun onSpawnEggUse(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        if (!isNecromancer(player)) return

        val item = player.inventory.itemInMainHand
        if (!item.type.name.endsWith("_SPAWN_EGG")) return

        event.isCancelled = true

        // Determine entity type from spawn egg
        val entityType = spawnEggToEntityType(item.type) ?: return

        // Spawn the mob 2 blocks in front (horizontal only)
        val dir = player.location.direction.clone().setY(0).normalize()
        val loc = player.location.add(dir.multiply(2.0))
        val mob = player.world.spawnEntity(loc, entityType) as? Mob ?: return

        // Configure as minion
        mob.persistentDataContainer.set(Keys.MINION_OWNER, PersistentDataType.STRING, player.uniqueId.toString())

        // Apply invisibility
        mob.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, Int.MAX_VALUE, 0, false, false))

        // Equip skull + leather chestplate (same visual as necromancer)
        val equipment = mob.equipment ?: return
        equipment.helmet = ItemStack(Material.SKELETON_SKULL) // TODO: TEST — match necromancer's actual skull
        val chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
        val chestMeta = chestplate.itemMeta as LeatherArmorMeta
        chestMeta.setColor(Color.fromRGB(50, 20, 50)) // Dark purple-ish // TODO: TEST color
        chestplate.itemMeta = chestMeta
        equipment.chestplate = chestplate

        // Track minion
        minions.getOrPut(player.uniqueId) { mutableListOf() }.add(mob.uniqueId)

        // Consume spawn egg
        item.amount -= 1

        // TODO: no chat messaage, maybe play sfx and have particle?
        // player.sendMessage("§5Minion summoned!")
    }

    // --- Prevent minions from targeting their owner, redirect to nearest mark ---

    @EventHandler
    fun onMinionTarget(event: EntityTargetEvent) {
        val mob = event.entity as? Mob ?: return
        val ownerStr = mob.persistentDataContainer.get(Keys.MINION_OWNER, PersistentDataType.STRING) ?: return
        val ownerUUID = UUID.fromString(ownerStr)

        val target = event.target

        // Never target owner
        if (target is Player && target.uniqueId == ownerUUID) {
            val nearest = findNearestMarkedTarget(ownerUUID, mob.location)
            if (nearest != null) {
                event.target = nearest
            } else {
                event.isCancelled = true
            }
            return
        }

        // Allow self-defense / programmatic targeting (from onMinionDamaged)
        val reason = event.reason
        if (reason == EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY ||
            reason == EntityTargetEvent.TargetReason.TARGET_ATTACKED_NEARBY_ENTITY ||
            reason == EntityTargetEvent.TargetReason.CUSTOM) {
            return
        }

        // For autonomous AI targeting: only allow if target is marked by this necromancer
        if (target != null) {
            val mark = marks[target.uniqueId]
            if (mark != null && mark.necromancerUUID == ownerUUID && System.currentTimeMillis() < mark.expiryTimeMs) {
                return // target is marked, allow
            }
        }

        // Not marked — redirect to nearest mark, or cancel
        val nearest = findNearestMarkedTarget(ownerUUID, mob.location)
        if (nearest != null) {
            event.target = nearest
        } else {
            event.isCancelled = true
        }
    }

    // --- Minion self-defense: if attacked, aggro the attacker ---

    @EventHandler
    fun onMinionDamaged(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        val ownerStr = victim.persistentDataContainer.get(Keys.MINION_OWNER, PersistentDataType.STRING) ?: return

        // Get the actual attacker (handle projectiles)
        val attacker = when (val damager = event.damager) {
            is Projectile -> damager.shooter as? LivingEntity ?: return
            is LivingEntity -> damager
            else -> return
        }

        // Don't aggro the owner
        val ownerUUID = UUID.fromString(ownerStr)
        if (attacker is Player && attacker.uniqueId == ownerUUID) return

        // Set this minion to aggro its attacker (self-defense takes priority)
        val mob = victim as? Mob ?: return
        mob.target = attacker
    }

    // --- Snowball hit: play hurt animation on players (snowballs deal 0 damage = no red flash by default) ---

    @EventHandler
    fun onSnowballHitPlayer(event: ProjectileHitEvent) {
        if (event.entity !is Snowball) return
        val hitPlayer = event.hitEntity as? Player ?: return
        hitPlayer.playHurtAnimation(0f)
    }

    // --- Mark Target snowball: infinite, doesn't consume ---

    @EventHandler
    fun onMarkTargetLaunch(event: ProjectileLaunchEvent) {
        val snowball = event.entity as? Snowball ?: return
        val shooter = snowball.shooter as? Player ?: return

        val itemName = snowball.item.itemMeta?.let { getPlainName(it.displayName()) }
        if (itemName == null || !itemName.contains("Mark Target")) return

        // Give the snowball back next tick (after consumption)
        val returnItem = snowball.item.clone()
        returnItem.amount = 1
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!shooter.isOnline) return@Runnable
            shooter.inventory.addItem(returnItem)
        }, 1L)
    }

    // --- Mark Target hit: mark mob or spawn particle cloud ---

    @EventHandler
    fun onMarkProjectileHit(event: ProjectileHitEvent) {
        val snowball = event.entity as? Snowball ?: return
        val shooter = snowball.shooter as? Player ?: return

        // Check if this is a Mark Target snowball by name
        val itemName = snowball.item.itemMeta?.let { getPlainName(it.displayName()) }
        if (itemName == null || !itemName.contains("Mark Target")) return

        // Check if hit entity is own minion — recall it instantly
        val hitEntity = event.hitEntity
        if (hitEntity != null) {
            val minionOwner = hitEntity.persistentDataContainer.get(Keys.MINION_OWNER, PersistentDataType.STRING)
            if (minionOwner != null && UUID.fromString(minionOwner) == shooter.uniqueId) {
                val eggType = entityToSpawnEgg(hitEntity.type)
                minions[shooter.uniqueId]?.remove(hitEntity.uniqueId)
                hitEntity.remove()
                if (eggType != null) {
                    shooter.inventory.addItem(ItemStack(eggType))
                    shooter.sendMessage("§5Minion recalled!")
                }
                return
            }
        }

        // Direct hit on a living entity (mob or player) — mark it
        val hitTarget = hitEntity as? LivingEntity
        if (hitTarget != null && hitTarget.uniqueId != shooter.uniqueId) {
            val distance = shooter.location.distance(hitTarget.location)
            val durationSeconds = (distance * MARK_SCALE_FACTOR).coerceAtLeast(2.0)
            applyMark(shooter, hitTarget, durationSeconds)
            return
        }

        // Missed — spawn particle cloud if hit a block
        if (event.hitBlock != null) {
            spawnMarkCloud(shooter, snowball.location)
        }
    }

    private fun applyMark(shooter: Player, target: LivingEntity, durationSeconds: Double) {
        val durationMs = (durationSeconds * 1000).toLong()
        val targetName = if (target is Player) target.name else target.type.name.lowercase()

        // Check if already marked by same necromancer — stack time
        val existingMark = marks[target.uniqueId]
        if (existingMark != null && existingMark.necromancerUUID == shooter.uniqueId) {
            val newExpiry = existingMark.expiryTimeMs + durationMs
            marks[target.uniqueId] = MarkData(shooter.uniqueId, newExpiry)
            shooter.sendMessage("§5Added ${durationSeconds.toInt()}s of marking to $targetName!")
        } else {
            marks[target.uniqueId] = MarkData(shooter.uniqueId, System.currentTimeMillis() + durationMs)
            shooter.sendMessage("§5Marked $targetName for ${durationSeconds.toInt()}s!")
        }

        // Redirect idle minions to nearest mark
        redirectIdleMinions(shooter.uniqueId)

        // Schedule mark expiry
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val currentMark = marks[target.uniqueId]
            if (currentMark != null && currentMark.necromancerUUID == shooter.uniqueId
                && System.currentTimeMillis() >= currentMark.expiryTimeMs) {
                marks.remove(target.uniqueId)
            }
        }, (durationSeconds * 20).toLong())
    }

    private fun spawnMarkCloud(shooter: Player, location: Location) {
        val world = location.world ?: return

        object : BukkitRunnable() {
            var ticksLeft = CLOUD_DURATION_TICKS

            override fun run() {
                if (ticksLeft <= 0) {
                    cancel()
                    return
                }

                // Spawn particles
                world.spawnParticle(Particle.PORTAL, location, 15, 0.5, 0.5, 0.5)

                // Check for living entities in radius (mobs + players)
                val nearbyTargets = world.getNearbyEntities(location, CLOUD_RADIUS, CLOUD_RADIUS, CLOUD_RADIUS)
                    .filterIsInstance<LivingEntity>()

                for (target in nearbyTargets) {
                    // Don't mark self
                    if (target is Player && target.uniqueId == shooter.uniqueId) continue
                    // Don't mark own minions
                    val minionOwner = target.persistentDataContainer.get(Keys.MINION_OWNER, PersistentDataType.STRING)
                    if (minionOwner != null && UUID.fromString(minionOwner) == shooter.uniqueId) continue

                    // Mark with 1/3 duration
                    val distance = shooter.location.distance(location)
                    val durationSeconds = (distance * MARK_SCALE_FACTOR).coerceAtLeast(2.0) / CLOUD_MARK_FRACTION

                    applyMark(shooter, target, durationSeconds)
                    cancel() // Cloud consumed after first mark
                    return
                }

                ticksLeft--
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun redirectIdleMinions(necromancerUUID: UUID) {
        val minionUUIDs = minions[necromancerUUID] ?: return
        for (minionUUID in minionUUIDs) {
            val minion = Bukkit.getEntity(minionUUID) as? Mob ?: continue
            // Only redirect idle minions (respect self-defense)
            if (minion.target != null) continue
            val nearest = findNearestMarkedTarget(necromancerUUID, minion.location)
            if (nearest != null) {
                minion.target = nearest
            }
        }
    }

    private fun findNearestMarkedTarget(necromancerUUID: UUID, fromLocation: Location): LivingEntity? {
        val now = System.currentTimeMillis()
        var nearest: LivingEntity? = null
        var nearestDistSq = Double.MAX_VALUE

        val expired = mutableListOf<UUID>()
        for ((targetUUID, markData) in marks) {
            if (markData.necromancerUUID != necromancerUUID) continue
            if (now >= markData.expiryTimeMs) {
                expired.add(targetUUID)
                continue
            }
            val entity = Bukkit.getEntity(targetUUID) as? LivingEntity ?: continue
            val distSq = entity.location.distanceSquared(fromLocation)
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearest = entity
            }
        }
        expired.forEach { marks.remove(it) }
        return nearest
    }

    // --- Minion Death (return spawn egg after cooldown) ---

    @EventHandler
    fun onMinionDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val ownerStr = entity.persistentDataContainer.get(Keys.MINION_OWNER, PersistentDataType.STRING) ?: return
        val ownerUUID = UUID.fromString(ownerStr)

        // Remove from tracking
        minions[ownerUUID]?.remove(entity.uniqueId)

        // Clear drops (minions shouldn't drop loot)
        event.drops.clear()

        // Determine spawn egg type from entity
        val eggType = entityToSpawnEgg(entity.type) ?: return

        // Schedule spawn egg return
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val owner = Bukkit.getPlayer(ownerUUID) ?: return@Runnable
            if (owner.gameMode == org.bukkit.GameMode.SPECTATOR) return@Runnable
            owner.inventory.addItem(ItemStack(eggType))
            owner.sendMessage("§5Minion spawn egg returned!")
        }, MINION_RESPAWN_TICKS)

        pendingEggTasks.getOrPut(ownerUUID) { mutableListOf() }.add(task)
    }

    // --- Necromancer Death: despawn all minions, cancel egg returns ---

    @EventHandler
    fun onNecromancerDeath(event: PlayerDeathEvent) {
        val playerUUID = event.player.uniqueId
        if (minions.containsKey(playerUUID)) {
            despawnMinionsFor(playerUUID)
        }
    }

    // --- Creaking interaction: cancel damage to necromancer, apply levitation ---

    @EventHandler(priority = EventPriority.HIGH)
    fun onCreakingDamage(event: EntityDamageByEntityEvent) {
        if (event.damager.type != EntityType.CREAKING) return

        val victim = event.entity as? Player ?: return
        if (!isNecromancer(victim)) return

        event.isCancelled = true
        victim.addPotionEffect(PotionEffect(
            PotionEffectType.LEVITATION, 40, 9, false, false // level 10 for 2s // TODO: TEST
        ))
    }

    // --- Utility ---

    fun isNecromancer(player: Player): Boolean {
        for (armorPiece in player.inventory.armorContents) {
            if (armorPiece == null) continue
            if (armorPiece.hasPluginName(NECROMANCER_ANCHOR)) return true
        }
        return false
    }

    private fun getPlainName(component: Component?): String? {
        if (component == null) return null
        return PlainTextComponentSerializer.plainText().serialize(component)
    }

    private fun spawnEggToEntityType(material: Material): EntityType? {
        val name = material.name.removeSuffix("_SPAWN_EGG")
        return try {
            EntityType.valueOf(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun entityToSpawnEgg(entityType: EntityType): Material? {
        return try {
            Material.valueOf("${entityType.name}_SPAWN_EGG")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun despawnAllMinions() {
        for ((_, minionList) in minions) {
            for (minionUUID in minionList) {
                Bukkit.getEntity(minionUUID)?.remove()
            }
        }
        minions.clear()
        cancelAllEggTasks()
    }

    fun despawnMinionsFor(playerUUID: UUID) {
        val minionList = minions.remove(playerUUID) ?: return
        for (minionUUID in minionList) {
            Bukkit.getEntity(minionUUID)?.remove()
        }
        pendingEggTasks.remove(playerUUID)?.forEach { it.cancel() }
        // Clear all marks for this necromancer
        marks.entries.removeAll { it.value.necromancerUUID == playerUUID }
    }

    private fun cancelAllEggTasks() {
        pendingEggTasks.values.forEach { tasks -> tasks.forEach { it.cancel() } }
        pendingEggTasks.clear()
    }

    fun clearAll() {
        despawnAllMinions()
        marks.clear()
    }
}
