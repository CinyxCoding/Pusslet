package se.pusslet.core.units

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Dimension { Volume, Mass, Count }

/**
 * Swedish kitchen units. Volume converts via millilitres, mass via grams.
 * Count-like units (st, förp, burk …) only merge with the same unit.
 */
@Serializable
enum class MeasureUnit(
    val dimension: Dimension,
    /** Size in the dimension's base unit (ml or g). 1.0 for count units. */
    val baseFactor: Double,
    /** Canonical Swedish abbreviation used in lists. */
    val symbol: String,
    val aliases: List<String>,
) {
    @SerialName("krm")
    Krm(Dimension.Volume, 1.0, "krm", listOf("krm", "kryddmått", "kryddmatt")),

    @SerialName("tsk")
    Tsk(Dimension.Volume, 5.0, "tsk", listOf("tsk", "tesked", "teskedar", "tsp", "teaspoon", "teaspoons")),

    @SerialName("msk")
    Msk(Dimension.Volume, 15.0, "msk", listOf("msk", "matsked", "matskedar", "tbsp", "tablespoon", "tablespoons")),

    @SerialName("ml")
    Ml(Dimension.Volume, 1.0, "ml", listOf("ml", "milliliter")),

    @SerialName("cl")
    Cl(Dimension.Volume, 10.0, "cl", listOf("cl", "centiliter")),

    @SerialName("dl")
    Dl(Dimension.Volume, 100.0, "dl", listOf("dl", "deciliter")),

    @SerialName("l")
    L(Dimension.Volume, 1000.0, "l", listOf("l", "liter", "litre", "liters")),

    @SerialName("mg")
    Mg(Dimension.Mass, 0.001, "mg", listOf("mg", "milligram")),

    @SerialName("g")
    G(Dimension.Mass, 1.0, "g", listOf("g", "gr", "gram")),

    @SerialName("hg")
    Hg(Dimension.Mass, 100.0, "hg", listOf("hg", "hekto", "hektogram")),

    @SerialName("kg")
    Kg(Dimension.Mass, 1000.0, "kg", listOf("kg", "kilo", "kilogram")),

    @SerialName("st")
    St(Dimension.Count, 1.0, "st", listOf("st", "st.", "styck", "stycken", "pcs")),

    @SerialName("forp")
    Forp(Dimension.Count, 1.0, "förp", listOf("förp", "förp.", "forp", "förpackning", "förpackningar")),

    @SerialName("pkt")
    Pkt(Dimension.Count, 1.0, "pkt", listOf("pkt", "paket")),

    @SerialName("burk")
    Burk(Dimension.Count, 1.0, "burk", listOf("burk", "burkar")),

    @SerialName("flaska")
    Flaska(Dimension.Count, 1.0, "flaska", listOf("flaska", "flaskor")),

    @SerialName("pase")
    Pase(Dimension.Count, 1.0, "påse", listOf("påse", "påsar", "pase")),

    @SerialName("klyfta")
    Klyfta(Dimension.Count, 1.0, "klyfta", listOf("klyfta", "klyftor")),

    @SerialName("skiva")
    Skiva(Dimension.Count, 1.0, "skiva", listOf("skiva", "skivor")),

    @SerialName("knippe")
    Knippe(Dimension.Count, 1.0, "knippe", listOf("knippe", "knippen")),

    @SerialName("kruka")
    Kruka(Dimension.Count, 1.0, "kruka", listOf("kruka", "krukor")),

    @SerialName("nypa")
    Nypa(Dimension.Count, 1.0, "nypa", listOf("nypa", "nypor")),

    @SerialName("portion")
    Portion(Dimension.Count, 1.0, "port", listOf("portion", "portioner", "port")),
    ;

    fun canConvertTo(other: MeasureUnit): Boolean =
        dimension == other.dimension && (dimension != Dimension.Count || this == other)

    companion object {
        private val byAlias: Map<String, MeasureUnit> = entries.flatMap { u -> u.aliases.map { it to u } }.toMap()

        /** Case-insensitive lookup of "dl", "Matskedar", "st." … */
        fun parse(token: String): MeasureUnit? = byAlias[token.trim().lowercase()]
    }
}

/** An amount, optionally a range ("2-3 st"). */
@Serializable
data class Quantity(val value: Double, val unit: MeasureUnit? = null, val max: Double? = null) {
    fun scaled(factor: Double): Quantity = copy(value = value * factor, max = max?.let { it * factor })

    /** Converts to [target] if the dimensions match. */
    fun convertTo(target: MeasureUnit): Quantity? {
        val u = unit ?: return null
        if (!u.canConvertTo(target)) return null
        val f = u.baseFactor / target.baseFactor
        return Quantity(value * f, target, max?.let { it * f })
    }

    /** Value in ml or g (or count). */
    val baseValue: Double? get() = unit?.let { value * it.baseFactor }
}

object Units {
    /**
     * Picks a readable unit for a merged amount: "15 tsk" becomes "5 msk", "1500 ml" becomes "1,5 l".
     * Stays within the household spoon/dl/l scale for volumes and g/kg for mass.
     */
    fun normalize(q: Quantity): Quantity {
        val unit = q.unit ?: return q
        val base = q.value * unit.baseFactor
        return when (unit.dimension) {
            Dimension.Count -> q
            Dimension.Mass -> when {
                base >= 1000 && isNice(base / 1000) -> q.convertTo(MeasureUnit.Kg) ?: q
                else -> q.convertTo(MeasureUnit.G) ?: q
            }
            Dimension.Volume -> when {
                base >= 1000 && isNice(base / 1000) -> q.convertTo(MeasureUnit.L) ?: q
                base >= 100 -> q.convertTo(MeasureUnit.Dl) ?: q
                base >= 15 && isNice(base / 15) -> q.convertTo(MeasureUnit.Msk) ?: q
                base >= 5 && isNice(base / 5) -> q.convertTo(MeasureUnit.Tsk) ?: q
                base >= 15 -> q.convertTo(MeasureUnit.Msk) ?: q
                base >= 5 -> q.convertTo(MeasureUnit.Tsk) ?: q
                else -> q.convertTo(MeasureUnit.Krm) ?: q
            }
        }
    }

    /** True for values that read well: whole numbers or halves/quarters. */
    private fun isNice(v: Double): Boolean = abs(v * 4 - (v * 4).roundToInt()) < 0.01

    /** Swedish number formatting: decimal comma, common fractions as ½ ¼ ¾, max 1 decimal. */
    fun formatNumber(value: Double): String {
        val whole = value.toLong()
        val frac = value - whole
        val fractionSymbol = when {
            abs(frac) < 0.01 -> ""
            abs(frac - 0.25) < 0.01 -> "¼"
            abs(frac - 0.5) < 0.01 -> "½"
            abs(frac - 0.75) < 0.01 -> "¾"
            abs(frac - 1.0 / 3) < 0.01 -> "⅓"
            abs(frac - 2.0 / 3) < 0.01 -> "⅔"
            else -> null
        }
        if (fractionSymbol != null) {
            return when {
                fractionSymbol.isEmpty() -> whole.toString()
                whole == 0L -> fractionSymbol
                else -> "$whole $fractionSymbol"
            }
        }
        val rounded = (value * 10).roundToInt() / 10.0
        val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        return text.replace('.', ',')
    }

    fun format(q: Quantity): String {
        val number = formatNumber(q.value) + (q.max?.let { "–" + formatNumber(it) } ?: "")
        return if (q.unit == null) number else "$number ${q.unit.symbol}"
    }
}
