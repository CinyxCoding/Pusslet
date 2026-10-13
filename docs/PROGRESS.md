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
> ⚠️ Fas 2-UI:t (2026-10-13) skrevs i en molnsession där Googles Maven/Android SDK var blockerat: `shared/core` är
> kompilerad och testad (246 tester gröna på JVM), men feature-modulerna, `shared/data`-ändringarna och appen är **inte
> kompilerade än**. Första steget i nästa session med Android-miljö: `./gradlew build` och rätta det som dyker upp.

- [x] Receptbibliotek (sök på namn/ingrediens, taggar, favoriter), receptvy med portionsskalning, redigerare (en rad per
  ingrediens/steg, klistra in och dela upp), URL-import (JSON-LD) med inklistring som reserv, delade länkar → import
- [x] Veckomatsedel: veckonummer, röda dagar, planera med förslag/recept/fritext, "gör dubbelt" (rester nästa lediga dag),
  flytta, kopiera förra veckan, favoritveckor, frukost/lunch om familjen valt det
- [x] Livsmedelsdatabasen: näringsöversikt per portion (neutral, med källa och hur komplett den är) och ark för att bekräfta
  ingrediensmatchningar (sparas för familjen), samt snitt per portion för veckans planerade middagar
- [x] Skollunch: skolmaten-RSS (fas 1) + manuell inmatning per barn och dag i matsedeln; krockmarkering mot middagen
- [x] Smarta förslag (regelbaserat) med synliga skäl: snabbt en upptagen kväll (kalendern 16:30–19:00), inte samma som
  skollunchen, länge sedan, varierar veckan, använder det ni har hemma
- [x] Inköpslista: flera listor, snabbtillägg med sammanslagning, avdelningar i butikens gångordning, avbockade i "I vagnen",
  Ångra, redigera vara (mängd, enhet, avdelning som familjen lär sig, anteckning, eget pris), dela som text, basvaror från
  köphistorik, butiker med egen gångordning, Hemma-lager, "Från matsedeln" med förhandsvisning (denna/nästa vecka)
- [x] Butiksläge: stor text, avdelningsordning, avbockade sjunker, skärmen hålls vaken, Ångra, "Allt i vagnen!"
- [x] Streckkodsskanning + Open Food Facts (namn + märke, avdelning från kategori, okänd kod → fråga efter namn, samma kod igen
  räknar upp)
- [x] "Jag handlar nu": synlig för familjen medan butiksläget är öppet (push kommer med FCM i fas 3)
- [x] ✅ 5 recept → korrekt sammanslagen och avdelningssorterad lista (ShoppingListGeneratorTest)
- [ ] Skärmdumpstester för Mat/Handla (Roborazzi) – kräver Android-miljö
- [ ] Kompilera och köra fas 2-UI:t på emulator (se varningen ovan)

## Fas 3 – Integrationer & notiser
- [ ] ICS-prenumerationer
- [ ] Tvåvägs enhetskalender (CalendarContract)
- [ ] Eget ICS-flöde (Cloud Function)
- [ ] Konfliktdetektering, hämta/lämna-förfrågningar
- [ ] FCM, lokala påminnelser, morgonnotis, tysta timmar, notiskanaler
- [ ] Två widgets (Idag, Inköpslista)
- [ ] Uppgifter/sysslor med rotation, fördelningsvy
- [ ] Export/radering av data
- [ ] ✅ Publik test-ICS visas korrekt (återkommande + tidszoner); familjehändelser syns i enhetens kalender

## Fas 4 – Polish & Play-beredskap
- [ ] Tillgänglighetsgenomgång, skärmdumpstester (ljust/mörkt/stor text)
- [ ] Prestanda (kallstart, 500+ varor)
- [ ] Felhantering och tomma lägen överallt, onboarding < 2 min
- [ ] Svenska + engelska texter genomlästa
- [ ] Entitlements/Billing-förberedelse, `docs/MONETIZATION.md`
- [ ] `docs/PRIVACY.md`, `docs/PLAY_DATA_SAFETY.md`, `docs/STORE_LISTING.md`
- [ ] Releasebygge (R8), signeringsinstruktioner, lanseringschecklista
- [ ] ✅ `./gradlew build` grönt, release-AAB bygger

## Fas 5 – V2
- [ ] Barnvy & veckopeng · kalas/presenter · sök · flerhushållsdelning · fler lunchleverantörer

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

Nästa steg: bygg och verifiera fas 2 på emulator (`./gradlew build`, `installDemoDebug`), spela in skärmdumpar för Mat och
Handla. Sedan fas 3.

## Kända begränsningar / buggar
- Emulatorn på utvecklingsmaskinen (4 kärnor, mjukvaru-GPU) är långsam och visar ibland ANR-dialoger för System UI – inte appens fel.
- iOS kan inte kompileras på Windows; verifieras i CI (macOS-runner).
- Hjälpare (t.ex. morfar) kan inte ändra lämning/hämtning direkt, bara svara på förfrågningar (ADR-015).
- Redigeras en händelse på en telefon i annan tidszon visas händelsens egna klockslag i redigeraren (kalendern visar omräknat).
- Delade bilder (SEND image/*) tas inte emot än (receptlänkar gör det sedan fas 2).
- CI hade aldrig gått igenom: `gradlew` saknade körbarhetsbiten i git (rättat 2026-10-13). Lint körs nu på riktigt, så det kan
  dyka upp gamla ktlint/detekt-anmärkningar första gången.
- Molnsessioner (claude.ai/code) behöver `dl.google.com` tillåtet i miljöns nätverksinställningar för att kunna bygga Android
  (Googles Maven och SDK). Maven Central svarar dessutom ofta 429 där; se docs/SETUP.md.
