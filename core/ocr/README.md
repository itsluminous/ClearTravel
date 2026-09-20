# core:ocr

The shared on-device document pipeline: PDF page → bitmap (`PdfRenderer`), image
preprocessing (deskew/crop/contrast), ML Kit **text recognition** for ticket OCR, and
ML Kit **barcode scanning** for IATA BCBP boarding-pass barcodes — files never leave
the device (no cloud OCR). Field extraction (PNR, train/flight numbers, passengers,
seats) is pure, unit-testable Kotlin fed by recorded OCR-text fixtures, including a
garbage-text fixture asserting the blank-form fallback; extraction results always
prefill an editable form with a confidence indication, never a blind save. The
skeleton ships the extraction-result stub; the pipeline lands with the Trains/Flights
import milestones.
