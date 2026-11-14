# PlayCaption – co je v které verzi

Číslo verze se ukazuje v aplikaci vpravo nahoře (např. BETA 0.10) a v Záznamu (logu).
Od verze 0.10 se vyvíjí jen jedna aplikace (dřívější NextGen, větev `master`) – umí „Jen televize“ i „S telefonem“.
Při každé nové verzi se zvýší číslo v `app/build.gradle.kts` (versionCode + versionName) a sem se dopíše, co je nového.

## 0.20 (versionCode 31)
- PS5 na telefonu: obraz jde z dekodéru přímo do paměti (jako u Xboxu) – čtení titulků 20–40 ms místo ~80 ms. Televize beze změny.
- PS5 na telefonu: kodek H.265 (čistší obraz, ostřejší písmo) – automaticky, jen když ho telefon umí; jinak H.264.
- PS5: poškozený obraz po výpadku Wi-Fi se nečte (méně nesmyslů).
- Spojování rozdělených vět: jen když pokračování přijde hned; tři tečky zůstávají; useknutý titulek se po dočtení přeloží znovu; useknutý začátek („et“ místo „Let“) se přečte znovu.
- Titulky už nepřeblikávají (rychlý offline překlad jen když Google neodpoví do 0,15 s).
- Telefon: po „Připojit“ rámeček „Máš PlayCaption už přihlášenou v televizi?“ (Vyhledat televizi / Nemám televizi).
- Televize: nový návod „Titulky z telefonu“ pro obě cesty spárování telefonu.
- Telefon: Zpět aplikaci nezavře (spojení drží), displej nezhasne, když běží spojení; srozumitelné hlášení, když PS5 drží jiné zařízení.
- Záznam: přehled připojení a odpojení, delší výpis titulků.

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

### Beta 0.18 (versionCode 29)
- Oprava: televize už nespadne při předání PS5 telefonu („Translator has been closed“ – např. po přepnutí telefonu na foťák a zpět).
- Telefon se po 1 minutě odpojí od PS5 jen když je zamčený (zhasnutý displej). S otevřenou jinou aplikací (foťák – natáčení videa) překlad běží dál.
- Televize: v kartě „Připojit ke konzoli“ nové tlačítko „+ Přidat Android zařízení“ – okno s QR kódem ke stažení aplikace, 4místným kódem (platí 180 s, odpočet, „Nový kód“) a adresou televize. Po spárování okno samo zmizí.
- Dlaždice „Jen televize“: místo „Funguje každému“ teď „Slabší televize nemusí stíhat“.
- Slabá televize = do 2 GB paměti (dřív pod 1,8 GB) – ale jakmile televize při hraní 10× po sobě přečte titulek rychle, upozornění se už neukazuje. Upozornění má žlutočerné výstražné proužky, tlačítka „Zpět“ / „Pokračovat“.
- Upozornění „Televize má slabý výkon“ (jen na opravdu slabé televizi při volbě „Jen televize“) říká i to, že aplikace může přestat reagovat nebo se ukončit, a doporučuje Android telefon / tablet.
- „Menu a ovládací nápověda“ už nemá Ano/Ne (televize ani telefon) – menu, úkoly a dopisy si každý přeloží žlutým rámečkem v mobilní aplikaci.
- Když aplikace v režimu „Jen televize“ spadne, po dalším otevření se nepřipojí sama dokola: ukáže „⚠️ Aplikace minule spadla“ (výstražné proužky, Zpět / Pokračovat) a televizi si zapamatuje jako slabou.
- Uvítání je kratší a přátelštější („Ahoj a vítej v PlayCaption!“ – beta, zpětná vazba ve Facebook skupině) a při prvním spuštění se ukáže až po výběru jazyka, rovnou v tom jazyce. Nemizí samo – pokračuje se tlačítkem „OK, jdeme na to“.
- Televize: po kliknutí na „Připojit“ nejdřív čitelné upozornění, že s mobilní aplikací (Android telefon / tablet) se v televizi přihlašovat nemusí. Tlačítka „Zpět (budu používat mobilní aplikaci)“ → druhá tabulka „vyber dole dlaždici S telefonem“ (potvrdit OK), a „OK (přihlásit televizi)“.
- Telefon: tlačítko „📺 Vyhledat televizi“ – seznam nalezených televizí, klepnout na svoji a opsat kód. Nenajde-li televizi: „Zkusit znovu“ nebo „Zadat adresu televize“.
- Nespárovaný telefon, který v síti najde SPÁROVANOU televizi s PlayCaption, schová přihlašování („Připojit“) – stačí opsat kód z televize. Nespárovaná televize nevadí: telefon se spáruje s PS5 sám a televize jen ukazuje titulky.

### Beta 0.17 (versionCode 28)
- Režim s telefonem: jméno postavy má na televizi (i v telefonu) barvu jako ve hře – stejně jako v režimu Jen televize („Vivian:“ zeleně). Se starší verzí na druhém zařízení je jméno prostě bílé.

### Beta 0.16 (versionCode 27)
- Navazuje na 0.14 (zase jedna apka pro televize i telefony).
- Titulky jen v telefonu (📱 Telefon): telefon jde otočit na šířku → černá obrazovka jen s velkými titulky přes celou šířku a malou zelenou tečkou „Titulky aktivní“. Obrazovka s posuvníky (📺 Televize) zůstává jen na výšku.

### Beta 0.15 (versionCode 26) – jen pro test na Philipsu, odložená ve složce `NextUpdate_015`
- Dvě apky: TV (navíc „Zapnout přes Usnadnění“ pro televize se schovaným povolením) a Telefon (bez Usnadnění).

### Beta 0.14 (versionCode 25)
- Odebráno „Zapnout přes Usnadnění“ – telefony Xiaomi (HyperOS) kvůli němu verzi 0.13 odmítly nainstalovat („Aplikaci nelze nainstalovat“). Jinak stejné jako 0.13.

### Beta 0.13 (versionCode 24)
- Tlačítko „Povolit zobrazení přes jiné aplikace“ otevře nastavení vždy, když to jde (stránka povolení → seznam aplikací → nastavení PlayCaption); návod až když televize nastavení nemá.
- Zapnutí přes Usnadnění (pro televize se schovaným povolením) – v 0.14 zase odebráno.

### Beta 0.12 (versionCode 23)
- Už by nemělo být potřeba „Vynutit ukončení“: ukončování starého spojení běží zvlášť a nezablokuje hledání PS5; když PS5 v síti není vidět, aplikace se zkusí připojit přímo.
- PS5 se hledá i přímo na známé adrese – na sítích, kde hromadné hledání neprojde (oddělená 2,4/5 GHz Wi-Fi), už není potřeba pořád znovu párovat.
- Žlutý rámeček má nový režim „📄 Text“: přeloží celý text v rámečku najednou (dopis, SMS, popis úkolu) a ukáže ho na televizi jedním oknem.
- Volby odpovědí (žlutá tabulka) se ukážou jen, když hra opravdu ukazuje zvýrazněnou volbu – loga a nápisy (např. na načítací obrazovce) už na televizi nezůstanou viset.
- Při tažení rámečků v mini televizi se už nehýbe celá stránka.
- Visící staré spojení s PS5 (po výpadku Wi-Fi, zhasnutí telefonu…) se před novým připojením vždy ukončí – méně chyb „already in use“.

### Beta 0.11 (versionCode 22)
- Telefon může převzít spárování s PS5 od televize: na televizi „S telefonem“, v telefonu „📺 Převzít spárování z televize“ a opsat 4místný kód z televize. Bez přihlašování účtu a PINu z PS5.

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
