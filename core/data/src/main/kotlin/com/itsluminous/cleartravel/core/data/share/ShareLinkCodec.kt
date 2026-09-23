package com.itsluminous.cleartravel.core.data.share

import com.itsluminous.cleartravel.core.model.EntityIds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** The `<kind>` + `<blob>` parts of a recognised share link, before decoding. */
data class ShareLink(
    val kind: ShareKind,
    val blob: String,
)

/** Why a share blob could not be turned into a payload — always typed, never a crash. */
enum class ShareLinkError {
    /** Not base64, not deflate, not JSON, or missing mandatory fields. */
    CORRUPTED,

    /** The payload's `v` is newer than this build understands. */
    UNSUPPORTED_VERSION,

    /** Well-formed but semantically unusable (malformed UUIDs, blank name, wrong kind). */
    INVALID_CONTENT,
}

sealed interface ShareDecodeResult {
    data class Ok(
        val payload: SharePayload,
    ) : ShareDecodeResult

    data class Failed(
        val error: ShareLinkError,
    ) : ShareDecodeResult
}

/** The outcome of building a share URL: either the https link, or "too much content". */
sealed interface ShareUrlResult {
    data class Ok(
        val url: String,
    ) : ShareUrlResult

    /** The link would exceed [ShareLinkCodec.MAX_URL_LENGTH] — share fewer items. */
    data class TooLong(
        val length: Int,
    ) : ShareUrlResult
}

/**
 * ADR-039: the PURE codec behind self-contained share links. Encoding is
 * `JSON → raw DEFLATE (best compression) → Base64 URL-safe, no padding`, and the blob
 * is placed on two link shapes (the ADR-020 dual-link pattern, both declared as
 * `ACTION_VIEW` intent filters on the app's `MainActivity`):
 *
 * - `https://cleartravel.itsluminous.com/share/<kind>/<blob>` — what we put in shared
 *   text (a real URL, so recipients without the app still land somewhere).
 * - `cleartravel://share/<kind>/<blob>` — custom-scheme twin for launchers that
 *   mangle https deep links.
 *
 * Decoding never throws: every failure is a typed [ShareLinkError]. Inflation is
 * capped at [MAX_INFLATED_BYTES] so a hostile blob cannot balloon memory.
 */
object ShareLinkCodec {
    const val HTTPS_SCHEME = "https"
    const val HTTPS_HOST = "cleartravel.itsluminous.com"
    const val HTTPS_PATH_PREFIX = "/share/"
    const val CUSTOM_SCHEME = "cleartravel"
    const val CUSTOM_HOST = "share"

    /** The newest payload version this build can read (and the one it writes). */
    const val SUPPORTED_VERSION = 1

    /**
     * Practical URL ceiling. Android's binder limit (~500 KB per intent) is far away;
     * the real constraints are messaging apps and browsers, which mishandle URLs in
     * the tens of KB. 8 000 chars keeps well inside every common client while still
     * fitting a ~40-item trip after compression.
     */
    const val MAX_URL_LENGTH = 8_000

    /** Cap on inflated JSON bytes (a 512 KB itinerary is not a plausible share). */
    private const val MAX_INFLATED_BYTES = 512 * 1024
    private const val INFLATE_CHUNK = 4 * 1024
    private const val VERSION_KEY = "v"

    /** Defaults omitted, unknown keys ignored: compact on the wire, tolerant on read. */
    internal val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()

    /** JSON → deflate → base64url blob. */
    fun encode(payload: SharePayload): String {
        val text =
            when (payload) {
                is TripSharePayload -> json.encodeToString(payload)
                is ChecklistSharePayload -> json.encodeToString(payload)
                is FlightSharePayload -> json.encodeToString(payload)
            }
        return base64Encoder.encodeToString(deflate(text.toByteArray(Charsets.UTF_8)))
    }

    /** The canonical https link for [payload]'s kind + blob. */
    fun httpsUrl(
        kind: ShareKind,
        blob: String,
    ): String = "$HTTPS_SCHEME://$HTTPS_HOST$HTTPS_PATH_PREFIX${kind.pathSegment}/$blob"

    /** The custom-scheme twin. */
    fun customUrl(
        kind: ShareKind,
        blob: String,
    ): String = "$CUSTOM_SCHEME://$CUSTOM_HOST/${kind.pathSegment}/$blob"

    /** Encodes and wraps in the https link, refusing links longer than [MAX_URL_LENGTH]. */
    fun buildShareUrl(payload: SharePayload): ShareUrlResult {
        val url = httpsUrl(payload.kind, encode(payload))
        return if (url.length > MAX_URL_LENGTH) ShareUrlResult.TooLong(url.length) else ShareUrlResult.Ok(url)
    }

    /**
     * Recognises either link shape and splits it into kind + blob; null for anything
     * else (other hosts, unknown kinds, missing blob). Does NOT decode.
     */
    fun parse(link: String?): ShareLink? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val rest =
            when {
                scheme == HTTPS_SCHEME && host == HTTPS_HOST && path.startsWith(HTTPS_PATH_PREFIX) ->
                    path.removePrefix(HTTPS_PATH_PREFIX)
                scheme == CUSTOM_SCHEME && host == CUSTOM_HOST -> path.removePrefix("/")
                else -> return null
            }.trimEnd('/')
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.lastIndex) return null
        val kind = ShareKind.fromPathSegment(rest.substring(0, slash)) ?: return null
        val blob = rest.substring(slash + 1)
        if (blob.isBlank() || blob.contains('/')) return null
        return ShareLink(kind, blob)
    }

    /** Finds the first share link inside free text (share sheets wrap links in sentences). */
    fun findInText(text: String?): ShareLink? {
        if (text.isNullOrBlank()) return null
        return text
            .split(Regex("\\s+"))
            .asSequence()
            .mapNotNull { parse(it.trim().trimEnd('.', ',', ')', '>')) }
            .firstOrNull()
    }

    /** Decodes a parsed link into its payload, or a typed failure. */
    fun decode(link: ShareLink): ShareDecodeResult {
        val bytes =
            runCatching { inflate(base64Decoder.decode(link.blob)) }.getOrNull()
                ?: return ShareDecodeResult.Failed(ShareLinkError.CORRUPTED)
        val text = String(bytes, Charsets.UTF_8)
        val element =
            runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return ShareDecodeResult.Failed(ShareLinkError.CORRUPTED)
        val version =
            runCatching { element[VERSION_KEY]?.jsonPrimitive?.int }.getOrNull()
                ?: return ShareDecodeResult.Failed(ShareLinkError.CORRUPTED)
        if (version > SUPPORTED_VERSION) return ShareDecodeResult.Failed(ShareLinkError.UNSUPPORTED_VERSION)
        val payload =
            try {
                when (link.kind) {
                    ShareKind.TRIP -> json.decodeFromJsonElement(TripSharePayload.serializer(), element)
                    ShareKind.CHECKLIST -> json.decodeFromJsonElement(ChecklistSharePayload.serializer(), element)
                    ShareKind.FLIGHT -> json.decodeFromJsonElement(FlightSharePayload.serializer(), element)
                }
            } catch (e: SerializationException) {
                return ShareDecodeResult.Failed(ShareLinkError.CORRUPTED)
            } catch (e: IllegalArgumentException) {
                return ShareDecodeResult.Failed(ShareLinkError.CORRUPTED)
            }
        return if (isValid(payload)) ShareDecodeResult.Ok(payload) else ShareDecodeResult.Failed(ShareLinkError.INVALID_CONTENT)
    }

    /** Convenience: parse + decode in one step; null when [link] is not a share link at all. */
    fun decode(link: String?): ShareDecodeResult? = parse(link)?.let(::decode)

    /** Semantic validation: canonical UUIDs everywhere ids are upserted, non-blank names. */
    private fun isValid(payload: SharePayload): Boolean =
        when (payload) {
            is TripSharePayload ->
                EntityIds.isValid(payload.id) &&
                    payload.name.isNotBlank() &&
                    payload.items.all { EntityIds.isValid(it.id) && it.name.isNotBlank() } &&
                    payload.items
                        .map { it.id }
                        .toSet()
                        .size == payload.items.size
            is ChecklistSharePayload ->
                EntityIds.isValid(payload.id) &&
                    payload.name.isNotBlank() &&
                    payload.items.all { EntityIds.isValid(it.id) && it.text.isNotBlank() } &&
                    payload.items
                        .map { it.id }
                        .toSet()
                        .size == payload.items.size
            is FlightSharePayload ->
                payload.airlineIata.isNotBlank() && payload.flightNumber.isNotBlank()
        }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            ByteArrayOutputStream()
                .also { out ->
                    DeflaterOutputStream(out, deflater).use { it.write(input) }
                }.toByteArray()
        } finally {
            deflater.end()
        }
    }

    @Throws(DataFormatException::class)
    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(INFLATE_CHUNK)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw DataFormatException("truncated deflate stream")
                }
                out.write(buffer, 0, count)
                if (out.size() > MAX_INFLATED_BYTES) throw DataFormatException("payload too large")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
