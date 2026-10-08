package se.pusslet.core.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

/** Advice keys; the UI maps each to a short, warm sentence in the current language. */
enum class ClothingAdvice {
    WinterOverall,
    WinterJacket,
    WoolLayer,
    HatAndMittens,
    ThinHatAndGloves,
    WinterBoots,
    RainGearAndBoots,
    RainJacket,
    Fleece,
    LightJacket,
    WindProof,
    TShirt,
    SunHatAndWater,
    Sunscreen,
    ExtraClothes,
    Reflective,
}

/**
 * One row of the clothing table. All conditions present must hold. Kept as plain data ("enkel regeltabell")
 * so the table is easy to read, tweak and test.
 */
data class ClothingRule(
    val advice: ClothingAdvice,
    val minAge: Int = 0,
    val maxAge: Int = 99,
    /** Applies when the day's lowest feels-like temperature is below this. */
    val feelsBelow: Double? = null,
    /** Applies when the lowest feels-like temperature is at or above this. */
    val feelsAtLeast: Double? = null,
    /** Applies when the warmest hour is at or above this. */
    val maxAtLeast: Double? = null,
    val needsRain: Boolean = false,
    val needsSnow: Boolean = false,
    val needsDry: Boolean = false,
    val windAtLeast: Double? = null,
    val months: Set<Month>? = null,
    /** Lower = shown first. */
    val priority: Int,
)

object ClothingRules {
    private val darkMonths = setOf(Month.OCTOBER, Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH)

    val default: List<ClothingRule> = listOf(
        // Cold
        ClothingRule(ClothingAdvice.WinterOverall, maxAge = 9, feelsBelow = 0.0, priority = 10),
        ClothingRule(ClothingAdvice.WinterJacket, minAge = 10, feelsBelow = 0.0, priority = 10),
        ClothingRule(ClothingAdvice.WoolLayer, feelsBelow = -8.0, priority = 12),
        ClothingRule(ClothingAdvice.HatAndMittens, feelsBelow = 2.0, priority = 14),
        ClothingRule(ClothingAdvice.ThinHatAndGloves, feelsAtLeast = 2.0, feelsBelow = 7.0, priority = 16),
        ClothingRule(ClothingAdvice.WinterBoots, feelsBelow = -2.0, needsDry = true, priority = 18),
        ClothingRule(ClothingAdvice.WinterBoots, needsSnow = true, priority = 18),
        // Wet
        ClothingRule(ClothingAdvice.RainGearAndBoots, maxAge = 9, needsRain = true, priority = 5),
        ClothingRule(ClothingAdvice.RainJacket, minAge = 10, needsRain = true, priority = 5),
        ClothingRule(ClothingAdvice.ExtraClothes, maxAge = 6, needsRain = true, priority = 30),
        ClothingRule(ClothingAdvice.ExtraClothes, maxAge = 6, needsSnow = true, priority = 30),
        // Mild
        ClothingRule(ClothingAdvice.Fleece, feelsAtLeast = 0.0, feelsBelow = 10.0, priority = 20),
        ClothingRule(ClothingAdvice.LightJacket, feelsAtLeast = 10.0, feelsBelow = 17.0, priority = 22),
        ClothingRule(ClothingAdvice.WindProof, windAtLeast = 9.0, priority = 25),
        // Warm
        ClothingRule(ClothingAdvice.TShirt, feelsAtLeast = 17.0, priority = 22),
        ClothingRule(ClothingAdvice.SunHatAndWater, maxAtLeast = 22.0, needsDry = true, priority = 8),
        ClothingRule(
            ClothingAdvice.Sunscreen,
            maxAtLeast = 22.0,
            needsDry = true,
            months = setOf(Month.MAY, Month.JUNE, Month.JULY, Month.AUGUST),
            priority = 9,
        ),
        // Dark mornings
        ClothingRule(ClothingAdvice.Reflective, maxAge = 12, months = darkMonths, priority = 40),
    )
}

object ClothingAdvisor {
    /** Children whose age is unknown are treated as school children. */
    private const val DEFAULT_AGE = 7

    fun ageOn(birthDate: LocalDate?, date: LocalDate): Int? {
        if (birthDate == null) return null
        var age = date.year - birthDate.year
        if (date.month < birthDate.month || (date.month == birthDate.month && date.day < birthDate.day)) age--
        return age.coerceAtLeast(0)
    }

    fun advise(
        day: DaySummary,
        ageYears: Int?,
        rules: List<ClothingRule> = ClothingRules.default,
        maxItems: Int = 4,
    ): List<ClothingAdvice> {
        val age = ageYears ?: DEFAULT_AGE
        return rules.filter { matches(it, day, age) }
            .sortedBy { it.priority }
            .map { it.advice }
            .distinct()
            .let { list ->
                // Rain gear already covers a light jacket; a winter overall covers fleece.
                list.filterNot {
                    (it == ClothingAdvice.LightJacket || it == ClothingAdvice.WindProof) &&
                        ClothingAdvice.RainGearAndBoots in list
                }
                    .filterNot {
                        it == ClothingAdvice.Fleece &&
                            (ClothingAdvice.WinterOverall in list || ClothingAdvice.WinterJacket in list)
                    }
            }
            .take(maxItems)
    }

    private fun matches(r: ClothingRule, day: DaySummary, age: Int): Boolean {
        if (age < r.minAge || age > r.maxAge) return false
        if (r.feelsBelow != null && day.minFeelsLike >= r.feelsBelow) return false
        if (r.feelsAtLeast != null && day.minFeelsLike < r.feelsAtLeast) return false
        if (r.maxAtLeast != null && day.maxTemperature < r.maxAtLeast) return false
        if (r.needsRain && !day.rainLikely) return false
        if (r.needsSnow && !day.snowLikely) return false
        if (r.needsDry && (day.rainLikely || day.snowLikely)) return false
        if (r.windAtLeast != null && day.maxWind < r.windAtLeast) return false
        if (r.months != null && day.date.month !in r.months) return false
        return true
    }
}
