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
> Fas 2-UI:t (2026-10-13) skrevs i en molnsession utan Android SDK. Verifierat i CI på
> [PR #1](https://github.com/CinyxCoding/Pusslet/pull/1): ktlint + detekt, alla enhetstester (även nya ViewModel-tester och
> befintliga skärmdumpstester), demo-APK, release-AAB och iOS-ramverket bygger. **Inte** kört på emulator än.

- [x] Receptbibliotek (sök på namn/ingrediens, taggar, favoriter), receptvy med portionsskalning, redigerare (en rad per
  ingrediens/steg, klistra in och dela upp), URL-import (JSON-LD) med inklistring som reserv, delade länkar → import;
  veckomenyer (ItemList, recept.se) och flera länkar på en gång, menyns recept i en egen samling (ADR-038)
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
- [x] "Jag handlar nu": synlig för familjen medan butiksläget är öppet; varor som läggs till då kommer som push (ADR-042)
- [x] ✅ 5 recept → korrekt sammanslagen och avdelningssorterad lista (ShoppingListGeneratorTest)
- [x] Skärmdumpstester för Mat/Handla (Roborazzi): matsedel, tom vecka, inköpslista, tom lista, butiksläge, recept
- [x] Köra fas 2-UI:t på emulator och gå igenom flödena för hand (ägaren, Android Studio, 2026-10-13)

## UX-förenkling (docs/UX_PLAN.md)
Ägarens test av fas 2: funktionerna fungerar, men UI:t kändes rörigt med för mycket text. Plan med beslut i
`docs/UX_PLAN.md`, ADR-023–033.
- [x] Kvällskoll: från 18:00 visar Idag morgondagens väder och klädråd (ADR-022)
- [x] 1.0 Baslinjetester för Mat, Handla, butiksläge och Kalender med stor text (PNG:erna spelas in av ägaren:
  `./gradlew :composeApp:recordRoborazziDemoDebug`)
- [x] 1.1 Mindre text: skrivregler, ~80 omskrivningar, 24 oanvända nycklar borta, `StringBudgetTest`
- [x] 1.2 Rund +, ett sätt att lägga till per skärm (`RegisterAddButton`); Handla lägger till via ett fält ovanför tangentbordet
- [x] 1.3–1.6 Etiketter i bottenfältet, sage för valt, 14 sp, tre uppmärksamhetsnivåer, `InfoPill`
- [x] 1.7–1.12 Klädråd grupperade, kvitton (”2 varor på listan · Visa”), Handla-ångra, kalendern fastnar inte i Dag,
  Mat visar idag först, Mer utan undertexter
- [x] 1.13 Hjälpare ser bara knappar som fungerar (Idag, Kalender, Inställningar)
- [x] 1.14–1.15 ”Visa första start igen” i demo/dev, ”Exempelfamilj” på Idag, första barnet blir aldrig terrakotta
- [x] 2.0 Gemensamma komponenter (TabHeader, WeekSwitcher, Section, HeroCard, SentenceRow, NavRow, QuietLine,
  OutlinedValueField, WeekStripPicker, ReorderButtons, showUndo)
- [x] Idag A: block och Morgonkollen · Idag B: Anpassa Idag (från Idag och Inställningar)
- [x] Steg 2 per flik: Kalender (Vecka | Månad, tysta rutiner, filterark), Mer (två grupper, inbjudan med ett tryck),
  Mat (veckan först, recepteditor som frågar innan ändringar slängs), Handla (listmeny i rubriken, rader som en papperslista,
  lugnare butiksläge, butiker och skafferi under ”Butiker och det vi har hemma”)
- [x] Steg 3: hjälparvy – H1 data och regler, H2 enkel vy (ADR-029), H3 ”Så ser Bengt appen”, svaren i Händelsedetaljer,
  ”Bengt kan inte hämta” som Problem på Idag och Saras fråga i exempelfamiljen (ADR-030 tillägg)
- [x] Steg 4: första användningen (4.1–4.3 onboarding ✅: fler vuxna med bara namn, en barnrad med fokus, sökning först, ”Klart!” med ”Visa min dag”; 4.4 ”Kom igång” på Idag ✅ (ADR-034); 4.5 tomlägen på Mat, Recept och Handla ✅; 4.6 snabbinmatningen med familjens egna namn, en mening med ”Ändra”, osäkert i terrakotta och ”Fyll i själv” till redigeraren ✅; 4.7 tips som visas en gång ✅ för Idag, Handla och hjälparvyn; 4.8 ”Börja på riktigt” ✅)

## Fas 3 – Integrationer & notiser
- [x] ICS-prenumerationer: hämtas när appen startar och var 6:e timme (bara föräldrar), skärmen ”Prenumerationer” med ”Hämta och visa”, VTIMEZONE med sommartid, acceptanstest med Google- och Outlook-liknande flöden över sommartidsskiftet; WorkManager var 6:e timme med nät även när appen är stängd
- [x] Tvåvägs enhetskalender (CalendarContract): telefonens kalendrar läses (krockar), familjens händelser skrivs till vald kalender
  eller en egen Pusslet-kalender och följer familjen medan appen kör – raderade tas bort, flyttade tillfällen blir egna, byte av
  kalender städar den gamla (ADR-040); provat på emulator 2026-10-21
- [x] Eget ICS-flöde (Cloud Function `familyCalendarFeed`): hemlig länk som en förälder skapar och kan sluta dela i
  Inställningar, RFC 5545 med serier, flyttade tillfällen och Stockholms sommartid; 55 funktions- och regeltester gröna mot
  emulatorerna (ADR-041). Kräver deploy till ett riktigt projekt (SETUP.md 2.4)
- [x] Konfliktdetektering, hämta/lämna-förfrågningar (ConflictDetector, ”Att lösa” på Idag, förfrågningar och svar ADR-030)
- [x] FCM, lokala påminnelser, morgonnotis, tysta timmar, notiskanaler
  - [x] Delad planering av lokala notiser: påminnelser, lämna/hämta, att göra, Morgon- och Kvällskoll, tysta timmar, samlade notiser, kanaler och texter (ADR-036)
  - [x] Android: WorkManager, kanaler, visning och tryck in i appen – verifierat på emulator 2026-10-21: sju kanaler med svenska
    namn, påminnelse i tid med appen i bakgrunden, privat på låsskärmen, tryck öppnar händelsen även från kallstart. Rättat:
    ett tryck efter att Android återställt appen lade samma händelse två gånger (två Tillbaka), och en Idag-notis visade
    det som låg öppet i Idag-fliken i stället för Idag (`AppNavigator.open`)
  - [x] FCM (ADR-042): förfrågan till den som tillfrågas, svar tillbaka, vara till den som handlar; data-only med id:n,
    texten byggs i appen på läsarens språk, tysta timmar och redan besvarade förfrågningar respekteras. Triggers testade mot
    emulatorerna (59 funktionstester gröna), appens del med enhetstester och provstart på emulator 2026-10-21. Själva
    leveransen kräver ett riktigt Firebase-projekt (SETUP.md 2.4)
  - [ ] Flytta från FCM-token (`getToken`/`onNewToken`, utfasade i firebase-messaging 25) till installations-id
    (`register()`/`onRegistered`) när serversidan för att skicka till dem går att prova mot ett riktigt projekt
- [x] Två widgets (Idag, Inköpslista) med Glance: nästa händelse, lämna/hämta, ska med; listan med bockar och + över hemskärmen (ADR-039), provade på emulator 2026-10-21
- [x] Uppgifter/sysslor med rotation, fördelningsvy: Mer › Uppgifter och sysslor (Mina, Ingen ansvarig, Familjen, Klart), redigerare med upprepning, ”Turas om”, ”Vems tur” och ”Byt vecka”, checklista; ”Så har vi delat” för de senaste 4 veckorna
- [x] Export/radering av data: export med kvitto (även konto och den här telefonens inställningar), ”Radera familjen för alla” och ”Radera mitt konto” via callables med tester mot emulatorn, ärlig text för ”bara den här telefonen” (ADR-035)
- [x] Det som tas bort försvinner på riktigt efter 30 dagar (ADR-046): servern rensar varje natt till en markering som
  behåller bara borttagningen (raderas efter ett år), telefonen glömmer sina gamla gravstenar vid start. En telefon som
  varit borta länge får ändå veta att saken är borttagen, gamla ändringar väcker den inte, och samma id kan användas igen.
  Funktionstester, regeltest och fyra synkscenarier gröna
- [x] ✅ Publik test-ICS visas korrekt (återkommande + tidszoner, acceptanstestet är grönt); familjehändelser syns i enhetens kalender (ADR-040)

### Tillägg efter korskontroll (2026-10-21)
- [x] Kvällskollen: från 18:00 visar Idag ”Imorgon, onsdag” med vem som lämnar och hämtar, ”Packa ikväll: …” med bockar,
  morgondagens väder och klädråd och middag. Följer ”Anpassa Idag”; problem för imorgon står kvar under ”Att lösa”
- [x] Påminnelser för händelser och uppgifter (ReminderPlanner, ADR-036; händelsepåminnelse provad på emulator 2026-10-21)
- [x] Matsedeln: ”Laga mat | Hämtmat | Ingen matlagning” och ”Vem lagar” (syns på Matsedel och Idag); hämtmat och ingen matlagning ger inga inköp och räknas inte som lagad mat i förslagen
- [x] Skafferiet: plats (skafferi, kyl, frys), bäst före med ”går ut snart”, ”Slut” utan att radera, streckkod; okänd mängd frågar ”Finns hemma?” i stället för att hoppas över
- [x] Inköpslista från valda dagar (nästa 14 dagar), varje ingrediens kan väljas bort, två körningar dubblar aldrig
- [x] Receptsamlingar: egna samlingar som synkas och slås ihop per recept, filter i receptbiblioteket, ”Lägg i samling…”
- [x] Veckans balans runt skollunchen: fisk/vegetariskt/kött per barn, förslag som fyller luckorna, "Föreslå veckans middagar" och balansen i Näring för veckan (ADR-037)
- [x] Importera flera receptlänkar på en gång: alla länkar i inklistrad text hämtas en i taget, alla recept på en sida (t.ex. en veckomeny med receptdata) listas, bocka för och spara; en veckomeny som bara länkar till sina recept följs ett steg och dess recept samlas i en samling (ADR-038)

## Fas 4 – Polish & Play-beredskap
- [x] Tillgänglighetsgenomgång, skärmdumpstester (ljust/mörkt/stor text)
  - [x] Skärmdumpstester (Roborazzi) för ljust, mörkt, engelska och stor text (1,5× och 2×) på Idag, Kalender och
    hjälparvyn, plus Mat, Handla, butiksläget, tomma lägen och onboarding
  - [x] Automatiska tillgänglighetskontroller (ADR-044): varje skärmdumpstest (37 skärmar, dialoger och ark) kontrollerar
    att allt tryckbart har ett namn och är minst 48 dp, och temats färger har WCAG-kontrast (4,5:1 text, 3:1 kontroller).
    Rättat: dragtaget på alla 11 ark var 32 dp brett, färgväljaren hade 40 dp-mål och lästes som "Färg 3 ✓" (nu
    "Salvia", "Terrakotta" … med valt-läge), konturfärgen i mörkt läge nådde 2,94:1 på surfaceVariant
  - [ ] En runda med TalkBack och Accessibility Scanner på en riktig telefon (lanseringschecklistan, SETUP.md 3.3)
- [x] Prestanda (kallstart, 500+ varor), mätt 2026-10-22 på emulator med R8-releasen (ADR-045):
  - Kallstart 0,6–0,7 s (första gången efter installation 1,2 s)
  - Inköpslista med 648 varor: rullar inte sämre än Idag (emulatorn ritar i mjukvara, så rendering dominerar).
    Att bocka av i butiksläget frös skärmen ~0,5 s per bock eftersom hela listan avkodades och byggdes på
    huvudtråden; nu byggs den i bakgrunden: långsammaste 1 % av bildrutorna 500 → 85 ms, långsamma bildrutor på
    huvudtråden 195 → 93 för 15 bockar
  - `ShoppingListPerformanceTest` håller 600 varor under koll (öppna, bocka, lägga till många på en gång)
- [x] Felhantering och tomma lägen överallt, onboarding < 2 min (genomgång 2026-10-22):
  - Tomma lägen på alla listskärmar (Idag, uppgifter, skafferi, inköpslista, recept, matsedel, prenumerationer,
    fördelning, hjälparens vecka); dagvyn är ett timrutnät där en tom timme går att trycka på för att lägga till
  - Nätfel ger en begriplig rad överallt där appen hämtar något: väder, skolsök, inbjudan, streckkod, receptimport,
    prenumerationer, näringsvärden, kalenderlänk, radering; synken visar "Offline – försöker igen automatiskt"
  - Inga `!!` i produktionskoden och inga tysta `catch`. Nytt: bakgrundsjobb som inte fångar ett oväntat fel själva
    loggas i stället för att krascha appen (`backgroundScope`, testat)
  - Onboarding: "Skapa vår familj", ditt namn, barnens förnamn (kan hoppas över), ort (kan hoppas över), klart – sex
    tryck och två textfält, väl under två minuter; kontot skapas först när man vill dela
- [x] Svenska + engelska texter genomlästa (2026-10-22, alla 1 161 strängar sida vid sida plus kontroller av saknade
  översättningar, platshållare, citattecken och stavning): samma nycklar och platshållare på båda språken. Rättat:
  engelskan blandade "could not"/"couldn't" (nu genomgående kortformer, som tonen i DESIGN.md), "Customize" bland brittisk
  stavning, "w. 43"/"wk", "To-do"/"To do", "present?" (gift), en krystad rad om veckomenyer; svenskan hade både "I morgon"
  och "Imorgon" och "…" med och utan mellanslag. Utvecklarknappen som tar bort familjen hette "Visa första start igen" –
  nu "Radera och börja om" och den frågar först
- [x] Entitlements/Billing-förberedelse, `docs/MONETIZATION.md`: allt upplåst i alla byggen, gratisgräns för
  prenumerationer testad, Play Billing förberett bakom `BillingRepository` utan bibliotek i bygget (ADR-043)
- [x] `docs/PRIVACY.md`, `docs/PLAY_DATA_SAFETY.md`, `docs/STORE_LISTING.md` (svenska och engelska, utkast för ägaren att
  fylla i och publicera). Rättat på vägen: telefonens position sparas avrundad till ~1 km, och switchen för kraschrapporter
  är dold tills kraschrapportering finns i bygget
- [x] Releasebygge (R8), signeringsinstruktioner, lanseringschecklista (SETUP.md 3): `bundleProdRelease` bygger, signering
  med uppladdningsnyckel från `keystore.properties` eller miljövariabler; R8-releasen provad på emulator 2026-10-21
  (exempelfamiljen, alla flikar, snabbinmatning, Handla för veckan) utan fel
- [x] ✅ `./gradlew build` grönt (alla varianter, tester och lint), release-AAB bygger och 59 funktionstester gröna
  (2026-10-22). Kvar för ägaren: TalkBack-rundan och allt i lanseringschecklistan (SETUP.md 3.3)

## Efter fas 4 – det föräldrar saknar
- [x] Sjuk dag och VAB (ADR-047): ”Sjuk idag?” på Idag ställer in barnets lämning, hämtning och aktiviteter överallt
  (Idag, notiser, widget, kalender, hjälparvyn), säger till de andra med push, ”Vem stannar hemma?” med VAB och del av dag,
  bockar för frånvaroanmälan, jobbet och Försäkringskassan, ”Fortfarande sjuk?” nästa morgon, ”Anmäl VAB” när barnet är
  friskt och VAB per förälder i år. Påminnelser för frånvaroanmälan och VAB. Testat i kärnan, Idag, kalendern, push och
  regler, med skärmdumpstest och tillgänglighetskontroll av arket och kortet
  - [x] Sjukanmälan via snabbinmatningen (”Ella sjuk”, ”Ella och Simon sjuka imorgon”), med Ångra och bara för föräldrar
- [x] Dela en bild till appen (ADR-049): sparas på telefonen med en händelse eller som anteckning, aldrig i molnet
- [x] Medicin och feber (ADR-050): delad logg för föräldrarna, temperaturer, kurer med påminnelser och ”Medicin idag”
- [x] Kvitto på hämtning (ADR-048): ”Hämtad” på min hämtning på Idag, i hjälparvyn och i notisen, push till de andra
  (”Ella är hämtad · Bengt · 15:52”), varning en kvart efter tiden till den som skulle hämta och föräldrarna, som notis och
  på Idag där en förälder kan bocka av åt någon annan, och en inställning för att stänga av varningen
- [x] Lov och vistelsetider (ADR-051): vem som är hemma varje lovdag, lovomsorg med påminnelse om anmälan, vistelsetider
  från kalendern som går att skicka till förskolan
- [x] Delade kostnader mellan hemmen (ADR-057): gemensam lista i det delade utrymmet, fördelning per kostnad, saldo och
  ”Reglera” när det andra hemmet har swishat

## Fas 5 – V2
- [x] Sök (ADR-052): händelser, recept, att göra, varor och kontakter, med å, ä och ö som egna bokstäver
- [x] Fler lunchleverantörer (ADR-053): Matilda via matsedelns officiella RSS-flöde (kontrollerat live 2026-10-28)
- [x] Kalas och presenter (ADR-054): OSA med sista dag, presentidé, budget och köpt, eget kalas med gäster och inbjudan,
  önskelistor där morföräldrar bockar av vad de köpt
- ~~Barnläge och veckopeng (ADR-055)~~ – borttaget av ägaren 2026-10-29 (ADR-060); sysslor kan fortfarande tilldelas barn
- [x] Flerhushållsdelning (ADR-056): ”Andra hemmet” – barnen man delar, deras vårdnadsschema och det som bara handlar om
  dem bor i ett delat utrymme som båda hemmens föräldrar synkar; starta med kod, gå med och para ihop barnen, sluta dela
  med egen kopia; ”Delas med andra hemmet” i händelseredigeraren; regler och kalenderflöde

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

Nästa steg: kör fas 2 på emulator (`installDemoDebug`), spela in skärmdumpar för Mat och Handla. Sedan fas 3.

## Verifiering fas 5: andra hemmet (2026-10-09)
Demo-APK på emulatorn (Pixel 9 Pro, API 37, utan fönster) mot Firebase-emulatorerna (auth + firestore), med
`functions/scripts/other-home.mjs` som "Lina" i ett annat hem (går genom säkerhetsreglerna som en telefon):
- Sara delar Ella → koden visas; Lina går med med koden → appen visar "Delas med Lina". Lina ser Ella, hennes
  händelser och packningsregler i utrymmet men kan inte läsa familjen Linds egna händelser.
- Linas "Simning med mamma" syns på Saras Idag och i detaljerna ("Delas med andra hemmet", Lina hämtar). En ny
  tandläkartid för Ella via snabbinmatningen hamnar i utrymmet av sig själv och syns för Lina.
- "Sluta dela": Lina har kvar utrymmet (bara hennes hem och hon själv), Sara har kvar en egen kopia.
- Buggar som hittades och rättades: (1) "Sluta dela" försökte dölja hemmets vuxna som aldrig gått med i utrymmet –
  reglerna nekade hela batchen; nu bara de som finns där. (2) Idag visade "Ingen har tagit hämtningen" när andra hemmet
  hämtar – Idag, kalendern, påminnelser och förfrågningar känner nu igen andra hemmets vuxna. (3) Texterna pekade på
  "Mer → Familj" men menyn heter "Familjen".

## Säkerhetsgranskning (2026-10-29)
Hela appen och backend granskades mot OWASP MASVS/MASTG och API Security Top 10. Rapporten finns i docs/SECURITY.md och
beslutet i ADR-058.

16 fynd är rättade och har tester där det går. De tre allvarligaste:
- En borttagen skapare kunde ge sig själv åtkomst igen.
- En inbjudan kunde användas för att bli en befintlig medlem.
- En kalenderlänk fungerade kvar efter att skaparen tagits bort.

Övriga rättningar:
- ICS-injektion i kalenderflödet.
- Ett delat utrymme kunde raderas för båda hemmen.
- Bilddelning, länköppning, "Hämtad" på en låst telefon och storlek och tolkning av svar från nätet.
- Sårbara npm- och Guava-versioner.

Status är **CONDITIONAL**: reglerna och funktionerna måste driftsättas, och App Check och konsolinställningarna återstår
(SECURITY.md, E–F). Det görs när Firebase-projektet skapas inför lanseringen.

Ägarens val efter granskningen (ADR-059), byggda samma dag:
- Andra hemmet hanterar bara sina egna vuxna och de kostnader det lade in, och varje kostnad visar "inlagd av".
- En inbjudningskod används en gång.
- Utloggning frågar om familjen ska ligga kvar på telefonen.
- CI körs också på `claude/`-grenar, som slås ihop direkt i `main`.

Barnläget, med stjärnor, godkännanden och veckopeng, togs bort helt samma dag (ADR-060).

Premiumvillkoren står i MONETIZATION.md: 39 kr i månaden eller 349 kr om året, 14 dagars provperiod, och tidiga
användare får premium i ett år. Samma dag blev maten, utom skollunchen, och telefonens kalender premium.

Medicindelen länkar till bipacksedeln på FASS och till 1177:s sida om feber hos barn, och har en knapp för att
ringa 1177 (ADR-061). Appen hämtar inget därifrån och räknar aldrig ut doser.

## Kända begränsningar / buggar
- CI hängde en timme 2026-10-14: Idags ViewModel startade en evig `while (true) { delay(1 h) }` i `init`, som fick
  Robolectric-testerna att aldrig bli klara. Vädret laddas nu inuti state-flödet (stannar med skärmen). Regel: inga eviga
  loopar i ViewModel-init; periodiskt arbete hör hemma i ett flöde under `stateIn(WhileSubscribed)`.
- Emulatorn på utvecklingsmaskinen (4 kärnor, mjukvaru-GPU) är långsam och visar ibland ANR-dialoger för System UI – inte appens fel.
- iOS kan inte kompileras på Windows; verifieras i CI (macOS-runner).
- Hjälpare (t.ex. morfar) kan inte ändra lämning/hämtning direkt, bara svara på förfrågningar (ADR-015).
- Redigeras en händelse på en telefon i annan tidszon visas händelsens egna klockslag i redigeraren (kalendern visar omräknat).
- CI hade aldrig gått igenom: `gradlew` saknade körbarhetsbiten i git. Rättat 2026-10-13; sedan dess grönt (Android, iOS,
  Firestore-regler).
- Molnsessionerna kan inte bygga Android (Googles Maven är blockerad), men en egen JVM-kompilering av alla delade moduler
  (commonMain + commonTest, med stubbar för Room/lifecycle) körs lokalt där och fångar de flesta kompileringsfel före CI.
- Molnsessioner (claude.ai/code) behöver `dl.google.com` tillåtet i miljöns nätverksinställningar för att kunna bygga Android
  (Googles Maven och SDK). Maven Central svarar dessutom ofta 429 där; se docs/SETUP.md.
