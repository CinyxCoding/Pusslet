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
- [ ] Auth (e-post + Google Sign-In; anonym i demo)
- [ ] Skapa/gå med i familj via länk/QR
- [ ] Barnprofiler med Skolverket-lookup
- [ ] Kalender: dag/vecka/månad/agenda (UI saknas) – RRULE-motor, ICS-parser och veckonummer klara med tester
- [x] Svenska helgdagar lokalt (inkl. klämdagar, aftnar) – korskontrollerat mot Googles helgdagsflöde (11 år)
- [x] Vårdnadsschema + överlämningskort (domänlogik + tester) – 🟡 UI saknas
- [ ] Idag-vy: tidslinje, ska-med-idag, SMHI-väder, klädråd
- [x] Svensk snabbinmatningsparser (81 testfall) – 🟡 UI (förhandsgranskning) saknas
- [x] Synkmotor (11 tvåenhetstester mot fejkad backend) + säkerhetsregler + 23 regeltester mot emulatorn
- [x] Seed-data "Familjen Lind" (DemoSeed)
- [ ] ✅ Två emulatorer i samma familj ser varandras ändringar, fungerar offline

## Fas 2 – Mat & handla
- [ ] Receptbibliotek, URL-import (JSON-LD), portionsskalning/enhetskonvertering
- [ ] Veckomatsedel (kopiera förra veckan, favoritveckor, gör dubbelt)
- [ ] Livsmedelsdatabasen: lokalt FTS-index + näringsöversikt
- [ ] Skollunch-provider (skolmaten-RSS + manuell)
- [ ] Smarta förslag (regelbaserat)
- [ ] Inköpslista från matsedel med sammanslagning, basvaror, butiksläge med avdelningsordning
- [ ] Streckkodsskanning + Open Food Facts
- [ ] "Jag handlar nu"
- [ ] ✅ 5 recept → korrekt sammanslagen och avdelningssorterad lista (enhetstester)

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

## Status vid senaste avbrott (2026-10-08)
Klart: domänkärnan i `shared/core` (veckor, helgdagar, RRULE/ICS, parser, vårdnad, packregler, klädråd, konflikter, uppgiftsrotation, Idag-planerare), datalagret i `shared/data` (Room-dokumentlager, utkö, synkmotor, Firestore-adapter, repositories, adaptrar för SMHI/Skolverket/skolmaten/Livsmedelsverket/Open Food Facts/ICS, demodata, AppCoordinator, Koin), Firestore-regler.
Nästa steg: (1) koppla in Koin-moduler och Firebase-init per flavor i `composeApp`, (2) UI för Idag/Kalender/Familj/onboarding/snabbinmatning med strängresurser, (3) Fas 2–4.

## Kända begränsningar / buggar
- Emulatorn på utvecklingsmaskinen (4 kärnor, mjukvaru-GPU) är långsam och visar ibland ANR-dialoger för System UI – inte appens fel.
- iOS kan inte kompileras på Windows; verifieras i CI (macOS-runner).
