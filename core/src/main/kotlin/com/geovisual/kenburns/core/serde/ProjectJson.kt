package com.geovisual.kenburns.core.serde

import com.geovisual.kenburns.core.model.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The file is unreadable, or not a project at all. */
class ProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The file was written by a newer app version than this one. */
class UnsupportedVersionException(val found: Int, val supported: Int) :
    Exception("This project was saved by a newer version of the app (format $found, this app reads up to $supported).")

/** Versioned project JSON. The version is read before anything else, so old and new shapes never mix. */
object ProjectJson {
    const val CURRENT_VERSION = 2

    /**
     * One step per version: key N upgrades a version N object to version N + 1.
     * v1 -> v2: SLIDE was added to TransitionType. No shape change; the bump only lets older app
     * builds report "made with a newer version" instead of a generic parse error.
     */
    val MIGRATIONS: Map<Int, (JsonObject) -> JsonObject> = mapOf(1 to { o -> o })

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(project: Project): String =
        json.encodeToString(Project.serializer(), project.copy(schemaVersion = CURRENT_VERSION))

    fun decode(text: String, migrations: Map<Int, (JsonObject) -> JsonObject> = MIGRATIONS, current: Int = CURRENT_VERSION): Project {
        var obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProjectFormatException("Project file is not valid JSON", e)
        }
        var version = try {
            obj["schemaVersion"]?.jsonPrimitive?.int ?: throw ProjectFormatException("Project file has no schemaVersion")
        } catch (e: ProjectFormatException) {
            throw e
        } catch (e: Exception) {
            throw ProjectFormatException("Project file has a bad schemaVersion", e)
        }
        if (version > current) throw UnsupportedVersionException(version, current)
        if (version < 1) throw ProjectFormatException("Project file has a bad schemaVersion ($version)")
        while (version < current) {
            val step = migrations[version] ?: throw ProjectFormatException("No migration from format $version")
            obj = JsonObject(step(obj) + ("schemaVersion" to JsonPrimitive(version + 1)))
            version++
        }
        return try {
            json.decodeFromJsonElement(Project.serializer(), obj)
        } catch (e: Exception) {
            throw ProjectFormatException("Project file could not be read: ${e.message}", e)
        }
    }
}
