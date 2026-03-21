package me.humboldt123.sky.abilities

import java.util.UUID

class CooldownManager {
    // UUID -> (abilityName -> expiryTimeMillis)
    private val cooldowns = mutableMapOf<UUID, MutableMap<String, Long>>()

    fun isOnCooldown(playerId: UUID, ability: String): Boolean {
        val expiry = cooldowns[playerId]?.get(ability) ?: return false
        if (System.currentTimeMillis() >= expiry) {
            cooldowns[playerId]?.remove(ability)
            return false
        }
        return true
    }

    fun getRemainingSeconds(playerId: UUID, ability: String): Int {
        val expiry = cooldowns[playerId]?.get(ability) ?: return 0
        val remaining = expiry - System.currentTimeMillis()
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    fun setCooldown(playerId: UUID, ability: String, durationTicks: Long) {
        val durationMs = durationTicks * 50L
        cooldowns.getOrPut(playerId) { mutableMapOf() }[ability] = System.currentTimeMillis() + durationMs
    }

    fun resetAll(playerId: UUID) {
        cooldowns.remove(playerId)
    }

    fun clearAll() {
        cooldowns.clear()
    }
}
