package com.itsluminous.cleartravel.core.google.drive

import com.itsluminous.cleartravel.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.cleartravel.core.google.auth.GoogleNotAvailableException
import com.itsluminous.cleartravel.core.google.rest.GoogleApiException
import com.itsluminous.cleartravel.core.google.rest.GoogleApiHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/** One file in the app's Drive folder (backup listing / uploads). */
data class DriveFileInfo(
    val fileId: String,
    val name: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

/**
 * Low-level Drive v3 operations, behind an interface so the upload/backup engines are
 * fully testable with a fake — tests never touch the live API.
 */
interface DriveClient {
    /** Id of a live (non-trashed) folder named [name] at the Drive root, or null. */
    suspend fun findFolder(name: String): String?

    /** Creates a root folder named [name] and returns its id. */
    suspend fun createFolder(name: String): String

    /** Multipart upload of [sourceFile] into folder [parentId]; returns the file id. */
    suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        sourceFile: File,
    ): String

    /** Downloads a file's media bytes into [target]; on failure [target] is removed. */
    suspend fun downloadFile(
        fileId: String,
        target: File,
    )

    /** Live files under [parentId] whose name starts with [namePrefix], newest first. */
    suspend fun listFiles(
        parentId: String,
        namePrefix: String,
    ): List<DriveFileInfo>

    /** Permanently deletes a file the app created; already-gone is not an error. */
    suspend fun deleteFile(fileId: String)
}

private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"

/** [DriveClient] over the plain Drive v3 REST endpoints using `drive.file` tokens. */
@Singleton
class RestDriveClient
    @Inject
    constructor(
        private val http: GoogleApiHttp,
        private val tokenProvider: GoogleAccessTokenProvider,
    ) : DriveClient {
        private val json = Json { ignoreUnknownKeys = true }

        private suspend fun token(): String = tokenProvider.accessToken() ?: throw GoogleNotAvailableException()

        override suspend fun findFolder(name: String): String? {
            val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
            val query = "name = '$escaped' and mimeType = '$FOLDER_MIME' and trashed = false"
            val url = "$FILES_URL?q=${encode(query)}&fields=files(id,name)&pageSize=1"
            val response = http.request("GET", url, token())
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject["files"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content
        }

        override suspend fun createFolder(name: String): String {
            val metadata =
                buildJsonObject {
                    put("name", name)
                    put("mimeType", FOLDER_MIME)
                }
            val response =
                http.request(
                    "POST",
                    "$FILES_URL?fields=id",
                    token(),
                    contentType = JSON_CONTENT_TYPE,
                    body = metadata.toString().toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

        override suspend fun uploadFile(
            name: String,
            mimeType: String,
            parentId: String,
            sourceFile: File,
        ): String {
            val metadata =
                buildJsonObject {
                    put("name", name)
                    put("parents", buildJsonArray { add(JsonPrimitive(parentId)) })
                }
            val boundary = "cleartravel-${System.currentTimeMillis()}"
            val body =
                ByteArrayOutputStream().use { out ->
                    fun writeText(text: String) = out.write(text.toByteArray())
                    writeText("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
                    writeText(metadata.toString())
                    writeText("\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n")
                    sourceFile.inputStream().use { it.copyTo(out) }
                    writeText("\r\n--$boundary--")
                    out.toByteArray()
                }
            val response =
                http.request(
                    "POST",
                    UPLOAD_URL,
                    token(),
                    contentType = "multipart/related; boundary=$boundary",
                    body = body,
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

        override suspend fun downloadFile(
            fileId: String,
            target: File,
        ) {
            val response = http.downloadToFile("$FILES_URL/${encode(fileId)}?alt=media", token(), target)
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
        }

        override suspend fun listFiles(
            parentId: String,
            namePrefix: String,
        ): List<DriveFileInfo> {
            val escapedPrefix = namePrefix.replace("\\", "\\\\").replace("'", "\\'")
            val query = "'$parentId' in parents and name contains '$escapedPrefix' and trashed = false"
            val url =
                "$FILES_URL?q=${encode(query)}&fields=files(id,name,size,createdTime)" +
                    "&orderBy=createdTime+desc&pageSize=100"
            val response = http.request("GET", url, token())
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            val files = json.parseToJsonElement(response.body).jsonObject["files"]?.jsonArray ?: return emptyList()
            return files
                .map { it.jsonObject }
                .filter {
                    it["name"]
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                        .startsWith(namePrefix)
                }.map { obj ->
                    DriveFileInfo(
                        fileId = obj.getValue("id").jsonPrimitive.content,
                        name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                        sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        createdAt = parseRfc3339(obj["createdTime"]?.jsonPrimitive?.content),
                    )
                }
        }

        override suspend fun deleteFile(fileId: String) {
            val response = http.request("DELETE", "$FILES_URL/${encode(fileId)}", token())
            if (!response.isSuccess && response.code != 404) throw GoogleApiException(response.code, response.body)
        }

        private fun parseRfc3339(value: String?): Instant =
            if (value == null) {
                Instant.EPOCH
            } else {
                try {
                    Instant.parse(value)
                } catch (e: DateTimeParseException) {
                    Instant.EPOCH
                }
            }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        private companion object {
            const val JSON_CONTENT_TYPE = "application/json; charset=UTF-8"
        }
    }
