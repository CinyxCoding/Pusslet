package se.pusslet.data.seed

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import se.pusslet.core.model.Aisle
import se.pusslet.core.model.Allergen
import se.pusslet.core.model.Child
import se.pusslet.core.model.ChoreRotation
import se.pusslet.core.model.ClothingCategory
import se.pusslet.core.model.ClothingSize
import se.pusslet.core.model.Contact
import se.pusslet.core.model.DietProfile
import se.pusslet.core.model.Event
import se.pusslet.core.model.EventTags
import se.pusslet.core.model.FamilySettings
import se.pusslet.core.model.FamilyTask
import se.pusslet.core.model.GiftPlan
import se.pusslet.core.model.Handoff
import se.pusslet.core.model.Ingredient
import se.pusslet.core.model.ListKind
import se.pusslet.core.model.LunchProviderType
import se.pusslet.core.model.LunchSource
import se.pusslet.core.model.MainComponent
import se.pusslet.core.model.ManualLunchWeek
import se.pusslet.core.model.MealEntry
import se.pusslet.core.model.MealPlanWeek
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.PackingRule
import se.pusslet.core.model.PackingTrigger
import se.pusslet.core.model.PartyDetails
import se.pusslet.core.model.Place
import se.pusslet.core.model.Recipe
import se.pusslet.core.model.RecipeTag
import se.pusslet.core.model.Role
import se.pusslet.core.model.Rsvp
import se.pusslet.core.model.SchoolKind
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.model.ShoppingItem
import se.pusslet.core.model.ShoppingList
import se.pusslet.core.model.Store
import se.pusslet.core.model.TaskCategory
import se.pusslet.core.model.TaskKind
import se.pusslet.core.model.WishItem
import se.pusslet.core.time.isoWeek
import se.pusslet.core.time.plusDays
import se.pusslet.core.time.startOfWeek
import se.pusslet.core.units.MeasureUnit
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.sync.Collections
import se.pusslet.data.sync.DocumentStore
import kotlin.time.Clock

/**
 * "Familjen Lind" – a made-up family with a realistic week, relative to [today]: Sara and Johan, Ella (9),
 * Simon (4) and morfar Bengt who picks up on Thursdays. Used by the demo flavor, screenshots and portfolio.
 * Contains deliberate situations the app should handle: a pick-up conflict, lunch/dinner clashes, a study day
 * and a party without a present.
 */
class DemoSeed(private val family: FamilyRepository, private val docs: DocumentStore, private val clock: Clock = Clock.System) {
    // A linear script of one demo week; splitting it up would only scatter the story across functions.
    @Suppress("LongMethod")
    suspend fun seed(today: LocalDate) {
        val created = family.createFamily("Familjen Lind", "Sara", MemberColor.Sage, demo = true)
        val fid = created.id
        val sara = docs.getAll(Collections.Members, Member.serializer()).first().id
        val monday = today.startOfWeek()

        fun d(n: Int) = monday.plusDays(n)

        fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

        suspend fun <T> put(spec: se.pusslet.data.sync.CollectionSpec, id: String, value: T, ser: kotlinx.serialization.KSerializer<T>) =
            docs.put(spec, id, value, ser, fid)

        // People
        put(Collections.Members, JOHAN, Member(JOHAN, "Johan", Role.Parent, MemberColor.Ocean), Member.serializer())
        put(Collections.Members, BENGT, Member(BENGT, "Bengt", Role.Caregiver, MemberColor.Slate, nickname = "Morfar"), Member.serializer())
        val ella = Child(
            ELLA,
            "Ella",
            MemberColor.Rose,
            avatar = "🦊",
            birthDate = LocalDate(2017, 3, 14),
            school = SchoolRef("Björkängsskolan", kind = SchoolKind.School),
            className = "3B",
            lunch = LunchSource(LunchProviderType.Manual),
            sizes = listOf(ClothingSize(ClothingCategory.Shoes, "33", d(-60)), ClothingSize(ClothingCategory.Pants, "134", d(-60))),
            contacts = listOf(
                Contact("Karin (lärare)", "Klassföreståndare", email = "karin@example.se"),
                Contact("Veras pappa Ali", "Kompis förälder", "070-000 00 00"),
            ),
        )
        val simon = Child(
            SIMON,
            "Simon",
            MemberColor.Ochre,
            avatar = "🦖",
            birthDate = LocalDate(2022, 5, 2),
            school = SchoolRef("Förskolan Ekorren", kind = SchoolKind.Preschool),
            className = "Blåbären",
            lunch = LunchSource(LunchProviderType.Manual),
            sizes = listOf(ClothingSize(ClothingCategory.Shoes, "26", d(-30)), ClothingSize(ClothingCategory.Pants, "104", d(-30))),
        )
        put(Collections.Children, ELLA, ella, Child.serializer())
        put(Collections.Children, SIMON, simon, Child.serializer())
        put(
            Collections.Diets,
            SIMON,
            DietProfile(SIMON, allergies = setOf(Allergen.Peanut), consentedAt = clock.now()),
            DietProfile.serializer(),
        )

        // School, preschool and pick-ups. Thursdays morfar picks up.
        val weekdays = "FREQ=WEEKLY;BYDAY=MO,TU,WE,FR"
        put(
            Collections.Events,
            "e-forskola",
            Event(
                "e-forskola",
                "Förskola",
                at(monday, 8),
                at(monday, 16),
                rrule = weekdays,
                participantIds = listOf(SIMON),
                dropOff = Handoff(JOHAN, LocalTime(7, 50)),
                pickUp = Handoff(sara, LocalTime(16, 0)),
                tags = listOf("förskola"),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-forskola-to",
            Event(
                "e-forskola-to",
                "Förskola",
                at(d(3), 8),
                at(d(3), 16),
                rrule = "FREQ=WEEKLY;BYDAY=TH",
                participantIds = listOf(SIMON),
                dropOff = Handoff(JOHAN, LocalTime(7, 50)),
                pickUp = Handoff(BENGT, LocalTime(15, 30)),
                tags = listOf("förskola"),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-skola",
            Event(
                "e-skola",
                "Skola & fritids",
                at(monday, 8, 15),
                at(monday, 16, 30),
                rrule = weekdays,
                participantIds = listOf(ELLA),
                dropOff = Handoff(JOHAN, LocalTime(8, 5)),
                pickUp = Handoff(sara, LocalTime(16, 30)),
                tags = listOf("skola"),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-skola-to",
            Event(
                "e-skola-to",
                "Skola & fritids",
                at(d(3), 8, 15),
                at(d(3), 16),
                rrule = "FREQ=WEEKLY;BYDAY=TH",
                participantIds = listOf(ELLA),
                dropOff = Handoff(JOHAN, LocalTime(8, 5)),
                pickUp = Handoff(BENGT, LocalTime(15, 45)),
                tags = listOf("skola"),
            ),
            Event.serializer(),
        )
        // Activities
        put(
            Collections.Events,
            "e-fotboll",
            Event(
                "e-fotboll",
                "Fotboll",
                at(d(1), 17, 30),
                at(d(1), 18, 30),
                location = "Vallen",
                rrule = "FREQ=WEEKLY;BYDAY=TU",
                participantIds = listOf(SIMON),
                tags = listOf(EventTags.TRAINING),
                packingItems = listOf("Vattenflaska"),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-dans",
            Event(
                "e-dans",
                "Dans",
                at(d(2), 17, 15),
                at(d(2), 18, 15),
                location = "Kulturskolan",
                rrule = "FREQ=WEEKLY;BYDAY=WE",
                participantIds = listOf(ELLA),
                tags = listOf(EventTags.TRAINING),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-sim",
            Event(
                "e-sim",
                "Simskola",
                at(d(5), 9),
                at(d(5), 9, 45),
                location = "Eriksdalsbadet",
                rrule = "FREQ=WEEKLY;BYDAY=SA",
                participantIds = listOf(ELLA, JOHAN),
                tags = listOf(EventTags.TRAINING),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-kalas",
            Event(
                "e-kalas",
                "Kalas hos Vera",
                at(d(5), 14),
                at(d(5), 16),
                location = "Lindvägen 12",
                participantIds = listOf(ELLA),
                tags = listOf(EventTags.PARTY),
                linkedTaskIds = listOf("t-present"),
                party = PartyDetails(rsvp = Rsvp.Yes, rsvpBy = d(1), gift = GiftPlan(idea = "Pysselset med pärlor", budget = 150)),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-utflykt",
            Event(
                "e-utflykt",
                "Skogsutflykt",
                at(d(4), 0),
                at(d(5), 0),
                allDay = true,
                participantIds = listOf(SIMON),
                tags = listOf(EventTags.TRIP),
                packingItems = listOf("Sittunderlag"),
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-studiedag",
            Event(
                "e-studiedag",
                "Studiedag",
                at(d(11), 0),
                at(d(12), 0),
                allDay = true,
                participantIds = listOf(ELLA),
                tags = listOf(EventTags.STUDY_DAY),
            ),
            Event.serializer(),
        )
        // Adults: Tuesday both parents are busy around pick-up (the conflict the Today view should flag).
        put(
            Collections.Events,
            "e-styrelse",
            Event(
                "e-styrelse",
                "Styrelsemöte",
                at(d(1), 16),
                at(d(1), 17, 30),
                participantIds = listOf(sara),
                tags = listOf(EventTags.MEETING),
                sharedWithCaregivers = false,
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-kundmote",
            Event(
                "e-kundmote",
                "Kundmöte",
                at(d(1), 15, 45),
                at(d(1), 17),
                participantIds = listOf(JOHAN),
                tags = listOf(EventTags.WORK),
                sharedWithCaregivers = false,
            ),
            Event.serializer(),
        )
        put(
            Collections.Events,
            "e-tandlakare",
            Event(
                "e-tandlakare",
                "Tandläkare",
                at(d(9), 10),
                at(d(9), 10, 45),
                location = "Folktandvården",
                participantIds = listOf(SIMON, sara),
                tags = listOf(EventTags.MEETING),
            ),
            Event.serializer(),
        )

        // Packing rules
        put(
            Collections.PackingRules,
            "p-gympa",
            PackingRule("p-gympa", "Gympapåse", ELLA, PackingTrigger.Weekly(setOf(DayOfWeek.WEDNESDAY))),
            PackingRule.serializer(),
        )
        put(
            Collections.PackingRules,
            "p-bok",
            PackingRule("p-bok", "Bibliotekbok", ELLA, PackingTrigger.Weekly(setOf(DayOfWeek.FRIDAY), 2, today.isoWeek)),
            PackingRule.serializer(),
        )
        put(
            Collections.PackingRules,
            "p-matsack",
            PackingRule("p-matsack", "Matsäck", null, PackingTrigger.EventTag(EventTags.TRIP)),
            PackingRule.serializer(),
        )
        put(
            Collections.PackingRules,
            "p-ombyte",
            PackingRule("p-ombyte", "Extra ombyte", SIMON, PackingTrigger.Weekly(setOf(DayOfWeek.MONDAY))),
            PackingRule.serializer(),
        )
        put(
            Collections.PackingRules,
            "p-bad",
            PackingRule("p-bad", "Badkläder och handduk", ELLA, PackingTrigger.Weekly(setOf(DayOfWeek.SATURDAY))),
            PackingRule.serializer(),
        )

        // Recipes and the week's dinners
        DemoRecipes.all.forEach { put(Collections.Recipes, it.id, it, Recipe.serializer()) }
        var plan = MealPlanWeek(today.isoWeek)
        listOf(
            0 to "r-kottbullar",
            1 to "r-fiskgratang",
            2 to "r-pesto",
            3 to "r-linssoppa",
            4 to "r-tacos",
            6 to "r-kycklinggryta",
        ).forEach { (day, rid) ->
            val r = DemoRecipes.all.first { it.id == rid }
            plan = plan.with(MealEntry(d(day), recipeId = r.id, title = r.title, cookDouble = rid == "r-kycklinggryta"))
        }
        plan = plan.with(MealEntry(d(5), title = "Pizza"))
        put(Collections.MealPlans, plan.week.key, plan, MealPlanWeek.serializer())
        val nextMonday = d(7)
        var next = MealPlanWeek(nextMonday.isoWeek)
        next = next.with(MealEntry(nextMonday, recipeId = "r-kycklinggryta", title = "Kycklinggryta", leftoversFrom = d(6)))
        put(Collections.MealPlans, next.week.key, next, MealPlanWeek.serializer())

        // School lunches (manual source). Monday and Friday clash with dinner on purpose.
        val ellaLunch = ManualLunchWeek(
            ELLA,
            today.isoWeek,
            mapOf(
                d(0).toString() to "Köttbullar med potatismos\nVegetariska bullar",
                d(1).toString() to "Fiskpinnar med kokt potatis",
                d(2).toString() to "Vegetarisk lasagne",
                d(3).toString() to "Ärtsoppa och pannkakor",
                d(4).toString() to "Tacobuffé",
            ),
        )
        put(Collections.SchoolLunches, ellaLunch.docId, ellaLunch, ManualLunchWeek.serializer())
        val simonLunch = ellaLunch.copy(childId = SIMON, days = ellaLunch.days.mapValues { (_, v) -> v.substringBefore('\n') })
        put(Collections.SchoolLunches, simonLunch.docId, simonLunch, ManualLunchWeek.serializer())

        // Shopping
        put(Collections.Stores, "s-ica", Store("s-ica", "ICA Nära Lindvägen"), Store.serializer())
        put(
            Collections.Stores,
            "s-willys",
            Store(
                "s-willys",
                "Willys",
                listOf(
                    Aisle.Produce,
                    Aisle.Dairy,
                    Aisle.Cheese,
                    Aisle.Meat,
                    Aisle.Deli,
                    Aisle.Fish,
                    Aisle.Bakery,
                    Aisle.Pantry,
                    Aisle.Canned,
                    Aisle.SpicesAndSauces,
                    Aisle.Baking,
                    Aisle.Breakfast,
                    Aisle.Frozen,
                    Aisle.Drinks,
                    Aisle.SnacksAndSweets,
                    Aisle.Baby,
                    Aisle.Hygiene,
                    Aisle.Household,
                    Aisle.Pets,
                    Aisle.Pharmacy,
                    Aisle.Other,
                ),
            ),
            Store.serializer(),
        )
        put(
            Collections.ShoppingLists,
            "l-vecka",
            ShoppingList("l-vecka", "Veckohandling", storeId = "s-ica", sortIndex = 0),
            ShoppingList.serializer(),
        )
        put(
            Collections.ShoppingLists,
            "l-apotek",
            ShoppingList("l-apotek", "Apoteket", ListKind.Other, sortIndex = 1),
            ShoppingList.serializer(),
        )
        listOf(
            ShoppingItem("i1", "l-vecka", "Mjölk", 2.0, MeasureUnit.L, aisle = Aisle.Dairy, addedBy = JOHAN),
            ShoppingItem("i2", "l-vecka", "Bananer", aisle = Aisle.Produce, addedBy = sara),
            ShoppingItem("i3", "l-vecka", "Gurka", 2.0, MeasureUnit.St, aisle = Aisle.Produce, addedBy = sara),
            ShoppingItem("i4", "l-vecka", "Kaffe", aisle = Aisle.Breakfast, addedBy = JOHAN),
            ShoppingItem("i5", "l-vecka", "Knäckebröd", aisle = Aisle.Bakery, addedBy = sara, checked = true),
            ShoppingItem("i6", "l-vecka", "Blöjor storlek 5", aisle = Aisle.Baby, addedBy = JOHAN),
            ShoppingItem("i7", "l-apotek", "Alvedon barn", aisle = Aisle.Pharmacy, addedBy = sara),
            ShoppingItem("i8", "l-apotek", "Plåster", aisle = Aisle.Pharmacy, addedBy = sara),
        ).forEach { put(Collections.ShoppingItems, it.id, it, ShoppingItem.serializer()) }

        // Ella's wishlist – morfar has already bought one thing.
        listOf(
            WishItem("w1", ELLA, "Lego Friends hästgård", addedAt = clock.now()),
            WishItem("w2", ELLA, "Bok: Sune och Svante", addedAt = clock.now(), taken = true, takenBy = BENGT),
            WishItem("w3", ELLA, "Rullskridskor, storlek 34", addedAt = clock.now()),
        ).forEach { put(Collections.Wishes, it.id, it, WishItem.serializer()) }

        // Tasks and chores
        listOf(
            FamilyTask(
                "t-present",
                "Köp present till Vera",
                category = TaskCategory.Gift,
                assigneeId = sara,
                due = d(4),
                linkedEventId = "e-kalas",
            ),
            FamilyTask("t-fritids", "Betala fritidsavgiften", category = TaskCategory.Kids, assigneeId = JOHAN, due = d(7)),
            FamilyTask("t-vaccin", "Boka vaccination för Simon", category = TaskCategory.Kids, assigneeId = sara),
            FamilyTask("t-lager", "Anmäl Ella till sportlovsläger", category = TaskCategory.Kids, due = d(13)),
            FamilyTask("t-lasning", "Läsa 20 minuter", category = TaskCategory.Homework, rrule = "FREQ=DAILY", sharedWithCaregivers = true),
            FamilyTask(
                "t-dammsug",
                "Dammsuga",
                kind = TaskKind.Chore,
                category = TaskCategory.Household,
                rrule = "FREQ=WEEKLY;BYDAY=SA",
                rotation = ChoreRotation(listOf(sara, JOHAN), 1, today.isoWeek),
            ),
            FamilyTask(
                "t-lakan",
                "Byta lakan",
                kind = TaskKind.Chore,
                category = TaskCategory.Household,
                rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU",
                rotation = ChoreRotation(listOf(JOHAN, sara), 2, today.isoWeek),
            ),
            FamilyTask(
                "t-sopor",
                "Ta ut soporna",
                kind = TaskKind.Chore,
                category = TaskCategory.Household,
                rrule = "FREQ=WEEKLY;BYDAY=MO,TH",
                rotation = ChoreRotation(listOf(JOHAN, sara), 1, today.isoWeek),
            ),
        ).forEach { put(Collections.Tasks, it.id, it, FamilyTask.serializer()) }

        family.saveSettings { FamilySettings(weatherPlace = Place("Stockholm", 59.3293, 18.0686), defaultStoreId = "s-ica") }
    }

    companion object {
        const val JOHAN = "m-johan"
        const val BENGT = "m-bengt"
        const val ELLA = "c-ella"
        const val SIMON = "c-simon"
    }
}

/** Everyday Swedish dinners used by the demo (and as a starter library). */
object DemoRecipes {
    private fun i(name: String, q: Double? = null, unit: MeasureUnit? = null, note: String = "", optional: Boolean = false) =
        Ingredient(name, q, unit, note = note, optional = optional)

    val all: List<Recipe> = listOf(
        Recipe(
            "r-kottbullar",
            "Köttbullar med potatismos",
            4,
            listOf(
                i("blandfärs", 500.0, MeasureUnit.G),
                i("ströbröd", 0.5, MeasureUnit.Dl),
                i("mjölk", 1.5, MeasureUnit.Dl),
                i("ägg", 1.0),
                i("gul lök", 1.0, note = "finhackad"),
                i("potatis", 1.0, MeasureUnit.Kg),
                i("smör", 2.0, MeasureUnit.Msk),
                i("mjölk", 2.0, MeasureUnit.Dl, "till moset"),
                i("lingonsylt", 1.0, MeasureUnit.Dl),
                i("salt", 1.0, MeasureUnit.Tsk, optional = true),
            ),
            listOf(
                "Blanda ströbröd och mjölk, låt svälla.",
                "Blanda i färs, ägg, lök och salt. Rulla bullar.",
                "Stek bullarna gyllene.",
                "Koka potatisen och mosa med smör och mjölk.",
            ),
            setOf(RecipeTag.KidFriendly),
            prepMinutes = 20,
            totalMinutes = 45,
            mainComponent = MainComponent.MincedMeat,
        ),
        Recipe(
            "r-fiskgratang",
            "Fiskgratäng med potatis",
            4,
            listOf(
                i("torskfilé", 500.0, MeasureUnit.G),
                i("potatis", 800.0, MeasureUnit.G),
                i("matlagningsgrädde", 2.5, MeasureUnit.Dl),
                i("räkor", 150.0, MeasureUnit.G, optional = true),
                i("dill", 1.0, MeasureUnit.Knippe),
                i("citron", 1.0),
                i("riven ost", 1.0, MeasureUnit.Dl),
            ),
            listOf("Sätt ugnen på 200 °C.", "Lägg fisken i en form, häll över grädde och dill.", "Toppa med ost, gratinera 25 min."),
            setOf(RecipeTag.KidFriendly),
            totalMinutes = 40,
            mainComponent = MainComponent.Fish,
        ),
        Recipe(
            "r-pesto",
            "Pasta med pesto och broccoli",
            4,
            listOf(
                i("pasta", 400.0, MeasureUnit.G),
                i("grön pesto", 1.0, MeasureUnit.Burk),
                i("broccoli", 1.0),
                i("parmesan", 50.0, MeasureUnit.G),
            ),
            listOf("Koka pastan, lägg i broccolin de sista 3 minuterna.", "Blanda med pesto och riven parmesan."),
            setOf(RecipeTag.Quick, RecipeTag.Vegetarian, RecipeTag.KidFriendly),
            totalMinutes = 15,
            mainComponent = MainComponent.Vegetarian,
        ),
        Recipe(
            "r-linssoppa",
            "Röd linssoppa",
            4,
            listOf(
                i("röda linser", 3.0, MeasureUnit.Dl),
                i("krossade tomater", 1.0, MeasureUnit.Burk),
                i("gul lök", 1.0),
                i("vitlök", 2.0, MeasureUnit.Klyfta),
                i("grönsaksbuljong", 1.0, MeasureUnit.L),
                i("kokosmjölk", 2.5, MeasureUnit.Dl),
                i("spiskummin", 1.0, MeasureUnit.Tsk),
            ),
            listOf("Fräs lök och vitlök.", "Tillsätt linser, tomater, buljong och kryddor. Koka 20 min.", "Rör ner kokosmjölk och mixa."),
            setOf(RecipeTag.Vegan, RecipeTag.Vegetarian, RecipeTag.Lunchbox, RecipeTag.FreezerFriendly),
            totalMinutes = 35,
            mainComponent = MainComponent.Soup,
        ),
        Recipe(
            "r-tacos",
            "Fredagstacos",
            4,
            listOf(
                i("nötfärs", 500.0, MeasureUnit.G),
                i("tacokrydda", 1.0, MeasureUnit.Pase),
                i("tortillabröd", 8.0),
                i("gurka", 1.0),
                i("tomat", 3.0),
                i("majs", 1.0, MeasureUnit.Burk),
                i("riven ost", 2.0, MeasureUnit.Dl),
                i("gräddfil", 2.0, MeasureUnit.Dl),
                i("salsa", 1.0, MeasureUnit.Burk),
            ),
            listOf("Bryn färsen, tillsätt krydda och vatten.", "Hacka grönsakerna.", "Duka fram allt och låt alla bygga själva."),
            setOf(RecipeTag.KidFriendly, RecipeTag.Weekend),
            totalMinutes = 30,
            mainComponent = MainComponent.Beef,
        ),
        Recipe(
            "r-kycklinggryta",
            "Krämig kycklinggryta",
            4,
            listOf(
                i("kycklingfilé", 600.0, MeasureUnit.G),
                i("matlagningsgrädde", 2.5, MeasureUnit.Dl),
                i("crème fraiche", 2.0, MeasureUnit.Dl),
                i("paprika", 1.0),
                i("gul lök", 1.0),
                i("ris", 3.0, MeasureUnit.Dl),
                i("kycklingbuljong", 1.0, MeasureUnit.Tsk),
            ),
            listOf(
                "Bryn kycklingen.",
                "Lägg i lök och paprika.",
                "Häll på grädde, crème fraiche och buljong, puttra 15 min.",
                "Servera med ris.",
            ),
            setOf(RecipeTag.KidFriendly, RecipeTag.Lunchbox, RecipeTag.FreezerFriendly),
            totalMinutes = 35,
            mainComponent = MainComponent.Chicken,
        ),
        Recipe(
            "r-pannkakor",
            "Ugnspannkaka",
            4,
            listOf(
                i("vetemjöl", 2.5, MeasureUnit.Dl),
                i("mjölk", 6.0, MeasureUnit.Dl),
                i("ägg", 3.0),
                i("smör", 2.0, MeasureUnit.Msk),
                i("bacon", 140.0, MeasureUnit.G, optional = true),
            ),
            listOf("Vispa mjöl och mjölk, tillsätt ägg.", "Häll i smord form, grädda 225 °C i 25 min."),
            setOf(RecipeTag.KidFriendly, RecipeTag.Budget),
            totalMinutes = 35,
            mainComponent = MainComponent.Egg,
        ),
        Recipe(
            "r-laxpasta",
            "Laxpasta med spenat",
            4,
            listOf(
                i("lax", 400.0, MeasureUnit.G),
                i("pasta", 400.0, MeasureUnit.G),
                i("spenat", 100.0, MeasureUnit.G),
                i("matlagningsgrädde", 2.0, MeasureUnit.Dl),
                i("citron", 0.5),
            ),
            listOf("Koka pastan.", "Tärna laxen och stek kort, häll på grädde och spenat.", "Blanda med pastan, pressa över citron."),
            setOf(RecipeTag.Quick, RecipeTag.KidFriendly),
            totalMinutes = 20,
            mainComponent = MainComponent.Fish,
        ),
    )
}
