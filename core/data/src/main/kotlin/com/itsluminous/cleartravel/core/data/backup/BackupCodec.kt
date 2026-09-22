package com.itsluminous.cleartravel.core.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Serializes a [BackupSnapshot] to the backup ZIP layout and back (ADR-015,
 * `docs/backup-format.md`): `manifest.json` + one JSON array per entity type under
 * `entities/` + bundled attachment bytes under `attachments/<attachmentId>`.
 *
 * Reading is lenient where safe (unknown JSON keys ignored, missing entity files =
 * empty lists — forward/backward tolerant) and strict where required: a missing or
 * unparseable manifest is [BackupException.CorruptedBackup], and a manifest with a
 * newer `schemaVersion` is [BackupException.UnsupportedSchemaVersion].
 */
internal object BackupCodec {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Writes the full backup ZIP; [bundledFiles] maps attachment/document id → source file. */
    fun writeZip(
        snapshot: BackupSnapshot,
        bundledFiles: Map<String, File>,
        out: OutputStream,
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putTextEntry(BackupEntries.MANIFEST, json.encodeToString(snapshot.manifest))
            zip.putTextEntry(BackupEntries.TRIPS, json.encodeToString(snapshot.trips))
            zip.putTextEntry(BackupEntries.ITINERARY_ITEMS, json.encodeToString(snapshot.itineraryItems))
            zip.putTextEntry(BackupEntries.CHECKLISTS, json.encodeToString(snapshot.checklists))
            zip.putTextEntry(BackupEntries.CHECKLIST_ITEMS, json.encodeToString(snapshot.checklistItems))
            zip.putTextEntry(BackupEntries.CHECKLIST_PRESETS, json.encodeToString(snapshot.checklistPresets))
            zip.putTextEntry(BackupEntries.CHECKLIST_PRESET_ITEMS, json.encodeToString(snapshot.checklistPresetItems))
            zip.putTextEntry(BackupEntries.TRAIN_TICKETS, json.encodeToString(snapshot.trainTickets))
            zip.putTextEntry(BackupEntries.TRAIN_PASSENGERS, json.encodeToString(snapshot.trainPassengers))
            zip.putTextEntry(BackupEntries.TRAIN_ROUTE_STOPS, json.encodeToString(snapshot.trainRouteStops))
            zip.putTextEntry(BackupEntries.TRAIN_COACHES, json.encodeToString(snapshot.trainCoaches))
            zip.putTextEntry(BackupEntries.FLIGHT_JOURNEYS, json.encodeToString(snapshot.flightJourneys))
            zip.putTextEntry(BackupEntries.ATTACHMENTS, json.encodeToString(snapshot.attachments))
            zip.putTextEntry(BackupEntries.TRAVEL_DOCUMENTS, json.encodeToString(snapshot.travelDocuments))
            for ((attachmentId, file) in bundledFiles) {
                zip.putNextEntry(ZipEntry(BackupEntries.attachmentEntry(attachmentId)))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Parses only the manifest — the cheap read behind the import preview. */
    fun readManifest(zip: ZipFile): BackupManifest {
        val manifest =
            readText(zip, BackupEntries.MANIFEST)
                ?: throw BackupException.CorruptedBackup()
        val parsed =
            try {
                json.decodeFromString<BackupManifest>(manifest)
            } catch (e: SerializationException) {
                throw BackupException.CorruptedBackup(e)
            }
        if (parsed.schemaVersion > BackupManifest.SCHEMA_VERSION) {
            throw BackupException.UnsupportedSchemaVersion(parsed.schemaVersion)
        }
        return parsed
    }

    /** Parses the whole snapshot (attachment bytes are read separately by id). */
    fun readSnapshot(zip: ZipFile): BackupSnapshot {
        val manifest = readManifest(zip)
        try {
            return BackupSnapshot(
                manifest = manifest,
                trips = readList(zip, BackupEntries.TRIPS),
                itineraryItems = readList(zip, BackupEntries.ITINERARY_ITEMS),
                checklists = readList(zip, BackupEntries.CHECKLISTS),
                checklistItems = readList(zip, BackupEntries.CHECKLIST_ITEMS),
                checklistPresets = readList(zip, BackupEntries.CHECKLIST_PRESETS),
                checklistPresetItems = readList(zip, BackupEntries.CHECKLIST_PRESET_ITEMS),
                trainTickets = readList(zip, BackupEntries.TRAIN_TICKETS),
                trainPassengers = readList(zip, BackupEntries.TRAIN_PASSENGERS),
                trainRouteStops = readList(zip, BackupEntries.TRAIN_ROUTE_STOPS),
                trainCoaches = readList(zip, BackupEntries.TRAIN_COACHES),
                flightJourneys = readList(zip, BackupEntries.FLIGHT_JOURNEYS),
                attachments = readList(zip, BackupEntries.ATTACHMENTS),
                travelDocuments = readList(zip, BackupEntries.TRAVEL_DOCUMENTS),
            )
        } catch (e: SerializationException) {
            throw BackupException.CorruptedBackup(e)
        }
    }

    /** Extracts one bundled attachment's bytes to [target]; false when not bundled. */
    fun extractAttachment(
        zip: ZipFile,
        attachmentId: String,
        target: File,
    ): Boolean {
        val entry = zip.getEntry(BackupEntries.attachmentEntry(attachmentId)) ?: return false
        target.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return true
    }

    /** Opens [file] as a ZIP, mapping unreadable archives to [BackupException]. */
    fun openZip(file: File): ZipFile =
        try {
            ZipFile(file)
        } catch (e: IOException) {
            throw BackupException.CorruptedBackup(e)
        }

    private inline fun <reified T> readList(
        zip: ZipFile,
        entryName: String,
    ): List<T> {
        val text = readText(zip, entryName) ?: return emptyList()
        return json.decodeFromString(json.serializersModule.serializer(), text)
    }

    private fun readText(
        zip: ZipFile,
        entryName: String,
    ): String? {
        val entry = zip.getEntry(entryName) ?: return null
        return zip.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    private fun ZipOutputStream.putTextEntry(
        name: String,
        content: String,
    ) {
        putNextEntry(ZipEntry(name))
        write(content.encodeToByteArray())
        closeEntry()
    }
}
