# Design

Lugn i kaoset: varm, trygg, vuxen – inte barnslig. Mycket luft, tydlig typografi, inget visuellt brus.
Koden är sanningen: tokens bor i `shared/ui/src/commonMain/kotlin/se/pusslet/ui/theme/` och medlemsfärgerna i
`shared/core/.../model/MemberPalette.kt`. Den här filen förklarar dem och reglerna runt dem.

## Färg

Material 3 med eget schema. **Salvia** (primär), **havre** (sekundär) och **terrakotta** (accent/varning).
Dynamic color (Android 12+) är ett tillval i Inställningar; det egna schemat är standard.

| Roll | Ljust | Mörkt | Används till |
|---|---|---|---|
| primary | Sage40 `#4A6851` | Sage80 `#AFCFB4` | Huvudknappar, +, avdelningsrubriker i inköpslistan, ”Du” |
| primaryContainer | Sage90 `#CDE8D1` | Sage30 `#354F3B` | Valt läge: vald flik, segment och filterchip (`SelectionColors`), idag, `InfoPill` vald |
| secondary | Oat40 `#6C5B44` | Oat80 `#D9C4A4` | Sekundära åtgärder |
| secondaryContainer | Oat90 `#F4E2C6` | Oat30 `#54442F` | Fokusytan – högst en per skärm |
| tertiary | Terracotta40 `#99442A` | Terracotta80 `#FFB59D` | Påminnelse eller osäker tolkning, som ikon/text: Warning-ikonen, krock med skollunchen |
| tertiaryContainer | Terracotta90 `#FFDBD0` | Terracotta30 `#7B2E16` | — (Warning är ofylld, inga terrakottafyllda kort) |
| error / errorContainer | Error40 `#B3261E` | Error80 `#F2B8B5` | Problem idag, fyllt (`errorContainer`) och med åtgärd ("ingen kan hämta Ella"), röda dagar |
| surface / background | Neutral98 `#FBF8F3` | Neutral10 `#1A1C18` | Bakgrund – varm off-white, aldrig ren vit; även Warning-kortets yta |
| surfaceContainerLow | Neutral96 `#F6F3EC` | Neutral12 `#1E201C` | Kort (`SectionCard`), förhandsvisningen i snabbinmatningen |
| surfaceContainerHigh | `#EBE8E1` | Neutral17 | `InfoPill` (ej vald) |
| outlineVariant | NeutralVariant80 | NeutralVariant30 | Kantlinjen runt Warning |
| onSurfaceVariant | NeutralVariant30 | NeutralVariant80 | Sekundär text, detaljer, Info-raden |

**Medlemsfärger** (8 st: Sage, Terracotta, Ocean, Plum, Ochre, Teal, Rose, Slate) har egna container/innehåll-par
för ljust och mörkt läge. Alla par klarar WCAG AA (4,5:1) – testat i `MemberPaletteTest`. Färg är **aldrig** enda
signalen: `PersonAvatar`/`ColorBadge` visar alltid initial eller emoji, och texten bredvid säger namnet.

## Typografi

Material 3-skalan, något större brödtext för att kunna läsas i farten. Allt i `sp` så systemets textstorlek
(upp till 200 %) gäller.

| Stil | Storlek / radhöjd | Vikt | Används till |
|---|---|---|---|
| headlineMedium | 28 / 36 | Normal | Skärmrubrik (Idag-hälsning, listans namn) |
| headlineSmall | 22 / 30 | Medium | Flikrubrik (`TabHeader`: ”Torsdag 8 oktober”) |
| titleLarge | 20 / 28 | Medium | Sektionsrubriker (`Section`), veckan i `WeekSwitcher`, ark-rubriker, avdelningar i butiksläge |
| titleMedium | 16 / 24 | SemiBold | Kortrubriker, veckodagar |
| titleSmall | 14 / 20 | SemiBold | Avdelningsrubriker i inköpslistan |
| bodyLarge | 17 / 25 | Normal | Listrader, varor, ingredienser |
| bodyMedium | 15 / 22 | Normal | Stödtext |
| bodySmall | 14 / 20 | Normal | Detaljrader, hjälptext under fält |
| labelLarge | 15 | SemiBold | Knappar |
| labelMedium | 14 / 20 | Medium | Fältetiketter, `InfoPill` |
| labelSmall | 14 / 20 | Medium | Små etiketter (timmar i dagvyn) – samma storlek som labelMedium, ingen stil under 14 sp |
| StoreModeItemStyle | 24 / 32 | Medium | Varor i butiksläget ("stor text") |

Rubriker får `semantics { heading() }` så TalkBack kan hoppa mellan dem. Typstegen går åt ett håll (22 > 20 > 17 > 15 >
14) och ingen text är under 14 sp.

## Avstånd och former

4 dp-rutnät (`Spacing`): `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`. Skärmkant 16 dp. Mellan två sektioner
`sectionGap` 32 dp; flikrötternas listor har `listBottomInset` 88 dp luft i botten.
Träffytor minst **48 dp**, primära rader och knappar **56 dp**, butikslägets rader **72 dp** ("en hand, en tumme").
Former: 6 / 10 / 16 / 24 / 32 dp (extraSmall … extraLarge). Kort använder medium (16 dp).

## Komponentprinciper

- **Idag först, nedtill nåbart.** Viktiga handlingar ligger i nedre halvan. **+** är rund och utan text och betyder
  ”lägg till det skärmen handlar om” – ett sätt att lägga till per skärm (ADR-023). Listor har 88 dp luft i botten.
- **Stateless skärmar + tunn `*Route`.** Skärmen tar state och callbacks (förhandsvisningar, skärmdumpstester); routen
  kopplar ViewModel, snackbars och plattform.
- **Ångra i stället för "Är du säker?"** för vardagliga handlingar (bocka av, ta bort vara, lägg till). Bekräftelse-
  dialog bara för det som inte går att ångra (ta bort lista, recept, familj).
- **En fokusyta per skärm** (havre). Annars platta sektioner med rubrik och luft; kort bara för fokusyta, Problem, ark
  och dialoger. Undantag (ägarens beslut 2026-10-21): på Idag är varje avsnitt (Idag händer, Att göra, Kommande, Inköp)
  ett eget tyst kort (`Section(framed = true)` / `SectionFrame`: `surfaceContainerLow` med tunn `outlineVariant`-kant), och
  Kalenderns veckolista har en tunn linje mellan dagarna. **Uppmärksamhet** har tre nivåer som skiljer sig i form, ikon och placering: Info är en enkel rad,
  Warning (påminnelse) är ofylld med terrakottaikon, Problem är fyllt (`errorContainer`) och har alltid en åtgärd.
- **Tomt är tyst.** Ett tomt block syns inte. En helt tom skärm har en mening och en primär åtgärd (`EmptyState`).
- **Förklarbart.** Regelbaserade förslag visar högst två skäl på en rad ("Snabbt", "Inte som lunchen"). Osäkra tolkningar
  i snabbinmatningen markeras i terrakotta.
- **Haptik** bara vid avbockning (subtil `TextHandleMove`). Butikslägets "Allt i vagnen!" är en lugn fade/scale, inget
  konfettiregn.
- **Omordning** görs med upp/ner-knappar (fungerar med TalkBack) – dra-och-släpp är i så fall ett tillägg, aldrig enda
  sättet.
- **Edge-to-edge:** innehåll får `contentPadding` från skalets `Scaffold`; inga hårdkodade systemfältshöjder.
- **Samma form, samma sak.** En knapp gör något, `NavRow` öppnar, `OutlinedValueField` väljer ett värde, FilterChip
  filtrerar, `InfoPill` informerar. `onClick = {}` är förbjudet. Högst en fylld knapp per skärm eller ark. Se tabellen
  nedan.
- **Anpassningsbar startsida** (ADR-027). Svaret står först och ligger fast; blocken under går att slå av och ordna om;
  det som måste lösas döljs aldrig; nya block kommer avstängda; anpassningen ligger som en rad längst ned.
- **Kvitton** säger vad som hände och var: ”2 varor på listan · Visa”. Ångra visas bara när den verkligen återställer.
- **Hjälparvyn** (Role.Caregiver) har ingen text under 18 sp och tryckytor på 64 dp (ADR-029).

### Komponenter

Allt ligger i `shared/ui/.../components/`. Varje form betyder en sak; välj efter vad raden gör, inte efter hur den ska se
ut. Komponenterna lägger inte till skärmkanten själva – det gör skärmen (`Spacing.screenHorizontal`).

| Komponent | Använd när | Inte för |
|---|---|---|
| `TabHeader` | Överst på en flikrot eller helskärm utan toppfält: ”Torsdag 8 oktober” med underraden ”Vecka 41”. En rubrik för TalkBack, högst två ikonknappar. | Undersidor och ark (`PussletTopBar`, arkets rubrik) |
| `WeekSwitcher` | Bläddra veckor i Kalender och Mat: ”Vecka 41 · oktober” till vänster, ‹ › till höger. ”Idag” bara när en annan vecka visas. Läses upp när veckan byts. | Välja en dag (`WeekStripPicker`) |
| `Section` / `SectionHeader` | Platt avsnitt: titleLarge-rubrik, valfri ikon och en tyst åtgärd (”Visa alla”), innehållet under. `sectionGap` mellan avsnitten. Med `framed = true` blir avsnittet ett tyst kort (`SectionFrame`), som på Idag. `SectionHeader` när raderna är egna poster i en LazyColumn. | Kort och avdelare (`SectionCard` utgår på huvudskärmarna) |
| `HeroCard` | Skärmens enda fokusyta i havre (`secondaryContainer`): svaret skärmen finns till för, t.ex. Morgonkollen. | Listor, påminnelser, Problem, en andra fokusyta |
| `SentenceRow` | En mening per rad, personen först: ”Johan lämnar Simon 07:50”. Egna rader (”Du …”) i fetstil och sage, högst en underrad, › när raden öppnar något. En TalkBack-nod; en knapp till höger (”Jag tar det”) är en egen. | Rader som bara leder vidare (`NavRow`) |
| `NavRow` | En rad som öppnar något: ”Anpassa Idag ›”, ”Näring för veckan ›”, raderna i Mer. Full bredd, ›, minst 56 dp. | Att göra något (knapp) eller välja ett värde |
| `QuietLine` | En tyst, centrerad rad när inget mer finns: ”Inget mer idag”. | En helt tom skärm (`EmptyState` med en åtgärd) |
| `OutlinedValueField` | Väljer ett värde i en väljare (ark, dialog, meny): ser ut som ett kantat textfält med ▾. Platshållare i stället för ”–” (”Ingen enhet”, ”Inte bestämt”). `highlight` = osäker tolkning, i terrakotta. | Fritext (textfält). Den platta `ValueField` finns kvar för befintliga skärmar. |
| `WeekStripPicker` | Byta dag inom en vecka i ett ark: sju chips mån–sön, vald dag i sage, idag kantad. Radbryts i stället för att klippas vid stor text. | Bläddra veckor (`WeekSwitcher`) |
| `ReorderButtons` | Flytta en rad upp eller ned (butikens avdelningar, Idags sektioner): två knappar på 48 dp med radens namn för TalkBack (”Flytta upp Mejeri”). Översta upp och nedersta ned är avstängda. | – |
| `showUndo` | Kvitto med Ångra (”Mjölk bockad · Ångra”). Ersätter snackbaren som syns och svarar `true` när man trycker Ångra. | Ändringar som Ångra inte återställer helt |
| `Banner` | Uppmärksamhet i tre nivåer: Info (en rad), Warning (ofylld, terrakottaikon), Problem (fyllt, med åtgärd). | Sådant som inte kräver uppmärksamhet |
| `InfoPill` | Informerar: ”Vecka 41”, helgdagens namn, en tagg. Ingen klick, ingen ripple; vald i sage (”Idag”). | Knappar och filter (FilterChip) |
| `CheckRow` / `SwitchRow` | Kryssruta eller switch där hela raden är tryckytan (minst 48 respektive 56 dp). | – |

Hela underlaget och arbetsplanen finns i [UX_PLAN.md](UX_PLAN.md).

## Ton i text

Kort, varmt, vuxet – som en lugn förälder som har koll. Svenska först, engelska med samma ton.

- Säg vad som händer och vad man kan göra: "Sidan gick inte att nå. Kolla länken eller försök igen när du har nät."
- Du-tilltal, inga utropstecken i felmeddelanden, inga skämt när något gått fel.
- Små glädjeämnen är okej där det passar: "Inköpslistan är tom. Skönt!", "Allt i vagnen! Bra jobbat 🎉".
- **Näring** beskrivs neutralt och informativt: inga mål, inga värdeord ("nyttigt", "onyttigt"), källa i näringsvyn
  (”Källa: Livsmedelsverket”) och hur komplett uppskattningen är. Övriga källor och licenser står i ”Om Pusslet” (ADR-025).
- Svenska format: 24-timmarsklocka, decimalkomma, veckan börjar på måndag, veckonummer syns ("Vecka 42"),
  svenska enheter (dl, msk, tsk, krm, st, förp).
- Inga hårdkodade strängar – allt ligger i `shared/ui/src/commonMain/composeResources/values*/strings.xml`.

**Skrivregler** (ADR-024, gäller båda språken):
1. Etiketter har 1–3 ord och knappar börjar med ett verb. Upprepa inte skärmens namn.
2. Huvudskärmar har inga förklarande meningar; förklaringen hör hemma i tomläget, bakom (i) eller i inställningen.
3. Siffror först och kompakt: ”5 kvar”, ”3 av 8 i vagnen”. Årtalet visas bara om det inte är i år.
4. Statusrader är fragment (”Sara handlar nu”). Hela meningar bara i Problem (”Ingen kan hämta Ella kl 15:45”).
5. En rad visar en sak, med högst en sekundär rad med högst tre delar (” · ”). Upprepa aldrig rubrik, ikon eller tid.
6. Tomt är tyst. Skriv aldrig ”lägg till med +”.
7. Ikon med ord är standard; ensam ikon bara när den är entydig (+, ×, ›, ⌄, sök, dela, stjärna, kryssruta, väder).
8. En sak heter samma sak överallt (”Handla till veckan”).
9. Källor och licenser står i ”Om Pusslet”.
10. Högst ett varmt ord eller en emoji per skärm, i tomlägen och klart-lägen. Kvitton har inget ”!”.

**Textbudget:** högst 40 ord ovanför vecket på Idag, 45 på övriga flikrötter, etiketter högst 3 ord.
