package se.pusslet.core.shopping

import se.pusslet.core.model.Aisle

/**
 * Puts a grocery in a store department from a local Swedish word list. Family corrections (overrides) always win,
 * so the classifier "learns" without any AI (ADR-001). Matching is on word stems, longest first.
 */
object AisleClassifier {
    private val words: List<Pair<String, Aisle>> = buildList {
        fun add(aisle: Aisle, vararg stems: String) = stems.forEach { add(it to aisle) }
        add(
            Aisle.Produce,
            "banan",
            "äpple",
            "äpplen",
            "päron",
            "apelsin",
            "clementin",
            "mandarin",
            "citron",
            "lime",
            "druv",
            "vindruv",
            "melon",
            "kiwi",
            "mango",
            "ananas",
            "avokado",
            "gurka",
            "tomat",
            "paprika",
            "lök",
            "vitlök",
            "purjo",
            "potatis",
            "morot",
            "morötter",
            "sallad",
            "spenat",
            "broccoli",
            "blomkål",
            "zucchini",
            "squash",
            "svamp",
            "champinjon",
            "selleri",
            "ingefära",
            "basilika",
            "persilja",
            "dill",
            "koriander",
            "gräslök",
            "rödbet",
            "sötpotatis",
            "kål",
            "majskolv",
            "bär",
            "jordgubb",
            "hallon",
            "blåbär",
            "frukt",
            "grönsak",
            "rucola",
            "aubergine",
            "fänkål",
            "ruccola",
            "groddar",
            "chili",
        )
        add(
            Aisle.Bakery,
            "bröd",
            "limpa",
            "baguette",
            "fralla",
            "frallor",
            "bullar",
            "kaka",
            "kakor",
            "tårta",
            "tortilla",
            "pitabröd",
            "hamburgerbröd",
            "korvbröd",
        )
        add(Aisle.Deli, "skinka", "salami", "pålägg", "leverpastej", "kalkon", "rökt", "hummus", "oliver")
        add(
            Aisle.Meat,
            "köttfärs",
            "nötfärs",
            "blandfärs",
            "fläskfärs",
            "kycklingfärs",
            "kyckling",
            "fläsk",
            "bacon",
            "korv",
            "falukorv",
            "biff",
            "entrecote",
            "karré",
            "kotlett",
            "lamm",
            "kött",
            "kassler",
            "högrev",
            "fläskfilé",
        )
        add(Aisle.Fish, "lax", "torsk", "sej", "fisk", "räk", "tonfisk", "sill", "makrill", "kolja", "musslor")
        add(
            Aisle.Dairy,
            "mjölk",
            "fil",
            "filmjölk",
            "yoghurt",
            "grädde",
            "gräddfil",
            "creme fraiche",
            "crème fraiche",
            "smör",
            "margarin",
            "kvarg",
            "keso",
            "havremjölk",
            "matlagningsgrädde",
            "kesella",
            "oatly",
        )
        add(Aisle.Cheese, "ost", "parmesan", "mozzarella", "fetaost", "halloumi", "cheddar", "brie", "riven ost")
        add(Aisle.Eggs, "ägg")
        add(Aisle.Frozen, "fryst", "frysta", "glass", "fiskpinn", "pommes", "frysgrönsak", "ärtor", "wokgrönsak", "isglass")
        add(
            Aisle.Pantry,
            "pasta",
            "spaghetti",
            "makaron",
            "ris",
            "nudl",
            "couscous",
            "bulgur",
            "quinoa",
            "linser",
            "kikärt",
            "bönor",
            "mjöl",
            "gryn",
            "havregryn",
            "tacoskal",
            "tacokrydd",
            "knäckebröd",
            "nötter",
            "russin",
            "kokosmjölk",
        )
        add(Aisle.Canned, "krossade tomater", "konserv", "burk", "tomatpuré", "majs", "passerade tomater")
        add(
            Aisle.SpicesAndSauces,
            "salt",
            "peppar",
            "krydd",
            "ketchup",
            "senap",
            "majonnäs",
            "soja",
            "buljong",
            "fond",
            "olja",
            "olivolja",
            "vinäger",
            "pesto",
            "salsa",
            "dressing",
            "sylt",
            "honung",
            "sirap",
            "spiskummin",
            "curry",
            "paprikapulver",
        )
        add(Aisle.Baking, "socker", "bakpulver", "jäst", "vaniljsocker", "kakao", "florsocker", "bikarbonat", "mandelmassa", "strössel")
        add(Aisle.Breakfast, "kaffe", "te", "flingor", "müsli", "granola", "gröt", "välling", "juice", "kakao")
        add(Aisle.Drinks, "läsk", "vatten", "mineralvatten", "saft", "öl", "cider", "dricka")
        add(Aisle.SnacksAndSweets, "chips", "godis", "choklad", "popcorn", "kex", "lösgodis", "snacks", "ostbågar")
        add(Aisle.Baby, "blöj", "barnmat", "våtservett", "napp", "ersättning", "klämpåse")
        add(
            Aisle.Hygiene,
            "tandkräm",
            "tandborste",
            "schampo",
            "balsam",
            "tvål",
            "deodorant",
            "deo",
            "bomull",
            "binda",
            "tampong",
            "rakhyvel",
            "duschtvål",
            "hudkräm",
        )
        add(
            Aisle.Household,
            "toapapper",
            "hushållspapper",
            "diskmedel",
            "tvättmedel",
            "sköljmedel",
            "soppåse",
            "påsar",
            "plastfolie",
            "aluminiumfolie",
            "bakplåtspapper",
            "batteri",
            "glödlampa",
            "disktabletter",
            "städ",
            "svamp till disk",
            "fryspåse",
        )
        add(Aisle.Pets, "kattmat", "hundmat", "kattsand", "djurfoder")
        add(
            Aisle.Pharmacy,
            "alvedon",
            "ipren",
            "panodil",
            "plåster",
            "nässpray",
            "febertermometer",
            "receptmedicin",
            "medicin",
            "huvudvärk",
        )
    }.sortedByDescending { it.first.length }

    fun normalize(name: String): String = name.trim().lowercase()

    fun classify(name: String, overrides: Map<String, Aisle> = emptyMap()): Aisle {
        val n = normalize(name)
        overrides[n]?.let { return it }
        // Exact word or stem at the start of a word wins over substrings in the middle ("vitlök" vs "lök").
        val tokens = n.split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() }
        for ((stem, aisle) in words) {
            if (' ' in stem) {
                if (stem in n) return aisle
            } else if (tokens.any { it.startsWith(stem) }) {
                return aisle
            }
        }
        for ((stem, aisle) in words) if (stem.length >= 4 && stem in n) return aisle
        return Aisle.Other
    }
}
