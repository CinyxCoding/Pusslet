# UX-förenkling

> Underlag: fem UX-granskningar, tre riktningar, utkastet och ägarens fyra svar. Mål: en app som är lugn, går att förstå första gången och har mindre text. Paletten, funktionerna och tonen ligger fast. Det som ändras är hierarki, likformighet och textmängd. Allt som sammanfattar är regelbaserat, ligger i `shared/core` och har tester (ADR-001).

## 0. Ägarens svar och vad de betyder

- **”För mycket text, allt blev typ luddigt och rörigt.”** Steg 1 börjar med ett textpass (1.1) med skrivregler, en textbudget och ett test. Idag får högst 40 ord ovanför vecket (i dag cirka 61). Det som är tomt syns inte.
- **”Hellre lugnt … och att man ska kunna anpassa sin startsida.”** Standardläget är kort och det avancerade ligger som en rad längst ned. Idag byggs av block som går att slå av och på och ordna om (Steg 2). Morgonkollen står alltid först.
- **”Ersätt med en +-knapp.”** Den utökade knappen ”Lägg till” blir en rund `FloatingActionButton` med bara + och en TalkBack-etikett. Varje skärm får exakt ett sätt att lägga till (1.2). Utkastets FAB-varianter utgår: ”Jag handlar nu”, ”+ Nytt recept” och ihopfällning vid scroll.
- **”Enklare med det som rör honom.”** Hjälpare (`Role.Caregiver`) får en enkel vy utan bottenfält och utan +. Den svarar på frågan *när hämtar eller lämnar jag, och vem?* (Steg 3). Hela appen finns ett steg bort.

## 1. Riktning

| Riktning | Första användning | Lugn | Risk/insats (högre = lättare) | Kärna |
|---|---|---|---|---|
| A. Lugn och luft | 7 | **9** | 6 | Platta sektioner, en färg per betydelse, typstege |
| B. En sak i taget | 7 | **9** | 4 | Nivåer (”Fler val”), men döljer mest och bryter mest mot BRIEF |
| C. Förstå direkt | **9** | 8 | 6 | Svaret först som meningar, samma form betyder samma sak, hjälp en gång |

**Beslut:** C är ryggraden. Från A tar vi det visuella systemet. Från B tar vi nivåindelningen och regeln att rutiner är tysta och bara avvikelser syns. Ägarens svar lägger till textbudget, anpassningsbar Idag, rund + och hjälparvy.

| Konflikt | Beslut |
|---|---|
| Fokusytans färg | **Havre `secondaryContainer`**, högst en per skärm. Valt läge (flik, segment, filterchip) flyttar till sage `primaryContainer`. surfaceContainerHigh ger bara cirka 1,1:1 mot bakgrunden. |
| FAB | Rund + överallt (svar 3). Vad den gör beror på skärmen (1.2). Kräver en ADR (BRIEF §5). |
| Hälsningen ”God morgon, Sara” | Tas bort från Idag (svar 1) men finns kvar i morgonnotisen (öppen fråga 5). |
| Hjälparläge i Idag eller egen vy | Egen vy (Steg 3), byggd på Idags blockmodell. Ingen switch ”Bara det som rör mig” behövs: den enkla vyn visar det som rör hjälparen och hela appen visar allt som delats. |

---

## 2. Steg 1 – Snabba vinster

Varje punkt blir en liten PR som tar om goldens för de skärmar den rör. Alla texter läggs i `values/` och `values-en/`.

**1.0 Baslinje-goldens.** Roborazzi-goldens för `meals_week`, `shopping_list`, `store_mode` och `calendar_week_large_text` (1,5).
- *Filer:* `composeApp/src/test/kotlin/se/pusslet/android/screenshots/ShellScreenshotTest.kt`.
- *Klart när:* PNG-filerna finns under `composeApp/src/test/snapshots/` och CI är grönt.

### 1.1 Mindre text

I dag finns 738 svenska strängar. 46 är längre än 60 tecken och 57 har 10 ord eller fler. Idag har cirka 61 ord ovanför vecket och middagen syns inte.

**Skrivregler** (blir DESIGN.md › Ton i text och gäller även engelskan):
1. Etiketter har 1–3 ord och knappar börjar med ett verb. Upprepa inte skärmens namn: ”Att göra för dig” blir ”Att göra”.
2. Huvudskärmar har inga förklarande meningar. Förklaringen hör hemma i tomläget, bakom (i) eller i inställningen.
3. Siffror först och kompakt: ”5 kvar”, ”3 av 8 i vagnen”, ”12–18 okt”. Tiden står i en egen kolumn. Årtalet visas bara om det inte är i år.
4. Statusrader är fragment: ”Sara handlar nu”. Hela meningar används bara i Problem: ”Ingen kan hämta Ella kl 15:45”.
5. En rad visar en sak. Den får högst en sekundär rad med högst tre delar (” · ”). Upprepa aldrig rubrik, ikon, märke eller tid.
6. Tomt är tyst. En helt tom skärm har högst en mening på 8 ord plus en knapp. Skriv aldrig ”lägg till med +”.
7. Ikon med ord är standard. En ikon står ensam bara när den är entydig: +, ×, ›, ⌄, sök, dela, stjärna, kryssruta och väder. Alla ikoner har en contentDescription.
8. En sak heter samma sak överallt och nämns en gång per skärm.
9. Källor och licenser står i ”Om Pusslet”. Undantaget är ”Källa: Livsmedelsverket” inne i näringsvyn.
10. Högst ett varmt ord eller en emoji per skärm, och bara i tomlägen och klart-lägen. Kvitton har inget ”!”.

**Omskrivningar som gör störst skillnad:**

| Nyckel | Nytt (sv) | Nytt (en) |
|---|---|---|
| `today_weather_now` + `_range` → `today_weather_line` | %1$d° nu · %2$d–%3$d° | %1$d° now · %2$d–%3$d° |
| `today_weather_missing` | Välj ort (hela raden är knappen) | Choose place |
| `today_weather_unavailable` / `_stale` | Inget väder just nu / Väder från %1$s + CloudOff | No weather right now / Weather from %1$s |
| `today_packing_title` · `today_meals_title` · `today_tasks_title` · `today_upcoming_title` | Ska med · Mat · Att göra · Kommande | To bring · Food · To do · Coming up |
| `today_lunch` | Lunch: %2$s (%1$s) | Lunch: %2$s (%1$s) |
| `today_clash` | %1$s åt %2$s till lunch | %1$s had %2$s for lunch |
| `today_quiet` | Lugn dag ☕ | A calm day ☕ |
| `today_custody` (+ ny `today_custody_a11y`) | Hos %1$s (husikon) | With %1$s |
| `today_custody_handover` (`card_handover` tas bort) | Byte %1$s till %2$s | Changeover %1$s to %2$s |
| `card_party_gift` | Kalas %1$s – present? | Party %1$s – present? |
| `card_action_add_task` → ny `card_action_remind` | Påminn mig | Remind me |
| `card_nobody_pickup` / `_dropoff` | Ingen kan hämta %3$s kl %2$s | Nobody can pick up %3$s at %2$s |
| `today_handoff_nobody`, `event_handoff_none` | Inte bestämt (överallt) | Not decided |
| `calendar_layers` | Filtrera | Filter |
| `layer_family` / `_subscriptions` / `_device` | Familjen / Skola och förening / Mina kalendrar | Family / School and clubs / My calendars |
| `meals_plan_slot` → synlig ny `meals_plan_short` | Planera (den gamla blir contentDescription) | Plan |
| `meals_clash` | Samma som lunchen | Same as lunch |
| `meals_add_lunch` | Fyll i skollunch (en rad per vecka) | Add school lunch |
| `meals_busy_evening` | %2$s %1$s – något snabbt? | %2$s %1$s – something quick? |
| `meals_shop_for_week`, `meals_to_list_title`, `shopping_from_meals` | Handla till veckan | Shop for the week |
| `reason_quick` · `_uses_pantry` · `_varies` · `_not_lunch` | Snabbt · Finns hemma · Omväxling · Inte som lunchen | Quick · At home · Variety · Not like lunch |
| `nutrition_unavailable` / `nutrition_source` | Behöver nät första gången / Källa: Livsmedelsverket | Needs internet the first time / Source: Swedish Food Agency |
| `shopping_left_count` | %1$d kvar | %1$d left |
| `shopping_shopper_now` / `shopping_someone_shopping` | %1$s handlar nu / Någon handlar nu | %1$s is shopping now / Someone is shopping now |
| `shopping_add_hint` / `shopping_empty_body` | Lägg till vara / T.ex. ”2 l mjölk, bröd” | Add item / E.g. “2 l milk, bread” |
| `shopping_already_on_list` | %1$s fanns redan | %1$s was already there |
| `store_mode_finish` | Klar | Done |
| `quick_add` / `quickadd_title` | Bara contentDescription / Lägg till | Add / Add |
| Jargong: Ort för väder · Matcha ingredienser · Hemma-lager · Konto och synk · Egen RSS-länk · Symbol | Välj ort · Förbättra uppskattningen · Det vi har hemma · Konto och delning · Annan länk till matsedeln · Bild | Choose place · Improve estimate · What we have at home · Account and sharing · Other menu link · Picture |

**Tas bort från huvudskärmarna:**
- ”Prognos: SMHI” och andra källrader.
- Väderordet efter emojin. Det blir emojins contentDescription.
- ”Inget planerat” under tomma dagar i kalendern. TalkBack får det i rubriken.
- Årtalet när det är i år, och ”:00” i dagvyn.
- ”Från …” bredvid källmärket, och dubbel starttid.
- De sju gröna ”Recept”. De blir ›.
- En lunchrad per barn när barnen äter samma rätt.
- Näringskortets intro och ”Räkna ut”.
- ”Kvar att handla:”, initialbrickan och pennan.
- ”Visa”/”Dölj” som text. De blir ⌄.
- ”4 port. · Snabbt · Barnvänligt”.
- ”Steg” i ”Steg 1 av 3”.
- Undertexterna i Mer.

**Raderas** (0 användningar enligt grep): `empty_today`, `empty_calendar`, `empty_meals`, `empty_shopping`, `today_holiday`, `today_handoff_line`, `card_action_ask` och `offline_notice`.

**Nycklar:**
- En sträng med samma betydelse behåller sin nyckel. En ny betydelse får en ny nyckel.
- Strängar som blir contentDescription ligger kvar.
- Positionella `%n$s` gör den ändrade argumentordningen säker.

- *Filer:*
  - `shared/ui/src/commonMain/composeResources/values/strings.xml` och `values-en/strings.xml`
  - berörda `*Screen.kt` (borttagna `Text`)
  - ny `shared/ui/src/androidHostTest/kotlin/se/pusslet/ui/StringBudgetTest.kt`
  - `docs/DESIGN.md` och ägarens testanteckningar.
- *Klart när:*
  - `StringBudgetTest` kräver följande:
    - Båda filerna har samma nycklar.
    - Huvudskärmssträngar har högst 40 tecken, Problem högst 60.
    - Etiketter (`nav_*`, sektionsrubriker, `layer_*`, `reason_*`, `action_*`) har högst 3 ord.
    - ”!” finns bara i `shopping_all_done`, `store_mode_all_done_title` och `onboarding_done_title`.
    - ”med +” finns bara i `onboarding_done_body`, där ingen + syns.
  - Ordräkningen i `ShellScreenshotTest` räknar synliga textnoder ovanför bottenfältet (411×800 dp, 1,0, demodata). Gränsen är 40 ord för Idag och 45 för Kalender, Mat och Handla. Varje gräns slås på i flikens egen PR i Steg 2.
  - Testanteckningarna säger ”Handla till veckan” och ”Klar”.

### 1.2 En rund + (svar 3)

`ExtendedFloatingActionButton` blir `FloatingActionButton` med `Icons.Filled.Add`. Den har ingen synlig text, fälls inte ihop och har en contentDescription per skärm. + betyder ”lägg till det skärmen handlar om”. Flikrötternas listor får `contentPadding(bottom = listBottomInset)` med `listBottomInset = 88.dp`.

| Skärm | + gör | TalkBack | Tas bort |
|---|---|---|---|
| Idag | Öppnar snabbinmatningen med dagens datum | Lägg till | – |
| Kalender | Öppnar snabbinmatningen med visad vecka eller vald dag som datum | Lägg till | – (Dag-skärmen har ingen +. Ett tryck på en tom timme öppnar redigeraren.) |
| Mat › Matsedel | Öppnar planeringsarket för första oplanerade dag från idag i visad vecka. Dagen går att byta i arket (`WeekStripPicker`). | Planera middag | ”+ Planera middag” ×7 (blir den tysta raden ”Planera”, som öppnar samma ark) |
| Mat › Recept | Öppnar väljaren Från länk / Skriv in själv / Klistra in text | Nytt recept | Knappraden ”Importera från länk” + ”Nytt recept” |
| Handla | Öppnar listans eget fält ovanför tangentbordet, med fokus. Fältet står öppet för flera varor och bekräftelsen syns ovanför det. En tom lista öppnas med fältet framme. Tolkning och sammanslagning är som i dag. | Lägg till vara | Det fasta fältet överst |
| Butiker | Ny butik | Ny butik | Den utökade ”Lägg till butik” |
| Mer, undersidor, butiksläge, hjälparvy | Ingen + | – | – |

Snabbinmatningen nås från Idag och Kalender, inte från Mat och Handla. Det avviker från BRIEF §5 (ADR-023).

- *Varför:* Handlas fält och den globala + tolkade samma text på två olika sätt, och den utökade FAB:en täckte ”Ska med idag”.
- *Filer:* `shared/app/src/commonMain/kotlin/se/pusslet/shell/PussletApp.kt:175-182, 312-321`, `ShoppingScreen.kt:473-477`, `RecipeLibraryScreen.kt:65`, `MealsScreen.kt`, `StoresScreen.kt:130-136`, `shared/ui/.../theme/Spacing.kt` och flikrötternas LazyColumn.
- *Klart när:*
  - `grep -rn ExtendedFloatingActionButton shared/` ger 0 träffar.
  - Ett semantiktest per flik hittar exakt en lägg-till-nod med rätt contentDescription.
  - Sista raden ligger ovanför + när listan är utscrollad (gränskontroll).
  - ”mjölk” två gånger via + på Handla blir en rad, och bekräftelsen syns medan tangentbordet är öppet.

### 1.3–1.15

**1.3 Bottennavigering och färgroller.** Etiketterna visas alltid och deras fontScale begränsas till 1,3. Idag får en egen ikon (sol) och Mer får tre punkter. Indikatorn, SegmentedButton och FilterChip använder `primaryContainer`.
- *Filer:* `PussletApp.kt:330-347`, `shared/ui/.../components/PussletIcons.kt`, `shared/ui/.../theme/`.
- *Klart när:* `today_large_text.png` visar fem etiketter, Idag och Kalender har olika ikoner och vald flik syns i både ljust och mörkt läge.

**1.4 Minsta text 14 sp.** `bodySmall` och `labelMedium` blir 14/20.
- *Filer:* `shared/ui/.../theme/Type.kt`, `docs/DESIGN.md`.
- *Klart när:* ingen stil är under 14 sp och ingen text klipps vid 1,5.

**1.5 Tre uppmärksamhetsnivåer.**
- `Warning` blir ofylld med en terrakottafärgad ämnesikon (Gift, School).
- `Problem` behåller `errorContainer` och PriorityHigh och har alltid en åtgärd.
- `Info` blir en enkel rad.
- *Filer:* `shared/ui/.../components/Containers.kt:82-99`, `PussletIcons.kt`.
- *Klart när:* i `today_dark.png` är kalaspåminnelsen ofylld, medan ett Problem är fyllt och har en knapp.

**1.6 Inga låtsasknappar.** En ny `InfoPill` (tonad, ingen ripple) används för ”Vecka 41”, helgdagar och taggar. ”Upprepas” och ”Plats” i snabbinmatningen blir vanlig text.
- *Filer:* `TodayScreen.kt:146,163`, `EventDetailsScreen.kt:278`, `QuickAddSheet.kt:265-273`, ny `shared/ui/.../components/InfoPill.kt`.
- *Klart när:* `grep -rn "onClick = {}" shared/` ger 0 träffar.

**1.7 Klädråd grupperade.** `today_clothes_for` tas i bruk: ”Ella och Simon: tunn jacka · reflexväst”.
- *Filer:* `TodayScreen.kt:223-243`, ny `shared/core/.../ClothingGrouping.kt` med test.
- *Klart när:* `today_light.png` visar en rad med båda namnen.

**1.8 Snackbaren säger var det hamnade.**
- Texten byggs från `QuickAddOutcome`: ”2 varor på listan · Visa”, ”I kalendern lör 14:00 · Visa” och ”Ny hämtning kl 15:30 (fanns ingen)”.
- Ingen Ångra när bara en lämning eller hämtning har ändrats.
- ”Sparat” skrivs utan ”!”.
- Klar-tangenten sparar inte.
- *Filer:* `PussletApp.kt:161-171`, `shared/app/.../shell/quickadd/QuickAddExecutor.kt:42-43`, `QuickAddSheet.kt:123-124`.
- *Klart när (för hand):* ”mjölk, bröd” ger ”2 varor på listan · Visa”, och Visa öppnar Handla.

**1.9 Handla: ångra och tomläge.**
- ”Mjölk bockad · Ångra” visas även på planeringslistan.
- Utanför butiksläget heter avsnittet ”Avbockat (n)”.
- ”Rensa avbockade” ger ”6 varor rensade · Ångra”.
- En ny lista visar ”Listan är tom”.
- ”Jag handlar nu” döljs när inget är kvar.
- *Filer:* `ShoppingViewModel.kt:256-263, 280`, `ShoppingScreen.kt:350, 498, 546-557`.
- *Klart när (för hand):* efter avbockning och Ångra ligger varan i samma avdelning igen.

**1.10 Kalendern fastnar inte i Dag.** Ett tryck på ett datum sparar inte Dag som vy, och ”Idag” scrollar alltid till idag.
- *Filer:* `CalendarViewModel.kt:96-103`, `CalendarScreen.kt:245`.
- *Klart när:* efter ett tryck på ”9 Fredag” och en omstart öppnas Kalender i Vecka.

**1.11 Mat: idag först.**
- Listan scrollar till dagens kort, som är fokusytan i havre med en sage ”Idag”-pill, som i Kalender.
- Ingen lunchuppmaning visas på helger, röda dagar eller lov.
- Näringens Unavailable-lägen får ”Försök igen”.
- *Filer:* `MealsScreen.kt:197, 331-342, 433, 560-567`, `RecipeDetailsScreen.kt:331-336`.
- *Klart när:* en fredag syns fredagens kort helt, och helgen har inga lunchrader.

**1.12 Mer utan återvändsgränd.** ”Uppgifter och sysslor” döljs tills uppgiftsfasen är klar. Raderna går i full bredd med chevron.
- *Filer:* `MoreScreen.kt:35-72`, `PussletApp.kt:265`.
- *Klart när:* `more.png` saknar ”Uppgifter” och har chevroner.

**1.13 Hjälpare får bara knappar som fungerar.** För `Role.Caregiver` döljs följande:
- ”Jag tar det” och ”Lägg till uppgift” på Idag
- +
- ”Ny händelse” och tryck på tom timme i Kalender
- väderort och kalenderexport.

I dag nekar Firestore skrivningen. `SyncEngine.kt:258-267` slänger ändringen men behåller det lokala värdet, så hjälparens telefon visar något som aldrig når familjen.
- *Filer:* `TodayScreen.kt:292-294, 307-312`, `TodayViewModel.kt`, `PussletApp.kt`, `CalendarScreen.kt`, `SettingsScreen.kt`.
- *Klart när:* semantiktester med `role = Caregiver` hittar ingen av knapparna.

**1.14 Riktig första start i demo.** En utvecklarrad (bara demo och dev), ”Visa första start igen”, nollställer `OnboardingDone` och exempeldata. Demo visar en tyst Info-rad: ”Exempelfamilj”.
- *Filer:* `AppCoordinator.kt:74-77`, `SettingsScreen.kt`, `composeApp/build.gradle.kts:93`.
- *Klart när (för hand):* välkomstskärmen går att visa igen.

**1.15 Första barnet blir inte terrakotta.** Barn från onboarding börjar på Ocean eller Plum.
- *Filer:* `OnboardingViewModel.kt:104-106`.
- *Klart när:* ett enhetstest visar att första barnet inte får `MemberColor.Terracotta`.

---

## 3. Steg 2 – Struktur per flik

**2.0 Gemensam grund.**
- *Komponenter i `shared/ui/components`:*
  - `TabHeader(title, subtitle, actions ≤ 2)`, `WeekSwitcher`, `Section` (platt, med ikon och åtgärdsplats)
  - `HeroCard`/`SentenceRow`, `Attention(level)`, `NavRow`, `QuietLine`, `OutlinedValueField`, `WeekStripPicker`
  - `HandoffLine` (från `TodayScreen.kt:357-385`), `ReorderButtons` (från `AisleOrderRow`, `StoresScreen.kt:281-310`) och `undoSnackbar`.
- *I core:* `TodaySummary`, `RoutineClassifier`, `LunchMerge` och listsammanfogning (”Simon och Ella”) för sv och en. `sectionGap = 32.dp`.
- *Klart när:* varje komponent har goldens i ljust och mörkt läge och vid 1,5. Core-testerna täcker flyttad hämtning, inställt tillfälle och saknad ansvarig.

### Idag: anpassningsbar, svaret först

**Skärmens ordning** (fast):
1. Rubriken ”Torsdag 8 oktober” med underraden ”Vecka 41”. Vid växelvis boende läggs ”· Hos Ahmed” till, och på bytesdagen ”· Byte 15:00 till Ahmed”. Ingen hälsning.
2. **Att lösa:** Problem idag och imorgon samt obesvarade förfrågningar till mig. Går inte att dölja.
3. **Kom igång** (4.4): bara för en ny familj och tills den döljs. Morgonkollen ritas då platt under den.
4. **Morgonkollen:** havrefärgad fokusyta utan synlig rubrik, högst fem rader. Rader vars tid har passerat försvinner.
5. **Sektionerna:** platta och i användarens ordning.
6. `QuietLine` ”Inget mer idag” när ingen sektion har innehåll.
7. `NavRow` ”Anpassa Idag ›”, alltid sist.

```
Torsdag 8 oktober
Vecka 41
┌──────────────────────────────────────────────┐
│ Johan lämnar Simon 07:50 och Ella 08:05    › │
│ Morfar hämtar Simon 15:30 och Ella 15:45   › │
│ Ska med: gympapåse (Ella)                  ⌄ │
│ Ikväll: Fiskgratäng                        › │
│   Lunch: Köttbullar (Ella, Simon)            │
│ ☁ 14° · Ella och Simon: tunn jacka           │
└──────────────────────────────────────────────┘
Idag händer
  17:30  Ella simskola · Valhallabadet       ›
Kommande
  Lördag  14:00 Kalas Ella – present?  [Påminn mig] ×
               Anpassa Idag ›                   (+)
```

**Block.** Varje block har ett id i ASCII som aldrig byter namn och aldrig återanvänds. Ett tomt block syns inte.

| Id | Namn | Visar | Förälder |
|---|---|---|---|
| `handoffs` | Lämning och hämtning | ”Johan lämnar Simon 07:50 och Ella 08:05”. Egna rader står som ”Du …” i fetstil. Ett tryck öppnar händelsen. | På |
| `packing` | Ska med | En rad som fälls ut till kryssrutor per barn. ”Allt är packat” står kvar tills sista lämningen har passerat. | På |
| `dinner` | Mat | ”Ikväll: …” och högst en underrad (sammanslagen lunch eller krock). Saknas middag visas ”Planera middag”, men bara före 18:00 och bara om familjen har planerat en middag den här eller förra veckan. Döljs efter 20:00. | På |
| `weather` | Väder och kläder | ”☁ 14° · Ella och Simon: tunn jacka”. Från 18:00 gäller raden morgondagen: ”🌧 I morgon 2–4° · Ella och Simon: regnkläder” (ADR-022). Döljs om ort saknas. | På |
| `timeline` | Idag händer | Tid, namn och plats. Lämning och hämtning visas bara här när `handoffs` är av. En nu-linje visas. Efter 5 rader: ”Visa hela dagen ›”. | På |
| `tasks` | Att göra | Mina öppna uppgifter. Efter 3: ”Visa alla (n) ›”. | På |
| `upcoming` | Kommande | 3 dagar framåt: händelser utom rutiner, röda dagar och påminnelser med åtgärd (kalas, studiedag, klämdag). Inga middagar. | På |
| `shopping` | Inköpslistan | ”Veckohandling: 5 kvar” eller ”Sara handlar nu” | Av |

- **Går inte att dölja:**
  - rubriken med veckonumret
  - Problem: fyllda, med åtgärd, högst 3 och sedan ”Fler att lösa (n) ›”
  - förfrågningar, till exempel ”Sara frågar om du kan hämta Ella fre 15:45 · Svara”.

  En konflikt kring en lämning visas bara här, inte i Morgonkollen.
- **Ett faktum en gång:** väderkortet och kontextbannrarna (`HandoverSoon`, kalas, studiedag, klämdag) försvinner som egna kort. Vädret visas i Morgonkollen, påminnelserna i Kommande och vårdnaden i rubriken.
- **Lugnt:** standard är Morgonkollen och högst tre sektioner. Nya block i senare versioner kommer avstängda. Orden ”modul”, ”widget” och ”layout” används inte i appen.

**Anpassa Idag** (`Route.TodayLayout`) är en egen skärm, eftersom ett ark inte rymmer raderna vid 200 % text.

```
←  Anpassa Idag
Det du stänger av finns kvar i flikarna. Det som måste lösas visas alltid.

Morgonkollen
Lämning och hämtning            (●)
Ska med                         (●)
Mat                             (●)
Väder och kläder                (●)

Längre ned
Idag händer        [↑] [↓]      (●)
Att göra           [↑] [↓]      (●)
Kommande           [↑] [↓]      (●)
Inköpslistan       [↑] [↓]      ( )

Återställ till standard
```

- **Switchar:**
  - Ändringar gäller direkt.
  - Hela raden är switchen (`SwitchRow`, `Role.Switch`, minst 56 dp).
  - Raderna har ingen beskrivning, utom ”Välj ort i Inställningar” när ort saknas.
- **Flytta:**
  - Bara sektionerna går att flytta, med `ReorderButtons` på 48 dp och contentDescription ”Flytta upp Idag händer”.
  - Översta uppåtpilen och nedersta nedåtpilen är inaktiverade. En avstängd sektion behåller sin plats.
  - TalkBack har anpassade åtgärder och en live region: ”Idag händer, plats 2 av 4”.
  - Vid fontScale 1,5 eller mer hamnar pilarna på en egen rad.
- **Morgonkollen:** raderna har fast ordning. Stängs alla fyra av försvinner kortet.
- **Återställ:** syns bara när något är ändrat och ger snackbaren ”Idag är som från början igen · Ångra”.
- **Ingångar:** raden sist på Idag och Inställningar › Utseende. Ingen long-press, ingen ikon i rubriken och inget tips.

**Lagring och arkitektur:**
- **Nyckel:** `Prefs.TodayLayout = PrefKey("today.layout", StoredTodayLayout.serializer().nullable, null)` i Room (ADR-012).
- **Per enhet, inte synkad:** det är en visningsinställning som `ui.theme`. Därför behövs inga nya regler eller migreringar, och Data safety ändras inte. En ny telefon börjar med standardläget.
- **Modell:** `StoredTodayLayout(version, memberId, sectionOrder, visible)` sparar bara det som har ändrats. `null` följer rollens standard.
- **Sammanslagning:** `TodayLayouts.resolve(role, memberId, stored)` ignorerar okända id och lägger nya id på sin standardplats. Ett annat `memberId` eller trasig JSON ger standard.
- **core:** ny `today/TodayBlocks.kt` med `TodayBlock(id, placement, roles)` och `TodayLayouts` (`defaultFor`, `resolve`, `move`, `setVisible`, `visibleBlocks`). `TodayPlanner` får reglerna för tomma block och `dinnerPlannedRecently`.
- **feature-today:** `TodayViewModel` får `PreferencesRepository`, `CalendarRepository.handoffRequests` och `ShoppingRepository`. Det sista läses bara när `shopping` är på. `TodayUiState` får `hero`, `sections`, `requests` och `quiet`.

**Klart när:**
1. **`TodayLayoutsTest`:**
   - Förälderns standard är Morgonkollen `[handoffs, packing, dinner, weather]`, alla på, och sektionerna `[timeline, tasks, upcoming]` på med `shopping` av.
   - `resolve` följer reglerna ovan, och `null` följer ett rollbyte.
   - `move` gör ingenting i ytterlägena.
2. **Tomma block** finns inte i `visibleBlocks`. Utan innehåll i någon sektion blir `quiet = true`. ”Planera middag” följer 18:00-regeln.
3. **Ett faktum en gång:** med `handoffs` på har Idag händer inga lämningsrader, och med den av har den det. En lämning utan ansvarig syns bara under Att lösa.
4. **Fast innehåll:** med alla block av syns ändå rubriken med vecka och Problemet. Täcks av ett VM-test och goldenen `today_all_off`.
5. **Beständighet:** stäng av Att göra, flytta Kommande överst och starta om. Valen finns kvar (VM-test mot Room i minnet). Återställ och Ångra testas också i VM.
6. **Anpassa:** goldens `today_layout` och `today_layout_large_text` (2,0, utan klippt text och utan horisontell scroll). Switch-raderna är minst 56 dp och pilarna 48 dp med blockets namn.
7. **`today_default`** (411×800, 1,0): de fyra svaren syns ovanför bottenfältet, med högst tre sektioner och högst 40 ord. + täcker inte ”Anpassa Idag ›”. Inget ändras i `functions/`.

**Filer:**
- **core:** `shared/core/src/commonMain/kotlin/se/pusslet/core/today/TodayBlocks.kt` (ny), `TodayPlanner.kt` och testet `commonTest/.../today/TodayLayoutsTest.kt` (nytt).
- **data:** `shared/data/src/commonMain/kotlin/se/pusslet/data/repository/PreferencesRepository.kt`.
- **feature-today:** i `shared/feature-today/src/commonMain/kotlin/se/pusslet/feature/today/` ändras `TodayViewModel`, `TodayScreen` och `TodayModule`. Nya filer: `TodayHero`, `TodaySections`, `TodayLayoutScreen` och `TodayLayoutViewModel`. Tester läggs i `commonTest`; modulen har inga i dag.
- **ui:** `shared/ui/.../components/ReorderButtons.kt` (ny, används även i `StoresScreen.kt`).
- **app och inställningar:** `shared/app/.../shell/navigation/Routes.kt`, `PussletApp.kt` och `shared/feature-settings/.../SettingsScreen.kt`.
- **Strängar:** nya `today_customize`, `today_layout_*`, `today_block_*`, `action_move_*_named`, `today_request_*` och `today_quiet_end`. Bort: `today_packing_empty`, `today_no_tasks`, `today_upcoming_nothing` och `card_handover`.
- **Test:** `ShellScreenshotTest.kt` och `TestEnvironment.kt`.

PR A: `feat(today): render Idag from a block layout`. PR B: `feat(today): customise Idag`. Idag-widgeten och morgonnotisen följer inte layouten.

### Kalender (`CalendarScreen.kt`, `CalendarViewModel.kt`, `EventDetailsScreen.kt`, `EventEditorScreen.kt`)

1. **En kontrollrad:** `WeekSwitcher` (”Vecka 41 · oktober”, ‹ › till höger, ”Idag” bara när man är på en annan vecka), segmentet ”Vecka | Månad” och en Filter-ikon med prick när ett filter är aktivt. Dag blir `Route.CalendarDay`.
   - *Klart när:* första händelsen ligger högst 130 dp från toppen och minst 5 dagar syns.
2. **Rutiner blir tysta:** skolserier blir en rad under datumet: ”Simon: Johan lämnar 07:50 · Morfar hämtar 15:30”. Utan ansvarig står ”Inte bestämt” i felfärg med ikon och ”Jag tar det”.
   - *Klart när:* en torsdag i demon har ingen rad ”Förskola 08:00–16:00”, men en lämningsrad med namn.
3. **Filter i ett bottenark** (”Filtrera”): barnen först, sedan lagren. Ett aktivt filter ger raden ”Bara Simon · Visa alla”.
   - *Klart när:* ett aktivt filter syns aldrig bara i ett dolt chip.
4. **Händelserad:** starttiden står till vänster, med underraden ”Simon · till 16:00 · Vallen”. Källmärket står utan ”Från …”.
   - *Klart när:* ingen tid står två gånger på samma rad.
5. **Månad:** bara händelser som inte är rutiner får prickar, högst 3 och sedan ”+N”. Röda dagar får ett kort helgdagsnamn. Nedre halvan visar vald dag.
   - *Klart när:* ett tryck på en dag lämnar inte månadsvyn.
6. **Händelsedetaljer:** varje lämning och hämtning blir en mening: ”Johan lämnar 08:05 · Ändra”. ”Jag tar det” är fylld bara när ingen är satt.
   - *Klart när:* `event_details.png` har högst en fylld knapp.
7. **Redigeraren:** Titel → Vem gäller det? → När → ”Vem lämnar/hämtar Simon?”, med ”Inte bestämt” som standard. Resten ligger under ”Fler detaljer” och ”Spara” sitter fast längst ned.
   - *Klart när:* de fyra första fälten syns utan att man scrollar.
8. **Växelvis boende:** veckan får raden ”Hos Ahmed (mån–sön)”. Ett vårdnadsschema läggs till i `DemoSeed.kt`.
   - *Klart när:* raden syns i `calendar_week.png`.

### Mat (`MealsScreen.kt`, `MealsViewModel.kt`, `DayPlanSheet.kt`, `Recipe*Screen.kt`)

1. **Samma header som Kalender:** `WeekSwitcher`, sedan ”Matsedel | Recept”. ⋮ innehåller bara favoritveckor.
   - *Klart när:* headern ser likadan ut som Kalenders i goldens.
2. **Dagkortet:** veckodagen i titleMedium, middagen i bodyLarge och högst en sekundär rad. › ersätter knappen ”Recept”, och ett tryck öppnar en läsvy med rätt, ”Visa recept”, lunch och ”Ändra”. En tom dag visar den tysta raden ”Planera”.
   - *Klart när:* måndagens kort har högst 3 textrader.
3. **En lunchrad:** ”Lunch: Köttbullar (Ella, Simon)”. Krocken visas en gång: ”Samma som lunchen”, med terrakottaikon. Manuell lunch fylls i via raden ”Fyll i skollunch ›”, en gång per vecka.
   - *Klart när:* veckan aldrig har fler lunchrader än middagsrader.
4. **Planeringsarket:** ett tryck på ett förslag planerar, stänger arket och visar ”Tis: Ugnspannkaka · Ångra”.
   - Arket visar 3 förslag, ”Visa fler” och högst 2 skäl på en rad.
   - ”Gör dubbelt”, ”Anteckning”, ”Flytta till…” och ”Ta bort” ligger under ”Fler val”.
   - *Klart när:* ett förslag väljs med ett tryck och ingen knapp ligger under vecket.
5. **Ångra överallt:** alla ändringar i planen returnerar tidigare poster, och `MealsRoute` får en SnackbarHost.
   - *Klart när:* varje ändring går att ångra i ett VM-test.
6. **Bron till Handla:** en tonad knapp ”Handla till veckan” efter söndag. Näringen blir en hopfälld `NavRow`, ”Näring för veckan ›”.
   - *Klart när:* knappen heter likadant i Mat, Handla och arket.
7. **Recept:** + öppnar väljaren (1.2). Raden i biblioteket visar ”45 min · ICA” och en stjärna.
   - Receptvyn har portionerna i ingredienshuvudet och en fast bottenrad med ”Lägg på inköpslistan” (fylld) och ”Lägg i matsedeln” (tonad).
   - Näringen visas som raden ”Näring · ca 520 kcal per portion ›”, och källan som länkikon och ”ica.se”.
   - Redigeraren visar fyra fält först och resten under ”Mer (valfritt)”. Tillbaka frågar ”Släng ändringarna?”.
   - *Klart när:* biblioteket har exakt ett sätt att lägga till.

### Handla (`ShoppingScreen.kt`, `ShoppingViewModel.kt`, `StoreModeScreen.kt`, `ShoppingDialogs.kt`, `StoresScreen.kt`, `PantryScreen.kt`)

1. **Listans huvud:**
   - Rubriken ”Veckohandling ▾” öppnar en meny med listor, ”Ny lista…”, byt namn och ta bort. Den ersätter chipraden.
   - Underraden ”5 kvar · ICA Nära Lindvägen ▾” har en delningsikon och en tonad ”Jag handlar nu”, som bara visas när något är kvar.
   - Man lägger till via + (1.2). ”Butiker och det vi har hemma ›” ligger sist.
   - *Klart när:* minst 8 varor syns ovanför vecket på 800 dp.
2. **Rader som på en papperslista:** kryssruta, namn och en detaljrad (”2 l · till Tacos fre”).
   - Ingen penna och ingen initialbricka. Texten öppnar redigering, och TalkBack har egna åtgärder.
   - En bockad rad står kvar överstruken i cirka 2 sekunder.
   - *Klart när:* ingen rad har fler än 3 element.
3. **Förslag efter varorna:** en rad, ”Brukar ni köpa: mjölk, bröd ⌄”, med × per chip.
   - *Klart när:* förslagen tar en rad när de är hopfällda.
4. **Handla till veckan:** en knapp i tomläget, och ett kort som går att stänga när recept från veckan saknas på listan. Arket grupperar varorna under Läggs till, Ändras och Tas bort.
   - *Klart när:* ingen knapp ”Från matsedeln” finns ovanför listan.
5. **Vem handlar:** ”Sara handlar (Apoteket) · Byt”. Flera personer slås ihop till en rad.
   - *Klart när:* två som handlar samtidigt visas på en rad.
6. **Redigera vara:** Enhet och Avdelning blir dropdown-fält, och ”Ingen enhet” ersätter ”–”. Under Avdelning står ”Sparas till nästa gång”, och priset ligger under ”Fler detaljer”.
   - *Klart när:* ”–” aldrig visas som värde.
7. **Butiksläge:**
   - Titeln är ”ICA Nära Lindvägen”, med underraden ”3 av 8 i vagnen” och en förloppsindikator. ”Klar” syns alltid.
   - Snackbaren med Ångra visas bara vid första bocken under besöket. Varor i ”I vagnen” går att trycka tillbaka.
   - ”Allt i vagnen!” ligger nederst med ”Klar – töm vagnen” (med Ångra) och ”Behåll avbockade”.
   - *Klart när:* kortet syns var man än har scrollat när sista varan bockas av.
8. **Butiker:** ”Spara” i toppfältet och en fråga vid osparade ändringar. Orden ”Vår vanliga butik” och ”Vanlig ordning”, och ordningen ändras med `ReorderButtons`.
   - *Klart när:* Tillbaka med osparade ändringar ger frågan.

### Mer (`MoreScreen.kt`, `SettingsScreen.kt`, `FamilyScreen.kt`, `ChildEditorScreen.kt`)

1. **Två grupper:** ”Familjen och vardagen” och ”Appen”, med ”Om Pusslet” sist. Den samlar källor och licenser. Raderna har ingen undertext.
   - *Klart när:* `more.png` visar två grupper.
2. **Familjen:** varje vuxen som inte har gått med får en tonad ”Bjud in {namn}”. Info-raden lyder ”Bara på den här telefonen · Logga in”.
   - *Klart när:* det går att bjuda in med ett tryck.
3. **Inställningar och barnredigeraren:** platta grupper (Handla, Mat, och Utseende med ”Anpassa Idag ›”). Skolfältet heter ”Skola (valfritt)”. Det finns en Spara, och ”Ta bort” står längst ned.
   - *Klart när:* ”–” aldrig visas som värde.

---

## 4. Steg 3 – Hjälparvy (svar 4)

**Läget i dag:**
- Hjälpare har samma flikar och + som föräldrar (`PussletApp.kt:137-198`). Knapparna rättas i 1.13.
- Bengt kan inte svara på förfrågningar. Han får bara en Warning (`EventDetailsScreen.kt:355-359`), och föräldrarna ser aldrig något svar (rad 124-127).
- `TaskScheduler.openFor` tar inte med delade uppgifter utan ansvarig.
- `firestore.rules:164` låter hjälpare skriva vad som helst i `handoffRequests`.
- Prenumerationer får `sharedWithCaregivers = true` lokalt (`CalendarRepository.kt:138`) men når aldrig hjälparen.
- Ett rollbyte når varken `access` eller `Session.role` (`FamilyRepository.kt:64, 135`). Det rättas i en egen PR före H2.

**Principer:**
- Föräldrarna bestämmer vad som delas, och Bengt bestämmer vad som visas.
- Vyn styr bara presentationen och är aldrig en behörighet.
- Svaret står först, som meningar med ”Du”.
- Vyn har bara handlingar som reglerna tillåter.

```
Torsdag 8 oktober
Vecka 41
┌ Sara undrar ─────────────────────────────┐   kantad
│ Kan du hämta Ella fredag 16:30?          │
│ [ Ja, jag hämtar Ella               ]    │   fylld, 64 dp
│ [ Nej, jag kan inte                 ]    │   kantad, 64 dp
└──────────────────────────────────────────┘
Idag                                            havre fokusyta
Du hämtar Simon 15:30
  Förskolan Ekorren
Du hämtar Ella 15:45
  Björkängsskolan · Efteråt: Dans 17:15
Jag kan inte idag                               TextButton
Att göra · Kommande (vecka 42) …
Hela veckan ›          Inställningar och hjälp ›
```

| Del | Regel | Kan döljas |
|---|---|---|
| Förfrågningar | Status `Pending`, till Bengt eller till alla, och inte avböjd av honom (`declines`). Tillfället är delat med honom och är idag eller senare. Bara den första får knappar, resten blir ”1 fråga till ›”. | Nej |
| Idag (`handoffs`, bara mitt) | Uppdrag där Bengt faktiskt är ansvarig (`EffectiveHandoffs`). Underraderna är ”Ta med: …” (högst 3) och ”Efteråt: …” (barnets delade händelser före 20:00, högst 2). Raden står kvar 60 minuter efter klockslaget och blir sedan ”Klart för idag”. ”Snart” visas från 60 minuter före. Utan uppdrag: ”Inget för dig idag.” och ”Nästa gång: torsdag 15 oktober”. | Nej |
| Ska med (`packing`) | Bara för barn han lämnar, plus det som ska med vid ett byte | Ja, på |
| Väder (`weather`) | En underrad, bara vid nederbörd eller under 5°: ”Regn vid hämtningen, 9°” | Ja, på |
| Att göra (`tasks`) | Delade uppgifter till honom, eller uppgifter utan ansvarig en dag då han har uppdrag. Högst 3. | Ja, på |
| Kommande (`upcoming`, bara mitt) | Hans uppdrag de kommande 7 dagarna, högst 3 dagar | Ja, på |
| Inköpslistan (`shopping`) | ”Inköpslistan · 5 kvar ›” | Ja, av |

- **Samma modell som Idag:** blockmodellen och `Prefs.TodayLayout` är desamma. `defaultFor(Caregiver)` ger tabellens standard, och rollen har varken `dinner` eller `timeline`. Startsidan anpassas under ”Inställningar och hjälp › Anpassa startsidan”, med samma skärm som för föräldrar.
- **Hela veckan:** 14 dagar med allt som delas med honom. Hans egna uppdrag står som ”Du …” i fetstil.
- **Bengt ser inte:**
  - bottenfältet, + och snabbinmatningen
  - kalenderns vyer, Problem, kalas och vårdnadsschemat
  - matsedeln, listhanteringen och familjeinställningarna
  - allergier och barnens kontakter och anteckningar
  - händelser som bara är för föräldrar, prenumerationer och enhetskalendrar.
- **Navigation:** `HelperShell` har ingen `NavigationBar` och ingen FAB. Roten är `Route.HelperHome`, och navigeringen sköts av en egen `HelperNavigator`. Undersidorna är högst två nivåer djupa:
  - `HelperWeek`
  - `EventDetails` (skrivskyddad, med Ja/Nej)
  - `Shopping` (`canManage = false`). Fältet står fast, eftersom vyn saknar +.
  - `HelperSettings`
  - `Account`, `Privacy` och `AboutData`.
- **Mått (`HelperTheme`):**
  - Datum 28/36, uppdrag 22/30 (namn och tid i SemiBold), brödtext 20/28 och knapp 18/24. Ingen text är mindre än 18 sp.
  - Knappar och rader är minst 64 dp med 12 dp mellanrum. Ja och Nej staplas.
  - Inga ensamma ikoner utom Tillbaka, och inga svep, långtryck, chips eller `maxLines`.
  - TalkBack läser ett uppdrag som en nod, och knapparna heter hela meningen.

**Svara:**
- **Ja:** ger `Accepted`, `respondedBy` och `respondedAt`. Kortet visar ”Du sa ja. Sara ser det.” och ”Ändra svar”.
  - `EffectiveHandoffs` visar uppdraget direkt, både hos Bengt och i föräldrarnas Idag och Kalender.
  - Den första föräldertelefonen som ser svaret skriver in det med `HandoffAssigner.assign` och sätter `appliedAt`. Det ger samma resultat om det körs flera gånger.
- **Nej:** ger `Declined` om förfrågan gick till honom, och annars `declines[Bengt]`. `declines` är ett map-fält som slås ihop per nyckel.
- **Ändra svar:** går så länge `appliedAt` är `null`.
- **Jag kan inte idag:** ett ark där dagens uppdrag är förvalda.
  - ”Säg till Sara och Johan” skapar förfrågningar med `fromMemberId = Bengt`, `toMemberId = null` och status `Pending`.
  - Föräldrarna får Problemet ”Bengt kan inte hämta Simon 15:30 · Jag tar det”.
  - När någon tar över ser Bengt ”Johan hämtar Simon i stället”.
- **Utan nät:** kortet visar ”Skickas när du har nät”.

**Val av vy:** `ShellViewModel.mode = Helper` när `role == Caregiver && !Prefs.HelperFullView`. ”Visa hela appen” finns under Inställningar och hjälp, och ”Enkel vy” överst i Mer. Valet sparas lokalt och är ingen behörighet.

**Förhandsvisning för föräldrar (”Så ser Bengt appen”):**
- **Ingång:** bara för föräldrar, på rader för en annan vuxen i Familjen, i medlemsredigeraren och i inbjudan.
- **Innehåll:** `Route.HelperPreview(memberId)` visar samma skärm, med data filtrerad genom `CaregiverVisibility`. Filtret släpper bara igenom familjens egna händelser med `sharedWithCaregivers`, aldrig prenumerationer eller enhetskalendrar, och bara delade uppgifter.
- **Text:** överst står ”Förhandsvisning. Det du trycker på här sparas inte.” och längst ned ”Bengt ser inte 2 händelser den här veckan.”
- Fungerar innan Bengt har gått med.

**Data och regler:**
- **Modell:** `HandoffRequest` får `declines` (i `mapFields`) och `appliedAt`. `PackingReason.ForEvent` får en starttid.
- **Svar:** en hjälpare får bara ändra `status`, `respondedBy` (sig själv), `respondedAt` och sin egen nyckel i `declines`. Det gäller förfrågningar till sig eller till alla som fortfarande är `pending`. Ändra svar går bara medan `appliedAt` saknas.
- **Skapa:** bara med `fromMemberId == mig`, `toMemberId == null` och status `pending`.
- **Föräldrar:** ”Kan någon hämta?” stängs för händelser som inte delas.
- **Loggar:** Kermit loggar bara id:n och antal.

**Klart när:**
1. **Rollval:** `FixedSessionProvider(role = Caregiver, memberId = "m-bengt")` öppnar `HelperHome` utan `NavigationBar` och FAB.
2. **Goldens:**
   - `helper_home`: ”Sara undrar” står överst och båda hämtningarna i fokusytan. Orden ”Styrelsemöte”, ”Kalas”, ”Middag” och ”Skollunch” finns inte. Allt syns utan scroll på 411×800 vid 1,0.
   - Även `helper_home_large_text` (2,0, där Ja syns utan scroll), `helper_home_dark` och `helper_nothing_today`.
3. **Mått:** inget i `HelperTypography` är under 18 sp. Klickbara noder är minst 56 dp och Ja/Nej minst 64 dp.
4. **`HelperPlannerTest`:**
   - privata händelser, prenumerationer och enhetskalendrar utesluts
   - bara faktiska uppdrag tas med
   - en accepterad förfrågan räknas innan den är inskriven
   - förfrågningsfiltren
   - Ta med och Efteråt
   - uppgiftsreglerna
   - 60-minutersregeln och Snart
   - Nästa gång.
5. **Paritet:** planeraren ger samma resultat på `CaregiverVisibility.filter(alla demodata)` som på det som synkas till hjälparen.
6. **VM-tester:**
   - Ja ger `Accepted` med `respondedBy = m-bengt`.
   - Nej ger `Declined` eller `declines["m-bengt"]`.
   - Inget skrivs i `events`, och förhandsvisningen skriver ingenting.
7. **Regeltester** (`functions/test/rules.test.ts`, ”caregivers (morfar)”):
   - En hjälpare kan svara på förfrågningar till sig själv eller till alla.
   - Hen kan inte svara åt någon annan, på en förfrågan till någon annan eller på en redan besvarad förfrågan.
   - Hen kan inte ändra `eventId`, `fromMemberId`, `toMemberId` eller `kind`.
   - Hen kan skapa ”Jag kan inte” från sig själv men inte åt någon annan.
8. **Inskrivning:** ger exakt en override med `pickUp = m-bengt` och sätter `appliedAt`. Den ger samma resultat varje gång och körs aldrig på en hjälpares telefon.
9. **Vy och föräldrar:**
   - `Prefs.HelperFullView` byter skal direkt och gäller efter omstart.
   - ”Så ser Bengt appen” syns bara för föräldrar (golden `helper_preview`).
   - Föräldrarna ser svaren i Händelsedetaljer och som Problem på Idag.
10. **Strängar:** finns på båda språken och `StringResourcesTest` är grönt. Inga namn i loggarna.

**Filer:**
- **core:** `core/helper/HelperPlanner.kt`, `core/helper/CaregiverVisibility.kt` och `core/calendar/EffectiveHandoffs.kt` (nya, med tester), samt `model/Tasks.kt`, `model/Packing.kt`, `PackingPlanner` och `today/TodayPlanner.kt`.
- **data:** `sync/Collections.kt`, `usecase/HandoffRequests.kt` och `usecase/AcceptedRequestApplier.kt` (nya), `AppCoordinator.kt`, `PreferencesRepository.kt` (`Prefs.HelperFullView`) och `seed/DemoSeed.kt`.
- **feature-today:** `shared/feature-today/.../today/helper/` med `HelperHomeScreen`, `HelperViewModel`, `HelperWeekScreen`, `HelperSettingsScreen`, `HelperPreviewRoute` och `CannotMakeSheet`.
- **ui:** `shared/ui/.../theme/HelperTheme.kt`.
- **app:** `shared/app/.../shell/ShellViewModel.kt`, `PussletApp.kt`, `navigation/Routes.kt` och `navigation/HelperNavigator.kt` (ny).
- **Andra skärmar:** `EventDetailsScreen.kt`, `FamilyScreen.kt`, `MemberEditorScreen.kt`, `InviteScreen.kt`, `MoreScreen.kt` och `SettingsScreen.kt`.
- **Regler och test:** `firestore.rules`, `functions/test/rules.test.ts`, `ShellScreenshotTest.kt` och `TestEnvironment.kt` (session som `DemoSeed.BENGT`).
- **Strängar:** `today_you_pickup` och `_dropoff` (används även för förälderns egna rader), `helper_*`, `request_answer_*` och `card_helper_cannot_pickup`.

---

## 5. Steg 4 – Första användningen

**4.1 Fler vuxna.** Onboardingens steg 1 får en valfri fråga, ”Vilka fler vuxna hämtar och lämnar?”, som bara tar namn.
- *Filer:* `OnboardingScreen.kt:170-196`, `OnboardingViewModel.kt`.
- *Klart när:* en vuxen som läggs till där går att välja i redigeraren.

**4.2 Steg 2 och 3 utan friktion.** En barnrad finns redan med fokus, skolan väljs i ett väljarfält och födelseåret är valfritt. I ortsteget kommer sökningen först, och knappen heter ”Använd min position”.
- *Filer:* `OnboardingViewModel.kt:34`, `OnboardingScreen.kt:233-289`, `SwedishPlaces.kt:100`.
- *Klart när:* en golden med tomt sökfält visar ingen ortslista.

**4.3 Klart-skärmen.** 🧩 ”Klart!”, texten ”Lägg till med +. Resten kan vänta.” och knappen ”Visa min dag”. En hjälpare hamnar i `HelperHome` med tipset ”Här ser du när du hämtar och lämnar.”
- *Filer:* `OnboardingScreen.kt:292-311`.
- *Klart när:* goldenen `onboarding_done` finns på sv och en.

**4.4 Kom igång på Idag.** En checklista som bockar av sig själv.
- Högst 3 rader syns, på högst 4 ord var: ”Lägg till Ellas skola”, ”Vem lämnar och hämtar?” och ”Bjud in Johan”.
- Dessutom ”Visa alla”, ”2 av 6 klart” och ”Dölj”.
- *Filer:* `TodayScreen.kt`, `TodayViewModel.kt`, ny `SetupChecklistCard`.
- *Klart när:* goldenen `today_new_family` visar listan.

**4.5 Tomlägen.** Varje tomläge har en mening och en primär åtgärd.
- Mat: ”Planera veckan”, plus ”Kopiera förra veckan” om det finns historik.
- Handla: exemplet, ”Handla till veckan” och ”Skanna”.
- Recept: tipset om att dela ett recept till appen.
- *Klart när:* goldens `meals_empty_week`, `shopping_empty` och `recipes_empty` har en primär åtgärd var.

**4.6 Snabbinmatningen lär sig.**
- Högst 3 exempelchips med ikon och familjens namn: `quickadd_hint` med barnets namn och `quickadd_hint_generic` när familjen saknar barn. De försvinner efter 3 sparade poster.
- Förhandsvisningen är en mening med ”Ändra”.
- Osäkra fält markeras i terrakotta, aldrig med `isError`.
- ”Fyll i själv” öppnar redigeraren.
- *Filer:* `QuickAddSheet.kt:115-144, 212, 373`, `QuickAddViewModel.kt`.
- *Klart när:* inga demonamn syns i en familj som inte har dem.

**4.7 Tips en gång.** `FirstTimeHint(id)` sparas i `Prefs.SeenHints`. Högst ett tips per skärm, inline och med ”Jag förstår”. På Idag gäller tipset +, och i demo visar Handla ”Tips: mjölk två gånger blir en rad”.
- *Klart när:* tipset inte kommer tillbaka efter omstart och inte syns i goldens.

**4.8 Lämna demon.** Raden ”Exempelfamilj” får åtgärden ”Börja på riktigt”. Den bekräftas, raderar exempeldata och öppnar onboardingen.
- *Klart när:* ett test visar att åtgärden aldrig syns för en riktig familj.

**4.9 Goldens för första start:**
- onboardingens steg 1–3, `today_new_family`, tom Mat, tom Handla och demoraden
- mörkt läge och 1,5/2,0 för Kalender, Mat, Handla och butiksläge.

---

## 6. Regler i docs/DESIGN.md

- **Svaret först:** varje flikrot svarar på en fråga och visar svaret före kontrollerna.
- **Textbudget:** högst 40 ord ovanför vecket på Idag och högst 45 på övriga flikrötter. Etiketter har högst 3 ord. Skrivreglerna står i 1.1, och budgeten testas.
- **En fokusyta:** högst en per skärm, i havre. Använd sektioner, inte kort: titleLarge med ikon och 32 dp mellan sektionerna. Kort används bara för fokusyta, Problem, ark och dialoger.
- **Typstege:** 22 > 20 > 17 > 15 > 14, aldrig omvänd. Ingen text under 14 sp. I hjälparvyn ingen text under 18 sp och tryckytor på 64 dp.
- **En färg, en betydelse:**
  - Sage: valt, idag, primär åtgärd och ”Du”.
  - Havre: fokus.
  - Terrakotta: påminnelse eller osäker tolkning, som ikon.
  - Rött: problem idag, fyllt och med åtgärd.
  - Medlemsfärger betyder aldrig status.
  - Färgtabellen skrivs om så att den stämmer med koden.
- **Uppmärksamhet:** tre nivåer som skiljer sig i form, ikon och placering.
- **Rader:** skrivs som meningar med personen först, och en avatar har alltid ett namn bredvid sig. Rutiner är tysta, och tomhet visas inte.
- **Samma form, samma sak:**
  - En knapp gör något.
  - `OutlinedValueField` väljer ett värde.
  - `NavRow` öppnar.
  - FilterChip filtrerar.
  - `InfoPill` informerar.

  `onClick = {}` är förbjudet. Bara entydiga ikoner står utan ord.
- **+:** rund och utan text. Den betyder ”lägg till det skärmen handlar om”. Varje skärm har ett sätt att lägga till, och listor har 88 dp luft i botten.
- **Knappar:** högst en fylld knapp per skärm eller ark. ”Spara” sitter fast längst ned. ⋮ används bara för sällsynta eller destruktiva åtgärder.
- **Anpassningsbar startsida:**
  - Svaret står först och ligger fast.
  - Blocken under går att slå av och ordna om.
  - Det som måste lösas döljs aldrig.
  - Nya block kommer avstängda.
  - Det avancerade ligger som en rad längst ned.
- **Kvitton:** säger vad som hände och var: ”Vad · Ångra/Visa”. Ångra visas bara när det verkligen återställer.
- **Tips och etiketter:** högst ett förstagångstips per skärm. Bottenfältet visar alltid etiketter.
- **Förklarbart:** skälen står på en rad, högst 2. Vid näring står källan i vyn och licensen i Om Pusslet.
- **Goldens:** nya mönster får goldens i ljust och mörkt läge, vid 1,5/2,0 och ett vecktest på 800 dp.

## 7. Detta får inte ändras

- **Funktionerna ägaren har testat** får flyttas eller byta namn men tas inte bort:
  - sammanslagna dubbletter, avdelningar som appen lär sig och Ångra
  - butikslägets tända skärm och ”Allt i vagnen!”
  - Handla till veckan, Dela som text, butiker och det vi har hemma
  - skolluncher med krockvarning, förslag med skäl, Gör dubbelt och Flytta till…
  - portionsskalning, Lägg på inköpslistan och näring
  - import från länk och delning från Chrome.

  Testanteckningarna uppdateras i samma PR.
- **Lämning och hämtning** som modell (`TodayPlanner.isRoutine`).
- **Utseende och ton:** paletten, de rundade hörnen, 4 dp-rutnätet, tryckytorna och det mörka läget. Tonen behålls: ”Skönt!”, ”Vem gäller det?” och fel som misslyckas mjukt.
- **Välkomstskärmen** som förebild, och den korta onboardingen där bara namnet krävs.
- **Ångra** i stället för ”Är du säker?”.
- **Svenska konventioner:** måndag först, ISO-veckan alltid synlig (även i hjälparvyn), 24-timmarsklocka och röda dagar.
- **Tillgänglighetsgrunden:** sp, rubriksemantik, Role.Checkbox, stateDescription och knappar för att flytta upp och ned. Hjälpare ser bara det som delas.
- **Snabbinmatningen** tolkar medan man skriver och har ”Spara N saker”. Ingen AI (ADR-001).

---

## 8. Ordning och PR:er

PR:erna är små och följer Conventional Commits. Varje PR tar om goldens för de skärmar den rör och har strängar på båda språken. H2 kräver 2.0 och Idag A. Fas 3 (push med Ja/Nej, påminnelse före hämtning och en widget för hjälpare) bygger på samma användningsfall.

| # | PR | Innehåll | Goldens |
|---|---|---|---|
| 1 | `test(screenshots): add baseline goldens` | 1.0 | baslinjer |
| 2 | `feat(ui): shorter copy and string budget` | 1.1 | alla som ändras |
| 3 | `feat(shell): round add button, one entry per screen` | 1.2 | today, calendar, meals, recipes, shopping |
| 4 | `fix(shell): nav labels, sage selection, 14 sp`, `feat(ui): attention levels and InfoPill` | 1.3–1.6 | alla, `today_large_text`, `today_dark` |
| 5 | en `fix(<flik>)` per punkt | 1.7–1.12 | respektive flik |
| 6 | `fix(shell): caregivers only see usable actions` | 1.13 | `today_caregiver` |
| 7 | `feat(dev): replay onboarding`, `fix(family): first child colour`, `fix(family): role change updates access and session` | 1.14, 1.15, före H2 | – |
| 8 | H1: `feat(core): caregiver visibility and effective handoffs`, `feat(rules): tighten handoff requests`, `feat(data): parents apply accepted requests` | parallellt med 2–7 | – |
| 9 | `feat(ui): shared components` | 2.0 | komponenter |
| 10 | `feat(today): render Idag from a block layout` | Idag A | `today_default`, `today_all_off`, mörkt, stor text |
| 11 | `feat(today): customise Idag` | Idag B | `today_layout`, `today_layout_large_text` |
| 12 | H2: `feat(today): simple helper view` | Steg 3 | `helper_home*`, `helper_nothing_today` |
| 13 | H3: `feat(family): preview helper view, show answers` | Steg 3 | `helper_preview`, event_details |
| 14 | Handla, Mat, Kalender och Mer, med 1–2 punkter per PR | Steg 2 | respektive flik och vecktest |
| 15 | en PR per punkt | 4.1–4.9 | första start |

## 9. Nya ADR:er

- **ADR-023 Rund +.** Utan text, med ett sätt att lägga till per skärm enligt 1.2. Avviker från BRIEF §5.
- **ADR-024 Skrivregler och textbudget.** Skrivregler, textbudget och ikonregel enligt 1.1, kontrollerade med `StringBudgetTest` och ordräkning.
- **ADR-025 Källor i Om Pusslet.** Källor och licenser står i ”Om Pusslet”, med en kort källa i näringsvyn. DESIGN.md:s ”alltid källa” skrivs om.
- **ADR-026 Idag utan hälsning.** Avviker från BRIEF §4.1. Hälsningen finns kvar i morgonnotisen.
- **ADR-027 Anpassningsbar Idag.** Block med stabila id och standard per roll. Att lösa syns alltid, nya block kommer avstängda och layouten lagras lokalt i `Prefs.TodayLayout` (ADR-012).
- **ADR-028 Ett faktum en gång.** Väderkortet och kontextbannrarna blir rader. Innehållet i BRIEF §4.1 behålls i ny form.
- **ADR-029 Enkel hjälparvy.** `HelperShell` med `HelperTheme` och ingen text under 18 sp. Hela appen kan väljas lokalt (`Prefs.HelperFullView`). Avviker från BRIEF §5 för hjälpare.
- **ADR-030 Hjälparens svar.** Svaret syns via `EffectiveHandoffs` och skrivs in av en föräldertelefon (`appliedAt`). `declines` läggs till och reglerna skärps. Kompletterar ADR-015.
- **ADR-031 `CaregiverVisibility`.** Speglar Firestore-reglerna och har ett paritetstest.
- **ADR-032 Färgroller.** Havre betyder fokus och sage `primaryContainer` betyder valt.
- **ADR-033 Kalendervyer.** ”Vecka | Månad”. Skrivs efter svaret på fråga 1.

## 10. Beslut om de öppna frågorna

Ägaren litar på rekommendationerna (2026-10-13), så de gäller som beslut:

1. **Kalendern:** ”Vecka | Månad”. Agenda går upp i veckolistan och Dag öppnas från dagrubriken (ADR-033).
2. **Skollunch i matsedeln:** varje skoldag, som en sammanslagen rad.
3. **SMHI:** nämns i ”Om Pusslet” (CC BY 4.0 kräver erkännande på ett skäligt sätt för mediet).
4. **Namnet:** ”Handla till veckan” överallt.
5. **Hälsningen:** bort från Idag, kvar i morgonnotisen (ADR-026).
6. **`dietProfiles` och barnens kontakter för hjälpare:** stängs i reglerna som ett eget ärende i Steg 3.
7. **+ på Matsedel:** planera middag.

## 11. Läge

Se `docs/PROGRESS.md` › ”UX-förenkling” för vad som är gjort. Goldens kan bara spelas in i en miljö med Android
(Googles Maven); molnsessionerna saknar det, så de spelas in av ägaren med `./gradlew :composeApp:recordRoborazziDemoDebug`.
