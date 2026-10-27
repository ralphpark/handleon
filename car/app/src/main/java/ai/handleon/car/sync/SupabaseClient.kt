package ai.handleon.car.sync

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class Session(val userId: String, val accessToken: String, val refreshToken: String, val expiresAtSec: Long)
interface TokenStore { fun load(): Session?; fun save(session: Session?) }
class SupabaseException(val status: Int, val code: String?) : Exception("supabase $status ${code ?: ""}")

/** Only projected identifiers leave this boundary as errors; never attach a response or cause. */
class SupabaseClient(
    baseUrl: String,
    private val anonKey: String,
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val configured = baseUrl.isNotBlank() && anonKey.isNotBlank()
    private val base = if (configured) baseUrl.trimEnd('/').toHttpUrl() else null
    private val sessionMutex = Mutex()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun ensureSession(): String = currentSession().userId

    private suspend fun currentSession(): Session = sessionMutex.withLock {
        if (!configured) throw SupabaseException(0, "not_configured")
        val saved = tokens.load()
        when {
            saved == null -> authenticate(null)
            saved.expiresAtSec <= nowSec() + 60 -> authenticate(saved)
            else -> saved
        }
    }

    private suspend fun authenticate(previous: Session?): Session {
        val url = if (previous == null) url("auth/v1/signup")
            else url("auth/v1/token").newBuilder().addQueryParameter("grant_type", "refresh_token").build()
        val body = buildJsonObject { previous?.let { put("refresh_token", it.refreshToken) } }
        val reply = execute(Request.Builder().url(url).header("apikey", anonKey)
            .post(body.toString().toRequestBody(mediaType)).build())
        if (reply.status !in 200..299) {
            val error = failure(reply, previous)
            if (previous != null && reply.status in listOf(400, 401)) tokens.save(null)
            throw error
        }
        val json = parse(reply).objectOrFail()
        val session = try {
            Session(json.getValue("user").jsonObject.getValue("id").jsonPrimitive.content,
                json.getValue("access_token").jsonPrimitive.content,
                json.getValue("refresh_token").jsonPrimitive.content,
                json["expires_at"]?.jsonPrimitive?.longOrNull ?: (nowSec() + json.getValue("expires_in").jsonPrimitive.long))
                .also { if (it.userId.isBlank() || it.accessToken.isBlank() || it.refreshToken.isBlank()) throw IllegalArgumentException() }
        } catch (_: Exception) { throw SupabaseException(reply.status, "invalid_response") }
        tokens.save(session)
        return session
    }

    suspend fun rpc(name: String, body: JsonObject = JsonObject(emptyMap())): JsonElement {
        val reply = authorized("POST", url("rest/v1/rpc/$name"), body)
        return if (reply.body.isBlank()) JsonNull else parse(reply)
    }

    suspend fun select(table: String, query: String): JsonArray =
        parse(authorized("GET", url("rest/v1/$table").newBuilder().encodedQuery(query).build())).arrayOrFail()

    suspend fun upsert(table: String, rows: JsonArray, onConflict: String) {
        authorized("POST", url("rest/v1/$table").newBuilder().addQueryParameter("on_conflict", onConflict).build(), rows,
            "resolution=merge-duplicates,return=minimal")
    }

    suspend fun invoke(fn: String, body: JsonObject): JsonObject =
        parse(authorized("POST", url("functions/v1/$fn"), body)).objectOrFail()

    suspend fun listObjects(bucket: String, prefix: String): List<String> {
        val paths = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = parse(authorized("POST", url("storage/v1/object/list/$bucket"), buildJsonObject {
                put("prefix", prefix); put("limit", 100); put("offset", offset)
                put("sortBy", buildJsonObject { put("column", "name"); put("order", "asc") })
            })).arrayOrFail()
            page.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                // Supabase directory entries have an explicit null id. Never treat folders as files.
                if (obj["id"] == JsonNull || name.isBlank() || name == "." || name == ".." || '/' in name) return@forEach
                paths += prefix.trimEnd('/') + "/" + name
            }
            if (page.size < 100) return paths
            offset += page.size
        }
    }

    suspend fun download(bucket: String, path: String): String =
        authorized("GET", url("storage/v1/object/authenticated/$bucket/$path")).body

    suspend fun delete(bucket: String, path: String) {
        authorized("DELETE", url("storage/v1/object/$bucket/$path"))
    }

    private fun url(path: String): HttpUrl = (base ?: throw SupabaseException(0, "not_configured"))
        .newBuilder().apply { path.split('/').filter(String::isNotEmpty).forEach(::addPathSegment) }.build()

    private suspend fun authorized(method: String, url: HttpUrl, body: JsonElement? = null, prefer: String? = null): Reply {
        var session = currentSession()
        repeat(2) { attempt ->
            val request = Request.Builder().url(url).header("apikey", anonKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .apply { prefer?.let { header("Prefer", it) } }
                .method(method, body?.toString()?.toRequestBody(mediaType)).build()
            val reply = execute(request)
            if (reply.status in 200..299) return reply
            if (reply.status != 401 || attempt == 1) throw failure(reply, session)
            session = sessionMutex.withLock {
                val saved = tokens.load() ?: throw failure(reply, session)
                if (saved.accessToken != session.accessToken) saved else authenticate(saved)
            }
        }
        throw SupabaseException(401, null)
    }

    private data class Reply(val status: Int, val body: String)
    private suspend fun execute(request: Request): Reply = withContext(Dispatchers.IO) {
        ensureActive()
        try {
            http.newCall(request).execute().use { response -> Reply(response.code, response.body?.string().orEmpty()) }
        } catch (_: IOException) { throw SupabaseException(0, "network_error") }
    }

    private fun parse(reply: Reply): JsonElement = try { Json.parseToJsonElement(reply.body) }
        catch (_: Exception) { throw SupabaseException(reply.status, "invalid_response") }
    private fun JsonElement.objectOrFail() = this as? JsonObject ?: throw SupabaseException(200, "invalid_response")
    private fun JsonElement.arrayOrFail() = this as? JsonArray ?: throw SupabaseException(200, "invalid_response")
    private fun failure(reply: Reply, session: Session?): SupabaseException {
        val secrets = listOfNotNull(anonKey, session?.accessToken, session?.refreshToken).filter(String::isNotBlank)
        val obj = try { Json.parseToJsonElement(reply.body) as? JsonObject } catch (_: Exception) { null }
        val candidates = listOf("code", "error", "message").asSequence()
            .mapNotNull { (obj?.get(it) as? JsonPrimitive)?.contentOrNull }
            .filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && secrets.none { secret -> secret in it } }.toList()
        // These exact public RPC codes are in message when PostgREST also supplies SQLSTATE P0001.
        val code = candidates.firstOrNull { it in setOf("round_changed", "fed_opt_in_required") } ?: candidates.firstOrNull()
        return SupabaseException(reply.status, code)
    }
}
