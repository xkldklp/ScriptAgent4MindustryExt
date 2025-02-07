@file:Depends("wayzer/cmds/restart", "计划重启")

package wayzer.ext

import arc.util.Interval
import arc.util.Log
import arc.util.serialization.Jval
import mindustry.core.Version
import mindustry.net.BeControl
import java.io.File
import java.net.URL
import java.time.LocalDateTime

name = "自动更新"

val enableUpdate by config.key(false, "是否开启自动更新")
val source by config.key("Anuken/Mindustry", "服务端来源，Github仓库")
val onlyInNight by config.key(false, "仅在凌晨自动更新", "本地时间1:00到7:00")
val useMirror by config.key(false, "使用镜像加速下载")
val mirror by config.key("https://gh.tinylake.tech", "GH镜像源")

suspend fun download(url: String, file: File): Int = runInterruptible {
    val steam = URL(url).openStream()
    val buffer = ByteArray(128 * 1024)//128KB
    val logInterval = Interval()
    var len = 0
    steam.use { input ->
        file.outputStream().use { output ->
            while (isActive) {
                val i = input.read(buffer)
                if (i == -1) break
                output.write(buffer, 0, i)
                len += i
                if (logInterval[60f])
                    logger.info("Downloaded ${len / 1024}KB")
            }
        }
    }
    len
}

onEnable {
    loop {
        if (enableUpdate) {
            if (!onlyInNight || LocalDateTime.now().hour in 1..6)
                try {
                    val txt =
                        URL("https://api.github.com/repos/$source/releases".let { if (useMirror) "$mirror/$it" else it }).readText()
                    val json = Jval.read(txt).asArray().first()
                    val newBuild = json.getString("tag_name", "")
                    val (version, revision) = ("$newBuild.0").removePrefix("v")
                        .split(".").map { it.toInt() }
                    if (version > Version.build || (version == Version.build && revision > Version.revision)) {
                        val asset = json.get("assets").asArray().find {
                            it.getString("name", "").contains("server", ignoreCase = true)
                        } ?: error("New version $newBuild, but can't find asset")
                        val url = asset.getString("browser_download_url", "")
                        try {
                            update(newBuild, url.let { if (useMirror) "$mirror/$it" else it })
                            cancel()
                        } catch (e: Throwable) {
                            logger.warning("下载更新失败: $e")
                            e.printStackTrace()
                        }
                    }
                } catch (e: Throwable) {
                    logger.warning("获取更新数据失败: $e")
                }
        }
        delay(5 * 60_000)//延时5分钟
    }
}

suspend fun update(version: String, url: String) {
    Log.info("发现新版本可用 $version 正在从 $url 下载")
    val dest = File(BeControl::class.java.protectionDomain.codeSource.location.toURI().path)
    val tmp = dest.resolveSibling("server-$version.jar.tmp")
    val size = try {
        download(url, tmp)
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
    Log.info("新版本 $version 下载完成: ${size / 1024}KB")
    contextScript<wayzer.cmds.Restart>().scheduleRestart("新版本更新 $version") {
        dest.outputStream().use { output ->
            tmp.inputStream().use { it.copyTo(output) }
            output.flush()
        }
        tmp.delete()
        Log.info(
            "&lcVersion downloaded, exiting. Note that if you are not using a auto-restart script, the server will not restart automatically."
        )
    }
}

command("forceUpdate", "强制更新服务器版本") {
    permission = dotId
    usage = "<url>"
    body {
        arg.firstOrNull()?.let { kotlin.runCatching { URL(it) }.getOrNull() } ?: replyUsage()
        reply("[green]正在后台处理中".with())
        launch {
            try {
                update("管理员手动升级", arg.first())
            } catch (e: Throwable) {
                reply("[red]升级失败{e}".with("e" to e))
                e.printStackTrace()
            }
        }
    }
}