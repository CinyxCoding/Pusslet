# Design – Pusslet

Lugn, varm och snabb. Appen används med en hand i hallen klockan 07:40 – allt viktigt ska synas utan att leta, och
inget ska skrika. Källkoden för allt nedan finns i `shared/ui/src/commonMain/kotlin/se/pusslet/ui/theme/`.

## Principer
1. **Dagen först.** Idag-vyn svarar på "vad händer, vem hämtar, vad ska med" innan något annat.
2. **En hand, en tumme.** Viktiga knappar nertill, minst 48 dp (primära 56 dp), inga små kryss i hörn utan bekräftelse.
3. **Färg är aldrig ensam.** Medlemsfärgen visas alltid med initial eller emoji; varningar har ikon och text.
4. **Tyst som standard.** Sammanslagna notiser, tysta timmar, inga röda prickar för sådant som kan vänta.
5. **Lokalt först.** Allt fungerar offline; laddning visas bara där nätet faktiskt behövs (väder, import, ICS).

## Färger
Material 3-roller på egen palett: salvia (primär), havre (sekundär), terrakotta (accent/tertiär). Dynamic color finns som
tillval i Inställningar men är av som standard – paletten är en del av produkten.

| Roll | Ljust | Mörkt |
|---|---|---|
| primary / onPrimary | `#4A6851` / `#FFFFFF` | `#AFCFB4` / `#1B3723` |
| primaryContainer / on | `#CDE8D1` / `#0F2415` | `#354F3B` / `#CDE8D1` |
| secondary / onSecondary | `#6C5B44` / `#FFFFFF` | `#D9C4A4` / `#3B2E1A` |
| secondaryContainer / on | `#F4E2C6` / `#241A0C` | `#54442F` / `#F4E2C6` |
| tertiary / onTertiary | `#99442A` / `#FFFFFF` | `#FFB59D` / `#5C1A05` |
| tertiaryContainer / on | `#FFDBD0` / `#390C00` | `#7B2E16` / `#FFDBD0` |
| error / onError | `#B3261E` / `#FFFFFF` | `#F2B8B5` / `#601410` |
| background = surface | `#FBF8F3` | `#1A1C18` |
| onSurface | `#1F1C17` | `#E4E2DB` |
| surfaceVariant / on | `#E1E4D8` / `#45483F` | `#45483F` / `#C5C8BC` |
| surfaceContainerLow (kort) | `#F6F3EC` | `#1E201C` |
| outline / outlineVariant | `#75786E` / `#C5C8BC` | `#8F9287` / `#45483F` |

Kort (`SectionCard`) ligger på `surfaceContainerLow`; problem/konflikter på `errorContainer`; tips på `secondaryContainer`.

### Medlemsfärger
Åtta färger (`MemberColor`): Salvia, Terrakotta, Hav, Plommon, Ockra, Kricka, Ros, Skiffer. Varje färg har behållare +
innehåll för ljust och mörkt läge; `MemberPaletteTest` kräver kontrast ≥ 4,5:1 (WCAG AA) mellan initial och behållare
i båda lägena. Nya medlemmar får första lediga färg.

## Typografi
Systemtypsnittet (Roboto på Android) – snabbt, läsbart och skalar med telefonens textstorlek.

| Stil | Storlek / radhöjd | Vikt | Används till |
|---|---|---|---|
| headlineMedium | 28 / 36 sp | Normal | Hälsning på Idag |
| headlineSmall | 22 / 30 sp | Medium | Rubrik i detaljvyer |
| titleLarge | 20 / 28 sp | Medium | Toppfält, dialogrubriker |
| titleMedium | 16 / 24 sp | SemiBold | Kortrubriker, datum |
| bodyLarge | 17 / 25 sp | Normal | Brödtext, listrader |
| bodyMedium | 15 / 22 sp | Normal | Sekundär text |
| labelLarge | 15 sp | SemiBold | Knappar, chips |
| StoreModeItemStyle | 24 / 32 sp | Medium | Butiksläget (läsbart på armlängds avstånd) |

Layouter testas i 150 % och 200 % textstorlek (Roborazzi); text radbryts i stället för att klippas.

## Avstånd och former
- 4 dp-rutnät: `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`; sidmarginal 16 dp.
- Hörn: 6 / 10 / 16 / 24 / 32 dp (extraSmall … extraLarge). Kort 16 dp, bottenark och dialoger 24–32 dp.
- Widgets: 20 dp hörn, 12 dp inre marginal, samma palett.

## Komponenter
- **SectionCard** – titel + innehåll, en sak per kort ("Ska med idag", "Mat idag").
- **PussletTopBar** – rubrik, bakåt och högst två åtgärder.
- **PersonAvatar / AvatarStack** – färg + initial/emoji; staplas med överlapp för flera.
- **Banner** – kontext som inte är fel (prenumererad händelse, "skrivskyddad").
- **EmptyState** – vänlig mening + en åtgärd, aldrig bara "Tomt".
- **Snabbinmatningen** – global + i alla flikar; bekräftelsekort där osäkra fält är markerade och Ångra finns.
- Avbockning ger kort haptik; ångra finns i snackbar i några sekunder efter radering och avbockning.

## Ton i texter
- **Du**-tilltal, korta meningar, vardagssvenska: "Simon ska hämtas 16:30", inte "Hämtning schemalagd".
- Varm men inte gullig. Max en emoji per vy, och bara där det hjälper (☕ i lugna tomma lägen).
- Säg vad som händer och vad man kan göra: "Kunde inte hämta vädret. Visar från 06:10." – aldrig felkoder.
- Inga skuldbeläggande formuleringar om fördelning ("Sara har gjort 12 av 15") – fördelningsvyn visar utan poäng.
- 24-timmarsklocka, veckan börjar på måndag, veckonummer ("v. 41") syns överallt där datum visas.
- Engelska följer samma ton ("Simon needs picking up at 16:30").

## Tillgänglighet
TalkBack-etiketter på alla ikoner och bilder, rubriker markerade som rubriker, 48 dp tryckytor, kontrast AA för text,
inget som bara bärs av färg, skalbar text upp till 200 %, och inga tidsgränser för att läsa något.
