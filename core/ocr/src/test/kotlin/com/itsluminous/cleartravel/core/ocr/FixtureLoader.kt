package com.itsluminous.cleartravel.core.ocr

/** Loads recorded OCR-text fixtures and expected-output JSON from test resources. */
object FixtureLoader {
    fun read(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing fixture /fixtures/$name"
        }.bufferedReader().use { it.readText() }
}
