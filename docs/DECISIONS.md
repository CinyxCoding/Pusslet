# Beslutslogg (ADR)

Korta arkitekturbeslut: kontext → beslut → konsekvens. Nyaste sist. Ändrade beslut markeras "Ersatt av ADR-xxx".

---

## ADR-001 – Ingen AI eller LLM-tjänst (ägarbeslut)
**Kontext:** Ägaren har beslutat att appen inte ska använda externa AI-tjänster eller LLM-API:er.
**Beslut:** Inga anrop till Claude, OpenAI eller andra AI-tjänster, och inga API-nycklar för sådant i projektet. All "smarthet" –
snabbinmatning, matsedelsförslag, kategorisering av varor, klädråd, receptuppdelning – är regelbaserad och körs lokalt i `shared/core`.
**Konsekvens:** Förutsägbart beteende, fungerar offline, inga kostnader per anrop och inga persondata lämnar enheten för tolkning.
Parsern måste visa förhandsgranskning och markera osäkra fält så att användaren kan rätta. Regler och ordlistor är data som går att utöka.

## ADR-002 – Modulstruktur
**Kontext:** Briefen föreslår `composeApp` (skal), `shared/core`, `shared/data`, `shared/feature-*` samt Android-specifika delar.
AGP 9 tillåter inte längre `com.android.application` och Kotlin Multiplatform i samma modul (se ADR-004).
**Beslut:**
- `composeApp` = Android-applikationen (flavors, Glance-widgets, WorkManager, CalendarContract, CameraX/ML Kit, FCM).
  Namnet behålls så att acceptanskriteriet `./gradlew :composeApp:assembleDemoDebug` gäller ordagrant.
- `shared/app` = det delade app-skalet (navigation, scaffold, Koin-graf, iOS `MainViewController` + framework-export).
- `shared/core`, `shared/data`, `shared/ui` (designsystem + **alla strängresurser**), `shared/feature-{today,calendar,meals,shopping,tasks,family,settings}`.
- `build-logic/` med convention plugins så att varje modul bara deklarerar det som skiljer.
**Konsekvens:** Ren separation mellan delad kod och Android-integrationer; iOS-appen kan senare använda `shared/app` direkt.
Onboarding ligger i `feature-family`.

## ADR-003 – Verifierade versioner (2026-10-08)
**Kontext:** Briefen kräver verifierade, aktuella stabila versioner.
**Beslut:** Versioner lästes direkt ur `maven-metadata.xml` på Maven Central, Google Maven och Gradle Plugin Portal samt
Compose Multiplatforms release-notes. Urval: Kotlin 2.4.20, Compose Multiplatform 1.12.1, AGP 9.4.1, Gradle 9.8.1, KSP 2.3.12,
Room 2.8.5, SQLite bundled 2.7.1, Ktor 3.6.0, Koin 4.2.2, kotlinx-datetime 0.8.0, kotlinx-serialization 1.11.0, coroutines 1.11.0,
Lifecycle 2.11.0, Navigation 3 1.1.2, GitLive firebase 2.7.0 (bygger på Firebase BoM 34.18.0, som vi därför också använder).
Undantag/val:
- **compileSdk 37, targetSdk 36.** Compose 1.12, OkHttp 5.5 m.fl. kräver kompilering mot API 37 (AAR-metadata). Play kräver target 36.
- **Material 3 1.9.0** (senaste stabila) i stället för `1.12.0-alpha03` som CMP 1.12.1 parar ihop med – vi behöver inga alfa-API:er.
- **detekt 2.0.0-alpha.6.** detekt 1.23.8 (senaste stabila) kan inte köra på JDK 25 / Kotlin 2.4 ("Invalid value (25) passed to --jvm-target").
  2.0-alfan bygger på Kotlin 2.4.10. Byt till 2.0.0 stabil när den släpps.
**Konsekvens:** Versionskatalogen (`gradle/libs.versions.toml`) är enda källan. Uppgraderingar görs i katalogen.

## ADR-004 – AGP 9: Android-appen i egen modul, KMP-bibliotek med `com.android.kotlin.multiplatform.library`
**Kontext:** Med AGP 9 är KMP-pluginen inkompatibel med `com.android.application`/`com.android.library` i samma modul.
Det nya KMP-biblioteks-pluginet saknar build variants/flavors och BuildConfig.
**Beslut:** Alla delade moduler använder `com.android.kotlin.multiplatform.library` (`kotlin { android { … } }`), host-tester
aktiverade (`testAndroidHostTest`). Flavors och `BuildConfig` finns bara i `composeApp`, som skickar in miljöberoende värden via Koin.
AGP:s inbyggda Kotlin används i `composeApp` (ingen `kotlin-android`-plugin).
**Konsekvens:** Flavor-specifik kod (emulator vs riktig Firebase) bor i `composeApp/src/{demo,dev,prod}`.

## ADR-005 – Navigation 3 för typsäker navigation
**Kontext:** Briefen ber om typsäker Compose-navigation och att aktuell KMP-lösning verifieras. JetBrains publicerar både
Navigation 2.9 (typsäkra routes) och Navigation 3 (stabil 1.1.x, multiplattform) för CMP 1.12.
**Beslut:** Navigation 3. Destinationer är `@Serializable` klasser (`Route`), backstacken är vår egen state (en stack per flik,
Idag-stacken underst så att "tillbaka" från en flikrot går till Idag). `rememberViewModelStoreNavEntryDecorator` ger ViewModels per destination.
**Konsekvens:** Fullt typsäkert, ingen sträng-routing, predictive back fungerar eftersom `NavDisplay` alltid får hela den synliga stacken.

## ADR-006 – Firestore via native Android-SDK bakom `RemoteStore`; GitLive för Auth och Functions
**Kontext:** Synkmotorn är generisk (per-fält last-write-wins över många samlingar) och behöver läsa/skriva schemalösa dokument
(`Map<String, Any?>`) med `serverTimestamp`, `mergeFields` och snapshot-metadata. GitLive-SDK:ns Firestore-API bygger på
kotlinx-serialization med statiska serializers och kan inte koda/avkoda `JsonElement`.
**Beslut:** `RemoteStore`-interface i `shared/data/commonMain`. Implementation med Firebase Android SDK i `androidMain`,
iOS får en tydlig stub (`TODO("iOS")` bakom interfacet). GitLive används i delad kod för Firebase-app, Auth och Callable Functions,
där dess typade API passar.
**Konsekvens:** Synkmotorn testas helt i common-kod mot en fejkad `RemoteStore`. iOS kräver en egen Firestore-adapter senare.

## ADR-007 – Egen RFC 5545-parser och RRULE-motor i `shared/core`
**Kontext:** ICS-prenumerationer kräver RRULE/EXDATE/tidszoner; familjens egna återkommande händelser kräver samma RRULE-motor.
ical4j är JVM-bundet, tungt och har kända problem på Android (resursladdning av tidszoner, java.beans m.m.).
**Beslut:** Egen parser + RRULE-expansion i ren Kotlin (kotlinx-datetime för tidszoner), bakom ett `CalendarFeedParser`-interface.
Testas mot RFC 5545-exempel, Googles publika helgdagsflöde och egna fixturer (sommartid, vecka 53, EXDATE, RECURRENCE-ID).
**Konsekvens:** Fungerar offline och på iOS. Ovanliga RRULE-delar (BYSETPOS m.fl.) stöds i den mån de testats; okända delar
loggas och händelsen visas som enstaka förekomst i stället för att krascha.

## ADR-008 – Strängar centralt i `shared/ui`, svenska som standard
**Kontext:** Inga hårdkodade strängar, svenska primärt, engelska som andraspråk, per-app-språkval.
**Beslut:** Alla UI-strängar i `shared/ui/.../composeResources/values/strings.xml` (svenska) och `values-en/` (engelska),
publik `Res`-klass i `se.pusslet.ui.resources`. Android får `locales_config.xml` så att systemets per-app-språkval fungerar.
**Konsekvens:** En fil per språk att granska. En användare med annat systemspråk än sv/en ser svenska.

## ADR-009 – SMHI SNOW1g: tolkning av nederbördstyp och vädersymbol
**Kontext:** `pmp3g` är avvecklad. SNOW1g v1 (`/api/category/snow1g/version/1/...`) har nya parameternamn
(`air_temperature`, `wind_speed`, `precipitation_amount_mean`, `probability_of_precipitation`, `precipitation_frozen_part`,
`predominant_precipitation_type_at_surface`, `symbol_code`). Metadata från `.../parameter.json` anger bara "category" för typen.
**Beslut:** `symbol_code` tolkas som SMHI:s 27 vädersymboler (1 klart … 27 kraftigt snöfall). Nederbördstyp tolkas enligt
GRIB2/ECMWF-kodning (0 ingen, 1 regn, 3 underkylt regn, 5 snö, 6 blötsnö, 7 regn och snö, 8 iskorn, 11 duggregn, 12 underkyld duggregn) –
verklig data visar 1 och 11 vid +12 °C. Klädreglerna bygger främst på temperatur, nederbördsmängd, `precipitation_frozen_part` och vind,
så en felaktig typkod får liten effekt. Anropen begränsas med `?parameters=` och cachas 1 h.
**Konsekvens:** Fixturen `smhi_snow1g_parameters.json` sparad; om SMHI publicerar kodtabellen verifieras mappningen igen.

## ADR-010 – Skolverkets skolenhetsregister v2
**Kontext:** Testanrop visade att listan saknar namnsökning och att filtren heter `municipality_code`, `school_type` (t.ex. `GR`) och
`status` (`AKTIV`). Detaljer (`/v2/school-units/{kod}`) har adresser, `schoolTypes` (GR, FKLASS, FTH …) och kommun.
Förskolor finns inte i registret.
**Beslut:** Onboarding: välj kommun (lokal lista över kommunkoder) → hämta aktiva skolenheter för kommunen → filtrera på namn lokalt.
För förskola eller okänd skola: fritext. Skolenhetskod sparas på barnprofilen.
**Konsekvens:** En nätverksförfrågan per kommun (cachas), snabb lokal sökning.

## ADR-011 – skolmaten.se: ny RSS-adress
**Kontext:** skolmaten.se är nu en SPA. Den gamla adressen `https://skolmaten.se/{skola}/rss/weeks` svarar 200 med HTML.
Sajtens kod pekar på `https://skolmaten.se/api/4/rss/week/{skola}?locale=sv` (och `/rss/day/`), som ger RSS 2.0 med en post per skoldag
(titel "Måndag - Vecka 41", rätter i CDATA separerade med `<br/>`).
**Beslut:** `SkolmatenRssProvider` normaliserar det användaren klistrar in (skolsidans URL, gammal RSS-URL eller bara skolnamnet)
till den nya adressen, kontrollerar att svaret faktiskt är RSS och faller annars tillbaka till manuell inmatning med ett vänligt fel.
Hämtas högst en gång per dygn och skola.
**Konsekvens:** Robust mot fler URL-byten: nya leverantörer (Matilda, Mashie, FoodIT, Open Meal Information) läggs till som
fler `LunchMenuProvider`-implementationer.

## ADR-012 – Preferenser i Room i stället för DataStore
**Kontext:** Få enhetslokala inställningar (valt språk, senast valda vy m.m.). DataStore för KMP är fortfarande alfa i 1.3.
**Beslut:** En liten `preferences`-tabell i Room.
**Konsekvens:** Ett beroende mindre, allt lokalt state på ett ställe (och i samma export).

## ADR-013 – Inget Google Services-plugin; Firebase-konfiguration läses vid bygget
**Kontext:** `google-services.json` finns bara hos ägaren (gitignorerad). Pluginet kräver filen för varje variant, och demo-
varianten ska köra mot emulatorerna helt utan nycklar.
**Beslut:** `composeApp/build.gradle.kts` läser `src/<flavor>/google-services.json` om den finns och lägger projekt-id, app-id,
API-nyckel, sändar-id, bucket och OAuth-webbklient (typ 3) i `BuildConfig`. `FirebaseSetup` initierar `FirebaseApp` manuellt.
Saknas filen körs appen lokalt (`BackendMode.LocalOnly`); demo använder projektet `demo-pusslet` och emulatorerna.
**Konsekvens:** Bygget fungerar på en ren maskin och i CI utan hemligheter. Crashlytics-pluginet (fas 4) behöver mappningsfilen
via sin egen konfiguration.

## ADR-014 – Skanner: CameraX + ML Kit (gebundlad modell) i egen aktivitet
**Kontext:** QR-koder för inbjudningar och EAN-koder i butiksläget. Googles Code Scanner kräver Play-tjänstmodulen och ger ingen
kontroll över UI:t.
**Beslut:** `ScannerActivity` med CameraX-förhandsvisning och ML Kit `barcode-scanning` (modellen följer med appen). Kamera-
behörighet begärs först när användaren trycker "Skanna". Bilder analyseras på enheten och sparas aldrig.
**Konsekvens:** ~2–3 MB större APK per ABI; fungerar utan nätverk. Data Safety: ingen bild lämnar enheten.

## ADR-015 – Vårdnadshavare/hjälpare (t.ex. morfar) ändrar inte händelser
**Kontext:** Reglerna låter hjälpare läsa delade händelser, bocka av packning, svara på hämtförfrågningar och markera delade
uppgifter klara – men inte skriva händelser.
**Beslut:** UI:t döljer redigering och "Jag tar det" för hjälpare. De svarar på förfrågningar (`handoffRequests`), och föräldrar
ser svaret. Att låta hjälpare ändra just `dropOff`/`pickUp` kräver override-dokument per tillfälle; det utvärderas i fas 3
tillsammans med notiserna.
**Konsekvens:** Enkel och säker behörighetsmodell nu; en känd begränsning listas i PROGRESS.

## ADR-016 – Skärmdumpstester kör den riktiga app-grafen
**Kontext:** Skärmdumpar av tomma skal fångar inte de fel som spelar roll (texter som bryts, avatarer som klipps vid stor text).
**Beslut:** `TestEnvironment` startar Koin med de riktiga modulerna, Room i minnet via ramverkets SQLite-drivrutin (den
gebundlade har inga värdbibliotek för Robolectric), fast klocka 2026-10-08 07:10 Europe/Stockholm, demofamiljen och en
inspelad SMHI-prognos. Testerna klickar sig fram och fotograferar Idag (ljust/mörkt/engelska/150 % text), kalendern och detaljvyn.
**Konsekvens:** Testerna är också röktester av DI och datalagret. De tar ~1 min; körs i CI med `verifyRoborazziDemoDebug`.

## ADR-017 – Lägga till på listan slår ihop i stället för att duplicera
**Kontext:** Hela familjen lägger till samtidigt ("mjölk" skrivs av två personer), och recept/skanning lägger till samma
varor som redan står på listan.
**Beslut:** `ShoppingInput.mergeInto` matchar på kanoniskt namn (`ShoppingNames.canonical`, plural → singular). Samma vara
utan mängd blir kvar som en rad; mängder adderas när enheterna går att konvertera (2 dl + 3 msk), annars blir det en ny rad
("2 kg potatis" + "4 potatis"). En avbockad rad med samma namn tas tillbaka med den nya mängden. Samma streckkod två gånger
räknar upp antal. Varje tillägg returnerar vad som ändrades så att UI:t kan erbjuda Ångra.
**Konsekvens:** Färre dubbletter och mindre städning; en rad kan ibland bli "fel" sammanslagen – Ångra och redigering löser det.

## ADR-018 – "Jag handlar nu" som närvarodokument med utgångstid
**Kontext:** Övriga i familjen ska se att någon är i affären och hinna lägga till saker. Push (FCM) kommer i fas 3.
**Beslut:** Butiksläget skriver `presence/{memberId}` med `until = nu + 60 min` när det öppnas och tar bort det när skärmens
ViewModel rensas – via app-scopet, så att skrivningen inte avbryts när skärmen stängs. UI:t filtrerar bort utgångna dokument,
så en kraschad app aldrig visar "handlar" för evigt. Hjälpare får skriva sin egen närvaro (reglerna tillät det redan).
**Konsekvens:** Fungerar offline-först och synkas som allt annat. Notisen "Sara lade till mjölk medan du handlar" kommer med FCM i fas 3.

## ADR-019 – Basvaror från lokal köphistorik
**Kontext:** Briefen vill att återkommande varor föreslås "baserat på historik/intervall", utan AI (ADR-001).
**Beslut:** Varje avbockning loggas i den lokala tabellen `purchase_log` (synkas aldrig). `StapleSuggester` grupperar på
kanoniskt namn, kräver minst tre köp olika dagar, tar medianintervallet och föreslår varan när 85 % av intervallet har gått.
Varor som inte köpts på fyra intervall räknas som slutade. "Inte nu" döljer förslagen tills varan köpts igen (per enhet).
**Konsekvens:** Förutsägbart och integritetsvänligt; varje telefon lär sig av det som bockats av på just den telefonen.

## ADR-020 – Receptredigerare med en rad per ingrediens och regelbaserad uppdelning av inklistrad text
**Kontext:** Recept ska kunna skrivas in eller klistras in snabbt, och sidor utan JSON-LD ska ändå gå att spara.
**Beslut:** Ingredienser och steg redigeras som text, en per rad ("2 dl grädde, vispad"; rubrik med kolon blir grupp).
`RecipeText.split` delar inklistrad text på rubriker ("Ingredienser", "Gör så här") eller på rader som börjar med en mängd,
och tar bort stegnummer. Bekräftade livsmedelsmatchningar behålls vid redigering om ingrediensens kanoniska namn är detsamma,
och sparas dessutom per familj i `ingredientMappings`.
**Konsekvens:** Snabbt att skriva och lätt att rätta; inga formulär med en rad per fält.

## ADR-021 – Delad länk öppnar receptimport
**Kontext:** Android-delning ger bara text. En receptlänk ska till receptimporten, ett veckobrev med en länk ska till snabbinmatningen.
**Beslut:** Om den delade texten nästan bara är en länk (högst 60 tecken annat) öppnas `RecipeImport`; annars snabbinmatning.
Importen hämtar bara den sidan, sparar källa och uppdaterar en tidigare import av samma länk i stället för att duplicera.
**Konsekvens:** Rätt flöde i de vanliga fallen; den som delar en lång text med länk hamnar i snabbinmatningen och kan klistra in länken i importen.

## ADR-022 – Morgondagens väder från kl. 18
**Kontext:** På kvällen är dagens väder mest passerat. Ägaren vill kunna planera ytterkläderna kvällen före.
**Beslut:** Från 18:00 lokal tid (`TodayInput.tomorrowWeatherFrom`) sammanfattar `TodayPlanner` morgondagens dagtid (07–17),
och klädråden räknas för morgondagen. Kortet visar "I morgon · Regn" i stället för "just nu". Om prognosen inte når
morgondagen visas dagens väder. Efter midnatt är den nya dagen "idag" igen. "Just nu" använder nu det prognossteg som ligger
närmast klockan i stället för dagens högsta temperatur.
**Konsekvens:** Kvällskollen svarar på "vad ska barnen ha på sig i morgon?" utan ett extra steg. Tiden kan göras inställbar senare.

## ADR-023 – Rund + med ett sätt att lägga till per skärm
**Kontext:** Ägaren tyckte att den utökade knappen ”Lägg till” var rörig (”ersätt med en +-knapp”). Handlas eget fält och
den globala snabbinmatningen tolkade dessutom samma text på två sätt.
**Beslut:** `FloatingActionButton` med bara + och en contentDescription per skärm. + betyder ”lägg till det skärmen handlar
om”: snabbinmatning på Idag och Kalender, planera middag på Matsedel, nytt recept på Recept, ny vara på Handla, ny butik på
Butiker. Ingen + i Mer, undersidor, butiksläget och hjälparvyn. Listor får 88 dp luft i botten. Avviker från BRIEF §5
(global + på alla flikar).
**Konsekvens:** En ingång per skärm; snabbinmatningen nås från Idag och Kalender.

## ADR-024 – Skrivregler och textbudget
**Kontext:** ”För mycket text, allt blev typ luddigt och rörigt.”
**Beslut:** Tio skrivregler (docs/UX_PLAN.md 1.1, DESIGN.md › Ton i text). Högst 40 ord ovanför vecket på Idag och 45 på
övriga flikrötter; etiketter högst 3 ord. `StringBudgetTest` kontrollerar strängarna och goldens räknar synliga ord.
**Konsekvens:** Förklaringar flyttar till tomlägen, (i) och inställningar.

## ADR-025 – Källor och licenser i ”Om Pusslet”
**Kontext:** ”Prognos: SMHI” och liknande källrader tog plats på huvudskärmarna.
**Beslut:** Källor och licenser (SMHI, Livsmedelsverket, OpenStreetMap m.fl.) står i ”Om Pusslet”. Undantag: en kort
”Källa: Livsmedelsverket” inne i näringsvyn. CC BY 4.0 kräver erkännande på ett skäligt sätt för mediet, vilket det uppfyller.
**Konsekvens:** DESIGN.md:s ”alltid källa” gäller näringsvyn, inte varje skärm.

## ADR-026 – Idag utan hälsning
**Kontext:** ”God morgon, Sara” var den största texten på Idag men sa inget om dagen.
**Beslut:** Idag börjar med datum och vecka. Hälsningen finns kvar i morgonnotisen (fas 3). Avviker från BRIEF §4.1.

## ADR-027 – Anpassningsbar Idag
**Kontext:** Ägaren vill kunna anpassa sin startsida, men standardläget ska vara lugnt.
**Beslut:** Idag byggs av block med stabila id (`handoffs`, `packing`, `dinner`, `weather`, `timeline`, `tasks`, `upcoming`,
`shopping`) och en standard per roll. Morgonkollen står alltid först; sektionerna under går att slå av och ordna om med
upp/ner-knappar på en egen skärm, ”Anpassa Idag”. Rubrik med vecka, Problem och förfrågningar går inte att dölja. Nya block
kommer avstängda. Layouten lagras lokalt i `Prefs.TodayLayout` (ADR-012) och synkas inte.
**Konsekvens:** Ingen ny data lämnar telefonen; en ny telefon börjar med standardläget.

## ADR-028 – Ett faktum en gång på Idag
**Kontext:** Samma sak syntes på flera ställen (väderkort, banners, tidslinje).
**Beslut:** Väderkortet och kontextbannrarna (byte, kalas, studiedag, klämdag) blir rader: vädret i Morgonkollen,
påminnelser i Kommande, vårdnaden i rubriken. Lämning och hämtning visas i Morgonkollen och bara i tidslinjen när blocket
är av. Innehållet i BRIEF §4.1 behålls i ny form.

## ADR-032 – Färgroller
**Kontext:** Valt läge och fokus använde färger med för låg kontrast mot bakgrunden, och varning och problem såg likadana ut.
**Beslut:** Havre (`secondaryContainer`) betyder fokus, högst en yta per skärm. Sage `primaryContainer` betyder valt (flik,
segment, filterchip). Terrakotta är påminnelse/osäker tolkning och visas som ikon på neutral yta. Rött (`errorContainer`) är
problem idag, fyllt och alltid med en åtgärd. Medlemsfärger betyder aldrig status.

## ADR-033 – Kalendervyer: Vecka och Månad
**Kontext:** Fyra vyer (Dag, Vecka, Månad, Agenda) gjorde kalendern svår att förstå, och Dag-vyn fastnade som standard.
**Beslut:** Segmentet visar ”Vecka | Månad”. Agenda går upp i veckolistan och Dag öppnas från dagrubriken som egen skärm.

## ADR-029 – Enkel hjälparvy
**Kontext:** Morfar Bengt hämtar på torsdagar. Han behöver se när han hämtar och lämnar och kunna svara Ja eller Nej, inte
familjens alla flikar, kalendervyer och inställningar. Stor text ska fungera utan att något klipps (BRIEF §5 gav hjälpare
samma skal som föräldrar).
**Beslut:** En hjälpare får `HelperShell`: ingen bottenmeny, ingen rund + och ingen snabbinmatning, en egen
`HelperNavigator` med undersidor högst två nivåer ned (Hela veckan, händelse, inköpslistan, inställningar). Startsidan
byggs av `HelperPlanner` i core (förfrågningar först, sedan ”Idag” med hans uppdrag, Ska med, Att göra och Kommande) på data
som först filtreras genom `CaregiverVisibility` och `EffectiveHandoffs`. `HelperTheme` har ingen text under 18 sp och knappar
och rader på minst 64 dp. ”Visa hela appen” sparas lokalt i `Prefs.HelperFullView` och ”Enkel vy” överst i Mer tar tillbaka
den; valet styr bara visningen, aldrig behörigheten (den ligger i Firestore-reglerna).
**Konsekvens:** Avviker från BRIEF §5 för hjälpare. Blocken på hjälparens startsida använder samma modell och samma lagring
som Idag (`Prefs.TodayLayout`, ADR-027). Förälderns förhandsvisning (”Så ser Bengt appen”) kan återanvända skärmen med samma
planerare.

## ADR-030 – Hjälparens svar på förfrågningar
**Kontext:** Morfar Bengt ska kunna svara Ja eller Nej på "Kan du hämta Ella?", men hjälpare får inte skriva händelser
(ADR-015). I dag nekade reglerna inte ens felaktiga ändringar i `handoffRequests`.
**Beslut:** Ett Ja sätter `Accepted`, `respondedBy` och `respondedAt`. `EffectiveHandoffs` (core) låter svaret gälla
direkt i Idag och Kalender på alla telefoner. Den första föräldertelefonen som ser svaret skriver in det i händelsen
(`AcceptedRequestApplier`, som en override för tillfället) och sätter `appliedAt`; det ger samma resultat om det körs
flera gånger. Ett Nej på en förfrågan till hjälparen ger `Declined`; på en förfrågan till alla sparas bara hjälparens nyckel
i `declines` (map-fält, sammanslaget per nyckel). "Ändra svar" går tills `appliedAt` är satt. "Jag kan inte" skapar en öppen
förfrågan till alla från hjälparen. Reglerna tillåter hjälpare just detta och inget mer (regeltester i `functions/test`).
**Konsekvens:** Svaret syns direkt och händelsen blir rätt även i ICS-flödet och enhetskalendern, utan att hjälpare får
skrivrätt till händelser.
**Tillägg (H3):** När en förälder själv väljer eller tar en lämning/hämtning får ett accepterat svar som inte är inskrivet
`appliedAt`, så att förälderns val vinner och inte skrivs över av `EffectiveHandoffs` eller inskrivningen. Ett ”Jag kan
inte” är löst när uppdraget har flyttats till någon annan, och ”Jag tar det” besvarar öppna förfrågningar för samma
tillfälle. Föräldrar ser svaren i Händelsedetaljer (”Frågat Bengt · inget svar än”, ”Bengt svarade ja”, ”Bengt kan inte”)
och ett ”Jag kan inte” som Problem på Idag. ”Så ser Bengt appen” bygger hjälparens startsida med samma planerare på all
data (filtrerad genom `CaregiverVisibility`) och skriver ingenting; den hämtar inget väder.

## ADR-031 – CaregiverVisibility speglar reglerna
**Kontext:** Hjälparvyn och förälderns förhandsvisning ("Så ser Bengt appen") måste visa exakt det reglerna släpper igenom.
**Beslut:** `CaregiverVisibility` i core: familjens egna händelser med `sharedWithCaregivers`, röda dagar och delade
uppgifter – aldrig prenumerationer eller enhetskalendrar. Förfrågningar om privata händelser filtreras bort. Allergier och
kost (`dietProfiles`) läses inte längre av hjälpare (regler och synk, UX-plan §10.6).
**Konsekvens:** Samma filter används som extra skydd på hjälparens telefon (t.ex. gammal data efter rollbyte).
Kalenderns filter (`Prefs.CalendarLayers`) är bara en vy på den egna telefonen: hjälparvyn, förhandsvisningen, inskrivningen
av svar, snabbinmatningens lämningar och Händelsedetaljer läser alla tillfällen (`CalendarRepository.allOccurrences`).

## ADR-034 – Kom igång på Idag och att lämna exempelfamiljen
**Kontext:** En ny familj ser en tom Idag och vet inte vad som ger mest. Exempelfamiljen går att öppna från välkomstskärmen
i alla byggen, men gick bara att lämna via utvecklarverktyget ”Visa första start igen”.
**Beslut:** ”Kom igång” (`SetupChecklist` i core) står under ”Att lösa” och före Morgonkollen, som då ritas platt (en
fokusyta per skärm). Stegen bockas av av datan själv: skola per barn, vem som lämnar och hämtar, inbjudan per vuxen utan
konto (bara när en backend finns), en egen aktivitet, en middag och en vara. Högst 3 öppna rader, ”2 av 6 klart”,
”Visa alla” och ”Dölj”. Bara för föräldrar, aldrig i exempelfamiljen och bara de första 28 dagarna efter
`Family.createdAt`, så att befintliga familjer inte plötsligt får listan efter en uppdatering. ”Dölj” sparas per familj på
telefonen (`Prefs.SetupHidden`). Det är inget block i ”Anpassa Idag”: det försvinner av sig självt (ADR-027).
”Börja på riktigt” på raden Exempelfamilj bekräftas och kör `AppCoordinator.replayOnboarding`, som nu tillåts i alla byggen
när sessionen är exempelfamiljen; en riktig familj tas aldrig bort den vägen i prod.
**Konsekvens:** Tipset om + (4.7) väntar tills ”Kom igång” är borta, så att Idag aldrig har två sådana ytor samtidigt.

## ADR-035 – Radera familj och konto
**Kontext:** GDPR (BRIEF §4.12, §6). Klienter får inte hårdradera i Firestore, och telefonerna är offline först.
**Beslut:** Två callables i europe-north1 (`functions/src/privacy.ts`), båda går att köra igen utan skada.
`deleteFamily({familyId})` (bara föräldrar) skriver först en tombstone `deletedFamilies/{id}`, tar bort familjen ur
`users/{uid}.familyIds` för alla med access, raderar `invites`, `calendarFeeds` och `handoverShares` för familjen, sedan
access-dokumenten och till sist `families/{id}` rekursivt. Reglerna vägrar att skapa familjen igen när tombstone finns, så en
telefon som varit offline inte kan återskapa den. `deleteAccount` vägrar om den som raderar är enda förälder medan andra har
konto (”last-parent”); annars lämnar personen varje familj (eller raderar den om hen är ensam med konto), och sist raderas
`users/{uid}` med enheter och Auth-kontot. ”Ta bort familjen från den här telefonen” finns kvar och säger tydligt att de andra
behåller allt.
**Konsekvens:** Andra personers konton raderas aldrig (de kan höra till andra familjer). Den som lämnar finns kvar som person
utan konto. Andra telefoner behåller sin lokala kopia men kan inte synka. Loggar innehåller bara id:n och antal. App Check
slås på när appen skickar tokens.

## ADR-036 – Lokala notiser
**Kontext:** BRIEF §4.10 vill ha påminnelser, tysta timmar, en kanal per sort och samlade notiser. WorkManager finns bara i
composeApp (ADR-002), och appen är offline först.
**Beslut:** `ReminderPlanner` (core) planerar 48 timmar framåt som data, från samma underlag som Idag (`TodayInputSource`),
med stabila nycklar (`event:<id>@<datum>:<min>`, `handoff:…`, `task:<id>:<datum>`, `morning:<datum>`, `evening:<datum>`,
`bundle:…`). Påminnelser gäller familjens egna händelser som rör mig (jag deltar, jag lämnar eller hämtar, eller – för en
förälder – ingen vuxen deltar); heldagshändelser påminns kl. 18:00 kvällen innan. ”Du hämtar Ella 15:45” kommer 30 min innan,
med accepterade svar inräknade (ADR-030). Att göra kommer på sin tid, annars 09:00. Morgonkollen kommer på vald tid med
hälsningen (ADR-026), Kvällskollen kl. 19:00 för föräldrar när något är opackat till imorgon. `QuietHours` släpper igenom det
användaren själv bett om på en viss tid (egna påminnelser, lämna/hämta, Morgonkoll, inköp) och håller resten till fönstrets
slut; Kvällskollen i fönstret släpps. `Bundling` slår ihop samma sort inom 5 minuter. Tysta timmar och sammanslagning räknas
från ett dygn bakåt innan passerade tider tas bort, så en ny planering vid varje tidpunkt ger samma svar.
`NotificationPlanSource` (data) läser bara Room och har ingen timer: workern planerar med `planNow()` och kontrollerar
`due(key)` igen när arbetet körs, så inget gammalt eller personligt sparas i arbetet. Texterna byggs av `NotificationTexts`
(shared/ui) från strängresurserna. Notiser läser alla kalenderlager, oberoende av Kalenders filter (ADR-031).
**Konsekvens:** Notiserna följer datan offline och blir likadana vid varje körning. Android-delen schemalägger, avbryter och
visar bara. Kanalerna för förfrågningar, inköp och krockar finns redan; FCM fyller dem senare (ADR-018).

## ADR-037 – Veckans balans runt skollunchen
**Kontext:** Ägaren vill få middagsförslag utifrån veckans skollunch, så att barnen får en varierad kost. Mängder syns aldrig
(skolmenyn är bara text och hur mycket ett barn äter är individuellt), och inga AI-tjänster får användas (ADR-001).
**Beslut:** Rätter sorteras i matgrupper (fisk, kyckling, rött kött, vegetariskt) med den lokala ordlistan `DishKeywords`:
bara skolmenyns första alternativ räknas, ett tydligt vegetariskt ord vinner, bönor och linser bara när inget kött nämns.
Recept använder huvudkomponenten, sedan titeln, sedan ingredienserna (inget kött eller fisk bland dem = vegetariskt).
`WeekBalancer` räknar skollunch och middag per barn (barn med samma lunch delar rad; utan skollunch räknas familjens
middagar) mot Livsmedelsverkets råd, tolkat som minst 2 fiskmåltider, minst 2 vegetariska och högst 3 med rött kött i veckan.
Det som saknas för något barn lutar förslagen (”Fisk i veckan”, ”Vegetariskt i veckan”, ”Mindre kött”). ”Föreslå veckans
middagar” (`WeekFiller`) fyller dagarna från idag utan middag en i taget och räknar in varje val innan nästa dag väljs; inget
recept två gånger samma vecka. ”Ingen matlagning” räknas inte; hämtmat räknas efter namnet.
**Konsekvens:** Stöd för en varierad vecka, ingen garanti för näringen; texterna säger att det bygger på rätternas namn och
anger Livsmedelsverket som källa. Okända rätter syns som ”Okänt” och påverkar inget. Har familjen färre recept än lediga dagar
blir resten tomma.

## ADR-038 – Veckomenyer och flera länkar i receptimporten
**Kontext:** Ägaren vill lägga in hela veckomenyer, t.ex. recept.se:s ”Lättlagade veckomenyer”, och fler receptlänkar på en
gång. Importen läste bara en receptsida (BRIEF §4.5), och recept.se ritar veckomenyns lista i webbläsaren, så sidan har ingen
receptdata eller vanliga länkar till recepten.
**Beslut:** Har sidan inget `Recipe` läser `RecipeLinks` (core) dess receptlista: schema.org `ItemList` i JSON-LD, annars
recept.se:s `<page-week :week="…">`, där varje recept har en `slug` (receptet ligger på /recept/{slug}). Bara länkar på samma
webbplats räknas, och en lista med fler än 12 länkar är en kategorisida, inte en meny, så den lämnas. En menys recept, flera
recept med data på samma sida och flera länkar inklistrade eller delade tillsammans hamnar i samma lista: sidorna hämtas en i
taget (`RecipeImporter.importPage`), högst 30 per import, och recepten bockas för innan ”Spara (n)”. En meny bland inklistrade
länkar följs ett steg (dess recept läggs in direkt efter den), aldrig vidare; ett recept som både klistrats in och står på menyn
hämtas en gång. Bara receptens JSON-LD läses och inga bilder hämtas. Samma länk igen uppdaterar receptet (favorit och
anteckningar finns kvar). En menys recept samlas när de sparas i en receptsamling som heter som menyn (”Veckomeny 44 - 2026”);
finns samlingen läggs de till i den. En enda receptlänk sparas direkt, som förut.
**Konsekvens:** En länk till en veckomeny ger hela veckans recept och en samling att planera från. recept.se-läsaren hänger på
sidans nuvarande uppbyggnad och misslyckas mjukt (”Sidan har inget recept vi kan läsa”) om den ändras. Texten om vad som hämtas
säger nu att en veckomeny också ger recepten på den. Recepten sparas för familjens eget bruk med källan synlig, som tidigare.

## ADR-039 – Widgets på hemskärmen: Idag och Inköpslistan
**Kontext:** BRIEF §4.11 vill ha två widgets: Idag (nästa händelse, vem hämtar, ska med idag) och Inköpslistan (listan och
att snabbt lägga till). Glance och WorkManager finns bara i composeApp (ADR-002), och appen är offline först.
**Beslut:** Fakta räknas i core ur samma `TodayPlan` som Idag (`TodayGlances`): nästa händelse som inte har börjat (annars
dagens heldagshändelse, som Morgonkollens ”Först idag”), lämna/hämta som Morgonkollen, ”Ska med” eller ”Allt är packat”
tills barnen gått, antal ”Att lösa” och nästa klockslag då bilden ändras av sig själv. Idags kalenderfilter gäller, men inte
Idags egen uppställning (UX-planen) och inget väder. `ShoppingGlances` tar listan Handla visar på telefonen, bara det som är
kvar, i butikens ordning. `WidgetSource` (data) läser Room; `WidgetTexts` (shared/ui) skriver orden från strängresurserna
utanför composition och låter `NotificationTexts` formulera lämna/hämta och packning. I composeApp ritar Glance med appens
eget färgschema (ljust/mörkt). Tryck öppnar appen genom notisernas `OPEN_NOTIFICATION` (Idag, händelsen, Handla). En bock
markerar varan som handlad som i Handla; + öppnar en ruta över hemskärmen (`ShoppingQuickAddActivity`) som läser texten som
listan gör (”2 l mjölk, bröd”). Widgetarna ritas om en sekund efter ändringar medan appen kör, efter en bock eller ett
tillägg, och Idag vid nästa förändring via en unik WorkManager-uppgift (annars strax efter midnatt); `updatePeriodMillis`
30 min är reserv. Eftersom Glance bara komponerar om en pågående session läggs en versionsräknare i widgetens state som
innehållet laddas om på. Widgetväljarens namn och beskrivning måste vara Android-resurser och ligger därför, som enda
undantag, i `composeApp/src/main/res/values*/strings.xml`.
**Konsekvens:** Widgetarna säger samma sak som Idag och Morgonkollen och fungerar utan nät. Ändringar från en annan telefon
syns när appen kör och synkar (som notiserna). Barnens namn syns på hemskärmen, inte på låsskärmen. Dynamiska färger följs
inte än. Hjälparens egen widget (UX-planen fas 3) är inte gjord.

## ADR-040 – Familjens händelser i telefonens kalender
**Kontext:** BRIEF §3 vill ha tvåvägskoppling till telefonens kalendrar: läsa föräldrarnas egna (för krockar) och skriva
familjens händelser till en vald kalender eller en kalender som appen äger, utan dubbletter. Läsningen fanns, men exporten
kördes bara en gång när man valde kalender, så ändringar, raderingar och byte av kalender följde inte med.
**Beslut:** `DeviceCalendarExporter` (data, startas av `AppCoordinator` som prenumerationerna) skriver om exporten en stund
efter varje ändring i familjens händelser, valet i Inställningar eller rollen, så länge appen kör. Bara föräldrar exporterar.
Varje rad bär `UID_2445` = `pusslet-<händelsens id>`; bara sådana rader uppdateras eller tas bort, användarens egna rörs
aldrig. Rader vars händelse inte längre finns tas bort. En serie skrivs en gång med RRULE; ett enskilt flyttat tillfälle
skrivs som egen händelse och dess ursprungliga datum läggs till seriens EXDATE (`DeviceExport.prepare`). Byts kalendern eller
stängs exporten av tas kopiorna bort ur den förra. Prenumerationerna hämtas också av ett periodiskt WorkManager-jobb var
6:e timme med nät, så de är färska även när appen inte öppnats.
**Konsekvens:** Familjens kalender syns i telefonens kalenderapp och i andra appar som läser den. Ändringar från en annan
telefon kommer till telefonkalendern när appen kör och har synkat. Utan skrivbehörighet görs ingenting (och inget kraschar).

## ADR-041 – Familjens eget kalenderflöde (ICS)
**Kontext:** BRIEF §3 vill att familjens kalender ska gå att prenumerera på i Google Kalender, Apple Kalender och Outlook.
Firestore-reglerna för hemliga flödesnycklar (`calendarFeeds/{token}`, aldrig läsbara för klienter) och appens anrop för att
skapa och återkalla en nyckel fanns, men inte funktionen som serverar flödet eller något sätt att skapa länken i appen.
**Beslut:** Cloud Function `familyCalendarFeed` (europe-north1) svarar på `GET /familyCalendarFeed/<token>.ics` (eller
`?t=<token>`) med familjens händelser som RFC 5545. Okänd, återkallad eller felformad nyckel och raderad familj ger samma
404. Raderade händelser tas inte med; ett flöde med `memberId` har bara den personens händelser. Lokala tider behåller sin
zon (TZID, med VTIMEZONE för Europe/Stockholm, IANA-id för andra zoner, "Z" för UTC) så att en vecka med sommartidsskifte
blir rätt. En serie är en VEVENT med RRULE och EXDATE; ett flyttat tillfälle har seriens UID och RECURRENCE-ID. Beskrivningen
är deltagarnas namn; anteckningar och bilagor tas inte med. Svaret cachas privat i 15 minuter, och kalendrarna uppmanas att
hämta om varje timme. Loggar har bara antal. I appen skapar en förälder länken under Inställningar → Kalendrar ("Familjens
kalender i andra appar"), kan dela den och "Sluta dela". Nyckeln är två slumpade UUID (64 hextecken) och sparas bara på
telefonen som skapade den, eftersom servern aldrig lämnar ut den igen.
**Konsekvens:** Den som har länken ser familjens händelser, vilket texten i appen säger. Att sluta dela stoppar länken för
alla som prenumererat. Bara telefonen som skapade länken kan visa och återkalla den. Hjälparnas synlighet styr inte flödet,
eftersom bara föräldrar kan skapa länken. Funktionen kräver Blaze-planen och deploy (SETUP.md 2.4).

## ADR-042 – Push mellan telefonerna (FCM)
**Kontext:** BRIEF §4.10 vill att en förfrågan om att lämna eller hämta, svaret på den och en vara som läggs till medan någon
handlar ska nå de andra telefonerna även när appen är stängd. De lokala notiserna (ADR-036) täcker bara det telefonen själv
vet, och kanalerna för förfrågningar och inköp fanns redan men användes inte.
**Beslut:** Tre Firestore-triggers i `functions/src/push.ts`: en ny förfrågan går till den som tillfrågas, eller till alla
utom avsändaren när den gäller vem som helst; ett svar (ja eller nej) går tillbaka till den som frågade; en vara går till
den som just nu handlar från samma lista (`presence`, ADR-018), aldrig till den som lade till den. Meddelandena är
data-only med id:n och det minsta som behövs (`pickup`, ett datum, varan som den skrevs). Appen formulerar notisen själv
från sina egna data och strängar (`PushMessages`), så namn och titlar går aldrig genom FCM och texten blir på läsarens
språk. En förfrågan som telefonen redan vet är besvarad, eller som jag själv har sagt nej till, visas inte. Tysta timmar
gäller som för de lokala notiserna: en förfrågan eller ett svar väntar till fönstrets slut (ett WorkManager-jobb med
meddelandets id:n som frågar `PushMessages` igen då), en vara medan man handlar kommer direkt. Flera varor till samma lista
ersätter varandra i stället för att staplas. Token sparas i `users/{uid}/devices/{deviceId}` (`PushRegistration`, ett
dokument per telefon, enhets-id i `Prefs.DeviceId`); tokens som FCM inte längre känner till tas bort när ett utskick
misslyckas. FCM slås på i kod och bara med ett riktigt Firebase-projekt (`firebase_messaging_auto_init_enabled=false` i
manifestet); i emulatorerna finns ingen FCM, så funktionerna skriver där vad de skulle skickat till `_pushOutbox`, vilket
testerna läser. Triggers körs där databasen ligger: `TRIGGER_REGION` (standard europe-north1, europe-west1 för `eur3`).
**Konsekvens:** Push kräver Blaze, deploy och ett riktigt projekt (SETUP.md 2.4); demo-flavorn visar inga push-notiser.
Kommer meddelandet innan förfrågan har synkats till telefonen visas den ändå (händelsen måste finnas lokalt). Den som tagit
bort appen eller loggat ut får inga fler meddelanden när FCM svarar att token är okänd. Loggar har bara typ och antal.

## ADR-043 – Premium förberett, avstängt
**Kontext:** BRIEF §7 vill ha en `Entitlements`-abstraktion som i dag låser upp allt, och en förberedd men inte aktiv
koppling till Google Play Billing, utan att någon funktion beror på att betalningen fungerar.
**Beslut:** `Entitlement` och `Feature` i `shared/core` (`premium`) beskriver vad premium skulle lägga till, `FreeLimits` gratisgränserna
(3 ICS-prenumerationer). `Entitlements` i `shared/data` har `AllUnlocked` bundet i alla flavors. En spärr stoppar bara något
nytt: över gränsen fungerar de befintliga prenumerationerna som förut, bara knappen för att lägga till försvinner och en rad
förklarar varför (i dag aldrig synlig). `BillingRepository`, `PremiumProducts` och `BillingEntitlements` är förberedda men
används inte, och inget faktureringsbibliotek finns i bygget, så appen begär ingen faktureringsbehörighet. Premium ska gälla
hela familjen och verifieras på servern; stegen står i docs/MONETIZATION.md.
**Konsekvens:** Att slå på premium blir att skriva Play-implementationen och serververifieringen och byta bindning i `prod`,
utan att skärmarna ändras mer än premiumsidan. Gratisgränsen är testad i `SubscriptionsViewModelTest`.

## ADR-044 – Tillgänglighet kontrolleras i skärmdumpstesterna
**Kontext:** Fas 4 kräver en tillgänglighetsgenomgång. Googles Accessibility Test Framework (via Compose
`enableAccessibilityChecks`) körs inte under Robolectric, där skärmdumpstesterna går.
**Beslut:** `AccessibilityAudit` går igenom det sammanslagna semantikträdet i varje fönster (skärmen och en dialog eller ett
ark ovanpå) när ett skärmdumpstest tar sin bild: allt som går att trycka på, slå av och på eller skriva i ska ha ett namn och
vara minst 48 × 48 dp i egen storlek (Compose förstorar själv ett litet tryckområde, men bara där inget annat är i vägen,
och Accessibility Scanner mäter den synliga storleken). Testet misslyckas om en skärm inte har några kontroller alls, så
att kontrollen inte blir tom, och granskningen har ett eget test med en medvetet dålig knapp. Färgkontrasten kontrolleras på
temat (`ThemeContrastTest`, WCAG 4,5:1 för text och 3:1 för konturer och ikoner) eftersom Robolectric inte ritar text
pixelrätt. Färgväljaren finns nu en gång i `shared/ui` med färgernas namn.
**Konsekvens:** En ny skärm i skärmdumpstesterna granskas automatiskt. Läsordning, rubriker och hur TalkBack låter
kräver fortfarande en runda på en riktig telefon (SETUP.md 3.3).

## ADR-045 – Inköpslistan byggs utanför huvudtråden
**Kontext:** Fas 4 kräver att listor med 500+ varor fungerar. Mätt på emulatorn med releasebygget och 648 varor: varje bock
i butiksläget frös skärmen i ungefär en halv sekund, eftersom alla varor avkodades från JSON och listan grupperades och
fick förslag på basvaror på huvudtråden, flera gånger per bock.
**Beslut:** `ShoppingViewModel` bygger sitt tillstånd med `flowOn(compute)`, där `compute` är `Dispatchers.Default` i appen
och testets egen dispatcher i testerna (så att testerna fortsätter att vara deterministiska). `ShoppingListPerformanceTest`
mäter öppna, bocka och lägga till med 600 varor med generösa gränser som fångar kvadratisk tillväxt, inte en långsam dag.
**Konsekvens:** Huvudtråden ritar bara. Samma mönster passar andra skärmar som bygger stora listor (kalendern med många
prenumerationer) om de blir tunga; de mäts inte än.

## ADR-046 – Det som tas bort försvinner efter 30 dagar
**Kontext:** Appen tar bara bort mjukt (`deletedAt`) så att borttagningen synkar, men innehållet låg kvar på servern och i
telefonernas Room tills hela familjen raderades. Integritetspolicyn lovade rensning efter 30 dagar. En vanlig hård
radering räcker inte: en telefon som varit avstängd i över 30 dagar får aldrig veta att dokumentet är borta (lyssnarna
läser från en vattenstämpel och ett raderat dokument hörs inte av), så saken ligger kvar där för alltid, och en gammal
ändring från den telefonen skulle skapa ett halvt dokument på servern.
**Beslut:** Cloud Function `purgeDeleted` (varje natt 03:30, `functions/src/purge.ts`) ersätter varje dokument som togs
bort för mer än 30 dagar sedan med en markering som bara har `deletedAt`, `updatedAt`, `updatedBy` och `purgedAt`.
`updatedAt` lämnas orört, så telefoner som redan vet om borttagningen inte får dokumentet igen, medan en telefon som varit
borta får markeringen när den synkar och döljer saken. En gammal ändring landar i markeringen och saken förblir borttagen
(och för händelser, uppgifter, recept m.fl. nekar reglerna en ändring utan titel). Markeringar raderas helt efter ett år.
Varje körning fortsätter där den förra slutade (`_maintenance/purge`), så ett dokument hanteras en gång. Telefonen tar
bort sina egna gravstenar äldre än 30 dagar vid start (`DocumentStore.purgeDeleted`), utom dem som har ändringar kvar att
skicka. Eftersom telefonen glömmer gravstenen skickar ett nytt dokument alltid `deletedAt` och `purgedAt` som borttagna,
så att ett dokument med fast id (veckans matsedel, inställningar, en persons kostprofil) inte blir kvar som raderat på
servern när det skapas igen.
**Konsekvens:** En telefon som varit avstängd i mer än ett år kan visa saker som tagits bort under tiden; ”Ta bort
familjen från den här telefonen” och att gå med igen ger en ren kopia. Det som tas bort kan inte ångras efter 30 dagar (ångra finns bara några sekunder i appen). En gammal
ändring i en samling utan fältkontroll (t.ex. matsedeln) kan lämna det ändrade fältet i en markering; saken syns ändå
inte och raderas med markeringen.

## ADR-047 – Sjuk dag och VAB
**Kontext:** Ett sjukt barn är det vanligaste skälet till att familjens dag rasar: lämning, hämtning och aktiviteter ska
ställas in, de andra ska få veta, någon ska stanna hemma, skolan ska ha en frånvaroanmälan och den som tar VAB ska säga
till jobbet och anmäla till Försäkringskassan (inom 90 dagar). Appen hade inget för det.
**Beslut:** En förälder trycker ”Sjuk idag?” på Idag, väljer barn och idag eller imorgon. Det sparas som ett dokument per
barn och dag (`sickDays/{barn}_{datum}`: vem som rapporterade, vem som stannar hemma, VAB och hur stor del av dagen, och
tre bockar: frånvaro anmäld, sagt till jobbet, VAB anmäld). Händelserna ändras aldrig: `SickDays.apply` markerar barnet
som frånvarande på dagens tillfällen (`EventOccurrence.absent`, läses som `attending`), så dess lämning och hämtning
försvinner överallt där dagen planeras (Idag, notiser, widget, kalender, hjälparvyn, krockar), och en aktivitet som bara
det sjuka barnet skulle till lämnas bort som inställd. Det som skrivs tillbaka till en händelse (”Jag tar det”) läser
fortfarande händelsen själv, så ingen tas bort ur den för gott. Idag visar ett kort med vad som är inställt, ”Vem stannar
hemma?” (en förälder får VAB som förval, morfar inte) och bockarna; nästa morgon frågar Idag ”Är Ella fortfarande sjuk?”
(Sjuk idag också kopierar vem som är hemma och VAB) och när barnet är friskt ”Anmäl VAB för Ella: 2 dagar” med länk till
Försäkringskassan. VAB räknas per förälder och kalenderår (”VAB i år: Sara 4,5”). En ny sjukdag och ett val av vem som
stannar hemma skickas som push till alla andra i familjen, hjälpare också (`pushOnSickDay`). Påminnelser på telefonen:
frånvaroanmälan 07:45 till den som är hemma, VAB kvällen barnet är friskt igen. Hjälpare läser sjukdagarna men skriver
dem inte. Sjukdagar från före förra året tas bort från föräldrarnas telefoner och sedan av rensningen (ADR-046).
**Konsekvens:** Sjukdagar är hälsouppgifter; bara att barnet är sjukt sparas, aldrig symtom eller diagnos, och arket
säger vad som sparas och varför (PRIVACY.md). Händelser över flera dagar (läger) ställs inte in av en sjukdag.
Snabbinmatningen förstår ”Ella sjuk”, ”Ella och Simon är sjuka imorgon”, ”Simon har feber” och ”Ella vab” när ett barn
nämns och inget annat står i meningen (en tid eller något att göra gör det till en händelse eller uppgift). Det sparas
som ”Sjuk idag?” och bara för en förälder; för andra blir raden en uppgift.

## ADR-048 – Kvitto på hämtning
**Kontext:** Hämtningen planeras i appen men ingen bekräftar att den har skett, så den som inte hämtar vet inte om barnet
är hämtat, och en glömd hämtning märks först när förskolan ringer.
**Beslut:** Ett tryck på ”Hämtad” sparar ett kvitto per tillfälle och slag (`handoffReceipts/{serie}_{datum}_pickup`: vem,
när, vilka barn); en serie heter efter sitt eget id, så ett flyttat tillfälle hittar samma kvitto. Knappen finns på min
egen hämtning i Morgonkollen från en halvtimme före tiden, och raden står kvar efter tiden tills den är avbockad; på
hjälparens uppdrag; och i notisen ”Du hämtar Ella 15:45” (låsskärmen). Den som bockade av kan ångra. Kvittot skickas som
push till resten av familjen (”Ella är hämtad · Bengt · 15:52”). En kvart efter tiden utan kvitto kommer en varning till
den som skulle hämta och till föräldrarna, både som notis (”Är Ella hämtad?”, med Hämtad-knapp) och på Idag (”Ella hämtad?
Bengt skulle hämta 15:45 · Hämtad”), där en förälder också kan bocka av åt någon annan. Varningen kommer även under tysta
timmar (ett barn som väntar), och kan stängas av per telefon (”Varna om en hämtning inte bockas av”). Hjälpare får skriva
sina egna kvitton men inte i någon annans namn. En sjuk dag (ADR-047) har ingen hämtning och därmed ingen varning.
**Konsekvens:** Familjer som inte vill bocka av kan stänga av varningen; kvittona sparas tills den vanliga rensningen tar
borttagna (ADR-046). Lämning har samma datamodell men ingen knapp eller varning än.

## ADR-049 – Delade bilder stannar på telefonen
**Kontext:** Briefen vill kunna dela en bild till appen (ett fotat veckobrev) och ha den med en händelse eller anteckning.
Bilder kan visa barn, namn och adresser, och Firestore är inte gjort för filer.
**Beslut:** En bild som delas till appen kopieras direkt (medan den delande appen ger oss läsrätt) till appens privata mapp
`files/attachments`, som JPEG högst 2048 px med kamerans rotation och en liten miniatyr; originalet lämnar aldrig sin app.
Sedan väljer man ”Som anteckning” (en uppgift utan datum som bär bilden) eller en kommande händelse (en serie visas en gång,
vid nästa tillfälle). Vem bilden hör till sparas i en egen samling som är `localOnly`: den köas aldrig för uppladdning och
finns inte i `Collections.all`. Mappen är undantagen från säkerhetskopiering, raderas med ”Glöm familjen på telefonen”, och
vid start tas bilder bort vars händelse eller anteckning är borta, liksom filer som ingenting pekat på på ett dygn (en
delning som avbröts). Bilden visas som miniatyr på händelsen och anteckningen och öppnas i telefonens bildvisare via en
FileProvider, en läsrätt i taget. Lämnar man ”Spara bilden” utan att välja tas kopian bort.
**Konsekvens:** Bilden finns bara på telefonen som tog emot den; de andra i familjen ser den inte. Det är medvetet tills
familjen vill ha delade bilder och vi har en lagring för det (Firebase Storage med regler per familj).

## ADR-050 – Medicin och feber
**Kontext:** När ett barn är sjukt undrar föräldrarna ”när fick hon Alvedon senast?” och ”vem gav kvällsdosen?”, och en
penicillinkur på tio dagar med tre doser om dagen glöms lätt. Det står i PROGRESS som ett behov efter fas 4.
**Beslut:** ”Medicin och feber” (från Mer och från sjukkortet på Idag) har per barn en logg med ”Gav medicin” (medicin och
dos som fritext, tidigare mediciner som ett tryck) och ”Tempade” (34–43 °C, komma eller punkt; från 38,0 står ”feber”),
och kurer: medicin, dos, 1–4 tider om dagen (förslag som går att ändra), startdag och antal dagar. Varje dos i en kur är
en påminnelse till föräldrarna på sin tid, även under tysta timmar (som en tid man själv valt), tills någon har bockat av
den; Idag visar ”Medicin idag” med ”Given” och vem som gav. Loggen (`medicineLog`) och kurerna (`medicineCourses`) är
föräldrarnas egna: hjälpare läser dem inte (firestore.rules, `parentsOnly`) och Mer visar inte vyn för dem. Rader tas bort
efter 90 dagar på föräldrarnas telefoner och sedan av rensningen (ADR-046).
**Konsekvens:** Appen ger inga medicinska råd och föreslår inga doser eller intervall; den visar vad som är givet och när,
och vyn säger det. Hälsouppgifter: bara det föräldern skriver sparas (PRIVACY.md). Morfar som är hemma med ett sjukt barn
ser inte loggen; vill familjer det kan en delning med hjälpare läggas till senare.

## ADR-051 – Lov och vistelsetider
**Kontext:** Inför varje lov ska familjen bestämma vem som är hemma vilka dagar och anmäla lovomsorg till fritids eller
förskolan före ett datum, och förskolan vill ha barnets vistelsetider. Lovdatum skiljer sig mellan kommuner (sportlov
v. 7–10), så appen kan inte räkna ut dem.
**Beslut:** Ett lov är en heldagshändelse taggad `lov` i kalendern: skriven i + (”höstlov v 44”), tillagd i ”Lov och
vistelsetider” (förval för höstlov v. 44) eller från skolans kalenderlänk. För varje lov visas vardagarna som inte är röda
dagar, och per dag väljer man en vuxen som är hemma (hjälpare också), ”Lovomsorg” eller ”Annat”. Planen sparas per lov
och första dag (`breakPlans/{händelse}_{datum}`, dagarna sammanfogas per dag) med en valfri sista anmälningsdag och en bock
”anmäld”. Föräldrarna påminns två dagar före kl. 19 och på dagen kl. 9 tills den är bockad, och Idag visar ett lov som
börjar inom tio dagar och har dagar utan plan eller en anmälan kvar. Hjälpare läser planen (de kan vara den som är hemma)
men skriver den inte. Vistelsetider sparas per barn (`staySchedules`, bara föräldrar): måndag–fredag med från- och till-tid,
fylls med ett tryck från barnets återkommande förskole- eller skoldag i kalendern (lämning till hämtning), visar timmar i
veckan och skickas som text till förskolan.
**Konsekvens:** Inga lovdatum är inbyggda förutom förslaget v. 44. Idag ser bara tio dagar framåt, så ett lov längre bort
syns i vyn och genom påminnelsen om anmälan, inte på Idag.

## ADR-052 – Sök
**Kontext:** Briefen har sök över händelser, recept, uppgifter och varor som V2. En tidigare gren hade en sökning som
vek bort prickarna (”ost” hittade ”Östron”), vilket går emot hur appen annars behandlar å, ä och ö.
**Beslut:** ”Sök” (från luppen på Idag och överst i Mer) söker på telefonen i familjens, prenumererade och telefonens
händelser (en serie en gång, vid nästa tillfälle), recept (namn, ingredienser, anteckningar), att göra (anteckning och
checklista), varor på inköpslistor och barnens kontakter. Varje ord måste börja ett ord i texten, eller finnas inuti ett
ord från tredje bokstaven (svenska sammansättningar: ”läkare” hittar ”Tandläkare”), och träffar i titeln går före
träffar i anteckningar. Å, ä och ö är egna bokstäver: ett ord prövas först som det är skrivet och bara om det inte ger
någon träff med prickarna borttagna, med halva poängen, så ”mjolk” hittar ”Mjölk” men ”ost” visar ”Ostkaka” före
”Östron”. Regler, inte AI (ADR-001).
**Konsekvens:** Sökningen läser allt på nytt vid varje fråga (efter 150 ms paus), vilket räcker för en familjs mängd data.

## ADR-053 – Matilda som lunchkälla via officiellt RSS
**Kontext:** BRIEF nämner fler matsedelsleverantörer än skolmaten.se: Matilda (`menu.matildaplatform.com`), Mashie
(`mpi.mashie.com`), FoodIT (`webmenu.foodit.se`) och specen Open Meal Information. Ingen av dem har ett öppet JSON-API.
Matildas matsedelssida länkar själv till ett RSS-flöde (`/rss?displayMode=week&distributorId=<id>&lang=sv`) med en post
per rätt och dag: beskrivningen börjar med rättens rubrik ("Dagens", "Tillbehör"), sedan rätterna ihopfogade med " & ".
Flödet ger bara innevarande vecka (`startDate` ignoreras). FoodIT:s webbmeny är numera Matildas. Mashie och Open Meal Information
har inget öppet flöde att läsa, så de väntar.
**Beslut:** `LunchProviderType.Matilda` på barnprofilen. Föräldern klistrar in länken till matsedeln; id:t (24 hextecken
före understrecket) blir `distributorId`. `MatildaLunchParser` slår ihop dagens poster, delar på " & " bara när nästa ord
börjar med versal ("pannbiff med lök & gräddsås" är en rätt) och sätter rubriken framför allt som inte är huvudrätten
("Tillbehör: Nudlar & couscous"). Hämtas högst en gång per dygn, cachas i Room som övriga källor och felar mjukt.
`LunchMenus` väljer leverantör per barn. `LunchSource.provider` har nu ett standardvärde (manuell) så att en äldre
appversion läser en okänd leverantör som manuell i stället för att tappa barnet.
**Konsekvens:** Ingen skrapning – bara flödet som Matilda själva erbjuder. Fler leverantörer läggs till som egna
`LunchMenuProvider` när de har ett öppet flöde; tills dess finns "Egen RSS-länk" och manuell inmatning.

## ADR-054 – Kalas och önskelistor
**Kontext:** BRIEF §4.8: OSA-status, presentidé, budget, "present köpt", påminnelse tre dagar innan, egna kalas med
gästlista och inbjudan, önskelista per barn.
**Beslut:** Kalasuppgifterna ligger på händelsen (`Event.party`: OSA och sista dag, present eller – för eget kalas –
gäster och inbjudningstext) och synkas som resten av händelsen; ett kalas känns igen på taggen eller på att uppgifterna
finns. `PartyRules` i kärnan avgör när något behövs: present för ett kalas familjen inte tackat nej till tills den är
köpt eller presentuppgiften är bockad, och OSA när ett datum finns och ingen svarat. Idag visar presenten i ”Kommande” och ett kort ”Dags att OSA” tre dagar innan sista dagen; notiser kl 19 tre dagar före kalaset och kvällen före OSA-datumet, bara
till föräldrar. "Köpt" bockar även av presentuppgiften från snabbinmatningen. Önskelistor är en egen samling (`wishes`)
som även hjälpare kan läsa och där de får markera "köpt" – inget annat (reglerna + regeltest), så två morföräldrar inte
köper samma sak. Inbjudan delas som text via telefonens delningsmeny; inga gästers kontaktuppgifter sparas.
**Konsekvens:** Gästlistan är bara namn och svar. Barnläget (ADR-055) visar inte "köpt" på önskelistan.
