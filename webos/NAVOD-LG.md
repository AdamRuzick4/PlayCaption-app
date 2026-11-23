# PlayCaption na LG televizi – návod pro uživatele

Tato verze zobrazuje přeložené titulky přes HDMI obraz na LG webOS. Překlad obstarává
Android telefon nebo tablet. Nejde o aplikaci z LG Store; instalace používá Developer Mode.
Je to experimentální podpora, zatím ověřená na LG C5.

## Co potřebuješ

- LG TV s webOS a aplikací **Developer Mode** z obchodu LG Apps.
- **Android telefon nebo tablet**, na kterém už funguje PlayCaption a překlad hry.
  iPhone neumí spustit Android APK. Telefon/tablet nemusí mířit kamerou na televizi.
- V novější Android aplikaci je instalátor LG přímo v průvodci. První záloha je externí Android správce; počítač je další možnost.
- Konzoli zapojenou přes HDMI do LG a zařízení ve stejné domácí síti.
  LG může být připojená kabelem a odesílající zařízení přes Wi-Fi; nepoužívej oddělenou síť pro hosty.
- Pro ruční instalaci navíc potřebuješ soubor **cz.playcaption.overlaytest_…_all.ipk** z balíčku daného vydání. Pro přímou instalaci jej už obsahuje Android aplikace.

APK patří do Android zařízení, IPK patří do LG. Na LG se nepřihlašuješ k PSN.
Hra běží přes HDMI; Remote Play dodává kopii obrazu Android zařízení pro čtení titulků.

## 1. Připrav televizi

1. Založ si účet na [LG Developer](https://webostv.developer.lge.com/).
2. Na LG otevři obchod **LG Apps**, najdi a nainstaluj **Developer Mode** od LG.
3. Otevři ji a přihlas se účtem LG Developer.
4. Zapni **Dev Mode Status** a potvrď restart TV.
5. Po restartu znovu otevři Developer Mode a zapni **Key Server**.
6. Poznamenej si IP adresu TV z nastavení sítě a **Passphrase** z Developer Mode.
   Passphrase slouží ke spárování instalačního zařízení s TV; neposílej ji do veřejných komentářů.

Podrobný postup LG: [Developer Mode](https://webostv.developer.lge.com/develop/getting-started/developer-mode-app).

## 2. Nainstaluj přijímač

Vyber jednu cestu. LG příprava v kroku 1 je nutná pro obě. Obyčejné odeslání souboru přes Bluetooth ani otevření IPK v prohlížeči TV aplikaci nenainstaluje.

### A. Přímo z PlayCaption, bez počítače

1. V Android PlayCaption otevři **Výstup titulků → Změnit**, vyber platformu
   **LG webOS** a **Jak připojit televizi**.
2. Projdi přípravu do kroku **Nainstaluj PlayCaption LG**. Zadej IP adresu TV
   bez portu. Při prvním použití zapni na TV Key Server a opiš Passphrase
   (6 znaků, rozlišuje velká a malá písmena). Nezadávej heslo účtu LG.
3. Stiskni **Ověřit TV a verzi přijímače**. Aplikace bezpečně uloží spárování,
   zjistí skutečnou verzi v této TV a připraví kontrolu zbývajícího času Developer Mode.
   Pro kontrolu času je potřeba internet; instalace z přibaleného IPK jej nepotřebuje.
4. Podle výsledku použij **Nainstalovat PlayCaption LG**, **Aktualizovat přijímač**
   nebo **Spustit přijímač bez přeinstalování**. Stejná verze se znovu neinstaluje
   a novější přijímač se nepřepíše starším. Před instalací se verze kontroluje znovu.
5. Nech TV i Android zařízení zapnuté a počkej na potvrzení výsledku.
   Vyber na TV HDMI konzole a pokračuj v průvodci vyhledáním televize.
   Pokud se přijímač nespustí, otevři **PlayCaption LG** ovladačem.

Při dalším použití stejné TV nech Passphrase prázdnou: použije se uložené
spárování a Key Server už není potřeba. Je-li klíč po resetu či novém Developer
Mode nepoužitelný, zapni znovu Key Server a zadej Passphrase. Případnou změnu
identity potvrď pouze po kontrole, že jde opravdu o tvoji televizi.

Instalátor nemaže původní přijímač před aktualizací a neukládá Passphrase.
Při chybě postupuj podle konkrétní zprávy; Key Server je nutný při prvním nebo novém párování.
**Zrušit čekání** nemusí zastavit instalaci, kterou už LG zahájila.
Pokud přímá instalace nejde, tlačítko
**Další možnosti instalace** otevře záložní postup: nejprve externí Android správce, potom volitelně počítač.

### B. Externí Android správce – první záložní možnost

1. V PlayCaption otevři **Další možnosti instalace** a **Uložit LG instalační
   soubor (.ipk)**. V systémovém dialogu zvol místní složku, například Stažené.
   Ukládá se přibalený a kontrolovaný přijímač; nemusíš čekat na samostatný IPK v Releases.
2. Nainstaluj [webOS Dev Mode Manager pro Android (Aphanes 1.0.2)](https://github.com/symonxdd/aphanes/releases/tag/v1.0.2).
   Použij soubor `.apk` z oficiálního vydání; jde o samostatnou komunitní aplikaci.
3. V Aphanes přidej TV podle její IP a Passphrase z Developer Mode při zapnutém Key Serveru.
4. V části aplikací vyber instalaci místního `.ipk` a uložený soubor PlayCaption.
5. Po dokončení spusť **PlayCaption LG** ovladačem a vyber HDMI konzole.

Celý postup v této externí aplikaci zatím na naší C5 nebyl otestovaný. Export IPK v PlayCaption je samostatný
krok a neznamená, že už je přijímač v TV nainstalovaný.

### C. Z počítače, graficky bez příkazové řádky

1. Na Windows můžeš použít [Aphanes Desktop 1.0.0](https://github.com/symonxdd/aphanes/releases/tag/desktop-v1.0.0).
   Pro Windows, macOS i Linux je alternativou [webOS Dev Manager pro svůj počítač](https://github.com/webosbrew/dev-manager-desktop/releases/latest).
   Je to komunitní nástroj; pro tento postup nepotřebuješ příkazovou řádku ani root TV.
2. Ve zvoleném správci přidej televizi přes **Add Device**. Zvol připojení přes Developer Mode,
   zadej IP televize a Passphrase. Pokud nástroj vyžádá port a uživatele, použij port
   **9922** a uživatele **prisoner**. Nevol připojení jako root.
3. Po připojení v části s aplikacemi použij **Install** a vyber rozbalený soubor
   **cz.playcaption.overlaytest_…_all.ipk** z téhož balíčku.
4. Počkej na dokončení. V seznamu aplikací má přibýt **PlayCaption LG**.
   Pokud už máš náš původní HDMI test, stejný identifikátor zajistí jeho aktualizaci.

Pokud klíč nejde získat, ověř zapnutý **Key Server**, aktuální Passphrase a společnou síť.
Názvy ovládacích prvků Dev Manageru se mohou mezi verzemi mírně lišit.

## 3. Spusť hru a titulky

1. Na odesílajícím zařízení spusť Android PlayCaption, připoj konzoli a ověř,
   že překlad funguje přímo na tomto zařízení.
2. Na LG otevři domeček a spusť **PlayCaption LG**.
3. V aplikaci vyber HDMI s konzolí a potvrď OK. Seznam se načítá z TV včetně názvů
   zařízení; žádné číslo HDMI není nastavené natvrdo. Aplikace přepne vstup a obnoví
   překryv titulků nad hrou. Poslední volba je příště předvybraná.
4. Na LG se objeví „Čekám na zařízení“. Pokud se výběr HDMI nepodaří,
   aplikace zobrazí chybu. Tlačítko **Pokračovat nad aktuálním obrazem** použij pouze
   tehdy, když už je pod aplikací vidět správný obraz hry.
5. Na Android zařízení ukonči úvodní nastavení tlačítkem **Hotovo – hrát**.
6. Na obrazovce s překladem najdi **vpravo nahoře přepínač 📺 Televize / 📱 Telefon**.
   Klepni na **📺 Televize**. Pro seznam dostupných televizí toto tlačítko podrž
   a vyber **LG PlayCaption · identifikátor** shodný s kódem na obrazovce LG.
   Každá TV má vlastní kód, takže rozlišíš i více LG ve stejné síti.
   Pokud je v síti jediná televize, může se připojit sama.
7. Při dalším textu ve hře se mají české titulky zobrazit i přes HDMI obraz na LG.
   Poloha, šířka a velikost odpovídají nastavení zeleného boxu na Android zařízení.

**Tlačítko Vyhledat televizi v úvodním nastavení Android aplikace je jiná funkce:**
přebírá přihlášení z Android TV. Pro LG nepoužívej tento postup ani volbu
**+ Přidat Android zařízení**. Přihlášení ke konzoli už máš v tabletu/telefonu.

Android zařízení nech odemčené s otevřeným PlayCaption, ideálně na nabíječce.
Toto doporučení platí pro dosud ověřené hraní. Oprava minutového ukončení při
zamknutí telefonu s aktivním LG výstupem je zatím vývojová a čeká na fyzickou
přejímku; samotné automatické testy nepotvrzují běh při vypnutém displeji.
Počítač při hraní není potřeba. Pokud chceš změnit vstup, otevři znovu PlayCaption LG
a vyber ho uvnitř aplikace. Přepnutí přes systémové menu LG může překryv zavřít;
přijímač nesleduje všechny změny vstupů na pozadí.
Přijímač neposílá ovládací vstupy do konzole; hraješ dál svým ovladačem.

Ve Standardním režimu mají dialogy nejvýše dva řádky. Dlouhá replika se střídá po krátkých částech,
přednostně na konci věty. Nová replika nebo konec její platnosti ruší zbývající
stránky; historie se nehromadí. Velké písmo se omezí tak, aby box zabral nejvýše
18 % výšky obrazu. Dopisy v režimu Text a volby odpovědí mají vlastní zobrazení.

## Každé další hraní

1. Zapni konzoli a LG.
2. Spusť překlad na Android zařízení.
3. Na LG spusť **PlayCaption LG**, vyber HDMI konzole a na Android zařízení případně znovu vyber TV.
4. Po hraní stiskni **OK nebo Zpět → Ukončit PlayCaption**. Samotné Zpět během hry nyní otevře nabídku; Zpět v nabídce ji zavře a vrátí překryv.

Přijímač nemá automatické spuštění po zapnutí TV. Po instalaci se spouští ovladačem;
počítač při dalším hraní není potřeba.
Pokud na LG otevíráš Home nebo měníš aplikaci, může se překryv zavřít;
pak znovu otevři PlayCaption LG a vyber HDMI. Android přepínač neotevře vypnutý LG přijímač.

## Nabídka během hraní

Stiskni **OK nebo Zpět**. Šipkami vyber akci a potvrď OK:

- **Změnit HDMI** otevře původní výběr vstupu.
- **Zkušební titulek** na 5 s zobrazí lokální český vzorek. Ověřuje pouze zobrazení,
  nikoliv spojení, OCR nebo překlad. Příjem skutečných dat přitom pokračuje.
- **Dočasně skrýt titulky** skryje přijímané texty a rámečky. Čas Standardních
  titulků běží dál; po obnovení se nevrací dávno prošlý text.
- **Zpět ke hře** (nebo Zpět na ovladači) zavře nabídku.
- **Ukončit PlayCaption** zavře přijímač a zastaví jeho příjem.

Nahoře je stav, verze, stáří posledních dat/titulku a naposledy zvolený HDMI.
Příjem dat nepotvrzuje funkčnost OCR či konzole. Neznámá verze není nahrazena
odhadem. Výstraha o udržování překryvu zůstává viditelná.

Přijímač drží **jedno aktivní odesílající zařízení**. Další zdroj živé spojení
nepřebírá. Pro změnu zastav překlad na původním zařízení, počkej nejméně 5 s a
spusť nový zdroj. Tato ochrana pracuje s adresou v domácí síti; není to přihlášení
ani rozlišení dvou aplikací na stejném telefonu. Používej důvěryhodnou LAN.

## Developer Mode je potřeba prodlužovat

V aplikaci Developer Mode sleduj skutečnou zbývající dobu **Remain Session**.
Ještě **před vypršením** připoj TV k internetu a stiskni **EXTEND**. Po vypršení
už LG tuto relaci prodloužit nedovolí; je nutné znovu zapnout Developer Mode.
Prodloužení zobrazení titulků není prodloužením Developer Mode.
Pokud režim vyprší a deaktivuje se, LG vývojově nainstalované aplikace odstraní.
Pak znovu zapni Developer Mode a zopakuj instalaci IPK.
V novější Android aplikaci klepni na údaj **Developer Mode** na kartě vybrané
televize, případně otevři **Výstup titulků → Změnit → Platnost Developer Mode**.
Při prvním ověření přijímače v instalačním průvodci se kontrola času nastaví
na stejné TV automaticky. Pokud přijímač instaloval někdo jiný nebo používáš
starší spárování, zvol **Načíst údaj z TV**. U uloženého klíče může Passphrase
zůstat prázdná; jinak zapni na této TV Key Server a opiš ji. Nic se tím neinstaluje.
Tento údaj se uloží šifrovaně pomocí Android Keystore pro konkrétní TV.
Passphrase se neukládá. SSH klíč je uložený odděleně a rovněž šifrovaně,
aby jej bylo možné použít pro další ověření a aktualizace bez opisování Passphrase.

Potom lze čas ověřovat přes **Zkontrolovat** bez opakovaného opisování Passphrase.
Kontrola potřebuje internet: dotaz s údajem relace míří přímo na službu LG.
Při otevřené kartě se kontroluje nejvýše jednou za hodinu. U času se uvádí datum
posledního ověření; z něj se dopočítává přibližný zbytek. Údaj starší než 24 hodin,
chyba kontroly či změna hodin telefonu jsou označené jako starší stav.
Při méně než třech dnech se čerstvý údaj zvýrazní a připomene ruční EXTEND.
Po novém přihlášení do Developer Mode může být potřeba **Načíst údaj z TV** znovu.
PlayCaption Developer Mode neprodlužuje ani neposílá upozornění na pozadí.
Pravidla a odstranění aplikací při deaktivaci popisuje [oficiální LG návod](https://webostv.developer.lge.com/develop/getting-started/developer-mode-app).

Běžné aktualizace PlayCaption
instaluj přes Install novějšího IPK a přijímač znovu spusť.

## Když něco nefunguje

| Problém | Co zkontrolovat |
|---|---|
| LG upozorní, že nepodporuje Story | Na Androidu zvol Standardní režim. Samostatný LG přijímač 0.1.0 Story frontu nepodporuje; samotné vyšší číslo verze nestačí. |
| V seznamu je jen „Jen telefon“ | Na LG otevři PlayCaption LG a vyber HDMI. Pak na Android zařízení znovu podrž 📺 Televize. Ověř společnou síť bez izolace klientů. |
| Android chce přebírat přihlášení z TV | Jsi v jiném průvodci. Dej Zrušit → Hotovo – hrát → přepínač 📺 Televize vpravo nahoře. |
| Překlad chybí na zařízení i LG | Nejprve řeš čtení/překlad na Android zařízení; LG dostává až jeho hotový výsledek. |
| Na zařízení překlad je, na LG není | Ověř vybranou LG a spuštěný přijímač. Po výpadku delším než 5 s se na LG schová starý text. |
| Po přepnutí na HDMI zmizí přijímač i jeho nabídka | Nejdřív na TV vyber HDMI a počkej, až uvidíš hru. Nad tímto vstupem otevři PlayCaption LG rychlou volbou na ovladači a vyber **Pokračovat nad aktuálním obrazem**. Na telefonu vyber svou LG a ověř první skutečnou repliku. Tento postup pomohl na 65QNED913PA ([hlášení](https://github.com/AdamRuzick4/PlayCaption-app/issues/30)). Na OLED55B23LA čeká na ověření. |
| LG odmítá prodloužení překryvu nebo překryv asi po dvou minutách zmizí | V nabídce **OK / Zpět** zkontroluj verzi přijímače. Pokud používáš 0.1.0, zopakuj test s přijímačem z aktuálně testovaného nebo vydaného balíčku. Vrať se ke hře a sleduj, zda dorazí překlad z telefonu ihned a zda je další replika vidět i po třech minutách bez použití ovladače LG. Poznamenej čas zmizení, model, firmware a údaje **Poslední data / poslední titulek**. |
| Víceřádkový text skončí pouze posledním řádkem | Známý problém čekající na samostatné řešení. Porovnej text na Android zařízení a LG; neřeš ho opakovanou instalací přijímače. |

Pro nahlášení problému stačí model LG, verze firmwaru/webOS, verze obou aplikací,
model Android zařízení a rozdíl mezi tím, co zobrazí odesílající zařízení a LG. Neposílej PSN tokeny,
Passphrase ani soukromé klíče. Pokud posíláš fotku, stačí samotný text na obrazovce.

## Oblasti čtení a překlad odpovědí

- Zelená oblast nastavuje umístění českých dialogových titulků.
- Modrá oblast vybírá místo čtení původních titulků. Zapni ruční čtení;
  při přesouvání se na LG ukáže modrý rámeček.
- Žlutá oblast vybírá místo čtení odpovědí nebo dokumentu. Zapni ji a zvol
  **Volby odpovědí**, nebo **Text (dopis, SMS)**. Český text se zobrazí vedle
  zvolené oblasti; pokud vedle není místo, zůstane uvnitř ní.
- **Rámeček na TV: Ukazovat pořád** ponechá žlutý rámeček viditelný i bez
  přečtených odpovědí. Jinak svítí jen při nastavování.

## Rozsah LG přijímače

Umí titulky, nápovědu, polohu/velikost, dialogové volby, delší texty, náhledy
oblastí a barvy jmen. Podrobnosti a testy uvádí [matice funkcí](SHODA-ANDROID.md).
Ověřená kombinace je LG C5 + Redmi Pad Pro + PS5. Stejná verze webOS nezaručuje
kompatibilitu všech modelů; překryv i jeho udržování používají neoficiální rozhraní LG.
Xbox ani jiné LG modely zatím v této sestavě nebyly otestované.

Přijímač sám nečte HDMI a nepřekládá. UDP přenos původní aplikace nemá přihlašování,
proto používej důvěryhodnou domácí síť a nepřesměrovávej jeho porty na internet.

## Volitelný Příběhový režim (testovací MVP)

Vyžaduje LG přijímač 0.2.0 a odpovídající PlayCaption Test na Androidu. V Android obrazovce překladu klepni na tlačítko **Příběhový**. Pod tlačítky se objeví **Délka zobrazení**: výchozí 100 % už používá kratší výpočet, posuvník 50–200 % jej násobí. Nižší procenta zkracují zobrazení; změna platí od další stránky. Android řídí čtecí frontu, LG zobrazuje jedinou repliku po stránkách a potvrzuje dočtení. Tlačítkem **Standardní** se vrátíš do původního režimu. Při čekání na podporovaný LG přijímač zkontroluj verzi a spuštění nad HDMI přímo z aplikace. Jde o experimentální MVP k hernímu ověření.
## Novější Android aplikace s profily televizí

Pokud Android nabízí **Nastavení televize**, zvol zvlášť **Televizní platforma →
LG webOS** a potom **Vybrat / změnit televizi**. U staršího přijímače potvrď,
že vybraná TV patří k LG; její název ani vyšší číslo verze platformu nedokazují.
Dvě LG mohou mít vlastní nastavení polohy a oblastí. Vypnutá televize v seznamu
uložených profilů neslouží k připojení přes starou IP; spusť ji a hledej znovu.
Verze přijímače může být zatím neověřená. Tato nabídka sama IPK neinstaluje.

V Android aplikaci s průvodcem otevři **Výstup titulků → Změnit → Jak připojit
televizi** při zvolené platformě LG webOS. Příprava sítě, účet LG, Developer Mode,
přímá instalace se záložním ručním postupem i první spuštění jsou rozdělené do pěti kroků. **PlayCaption LG
už mám** přeskočí přípravu k vyhledání přijímače. Návod si pamatuje rozpracovaný
krok; jeho dokončení samo neprokazuje instalaci ani příjem titulků.
