# PROJEKTBRIEF: "Pusslet" – familjens vardagspussel i en app

> Arbetsnamn: **Pusslet** (gör namnet konfigurerbart via en konstant + string-resurs så det kan bytas utan sökningar i koden).
> Den här briefen är skriven för att du (Claude Code) ska kunna arbeta **helt självständigt** från tom mapp till en körbar, testad och dokumenterad app. Läs hela dokumentet innan du skriver kod.

---

## 0. Din roll och arbetssätt

Du är på samma gång senior Kotlin Multiplatform-utvecklare, produktdesigner, UX-skribent och en trött småbarnsförälder som vill att saker *bara ska funka* kl 06:50 en tisdag. Fatta beslut som den kombinationen skulle fatta.

**Självständighet – regler:**
1. Fråga inte om lov för beslut som går att ändra senare. Ta det rimligaste beslutet, skriv ner det i `docs/DECISIONS.md` (kort ADR: kontext → beslut → konsekvens) och fortsätt.
2. Stanna och fråga **endast** om du behöver något som bara ägaren kan ge (riktiga nycklar, kontoinloggning, betalning). Bygg då vidare mot stubbar/emulatorer så arbetet inte blockeras, och lista det manuella steget i `docs/SETUP.md`.
3. Skapa direkt `CLAUDE.md` i repo-roten med projektets konventioner, kommandon (build/test/lint/emulator) och en länk till denna brief (lägg briefen som `docs/BRIEF.md`).
4. För `docs/PROGRESS.md`: per fas vad som är klart, vad som återstår, kända buggar. Uppdatera efter varje milstolpe.
5. Arbeta i små steg: bygg → kör tester → committa (Conventional Commits, t.ex. `feat(shopping): merge quantities across recipes`). Pusha aldrig till någon remote utan att ägaren bett om det.
6. Lita inte på versionsnummer i den här briefen eller i ditt minne. **Verifiera aktuella stabila versioner** (Kotlin, Compose Multiplatform, AGP, Room, Ktor, Koin, Firebase-SDK:er, GitLive firebase-kotlin-sdk osv.) i officiell dokumentation när du sätter upp `libs.versions.toml`. Dagens datum är oktober 2026.
7. Varje fas har acceptanskriterier. En fas är klar först när kriterierna är uppfyllda, `./gradlew build` och alla tester är gröna och PROGRESS.md är uppdaterad.

---

## 1. Problemet vi löser

Svenska barnfamiljer jonglerar: lämning/hämtning, skolans veckobrev, gympapåse på onsdagar, studiedagar, fotbollsträningar som flyttas, kalas med presentköp, veckohandling, "vad blir det till middag?" (helst inte samma som skollunchen), VAB, delad vårdnad varannan vecka, och den osynliga *mentala lasten* av att komma ihåg allt. Informationen ligger utspridd i Vklass/Unikum/SchoolSoft, laget.se, sms-trådar, mejl, lappar på kylen och i en förälders huvud.

**Pusslet ska vara den enda plats familjen behöver titta på**, och ska aktivt minska antalet saker någon måste komma ihåg.

### Personas (designa för dessa)
- **Sara, 38, två barn (4 och 9), heltid.** Vill på 10 sekunder veta: vem lämnar/hämtar, vad ska med idag, vad blir det till middag. Använder Google-kalender på jobbet.
- **Ahmed, 41, delad vårdnad varannan vecka.** Behöver tydlig översikt över "min vecka/din vecka", smidig överlämning av info (packning, läxor, saker som ska med) till ex-partnern utan att dela allt annat.
- **Lina & Johan, föräldrar till tre.** Kämpar med rättvis fördelning av hushållssysslor och vill att barnen tar mer ansvar (sysslor, veckopeng).
- **Morfar Bengt, 70.** Hämtar på torsdagar. Ska kunna bjudas in med begränsad behörighet och förstå appen direkt.

### Designprinciper
1. **Lugn i kaoset.** Varm, trygg, vuxen – inte barnslig. Mycket luft, tydlig typografi, inget visuellt brus.
2. **Idag först.** Startskärmen svarar på dagens frågor innan användaren hinner ställa dem.
3. **En hand, en tumme.** Stora träffytor, viktiga handlingar nåbara nedtill.
4. **Fånga snabbt, sortera sen.** Allt ska kunna läggas in på < 5 sekunder (snabbinmatning med naturligt språk, dela-till-appen).
5. **Offline först.** Allt fungerar utan nät; synkar när nätet kommer tillbaka.
6. **Svensk vardag på riktigt.** Veckan börjar på måndag, **veckonummer syns överallt** där det är relevant, 24-timmarsklocka, svenska helgdagar, skollov, "klämdagar", svenska enheter (dl, msk, tsk, krm, st, förp).
7. **Integritet som funktion.** Barns data hanteras minimalt, transparent och inom EU.

---

## 2. Teknisk stack (beslutad av ägaren – följ den)

- **Kotlin Multiplatform** med **Compose Multiplatform** för UI. Android är målplattformen nu; iOS-target ska finnas i projektstrukturen och kompilera, men iOS-specifika `actual`-implementationer får vara stubbar (`TODO("iOS")` bakom ett tydligt interface) tills vidare.
- **Moduler** (förslag – justera om du hittar bättre och dokumentera i DECISIONS.md):
  - `composeApp` – app-skal, navigation, tema
  - `shared/core` – domänmodeller, use cases, tid/datum-logik, parsers (ren Kotlin, väl testad)
  - `shared/data` – repositories, lokal databas, synk, nätverksklienter
  - `shared/feature-*` – today, calendar, meals, shopping, tasks, family, settings (UI + ViewModel per feature)
  - `androidApp`-specifikt: widgets (Glance), WorkManager, kalendersynk (CalendarContract), streckkodsskanning (ML Kit/CameraX), FCM
  - `functions/` – Firebase Cloud Functions i **TypeScript** (Node)
- **Lokal databas:** Room (KMP) – single source of truth i appen. (Om Room KMP visar sig problematiskt: SQLDelight, dokumentera.)
- **Nätverk:** Ktor client + kotlinx.serialization. **DI:** Koin. **Datum:** kotlinx-datetime med `Europe/Stockholm` som standardzon men tidszonsmedvetet. **Navigation:** typsäker Compose-navigation (verifiera aktuell KMP-lösning).
- **Backend:** Firebase i **EU-region** (Firestore i EU-plats, t.ex. `eur3` – verifiera), Firebase Auth, Cloud Messaging, Cloud Functions, App Check, Crashlytics (opt-in). Använd GitLive `firebase-kotlin-sdk` i shared-koden (verifiera att den täcker det du behöver; annars expect/actual mot native Android-SDK).
- **Android-krav:** `targetSdk = 36` och `compileSdk` minst 36 (Google Play kräver API 36 för nya appar och uppdateringar sedan 31 aug 2026). `minSdk = 26`. Edge-to-edge är obligatoriskt i Android 16 – designa för det. Stöd **predictive back** (ingen `onBackPressed`). Använd Photo Picker (inga breda lagringsbehörigheter). Begär `POST_NOTIFICATIONS`, kalender- och kamerabehörighet **i sammanhang**, aldrig vid start.
- **Lokalt utan nycklar:** Hela appen ska gå att bygga, köra och testa **utan** riktiga Firebase-nycklar via **Firebase Local Emulator Suite** och en debug-flavor. Skapa `flavors`: `demo` (emulator + seed-data), `dev`, `prod`.
- **Kvalitet:** ktlint + detekt, GitHub Actions-workflow (build, unit-tester, lint, Firestore-regeltester mot emulator).

---

## 3. Externa datakällor (researchat – verifiera mot dokumentationen innan implementation)

Bygg varje källa bakom ett **interface + adapter**, med cache i Room, tidsstämplad "senast hämtad", fel-tolerans (appen ska aldrig krascha eller bli tom för att en källa är nere) och tydlig **källattribuering** i en "Om data"-vy.

| Källa | Användning | Detaljer |
|---|---|---|
| **Livsmedelsverkets Livsmedelsdatabas** | Näringsvärden för recept/matsedel, allergen- och klassificeringsinfo | Bas-URL `https://dataportal.livsmedelsverket.se/livsmedel/api/v1`. Endpoints: `/livsmedel` (paginerad lista), `/livsmedel/{nummer}`, `/{nummer}/naringsvarden`, `/{nummer}/klassificeringar`, `/{nummer}/ravaror`, `/{nummer}/ingredienser`. Swagger: `https://dataportal.livsmedelsverket.se/livsmedel/swagger/index.html`. JSON, ingen nyckel, licens **CC BY 4.0** (ange Livsmedelsverket som källa). **API:et kan inte söka på namn** → hämta hela listan (~2 500 livsmedel) vid första behov, lagra lokalt med **FTS-index** för svensk fritextsökning, och uppdatera bara när versionsfältet ändrats. Näringsvärden per livsmedel hämtas lat och cachas. |
| **SMHI Open Data – prognos** | "Vad ska barnen ha på sig?", regnvarning i morgonvyn | **Gamla `pmp3g` är avvecklad sedan 31 mars 2026 (ger 404).** Använd **SNOW1g v1**: `https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/{lon}/lat/{lat}/data.json` (stöder `?parameters=` och `?timeseries=`). Läs aktuell parameterlista i SMHI:s dokumentation – datastrukturen skiljer sig från pmp3g. Tider i UTC. Cacha 1 h. Grovposition räcker (ACCESS_COARSE_LOCATION, eller manuellt valt ort). |
| **Skolverkets Skolenhetsregistret v2** | Välja barnets skola/förskola vid onboarding (namn, adress, kommun) | `https://api.skolverket.se/skolenhetsregistret/v2/school-units` med filter på bl.a. kommunkod, skolform och status; `/v2/school-units/{schoolUnitCode}` för detaljer. Öppet API. Spara skolenhetskod på barnprofilen. |
| **Skollunch (skolmaten.se m.fl.)** | Visa dagens skollunch per barn; matsedelsförslag som undviker samma rätt som till lunch | **Inofficiellt.** skolmaten.se exponerar RSS per skola (`https://skolmaten.se/{skol-slug}/rss/weeks`) och har nyligen ändrat URL-struktur – bygg robust och testa mot riktig feed. Andra leverantörer som kommuner använder: Matilda (`menu.matildaplatform.com`), Mashie (`mpi.mashie.com`), FoodIT (`webmenu.foodit.se`). Se även specen *Open Meal Information* (Örebro kommun, `orebrokommun.github.io/Open-Meal-Information`). Implementera ett `LunchMenuProvider`-interface, börja med skolmaten-RSS, låt användaren klistra in sin skolas matsedels-URL, och ha **manuell inmatning** som fallback. Hämta max en gång per dygn per skola. |
| **Svenska helgdagar** | Röda dagar, klämdagar, aftnar | **Beräkna lokalt** i `shared/core` (påsk via algoritm, midsommar, alla helgons dag osv.) – fungerar offline och kan enhetstestas. Valfritt: `sholiday.faboul.se/dagar/v2.1/{år}` för flaggdagar/namnsdagar (kolla HTTPS; Android blockerar cleartext). **Bunta inte** namnsdagslistan i appen (upphovsrättsligt skyddad lista) – hämta den i så fall vid körning. |
| **Open Food Facts** | Skanna streckkod på varor → lägg till i inköpslista/basvarulager | `https://world.openfoodfacts.org/api/v2/product/{barcode}.json?fields=...`. Ingen nyckel, men **skicka en beskrivande User-Agent** (appnamn/version/kontakt). Licens **ODbL** – attribuera. Data är crowdsourcad: hantera saknade fält. Använd bara produkt-lookup (sök-API:t är opålitligt under last). |
| **ICS/iCal-prenumerationer** | Skolplattformar, idrottsföreningar (t.ex. laget.se, IdrottOnline), förskolor, TimeEdit m.fl. erbjuder ofta "prenumerera på kalender"-länkar | Användaren klistrar in en ICS-URL och kopplar den till ett barn/en färg. Hämta periodiskt (WorkManager, var 6:e h + manuellt), parsa RFC 5545 inkl. RRULE/EXDATE/tidszoner (använd ett välunderhållet bibliotek på Android – t.ex. ical4j – bakom ett interface, eller skriv en parser med gedigna tester). Read-only. |
| **Enhetens kalendrar** | Tvåvägs: visa föräldrarnas egna kalendrar (konflikter!) och exportera familjehändelser | Android `CalendarContract` (Google, Outlook/Exchange, Samsung-konton som finns på enheten). Användaren väljer vilka kalendrar som ska läsas och vilken (eller en appägd lokal kalender) familjehändelser ska skrivas till. Hantera dubbletter med stabila ID:n i `SYNC_DATA`-fält eller extended properties. |
| **Familjens ICS-flöde (egen)** | "Prenumerera på familjekalendern i din jobb-Outlook/Google" | Cloud Function som serverar en hemlig, återkallbar token-URL med familjens (eller en medlems) händelser i ICS-format. Ingen inloggning krävs i jobbkalendern. |
| **Inköps-/prisdata** | – | Det finns **inget öppet API** för svenska matpriser/erbjudanden (ICA, Coop, Willys, Matpriskollen m.fl.). **Skrapa inte.** Designa ett `GroceryProvider`-interface för framtida integrationer; i v1: dela listan som text, sortera efter butikens avdelningsordning, och spara egna priser om användaren vill. |

---

## 4. Funktioner

Märkning: **[MVP]** = måste finnas i första fullständiga versionen. **[V2]** = bygg om tid finns efter att MVP är klar och polerad. **[PREMIUM]** = byggs, men bakom en `FeatureFlags`/`Entitlements`-abstraktion som i nuläget alltid returnerar "upplåst" i dev/demo (se §7).

### 4.1 Idag ("Morgonkollen") [MVP] – startskärmen
- Datum + **veckonummer**, hälsning, väder nu och för dagen (SMHI) med klädråd per barn baserat på temperatur/nederbörd/vind och barnets ålder ("Regnställ + stövlar", "Mössa och vantar", "Solhatt + vatten"). Reglerna ska vara datadrivna (enkel regeltabell) och testade.
- **Tidslinje** för dagen: vem lämnar, vem hämtar och när, aktiviteter, möten – färgkodat per familjemedlem.
- **"Ska med idag"** per barn: genererat från återkommande packregler (t.ex. "Gympapåse – onsdagar", "Matsäck – när händelse taggad *utflykt*", "Bibliotekbok – varannan fredag") + engångsposter. Avbockningsbar.
- Dagens **skollunch** per barn och **kvällens middag** från matsedeln (med varning om de krockar).
- Kommande 3 dagar i kompakt form, öppna uppgifter tilldelade mig, påminnelser.
- Kontextuella kort: "Studiedag på fredag – vem är hemma?", "Kalas på lördag – present är inte köpt", "Klämdag nästa vecka".
- Valfri **morgonnotis** (standard 06:45, justerbar) med dagens sammanfattning.

### 4.2 Familj & roller [MVP]
- Skapa familj, bjud in via **länk + QR-kod** (Android App Links + fallbackkod). Roller: **Förälder** (allt), **Vårdnadshavare/annan vuxen** (t.ex. morfar: ser och bockar av det som delas med hen), **Barn** [V2] (egen förenklad vy: mina sysslor, mina stjärnor, mitt schema).
- Barnprofiler utan eget konto: namn, färg, avatar (emoji/initialer, inga foton krävs), födelsedatum, skola/förskola (Skolverket-lookup), klass, lunchkälla, klädstorlekar (byxor/tröja/skor – med historik), viktiga kontakter (lärare, fritids, andra föräldrar), anteckningar.
- **Kost & allergier** per medlem är **opt-in**, lagras med tydlig förklaring av varför, och används bara för att filtrera recept/varna. Behandla som känsliga uppgifter (se §6).
- **Delad vårdnad / växelvis boende** [MVP]: vårdnadsschema (varannan vecka, 2-2-5-5, egna mönster, undantag för lov/högtider). Visas som bakgrundsband i kalendern och "Barnen är hos: Ahmed" i Idag-vyn. **Överlämningskort**: inför byte genereras en checklista (saker som ska med, läxor, kommande händelser) som kan delas med andra hushållet via länk/text *utan* att de får tillgång till resten av familjens data. Stöd för att ett barn tillhör två "hushåll" i datamodellen (förbered; full flerhushållsdelning kan vara [V2]).

### 4.3 Familjekalender [MVP]
- Vyer: **Dag, Vecka (standard, med veckonummer), Månad, Agenda**. Filter per medlem.
- Händelser: titel, tid/heldag, plats, återkommande (RRULE), deltagare (barn), **ansvarig för lämning/hämtning**, packregler, påminnelser, anteckning.
- Lager som kan slås av/på: familjehändelser, ICS-prenumerationer, enhetens kalendrar (read-only), svenska helgdagar, skollov/studiedagar (manuellt + via ICS), vårdnadsschema.
- **Konfliktdetektering**: "Både Sara och Johan har möte 16:30 – ingen kan hämta Ella". Visa i Idag-vyn och som notis.
- **Hämta/lämna-koordinering**: tilldela, "Kan du ta hämtningen?"-förfrågan med ett-klicks svar (push via FCM).
- Export till enhetskalender + eget ICS-flöde (§3).

### 4.4 Snabbinmatning med naturligt språk [MVP] – en kärnfunktion
- En global **+**-knapp öppnar ett fält som förstår svenska:
  - "Simon fotboll tis 17:30 varje vecka" → återkommande händelse för Simon
  - "Ella kalas lör 14-16 hos Vera, köp present" → händelse + uppgift
  - "mjölk, 2 gurka, pasta" → tre varor i inköpslistan
  - "Johan hämtar imorgon" → tilldelar hämtning
  - "tacos fredag" → middag i matsedeln
- Bygg en **regelbaserad svensk parser** i `shared/core` (veckodagar, "imorgon/övermorgon/nästa vecka/v. 42", klockslag, "varje/varannan", familjemedlemmarnas namn, enheter/antal) med omfattande enhetstester. Visa en förhandsgranskning ("Jag tolkade detta som…") innan det sparas. När parsern är osäker: visa förhandsgranskningen med de osäkra fälten markerade så användaren kan rätta dem med ett tryck.
- **Dela till appen** (Android share intent): text (→ snabbinmatningens parser), länk (recept-URL → receptimport), bild (→ bifogas som bilaga till en händelse eller anteckning, t.ex. ett fotat veckobrev).

### 4.5 Matsedel & recept [MVP]
- **Veckomatsedel** (mån–sön, frukost/lunch/middag konfigurerbart; standard bara middag), drag-och-släpp, "kopiera förra veckan", favoritveckor.
- **Receptbibliotek**: egna recept, portionsskalning med svenska enheter och enhetskonvertering, taggar (snabbt, vegetariskt, barnvänligt, matlåda), tillagningstid.
- **Receptimport från URL**: hämta sidan och läs **schema.org `Recipe` JSON-LD** (ingredienser, steg, portioner, tid, bild-URL). Spara bara för användarens personliga bruk, behåll käll-URL och visa källan tydligt. Om strukturerad data saknas: låt användaren klistra in texten och använd en regelbaserad uppdelning i ingredienser (rader med mängd + enhet) och steg, som användaren sedan justerar.
- **Näringsöversikt** per recept/dag/vecka via Livsmedelsdatabasen: matcha ingredienser mot livsmedel (fuzzy-match + användaren kan bekräfta/ändra; spara mappningen så den återanvänds). Visa energi, protein, fett, kolhydrater, fiber, salt – framställt neutralt och informativt, **utan** bantningsfokus, kalorimål eller värderande språk.
- **Smart förslag** (regelbaserat i MVP): undvik samma huvudkomponent som dagens skollunch, variera protein över veckan, respektera allergier/kostval, prioritera "snabbt" på dagar med kvällsaktiviteter (läses från kalendern!).
- **Restplanering**: markera "gör dubbelt" → skapar automatiskt matlåda/rester-dag.

### 4.6 Inköpslista [MVP]
- Flera listor (veckohandling, apoteket, Biltema…), realtidsdelad i familjen.
- **Generera från matsedeln**: slår ihop mängder över recept med enhetskonvertering ("2 dl + 1 dl grädde = 3 dl"), drar av det som finns i "hemma"-lagret om användaren använder det.
- **Basvaror**: varor som återkommer (mjölk, bröd, bananer) föreslås automatiskt baserat på historik/intervall.
- **Butiksläge**: stor text, sorterat efter **butikens avdelningsordning** (användaren kan ha flera butiker med egen ordning; standardordning för en typisk svensk mataffär), avbockade varor sjunker till botten, skärmen hålls vaken, ångra.
- Automatisk kategorisering av varor till avdelning (lokal ordlista + lär sig av användarens korrigeringar).
- **Streckkodsskanning** (CameraX + ML Kit, Android-`actual`) → Open Food Facts → vara.
- "Jag handlar nu"-indikator till övriga i familjen ("Sara är i affären – lägg till det du behöver nu"), push när någon lägger till en vara under tiden.
- Dela som text till valfri app.

### 4.7 Uppgifter, sysslor & mental last [MVP]
- Uppgifter med ansvarig, deadline, återkommande, checklista, koppling till händelse ("köp present" ↔ kalas).
- **Hushållssysslor med rotation** (t.ex. dammsugning roterar veckovis).
- **Fördelningsvy** [MVP]: enkel, icke-dömande visualisering av hur uppgifter/hämtningar/sysslor fördelats mellan vuxna senaste 4 veckorna. Syfte: samtal, inte skuld.
- **Barnens sysslor & veckopeng** [V2]: stjärnor/poäng, föräldern godkänner, valfri koppling till veckopeng.

### 4.8 Kalas, presenter & högtider [V2]
- Kalas-händelser med OSA-status, presentidéer per barn (önskelista), budget, "present köpt"-status, påminnelse 3 dagar innan.
- Egna kalas: gästlista, inbjudningstext att dela.

### 4.9 Ingen AI (beslutat av ägaren)
- Appen ska **inte** använda några externa AI-tjänster eller LLM-API:er (varken Claude, OpenAI eller andra), och inga API-nycklar för sådant ska finnas någonstans i projektet. All "smarthet" (snabbinmatning, matsedelsförslag, kategorisering av varor, klädråd) är regelbaserad och körs lokalt.
- Skriv beslutet som första post i `docs/DECISIONS.md`.

### 4.10 Notiser [MVP]
- Lokala schemalagda påminnelser (WorkManager/AlarmManager – undvik exakta alarm om det inte verkligen behövs, p.g.a. behörighetskrav) + FCM för familjehändelser (ny vara, hämtningsförfrågan, ändrad händelse).
- **Tysta timmar**, notiskanaler per typ (så användaren kan styra i systeminställningar), sammanslagning för att undvika spam.

### 4.11 Widgets [MVP: 2 st]
- **Idag-widget** (Glance): nästa händelse, vem hämtar, ska-med-idag.
- **Inköpslista-widget**: lista + snabbt lägga till.

### 4.12 Övrigt
- **Onboarding på under 2 minuter** [MVP]: skapa familj → lägg till barn (namn + ev. skola) → välj ort för väder → klart. Allt annat erbjuds kontextuellt senare. Möjlighet att hoppa över allt och utforska **demoläge**.
- **Demoläge / seed-data** [MVP]: en påhittad familj ("Familjen Lind": två vuxna, Ella 9, Simon 4, morfar) med en realistisk vecka – används i demo-flavor, för skärmdumpar och portfolio.
- **Sök** över händelser, recept, uppgifter, varor [V2].
- **Export av all data** (JSON) och **radera konto/familj** [MVP] (GDPR-krav, se §6).
- **Språk:** svenska (primärt) och engelska via string-resurser; per-app-språkval. All UI-text skrivs av dig med samma varma, korta ton. Inga hårdkodade strängar.
- **Tillgänglighet** [MVP]: TalkBack-etiketter, kontrast AA, skalbar text upp till 200 % utan att layouter går sönder, inte bara färg för att bära betydelse (medlemsfärg + initial).

---

## 5. Design & UX

- **Material 3** med eget tema: varm, lugn färgskala (t.ex. dämpad salvia/havre/terrakotta som accent – välj själv och dokumentera i `docs/DESIGN.md`), stöd för **dynamic color** som tillval, **mörkt läge** från start.
- Varje familjemedlem har en färg från en palett som är testad för kontrast i både ljust och mörkt läge.
- **Bottennavigering:** Idag · Kalender · Mat · Handla · Mer. Global **+** (snabbinmatning) nåbar från alla flikar.
- Tomma lägen ska vara hjälpsamma och vänliga ("Inga händelser idag. Njut av lugnet ☕ – eller lägg till något med +").
- Mikrointeraktioner: subtil haptik vid avbockning, tillfredsställande men diskret animation när inköpslistan är klar.
- Skriv `docs/DESIGN.md` med färgtokens, typografiskala, spacing, komponentprinciper och tonalitet för text.
- Gör **skärmdumpstester** (Roborazzi eller motsvarande) för huvudvyerna i ljust/mörkt läge och stor text – de fungerar både som regressionstest och portfoliobilder.

---

## 6. Data, synk, säkerhet & integritet

### Datamodell (Firestore, förslag)
```
families/{familyId}
  members/{memberId}          // vuxna med konton + roll
  children/{childId}          // barnprofiler (inget konto)
  custodySchedules/{id}
  events/{eventId}
  packingRules/{id}
  mealPlans/{isoWeek}         // t.ex. "2026-W42"
  recipes/{recipeId}
  shoppingLists/{listId}/items/{itemId}   // varje vara eget dokument → färre konflikter
  tasks/{taskId}
  icsSubscriptions/{id}
  settings/{doc}
users/{uid}                   // profil, familjemedlemskap, notisinställningar, FCM-tokens
```
- **Offline först:** Room är sanningen i appen; en synkmotor speglar mot Firestore (lyssnare för inkommande, köade skrivningar för utgående). Konflikter: last-write-wins per fält med `updatedAt` (servertid) + `updatedBy`; mjuk radering (`deletedAt`) så raderingar synkas. Testa synkmotorn med fejkad backend.
- **Säkerhetsregler:** endast medlemmar i en familj kan läsa/skriva dess data; rollbaserad skrivbehörighet; validering av fält och typer. **Skriv regeltester** mot emulatorn och kör dem i CI.
- **App Check** på Functions och Firestore. Inga hemligheter i klienten.

### GDPR & barns data
- All lagring i EU-region. Dataminimering: barn behöver bara förnamn; födelsedatum valfritt (ålder används för klädråd).
- Allergier/kost = känsliga uppgifter: opt-in, tydligt syfte, möjlighet att radera när som helst.
- Integritetsvy i appen + utkast till **integritetspolicy** och underlag för Google Plays **Data safety-formulär** i `docs/PRIVACY.md` och `docs/PLAY_DATA_SAFETY.md` (på svenska och engelska).
- Funktion för **export** (JSON) och **radering** av konto/familj (Cloud Function som raderar rekursivt).
- Crashlytics/analys endast efter samtycke; ingen reklam, ingen försäljning av data.

---

## 7. Premium-förberedelse (ingen betalning aktiv nu)

Ägaren använder appen själv/som portfolio först och planerar en Play-lansering med premiumfunktioner senare.
- Bygg en `Entitlements`-abstraktion (`isPremium`, `features: Set<Feature>`) med en implementation som **alltid låser upp allt** i nuläget.
- Förbered (men aktivera inte) en `BillingRepository` för Google Play Billing bakom samma interface; lämna tydliga TODO:er och en sektion i `docs/MONETIZATION.md` med förslag på gratis vs premium (förslag: gratis = kalender, listor, matsedel, 1 familj; premium = obegränsade ICS-prenumerationer, vårdnadsdelning med andra hushållet, näringsöversikt, extra widgets – motivera och justera själv).
- Ingen funktion får vara beroende av att betalning fungerar.

---

## 8. Faser och acceptanskriterier

Arbeta fas för fas. Committa ofta. Uppdatera `docs/PROGRESS.md` efter varje fas.

**Fas 0 – Grund**
- KMP-projekt med modulstruktur, version catalog (verifierade versioner), flavors (demo/dev/prod), Koin, Room, Ktor, navigation-skal, tema (ljust/mörkt), CI-workflow, ktlint/detekt, Firebase-emulatorkonfiguration (`firebase.json`, regler, `functions/`-skelett i TypeScript).
- `CLAUDE.md`, `docs/BRIEF.md`, `docs/DECISIONS.md`, `docs/PROGRESS.md`, `docs/SETUP.md` skapade.
- ✅ `./gradlew :composeApp:assembleDemoDebug` bygger; appen startar på emulator och visar tom navigation; iOS-target kompilerar.

**Fas 1 – Familj, kalender, Idag**
- Auth (e-post + Google Sign-In; anonym i demo), skapa/gå med i familj via länk/QR, barnprofiler med Skolverket-lookup, kalender (dag/vecka/månad/agenda, RRULE, veckonummer), svenska helgdagar lokalt, vårdnadsschema, Idag-vy med tidslinje + ska-med-idag + SMHI-väder och klädråd, snabbinmatning med svensk parser, synkmotor + säkerhetsregler + regeltester, seed-data.
- ✅ Två emulatorer inloggade i samma familj ser varandras ändringar inom några sekunder och fungerar offline. Parser- och helgdagstester gröna (minst 60 parsertestfall).

**Fas 2 – Mat & handla**
- Receptbibliotek, URL-import (JSON-LD), portionsskalning/enhetskonvertering, veckomatsedel, Livsmedelsdatabasen (lokalt FTS-index + näringsöversikt), skollunch-provider (skolmaten-RSS + manuell), smarta förslag (regelbaserat), inköpslista genererad från matsedel med sammanslagning, basvaror, butiksläge med avdelningsordning, streckkodsskanning + Open Food Facts, "jag handlar nu".
- ✅ Från en planerad vecka med 5 recept genereras en korrekt sammanslagen och avdelningssorterad lista; testat med enhetstester för konvertering och sammanslagning.

**Fas 3 – Integrationer & notiser**
- ICS-prenumerationer, tvåvägs enhetskalender, eget ICS-flöde (Function), konfliktdetektering, hämta/lämna-förfrågningar, FCM, lokala påminnelser, morgonnotis, tysta timmar, två widgets, uppgifter/sysslor med rotation, fördelningsvy, export/radering av data.
- ✅ En ICS-URL från en verklig källa (använd en publik test-ICS) visas korrekt inkl. återkommande händelser och tidszoner; familjehändelser syns i enhetens Google-kalender.

**Fas 4 – Polish & Play-beredskap**
- Tillgänglighetsgenomgång, skärmdumpstester, prestanda (kallstart, listor med 500+ varor), felhantering och tomma lägen överallt, onboarding < 2 min, svenska + engelska texter genomlästa, Entitlements/Billing-förberedelse, integritetspolicy + Data safety-underlag, Play-butikstexter (kort/lång beskrivning på svenska och engelska) i `docs/STORE_LISTING.md`, releasebygge (R8, signeringsinstruktioner utan att checka in nycklar).
- Skriv i `docs/SETUP.md` en checklista för lansering, inklusive att **nya personliga utvecklarkonton på Google Play måste köra ett stängt test med minst 12 testare i 14 dagar i följd** innan produktionsåtkomst kan sökas.
- ✅ `./gradlew build` grönt, alla tester gröna, lint utan fel, release-AAB bygger, PROGRESS.md visar alla MVP-punkter som klara.

**Fas 5 – V2-funktioner** (endast när fas 0–4 är klara och polerade): barnvy & veckopeng, kalas/presenter, sök, flerhushållsdelning fullt ut, fler lunchleverantörer.

---

## 9. Kvalitetskrav (gäller hela tiden)

- Domänlogik (parsers, datum, helgdagar, enhetskonvertering, inköpssammanslagning, klädregler, vårdnadsschema, konfliktdetektering) ligger i `shared/core` som ren Kotlin och har **hög testtäckning**.
- ViewModels testas med fejkade repositories. Kritiska UI-flöden har Compose UI-tester (onboarding, lägg till händelse via snabbinmatning, generera inköpslista, butiksläge).
- Inga `!!` i produktionskod utan kommentar om varför. Inga tysta `catch`. Strukturerad loggning (ingen persondata i loggar).
- Nätverksanrop: timeouts, retry med backoff, respektera källornas villkor, cacha aggressivt.
- Allt som rör tid testas över sommartidsomställning och årsskifte (vecka 53!).
- Varje extern källa har attribuering i "Om data"-vyn: Livsmedelsverket (CC BY 4.0), SMHI, Skolverket, Open Food Facts (ODbL) och skollunchleverantören.

---

## 10. Manuella steg för ägaren (samla i `docs/SETUP.md`)

Bygg allt så att detta kan göras sist. Lista tydligt, steg för steg:
1. Skapa Firebase-projekt, välj EU-plats för Firestore, lägg till Android-app (paketnamn), ladda ner `google-services.json` till rätt flavor-mapp.
2. Aktivera Auth-metoder, FCM, App Check (Play Integrity), Crashlytics.
3. Uppgradera till **Blaze-planen** (krävs för Cloud Functions) och sätt en budgetvarning.
4. Deploya regler och Functions.
5. SHA-1/SHA-256 för debug och release i Firebase (för Google Sign-In och App Links).

---

## 11. Börja nu

1. Läs igenom briefen, skapa `docs/BRIEF.md` (kopia av denna), `CLAUDE.md` och en första `docs/PROGRESS.md` med alla faser som checklistor.
2. Verifiera versioner och de externa API:erna (gör ett testanrop mot Livsmedelsdatabasen, SMHI SNOW1g, Skolverket och en skolmaten-RSS och spara exempelsvar som testfixturer i `shared/data/src/commonTest/resources/fixtures/`).
3. Starta Fas 0 och arbeta dig igenom faserna utan att vänta på bekräftelse.

Lycka till – bygg något som får en förälder att andas ut.
