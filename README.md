# PlayCaption

© 2026 Adam Růžička. Licence GNU AGPLv3 (viz níže).

**České (a slovenské) titulky ke hrám na PS5 a Xboxu přímo na televizi.**

> Spojení s PS5 zajišťuje otevřená knihovna **[Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki)** (Florian Märkl a přispěvatelé, AGPLv3). Čtení titulků, překlad a zobrazení na televizi jsou vlastní práce PlayCaption, vyvíjená s pomocí AI. Podrobně v části [Poděkování](#poděkování-a-použitý-software).

PlayCaption čte anglické titulky ze hry, překládá je a zobrazuje česky přes obraz z HDMI. Hru dál sleduješ přes HDMI v rozlišení a obrazovém režimu konzole. Pomocný Remote Play stream slouží ke čtení textu; nenahrazuje HDMI obraz a PlayCaption na jeho nižší rozlišení HDMI obraz nepřevádí.

Na [předvedené sestavě](https://www.youtube.com/watch?v=N_jr-jix7TE) funguje souběžně Remote Play i HDR. Ukázka není měřením interního rozlišení hry ani přesného počtu vykreslených snímků. Podporu dalších kombinací HDR/VRR a dopad souběžného Remote Play na výkon je potřeba ověřit na konkrétní sestavě.

- Android aplikace funguje na **Android TV / Google TV** a na Android telefonech/tabletech.
- Pro **LG webOS** je dostupný experimentální přijímač titulků z Android telefonu/tabletu: [návod pro LG](webos/NAVOD-LG.md). Vyžaduje Developer Mode; ověřený model je LG C5. Samostatný překlad přímo v LG bez Android zařízení tato verze neumí.
- Pro **Samsung (Tizen)** je dostupný experimentální přijímač titulků z Android telefonu/tabletu: [návod pro Samsung](tizen/NAVOD-SAMSUNG.md). Vyžaduje Developer Mode a účet Samsung; instalaci zatím provádí externí nástroj, protože Samsung vyžaduje podpis pro konkrétní televizi.
- Projekt je ve stavu **beta**, sledujte [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases).

Příběhový režim používá stejný hlavní překladač jako Standardní režim. Přidává
čtecí frontu a omezené skládání některých nedokončených anglických vět. Větší
kontext může pomoci překladu, ale nejde o jiný překladový model ani o záruku
vyšší kvality. Zpoždění závisí na OCR, síti, skládání vět a délce fronty;
pevné zpoždění 1–2 sekundy negarantujeme.

Při online překladu se rozpoznaný text odesílá externím překladovým službám.
Hlavní online překlad používá neoficiální rozhraní Google, jehož dostupnost ani
limity nemáme pod kontrolou. Lokální záloha potřebuje stažený jazykový model.

Údaj Hz/FPS nebo rozlišení v informacích televize popisuje vstupní HDMI signál,
nikoli automaticky interní rozlišení a počet unikátních snímků vykreslených hrou.
Samotná fotografie tohoto údaje neprokazuje vliv Remote Play na výkon hry.

## Stažení

Nejnovější verze: [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases), soubor `PlayCaption_beta_*.apk`.

## Poděkování a použitý software

PlayCaption stojí na práci jiných open-source projektů:

- **[Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki)** (Florian Märkl a přispěvatelé) a **[chiaki-ng](https://github.com/streetpea/chiaki-ng)**. Jejich knihovna (`libchiaki-jni.so`) zajišťuje veškerou komunikaci s PS5: přihlášení, párování, Remote Play a přenos obrazu. Licence **AGPLv3**. Velké díky autorům, bez nich by PlayCaption nevznikl.
- **[xbox-xcloud-player](https://github.com/unknownskl/xbox-xcloud-player)** (unknownskl, MIT) a **[XStreaming](https://github.com/Geocld/XStreaming)** (Geocld, AGPLv3): podle jejich postupu je napsané přihlášení k Xboxu a spojení s ním (`XboxUcet.kt`, `XboxSpojeni.kt`).
- **[WebRTC](https://webrtc.org/)** (Android sestavení [stream-webrtc-android](https://github.com/GetStream/webrtc-android), BSD/Apache 2.0): přenos obrazu z Xboxu.
- **[ZXing](https://github.com/zxing/zxing)** (Apache 2.0): QR kódy.
- **[JSch](https://github.com/mwiede/jsch)** (BSD): SSH/SFTP pro místní instalaci LG přijímače z Android průvodce. Licenční texty jsou přibalené v APK v `assets/licenses/`.
- **Google ML Kit**: rozpoznání textu z obrazu, offline překlad a rozpoznání jazyka.
- Překlad: online překladová služba.

Část PlayCaption (čtení titulků z obrazu, překlad, zobrazení přes HDMI, rozhraní pro TV a telefon) vzniká s pomocí AI a hodně testování na skutečných televizích a hrách.

Podporu LG webOS a Příběhový režim napsal **Rudolf Nohal**, který se podílí i na opravách chyb a vývoji celé aplikace. Podporu Samsung (Tizen) napsal **Michael Šubák**.

## Zdrojový kód a převzaté části

- Zdrojový kód PlayCaption je v tomto repozitáři. Každá vydaná verze má svůj tag (např. `v0.9-beta`) a odpovídající zdrojový kód je u ní v [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases).
- `app/src/main/jniLibs/*/libchiaki-jni.so`: nativní knihovna z projektu **Chiaki 2.1.1** (© Florian Märkl a přispěvatelé, licence GNU AGPLv3), převzatá z Android sestavení [Chiaki-TV](https://github.com/505e06b2/Chiaki-TV). ARM varianty obsahují opravu odemčení audio dekodéru po nedostupném vstupním bufferu a potlačení přehrávání pomocného Remote Play zvuku; zdrojové patche, testy a postup sestavení jsou v [`native/README.md`](native/README.md). x86 varianty zůstávají původní.
- `app/src/main/jniLibs/*/libchiaki-pc.so`: kopie výše uvedené `libchiaki-jni.so` s **jedinou změnou** – závislost `libmediandk.so` je přejmenovaná na `libpcobraz1.so` (stejně dlouhý název v tabulce dynamických řetězců). Používá se jen na telefonu.
- `app/src/main/jniLibs/*/libpcobraz1.so`: vlastní mezivrstva PlayCaption (AGPLv3) mezi Chiaki a systémovým dekodérem, předává obraz z PS5 přímo do paměti. Zdrojový kód: [`native/pcobraz.c`](native/pcobraz.c).
- `app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt`: převzato z Chiaki 2.1.1 (AGPLv3), upraveno (odstraněno Parcelable) – popis úprav je v hlavičce souboru.

## Vývoj a ověřování

- [Priority a technický backlog P0–P6](docs/PLAN-VYVOJE.md)
- [Výchozí audit větví, testů a měření](docs/P0-VYCHOZI-STAV.md)
- [Reprodukovatelný OCR benchmark](tools/ocr/README.md)

[Příběhový režim](docs/PRIBEHOVY-REZIM.md) je volitelný režim pro telefon/tablet (s LG přijímačem 0.2.1+, Samsung přijímačem 1.3.0+, aktualizovaným Android TV přijímačem nebo přímo na telefonu). Zapíná se na obrazovce překladu v telefonu/tabletu; výchozí Standardní režim zůstává zachovaný. Skládání rozdělených vět je zatím experimentální.

Story výstup do TV se používá až po potvrzení podpory přijímačem. Pro Android TV
v režimu „S telefonem“ je nutná aktualizace telefonu i TV na sestavení s touto
podporou. Starší přijímač bez potvrzení dál dostává Standardní titulky. Nový Android
přijímač stránkuje podle skutečného boxu a potvrzuje dočtení, nikoli jen příjem paketu.
Rozšíření je zatím nevydané a čeká na fyzickou přejímku TCL. Režim „Jen televize“
tím nový přepínač Story nezískává.

## Licence

PlayCaption je šířen pod licencí **GNU AGPLv3** (viz soubor [LICENSE](LICENSE)), stejně jako Chiaki. Kdo kód použije a šíří, musí zveřejnit i svůj zdrojový kód pod stejnou licencí.

PlayCaption je neoficiální projekt a nemá nic společného se společnostmi Sony Interactive Entertainment ani Microsoft. PlayStation, PS5 a Xbox jsou ochranné známky jejich vlastníků.

### Název a logo

Licence AGPLv3 se vztahuje na zdrojový kód, ne na název a logo. Název **PlayCaption** a logo jsou vyhrazeny autorovi (Adam Růžička). Upravené verze musí mít jiný název a nesmí působit jako oficiální PlayCaption (viz čl. 7 písm. e licence AGPLv3).

## LG v Android aplikaci

Stav spojení rozlišuje běh služby, spojení s konzolí a skutečný příjem snímků.
Zvolená TV ani úspěšné odeslání UDP dat nepotvrzují jejich přijetí nebo zobrazení;
aplikace to u výstupu výslovně uvádí. Aktuální chybu sítě a na Android TV také
chybu překryvu najdeš na stavové obrazovce a v nabídce **Záznam**.
Běžná obrazovka ukazuje stručné shrnutí; technické podrobnosti patří do Záznamu.

Vyber LG webOS a projdi průvodcem. Jedno spárování slouží k instalaci, kontrole
verze, aktualizaci i zobrazení platnosti Developer Mode. Přibalený přijímač se
instaluje pouze tehdy, když v TV chybí nebo je starší. Novější se nepřepisuje.
Párovací klíč a údaj relace jsou uložené šifrovaně v Android zařízení; heslo LG ani
Passphrase se neukládají. Samotná instalace běží v místní síti, kontrola času
potřebuje spojení přímo se službou LG. V detailu TV lze zapnout prodloužení při používání, pokud zbývá nejvýše týden.
Externí Android správce a počítačový postup zůstávají záložními možnostmi.

Po kontrole verze lze v LG průvodci samostatně ověřit běžící přijímač na konkrétní
TV. Ohlášení musí odpovídat jejímu ID i IP. Výsledek s časem potvrzuje dostupnost
přijímače v místní síti; neověřuje OCR, překlad ani viditelnost textu na obrazovce.
Pro místní test zobrazení použij v LG nabídce OK / Zpět → Zkušební titulek.
Pokud se LG neozve, samotný tento výsledek neurčuje příčinu ani nespouští reinstalaci.
Kontrola je dostupná i v posledním kroku průvodce pro známou LG; nemění vybraný
výstup, spárování ani nastavení. Souhrn kontroly je v nabídce Záznam telefonu.

## Sestavení Android APK s LG přijímačem

Vedle Android SDK a JDK potřebuješ Python 3.9+ a Node.js 20+ s npx.
`bash gradlew :app:assembleDebug` (pro vydání odpovídající podepsaný release build)
automaticky sestaví a zkontroluje LG IPK a přibalí jej s manifestem do APK.
CLI se při sestavení získává přes npx; koncový uživatel při místní instalaci
z APK nepotřebuje počítač ani internetové stahování balíčku.
Samostatný IPK a ZIP pro ruční instalaci vzniknou také ve `webos/dist/`;
Adam je může přiložit ke stejnému vydání jako APK. Podrobnosti: [LG build](webos/README.md).

## Samsung v Android aplikaci

Vyber Samsung (Tizen) a projdi průvodcem: zapnutí Developer Mode (průvodce ukáže adresu
pro pole Host PC IP), uložení přibaleného instalačního souboru a ověření přijímače.
Přibalený WGT je nepodepsaný, protože Samsung přijme jen balíček podepsaný certifikátem
účtu Samsung vystaveným pro konkrétní televizi; jeden podepsaný soubor pro všechny vyrobit nejde.
Přijímač hlásí svou verzi sám, průvodce podle ní řekne, zda je aktuální, starší, nebo novější
než přibalený. Úspěch se potvrzuje až ohlášením přijímače z televize.
Podrobnosti: [návod](tizen/NAVOD-SAMSUNG.md), [technický popis](tizen/INSTALACE-Z-ANDROIDU.md),
[sestavení přijímače](tizen/README.md). `bash gradlew :app:assembleDebug` přibalí WGT automaticky.
