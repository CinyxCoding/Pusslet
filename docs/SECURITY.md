# Säkerhetsgranskning – Pusslet

**Datum:** 2026-10-29 · **Gren:** `claude/busy-bohr-jdhgn7` · **Beslut:** ADR-058 · **Ramverk:** OWASP MASVS v2 / MASTG,
OWASP API Security Top 10 (2023).

Granskningen omfattar Android-appen (`composeApp` och `shared/*`), Firestore-reglerna, Cloud Functions (`functions/`),
bygg- och CI-konfigurationen. iOS-delen ingår bara genom den delade koden. Firebase- och Google Cloud-konsolen
(App Check, API-nycklar, Auth-inställningar, budget) gick inte att se, och har därför inte kunnat verifieras.

---

## A. Sammanfattning

Appen har en sund grund. Room är källan till sanning och Firebase speglar den. All behörighet avgörs av Firestore-reglerna och
funktionerna, inte i appen. Produktionen har bara HTTPS. Det finns ingen WebView, ingen egen TLS-hantering, ingen rå SQL med
indata, ingen reklam, analys eller AI, och loggarna innehåller inga personuppgifter.

De allvarliga bristerna fanns i vem servern tror att man är:

- En förälder som tagits bort kunde lägga tillbaka sin egen åtkomst, om hen hade skapat familjen.
- En vanlig inbjudan kunde användas för att bli en annan, redan befintlig medlem.
- En kalenderlänk fortsatte att visa familjens kalender för en förälder som tagits bort.

I en app för familjer, och för två hem efter en separation, är det realistiska hot.

Alla tre är rättade, tillsammans med tretton andra fynd. Rättningarna har regressionstester där det går, och de flesta
testerna misslyckades före rättningen. Fyra rättningar har inget eget test och är verifierade med kodgranskning och bygge:
- notisändringarna (SEC-10, SEC-14)
- push-id-kontrollen (SEC-15)
- CI-behörigheterna (SEC-16)

Reglerna och funktionerna skyddar produktionen först när de är driftsatta.

**Status: CONDITIONAL** (se G).

---

## 1. Arkitektur och förtroendegränser

| Del | Fakta (verifierat i koden) |
|---|---|
| App | `se.pusslet.app` (+ `.demo`, `.dev`), minSdk 26, targetSdk 36, compileSdk 37, version 0.1.0. Flavors `demo` (emulatorer, klartext tillåten, utvecklarverktyg), `dev` och `prod` (bara HTTPS, inga utvecklarverktyg). Release: R8 med minifiering och resurskrympning. Signering via `keystore.properties` eller miljövariabler; nycklarna finns aldrig i repot. |
| Data på telefonen | Room (familjens dokument, cache, utkorg), inställningar, `files/attachments` (bilder), WorkManager-databasen. Firebase Auth-token ligger i `shared_prefs` och undantas från säkerhetskopior. |
| Backend | Firebase Auth (anonym, e-post och lösenord, Google). Firestore med `firestore.rules`. Functions v2 i europe-north1: `familyCalendarFeed` (HTTP, hemlig token), `deleteFamily`/`deleteAccount` (callable), fem push-triggers, `purgeDeleted` (schemalagd), `health`. Push sker med FCM-datameddelanden. |
| Tredje part | Livsmedelsverket, Skolverket, SMHI och Open Food Facts (fasta värdar). skolmaten.se och Matilda (fasta värdar). Egen lunch-RSS, ICS-prenumerationer och receptsidor (adresser som familjen anger). Ingen analys, ingen kraschrapportering. |
| Ingångar från andra appar | `MainActivity` (exporterad): startikon, `https://<linkHost>/join/…`, `pusslet://join`, `SEND` text och `SEND image/*`, samt notisens `OPEN`. Två widgetmottagare. Övriga komponenter är inte exporterade eller skyddas av systembehörigheter (kontrollerat i release-APK:n). |
| Behörigheter | INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, READ/WRITE_CALENDAR, CAMERA och ACCESS_COARSE_LOCATION, som alla begärs när funktionen används. Bibliotek lägger till WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE (WorkManager), c2dm RECEIVE (FCM) och USE_BIOMETRIC (Credential Manager). Ingen AD_ID-behörighet. |
| Betalningar | Inte aktiva: `AllUnlocked`, och inget faktureringsbibliotek byggs in. |

**Förtroendegränser:**
1. Andra appar på telefonen (intents, delning, widgetar).
2. Telefonen och Firebase (ID-token; reglerna är behörighetslagret).
3. Föräldrar och hjälpare i en familj.
4. Hem och hem i ett delat utrymme (ADR-056), där parterna kan vara i konflikt.
5. Telefonen och externa källor (opålitligt innehåll).
6. Den som har en kalenderlänk (kapabilitets-URL).
7. CI och repot.

## 2. Hotmodell (urval)

| Hot | Tillgång | Angreppsväg | Skydd före | Lucka → åtgärd | MASVS / API |
|---|---|---|---|---|---|
| Borttagen förälder (separation) | Familjens data | Egen klient mot Firestore-API:t | Reglerna | Skaparens gren gällde för alltid → SEC-01 | API1, API5 |
| Borttagen förälder | Kalendern | Gammal flödeslänk | Token, 404 vid radering | Länken följde inte åtkomsten → SEC-02 | API2, API5 |
| Den som har en inbjudan | En annan medlems identitet och pushar | Valfritt `memberId` vid anslutning | Rollkontroll | `memberId` kontrollerades inte → SEC-03, SEC-04 | API1, API3 |
| Andra hemmet (fientligt) | Delade barnens data | `deleteFamily` på utrymmet | Föräldrakrav | Hårdradering för båda → SEC-05 | API5, API6 |
| Familjemedlem eller andra hemmet | Prenumeranters kalendrar | Radbrytning i titel, regel eller zon | Delvis escaping | → SEC-07 | API8 |
| Skadlig app på telefonen | Privata filer, stabilitet | `SEND image/*` med `file://`, egen provider eller kastande provider | Bildavkodning | → SEC-08 | MASVS-PLATFORM-1 |
| Familjemedlem eller andra hemmet | Andra appar och deras djuplänkar | Önskelistans länk med eget schema | Inget | → SEC-09 | MASVS-PLATFORM-1/3 |
| Person med en låst telefon | Barnets säkerhet ("hämtad") | Notisknapp på låsskärmen | Inget | → SEC-10 | MASVS-AUTH-3 |
| Familjens egna adresser, fientlig server | Minne och CPU | Gigantiska eller patologiska svar | Timeouts | → SEC-11, SEC-12 | MASVS-CODE-4 |
| Leverantörskedjan | Funktionerna och APK:n | Kända sårbarheter | Låsfiler | → SEC-06, SEC-13 | MASVS-CODE-3 |
| Kostnadsmissbruk | Projektets budget | Egna konton som skriver mycket data | Rollregler | App Check saknas → R1 | API4 |
| Andra hemmet | Delade kostnader | Ändrar den andras poster | Inget (design) | → R2 | API6 |

---

## B. Fynd

| ID | Titel | Allvar | Säkerhet | Status |
|---|---|---|---|---|
| SEC-01 | Borttagen skapare kunde återställa sin föräldraåtkomst | Hög | Bekräftad | Rättad |
| SEC-02 | Kalenderlänken fungerade kvar när skaparen inte längre var förälder | Hög | Bekräftad | Rättad |
| SEC-03 | En inbjudan kunde användas för att bli en befintlig medlem | Medel | Bekräftad | Rättad |
| SEC-04 | Medlem kunde byta konto och roll på sig själv, och välja roll vid anslutning | Medel | Bekräftad | Rättad |
| SEC-05 | `deleteFamily` hårdraderade ett delat utrymme för båda hemmen | Medel | Bekräftad | Rättad |
| SEC-06 | Sårbara produktionsberoenden i funktionerna (body-parser, qs) | Medel | Bekräftad | Rättad |
| SEC-07 | ICS-injektion i kalenderflödet | Låg | Bekräftad | Rättad |
| SEC-08 | Bilddelning accepterade `file://` och egna providers; kastande provider kraschade appen | Låg | Bekräftad | Rättad |
| SEC-09 | Länkar ur delad data öppnades med valfritt schema | Låg | Bekräftad | Rättad |
| SEC-10 | "Hämtad" kunde tryckas på en låst telefon | Låg | Bekräftad (kod) | Rättad |
| SEC-11 | Obegränsad storlek på svar från nätet | Låg | Bekräftad | Rättad |
| SEC-12 | Kvadratisk tolkning av lunch-RSS (CPU-DoS) | Låg | Bekräftad | Rättad |
| SEC-13 | Guava med känd sårbarhet (GHSA-xxph-c9ww-hj94) i APK:n | Låg | Bekräftad | Rättad |
| SEC-14 | Notiser utan offentlig version; missvisande kommentar om låsskärmen | Info | Bekräftad | Rättad |
| SEC-15 | Push byggde dokumentsökvägar av id ur dokument | Info | Bekräftad (kod) | Rättad |
| SEC-16 | CI-token utan uttryckliga behörigheter | Info | Bekräftad | Rättad |

### SEC-01 – Borttagen skapare kunde återställa sin föräldraåtkomst
- **Plats:** `firestore.rules`, `families/{fid}/access/{userId}`, `allow create` (skaparens gren).
- **Rotorsak:** Grenen `getAfter(famPath(fid)).data.createdBy == me()` gällde så länge familjedokumentet fanns. Regeln
  för familjeuppdateringar låser `createdBy`, så skaparen kunde alltid skapa sitt eget access-dokument igen.
- **Scenario:** Sara skapar familjen och Johan är förälder. Efter en separation tar Johan bort Saras åtkomst. Sara skriver
  `families/{fid}/access/{saraUid}` direkt via REST-API:t med sin ID-token. Hon är då förälder igen, med läs- och
  skrivrätt till allt.
- **Påverkan:** Åtkomsten går inte att återkalla. Det gäller barnens data, hälsa (om den delas), scheman och adresser.
- **Åtgärd:** Grenen gäller bara när familjen skapas i samma batch: `!exists(famPath(fid)) && getAfter(…)…`. Appens
  `bootstrapFamily` skriver redan familjen och access-dokumentet i en batch och påverkas inte.
- **Verifiering:** Det nya regeltestet *"does not let a creator who was removed give themselves their access back"*
  misslyckades före rättningen (skrivningen lyckades) och går igenom efter. Testerna för att skapa familj och utrymme går
  fortfarande igenom.
- **Kvarvarande risk:** Ingen känd.

### SEC-02 – Kalenderlänken fungerade kvar när skaparen inte längre var förälder
- **Plats:** `functions/src/calendarFeed.ts`.
- **Rotorsak:** Flödet kontrollerade token, familj och tombstone, men inte att skaparen fortfarande hade åtkomst. Token kan
  inte läsas av klienter, så de andra föräldrarna kan inte se den och därför inte stänga den. Bara
  `deleteAccount` städade bort den.
- **Scenario:** En förälder skapar en prenumerationslänk och tas sedan bort eller görs till hjälpare. Hen ser fortsatt
  familjens alla händelser, även privata, i sin kalenderapp utan tidsgräns.
- **Åtgärd:** Funktionen läser `families/{fid}/access/{createdBy}` och svarar bara om rollen är `parent`. Annars blir
  svaret 404. `createdBy` måste se ut som ett dokument-id.
- **Verifiering:** Det nya funktionstestet *"stops when the parent who made the link…"* gav 200 i stället för 404 före
  rättningen och går igenom efter.
- **Kvarvarande risk:** Token syns i Cloud Functions begärandeloggar (R8).

### SEC-03 – En inbjudan kunde användas för att bli en befintlig medlem
- **Plats:** `firestore.rules`, `validInvite`.
- **Rotorsak:** En inbjudan utan `memberId` lät den som gick med välja vilket `memberId` som helst. En namngiven inbjudan
  krävde inte att personen saknade konto.
- **Scenario:** Eve har morfars inbjudningskod (giltig i 7 dagar och flergångs), till exempel från ett gruppchattfoto.
  Hon skapar ett access-dokument med `memberId` = Saras och kan sedan ändra Saras medlemsdokument, inklusive `uid`, så att
  Saras pushar (sjukdagar, hämtningar) går till Eve. Hon kan också skriva förfrågningar i Saras namn.
- **Åtgärd:** Funktionen `claimable` gör tre saker:
  - Ger en ny medlem, eller samma konto som kommer tillbaka.
  - Släpper aldrig in någon som en medlem som redan har ett annat konto.
  - Låter en namngiven inbjudan gälla bara så länge personen inte har ett annat konto.
- **Verifiering:** Det nya regeltestet *"does not let an invite for anyone take over…"* misslyckades före rättningen och
  går igenom efter. Det gör även *"lets someone who left join again…"* och alla befintliga anslutningstester.
- **Kvarvarande risk:** En inbjudan kan användas flera gånger tills den gått ut (R3).

### SEC-04 – Medlem kunde byta konto och roll på sig själv
- **Plats:** `firestore.rules`, `members/{memberId}`.
- **Rotorsak:** Den egna medlemmen fick uppdateras fritt, utan validering av namn eller storlek. Den andra grenen, avsedd
  för anslutning, gällde också för vanliga ändringar.
- **Scenario:** En hjälpare sätter `role: "parent"` eller ett annat `uid` på sin medlem. Andra telefoner visar då fel roll,
  och pushar styrs om.
- **Åtgärd:**
  - Vid egen ändring behålls `uid` och `role`; bara en förälder ändrar dem.
  - Rollen i medlemsdokumentet måste vara densamma som i access-dokumentet vid anslutning.
  - Namnet valideras, och dokumentet får ha högst 64 fält.
- **Verifiering:** De nya testerna *"lets members edit their own member, but not…"* och *"shows a joiner with the role the
  invite gives…"* går igenom. Det första misslyckades före rättningen, och efter den första rättningen hittade det även
  vägen via den andra grenen.
- **Kvarvarande risk:** Ingen känd.

### SEC-05 – `deleteFamily` hårdraderade ett delat utrymme för båda hemmen
- **Plats:** `functions/src/privacy.ts`, `deleteFamily`.
- **Rotorsak:** Alla föräldrar i ett utrymme är föräldrar. Funktionen skilde inte på en familj och ett utrymme.
- **Scenario:** Det ena hemmet anropar `deleteFamily({familyId: utrymmet})` med en egen klient (appen gör det aldrig).
  Det andra hemmets kostnader, scheman och anteckningar om barnen raderas för gott, utan de 30 dagarnas mjukradering.
- **Åtgärd:** `failed-precondition` / `shared-space`. Ett hem lämnar utrymmet, som tidigare, och ett utrymme utan
  föräldrar raderas av `leaveSpace`.
- **Verifiering:** Det nya testet *"never lets one home delete the space for both"* misslyckades före rättningen och går
  igenom efter.
- **Kvarvarande risk:** Mjukradering och borttagning av den andras åtkomst går fortfarande (R2).

### SEC-06 – Sårbara produktionsberoenden i funktionerna
- **Plats:** `functions/package-lock.json` (via firebase-functions → express).
- **Fynd:** body-parser (hög, DoS via efterföljande byte i en komprimerad kropp, GHSA-27r3-jxmh-p828) och qs (medel).
- **Åtgärd:** `npm audit fix`, som bara gör versionsbyten inom de semver-intervall som redan tillåts. `npm audit --omit=dev`
  visar 0.
- **Verifiering:** Hela funktions- och regelsviten går igenom på den nya låsfilen (D).
- **Kvarvarande risk:** Tio råd återstår i utvecklingsverktygen, som inte driftsätts (R6).

### SEC-07 – ICS-injektion i kalenderflödet
- **Plats:** `functions/src/ics.ts`.
- **Rotorsak:** `rrule`, `timeZoneId` och `seriesId`/`id` skrevs ut råa. `escapeText` lämnade ensam CR och andra
  styrtecken.
- **Scenario:** Någon med skrivrätt till en händelse (familjen eller andra hemmet) lägger `\r\nBEGIN:VALARM…`, `URL:` eller
  `ATTACH:` i en regel eller titel. Det hamnar i alla prenumeranters kalendrar, eller förstör flödet.
- **Åtgärd:**
  - All text escapas med alla radbrytningar, och styrtecken tas bort.
  - Zonen måste vara ett IANA-id (annars används Stockholm).
  - Regeln måste bestå av RRULE-tecken (annars utelämnas den).
  - UID tvättas.
- **Verifiering:** Det nya testet *"never lets what someone typed…"* misslyckades före rättningen och går igenom efter.
  De befintliga ICS-testerna är oförändrade och går igenom.

### SEC-08 – Bilddelning
- **Plats:** `composeApp/.../attachments/AndroidAttachmentFiles.kt`, `import()`.
- **Rotorsak:** Vilken `Uri` som helst lästes. Bara `IOException` och `SecurityException` fångades, och `MainActivity` är
  exporterad.
- **Scenario:**
  - En skadlig app delar `file:///data/data/se.pusslet.app/…` eller `content://se.pusslet.app.files/…`, och appen läser
    sina egna privata filer åt den (confused deputy).
  - En provider som kastar `IllegalStateException` kraschar appen.
- **Åtgärd:** Bara andra appars `content://` läses. Allt utom avbrott blir "ingen bild" och loggas utan innehåll.
- **Verifiering:** De befintliga testerna läste via `file://`, vilket bevisar att det gamla beteendet accepterade sådana
  länkar. De går nu via en provider. Nya tester täcker `file://`, den egna providern och en kastande provider. 4 av 4
  går igenom.

### SEC-09 – Länkar ur delad data
- **Plats:** `composeApp/.../platform/AndroidPlatformActions.kt`, `openUrl`.
- **Rotorsak:** `ACTION_VIEW` gick till valfritt schema utan `CATEGORY_BROWSABLE`. Önskelistans länk kan skrivas av andra
  hemmet.
- **Åtgärd:** Bara `http`/`https` med värd, och bara till appar som öppnar länkar från en webbläsare.
- **Verifiering:** `OpenUrlTest` (intent:, file:, content:, swish:, javascript:, tom värd, länk utan schema) går igenom.

### SEC-10 – "Hämtad" på en låst telefon
- **Plats:** `composeApp/.../notify/AndroidNotifier.kt`.
- **Åtgärd:** `setAuthenticationRequired(true)` på åtgärden (Android 12+). Kvittot säger till hela familjen att barnet är
  hämtat.
- **Verifiering:** Kodgranskning och bygge. Det finns inget automatiskt test, eftersom notiser inte testas med Robolectric
  här.
- **Kvarvarande risk:** På Android 8–11 finns inget API för detta.

### SEC-11 – Obegränsade svar
- **Plats:** `CachedFetcher`, `SubscriptionSync.preview`, `RecipeImporter.page`.
- **Rotorsak:** `HttpClient.get` sparar hela kroppen i minnet innan den läses. Adresserna kan komma från familjen.
- **Åtgärd:** `prepareGet().execute { bodyAsTextAtMost(…) }` läser strömmande med tak: kalenderflöden 10 MB, lunch-RSS
  1 MB, receptsidor 8 MB och övrigt 10 MB. Ett större svar räknas som ett misslyckat hämtförsök, och den gamla kopian
  ligger kvar.
- **Verifiering:** Det nya testet *"anAnswerLargerThanItsLimit…"* går igenom.

### SEC-12 – Kvadratisk tolkning av lunch-RSS
- **Plats:** `shared/data/.../sources/LunchMenuProvider.kt`.
- **Rotorsak:** `<item>([\s\S]*?)</item>` och `<[^>]+>` söker till slutet från varje öppning som aldrig stängs.
- **Mätning (gammalt mönster, backtracking-motor):**

  | Öppna `<item>` | Tid |
  |---|---|
  | 2 000 | 0,42 s |
  | 4 000 | 1,97 s |
  | 8 000 | 9,18 s |

  Tiden ökar ungefär 4,5 gånger när indata dubbleras. Ett flöde på 1 MB skulle ta flera minuter till timmar.
- **Åtgärd:**
  - Element och taggar hittas med indexsökning, som är linjär.
  - Texter kortas innan något reguljärt uttryck körs.
  - Svaret får vara högst 1 MB.
- **Verifiering:** *"aHostileLunchFeedIsReadInLinearTime"* tar 14 ms. Fixturtesterna för skolmaten och Matilda ger samma
  menyer som förut.

### SEC-13 – Guava (GHSA-xxph-c9ww-hj94, publicerad 2026-10-27)
- **Plats:** Kommer transitivt via Firebase och Credential Manager (33.3.1-android).
- **Påverkan:** Appen använder inte Java-deserialisering. På Android 8–12 packar ett `Bundle` ändå upp alla extras när
  ett av dem läses, så en skadlig app kan krascha appen med en sådan nyttolast. Det är en DoS-risk.
- **Åtgärd:** Versionskravet `guava ≥ 33.7.2-android` (`libs.versions.toml`, `composeApp` constraints).
- **Verifiering:** OSV-frågan visar 0 råd för 403 koordinater, och release-bygget och testerna går igenom.

### SEC-14 – SEC-16 (information)
- **SEC-14:** Notiserna har nu en offentlig version med bara typen, utan namn och utan hälsa. Den visas när ägaren valt
  att dölja känsligt innehåll på låsskärmen. Kommentaren beskriver nu att Androids standardinställning visar texten.
- **SEC-15:** `deliver` godtar bara id som är ett dokument-id, utan `/`, `.` eller `..`.
- **SEC-16:** `.github/workflows/ci.yml` har `permissions: contents: read`.

---

## C. Ändringar

| Fil | Varför |
|---|---|
| `firestore.rules` | SEC-01, SEC-03, SEC-04 |
| `functions/src/calendarFeed.ts` | SEC-02 |
| `functions/src/ics.ts` | SEC-07 |
| `functions/src/privacy.ts` | SEC-05 (orsakskod `shared-space`) |
| `functions/src/push.ts` | SEC-15 |
| `functions/package-lock.json` | SEC-06 |
| `functions/test/rules.test.ts`, `feed.test.ts`, `ics.test.ts`, `deletion.test.ts` | Regressionstester (8 nya) |
| `composeApp/.../attachments/AndroidAttachmentFiles.kt` (+ test) | SEC-08 |
| `composeApp/.../platform/AndroidPlatformActions.kt`, `OpenUrlTest.kt` (ny) | SEC-09 |
| `composeApp/.../notify/AndroidNotifier.kt`, `PickupDoneReceiver.kt` | SEC-10, SEC-14 |
| `composeApp/src/test/.../SecurityConfigTest.kt` (ny) | Vakt: bara HTTPS, exporterade komponenter, FileProvider, säkerhetskopior |
| `composeApp/build.gradle.kts`, `gradle/libs.versions.toml` | SEC-13 |
| `shared/data/.../net/HttpClientFactory.kt` | `bodyAsTextAtMost`, `ResponseTooLargeException` (SEC-11) |
| `shared/data/.../sources/CachedFetcher.kt`, `SubscriptionSync.kt`, `usecase/RecipeImporter.kt` | SEC-11 |
| `shared/data/.../sources/LunchMenuProvider.kt` (+ `OpenDataAdaptersTest`) | SEC-11, SEC-12 |
| `.github/workflows/ci.yml` | SEC-16 |
| `docs/DECISIONS.md` (ADR-058), `docs/SECURITY.md`, `docs/PROGRESS.md`, `CLAUDE.md` | Dokumentation |

Inga filer har tagits bort. Inga funktioner har tagits bort.

---

## D. Verifiering

| Kontroll | Kommando / metod | Resultat | Begränsning |
|---|---|---|---|
| Regel- och funktionstester före rättning | `npm test` (functions/) | **6 misslyckade** (exakt de nya), 83 godkända | – |
| Regel- och funktionstester efter | `npm ci && npm run lint && npm test` | **90 av 90** godkända. En körning samtidigt med Gradle-bygget gav en tidsgräns i ett push-test; omkört utan belastning: 90/90 | Emulatorer, inte produktion |
| Kotlin-enhetstester | `./gradlew testAndroidHostTest :composeApp:testDemoDebugUnitTest` | **1 124** godkända, 0 fel. Efter stilrättningarna är datamodulen (139) och de nya apptesterna omkörda och gröna | JVM/Robolectric |
| Kodstil och statisk analys | `./gradlew ktlintCheck detekt` | Gröna efter rättning av tre stilfel i den nya koden | – |
| Android Lint | `./gradlew :composeApp:lintProdRelease` | 34 varningar, ingen i säkerhetskategorin (versionsuppdateringar, OldTargetApi, `CredentialManagerMisuse`, som redan fångas via överklassen) | – |
| Debugbygge | `:composeApp:assembleDemoDebug` | Lyckades | – |
| Releasebygge | `:composeApp:assembleProdRelease` (R8) | Lyckades | Osignerad, utan `google-services.json` |
| Release-manifest | `aapt2 dump xmltree` på APK:n | Inte felsökningsbar, ingen klartext, `networkSecurityConfig` satt, ingen AD_ID; exporterat utan systembehörighet är bara `MainActivity`, två widgetar och Firebase Auths två omdirigeringsaktiviteter | – |
| Hemligheter | Eget mönsterskript (Google-nycklar, privata nycklar, tjänstekonton, tokens, lösenord, nyckelfiler) över 153 commits och arbetskatalogen | Inga riktiga hemligheter, bara falsklarm (parameternamn, platshållare i SETUP.md) | gitleaks/trufflehog finns inte installerade |
| npm, produktion | `npm audit --omit=dev` | 2 → **0** | – |
| npm, allt | `npm audit` | 21 → 10 (bara utvecklingsverktyg) | Kräver `--force` och nya huvudversioner |
| Maven-beroenden | OSV querybatch (api.osv.dev) över `prodReleaseRuntimeClasspath` | 1 → **0** (Guava) | Bara koordinater skickades, ingen kod |
| Kvadratisk regex | Mätning med Python `re` | 0,42 / 1,97 / 9,18 s för 2k / 4k / 8k | Annan motor än JVM, samma komplexitet |
| Dynamiskt test (demo-bygget på emulatorn) | `adb shell am start` som en annan app: `SEND image/*` med `file://…/databases/pusslet.db` och med den egna FileProviderns länk, ett trasigt notismål, en inbjudningslänk med skräp | Allt vägrat eller ignorerat ("Import refused: not another app's content"), samma process lever, ingen krasch, inget sparat | Emulatorn har en riktig familj, så inget bekräftades eller sparades. Ingen kastande provider (en egen app hade behövts; täcks av enhetstest) |
| Ej utfört | MobSF, OWASP Dependency-Check (NVD), instrumenterade tester (finns inga), pentest mot produktion | – | Verktyg saknas eller är inte tillåtna |

---

## E. Kvarvarande risker

| ID | Risk | Allvar | Rekommendation |
|---|---|---|---|
| R1 | App Check är inte påslaget. Ett eget konto kan skriva godtyckliga samlingar och stora dokument (kostnadsmissbruk, API4) | Medel | **Beslut (ADR-059): vid lanseringen.** App Check (Play Integrity) först i övervakningsläge, sedan krav på Firestore och funktionerna. Budgetlarm. Överväg en lista över tillåtna samlingsnamn i reglerna |
| R2 | ~~Andra hemmet kunde ta bort varandras kostnader och åtkomst~~ | – | **Åtgärdad (ADR-059):** ett hem hanterar bara sina egna vuxna, sin plats i `familyIds` och de kostnader det lade in, och varje kostnad visar "inlagd av". Kvar: båda hemmen kan ändra barnens delade saker (händelser, packning), vilket är meningen |
| R3 | ~~En inbjudan kunde användas flera gånger~~ | – | **Åtgärdad (ADR-059):** en kod används en gång och markeras i samma batch som anslutningen |
| R4 | Hjälparvyn (den enkla vyn för hjälpare) är ingen säkerhetsgräns; det är reglerna som avgör vad en hjälpare läser och ändrar | Info | Barnläget, med stjärnor och godkännanden, är borttaget (ADR-060), och reglerna låter inte längre hjälpare ändra `approved` |
| R5 | Push-registreringen ligger kvar efter utloggning | Låg | **Delvis åtgärdad (ADR-059):** utloggningen frågar om familjen ska tas bort från telefonen och förklarar valet. Avregistrera `users/{uid}/devices/{id}` före utloggning |
| R6 | Tio npm-råd i utvecklingsverktyg (firebase-tools, firebase, rules-unit-testing: grpc-js, uuid, opentelemetry) | Låg | Uppdatera när det finns rättade versioner |
| R7 | Actions är pinnade på taggar, inte SHA. Ingen Gradle dependency verification | Låg | Pinna på SHA med Dependabot, `--write-verification-metadata sha256` |
| R8 | Token i kalenderlänken syns i Cloud Functions begärandeloggar | Info | Begränsa åtkomsten till loggarna, och överväg kort lagringstid |
| R9 | Säkerhetskopior innehåller Room-databasen (Androids säkerhetskopia, krypterad med skärmlåset) | Info | Beskriv det i integritetspolicyn |
| R10 | Adresser som familjen eller andra hemmet anger (ICS, lunch-RSS) hämtas automatiskt och avslöjar telefonernas IP-adress för den servern | Info | Det hör till funktionen. Nämn det vid delning |
| R11 | Emulatorerna lyssnar på 0.0.0.0 (bara för utveckling) | Info | 127.0.0.1 när ingen fysisk telefon behöver dem |
| R12 | `MainActivity` är `singleTask` med standardaffinitet (StrandHogg 1) | Info | Prova `android:taskAffinity=""` på en riktig telefon |
| R13 | Konsolinställningar har inte verifierats: API-nyckelbegränsning, e-postuppräkningsskydd, lösenordspolicy, auktoriserade domäner, `assetlinks.json` på linkHost, budget | Okänt | Se F |
| R14 | Betalningar är inte aktiva. Köp måste verifieras på servern när de slås på (TODO i `Entitlements.kt`) | – | Före betalningar |
| R15 | targetSdk 36, där 37 finns | Info | Uppgradera med tester |

---

## F. Driftsättning

> Firebase-projektet är inte skapat än (ADR-059): det finns ingen produktion som är utsatt, och stegen nedan gäller när
> projektet skapas inför lanseringen.

1. Granska grenen och slå ihop den.
2. `cd functions && npm ci && npm test`, som kör regler och funktioner mot emulatorerna.
3. `firebase deploy --only firestore:rules,functions --project <prod>`. Ingen datamigrering behövs:
   - Befintliga familjer, medlemmar och inbjudningar fungerar.
   - Kalenderlänkar vars skapare inte längre är förälder slutar svara direkt, vilket är avsett.
4. Bygg en ny app-version (`./gradlew :composeApp:bundleProdRelease`) med appändringarna och ladda upp den.
5. Gör följande i Firebase- och Google Cloud-konsolen:
   - App Check med Play Integrity, först i övervakningsläge. När appen skickar App Check-tokens slår du på krav och
     `enforceAppCheck: true` i `privacy.ts`. Appen skickar App Check-tokens: Play Integrity i release-byggen och en
     felsökningstoken i debug-byggen (SETUP.md 2.2).
   - Begränsa Androids API-nyckel till paketnamn och SHA-1.
   - Auth: e-postuppräkningsskydd, lösenordspolicy, och stäng av oanvända inloggningsmetoder.
   - Budgetlarm.
   - Publicera `/.well-known/assetlinks.json` på linkHost, så att inbjudningslänkar går till appen.
6. Inga hemligheter behöver roteras, eftersom inga hittades.

## G. Bedömning

**CONDITIONAL.** Inga kända kritiska eller höga brister finns kvar inom det granskade området, och alla rättningar har
verifierats med tester och byggen. Ägaren har valt i R2 och R3 (ADR-059), och båda är åtgärdade. Följande återstår, och
det görs när Firebase-projektet skapas inför lanseringen:
- Driftsättning av reglerna och funktionerna.
- En ny app-version.
- Konsolinställningar som inte kunnat kontrolleras (R1, R13), App Check inräknat.

Det betyder inte att appen är garanterat säker.

### Täckning per fas
| Fas | Status |
|---|---|
| 1 Kartläggning | Klar |
| 2 Hotmodell | Klar |
| 3 Hemligheter och historik | Klar |
| 4 Nätverk och API | Klar: TLS och klartext, timeouts, återförsök, storlek, behörighet i regler och funktioner |
| 5 Plattform | Klar: manifest, intents, PendingIntent, FileProvider, behörigheter, säkerhetskopior |
| 6 Lagring | Klar: Room, inställningar, bilder, notiser, inget urklipp |
| 7 WebView | Ej tillämplig (finns inte) |
| 8 Notiser, djuplänkar | Klar |
| 9 Kryptografi | Ingen egen kryptografi. Slumptal via `Uuid.random()` (CSPRNG) för inbjudningar (≈49 bitar) och flödestokens (≈244 bitar) |
| 10 Indata | Klar: SQL parametriserad, FTS-sökningen tvättas, ICS, RSS, HTML och bilder |
| 11 Konto | Klar för appen och funktionerna. Auth-inställningarna i konsolen är inte verifierade |
| 12 Leverantörskedja | Klar: npm audit, OSV, Lint, Actions, Gradle-repon (innehållsfiltrerade), wrapperns kontrollsumma |
| 13 Integritet och loggar | Klar: loggar utan personuppgifter, ingen analys eller kraschrapportering |
| 14 Affärslogik | Klar: betalningar ej aktiva, delade kostnader (R2) |
| 15 Tillförlitlighet | Delvis: trasiga dokument hoppas över, hämtningar fallerar mjukt, gränser. Inga stresstester på enhet |
| 16 Release | Klar: R8, flavors, signering och manifest |
| 17 Automatiska tester | Se D |
| 18–19 Rättningar och verifiering | Se B–D |
| 20 Förebyggande | `SecurityConfigTest`, regressionstester, CI-behörigheter, ADR-058 |
| 21 Rapport | Detta dokument |
