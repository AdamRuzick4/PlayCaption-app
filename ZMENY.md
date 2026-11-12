# PlayCaption – co je v které verzi

Číslo verze se ukazuje v aplikaci vpravo nahoře (např. BETA 0.10) a v Záznamu (logu).
Od verze 0.10 se vyvíjí jen jedna aplikace (dřívější NextGen, větev `master`) – umí „Jen televize“ i „S telefonem“.
Při každé nové verzi se zvýší číslo v `app/build.gradle.kts` (versionCode + versionName) a sem se dopíše, co je nového.

## Stará beta (jen televize) – větev `beta-0.9` (už se nevyvíjí)

### 0.9.2 (versionCode 12)
- Lepší čtení: zkomolené slovo z rozmazaného snímku se nepřekládá hned, počká se na ostřejší snímek; úplný nesmysl se na televizi neukáže.
- Slabší televize: čtení titulků běží „v pozadí“ (spojení s PS5 má přednost), úsporný režim se zapne hned po prvním pomalém čtení nebo výpadku.
- Po výpadku obrazu delší pauza (8 s) před novým připojením – méně chyb „already in use“.
- Srozumitelné české zprávy při odpojení („připojím se znovu sám, nic nedělej“).

### 0.9.1 (versionCode 11)
- Televize, které nedávají obraz (Philips, Xiaomi): po 15 s se aplikace sama přepne na jiný způsob čtení obrazu (OpenGL).
- Bez povolení „Zobrazovat přes jiné aplikace“ už aplikace nespadne – ukáže návod.
- Pojistka proti opakovanému pádu po zapnutí televize.
- Poděkování projektu Chiaki / chiaki-ng v aplikaci.

### 0.9 (versionCode 10)
- První veřejná beta.

## Hlavní verze (televize + telefon) – větev `master`

### Beta 0.10 (versionCode 21) – dřívější NextGen
- Nově jedna aplikace pro všechny: „Jen televize“ i „S telefonem“.
- Vše z bety 0.9.2 (lepší čtení, slabší televize, české zprávy).
- Telefon dostává z PS5 ostřejší obraz (720p, víc dat) – přesnější čtení v akčních scénách.
- Přepínač v telefonu [📺 Televize | 📱 Telefon] – kde se ukazují titulky, jedním klepnutím.
- Modrý rámeček „Čtení titulků: RUČNĚ“ – čte se jen tam, kde jsou titulky; posuvníky šířky a výšky.
- Zámečky u zeleného, modrého i žlutého rámečku (zamčený rámeček nejde posunout, posuvníky se schovají).
- Šedá nápověda pod tlačítky; v režimu televize jen malý zelený řádek nahoře, nastavení jde posouvat.
- Nic nezamrzne na televizi: po uspání PS5 telefon smaže nápovědu i volby; pojistky 20 s / 45 s / 5 s.
- Otočení telefonu na bok už nepřeruší posílání do televize (aplikace jen na výšku).
- Slabá televize: aplikace doporučí režim s telefonem a upozorní, když člověk zvolí jen televizi.

### NextGen 0.1 (versionCode 20, jen testovací)
- Režim „S telefonem“, volby odpovědí (žlutý rámeček), úpravy polohy titulků z telefonu.
