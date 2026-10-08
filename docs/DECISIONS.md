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
