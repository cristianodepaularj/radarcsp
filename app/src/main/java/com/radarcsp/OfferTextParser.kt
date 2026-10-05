package com.radarcsp

internal class Parsed(val price: Double?, val perKm: Double, val totalKm: Double?)

/** Lê preço, km e "R$/km aprox." do texto reconhecido na tela. */
internal object OfferTextParser {
    private val priceRegex = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})+|\d+)(?:[.,](\d{1,2}))?""")
    private val perKmRegex = Regex("""R\$\s*(\d+)[.,](\d{1,2})\s*/\s*km""", RegexOption.IGNORE_CASE)
    private val distRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m)(?![A-Za-zÀ-ÿ])""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Parsed? {
        val t = raw.replace('\n', ' ')

        val perKmUber = perKmRegex.find(t)?.let { m ->
            (m.groupValues[1] + "." + m.groupValues[2]).toDoubleOrNull()
        }

        // Preço da corrida: ignora "R$ x/km" e "+R$ x incluído"
        val priceMatch = priceRegex.findAll(t).firstOrNull { m ->
            val before = t.substring(0, m.range.first).trimEnd().lastOrNull()
            val after = t.substring(m.range.last + 1).trimStart().lowercase()
            before != '+' && !after.startsWith("/") && !after.startsWith("km") && !after.startsWith("inclu")
        }
        val price = priceMatch?.let { m ->
            val intPart = m.groupValues[1].replace(".", "")
            val dec = m.groupValues[2]
            (if (dec.isEmpty()) intPart else "$intPart.$dec").toDoubleOrNull()
        }

        val dists = distRegex.findAll(t).mapNotNull { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            if (m.groupValues[2].equals("m", ignoreCase = true)) v / 1000.0 else v
        }.toList()

        val total: Double? = when {
            dists.size >= 2 -> dists[0] + dists[1]   // até o passageiro + viagem
            dists.size == 1 -> dists[0]
            else -> null
        }
        val computed: Double? =
            if (price != null && dists.size >= 2 && total != null && total > 0.0) price / total else null

        val perKm = perKmUber ?: computed ?: return null
        if (perKm < 0.1 || perKm > 50.0) return null
        return Parsed(price, perKm, total)
    }
}
