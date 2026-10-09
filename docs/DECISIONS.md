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

## ADR-017 – ViewModel-tester mot riktiga repositories (`:shared:testing`)
**Kontext:** Briefen kräver att ViewModels testas med fejkade repositories. Våra repositories är tunna lager över
`DocumentStore`; att fejka varje repository separat skulle testa fejkarna snarare än appen.
**Beslut:** En liten modul `:shared:testing` (bara `commonTest`-beroende för feature-modulerna) med minnesbaserade DAO:er,
`InMemorySyncLocalStore`, fejkad livsmedelsdatabas/produktuppslag/skollunch, en flyttbar klocka och `TestGraph` som bygger
de riktiga repositories. `viewModelTest {}` byter `Dispatchers.Main` mot testschemaläggaren.
**Konsekvens:** Testerna kör samma kod som appen (inkl. synkstämpling och mjuk radering) utan nätverk eller databas. Tidsstyrda
loopar i ViewModels (närvaro, basvaror) har en injicerbar takt (`tickMillis = 0` i test).

## ADR-018 – Allergi- och kostkontroll av recept är regelbaserad och varnar
**Kontext:** Allergier/kost är opt-in och känsliga. Förslag ska "respektera allergier/kostval" – utan AI (ADR-001).
**Beslut:** `RecipeDietCheck` matchar ordstammar i ingrediensnamn (ord som börjar eller slutar med stammen, så att
sammansättningar som "vispgrädde" fångas men "rostad lök" inte blir ost), med undantagslistor ("kokosmjölk", "majstortilla").
Recept med en allergen som någon i familjen undviker föreslås aldrig; recept som bryter en kost rankas lågt och märks
"Passar inte alla: Fisk (Ella)". Appen säger tydligt att kontrollen kan missa saker.
**Konsekvens:** Förutsägbart och förklarbart. Kontrollen är en hjälp, inte en garanti – ordlistorna går att utöka.

## ADR-019 – Basvaror från lokal köphistorik
**Kontext:** "Basvaror föreslås automatiskt baserat på historik/intervall."
**Beslut:** Varje avbockning loggas i `purchase_log` (bara på enheten, synkas inte). `StapleSuggester` slår ihop köp inom
12 h till ett tillfälle, kräver minst tre tillfällen, ett typiskt (median-)intervall på 1–28 dagar och föreslår varan när
≥ 90 % av intervallet gått. Långt förbi (> 4×) räknas som att familjen slutat köpa den. "Inte nu" gömmer förslaget i tre dygn.
**Konsekvens:** Inga persondata lämnar telefonen för att räkna fram förslag; två telefoner i samma familj kan föreslå lite olika.

## ADR-020 – "Gör dubbelt" ger rester nästa dag
**Kontext:** Kärnan skapade rester som lunch, appen som middag – två sanningar.
**Beslut:** `MealPlanner.leftoversFor` är enda regeln: nästa dags middag blir rester om den är ledig, annars en matlåda (lunch).
Resterna behåller receptkopplingen men handlas aldrig för (den dubbla satsen räknas på originalmiddagen). Stängs "Gör dubbelt"
av tas resterna bort om ingen ändrat dem. Favoritveckor appliceras från söndag och bakåt så att samma regel gäller.
**Konsekvens:** Restdagen syns direkt i veckovyn (som standard visas bara middag).

## ADR-021 – Familjens eget ICS-flöde via Cloud Function och Hosting
**Kontext:** "Prenumerera på familjekalendern i jobbets Outlook/Google" utan inloggning, men hemligt och återkallbart.
**Beslut:** Appen skapar `calendarFeeds/{token}` (64 hextecken från en kryptografisk UUID-källa, aldrig id-generatorn;
reglerna kräver ≥ 24 tecken och att skaparen är förälder). `calendarFeed` (europe-north1) läser bara, bygger RFC 5545
(VTIMEZONE för Europe/Stockholm, RRULE/EXDATE, flyttade tillfällen som RECURRENCE-ID, escaping, radvikning på 75 oktetter)
och svarar `text/calendar` med `private, max-age=900`. Okänd och felformad token ger samma 404. Hosting skriver om
`/feed/**` så länken blir `https://<linkHost>/feed/<token>.ics`. En personlig länk tar bara händelser där personen deltar,
lämnar eller hämtar. Länkar sparas på telefonen som skapade dem; `revokeCalendarFeeds` låter vilken förälder som helst
återkalla alla. Samma "golden"-ICS testas i TypeScript och parsas tillbaka av appens Kotlin-parser.
**Konsekvens:** Den som har länken ser händelserna (sagt i appen). Andra tidszoner än Stockholm skrivs med IANA-TZID utan
VTIMEZONE – det förstår Google, Outlook och Apple.

## ADR-022 – Radering av familj och konto på servern
**Kontext:** GDPR kräver radering; klienter kan inte radera rekursivt och reglerna tillåter inga raderingar (bara mjuk radering).
**Beslut:** `deleteFamily` (bara föräldrar) raderar familjen rekursivt, dess kalender-/delningslänkar och inbjudningar och tar
bort familjen från användarnas listor. `deleteAccount` tar bort åtkomst, personpost, enheter, `users/{uid}` och Auth-användaren;
var anroparen sista förälder raderas hela familjen. Appen raderar lokalt först när servern svarat, och säger till om det misslyckas.
**Konsekvens:** Inget halvraderat läge i appen. Utan backend (lokalt läge) är "ta bort från telefonen" detsamma som att radera allt.


## ADR-023 – Påminnelser planeras på telefonen, push bara för det andra gjort
**Kontext:** Påminnelser, hämtnudd och morgonkoll måste fungera offline; "Kan du ta hämtningen?" och ändringar som någon
annan gör ska nå telefonen även när appen är stängd.
**Beslut:** `ReminderPlanner` (ren Kotlin, testad) planerar 48 h framåt för mig: händelsepåminnelser, nudd 15 min före en
lämning/hämtning jag har, uppgifter med tid och morgonkollen. Tysta timmar: uppgifter väntar tills de är slut och notiser
om andras ändringar kommer tyst, men påminnelser man själv bett om och hämtnudden hörs alltid.
WorkManager kör en jobb per notis (`REPLACE` bara när tiden ändrats), omplanering vid varje ändring och varje timme.
Fem notiskanaler (påminnelser, lämna/hämta, morgonkoll, inköp, familjen). Push via FCM skickas av Cloud Functions bara
vid förfrågningar/svar, varor som läggs till när någon handlar ("Sara handlar nu") och kalenderändringar av någon annan
inom 14 dagar. Pushen är data-only: appen bygger texten på användarens språk och tillämpar tysta timmar. Innehållet är
förnamn, händelsetitel/varunamn och tider – aldrig allergier, adresser eller anteckningar; TTL 24 h; inget loggas.
Token hämtas via `FirebaseMessaging.register()`/`onRegistered` (25.x), sparas lokalt och registreras under kontot.
**Konsekvens:** Utan nät fungerar allt utom push. Notiser om andras ändringar slås ihop till en löpande sammanfattning.

## ADR-024 – Hemskärmswidgets med Glance, utan nät
**Kontext:** Briefen vill ha Idag- och Inköpslista-widgets. Widgets uppdateras ofta och får inte dra batteri eller vänta på nät.
**Beslut:** Glance 1.2. Innehållet byggs av samma `TodayPlans` som Idag-skärmen (utan väder) via `TodayGlancer` i kärnan,
som också räknar ut när innehållet ändras av sig självt (nästa start/slut, en hämtning passerar, midnatt) – då väcks
widgeten med ett engångsjobb i WorkManager. Medan Glance-sessionen är öppen samlas innehållet in som ett flöde i
kompositionen (annars ritas en avbockad vara inte om). Inköpslistan bockas av direkt och + öppnar en liten dialog med
samma tolkning som Handla-fliken (`ShoppingQuickAdd`). Texter i widgetväljaren läses från manifestet och måste därför
vara Android-resurser (`res/values*/widget_strings.xml`, sv+en, paritet testas) – allt i själva widgeten kommer från
compose-resurserna.
**Konsekvens:** Widgets visar lokal data även offline; en ändring på en annan telefon syns när synken når hit.

## ADR-025 – Delade bilder sparas bara på telefonen
**Kontext:** "Dela bild → bifoga till händelse eller anteckning" (t.ex. ett fotat veckobrev). Bilder kan visa barn, namn och
adresser, och Firebase Storage skulle kräva nya regler, kvoter och radering i molnet.
**Beslut:** Bilden kopieras in i appens privata mapp (`files/attachments`), skalas till högst 2048 px som JPEG med kamerans
rotation och en 480 px-miniatyr; originalet lämnar aldrig delande app. Kopplingen sparas i en lokal samling
(`CollectionSpec.localOnly`) som aldrig köas för uppladdning. Man väljer en kommande händelse (serie för återkommande) eller
"Som anteckning" (en uppgift utan datum). Lämnar man skärmen utan att välja raderas bilden. Bilder till borttagna
händelser/anteckningar och kvarglömda filer äldre än ett dygn städas vid start. Mappen är undantagen från molnbackup
och raderas med "Glöm familjen på den här telefonen". Visning sker via FileProvider i telefonens bildvisare.
**Konsekvens:** Bilden syns bara på telefonen som sparade den – det står i appen. Delning till andra i familjen kan
läggas till senare med Storage om ägaren vill (premium-kandidat).

## ADR-026 – Ingen krasch-SDK i första versionen
**Kontext:** Briefen nämner Crashlytics (opt-in). Crashlytics Gradle-plugin 3.x kräver Google Services-pluginet, som projektet
medvetet inte använder (ADR-013), och har öppna problem med AGP 9; utan pluginet saknas bygg-id:t och SDK:n kan krascha
vid start. Inställningen "Skicka kraschrapporter" fanns men gjorde ingenting.
**Beslut:** Ingen krasch- eller analys-SDK. Krascher och ANR följs i Play Consoles **Android vitals**, som Google samlar på
systemnivå från användare som delar diagnostik. Inställningen och katalogposterna för Crashlytics är borttagna.
**Konsekvens:** Färre beroenden och inget samtycke att fråga om; Data safety blir enklare. Saknas detaljer i vitals kan
Crashlytics läggas till senare med Google Services-pluginet i dev/prod och samtycke i Inställningar (integritetspolicy
och Data safety uppdateras då).

## ADR-027 – Kalas och önskelistor
**Kontext:** BRIEF §4.8: OSA-status, presentidé, budget, "present köpt", påminnelse tre dagar innan, egna kalas med
gästlista och inbjudan, önskelista per barn.
**Beslut:** Kalasuppgifterna ligger på händelsen (`Event.party`: OSA och sista dag, present eller – för eget kalas –
gäster och inbjudningstext) och synkas som resten av händelsen; ett kalas känns igen på taggen eller på att uppgifterna
finns. `PartyRules` i kärnan avgör när något behövs: present för ett kalas familjen inte tackat nej till tills den är
köpt eller presentuppgiften är bockad, och OSA när ett datum finns och ingen svarat. Idag-kort för present (en vecka
framåt) och OSA (tre dagar innan sista dagen); notiser kl 18 tre dagar före kalaset och kvällen före OSA-datumet, bara
till föräldrar. "Köpt" bockar även av presentuppgiften från snabbinmatningen. Önskelistor är en egen samling (`wishes`)
som även hjälpare kan läsa och där de får markera "köpt" – inget annat (reglerna + regeltest), så två morföräldrar inte
köper samma sak. Inbjudan delas som text via telefonens delningsmeny; inga gästers kontaktuppgifter sparas.
**Konsekvens:** Gästlistan är bara namn och svar. Barnets egen vy (fas 5) ska inte visa "köpt" på önskelistan.

## ADR-028 – Barnläge, stjärnor och veckopeng utan barnkonton
**Kontext:** BRIEF: barnets förenklade vy (mina sysslor, mina stjärnor, mitt schema) och veckopeng där föräldern godkänner.
Barn har inga konton (dataminimering) och reglerna kan inte skilja ett barn från den vuxna vars telefon det lånar.
**Beslut:** Barnläge öppnas från Mer på en förälders telefon eller en familjeplatta och visar ett barn: dagens händelser
(och vem som hämtar), sysslor tilldelade barnet (även i turordning) och veckans stjärnor. "Klar!" sparas som en
avbockning som väntar (`Completion.approved = false`, för engångsuppgifter `FamilyTask.approved`); en förälder
godkänner eller säger "inte klart" under Uppgifter, och ett Idag-kort påminner. Stjärnor räknas på sysslans datum och på
den som hade turen. Veckopeng (`allowances`, bara föräldrar läser) = grundbelopp + kr per godkänd stjärna, med "utbetald"
per vecka som slås ihop per vecka. Man lämnar barnläge med ett långt tryck. `KidChores` i kärnan bär reglerna.
**Konsekvens:** Barnet "är" alltid den inloggade vuxna mot servern – godkännandet är en familjeöverenskommelse, inte en
säkerhetsgräns. Ett eget barnkonto kan läggas till senare med en barnroll i reglerna.

## ADR-029 – Matilda som lunchkälla via officiellt RSS
**Kontext:** BRIEF nämner fler matsedelsleverantörer än skolmaten.se: Matilda (`menu.matildaplatform.com`), Mashie
(`mpi.mashie.com`), FoodIT (`webmenu.foodit.se`) och specen Open Meal Information. Ingen av dem har ett öppet JSON-API.
Matildas matsedelssida länkar själv till ett RSS-flöde (`/rss?displayMode=week&distributorId=<id>&lang=sv`) med en post
per rätt och dag: beskrivningen börjar med rättens rubrik ("Dagens", "Tillbehör"), sedan rätterna ihopfogade med " & ".
Flödet ger bara innevarande vecka (`startDate` ignoreras). FoodIT:s webbmeny är numera Matildas. Mashie gick inte att nå
från utvecklingsmaskinen och Open Meal Information-sidan finns inte längre, så de väntar.
**Beslut:** `LunchProviderType.Matilda` på barnprofilen. Föräldern klistrar in länken till matsedeln; id:t (24 hextecken
före understrecket) blir `distributorId`. `MatildaLunchParser` slår ihop dagens poster, delar på " & " bara när nästa ord
börjar med versal ("pannbiff med lök & gräddsås" är en rätt) och sätter rubriken framför allt som inte är huvudrätten
("Tillbehör: Nudlar & couscous"). Hämtas högst en gång per dygn, cachas i Room som övriga källor och felar mjukt.
`LunchMenus` väljer leverantör per barn. `LunchSource.provider` har nu ett standardvärde (manuell) så att en äldre
appversion läser en okänd leverantör som manuell i stället för att tappa barnet.
**Konsekvens:** Ingen skrapning – bara flödet som Matilda själva erbjuder. Fler leverantörer läggs till som egna
`LunchMenuProvider` när de har ett öppet flöde; tills dess finns "Egen RSS-länk" och manuell inmatning.
