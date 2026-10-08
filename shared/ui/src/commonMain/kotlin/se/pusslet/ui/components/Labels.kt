package se.pusslet.ui.components

import org.jetbrains.compose.resources.StringResource
import se.pusslet.core.holidays.HolidayId
import se.pusslet.core.model.Allergen
import se.pusslet.core.model.Diet
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.MealSlot
import se.pusslet.core.model.Role
import se.pusslet.core.weather.ClothingAdvice
import se.pusslet.core.weather.WeatherCondition
import se.pusslet.ui.resources.*

/** Maps domain enums to their display strings (so the domain stays free of UI text). */
object Labels {
    fun holiday(id: HolidayId): StringResource = when (id) {
        HolidayId.NewYearsDay -> Res.string.holiday_NewYearsDay
        HolidayId.TwelfthNight -> Res.string.holiday_TwelfthNight
        HolidayId.Epiphany -> Res.string.holiday_Epiphany
        HolidayId.ValentinesDay -> Res.string.holiday_ValentinesDay
        HolidayId.ShroveTuesday -> Res.string.holiday_ShroveTuesday
        HolidayId.MaundyThursday -> Res.string.holiday_MaundyThursday
        HolidayId.GoodFriday -> Res.string.holiday_GoodFriday
        HolidayId.EasterEve -> Res.string.holiday_EasterEve
        HolidayId.EasterSunday -> Res.string.holiday_EasterSunday
        HolidayId.EasterMonday -> Res.string.holiday_EasterMonday
        HolidayId.WalpurgisNight -> Res.string.holiday_WalpurgisNight
        HolidayId.MayDay -> Res.string.holiday_MayDay
        HolidayId.AscensionDay -> Res.string.holiday_AscensionDay
        HolidayId.MothersDay -> Res.string.holiday_MothersDay
        HolidayId.WhitsunEve -> Res.string.holiday_WhitsunEve
        HolidayId.WhitSunday -> Res.string.holiday_WhitSunday
        HolidayId.NationalDay -> Res.string.holiday_NationalDay
        HolidayId.MidsummerEve -> Res.string.holiday_MidsummerEve
        HolidayId.MidsummerDay -> Res.string.holiday_MidsummerDay
        HolidayId.AllSaintsEve -> Res.string.holiday_AllSaintsEve
        HolidayId.AllSaintsDay -> Res.string.holiday_AllSaintsDay
        HolidayId.FathersDay -> Res.string.holiday_FathersDay
        HolidayId.FirstAdvent -> Res.string.holiday_FirstAdvent
        HolidayId.SecondAdvent -> Res.string.holiday_SecondAdvent
        HolidayId.ThirdAdvent -> Res.string.holiday_ThirdAdvent
        HolidayId.FourthAdvent -> Res.string.holiday_FourthAdvent
        HolidayId.Lucia -> Res.string.holiday_Lucia
        HolidayId.ChristmasEve -> Res.string.holiday_ChristmasEve
        HolidayId.ChristmasDay -> Res.string.holiday_ChristmasDay
        HolidayId.BoxingDay -> Res.string.holiday_BoxingDay
        HolidayId.NewYearsEve -> Res.string.holiday_NewYearsEve
        HolidayId.DaylightSavingStart -> Res.string.holiday_DaylightSavingStart
        HolidayId.DaylightSavingEnd -> Res.string.holiday_DaylightSavingEnd
    }

    fun advice(a: ClothingAdvice): StringResource = when (a) {
        ClothingAdvice.WinterOverall -> Res.string.advice_winter_overall
        ClothingAdvice.WinterJacket -> Res.string.advice_winter_jacket
        ClothingAdvice.WoolLayer -> Res.string.advice_wool
        ClothingAdvice.HatAndMittens -> Res.string.advice_hat_mittens
        ClothingAdvice.ThinHatAndGloves -> Res.string.advice_thin_hat
        ClothingAdvice.WinterBoots -> Res.string.advice_winter_boots
        ClothingAdvice.RainGearAndBoots -> Res.string.advice_rain_gear
        ClothingAdvice.RainJacket -> Res.string.advice_rain_jacket
        ClothingAdvice.Fleece -> Res.string.advice_fleece
        ClothingAdvice.LightJacket -> Res.string.advice_light_jacket
        ClothingAdvice.WindProof -> Res.string.advice_windproof
        ClothingAdvice.TShirt -> Res.string.advice_tshirt
        ClothingAdvice.SunHatAndWater -> Res.string.advice_sun_hat
        ClothingAdvice.Sunscreen -> Res.string.advice_sunscreen
        ClothingAdvice.ExtraClothes -> Res.string.advice_extra_clothes
        ClothingAdvice.Reflective -> Res.string.advice_reflective
    }

    fun weather(c: WeatherCondition): StringResource = when (c) {
        WeatherCondition.Clear -> Res.string.weather_clear
        WeatherCondition.PartlyCloudy -> Res.string.weather_partly
        WeatherCondition.Cloudy -> Res.string.weather_cloudy
        WeatherCondition.Fog -> Res.string.weather_fog
        WeatherCondition.Rain -> Res.string.weather_rain
        WeatherCondition.Showers -> Res.string.weather_showers
        WeatherCondition.Thunder -> Res.string.weather_thunder
        WeatherCondition.Sleet -> Res.string.weather_sleet
        WeatherCondition.Snow -> Res.string.weather_snow
    }

    /** Friendly glyph next to the weather text (decorative; the text carries the meaning). */
    fun weatherEmoji(c: WeatherCondition): String = when (c) {
        WeatherCondition.Clear -> "☀️"
        WeatherCondition.PartlyCloudy -> "⛅"
        WeatherCondition.Cloudy -> "☁️"
        WeatherCondition.Fog -> "🌫️"
        WeatherCondition.Rain -> "🌧️"
        WeatherCondition.Showers -> "🌦️"
        WeatherCondition.Thunder -> "⛈️"
        WeatherCondition.Sleet -> "🌨️"
        WeatherCondition.Snow -> "❄️"
    }

    fun role(r: Role): StringResource = when (r) {
        Role.Parent -> Res.string.role_parent
        Role.Caregiver -> Res.string.role_caregiver
        Role.Child -> Res.string.role_child
    }

    fun mealSlot(s: MealSlot): StringResource = when (s) {
        MealSlot.Breakfast -> Res.string.meal_breakfast
        MealSlot.Lunch -> Res.string.meal_lunch
        MealSlot.Dinner -> Res.string.meal_dinner
    }

    /** Well-known tags; free-text tags are shown as typed. */
    fun tag(tag: String): StringResource? = when (tag.lowercase()) {
        EventTags.PARTY -> Res.string.tag_party
        EventTags.TRIP -> Res.string.tag_trip
        EventTags.STUDY_DAY -> Res.string.tag_study_day
        EventTags.HOLIDAY_BREAK -> Res.string.tag_break
        EventTags.TRAINING -> Res.string.tag_training
        EventTags.MEETING -> Res.string.tag_meeting
        EventTags.WORK -> Res.string.tag_work
        EventTags.PE -> Res.string.tag_pe
        else -> null
    }

    fun allergen(a: Allergen): StringResource = when (a) {
        Allergen.Milk -> Res.string.allergen_Milk
        Allergen.Lactose -> Res.string.allergen_Lactose
        Allergen.Egg -> Res.string.allergen_Egg
        Allergen.Gluten -> Res.string.allergen_Gluten
        Allergen.Peanut -> Res.string.allergen_Peanut
        Allergen.TreeNuts -> Res.string.allergen_TreeNuts
        Allergen.Fish -> Res.string.allergen_Fish
        Allergen.Shellfish -> Res.string.allergen_Shellfish
        Allergen.Soy -> Res.string.allergen_Soy
        Allergen.Sesame -> Res.string.allergen_Sesame
        Allergen.Celery -> Res.string.allergen_Celery
        Allergen.Mustard -> Res.string.allergen_Mustard
        Allergen.Lupin -> Res.string.allergen_Lupin
        Allergen.Sulphites -> Res.string.allergen_Sulphites
    }

    fun diet(d: Diet): StringResource = when (d) {
        Diet.Vegetarian -> Res.string.diet_Vegetarian
        Diet.Vegan -> Res.string.diet_Vegan
        Diet.Pescetarian -> Res.string.diet_Pescetarian
        Diet.Halal -> Res.string.diet_Halal
        Diet.NoPork -> Res.string.diet_NoPork
        Diet.Kosher -> Res.string.diet_Kosher
    }
}
