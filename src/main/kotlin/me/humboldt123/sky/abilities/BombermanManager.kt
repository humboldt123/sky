package me.humboldt123.sky.abilities

import me.humboldt123.sky.Sky
import me.humboldt123.sky.util.Keys
import me.humboldt123.sky.util.hasPluginName
import me.humboldt123.sky.util.pluginName
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID

class BombermanManager(private val plugin: Sky) : Listener {

    companion object {
        const val CROSSBOW_TNT_RADIUS = 4.0
        const val CROSSBOW_TNT_DAMAGE = 6.0
        const val CROSSBOW_TNT_KB_MULTIPLIER = 2.0

        const val STICKY_MINE_RADIUS = 4.0
        const val STICKY_MINE_DAMAGE = 8.0
        const val MINE_PROXIMITY_RADIUS = 2.0
        const val MINE_CHAIN_RADIUS = 4.0
        const val MINE_CROSSBOW_TRIGGER_RADIUS = 4.0
        const val MINE_RETURN_DELAY = 20L // 1 second

        const val BAT_TNT_RADIUS = 4.0
        const val BAT_TNT_DAMAGE = 6.0
        const val BAT_TNT_FUSE = 30 // 1.5 seconds
        const val BAT_TNT_KICK_RANGE = 5.0
        const val BAT_TNT_KICK_SPEED = 1.5
        const val BAT_TNT_RETURN_DELAY = 20L // 1 second
    }

    // Owner UUID → list of TNT entity UUIDs (all types)
    private val trackedTnt = mutableMapOf<UUID, MutableList<UUID>>()

    // Sticky mine entity UUIDs (subset for proximity tick)
    private val stickyMines = mutableSetOf<UUID>()

    // Mines currently detonating (prevent double-detonation in chain reactions)
    private val detonatingMines = mutableSetOf<UUID>()

    // Crossbow TNT: track last Y position per entity UUID for ground impact detection
    private val crossbowTntLastY = mutableMapOf<UUID, Double>()

    // --- Kit Detection ---

    private fun isBomberman(player: Player): Boolean {
        val chestplate = player.inventory.chestplate ?: return false
        return chestplate.hasPluginName("Explosive Tunic")
    }

    // --- PDC Helpers ---

    private fun tagTnt(tnt: TNTPrimed, type: String, ownerUUID: UUID) {
        tnt.persistentDataContainer.set(
            Keys.TAGGED_TNT, PersistentDataType.STRING, "$type:$ownerUUID"
        )
    }

    private fun getTntData(entity: Entity): Pair<String, UUID>? {
        val value = entity.persistentDataContainer.get(Keys.TAGGED_TNT, PersistentDataType.STRING)
            ?: return null
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) return null
        return try {
            parts[0] to UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // --- Tracking ---

    private fun trackTnt(ownerUUID: UUID, tntUUID: UUID) {
        trackedTnt.getOrPut(ownerUUID) { mutableListOf() }.add(tntUUID)
    }

    private fun untrackTnt(ownerUUID: UUID, tntUUID: UUID) {
        trackedTnt[ownerUUID]?.remove(tntUUID)
    }

    // --- Shared Explosion Damage (with optional knockback multiplier) ---

    private fun applyExplosionDamage(
        loc: Location, radius: Double, damage: Double,
        excludeOwner: UUID?,
        knockbackMultiplier: Double = 1.0
    ) {
        for (entity in loc.world!!.getNearbyEntities(loc, radius, radius, radius)) {
            if (entity !is LivingEntity) continue
            // Skip spectators (and non-survival players for the owner check)
            if (entity is Player && entity.gameMode != GameMode.SURVIVAL) continue
            if (excludeOwner != null && entity.uniqueId == excludeOwner) continue
            val dist = entity.location.distance(loc)
            val scaledDamage = damage * (1.0 - dist / radius).coerceAtLeast(0.0)
            if (scaledDamage > 0) {
                entity.damage(scaledDamage)
                // Apply additional knockback
                if (knockbackMultiplier > 1.0) {
                    val kb = entity.location.toVector().subtract(loc.toVector()).normalize()
                        .multiply(knockbackMultiplier * (1.0 - dist / radius).coerceAtLeast(0.2))
                    entity.velocity = entity.velocity.add(kb)
                }
            }
        }
    }

    // ========== 1. TNT CROSSBOW ==========
    // Fires a TNTPrimed entity. Ground impact detected via tick (Y unchanged between ticks).

    @EventHandler
    fun onCrossbowShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        if (!isBomberman(player)) return

        val bow = event.bow ?: return
        if (!bow.hasPluginName("TNT Crossbow")) return

        val projectile = event.projectile

        // Tag the arrow so we can remove it
        projectile.persistentDataContainer.set(
            Keys.TAGGED_TNT, PersistentDataType.STRING, "CROSSBOW_ARROW"
        )

        // Spawn primed TNT with the arrow's velocity — long fuse, we detonate on impact
        val tnt = player.world.spawn(projectile.location, TNTPrimed::class.java)
        tnt.velocity = projectile.velocity
        tnt.fuseTicks = 200 // 10s safety fuse (will explode on impact long before this)
        tnt.source = player
        tagTnt(tnt, "CROSSBOW_TNT", player.uniqueId)
        trackTnt(player.uniqueId, tnt.uniqueId)
        crossbowTntLastY[tnt.uniqueId] = tnt.location.y

        // Remove the arrow next tick (let crossbow unload naturally)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            projectile.remove()
        }, 1L)
    }

    @EventHandler
    fun onCrossbowArrowHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        val tag = projectile.persistentDataContainer.get(Keys.TAGGED_TNT, PersistentDataType.STRING)
        if (tag == "CROSSBOW_ARROW") {
            event.isCancelled = true
            projectile.remove()
        }
    }

    /**
     * Called every 5 ticks. Checks if any crossbow TNT has "landed" (Y unchanged).
     * If so, explode it immediately.
     */
    fun tickCrossbowTnt() {
        val toExplode = mutableListOf<Pair<TNTPrimed, UUID>>() // tnt, ownerUUID

        val iterator = crossbowTntLastY.entries.iterator()
        while (iterator.hasNext()) {
            val (tntUUID, lastY) = iterator.next()
            val tnt = Bukkit.getEntity(tntUUID) as? TNTPrimed
            if (tnt == null || tnt.isDead) {
                iterator.remove()
                continue
            }

            val currentY = tnt.location.y
            // If Y hasn't changed (within small epsilon), TNT has landed
            if (Math.abs(currentY - lastY) < 0.01 && tnt.ticksLived > 2) {
                val data = getTntData(tnt)
                if (data != null && data.first == "CROSSBOW_TNT") {
                    toExplode.add(tnt to data.second)
                }
                iterator.remove()
            } else {
                crossbowTntLastY[tntUUID] = currentY
            }
        }

        for ((tnt, ownerUUID) in toExplode) {
            explodeCrossbowTnt(tnt, ownerUUID)
        }
    }

    private fun explodeCrossbowTnt(tnt: TNTPrimed, ownerUUID: UUID) {
        val loc = tnt.location.clone()
        tnt.remove()
        untrackTnt(ownerUUID, tnt.uniqueId)

        // Explosion effects
        loc.world?.spawnParticle(Particle.EXPLOSION, loc, 1)
        loc.world?.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f)

        // Damage all players except owner, with 2x knockback
        applyExplosionDamage(loc, CROSSBOW_TNT_RADIUS, CROSSBOW_TNT_DAMAGE,
            excludeOwner = ownerUUID, knockbackMultiplier = CROSSBOW_TNT_KB_MULTIPLIER)

        // Apply KB-only to owner (for rocket jumping, no damage)
        val owner = Bukkit.getPlayer(ownerUUID)
        if (owner != null && owner.gameMode == GameMode.SURVIVAL) {
            val dist = owner.location.distance(loc)
            if (dist < CROSSBOW_TNT_RADIUS) {
                val scale = (1.0 - dist / CROSSBOW_TNT_RADIUS).coerceAtLeast(0.2)
                val kb = owner.location.toVector().subtract(loc.toVector()).normalize()
                    .multiply(CROSSBOW_TNT_KB_MULTIPLIER * scale)
                owner.velocity = owner.velocity.add(kb)
            }
        }

        // Trigger nearby sticky mines
        triggerNearbyMines(loc)
    }

    // ========== 2. STICKY MINES ==========

    @EventHandler
    fun onStickyMinePlace(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        if (!isBomberman(player)) return

        val item = player.inventory.itemInMainHand
        if (item.type != Material.FIREWORK_STAR) return
        if (!item.hasPluginName("Sticky Mine")) return

        val block = event.clickedBlock ?: return
        val face = event.blockFace

        event.isCancelled = true

        // Place TNT on the surface of the clicked face
        val placeLoc = block.getRelative(face).location.add(0.5, 0.0, 0.5)

        val tnt = player.world.spawn(placeLoc, TNTPrimed::class.java)
        tnt.fuseTicks = Int.MAX_VALUE
        tnt.setGravity(false)
        tnt.source = player
        // Zero out the default spawn velocity so it doesn't float upward
        tnt.velocity = Vector(0, 0, 0)
        tagTnt(tnt, "STICKY_MINE", player.uniqueId)
        trackTnt(player.uniqueId, tnt.uniqueId)
        stickyMines.add(tnt.uniqueId)

        item.amount -= 1

        player.playSound(placeLoc, Sound.BLOCK_STONE_PLACE, 1.0f, 0.8f)
    }

    /**
     * Called every 5 ticks to check sticky mine proximity triggers.
     * Also re-freezes mines in place (zero velocity) in case anything pushes them.
     * Also checks for CROSSBOW_TNT entities touching mines (triggers detonation).
     */
    fun tickProximity() {
        val toDetonate = mutableListOf<UUID>()

        val iterator = stickyMines.iterator()
        while (iterator.hasNext()) {
            val mineUUID = iterator.next()
            if (mineUUID in detonatingMines) continue

            val mine = Bukkit.getEntity(mineUUID) as? TNTPrimed
            if (mine == null || mine.isDead) {
                iterator.remove()
                continue
            }

            // Keep mine frozen in place
            if (mine.velocity.lengthSquared() > 0.001) {
                mine.velocity = Vector(0, 0, 0)
            }

            val data = getTntData(mine) ?: continue
            val ownerUUID = data.second
            var shouldDetonate = false

            val nearby = mine.location.world!!.getNearbyEntities(
                mine.location, MINE_CROSSBOW_TRIGGER_RADIUS, MINE_CROSSBOW_TRIGGER_RADIUS, MINE_CROSSBOW_TRIGGER_RADIUS
            )
            for (entity in nearby) {
                // Player proximity trigger (2 blocks)
                if (entity is Player && entity.gameMode == GameMode.SURVIVAL
                    && entity.uniqueId != ownerUUID
                    && entity.location.distance(mine.location) <= MINE_PROXIMITY_RADIUS) {
                    shouldDetonate = true
                    break
                }
                // CROSSBOW_TNT entity touching mine (within trigger radius)
                if (entity is TNTPrimed && entity.uniqueId != mineUUID) {
                    val tntData = getTntData(entity)
                    if (tntData != null && tntData.first == "CROSSBOW_TNT"
                        && entity.location.distance(mine.location) <= MINE_CROSSBOW_TRIGGER_RADIUS) {
                        shouldDetonate = true
                        break
                    }
                }
            }

            if (shouldDetonate) {
                toDetonate.add(mineUUID)
            }
        }

        for (mineUUID in toDetonate) {
            detonateMine(mineUUID)
        }
    }

    private fun detonateMine(mineUUID: UUID) {
        if (mineUUID in detonatingMines) return
        detonatingMines.add(mineUUID)

        val mine = Bukkit.getEntity(mineUUID) as? TNTPrimed
        if (mine == null || mine.isDead) {
            detonatingMines.remove(mineUUID)
            stickyMines.remove(mineUUID)
            return
        }

        val data = getTntData(mine) ?: run {
            detonatingMines.remove(mineUUID)
            return
        }
        val ownerUUID = data.second
        val loc = mine.location.clone()

        // Remove the mine entity
        mine.remove()
        stickyMines.remove(mineUUID)
        untrackTnt(ownerUUID, mineUUID)

        // Cross (plus) shaped explosion particles — extends to chain radius
        val world = loc.world ?: return
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f)
        world.spawnParticle(Particle.EXPLOSION, loc, 1) // center
        val step = 1.0
        var d = step
        while (d <= MINE_CHAIN_RADIUS) {
            world.spawnParticle(Particle.EXPLOSION, loc.clone().add(d, 0.0, 0.0), 1)
            world.spawnParticle(Particle.EXPLOSION, loc.clone().add(-d, 0.0, 0.0), 1)
            world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0.0, 0.0, d), 1)
            world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0.0, 0.0, -d), 1)
            d += step
        }

        // Damage players (exclude owner)
        applyExplosionDamage(loc, STICKY_MINE_RADIUS, STICKY_MINE_DAMAGE, excludeOwner = ownerUUID)

        // Chain reaction: detonate nearby mines after 1 tick
        for (otherMineUUID in stickyMines.toList()) {
            if (otherMineUUID in detonatingMines) continue
            val otherMine = Bukkit.getEntity(otherMineUUID) as? TNTPrimed ?: continue
            if (otherMine.location.distance(loc) <= MINE_CHAIN_RADIUS) {
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    detonateMine(otherMineUUID)
                }, 1L)
            }
        }

        // Return 1 Sticky Mine item to owner after 1 second
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val owner = Bukkit.getPlayer(ownerUUID) ?: return@Runnable
            if (owner.gameMode == GameMode.SPECTATOR) return@Runnable
            val mineItem = ItemStack(Material.FIREWORK_STAR)
            val meta = mineItem.itemMeta!!
            meta.displayName(pluginName("§fSticky Mine"))
            mineItem.itemMeta = meta
            val leftover = owner.inventory.addItem(mineItem)
            if (leftover.isNotEmpty()) {
                owner.world.dropItemNaturally(owner.location, leftover.values.first())
            }
        }, MINE_RETURN_DELAY)

        detonatingMines.remove(mineUUID)
    }

    private fun triggerNearbyMines(loc: Location) {
        for (mineUUID in stickyMines.toList()) {
            if (mineUUID in detonatingMines) continue
            val mine = Bukkit.getEntity(mineUUID) as? TNTPrimed ?: continue
            if (mine.location.distance(loc) <= MINE_CROSSBOW_TRIGGER_RADIUS) {
                detonateMine(mineUUID)
            }
        }
    }

    // ========== 3. SHORT FUSE TNT (BAT TNT) ==========

    @EventHandler
    fun onBatTntRightClick(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        if (!isBomberman(player)) return

        val item = player.inventory.itemInMainHand
        if (item.type != Material.TNT) return
        if (!item.hasPluginName("Short Fuse TNT")) return

        val block = event.clickedBlock ?: return
        val face = event.blockFace

        event.isCancelled = true

        val placeLoc = block.getRelative(face).location.add(0.5, 0.0, 0.5)

        val tnt = player.world.spawn(placeLoc, TNTPrimed::class.java)
        tnt.fuseTicks = BAT_TNT_FUSE
        tnt.source = player
        tagTnt(tnt, "BAT_TNT", player.uniqueId)
        trackTnt(player.uniqueId, tnt.uniqueId)

        item.amount -= 1

        player.playSound(placeLoc, Sound.ENTITY_TNT_PRIMED, 1.0f, 1.0f)
    }

    @EventHandler
    fun onBatTntPunch(event: PrePlayerAttackEntityEvent) {
        val player = event.player
        val tnt = event.attacked as? TNTPrimed ?: return

        val data = getTntData(tnt) ?: return
        if (data.first != "BAT_TNT") return
        if (data.second != player.uniqueId) return

        event.isCancelled = true

        // Kick the TNT in the player's look direction
        val lookDir = player.eyeLocation.direction.normalize()
        tnt.velocity = lookDir.multiply(BAT_TNT_KICK_SPEED)
        player.playSound(player.location, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.5f, 1.5f)
    }

    // ========== 4. UNIFIED EXPLOSION HANDLER ==========

    @EventHandler
    fun onTntExplode(event: EntityExplodeEvent) {
        val entity = event.entity
        val loc = entity.location

        // Check if this is a tagged bomberman TNT
        val data = getTntData(entity)
        if (data != null) {
            val type = data.first
            val ownerUUID = data.second

            // Cancel block damage for ALL bomberman TNT types
            event.blockList().clear()
            event.yield = 0f

            when (type) {
                "CROSSBOW_TNT" -> {
                    // Safety fuse fallback (normally explodes on impact via tickCrossbowTnt)
                    crossbowTntLastY.remove(entity.uniqueId)
                    applyExplosionDamage(loc, CROSSBOW_TNT_RADIUS, CROSSBOW_TNT_DAMAGE,
                        excludeOwner = ownerUUID, knockbackMultiplier = CROSSBOW_TNT_KB_MULTIPLIER)
                    untrackTnt(ownerUUID, entity.uniqueId)
                    triggerNearbyMines(loc)
                }
                "BAT_TNT" -> {
                    applyExplosionDamage(loc, BAT_TNT_RADIUS, BAT_TNT_DAMAGE, excludeOwner = null)
                    untrackTnt(ownerUUID, entity.uniqueId)
                    returnBatTntItem(ownerUUID)
                }
                "STICKY_MINE" -> {
                    // Safety fallback — sticky mines normally detonate via detonateMine()
                    applyExplosionDamage(loc, STICKY_MINE_RADIUS, STICKY_MINE_DAMAGE, excludeOwner = ownerUUID)
                    stickyMines.remove(entity.uniqueId)
                    untrackTnt(ownerUUID, entity.uniqueId)
                }
            }
        }

        // ANY explosion (tagged or not) near sticky mines should trigger them
        if (stickyMines.isNotEmpty()) {
            triggerNearbyMines(loc)
        }
    }

    private fun returnBatTntItem(ownerUUID: UUID) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val owner = Bukkit.getPlayer(ownerUUID) ?: return@Runnable
            if (owner.gameMode == GameMode.SPECTATOR) return@Runnable
            val tntItem = ItemStack(Material.TNT)
            val meta = tntItem.itemMeta!!
            meta.displayName(pluginName("§fShort Fuse TNT"))
            tntItem.itemMeta = meta
            val leftover = owner.inventory.addItem(tntItem)
            if (leftover.isNotEmpty()) {
                owner.world.dropItemNaturally(owner.location, leftover.values.first())
            }
        }, BAT_TNT_RETURN_DELAY)
    }

    // ========== 5. CLEANUP ==========

    fun clearPlayer(playerUUID: UUID) {
        val tntList = trackedTnt.remove(playerUUID) ?: return
        for (tntUUID in tntList) {
            stickyMines.remove(tntUUID)
            detonatingMines.remove(tntUUID)
            Bukkit.getEntity(tntUUID)?.remove()
        }
    }

    fun clearAll() {
        for ((_, tntList) in trackedTnt) {
            for (tntUUID in tntList) {
                Bukkit.getEntity(tntUUID)?.remove()
            }
        }
        trackedTnt.clear()
        stickyMines.clear()
        detonatingMines.clear()
        crossbowTntLastY.clear()
    }
}
