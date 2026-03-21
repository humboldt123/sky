package me.humboldt123.sky.util

import me.humboldt123.sky.Sky
import org.bukkit.NamespacedKey

object Keys {
    lateinit var SHIELD_MAX_USES: NamespacedKey
    lateinit var SOUL_HEAD: NamespacedKey
    lateinit var MINION_OWNER: NamespacedKey
    lateinit var TAGGED_TNT: NamespacedKey
    lateinit var BOUNCE_STAND: NamespacedKey
    lateinit var MANAGED_GHAST: NamespacedKey
    lateinit var CRYSTAL_FIREBALL: NamespacedKey
    lateinit var COOLDOWN_ID: NamespacedKey
    lateinit var SHIELD_REMAINING: NamespacedKey

    fun init(plugin: Sky) {
        SHIELD_MAX_USES = NamespacedKey(plugin, "shield_max_uses")
        SHIELD_REMAINING = NamespacedKey(plugin, "shield_remaining")
        SOUL_HEAD = NamespacedKey(plugin, "soul_head")
        MINION_OWNER = NamespacedKey(plugin, "minion_owner")
        TAGGED_TNT = NamespacedKey(plugin, "tagged_tnt")
        BOUNCE_STAND = NamespacedKey(plugin, "bounce_stand")
        MANAGED_GHAST = NamespacedKey(plugin, "managed_ghast")
        CRYSTAL_FIREBALL = NamespacedKey(plugin, "crystal_fireball")
        COOLDOWN_ID = NamespacedKey(plugin, "cooldown_id")
    }
}
