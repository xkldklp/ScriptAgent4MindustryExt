package wayzer.user.lib

import cf.wayzer.placehold.PlaceHoldApi
import cf.wayzer.scriptAgent.depends
import cf.wayzer.scriptAgent.import
import cf.wayzer.scriptAgent.thisContextScript
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.Dispatchers
import mindustry.gen.Player
import java.time.Duration

object UserService {
    private val module = thisContextScript()
    internal val profiles = mutableMapOf<String, PlayerProfile>()//usid->profile
    val snapshots = CacheBuilder.newBuilder()
        .expireAfterWrite(Duration.ofDays(1))
        .build<String, PlayerSnapshot>()

    fun getLevel(profile: PlayerProfile): Int {
        return PlaceHoldApi.GlobalContext.resolveVar(this, "level") as Int
    }

    fun secureProfile(player: Player) = profiles[player.usid()]
    fun getId(player: Player): String = secureProfile(player)?.id?.toString() ?: player.uuid()
    fun getAllId(player: Player): Set<String> = setOf(getId(player), player.uuid())

    /** Should call in [Dispatchers.IO] */
    fun updateExp(profile: PlayerProfile, exp: Int, desc: String = "") {
        val impl = module.depends("wayzer/user/level")?.import<(PlayerProfile, String, Int) -> Unit>("updateExp")
        if (impl == null) {
            module.logger.severe("updateExp(${profile.qq},$exp)")
            error("经验等级系统不可用,请联系管理员")
        }
        impl(profile, desc, exp)
    }

    /**
     * Should call in [Dispatchers.IO]
     * @param message 字符串模板
     * @param params 字符串变量,因为要存入数据库,仅支持字符串 例外自带变量player指向profile对应玩家
     * @param broadcast 在所有profile在线的服务器广播,否则只发给个人
     */
    fun notify(profile: PlayerProfile, message: String, params: Map<String, String>, broadcast: Boolean = false) {
        val impl = module.depends("wayzer/user/notification")
            ?.import<(PlayerProfile, String, Map<String, String>, Boolean) -> Unit>("notify")
        if (impl == null) {
            module.logger.severe("finishAchievement(${profile.qq},$message,$params,$broadcast)")
            error("通知系统不可用,请联系管理员")
        }
        impl(profile, message, params, broadcast)
    }

    /** Should call in [Dispatchers.IO] */
    fun finishAchievement(profile: PlayerProfile, name: String, exp: Int, broadcast: Boolean = false) {
        val impl = module.depends("wayzer/user/achievement")
            ?.import<(PlayerProfile, String, Int, Boolean) -> Unit>("finishAchievement")
        if (impl == null) {
            module.logger.severe("finishAchievement(${profile.qq},$name,$exp,$broadcast)")
            error("成就系统不可用,请联系管理员")
        }
        impl(profile, name, exp, broadcast)
    }
}