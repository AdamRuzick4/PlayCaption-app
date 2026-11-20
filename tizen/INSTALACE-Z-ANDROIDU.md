# Samsung: inštalácia a pripojenie z Androidu (technický opis)

Stav k 15. 11. 2026. Tento dokument opisuje, čo je v Android aplikácii pre Samsung hotové,
čo zámerne nie a prečo. Je to obdoba [LG dokumentu](../webos/INSTALACE-Z-ANDROIDU.md),
ale mechanizmus Samsungu je iný a z LG sa nič nepreberalo naslepo.

## Zhrnutie

| Princíp | Stav |
| --- | --- |
| Samostatná implementácia, ktorá neovplyvní LG ani Android TV | hotové: `Samsung*.kt`, vlastné nastavenia `samsung_pruvodce`, žiadny zásah do `Lg*.kt` |
| Najprv zistiť skutočne nainštalovanú verziu | hotové pre bežiaci prijímač: verziu hlási sám v ohlásení (od 1.3.0) |
| Rovnakú verziu nepreinštalovať, novšiu neprepísať staršou | hotové ako rozhodnutie v sprievodcovi (`SamsungStavVerze`), inštaláciu samotnú robí externý nástroj |
| Úspech až po potvrdenej inštalácii a overení prijímača | hotové: úspech je iba ohlásenie prijímača z televízora, nie odoslanie súboru |
| Priama inštalácia ako hlavná cesta, ručný návod ako záloha | **nehotové**: priama inštalácia v tomto PR nie je, pozri nižšie |
| Dá sa šíriť jeden podpísaný WGT? | **nie**, pozri „Certifikáty“ |

## Certifikáty: prečo nejde jeden podpísaný WGT

Balíček pre Samsung TV nesie dva podpisy, autorský a distribučný. Distribučný certifikát
vystavuje Samsung na účet Samsung a obsahuje zoznam DUID televízorov, na ktoré sa smie inštalovať.
Televízor, ktorého DUID v certifikáte nie je, balíček odmietne chybou certifikátu (kód 118).
Všeobecný certifikát „Tizen“ (bez účtu Samsung) spotrebiteľské televízory Samsung podľa
komunitných zdrojov neprijímajú, na novších systémoch (Tizen 7 a vyššie) určite nie.

Dôsledky:

- LG správanie (jeden IPK pre všetkých) sa na Samsung preniesť nedá.
- APK preto pribaľuje **nepodpísaný** WGT. Manifest má `"signed": false` a `SamsungBalicek.over`
  podpísaný balíček odmietne, aby sa nikdy nevydával za univerzálny.
- Podpis musí vzniknúť u používateľa: prihlásenie účtom Samsung, vystavenie certifikátu
  s DUID jeho televízora, podpísanie súborov vo WGT.
- Aktualizácia podpísaná iným autorským certifikátom zlyhá („Author certificate not match“),
  kľúče sa teda musia uchovať, alebo treba starú inštaláciu odstrániť.

Zdroje sú na konci. Oficiálnu dokumentáciu Samsungu sa z vývojového prostredia tohto PR nepodarilo
otvoriť (doména nebola dostupná), tvrdenia vyššie vychádzajú z výsledkov vyhľadávania nad
oficiálnymi stránkami a z komunitných návodov. Pred implementáciou priamej inštalácie ich treba
overiť na skutočnom televízore.

## Balíček a zostavenie

`python3 tizen/build.py` (volá ho aj Gradle `preBuild` cez úlohu `prepareSamsungReceiver`):

1. overí zhodu verzie v `config.xml` a v službe,
2. overí, že spoločný kód (`protocol.js`, `prijimac-ui.js`) zodpovedá LG zdrojom (`tizen/sync.py --check`),
3. spustí `node --check` a skúšku `test/test.cjs` (služba cez skutočné UDP, vykreslenie v Chromiu, ak je nainštalovaný Playwright),
4. vyrobí `tizen/dist/PlayCaption-Samsung-<verzia>-nepodpisany.wgt` s pevným časom a poradím súborov,
   rovnaké zdroje dajú rovnaký SHA256,
5. zapíše `receiver-manifest.json`: ID, verzia, veľkosť, SHA256, protokoly, schopnosti, `signed: false`.

Do APK idú `assets/samsung/receiver.wgt` a `assets/samsung/receiver-manifest.json`.
Zostavenie potrebuje iba Python 3.9+ a Node.js 20+, žiadne Tizen Studio.

## Čo robí Android aplikácia

- `TvPlatforma.SAMSUNG` a `TvPlatforma.jenZobrazovac`: Samsung je rovnako ako LG iba zobrazovač,
  prevzatie prihlásenia ku konzole z televízora sa pri ňom neponúka.
- `SamsungPruvodce`: štyri kroky (čo treba, Developer Mode s adresou tohto zariadenia pre Host PC IP,
  inštalácia, spustenie a overenie).
- `SamsungExportActivity`: uloží pribalený WGT cez systémový dialóg (Storage Access Framework),
  bez oprávnenia k úložisku. Overuje rovnaký manifest a SHA256, sama nič neinštaluje.
- Overenie prijímača: `Propojeni.hledej` počúva ohlásenia `PLAYCAPTION_TV`. Samsung prijímač má ID
  `samsung-<12 hex>` (odtlačok názvu a MAC adries televízora, nie IP) a od 1.3.0 pridáva piate pole
  s verziou. Staršie Android verzie pole navyše ignorujú, staršie prijímače ho neposielajú
  a sprievodca ich označí ako „verziu nehlási“, nikdy nie ako aktuálne.
- Nájdené Samsung prijímače sa uložia ako profily s platformou Samsung. Výber televízora pri
  platforme Samsung ukazuje iba ID `samsung-…`, pri ostatných platformách ich skrýva. Správanie
  pre LG a Android TV ID sa nezmenilo.

## Príbehový režim

Prijímač 1.3.0 preberá protokol z PR #4 (`PCQ1`, `PCS1` s tokenom, `PCA1`). Stránkovanie robí
rovnaký kód ako v LG. Stav čítania ide z obrazovky do služby cez message port `PLAYCAPTION_SLUZBA`
a služba ho pošle telefónu ako `PCA1`. Ak sa port nepodarí otvoriť, služba na `PCQ1` neodpovie
a telefón sa správa ako pri prijímači bez Príbehu. Žiadosť o prevzatie prihlásenia
(`PCS1\tZADOST`, bez tokenu) sa naďalej odmieta odpoveďou „nespárované“.
Overené skúškou `test/test.cjs`, na skutočnom televízore zatiaľ nie.

## Priama inštalácia: čo chýba

Samsung nemá Key Server, SSH ani inštalačnú službu ako LG. Jeho cesta je:

1. **SDB** (Smart Development Bridge) na porte 26101, protokol podobný ADB. Televízor prijme
   spojenie len z adresy v poli Host PC IP.
2. Prečítanie **DUID** a zoznamu nainštalovaných aplikácií.
3. **Certifikát**: prihlásenie účtom Samsung v prehliadači, vystavenie autorského a distribučného
   certifikátu s DUID.
4. **Podpis** súborov vo WGT (`author-signature.xml`, `signature1.xml`).
5. Odoslanie súboru a inštalácia príkazom televízora, potom kontrola zoznamu aplikácií.

Nezávislé Android projekty ukazujú, že to bez počítača ide (vlastná implementácia SDB v Kotline,
príkaz `vd_appinstall`). Do tohto PR to nepatrí, lebo bez skutočného televízora sa nedá splniť
pravidlo „úspech až po potvrdenej inštalácii“ a kroky 3 a 4 pracujú s účtom a kľúčmi používateľa.

Navrhované poradie ďalšej práce, každý krok s prebierkou na televízore:

1. SDB iba na čítanie: DUID, model, nainštalovaná verzia aj pri vypnutom prijímači.
2. Odoslanie a inštalácia balíčka podpísaného inde (napríklad v Tizen Studiu), kontrola výsledku.
3. Vystavenie certifikátu a podpis v telefóne, kľúče šifrované cez Android Keystore ako pri LG.

Dovtedy je hlavná cesta externý inštalátor v telefóne a záloha Tizen Studio v počítači.

## Čo nie je overené

- Zmeny z tohto PR (prijímač 1.3.0, sprievodca, overenie verzie) neboli skúšané na skutočnom
  Samsung televízore. Pred vydaním treba prebierku na televízore.
- Android APK sa vo vývojovom prostredí nepodarilo zostaviť cez Gradle (chýba Android SDK).
  Kotlin zdroje prešli typovou kontrolou kompilátorom 1.9.24, zostávajúce chyby boli iba
  z nedostupných knižníc tretích strán. Nové JVM testy `SamsungPrijimacTest` a pôvodné
  `TvProfilyTest` prešli (12 testov).
- Externý Apps2Samsung s naším nepodpísaným WGT: používatelia potvrdili, že inštalácia prejde na
  Androide aj v počítači. Sami sme to na vlastnom televízore neskúšali.
- Externý TV Installer s naším nepodpísaným WGT: u časti používateľov sa nezobrazil obraz z HDMI,
  po prechode na Apps2Samsung sa to opravilo.
- Správanie message portu smerom do služby na starších Tizen verziách.

## Zdroje

- [Samsung: TV Device, Developer Mode](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html)
- [Samsung: Creating Certificates](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/creating-certificates.html)
- [Apps2Samsung FAQ](https://github.com/Apps2Samsung/Apps2Samsung/wiki/FAQ): účet Samsung od Tizen 7, otočená adresa Host PC IP, „Author certificate not match“
- [Apps2Samsung](https://github.com/Apps2Samsung/Apps2Samsung): inštalácia vlastného WGT z Androidu, Windowsu, macOS a Linuxu, certifikát s účtom Samsung a DUID
- [sunic007/tvinstaller](https://github.com/sunic007/tvinstaller): inštalácia WGT z Androidu s certifikátom z účtu Samsung
- [Readiz/tizen-youtube-installer](https://github.com/Readiz/tizen-youtube-installer): opis vlastného SDB v Kotline, `vd_appinstall`, certifikát viazaný na televízor
- [jellyfin-tizen, chyba 118](https://github.com/jellyfin/jellyfin-tizen/issues/10) a [jellyfin-tizen-builds #39](https://github.com/jeppevinkel/jellyfin-tizen-builds/issues/39): hotové balíčky treba podpísať vlastným certifikátom
