# Design

Lugn i kaoset: varm, trygg, vuxen – inte barnslig. Mycket luft, tydlig typografi, inget visuellt brus.
Koden är sanningen: tokens bor i `shared/ui/src/commonMain/kotlin/se/pusslet/ui/theme/` och medlemsfärgerna i
`shared/core/.../model/MemberPalette.kt`. Den här filen förklarar dem och reglerna runt dem.

## Färg

Material 3 med eget schema. **Salvia** (primär), **havre** (sekundär) och **terrakotta** (accent/varning).
Dynamic color (Android 12+) är ett tillval i Inställningar; det egna schemat är standard.

| Roll | Ljust | Mörkt | Används till |
|---|---|---|---|
| primary | Sage40 `#4A6851` | Sage80 `#AFCFB4` | Huvudknappar, valda flikar, avdelningsrubriker i inköpslistan |
| primaryContainer | Sage90 `#CDE8D1` | Sage30 `#354F3B` | Valda chips, markerad dag |
| secondary | Oat40 `#6C5B44` | Oat80 `#D9C4A4` | Sekundära åtgärder |
| secondaryContainer | Oat90 `#F4E2C6` | Oat30 `#54442F` | Info-banners ("Sara handlar nu") |
| tertiary | Terracotta40 `#99442A` | Terracotta80 `#FFB59D` | Varningar utan fara: krock med skollunchen, upptagen kväll |
| tertiaryContainer | Terracotta90 `#FFDBD0` | Terracotta30 `#7B2E16` | Varningsbanners |
| error | Error40 `#B3261E` | Error80 `#F2B8B5` | Riktiga problem ("ingen kan hämta Ella"), röda dagar |
| surface / background | Neutral98 `#FBF8F3` | Neutral10 `#1A1C18` | Bakgrund – varm off-white, aldrig ren vit |
| surfaceContainerLow | Neutral96 `#F6F3EC` | Neutral12 `#1E201C` | Kort (`SectionCard`) |
| onSurfaceVariant | NeutralVariant30 | NeutralVariant80 | Sekundär text, detaljer |

**Medlemsfärger** (8 st: Sage, Terracotta, Ocean, Plum, Ochre, Teal, Rose, Slate) har egna container/innehåll-par
för ljust och mörkt läge. Alla par klarar WCAG AA (4,5:1) – testat i `MemberPaletteTest`. Färg är **aldrig** enda
signalen: `PersonAvatar`/`ColorBadge` visar alltid initial eller emoji, och texten bredvid säger namnet.

## Typografi

Material 3-skalan, något större brödtext för att kunna läsas i farten. Allt i `sp` så systemets textstorlek
(upp till 200 %) gäller.

| Stil | Storlek / radhöjd | Vikt | Används till |
|---|---|---|---|
| headlineMedium | 28 / 36 | Normal | Skärmrubrik (Idag-hälsning, listans namn) |
| headlineSmall | 22 / 30 | Medium | — |
| titleLarge | 20 / 28 | Medium | Veckonummer, ark-rubriker, avdelningar i butiksläge |
| titleMedium | 16 / 24 | SemiBold | Kortrubriker, veckodagar |
| titleSmall | 14 / 20 | SemiBold | Avdelningsrubriker, sektionsrubriker |
| bodyLarge | 17 / 25 | Normal | Listrader, varor, ingredienser |
| bodyMedium | 15 / 22 | Normal | Stödtext |
| labelLarge | 15 | SemiBold | Knappar |
| StoreModeItemStyle | 24 / 32 | Medium | Varor i butiksläget ("stor text") |

Rubriker får `semantics { heading() }` så TalkBack kan hoppa mellan dem.

## Avstånd och former

4 dp-rutnät (`Spacing`): `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`. Skärmkant 16 dp.
Träffytor minst **48 dp**, primära rader och knappar **56 dp**, butikslägets rader **72 dp** ("en hand, en tumme").
Former: 6 / 10 / 16 / 24 / 32 dp (extraSmall … extraLarge). Kort använder medium (16 dp).

## Komponentprinciper

- **Idag först, nedtill nåbart.** Viktiga handlingar ligger i nedre halvan; global **+** (snabbinmatning) finns på
  alla flikrötter utom Mer.
- **Stateless skärmar + tunn `*Route`.** Skärmen tar state och callbacks (förhandsvisningar, skärmdumpstester); routen
  kopplar ViewModel, snackbars och plattform.
- **Ångra i stället för "Är du säker?"** för vardagliga handlingar (bocka av, ta bort vara, lägg till). Bekräftelse-
  dialog bara för det som inte går att ångra (ta bort lista, recept, familj).
- **Kort (`SectionCard`)** grupperar en fråga ("Ska med idag", en dag i matsedeln). **Banner** för sådant som kräver
  uppmärksamhet: Info (havre), Warning (terrakotta), Problem (error).
- **Tomma lägen** säger alltid vad man kan göra härnäst (`EmptyState`).
- **Förklarbart.** Regelbaserade förslag visar sina skäl som chips ("Snabbt en upptagen kväll", "Inte samma som
  skollunchen"). Osäkra tolkningar i snabbinmatningen markeras i terrakotta.
- **Haptik** bara vid avbockning (subtil `TextHandleMove`). Butikslägets "Allt i vagnen!" är en lugn fade/scale, inget
  konfettiregn.
- **Omordning** görs med upp/ner-knappar (fungerar med TalkBack) – dra-och-släpp är i så fall ett tillägg, aldrig enda
  sättet.
- **Edge-to-edge:** innehåll får `contentPadding` från skalets `Scaffold`; inga hårdkodade systemfältshöjder.

## Ton i text

Kort, varmt, vuxet – som en lugn förälder som har koll. Svenska först, engelska med samma ton.

- Säg vad som händer och vad man kan göra: "Sidan gick inte att nå. Kolla länken eller försök igen när du har nät."
- Du-tilltal, inga utropstecken i felmeddelanden, inga skämt när något gått fel.
- Små glädjeämnen är okej där det passar: "Inköpslistan är tom. Skönt!", "Allt i vagnen! Bra jobbat 🎉".
- **Näring** beskrivs neutralt och informativt: inga mål, inga värdeord ("nyttigt", "onyttigt"), alltid källa
  (Livsmedelsverket, CC BY 4.0) och hur komplett uppskattningen är.
- Svenska format: 24-timmarsklocka, decimalkomma, veckan börjar på måndag, veckonummer syns ("Vecka 42"),
  svenska enheter (dl, msk, tsk, krm, st, förp).
- Inga hårdkodade strängar – allt ligger i `shared/ui/src/commonMain/composeResources/values*/strings.xml`.
