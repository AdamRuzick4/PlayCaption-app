# PlayCaption

**České (a slovenské) titulky ke hrám na PS5 přímo na televizi s Androidem.**

PlayCaption čte anglické titulky ze hry, překládá je a zobrazuje česky přes obraz z HDMI. Hra běží normálně z PS5 přes HDMI (plné rozlišení, HDR, zvuk). PlayCaption se k PS5 připojuje přes Remote Play jen na pozadí, aby z obrazu mohl přečíst titulky. Na obrazovce z Remote Play nic není.

- Funguje jen na televizích s **Androidem** (Android TV / Google TV), ne Samsung ani LG.
- Projekt je ve stavu **beta**, sledujte [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases).

## Stažení

Nejnovější verze: [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases), soubor `PlayCaption_beta_*.apk`.

## Poděkování a použitý software

PlayCaption stojí na práci jiných open-source projektů:

- **[Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki)** (Florian Märkl a přispěvatelé) a **[chiaki-ng](https://github.com/streetpea/chiaki-ng)**. Jejich knihovna (`libchiaki-jni.so`) zajišťuje veškerou komunikaci s PS5: přihlášení, párování, Remote Play a přenos obrazu. Licence **AGPLv3**. Velké díky autorům, bez nich by PlayCaption nevznikl.
- **Google ML Kit**: rozpoznání textu z obrazu.
- Překlad: online překladová služba.

Část PlayCaption (čtení titulků z obrazu, překlad, zobrazení přes HDMI, rozhraní pro TV a telefon) vzniká s pomocí AI a hodně testování na skutečných televizích a hrách.

## Zdrojový kód a převzaté části

- Zdrojový kód PlayCaption je v tomto repozitáři. Každá vydaná verze má svůj tag (např. `v0.9-beta`) a odpovídající zdrojový kód je u ní v [Releases](https://github.com/AdamRuzick4/PlayCaption-app/releases).
- `app/src/main/jniLibs/*/libchiaki-jni.so`: **nezměněná** nativní knihovna z projektu **Chiaki 2.1.1** (© Florian Märkl a přispěvatelé, licence GNU AGPLv3), převzatá z Android sestavení [Chiaki-TV](https://github.com/505e06b2/Chiaki-TV). Zdrojový kód: [Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) (verze 2.1.1) a [Chiaki-TV](https://github.com/505e06b2/Chiaki-TV).
- `app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt`: převzato z Chiaki 2.1.1 (AGPLv3), upraveno (odstraněno Parcelable) – popis úprav je v hlavičce souboru.

## Licence

PlayCaption je šířen pod licencí **GNU AGPLv3** (viz soubor [LICENSE](LICENSE)), stejně jako Chiaki. Kdo kód použije a šíří, musí zveřejnit i svůj zdrojový kód pod stejnou licencí.

PlayCaption je neoficiální projekt a nemá nic společného se společností Sony Interactive Entertainment. PlayStation a PS5 jsou ochranné známky jejich vlastníků.
