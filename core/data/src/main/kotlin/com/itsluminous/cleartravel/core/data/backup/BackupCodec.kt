package com.itsluminous.cleartravel.core.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Serializes a [BackupSnapshot] to the backup ZIP layout and back (ADR-015,
 * `docs/backup-format.md`): `manifest.json` + one JSON array per entity type under
 * `entities/` + bundled attachment/document bytes under `attachments/<id>` and
 * boarding-pass bytes under `boarding_passes/<flightId>` (ADR-038).
 *
 * Reading is lenient where safe (unknown JSON keys ignored, missing entity files =
 * empty lists — forward/backward tolerant) and strict where required: a missing or
 * unparseable manifest is [BackupException.CorruptedBackup], and a manifest with a
 * newer `schemaVersion` is [BackupException.UnsupportedSchemaVersion].
 *
 * ADR-031: bundled bytes are PLAINTEXT inside the ZIP (the whole ZIP is sealed in the
 * portable envelope by the manager), so on-device files are decrypted through
 * [openBundledFile] while bundling and re-encrypted through the extraction sink.
 */
internal object BackupCodec {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Writes the full backup ZIP; [bundledEntries] maps ZIP entry name (see
     * [BackupEntries.attachmentEntry] / [BackupEntries.boardingPassEntry]) → source
     * file, read through [openBundledFile] (the decrypting file cipher in production).
     */
    fun writeZip(
        snapshot: BackupSnapshot,
        bundledEntries: Map<String, File>,
        out: OutputStream,
        openBundledFile: (File) -> InputStream = { it.inputStream() },
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
            for ((entryName, file) in bundledEntries) {
                zip.putNextEntry(ZipEntry(entryName))
                openBundledFile(file).use { it.copyTo(zip) }
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

    /**
     * Extracts one bundled attachment's bytes to [target] through [write] (the
     * encrypting file cipher in production); false when not bundled.
     */
    fun extractAttachment(
        zip: ZipFile,
        attachmentId: String,
        target: File,
        write: (InputStream, File) -> Unit = { input, file -> file.outputStream().use { input.copyTo(it) } },
    ): Boolean = extractEntry(zip, BackupEntries.attachmentEntry(attachmentId), target, write)

    /** Extracts the bundled bytes at [entryName] to [target] through [write]; false when absent. */
    fun extractEntry(
        zip: ZipFile,
        entryName: String,
        target: File,
        write: (InputStream, File) -> Unit = { input, file -> file.outputStream().use { input.copyTo(it) } },
    ): Boolean {
        val entry = zip.getEntry(entryName) ?: return false
        target.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input -> write(input, target) }
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
