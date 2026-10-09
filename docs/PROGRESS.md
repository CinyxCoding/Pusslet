# Progress

Status per fas. ✅ klart · 🟡 delvis · ⬜ inte påbörjat. Uppdateras efter varje milstolpe.

## Fas 0 – Grund
- [x] KMP-projekt med modulstruktur (`composeApp`, `shared/{core,data,ui,app,feature-*}`, `build-logic`)
- [x] Version catalog med verifierade versioner (ADR-003)
- [x] Flavors `demo` / `dev` / `prod`
- [x] Koin, Room (KMP, bundled SQLite), Ktor (timeouts, retry, User-Agent)
- [x] Navigation-skal (Navigation 3, bottennavigering Idag · Kalender · Mat · Handla · Mer, global +)
- [x] Tema ljust/mörkt, dynamic color som tillval, kontrasttestad medlemspalett
- [x] ktlint + detekt
- [x] CI-workflow (GitHub Actions: Android lint+test+build, iOS-länkning på macOS, Firestore-regeltester mot emulator)
- [x] Firebase-emulatorkonfiguration (`firebase.json`, `.firebaserc` demo-pusslet, regler, `functions/` i TypeScript + regeltest)
- [x] `CLAUDE.md`, `docs/BRIEF.md`, `docs/DECISIONS.md`, `docs/PROGRESS.md`, `docs/SETUP.md`
- [x] Testanrop mot Livsmedelsdatabasen, SMHI SNOW1g, Skolverket, skolmaten-RSS, Open Food Facts → fixturer
- [x] ✅ `./gradlew :composeApp:assembleDemoDebug` bygger
- [x] ✅ Appen startar på emulator (API 36, AVD `pusslet_api36`) och visar navigationen; per-app-språk sv fungerar. Skalet har även Roborazzi-skärmdumpar (ljust/mörkt/engelska)
- [ ] iOS-target kompilerar (verifieras i macOS-jobbet i CI; går inte att bygga på Windows)

## Fas 1 – Familj, kalender, Idag
- [x] Auth: e-post, anonym i demo; Google Sign-In via Credential Manager (aktiveras när ägaren lagt in OAuth-klienten)
- [x] Skapa/gå med i familj via länk/QR (App Links + pusslet://, kod, skanner)
- [x] Barnprofiler med Skolverket-lookup, storlekar, kontakter, kost (opt-in)
- [x] Kalender: dag/vecka/månad/agenda med veckonummer, personfilter, lager, redigering (en gång/hela serien) och detaljvy
- [x] Svenska helgdagar lokalt (inkl. klämdagar, aftnar) – korskontrollerat mot Googles helgdagsflöde (11 år)
- [x] Vårdnadsschema + överlämningskort (UI + delning)
- [x] Idag-vy: tidslinje, ska-med-idag, SMHI-väder, klädråd, kontextkort, mat, uppgifter
- [x] Svensk snabbinmatning (81 parsertester) med bekräftelsekort, osäkra fält markerade, Ångra
- [x] Synkmotor (11 tvåenhetstester mot fejkad backend) + säkerhetsregler + 25 regeltester mot emulatorn
- [x] Seed-data "Familjen Lind" (DemoSeed)
- [x] ✅ Två enheter i samma familj ser varandras ändringar, fungerar offline – verifierat 2026-10-08 på API 36-emulator + "andra enhet" via klient-SDK mot Firestore-emulatorn (se nedan). Två samtidiga Android-emulatorer ryms inte i maskinens 8 GB RAM


## Fas 2 – Mat & handla
- [x] Receptbibliotek (sök i titel och ingredienser, taggfilter, favoriter), receptvy med portionsskalning (svenska enheter,
      läsbara mängder), redigerare med förhandsvisning av hur varje ingrediensrad tolkas
- [x] URL-import (schema.org JSON-LD) från länk eller delad länk (SEND text/plain med URL) – källan visas och krediteras;
      utan receptdata: klistra in texten → regelbaserad uppdelning i ingredienser och steg (RecipeTextSplitter)
- [x] Veckomatsedel med veckonummer: kopiera förra veckan, favoritveckor, flytta/byt dag, gör dubbelt (rester nästa dag
      eller matlåda, ADR-020), konfigurerbara måltider (frukost/lunch/middag)
- [x] Livsmedelsdatabasen: lokalt FTS-index, näring per portion i receptvyn och veckans snitt; användaren kan granska och
      rätta matchningen per ingrediens (sparas för familjen). Neutral framställning, inga mål eller värderande ord
- [x] Skollunch: skolmaten-RSS (konfigureras på barnprofilen) + manuell inmatning i dagsarket; visas per barn i veckovyn,
      krockvarning "Ella och Simon har redan ätit köttbullar till lunch"
- [x] Smarta förslag (regelbaserat, förklarade skäl): favoriter, inte lagat på länge, snabbt på kvällar med aktiviteter i
      kalendern (16:30–19:00), annat än skollunchen, varierar veckan, använder det ni har hemma, respekterar allergier/kost (ADR-018)
- [x] Inköpslista från matsedeln med förhandsvisning ("3 nya, 1 ändrad"), sammanslagning, Hemma-lager, enskilt recept → lista
- [x] Flera listor, snabbtillägg ("mjölk, 2 gurka och pasta"), avdelningar i butikens gångordning, egna butiker med egen ordning,
      inlärd avdelning när användaren rättar, egna priser, dela som text, basvaror från köphistorik (ADR-019)
- [x] Butiksläge: stor text, avbockat sjunker till "I vagnen", ångra, skärmen hålls vaken, "Klart!"-animation
- [x] Streckkodsskanning (CameraX + ML Kit) → Open Food Facts → vara; okänd kod → användaren namnger varan
- [x] "Jag handlar nu": synligt för familjen ("Sara handlar nu – lägg till det du behöver"), förnyas medan butiksläget är öppet,
      aviserar i butiksläget när någon annan lägger till en vara. (Push till låst telefon kommer med FCM i fas 3.)
- [x] ViewModel-tester mot riktiga repositories (ADR-017): Mat 21, Handla 10; Roborazzi: veckovy (ljust/mörkt/150 %), dagsark,
      bibliotek, receptvy, inköpslista (ljust/mörkt), butiksläge
- [x] ✅ 5 recept → korrekt sammanslagen och avdelningssorterad lista (ShoppingListGeneratorTest, MealDataTest, MealsViewModelTest)

## Fas 3 – Integrationer & notiser
- [x] ICS-prenumerationer – lista med status (antal händelser, senast hämtat, fel i klartext), lägg till/ändra (namn, länk,
      vem den gäller, färg), av/på, uppdatera nu; WorkManager hämtar var 6:e timme med nät; webcal://-länkar och delade
      .ics-länkar öppnar dialogen. Gamla händelser gallras (FeedWindow) så att även stora flöden går snabbt
- [x] Tvåvägs enhetskalender (CalendarContract) – egna kalendrar visas som eget lager (krockar med jobbet syns) och
      familjens händelser skrivs till en vald kalender eller en egen "Pusslet"-kalender vid varje ändring och var 6:e timme
      (serier som RRULE, flyttade tillfällen som egna händelser, borttagna försvinner)
- [x] Eget ICS-flöde (Cloud Function) – `calendarFeed` + Hosting-omskrivning `/feed/<token>.ics`, hela familjen eller
      "bara mina", skapa/dela/återkalla i Inställningar, "återkalla alla" via `revokeCalendarFeeds` (ADR-021).
      Tester: 7 enhet (escaping, vikning, serier/undantag/flyttade/heldag, golden), 4 mot emulatorn, Kotlin-rundtur
- [x] Konfliktdetektering, hämta/lämna-förfrågningar – "Kan någon hämta?" till en person eller alla vuxna; notis med
      "Jag tar det"/"Kan inte" direkt i notisen, svaret tillbaka till den som frågade, "Jag tar det" besvarar öppna frågor
- [x] FCM, lokala påminnelser, morgonnotis, tysta timmar, notiskanaler (ADR-023) – planeras på telefonen 48 h framåt
      (påminnelser, hämtnudd 15 min före, uppgifter med tid, morgonkoll), fem kanaler; push från Functions för förfrågningar,
      svar, varor medan någon handlar och andras kalenderändringar, sammanslagna. 7 kärntester, 7 notistester, 6 Functions-tester
- [x] Två widgets (Idag, Inköpslista) (ADR-024) – Idag: datum + vecka, nästa händelse, vem som lämnar/hämtar (varnar när
      ingen är satt), ska med, i morgon på kvällen; Inköpslista: butiksordning, bocka av, + med snabbtillägg. 4 kärntester,
      5 widgettester
- [x] Dela bild till appen → bifogas en händelse eller en anteckning, bara på telefonen (ADR-025). 4 VM-tester, 2 importtester
- [x] Uppgifter/sysslor med rotation, fördelningsvy – listor (mina/alla/sysslor; försenat, idag, kommande, när det passar),
      snabbtillägg, redigerare med checklista och upprepning, turordning per vecka/varannan vecka, "Johan nästa gång",
      fördelning senaste 4 veckorna (uppgifter, sysslor, lämningar, hämtningar) utan poäng. Bugg rättad: roterande syssla utan
      startdatum visades felaktigt på rotationens måndag
- [x] Export/radering av data – export (JSON) fanns; nu även `deleteFamily`/`deleteAccount` (rekursivt, roller,
      Auth-användare) med 4 emulatortester och knappar i Integritet (ADR-022)
- [x] ✅ Publik test-ICS visas korrekt (återkommande + tidszoner); familjehändelser syns i enhetens kalender
      – andra halvan verifierad på emulatorn 2026-10-09: 13 familjehändelser med RRULE i kalendern "Pusslet" i
      CalendarContract och instanserna (t.ex. förskola mån–fre) räknas ut av kalenderleverantören, som alla kalenderappar läser.
      Google Kalender-appen på emulatorn visar inget utan Google-konto – kontrollera på en riktig telefon (SETUP 3).
      Första halvan: Kubernetes-communityns publika Google-kalender (15 643 händelser, 24 MB, parsas på 0,8 s) – ett
      urval ordagranna rader som fixtur: veckomöte 10:00 Los Angeles visas 19:00 → 18:00 (vecka 44, mellan EU:s och USA:s
      sommartidsbyte) → 19:00; varannan-vecka-serie med EXDATE och RECURRENCE-ID. Verifierat genom WorkManager-workern till
      kalendervyn (SubscriptionRefreshWorkerTest + skärmdump `calendar_subscription_week44`)

## Fas 4 – Polish & Play-beredskap
- [x] Tillgänglighetsgenomgång, skärmdumpstester (ljust/mörkt/stor text) – `AccessibilityTest` går igenom
      semantikträdet för Idag, Kalender, Mat, Handla, Mer och händelsedetaljer: allt tryckbart har ett namn och minst
      48 dp (ATF rapporterar inget under Robolectric – kontrollerat med en avsiktligt felaktig knapp). Nya skärmdumpar i
      200 % textstorlek (Idag, vecka, inköpslista) och mörk vecka. Hittat och rättat: vyväljaren Dag/Vecka/Månad/Agenda
      klipptes i stor text
- [x] Prestanda (kallstart, 500+ varor) – releasebygget startar kallt på 2,9 s på emulatorn med mjukvaru-GPU (riktiga
      telefoner är betydligt snabbare); listor är lata med nycklar; `ShoppingPerformanceTest` (600 varor genom de riktiga
      repositoryna) och ICS-flödet med 15 643 händelser (0,8 s) håller budget
- [x] Felhantering och tomma lägen överallt, onboarding < 2 min – vänliga tomma lägen på alla listor, nätfel med
      klartext och "försök igen", ny remsa när ändringar väntar på nät. `CriticalFlowsTest` kör onboarding (4 korta steg),
      snabbinmatning, inköpslista från matsedeln och butiksläget genom hela appen. Hittat och rättat: onboardingens
      "Klart!"-steg visades aldrig
- [x] Svenska + engelska texter genomlästa – alla ~900 strängar per språk; engelska exempel i snabbinmatningen som
      tolken förstår (testade), 30 oanvända strängar borttagna, studiedag/kalas säger "idag/imorgon"
- [x] Entitlements/Billing-förberedelse, `docs/MONETIZATION.md` – allt upplåst, `NoBilling`, gränsen för gratisversionen
      kontrolleras i ViewModel (testad). Kraschrapport-reglaget som inte gjorde något är borttaget (ADR-026)
- [x] `docs/PRIVACY.md`, `docs/PLAY_DATA_SAFETY.md`, `docs/STORE_LISTING.md` (sv+en) och `docs/DESIGN.md`
- [x] Releasebygge (R8), signeringsinstruktioner, lanseringschecklista (SETUP 4–5) – den krympta demo-appen
      verifierad på emulatorn: alla flikar, snabbinmatning, widget-avbockning (Glance-callback) och "Dela bild"
- [x] ✅ `./gradlew build` grönt (alla varianter, lint, detekt, ~470 JVM-tester), release-AAB bygger
      (`bundleProdRelease`, 26 MB, osignerad utan uppladdningsnyckel) – 2026-10-09

## Fas 5 – V2
- [x] Sök – händelser (familj, prenumerationer, egna kalendrar; en serie en gång vid nästa tillfälle), recept (namn och
      ingredienser), att göra, varor och barnens kontakter; skiftlägen och å/ä/ö spelar ingen roll, svenska sammansättningar
      hittas inifrån ("läkare" → tandläkare). Från Idag och Mer. 4 + 3 tester + flödestest
- [x] Kalas och presenter (ADR-027) – OSA med sista dag, presentidé, budget, köpt (bockar presentuppgiften), eget kalas med
      gästlista och delbar inbjudan, Idag-kort och notiser (present tre dagar innan, OSA kvällen före), önskelistor per barn
      som morföräldrar kan bocka av. 4 regel-, 1 planerar-, 1 Idag-, 3 VM-tester, regeltest för önskelistor, skärmdumpar
- [x] Barnvy och veckopeng (ADR-028) – barnläge (dagen, mina sysslor, stjärnor, veckopeng hittills; lämnas med långt
      tryck), sysslor och uppgifter kan ges till barn med 0–5 stjärnor, föräldrar godkänner eller säger "inte klart"
      (Uppgifter + Idag-kort), veckopeng per barn med utbetalda veckor. 3 kärn-, 3 VM-, 1 Idag-test, skärmdumpar
- [ ] Fler lunchleverantörer
- [ ] Flerhushållsdelning fullt ut

## Verifiering fas 1 (2026-10-08)
Demo-APK på emulator `pusslet_api36` mot Firebase-emulatorerna (auth + firestore):
- Demofamiljen seedas lokalt och synkas upp (13 händelser, 3 medlemmar, 2 barn, 8 uppgifter …), alla skrivningar stämplade.
- "Johans telefon" (`functions/scripts/second-device.mjs`) går med via inbjudan genom reglerna och lägger till en händelse →
  syns direkt på Android, och konfliktdetektorn flaggar att Simon har förskola samtidigt.
- Android i flygplansläge, Johan byter titel → syns ~5 s efter återanslutning.
- Android i flygplansläge, "Jag tar det" → visas lokalt direkt, köas, når servern ~3 s efter återanslutning som fältvis
  ändring (Johans titel bevaras).
- Buggar som hittades och rättades: (1) regeln för `members` nekade anslutning som tar över en befintlig medlem
  (nytt regeltest), (2) lämnings-/hämtningstider konverterades inte till enhetens tidszon, (3) engelska strängar med `\'`
  visades bokstavligt (nytt strängtest), (4) regeltesterna rensade demoappens data på delad emulator (eget projekt-id).

Nästa steg (fas 3): ICS-prenumerationer (UI + WorkManager var 6:e h), tvåvägs enhetskalender (CalendarContract), eget ICS-flöde
(Cloud Function med hemlig token), hämta/lämna-förfrågningar, FCM + lokala påminnelser + morgonnotis + tysta timmar +
notiskanaler, två Glance-widgets, uppgifter/sysslor med rotation och fördelningsvy, export/radering (Cloud Function).

## Verifiering fas 3 (2026-10-09)
Demo-APK på emulatorn (Pixel 9 Pro, API 37) utan backend:
- Båda widgets läggs till från widgetväljaren (namn och beskrivning på svenska/engelska). Idag-widgeten visar heldag,
  lämningar/hämtningar med vem och ska med; tryck på en rad öppnar tillfället. Inköpslistan i butiksordning; bocka av
  försvinner direkt; + → "citroner, 3 lime" hamnar som två varor under frukt och grönt; titeln öppnar Handla-fliken.
- Exporten till telefonens kalender (se ovan); påminnelsejobben för dagens hämtningar ligger i WorkManager.
- Bugg som hittades och rättades: en avbockad vara låg kvar på widgeten (Glance ritar inte om innehåll som laddats före
  kompositionen medan sessionen är öppen) – innehållet är nu ett flöde (ADR-024).
- FCM end-to-end kräver ett riktigt Firebase-projekt (emulatorn har ingen FCM); mottagare, betalaster och mottagarval är
  testade mot Firestore-emulatorn och i Robolectric.

Nästa steg (fas 5, V2): sök, kalas och presenter, barnvy med sysslor och veckopeng, fler lunchleverantörer och
flerhushållsdelning fullt ut.

## Kända begränsningar / buggar
- Emulatorn på utvecklingsmaskinen (4 kärnor, mjukvaru-GPU) är långsam och visar ibland ANR-dialoger för System UI – inte appens fel.
- iOS kan inte kompileras på Windows; verifieras i CI (macOS-runner).
- Hjälpare (t.ex. morfar) kan inte ändra lämning/hämtning direkt, bara svara på förfrågningar (ADR-015).
- Redigeras en händelse på en telefon i annan tidszon visas händelsens egna klockslag i redigeraren (kalendern visar omräknat).
- Delade bilder syns bara på telefonen som sparade dem (ADR-025).
- Kortet "Studiedag på fredag" säger veckodagen även när studiedagen är idag (fixas i fas 4).
- Notiser och widgets byggs med telefonens språk; har man valt ett annat språk i appen på Android 12 eller äldre kan de
  komma på systemspråket.
- Livsmedelsmatchningen är ordbaserad: ovanliga ingredienser kan behöva rättas för hand en gång (sparas sedan för familjen).
