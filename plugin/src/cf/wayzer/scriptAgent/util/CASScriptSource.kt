package cf.wayzer.scriptAgent.util

import cf.wayzer.scriptAgent.define.SAExperimentalApi
import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptResourceFile
import cf.wayzer.scriptAgent.define.ScriptSource
import java.io.File
import java.net.URL

@SAExperimentalApi
abstract class CASScriptSource(
    override val scriptInfo: ScriptInfo,
    val hash: String,
    val resourceHashes: Map<String, String>,
) : ScriptSource.Compiled {
    abstract fun getCAS(hash: String): URL?
    class ResourceImpl(
        override val name: String,
        private val hash: String,
        override val url: URL
    ) : ScriptResourceFile {
        override fun loadFile(): File = CAStore.getOrLoad(hash, url)
    }

    override fun listResources(): Collection<ScriptResourceFile> = resourceHashes
        .mapNotNull { getCAS(it.value)?.let { url -> ResourceImpl(it.key, it.value, url) } }

    override fun findResource(name: String): ScriptResourceFile? {
        val hash = resourceHashes[name] ?: return null
        val url = getCAS(hash) ?: return null
        return ResourceImpl(name, hash, url)
    }


    override fun compiledValid(): Boolean = getCAS(hash) != null
    override fun loadCompiled(): File = getCAS(hash)?.let { CAStore.getOrLoad(hash, it) }
        ?: error("Can't load compiled script")

    constructor(meta: MetadataFile) : this(
        ScriptInfo.getOrCreate(meta.id),
        meta.attr["HASH"] ?: error("Break META: ${meta.id}, require hash"),
        meta.data["RESOURCE"].orEmpty().associate {
            val (name, hash) = it.split(' ', limit = 2)
            name to hash
        }
    )
}