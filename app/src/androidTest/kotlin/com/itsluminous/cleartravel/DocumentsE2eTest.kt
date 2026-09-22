package com.itsluminous.cleartravel

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentRepository
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentStorage
import com.itsluminous.cleartravel.core.model.TravelDocument
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import com.itsluminous.cleartravel.core.designsystem.R as DesignR
import com.itsluminous.cleartravel.feature.documents.R as DocumentsR

/**
 * Documents tab happy path (ADR-027, hermetic — in-memory Room): a passport seeded
 * through the real repository with a real PNG under `filesDir/documents/` shows up
 * as a card (name, type label, expiry line); tapping it opens the full-brightness
 * viewer (title = document name, Close control); Close returns to the list.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DocumentsE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var documentRepository: TravelDocumentRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        val filesDir = composeRule.activity.applicationContext.filesDir
        val dir = TravelDocumentStorage.directory(filesDir).apply { mkdirs() }
        val document =
            TravelDocument(
                name = DOCUMENT_NAME,
                type = TravelDocumentType.PASSPORT,
                filePath = "",
                mimeType = "image/png",
                expiryDate = LocalDate.now().plusYears(6),
            )
        val file = File(dir, TravelDocumentStorage.fileName(document.id, "png"))
        file.outputStream().use { out ->
            Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        runBlocking { documentRepository.save(document.copy(filePath = file.absolutePath)) }
    }

    @Test
    fun seededDocument_appearsInList_andOpensViewer() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_documents)).performClick()

        // Card: name + type label + an expiry line (not expired, not expiring soon).
        composeRule.waitForText(DOCUMENT_NAME)
        composeRule.onNodeWithText(composeRule.string(DocumentsR.string.documents_type_passport)).assertExists()
        composeRule.waitForText(EXPIRES_FRAGMENT, substring = true)

        // Tap → viewer: title is the document name and the Close control is present.
        composeRule.onNodeWithText(DOCUMENT_NAME).performClick()
        val close = composeRule.string(DesignR.string.designsystem_viewer_close)
        composeRule.waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription(close).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(DOCUMENT_NAME).assertExists()
        composeRule.onAllNodesWithText(composeRule.string(DesignR.string.designsystem_viewer_missing)).assertCountEquals(0)
        composeRule
            .onNodeWithContentDescription(composeRule.string(DesignR.string.designsystem_viewer_image_description))
            .assertExists()

        // Close → back on the list with the FAB visible.
        composeRule
            .onNodeWithContentDescription(composeRule.string(DesignR.string.designsystem_viewer_close))
            .performClick()
        composeRule.waitForText(composeRule.string(DocumentsR.string.documents_type_passport))
        composeRule
            .onNodeWithContentDescription(composeRule.string(DocumentsR.string.documents_add_fab))
            .assertExists()
    }

    private companion object {
        const val DOCUMENT_NAME = "Ada Lovelace passport"

        // Prefix of documents_card_expires ("Expires %1$s") — the date part is locale-formatted.
        const val EXPIRES_FRAGMENT = "Expires"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
