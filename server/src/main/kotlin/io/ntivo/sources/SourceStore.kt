package io.ntivo.sources

import io.ntivo.model.Source
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed list of sources. Loads [file] on construction and rewrites the
 * whole list on every change. All access is synchronized on this instance.
 */
class SourceStore(private val file: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Source.serializer())

    // Insertion order is kept so the UI lists sources in the order they were connected
    private val sources = LinkedHashMap<String, Source>()

    init {
        if (file.isFile && file.length() > 0) {
            json.decodeFromString(serializer, file.readText()).forEach { sources[it.id] = it }
        }
    }

    @Synchronized
    fun all(): List<Source> = sources.values.toList()

    @Synchronized
    fun get(id: String): Source? = sources[id]

    @Synchronized
    fun put(source: Source) {
        sources[source.id] = source
        save()
    }

    @Synchronized
    fun remove(id: String) {
        if (sources.remove(id) != null) save()
    }

    /** Applies [f] to the current value and stores the result. Throws if [id] is unknown. */
    @Synchronized
    fun update(id: String, f: (Source) -> Source): Source {
        val current = sources[id] ?: throw NoSuchElementException("Unknown source '$id'")
        val next = f(current)
        sources[id] = next
        save()
        return next
    }

    private fun save() {
        file.absoluteFile.parentFile?.mkdirs()
        // Write to a sibling file and move it in place, so a crash never leaves half a file
        val tmp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, sources.values.toList()))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
