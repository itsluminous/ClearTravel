# core:ocr

The shared on-device document pipeline: PDF page → bitmap (`PdfPageRasterizer` over the
platform `PdfRenderer`), preprocessing (`BitmapPreprocessor`: grayscale + contrast
stretch, `ColorMatrix` only), ML Kit **text recognition** (`OcrTextRecognizer`) for
ticket OCR and ML Kit **barcode scanning** (`BcbpBarcodeDecoder`, PDF417/Aztec/QR) for
IATA BCBP boarding-pass barcodes — files never leave the device (no cloud OCR).

Everything that interprets text is pure, plain-JUnit-testable Kotlin: `BcbpParser`
(IATA Resolution 792 type-M, multi-leg tolerant), `IrctcTicketExtractor`,
`IrctcSmsParser`, `BoardingPassTextExtractor` — all fed by recorded OCR-text fixtures
in `src/test/resources/fixtures/` (`<name>.txt` + `<name>.expected.json`, including a
garbage fixture asserting the blank-form fallback). Results are typed
(`TrainTicketExtraction`, `BoardingPassExtraction`) with per-field
`ExtractedField(value, confidence)`; extraction always prefills an editable form,
never a blind save, and always succeeds structurally (garbage → EMPTY, never throws).

Feature entry point: **`OcrPrefillService`** — `prefillTrainTicket(Uri)`,
`prefillTrainTicketFromText(String)` (pasted SMS/email), and
`prefillBoardingPass(Uri)` (barcode first, OCR-text heuristics as fallback). Hilt
provides the ML Kit clients in `di/OcrModule`. Design rationale: ADR-009.
