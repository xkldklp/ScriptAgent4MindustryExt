package coreLibrary.commands

import cf.wayzer.scriptAgent.impl.ScriptCache
import cf.wayzer.scriptAgent.util.CASScriptPack
import cf.wayzer.scriptAgent.util.MetadataFile

suspend inline fun runIgnoreCancel(sync: Boolean, crossinline body: suspend () -> Unit) {
    val job = launch(Job()) { body() }
    if (sync) job.join()
}

onEnable {
    Commands.controlCommand += CommandInfo(this, "scan", "重新扫描脚本") {
        permission = "scriptAgent.control.scan"
        aliases = listOf("扫描")
        body {
            val old = ScriptRegistry.allScripts { true }.size
            ScriptRegistry.scanRoot()
            val now = ScriptRegistry.allScripts { true }.size
            reply("[green]扫描完成,新发现{count}脚本".with("count" to (now - old)))
        }
    }
    Commands.controlCommand += CommandInfo(this, "list", "列出所有模块或模块内所有脚本") {
        usage = "[module/fail]"
        permission = "scriptAgent.control.list"
        aliases = listOf("ls", "列出")
        onComplete {
            onComplete(0) {
                ScriptRegistry.allScripts().map { it.id.substringBefore(Config.idSeparator) }
                    .toSet().sortedBy { it }
            }
        }
        body {
            val module = arg.getOrNull(0) ?: kotlin.run {
                val counts = ScriptRegistry.allScripts().map { it.id.substringBefore(Config.idSeparator) }
                    .groupBy { it }.mapValues { it.value.size }
                val list = counts.entries.sortedBy { it.key }
                    .map { "[purple]${(it.key + "...").padEnd(20)} [blue]${it.value}" }
                returnReply("[yellow]==== [light_yellow]已加载模块[yellow] ====\n{list:\n}".with("list" to list))
            }
            val list = ScriptRegistry.allScripts {
                if (module.equals("fail", true)) it.failReason != null
                else it.id.startsWith(module + Config.idSeparator)
            }.map {
                if (it.enabled) "[purple][${it.scriptState}] ${it.id}"
                else "[reset][${it.scriptState}] ${it.id.padEnd(30)} ${it.failReason.orEmpty()}"
            }
            reply(
                "[yellow]==== [light_yellow]{module}脚本[yellow] ====\n{list:\n}".with(
                    "module" to module, "list" to list
                )
            )
        }
    }
    Commands.controlCommand += CommandInfo(this, "load", "(重新)加载一个脚本或者模块") {
        usage = "<module[/script]> [--noCache] [--noEnable] [--async]"
        permission = "scriptAgent.control.load"
        aliases = listOf("reload", "加载", "重载")
        onComplete {
            onComplete(0) { ScriptRegistry.allScripts { true }.map { it.id } }
        }
        body {
            val noCache = checkArg("--noCache")
            var noEnable = checkArg("--noEnable")
            val async = checkArg("--async")

            if (arg.isEmpty()) replyUsage()
            val script = ScriptRegistry.findScriptInfo(arg[0])
                ?: returnReply("[red]找不到模块或者脚本".with())
            if (noCache) {
                val file = Config.cacheFile(script.id)
                reply("[yellow]清理cache文件{name}".with("name" to file.name))
                file.delete()
            }
            runIgnoreCancel(!async) {
                ScriptManager.transaction {
                    add(script)
                    //因为其他原因本来就保持loaded
                    if (script.run { failReason == null && scriptState.loaded && !scriptState.enabled })
                        noEnable = true
                    unload(addAllAffect = true)
                    load()
                    if (!noEnable) enable()
                }
                script.failReason?.let {
                    reply("[red]加载失败({state}): {reason}".with("state" to script.scriptState, "reason" to it))
                } ?: reply("[green]重载成功: {state}".with("state" to script.scriptState))
            }
        }
    }
    Commands.controlCommand += CommandInfo(this, "enable", "(重新)启用一个脚本或者模块") {
        usage = "<module[/script]> [--async]"
        permission = "scriptAgent.control.enable"
        aliases = listOf("启用")
        onComplete {
            onComplete(0) { ScriptRegistry.allScripts { it.scriptState.loaded }.map { it.id } }
        }
        body {
            val async = checkArg("--async")
            if (arg.isEmpty()) replyUsage()
            val script = ScriptRegistry.getScriptInfo(arg[0])
                ?: returnReply("[red]找不到模块或者脚本".with())
            runIgnoreCancel(!async) {
                ScriptManager.transaction {
                    add(script)
                    disable(addAllAffect = true)
                    enable()
                }
                val success = script.scriptState.enabled
                reply((if (success) "[green]启用成功" else "[red]加载失败").with())
            }
        }
    }
    Commands.controlCommand += CommandInfo(this, "unload", "卸载一个脚本或者模块") {
        usage = "<module[/script]> [--async]"
        permission = "scriptAgent.control.unload"
        aliases = listOf("卸载")
        onComplete {
            onComplete(0) { ScriptRegistry.allScripts { it.scriptState.loaded }.map { it.id } }
        }
        body {
            val async = checkArg("--async")
            if (arg.isEmpty()) replyUsage()
            val script = ScriptRegistry.getScriptInfo(arg[0]) ?: returnReply("[red]找不到模块或者脚本".with())

            runIgnoreCancel(!async) {
                ScriptManager.unloadScript(script)
                if (script.scriptState == ScriptState.ToLoad)
                    script.scriptInfo.stateUpdate(ScriptState.Found)
                reply("[green]关闭脚本成功".with())
            }
        }
    }
    Commands.controlCommand += CommandInfo(this, "disable", "关闭一个脚本或者模块") {
        usage = "<module[/script]> [--async]"
        permission = "scriptAgent.control.disable"
        aliases = listOf("关闭")
        onComplete {
            onComplete(0) { ScriptRegistry.allScripts { it.scriptState.enabled }.map { it.id } }
        }
        body {
            val async = checkArg("--async")
            if (arg.isEmpty()) replyUsage()
            val script = ScriptRegistry.getScriptInfo(arg[0]) ?: returnReply("[red]找不到模块或者脚本".with())

            runIgnoreCancel(!async) {
                ScriptManager.disableScript(script)
                if (script.scriptState == ScriptState.ToEnable)
                    script.scriptInfo.stateUpdate(ScriptState.Loaded)
                reply("[green]关闭脚本成功".with())
            }
        }
    }
    Commands.controlCommand += CommandInfo(this, "genMetadata", "生成供开发使用的元数据") {
        permission = "scriptAgent.control.genMetadata"
        body {
            withContext(Dispatchers.Default) {
                val all = ScriptRegistry.allScripts { it.children(true).isNotEmpty() }
                    .mapNotNull { it.compiledScript }
                reply("[yellow]共{size}待生成".with("size" to all.size))
                Config.metadataDir.mkdirs()
                all.forEach { info ->
                    Config.metadataFile(info.scriptInfo.id).writer().use {
                        val meta = ScriptCache.asMetadata(info)
                        MetadataFile(meta.id, meta.attr - "SOURCE_MD5", meta.data).writeTo(it)
                    }
                }
                reply("[green]生成完成".with())
            }
        }
    }
    Commands.controlCommand += CommandInfo(this, "packModule", "打包模块") {
        usage = "<module>"
        permission = "scriptAgent.control.packModule"
        body {
            val module = arg.getOrNull(0) ?: replyUsage()
            val scripts = ScriptRegistry.allScripts { it.id.startsWith("$module/") }
                .mapNotNull { it.compiledScript }
            CASScriptPack.Packer(Config.cacheDir.resolve("$module.packed.zip").outputStream())
                .use { scripts.forEach(it::add) }
        }
    }
}