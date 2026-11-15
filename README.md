# PlayCaption

© 2026 Adam Růžička. Licence GNU AGPLv3 (viz níže).

**České (a slovenské) titulky ke hrám na PS5 a Xboxu přímo na televizi.**

> Spojení s PS5 zajišťuje otevřená knihovna **[Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki)** (Florian Märkl a přispěvatelé, AGPLv3). Čtení titulků, překlad a zobrazení na televizi jsou vlastní práce PlayCaption, vyvíjená s pomocí AI. Podrobně v části [Poděkování](#poděkování-a-použitý-software).

PlayCaption čte anglické titulky ze hry, překládá je a zobrazuje česky přes obraz z HDMI. Hra běží normálně z konzole přes HDMI (plné rozlišení, HDR, zvuk). PlayCaption se ke konzoli připojuje přes Remote Play jen na pozadí, aby z obrazu mohl přečíst titulky. Na obrazovce z Remote Play nic není.

- Android aplikace funguje na **Android TV / Google TV** a na Android telefonech/tabletech.
- Pro **LG webOS** je dostupný experimentální přijímač titulků z Android telefonu/tabletu: [návod pro LG](webos/NAVOD-LG.md). Vyžaduje Developer Mode; ověřený model je LG C5. Samostatný překlad přímo v LG bez Android zařízení tato verze neumí.
- Pro **Samsung (Tizen)** je dostupný experimentální přijímač titulků z Android telefonu/tabletu: [návod pro Samsung](tizen/NAVOD-SAMSUNG.md). Vyžaduje Developer Mode a účet Samsung; instalaci zatím provádí externí nástroj, protože Samsung vyžaduje podpis pro konkrétní televizi.
- Projekt je ve stavu **beta**, sledujte [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases).

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

Podporu LG webOS a Příběhový režim napsal **Rudolf Nohal**.

## Zdrojový kód a převzaté části

- Zdrojový kód PlayCaption je v tomto repozitáři. Každá vydaná verze má svůj tag (např. `v0.9-beta`) a odpovídající zdrojový kód je u ní v [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases).
- `app/src/main/jniLibs/*/libchiaki-jni.so`: **nezměněná** nativní knihovna z projektu **Chiaki 2.1.1** (© Florian Märkl a přispěvatelé, licence GNU AGPLv3), převzatá z Android sestavení [Chiaki-TV](https://github.com/505e06b2/Chiaki-TV). Zdrojový kód: [Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) (verze 2.1.1) a [Chiaki-TV](https://github.com/505e06b2/Chiaki-TV).
- `app/src/main/jniLibs/*/libchiaki-pc.so`: kopie výše uvedené `libchiaki-jni.so` s **jedinou změnou** – závislost `libmediandk.so` je přejmenovaná na `libpcobraz1.so` (stejně dlouhý název v tabulce dynamických řetězců). Používá se jen na telefonu.
- `app/src/main/jniLibs/*/libpcobraz1.so`: vlastní mezivrstva PlayCaption (AGPLv3) mezi Chiaki a systémovým dekodérem, předává obraz z PS5 přímo do paměti. Zdrojový kód: [`native/pcobraz.c`](native/pcobraz.c).
- `app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt`: převzato z Chiaki 2.1.1 (AGPLv3), upraveno (odstraněno Parcelable) – popis úprav je v hlavičce souboru.

## Vývoj a ověřování

- [Priority a technický backlog P0–P6](docs/PLAN-VYVOJE.md)
- [Výchozí audit větví, testů a měření](docs/P0-VYCHOZI-STAV.md)
- [Reprodukovatelný OCR benchmark](tools/ocr/README.md)

[Příběhový režim](docs/PRIBEHOVY-REZIM.md) je volitelný režim pro telefon/tablet (s LG přijímačem 0.2.1 nebo přímo na telefonu). Zapíná se na obrazovce překladu v telefonu/tabletu; výchozí Standardní režim zůstává zachovaný. Skládání rozdělených vět je zatím experimentální.

## Licence

PlayCaption je šířen pod licencí **GNU AGPLv3** (viz soubor [LICENSE](LICENSE)), stejně jako Chiaki. Kdo kód použije a šíří, musí zveřejnit i svůj zdrojový kód pod stejnou licencí.

PlayCaption je neoficiální projekt a nemá nic společného se společnostmi Sony Interactive Entertainment ani Microsoft. PlayStation, PS5 a Xbox jsou ochranné známky jejich vlastníků.

### Název a logo

Licence AGPLv3 se vztahuje na zdrojový kód, ne na název a logo. Název **PlayCaption** a logo jsou vyhrazeny autorovi (Adam Růžička). Upravené verze musí mít jiný název a nesmí působit jako oficiální PlayCaption (viz čl. 7 písm. e licence AGPLv3).

## LG v Android aplikaci

Vyber LG webOS a projdi průvodcem. Jedno spárování slouží k instalaci, kontrole
verze, aktualizaci i zobrazení platnosti Developer Mode. Přibalený přijímač se
instaluje pouze tehdy, když v TV chybí nebo je starší. Novější se nepřepisuje.
Párovací klíč a údaj relace jsou uložené šifrovaně v Android zařízení; heslo LG ani
Passphrase se neukládají. Samotná instalace běží v místní síti, kontrola času
potřebuje spojení přímo se službou LG. PlayCaption Developer Mode neprodlužuje.
Externí Android správce a počítačový postup zůstávají záložními možnostmi.

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
