package cf.wayzer.scriptAgent.util

import cf.wayzer.scriptAgent.define.ScriptInfo
import cf.wayzer.scriptAgent.define.ScriptResourceFile
import cf.wayzer.scriptAgent.define.ScriptSource
import cf.wayzer.scriptAgent.impl.SACompiledScript
import cf.wayzer.scriptAgent.impl.ScriptCache
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

abstract class CASScriptPack {
    class Zip(val file: File) : CASScriptPack(), AutoCloseable {
        val zip = java.util.zip.ZipFile(file)
        override fun loadMeta(): List<MetadataFile> {
            return zip.getEntry("META")
                ?.let { zip.getInputStream(it) }?.bufferedReader()
                ?.use { MetadataFile.readAll(it.lineSequence().iterator()) }.orEmpty()
        }

        override fun getCAS(hash: String): URL {
            return URL(null, "jar:file:${file.absolutePath}!/CAS/$hash", object : URLStreamHandler() {
                override fun openConnection(url: URL) = object : URLConnection(url) {
                    override fun connect() {}
                    override fun getInputStream(): InputStream = zip.getInputStream(zip.getEntry("CAS/$hash"))
                }
            })
        }

        override fun close() {
            zip.close()
        }
    }

    abstract fun loadMeta(): List<MetadataFile>
    abstract fun getCAS(hash: String): URL
    fun loadResFile(md5: String): File {
        return CAStore.getOrLoad(md5) { f ->
            f.outputStream().use { out ->
                getCAS(md5).openStream().use { it.copyTo(out) }
            }
        }
    }

    inner class ScriptResourceFileImpl(
        override val name: String,
        private val md5: String
    ) : ScriptResourceFile {
        override val url: URL get() = getCAS(md5)
        override fun loadFile(): File = loadResFile(md5)
    }

    inner class ScriptSourceImpl(
        override val scriptInfo: ScriptInfo,
        private val scriptMd5: String,
        resources: List<ScriptResourceFile>,
    ) : ScriptSource.Compiled {
        private val resources = resources.associateBy { it.name }
        override fun listResources(): Collection<ScriptResourceFile> = resources.values
        override fun findResource(name: String): ScriptResourceFile? = resources[name]

        override fun compiledValid(): Boolean = true
        override fun loadCompiled(): File = loadResFile(scriptMd5)
    }

    open fun load(): List<ScriptSource> = loadMeta().map { meta ->
        val md5 = meta.attr["HASH"] ?: error("Break META: ${meta.id}, require hash")
        val res = meta.data["RESOURCE"].orEmpty().map {
            val (name, hash) = it.split(' ', limit = 2)
            ScriptResourceFileImpl(name, hash)
        }
        ScriptSourceImpl(ScriptInfo.getOrCreate(meta.id), md5, res)
    }

    class Packer(stream: OutputStream) : AutoCloseable {
        val metas = mutableListOf<MetadataFile>()
        private val added = mutableSetOf<String>()
        private val digest = MessageDigest.getInstance("MD5")!!
        private val zip = ZipOutputStream(stream)

        fun addCAS(bs: ByteArray): String {
            @OptIn(ExperimentalStdlibApi::class)
            val hash = digest.digest(bs).toHexString()
            if (added.add(hash)) {
                zip.putNextEntry(ZipEntry("CAS/$hash"))
                zip.write(bs)
                zip.closeEntry()
            }
            return hash
        }

        override fun close() {
            zip.putNextEntry(ZipEntry("META"))
            zip.bufferedWriter().let { f ->
                metas.sortedBy { it.id }
                metas.forEach { it.writeTo(f) }
                f.flush()
            }
            zip.closeEntry()
            zip.close()
        }

        fun add(script: SACompiledScript) {
            val scriptMD5 = addCAS(script.compiledFile.readBytes())
            val resources = script.source.listResources()
                .map { it.name to addCAS(it.loadFile().readBytes()) }
                .sortedBy { it.first }
                .map { "${it.first} ${it.second}" }

            val meta = ScriptCache.asMetadata(script)
            metas += meta.copy(
                attr = meta.attr + ("HASH" to scriptMD5),
                data = meta.data + ("RESOURCE" to resources)
            )
        }
    }
}