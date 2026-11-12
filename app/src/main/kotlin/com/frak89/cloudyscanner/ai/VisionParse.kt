package com.frak89.cloudyscanner.ai

/** What the model currently believes the subject is. */
class Recognition(
    val name: String = "",
    /** 0..100 */
    val confidence: Int = 0,
    /** 0 simple, 1 medium, 2 complex, -1 unknown */
    val level: Int = -1,
    /** What the model wants to be shown to be surer; empty when it needs nothing. */
    val ask: String = "",
    val material: String = "",
) {
    val known get() = name.isNotEmpty() && !name.equals("sconosciuto", true)
}

/** Reads the short "KEY: value" answers the recognition prompts ask for. */
object VisionParse {
    private fun field(text: String, vararg keys: String): String {
        for (raw in text.lines()) {
            val line = raw.trim().trimStart('-', '*', '•', '`', ' ').replace("**", "")
            val c = line.indexOf(':')
            if (c <= 0) continue
            val k = line.substring(0, c).trim().uppercase().replace('À', 'A').replace("'", "")
            if (keys.any { k == it }) return line.substring(c + 1).trim().trim('`', '"', '.', ' ')
        }
        return ""
    }

    fun parse(text: String): Recognition {
        val name = field(text, "OGGETTO", "OBJECT").take(40)
        val conf = Regex("\\d+").find(field(text, "SICUREZZA", "CONFIDENZA", "CONFIDENCE"))?.value?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        val lv = field(text, "COMPLESSITA", "COMPLEXITY").uppercase()
        val level = when { lv.startsWith("SEMPL") || lv.startsWith("SIMPL") -> 0; lv.startsWith("MED") -> 1; lv.startsWith("COMPL") -> 2; else -> -1 }
        var ask = field(text, "RICHIESTA", "REQUEST").take(120)
        if (ask.uppercase().startsWith("NESSUN") || ask.equals("none", true) || ask == "-") ask = ""
        return Recognition(name, conf, level, ask, field(text, "MATERIALE", "MATERIALI").take(60))
    }
}
