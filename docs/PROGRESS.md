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
- [x] Skalet renderar (Robolectric + Roborazzi-skärmdumpar ljust/mörkt/engelska). Start på riktig emulator: se Kända begränsningar
- [ ] iOS-target kompilerar (verifieras i macOS-jobbet i CI; går inte att bygga på Windows)

## Fas 1 – Familj, kalender, Idag
- [ ] Auth (e-post + Google Sign-In; anonym i demo)
- [ ] Skapa/gå med i familj via länk/QR
- [ ] Barnprofiler med Skolverket-lookup
- [ ] Kalender: dag/vecka/månad/agenda, RRULE, veckonummer
- [ ] Svenska helgdagar lokalt (inkl. klämdagar, aftnar)
- [ ] Vårdnadsschema + överlämningskort
- [ ] Idag-vy: tidslinje, ska-med-idag, SMHI-väder, klädråd
- [ ] Snabbinmatning med svensk parser (≥ 60 testfall)
- [ ] Synkmotor + säkerhetsregler + regeltester
- [ ] Seed-data "Familjen Lind"
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

## Kända begränsningar / buggar
- Ingen Android-systemavbild finns installerad på utvecklingsmaskinen, så appen har ännu inte startats på emulator.
- iOS kan inte kompileras på Windows; verifieras i CI (macOS-runner).
