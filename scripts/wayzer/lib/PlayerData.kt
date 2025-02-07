package wayzer.lib

import com.google.common.cache.CacheBuilder
import mindustry.gen.Player
import mindustry.net.Packets.ConnectPacket
import java.time.Duration

class PlayerData(val name: String, val uuid: String, val ids: Set<String> = mutableSetOf(uuid)) {
    var player: Player? = null
    var id: String = uuid
        private set
    val authed get() = id !== uuid

    fun addId(id: String, asPrimary: Boolean) {
        (ids as MutableSet).add(id)
        if (asPrimary) this.id = id
    }

    val idsInDB = ids.joinToString("$", "$", "$") { it }

    companion object {
        val history = CacheBuilder.newBuilder()
            .expireAfterWrite(Duration.ofDays(1))
            .build<String, PlayerData>()!!
        private val preOnline = mutableMapOf<String, PlayerData>()
        private val online = mutableMapOf<Player, PlayerData>()

        fun forAuth(packet: ConnectPacket) = preOnline.getOrPut(packet.usid) {
            PlayerData(packet.name, packet.uuid)
        }

        operator fun get(player: Player): PlayerData = online.getOrPut(player) {
            if (player.con == null) error("player is not online")
            (preOnline.remove(player.usid()) ?: PlayerData(player.plainName(), player.uuid()))
                .also { it.player = player }
        }

        fun onLeave(player: Player) {
            val data = get(player)
            online.remove(player)
            history.put(player.uuid(), data)
            data.player = null
        }
    }
}