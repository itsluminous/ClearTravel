package com.itsluminous.cleartravel.core.data.intake

/** What a shared/pasted travel text is most likely about (ADR-042 shared-text intake). */
enum class SharedTextKind {
    TRAIN,
    FLIGHT,
}

/** The classifier's evidence, exposed so the intake UI/tests can explain a suggestion. */
data class SharedTextScores(
    val train: Int,
    val flight: Int,
) {
    /**
     * The suggested kind: the higher score wins; a tie — including no evidence at
     * all — is TRAIN, the destination every shared text had before ADR-042.
     */
    val suggested: SharedTextKind get() = if (flight > train) SharedTextKind.FLIGHT else SharedTextKind.TRAIN
}

/**
 * Pure train-vs-flight classifier for `text/plain` shares (ADR-042). Cheap, regex-only
 * and deliberately NOT a parser: it only PRESELECTS the option in the shared-text
 * intake dialog — the user always confirms or overrides, and the confirmed feature's
 * own parser does the real reading. Lives in `core:data` so the app shell can route
 * without the feature modules knowing about each other.
 *
 * Evidence (weights in [SharedTextClassifier.classify]):
 * - train: 10-digit PNR (labelled or bare), "IRCTC", the word "train", a labelled
 *   `TRN`/train number, a bare 5-digit number, IRCTC class codes, `DOJ`, coach/berth
 *   words, station-code pairs like `SBC-NDLS`;
 * - flight: a flight number (`QP 1421`, `6E-2001`), a labelled 6-char alphanumeric
 *   PNR, airline names, the word "flight", airport/boarding/terminal/gate words,
 *   IATA pairs (`DEL-BOM`, `from BLR to VNS`).
 */
object SharedTextClassifier {
    fun classify(text: String?): SharedTextKind = score(text).suggested

    fun score(text: String?): SharedTextScores {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return SharedTextScores(train = 0, flight = 0)
        return SharedTextScores(train = trainScore(t), flight = flightScore(t))
    }

    private fun trainScore(t: String): Int {
        var score = 0
        if (LABELED_TRAIN_PNR.containsMatchIn(t)) {
            score += 4
        } else if (BARE_TEN_DIGITS.containsMatchIn(t)) {
            score += 2
        }
        if (IRCTC.containsMatchIn(t)) score += 3
        if (TRAIN_WORD.containsMatchIn(t)) score += 2
        if (LABELED_TRAIN_NUMBER.containsMatchIn(t)) {
            score += 3
        } else if (BARE_FIVE_DIGITS.containsMatchIn(t)) {
            score += 1
        }
        if (TRAIN_CLASS.containsMatchIn(t)) score += 1
        if (DOJ.containsMatchIn(t)) score += 2
        if (COACH_BERTH.containsMatchIn(t)) score += 1
        if (STATION_PAIR.containsMatchIn(t)) score += 1
        return score
    }

    private fun flightScore(t: String): Int {
        var score = 0
        if (FLIGHT_NUMBER.findAll(t).any { it.groupValues[1] !in FLIGHT_CODE_STOPWORDS }) score += 3
        if (LABELED_FLIGHT_PNR.containsMatchIn(t)) score += 3
        if (AIRLINE_NAMES.containsMatchIn(t)) score += 3
        if (FLIGHT_WORD.containsMatchIn(t)) score += 2
        if (AIRPORT_WORDS.containsMatchIn(t)) score += 1
        val iataPair =
            (IATA_PAIR.findAll(t) + FROM_TO.findAll(t)).any {
                it.groupValues[1] !in IATA_STOPWORDS &&
                    it.groupValues[2] !in IATA_STOPWORDS
            }
        if (iataPair) score += 2
        return score
    }

    private val LABELED_TRAIN_PNR = Regex("""(?i)\bPNR\s*(?:no\.?|number)?\s*[:#\-]?\s*(\d{10})\b""")
    private val BARE_TEN_DIGITS = Regex("""\b\d{10}\b""")
    private val IRCTC = Regex("""(?i)\bIRCTC\b|\bIndian\s+Railways?\b""")
    private val TRAIN_WORD = Regex("""(?i)\btrains?\b|\brailway\b""")
    private val LABELED_TRAIN_NUMBER = Regex("""(?i)\bTR(?:AI)?N\s*(?:no\.?|number)?\s*[:#\-]?\s*(\d{5})\b""")
    private val BARE_FIVE_DIGITS = Regex("""\b\d{5}\b""")
    private val TRAIN_CLASS = Regex("""\b(1A|2A|3A|3E|EA|EC|CC|SL|2S|FC)\b""")
    private val DOJ = Regex("""(?i)\bDOJ\b""")
    private val COACH_BERTH = Regex("""(?i)\bcoach\b|\bberth\b|\b(?:CNF|RAC|WL)\b""")

    /** Station codes are 2–5 letters, so a 4- or 5-letter member is a train signal, not an IATA pair. */
    private val STATION_PAIR = Regex("""\b(?:[A-Z]{2,5}-[A-Z]{4,5}|[A-Z]{4,5}-[A-Z]{2,5})\b""")

    private val FLIGHT_NUMBER = Regex("""\b([A-Z][A-Z0-9]|[0-9][A-Z])\s?-?\s?(\d{3,4})\b""")
    private val FLIGHT_CODE_STOPWORDS =
        setOf("ON", "AT", "TO", "IN", "BY", "OF", "NO", "IS", "OR", "RS", "PM", "AM", "GO", "UP", "US", "IT", "AS", "ID")
    private val LABELED_FLIGHT_PNR =
        Regex(
            """(?i)\b(?:PNR|booking\s+ref(?:erence)?|record\s+locator)\s*(?:no\.?|number)?\s*[:#\-]?\s*(?:is\s+)?(?=[A-Z0-9]*[A-Z])([A-Z0-9]{6})\b""",
        )
    private val AIRLINE_NAMES =
        Regex(
            """(?i)\b(?:IndiGo|Air\s+India|Vistara|SpiceJet|Akasa|AIX\s+Connect|Alliance\s+Air|Emirates|Qatar\s+Airways|""" +
                """Singapore\s+Airlines|Etihad|flydubai|Lufthansa|British\s+Airways|Cathay\s+Pacific|Thai\s+Airways|""" +
                """Malaysia\s+Airlines|SriLankan|Delta|American\s+Airlines|United\s+Airlines)\b""",
        )
    private val FLIGHT_WORD = Regex("""(?i)\bflights?\b|\bairline\b""")
    private val AIRPORT_WORDS = Regex("""(?i)\bairport\b|\bboarding\s+pass\b|\bterminal\b|\bgate\b|\bweb\s+check-?in\b""")
    private val IATA_PAIR = Regex("""\b([A-Z]{3})\s*(?:->|→|—|–|-|(?i:\s+to\s+))\s*([A-Z]{3})\b""")
    private val FROM_TO =
        Regex("""(?i:\bfrom)\s+(?:[A-Za-z .]+\()?([A-Z]{3})\)?(?:\s*\([^)]{0,40}\))?\s+(?i:to)\s+(?:[A-Za-z .]+\()?([A-Z]{3})\b""")
    private val IATA_STOPWORDS = setOf("PNR", "SMS", "AIR", "INR", "USD", "AND", "THE", "FOR", "YOU", "NOT", "ALL")
}
