@file:Depends("wayzer/maps", "监测投票换图")

package wayzer.user.ext

import cf.wayzer.placehold.DynamicVar
import cf.wayzer.placehold.PlaceHoldApi
import mindustry.game.Team
import wayzer.MapChangeEvent
import java.io.Serializable
import java.time.Duration
import kotlin.math.ceil

class GameoverStatisticsEvent(
    var data: List<StatisticsData>//sorted by score
) : Event {
    companion object : Event.Handler()
}

data class StatisticsData(
    val uuid: String,
    var name: String = "",
    var playedTime: Int = 0,
    var idleTime: Int = 0,
    var breakBlock: Int = 0,
    @Transient var pvpTeam: Team = Team.sharded,
    @Transient var profile: PlayerProfile? = null,
) : Serializable {
    //set when gameover
    var win: Boolean = false
    var score: Double = 0.0
    var exp: Int = 0

    fun cal(winTeam: Team) {
        win = state.rules.pvp && pvpTeam == winTeam
        score = playedTime - 0.8 * idleTime +
                if (win) 600 * (1 - idleTime / playedTime) else 0
        exp = ceil((score * 15 / 3600).coerceAtMost(60.0)).toInt()//3600点积分为15,40封顶
    }
}

val Player.active
    get() = depends("wayzer/user/ext/activeCheck")
        ?.import<(Player) -> Int>("inactiveTime")
        ?.let { it(this) < 5000 } ?: true

//region Data
@Savable
val statisticsData = mutableMapOf<String, StatisticsData>()
val Player.data get() = statisticsData.getOrPut(uuid()) { StatisticsData(uuid()) }
customLoad(::statisticsData) { statisticsData += it }
listen<EventType.ResetEvent> { statisticsData.clear() }

registerVarForType<StatisticsData>().apply {
    registerChild("playedTime", "本局在线时间", DynamicVar.obj { Duration.ofSeconds(it.playedTime.toLong()) })
    registerChild("idleTime", "本局在线时间", DynamicVar.obj { Duration.ofSeconds(it.idleTime.toLong()) })
    registerChild("score", "综合得分", DynamicVar.obj { it.score })
    registerChild("breakBlock", "破坏方块数", DynamicVar.obj { it.breakBlock })
}
registerVarForType<Player>().apply {
    registerChild("statistics", "游戏统计数据", DynamicVar.obj { it.data })
}
onDisable {
    PlaceHoldApi.resetTypeBinder(StatisticsData::class.java)//局部类，防止泄漏
}
//endregion

listen<EventType.PlayerJoin> {
    it.player.data.pvpTeam = it.player.team()
}
listen<EventType.WorldLoadEvent> {
    launch(Dispatchers.gamePost) {
        Groups.player.forEach { p ->
            p.data.pvpTeam = p.team()
        }
    }
}

onEnable {
    loop(Dispatchers.game) {
        delay(1000)
        Groups.player.forEach {
            it.data.name = it.info.lastName
            if (it.data.profile == null)
                it.data.profile = UserService.secureProfile(it)
            if (!it.dead() && it.active)
                it.data.playedTime++
        }
    }
}

//region gameOver
listen<EventType.GameOverEvent> { event ->
    onGameOver(event.winner)
}

fun onGameOver(winner: Team) {
    val gameTime by PlaceHold.reference<Duration>("state.gameTime")
    if (state.rules.infiniteResources || state.rules.editor) {
        return broadcast(
            """
            [yellow]地图: [{map.id}]{map.name}[yellow]
            [yellow]总游戏时长: {state.mapTime:分钟}
            [yellow]本局游戏时长: {state.gameTime:分钟}
            [yellow]沙盒或编辑器模式,不计算贡献
        """.trimIndent().with()
        )
    }

    launch(Dispatchers.game) {
        val sortedData = statisticsData.values
            .filter { it.playedTime > 60 }
            .onEach { it.cal(winner) }
            .sortedByDescending { it.score }
            .let { GameoverStatisticsEvent(it).emitAsync().data }
        statisticsData.clear()

        val totalTime = sortedData.sumOf { it.playedTime - it.idleTime }
        val list = sortedData.map {
            "{pvpState}{name}[white]({statistics.playedTime:分钟})".with(
                "name" to it.name, "statistics" to it, "pvpState" to if (it.win) "[green][胜][]" else ""
            )
        }
        broadcast(
            """
            [yellow]地图: [white][{map.id}]{map.name}
            [yellow]总游戏时长: [white]{state.mapTime:分钟}
            [yellow]本局游戏时长: [white]{state.gameTime:分钟}
            [yellow]有效总贡献时长: [white]{totalTime:分钟}
            [yellow]贡献排行榜(时长): [white]{list}
            """.trimIndent()
                .with("totalTime" to Duration.ofSeconds(totalTime.toLong()), "list" to list)
        )

        if (sortedData.isNotEmpty() && gameTime > Duration.ofMinutes(15)) {
            sortedData.groupBy { it.profile }
                .forEach { (profile, data) ->
                    if (profile == null) return@forEach
                    val best = data.maxBy { it.score }
                    UserService.updateExp(profile, best.exp, "游戏结算")
                }
        }
    }
}
listenTo<MapChangeEvent>(Event.Priority.Watch) {
    if (statisticsData.none { it.value.playedTime > 60 }) return@listenTo
    onGameOver(Team.derelict)
}
//endregion