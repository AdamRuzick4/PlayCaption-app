# Kontrola LG přijímače před předáním

## Obnova LG příjmu – 18. 11. 2026 (nevydáno)

- `node webos/test-recovery.cjs` používá simulovaný čas: chyby create/bind/error/close,
  prodlevy 1–15 s, zánik odběru Luna bez callbacku, pozdní callbacky, výpadek sítě
  a změnu IP, zrušení všech opakování po zavření aplikace. Pro Story ověřuje stejnou
  stránku a zbývající čas, opakování done bez zobrazení a obnovení voleb se stejným ID.
- `node webos/test-udp.cjs` navíc skutečně obnoví socket po vložené chybě ENETDOWN
  a přes nový UDP socket přijme český titulek a potvrdí dotaz Story. Síť TV nesimuluje.
- Služba má samostatné opakování po 1/2/4/8/15 s, UI obnovuje odběr po 2/4/8/15 s.
  Pravidelná odpověď služby každé 2 s odlišuje živý odběr od ticha ve hře. Stávající
  desetisekundový limit nyní odhalí i pozdější zánik odběru; pětisekundový limit
  příchozích titulků zůstává. Jde o výchozí parametry pro fyzickou přejímku.

Fyzická LG přejímka zatím neproběhla. Před sloučením na konkrétní TV:

1. Ručně nainstalovat nově sestavené IPK. Verze přijímače se v této větvi nemění,
   průvodce proto již nainstalovanou stejnou verzi automaticky nepřepíše.
2. Ve Standardním režimu přerušit Wi-Fi/LAN TV a obnovit ji, potom opakovat se změnou
   IP. Ověřit návrat českých titulků bez ručního zavření přijímače.
3. Ve Story přerušit spojení uprostřed druhé stránky. Ověřit pokračování stejné
   stránky, opakované potvrzení dočtené repliky a funkční volby po návratu.
4. Ukončit/znovu spustit přijímací službu při otevřeném UI a prověřit obnovu odběru.
   Při úplném zavření aplikace se nesmí přijímač sám otevírat ani pokračovat ve čtení.
5. Nechat hru bez dialogu alespoň minutu: živá služba se nesmí zbytečně restartovat.
   Ověřit také Standardní režim po přepnutí ze Story.

## PC-001 – stabilizace víceřádkového OCR na Androidu, 16. 11. 2026

- Větev `codex/pc-001-multiline-text` z `origin/master` (`bc5237d`). Bez vydání
  a bez změny verze/protokolu LG nebo Samsung přijímače.
- Před opravou cílený běh `:app:testDebugUnitTest --tests cz.titulky.tv.StabilitaTextuTest
  --tests cz.titulky.tv.TextVRameckuTest` skončil dvěma selháními z 29 testů:
  opakovaný výpadek horních řádků nahradil celý odstavec posledním řádkem.
- Po opravě prošlo všech 202 JVM testů příkazem
  `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
  Sestavily se aplikační i instrumentační APK; instrumentační testy na zařízení
  spuštěny nebyly. Sestavení přijímače navíc spustilo čtyři Python testy IPK
  a šest Node sad LG (včetně skutečných UDP socketů).
- Nové testy pokrývají opakovaně chybějící horní/prostřední řádky, prázdné OCR,
  skutečné kratší znění při změně obrazu, zmizení, doplňování, opravy, reset
  oblasti a otisk všech pixelů včetně změn mimo rychlý vzorek. Test `VysilacTest`
  ověřil celý odstavec v reálném `PCV1` UDP paketu na loopbacku; překlad nahradila
  pevná česká odpověď, nejde o měření kvality Google překladu.
- Tablet ani Android emulátor nebyl dostupný. Nelze tím potvrdit původní scénu
  Alan Wake 2, výkon na Android TV ani chování na měnícím se pozadí. Při další
  herní přejímce použít postup a nové diagnostické počty řádků z `BACKLOG.md` PC-001.

## Přihlášení PSN v telefonu a zachování QR Android TV – 16. 11. 2026

- Samostatná větev `codex/psn-login-confirmation` z `origin/master` (`bc5237d`).
  Neobsahuje samostatnou opravu PC-001; nemění LG/Samsung protokol ani přijímače.
- Regresní test původního HTTP serveru nejprve selhal: stránka hlásila uložený
  účet hned po převzetí kódu, bez dokončeného ověření nebo zápisu.
- Nový společný stav `PsnPokus` potvrzuje úspěch až po validaci osmibajtového
  Account ID a úspěšném zápisu SharedPreferences na disk. Neúspěšné ověření
  nepřepisuje původní účet. Zrušený pokus ignoruje opožděnou odpověď; opakovaný
  kód se znovu nevyměňuje. Chyby nerozepisují autorizační kód ani token.
- Web přesměruje přijatý kód na `/stav`, při ověřování obnovuje stav a při chybě
  ponechá důvod i možnost vložit novou adresu. Server zůstává dostupný po návratu
  aplikace na párování, aby se výsledek mohl zobrazit také v prohlížeči.
- Prošlo 206 JVM testů, včetně 17 nových pro přihlášení. Síťové testy použily
  skutečné HTTP sockety na loopbacku i místní IPv4 adrese: čekání, úspěch,
  trvalá chyba, nový kód po odmítnutí, neplatný vstup, obsazený port a odlišné
  potvrzení „v televizi“ pro QR cestu. Odpovědi Sony i zápis účtu jsou v JVM
  testech nahrazené; testy se ke skutečnému PSN nepřihlašují.
- Příkaz `bash gradlew :app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest` sestavil aplikaci i instrumentační APK.
  `PsnQrTest` dekóduje QR bitmapu zpět na původní adresu Android TV; bez
  připojeného zařízení byl pouze sestaven, nikoli spuštěn. Živé přihlášení
  Sony, kamera telefonu a ovládání skutečné Android TV ještě vyžadují přejímku.

## Krátké dialogy ve verzi 0.0.7 – 15. 11. 2026

- Záznam z Redmi Pad Pro zachytil dvě chybné OCR varianty téže repliky v GTA V,
  které Android spojil jako pokračování. Ochrana proti tomuto případu je na větvi
  `bugfix/subtitle-cues`; 44 JVM testů prošlo a na tablet byla nainstalována
  `PlayCaption Test 0.20.1-cues-test01` se zachováním dat (`adb install -r`).
- LG přijímač nyní ukazuje dialog po nejvýše dvou řádcích. Nová replika zruší
  zbytek předchozí, heartbeat ani stejné znění s novým ID nevrací stránky na začátek.
  Platnost paketu dál omezuje celkovou dobu čtení; rychlá nová replika může
  přerušit nedočtené stránky. Text a nabídky odpovědí nejsou tímto limitem dotčené.
- C5 Web Inspector otestoval devět kombinací šířky (20/54/100 %) a velikosti
  písma (0.5/1.05/2.5). Obsah stránek dohromady odpovídal originálu, bez přetečení.
  Největší naměřená výška boxu byla 17.998 % obrazu.
- Skutečné UDP pakety v nainstalované aplikaci vystřídaly stránky a novou krátkou
  repliku; prázdný paket zobrazení ukončil. Nejvýše dva řádky, žádný návrat starého
  textu po nové replice. SHA256 všech 11 souborů UI a služby souhlasí se zdroji.
- Herní přejímka po opravě ještě zbývá. Špatně rozpoznaná slova z Remote Play
  tímto krokem obecně neopravujeme; nejde o důkaz kvality všech her a fontů.

## Ověření voleb a rámečků ve verzi 0.0.6

- Skutečné UDP pakety na C5 současně vykreslily titulky, volby a oba rámečky.
  Web Inspector naměřil modrou oblast `(0.1, 0.72, 0.8, 0.25)` a žlutou
  `(0.55, 0.45, 0.4, 0.35)` ve formátu x/y/šířka/výška, podle paketů.
- Český seznam byl vlevo vedle oblasti voleb, druhá odpověď byla zvýrazněná.
  Jména byla zelená a červená podle ARGB dat; zelený náhled měl přerušovaný obrys.
- Celý testovací odstavec v režimu Text odpovídal odeslanému obsahu. DOM potvrdil
  umístění uvnitř obrazovky a obsah bez oříznutí.
- Uživatel na tabletu potvrdil, že modrý i žlutý rámeček jsou na LG vidět
  a pohybují se podle tabletu. Jde o end-to-end test nastavování oblastí.
- SHA256 všech šesti souborů UI a pěti souborů služby na TV odpovídal aktuálním
  zdrojům; nejde pouze o kontrolu souboru APK/IPK v počítači.
- Skutečné herní odpovědi a úplnost jejich OCR/překladu zbývají k přejímce;
  syntetické UDP pakety je nenahrazují. Viz `SHODA-ANDROID.md`.

## Story MVP 0.0.8 a časování 0.0.9

- Přidán `test-story.cjs`: časování skutečných českých stránek, dočtení, pauza/obnova, opakovaná potvrzení a generační reset.
- Skutečný C5 test přes UDP a DOM: 3 stránky, celý obsah, nulové přetečení, potvrzení až po čtecím minimu; po zrušení fungoval Standardní titulek. Viz `docs/PRIBEHOVY-REZIM.md`.
- Nová herní přejímka a dlouhý běh ještě zbývají.

- LG 0.0.9: parser 9/10 polí, procenta 50–200, kratší výchozí základ a změna až od následující stránky prošly v `test-story.cjs`. Skutečná C5: stejný text 2252 ms při 100 % a 1932 ms při 80 %, následně funkční Standardní titulek. Podrobnosti v `docs/PRIBEHOVY-REZIM.md`.
## Samostatná LG větev 0.0.10

`feature/lg-webos-receiver` obsahuje původní LG přijímač, opravy HDMI/voleb/stránek
a přenesené úpravy vzhledu, discovery a instalace. Neobsahuje Android OCR opravy,
čtecí frontu ani Story protokol. Ty patří na `feature/story-mode`; později se do ní
může tato LG větev sloučit.

- Automatické kontroly: 4 LG sady, syntaxe JS, obsah IPK a build distribučních zdrojů.
- Testy pokrývají ikony 80×80/130×130, obecné hlášení zařízení, více TV/názvů,
  zachování identity při změně IP, jiné podsítě/masky a HDMI 1/3/4 bez pevného portu.
- Před oddělením větví byly společné LG úpravy ověřené na C5 v integrovaném buildu
  z commitu `fbd966e`: discovery odpovídalo názvu na TV, proběhlo přepnutí HDMI,
  zobrazení titulků/voleb/rámečků, výpadek/obnova a běh přes 150 s.
- Toto je důkaz integrovaného buildu, nikoliv nové hardwarové přejímky samostatné
  LG větve. Při opravě větví se instalace v TV/tabletu nemění. Pro vydání této
  samostatné varianty zopakovat přejímku přímo s jejím IPK.
- Mobilní instalace přes komunitního správce zůstává ručně neotestovaná; vlastní
  Android instalátor je pouze návrh. Další fyzické LG modely nejsou ověřené.

## Kandidát P0 0.1.0

15. 11. 2026, samostatná `feature/lg-webos-receiver`, bez Android/native změn:

- Původní 4 sady a nová `test-controls.cjs` prošly. Menu test pokrývá OK/šipky/
  Zpět, lokální test bez falešného připojení, oddělený příjem dat a titulku,
  skrytí bez oživení prošlého textu, chyby, HDMI, ukončení a nový start.
- Test služby pokrývá živého/odmítnutého/dalšího odesílatele a hranici 5 s.
  Nepodporované Story/účtové pakety neobsadí příjem, nevrací podporu a nebrání
  následnému standardnímu PCT1. Nový handshake se nepřidává.
- Build 0.1.0 má vlastní manifest ID/verze/schopností/velikosti/SHA256. Story je
  výslovně `false`; bundlovaný manifest nepředstírá verzi skutečně instalované TV.
- CLI metadata časů a místních UID/GID v IPK jsou normalizovaná. Dvě sestavení
  stejného zdroje vytvořila bitově totožný IPK; obsah se také porovnává se zdroji.
- Normalizovaný IPK se nainstaloval i znovu aktualizoval na C5 bez odinstalace.
  SHA256 všech 12 souborů na TV odpovídal finálním zdrojům.
- Skutečný lokální test na C5 nenastavil falešný síťový stav. Menu četlo verzi
  z místního appinfo, přepnutí HDMI obnovilo překryv. Skrytí/obnovení fungovalo.
- Dva reálné zdroje: UDP z Macu a z připojeného Androidu. Živý Mac nepustil druhý
  zdroj; po jeho výpadku Android převzal příjem; Mac se vrátil až po uvolnění
  Androidu. Není to autentizace ani rozlišení dvou procesů na jedné IP.
- Uživatel potvrdil fyzické OK → menu, šipky/OK → lokální test, Zpět → menu → hra.
  Následně potvrdil ukončení přes Ukončit PlayCaption. Přerušený první monitorovací
  běh byl ručním ukončením, nikoliv důkazem automatického pádu.

Kontrolovaný běh po opětovném spuštění: 0/30/60/90/120/150/180 s, překryv zůstal
aktivní bez hlášení odmítnutého keepalive.

Přejímka původního Android APK 0.20 (versionCode 31): uživatel neviděl překlad.
Na LG bylo během 6 s zachyceno 6 platných paketů titulku a 6 paketů voleb, všechny
s prázdným textem; maskování bylo vypnuté a blokace dalšího zdroje nebyla hlášená.
To potvrzuje přenos těchto starých formátů, nikoliv správnost OCR/překladu původního
APK. Uživatel následně potvrdil, že existující PlayCaption Test ve Standardním
režimu titulky zobrazuje; předchozí neúspěšný pokus v Test APK byl v režimu Story.
Android Test obsahuje samostatné opravy OCR/textu a ty se do LG větve nepřenášejí.

Tato záměna odhalila chybějící vysvětlení nepodporovaného režimu. Přijímač nyní
při platném Story dotazu lokálně upozorní na přepnutí do Standardního režimu;
nevrací žádné ready/ACK a nepřebírá zdroj. Rámečky ani keepalive hlášku neschovají;
podporovaný PCT1 ji zruší. Cizí dotaz nesmí přerušit živého vlastníka.
Na C5 byl z Androidu odeslán kontrolovaný PCQ1: hláška se skutečně zobrazila
a následný heartbeat běžícího Standardního režimu ji odstranil. SHA256 všech
12 instalovaných souborů po této úpravě opět odpovídal místním zdrojům.

Několikahodinové hraní, uspání/probuzení a změna DHCP adresy skutečné TV nebyly
v této etapě provedeny. Změna adresy/broadcastu a identity je pokrytá simulacemi;
nezaměňovat ji za zásah do domácí sítě. Samsung ani Android instalátor se netestují.
Vydání na GitHubu a merge do master nejsou automatickou součástí sestavení.

## Automatické kontroly

```sh
node webos/test.cjs
node webos/test-udp.cjs
node webos/test-input.cjs
node webos/test-receiver.cjs
node webos/test-story.cjs
node webos/test-controls.cjs
node webos/test-recovery.cjs
python3 webos/build.py
git diff --check
```

`test.cjs` testuje parser, Unicode a escapování řádků, neplatné/velké pakety,
číselné limity, dobu zobrazení, heartbeat, výpadek a obnovení přenosu, restart UI,
prodlužování překryvu, opožděné odpovědi a trvalé chybové hlášení. Síť a systémový
most LG zde nahrazují testovací objekty; není to důkaz kompatibility konkrétní TV.

`test-udp.cjs` spouští produkční službu se skutečnými UDP sockety. Pouze porty/cíle
přesměruje na loopback a náhrada Luna busu zachytí odpověď odběrateli. Ověřuje
discovery, přenos víceřádkového českého paketu a ukončení socketu/časovače po odhlášení.
Nezávisí na televizi, připojeném tabletu ani přístupu k lokální síti.

`test-input.cjs` ověřuje seznam HDMI, výběr jiného než pevně daného portu,
pořadí spuštění HDMI → overlay, validaci vstupu, souběžný požadavek, odmítnutí
API, timeout a opožděnou aktivitu. Testuje i výběr v UI, uloženou volbu, neplatné
odpovědi a obnovení bez opětovného otevření menu. Systémová API jsou zde náhrady.

`test-receiver.cjs` testuje PCV1/PCO1, limity a neplatné souřadnice, časování
voleb/textu/rámečků, zvýraznění odpovědi, barevná jména, souběžné kanály se stejným
ID a společný výpadek spojení. Také dvouřádkové stránky, úplnost obsahu, dlouhé slovo,
Unicode, opakované znění, nahrazení novou replikou a ukončení stránkování.

`build.py` znovu spustí všechny čtyři testy a kontrolu syntaxe, sestaví IPK oficiálním CLI
3.2.6 a porovná veškerý obsah IPK se zdroji. Odmítne soubory navíc či jinou verzi.
Vytvoří ZIP s návodem, licencí, odpovídajícími zdroji a SHA256SUMS; ověří CRC ZIPu.
Vyžaduje Python 3.9+, Node.js 20+ a internet pro stažení CLI přes npx.

## Dosavadní důkazy ze skutečné TV – 14. 11. 2026

| Oblast | Důkaz / omezení |
|---|---|
| Sestava | LG OLED65C5ELB, SDK 11.2.0, firmware 43.21.96; Redmi Pad Pro, PS5. |
| Překrytí HDMI | Uživatel potvrdil testovací text přes HDMI a následně skutečné titulky z tabletu. |
| Discovery a UDP | Na počítači přijato ohlášení LG; testovací paket prošel přes TV službu až do viditelného titulku v DOM aplikace. |
| Systémový limit | TV log zaznamenal skrytí overlay přesně 120 s po spuštění verze 0.0.2. |
| Oprava ve verzi 0.0.3 | Volání restartUserActivityTimer vrátilo returnValue=true. Web Inspector kontroloval běh v 0/30/60/90/125/150 s; ve 150 s byl viditelný přijatý titulek. |
| Kontrola verze 0.0.4 | Stejný IPK jako v distribučním ZIPu nainstalovaný na C5. Kontrola přes Web Inspector v 0/30/60/90/125/150 s: aplikace zůstala viditelná, bez hlášení o odmítnutém časovači. |
| Tři řádky ve verzi 0.0.4 | Syntetický český UDP paket se třemi řádky prošel přes službu do UI. DOM text přesně odpovídal paketu; výška boxu pokryla tři řádky a box zůstal v ploše obrazovky. Nejde o test OCR ani překladu ze hry. |
| Zavření a opětovné spuštění 0.0.4 | Po zavření aplikace přes CLI přestalo přicházet UDP discovery; po dalším spuštění opět přicházelo z C5. |
| Úplnost překladu | Uživatel dodal chybu víceřádkového textu; zatím nebyla lokalizovaná ani opravovaná, viz PC-001 v BACKLOG.md. |

Tato tabulka nedokazuje několikahodinový běh, všechny HDR/VRR režimy, jiné modely,
Xbox nebo distribuci přes LG Store. Výsledky starší verze se automaticky nepřenášejí
na novou verzi; u vydávaného IPK znovu zaznamenej provedené kroky níže.

## Ruční přejímka aktuálního IPK na TV

- [ ] Zapsat verzi LG přijímače, verzi Android APK, model/firmware LG a model Android zařízení.
- [ ] Nainstalovat IPK ze sestaveného ZIPu, otevřít na TV a vybrat HDMI přímo v aplikaci.
- [ ] Najít LG přes přepínač 📺 Televize na Android obrazovce s překladem.
- [ ] Porovnat stejný český text na tabletu a LG; vyzkoušet české znaky i více řádků v jednom paketu.
- [ ] Změnit polohu, šířku a velikost zeleného boxu; pozor na překrytí nápovědy.
- [ ] Nechat běžet alespoň 3 minuty bez LG ovladače a potvrdit, že přijímač stále běží.
- [ ] Přerušit odesílání z tabletu: starý text zmizí do přibližně 5 s. Obnovit přenos.
- [ ] Zpět na LG ovladači zavře aplikaci; po dalším spuštění funguje discovery i příjem.
- [ ] Ověřit obraz, zvuk a běžně používaný HDR/VRR režim; zaznamenat, který byl zkoušen.
- [ ] Před širším vydáním otestovat delší hraní a alespoň další kombinaci model/firmware.

Bez skutečné TV lze potvrdit balení a logiku, nikoli chování nedokumentovaného LG overlay.

## Ověření výběru HDMI ve verzi 0.0.5

- C5 vrátila seznam čtyř HDMI včetně názvů zařízení přes `getAllInputStatus`.
- UI zobrazilo načtené vstupy; výběr PS5 spustil službu a překryv byl obnoven
  s parametrem `resumeOverlay: true`. Web Inspector potvrdil zavřený výběr HDMI.
- Uživatel ovladačem vyzkoušel domeček → PlayCaption LG → HDMI s PS5 a potvrdil
  současný obraz PS5 i překryv. To ověřuje konkrétní postup, nikoli automatické
  obnovení po libovolné systémové změně vstupu.
- Stejné C5 odmítla webové aplikaci metodu `getForegroundAppInfo`.
- Delší provoz nové verze, jiná TV/firmware a všechny HDR/VRR kombinace zůstávají
  k ruční přejímce. Doklady běhu do 150 s výše se vztahují ke starším verzím.


## Android instalátor LG – 15. 11. 2026

Větev `feature/tv-platform-management`, Android `PlayCaption Profily Test`
`0.20.1-platforms-test06`, Redmi Pad Pro; přijímač 0.1.0 na LG C5.

- `:app:testDebugUnitTest :app:assembleDebug`: 20 JVM testů bez selhání.
  Nové testy zahrnují skutečný formát C5 subscription událostí bez `returnValue`
  a kompatibilitu `ssh-rsa` omezenou na konkrétní SSH relaci.
- Gradle vytváří a kontroluje LG IPK; sestavení přijímače spouští všech pět Node
  sad. IPK v APK má 31 060 bajtů a odpovídá SHA256 manifestu.
- Uživatel na C5 potvrdil úspěšnou instalaci/aktualizaci z tabletu v test06,
  správné potvrzení nainstalované verze a automatické spuštění přijímače.
  Passphrase zadával pouze na tabletu. Původní PlayCaption ani Story Test se neměnily.
- Při diagnostice byla zachycena skutečná posloupnost LG od `ipk verifying`
  přes `installing` do `installed`. Předchozí obecná chyba SSH odpovídala tomu,
  že Developer Mode na této C5 nabízí pouze `ssh-rsa`.
- Záložní ruční postup zůstává v instalačním kroku i uživatelském návodu.

Neověřeno: první instalace na čisté/jiné LG, všechny fyzické výpadky sítě
uprostřed instalace a chování všech podporovaných verzí Androidu.
Kontrola UI po uživatelském dokončení již instalační obrazovku nenašla;
není evidována jako úspěšný automatický test tlačítka záložního postupu.


### Záložní instalace a platnost – Android test07

- Na Redmi Pad Pro UIAutomator prošel přepnutí Android → počítač → Android.
  Sousední tlačítka mají fyzickou mezeru 24 px (12 dp); kontrolováno i screenshotem.
- „Uložit LG instalační soubor“ otevřelo systémový výběr dokumentu. Po potvrzení
  uložení do Stažených se průvodce obnovil. Exportovaný IPK měl 31 060 bajtů
  a SHA256 shodný s přibaleným manifestem.
- Další UI kontrola potvrdila, že export sám neoznačí TV za nainstalovanou;
  v přímé instalační cestě zůstává Další bez potvrzené instalace zakázané.
- Platnost Developer Mode je vysvětlená u kroků přípravy, instalace i spuštění.
  Nejde o odpočet skutečné TV ani automatické prodlužování. Text vychází
  z oficiální dokumentace LG, bez testování vypršení na C5.
- Android Aphanes 1.0.2 a Windows Desktop 1.0.0 jsou odkazy na oficiální vydání;
  samotná instalace přes externí aplikaci nebyla při této změně provedena.


### Zbývající čas Developer Mode – Android test09

- C5 vrátila přes kontrolní službu LG úspěch a čas `999:32:04`.
  Kontrolní nástroj držel session token pouze v paměti a nevypisoval jej.
- V Androidu uživatel provedl prvotní načtení z TV. Karta i detail zobrazily
  přibližně 41 dní a 15 hodin, datum ověření a ruční kontrolu.
- `:app:testDebugUnitTest :app:assembleDebug`: 26 JVM testů bez selhání.
  Nové případy: dlouhé hodiny, chybná odpověď není nula, stáří a posun hodin,
  hranice 72 hodin, AES-GCM vazba na konkrétní TV, změněný ciphertext a zrušené čtení.
- Po aktualizaci/restartu testovací APK zůstal čas zachovaný. UIAutomator pak
  provedl Zkontrolovat bez opakované Passphrase; datum v uloženém ověření se posunulo.
  První pokus UI kontroly nenašel kartu při přechodu obrazovky; opakovaný průchod
  na zobrazené hlavní kartě prošel. Karta i detail byly vizuálně zkontrolované.
- Kontrola app dat potvrdila jednu šifrovanou relaci a jeden veřejný časový záznam;
  citlivý obsah se nevypisoval. LG přijímač zůstal 0.1.0 bez změny zdrojů či instalace.

První nastavení času je samostatný jednorázový krok pro konkrétní TV, není ještě
navázané automaticky na instalátor. Bez automatického prodlužování, notifikací
na pozadí či simulování expirování skutečné TV. Další fyzická LG není ověřená.


### Společné spárování a ochrana verzí – Android test10

- První ověření s Passphrase na C5 vrátilo skutečnou verzi 0.1.0 a stav aktuálního
  přijímače. Uživatel výsledek potvrdil. Šifrovaný SSH záznam vznikl pro stejné
  stabilní ID jako discovery; v diagnostice se kontroloval jen počet a velikost obálky.
- UIAutomator provedl další ověření a spuštění bez Passphrase. Výsledek výslovně
  uváděl zachovanou verzi bez přeinstalování. `stat` instalačního `appinfo.json`
  před/po operaci potvrdil nezměněný čas modifikace.
- Po aktualizaci/restartu APK další ověření znovu vrátilo aktuální 0.1.0 bez
  opisování Passphrase. Obrazovka byla vizuálně zkontrolovaná; uložené spojení
  má vlastní vysvětlení a tlačítka jsou oddělená.
- `:app:testDebugUnitTest :app:assembleDebug`: 30 JVM testů bez selhání.
  Nové případy: číselné porovnání verzí (včetně 0.10 proti 0.9), stejná/novější
  verze, neznámé formáty, nezávislost identity TV na IP, odemknutý RSA klíč bez
  další Passphrase a oddělení šifrovaného SSH materiálu od session tokenu/jiné TV.
- Instalace i samostatné čtení relace sdílejí autentizované připojení. První
  spárování připravuje i kontrolu času; do LG přijímače se kvůli tomu nic nepřidává.

Nové větve chybějící/starší/novější verze jsou pokryté rozhodovacími testy;
na C5 se v této poslední změně záměrně neměnila verze ani neodinstalovával přijímač.
Dřívější skutečná instalace/aktualizace z Androidu je popsaná výše u test06.
Další fyzická LG, všechny výpadky sítě a původní Android TV hardware touto etapou
nebyly znovu přetestované. Žádné automatické prodlužování Developer Mode.

## Integrace Story se správou LG (0.2.0)

Větev `feature/story-mode` slučuje `feature/tv-platform-management` (`484ed6e`).
Historické záznamy 0.1.0 výše popisují samostatný přijímač bez Story; integrovaná
0.2.0 podporuje oba režimy. Android nadále vlastní jedinou čtecí frontu, LG jen
stránkuje aktuální repliku a potvrzuje její dočtení.

- Šest Node sad přes `python3 webos/build.py`: parser/UI, UDP, HDMI, stránky,
  ovládání i Story včetně procent. Nové regrese pokrývají zachování zdrojové
  relace pro Story, zneplatnění ACK po změně odesílatele a pozastavení při
  skrytí/menu/lokálním testu.
- Android `VysilacTest` zachovává generační ochranu při změně TV a ověřuje,
  že Story potlačí pouze standardní titulky; kanál voleb dál funguje a po
  vypnutí Story se obnoví PCT1 heartbeat.
- Tento záznam neprohlašuje nové hraní s integrovanou verzí za dokončené.


## LG 0.2.3 – vyhledání a start přijímače (2026-11-16)

- `:app:testDebugUnitTest :app:assembleDebug`: testy identity/platformy a zachování
  Android TV převzetí, skutečné UDP dotazy na známé LG, chybný/chybějící nonce,
  jiná identita, neodpovídající TV, původní Android broadcast a souběžné hledání.
- `python3 webos/build.py`: pozdější připojení sítě, změna IP, přímá odpověď,
  opožděný bind/error starého socketu, timeout startu a návrat do připraveného stavu;
  zůstávají testy Standardního režimu, Story, ovladače a přepnutí HDMI.
- Fyzická C5, SDK 11.2.0: instalace 0.2.3, přímé discovery se shodou ID/nonce,
  původní broadcast se stejným ID a dvě opětovná spuštění přijímače s odpovědí.
- Neověřeno: problémová B4, fyzická Android TV, vizuální titulky při hraní a
  skutečné odpojení/obnovení domácí sítě. Simulace není důkaz příčiny hlášené B4.
- Přímý dotaz je doplněk: nová TV nebo TV s jinou IP musí být nejdřív nalezena
  broadcastem. Neobchází se izolace klientů ani síťová pravidla routeru.


## Android odesílač pro LG při zhasnutém displeji

Větev `codex/lg-screen-off` ruší minutové ukončení pouze pro aktivní LG výstup
s konkrétní IP a profilem webOS. CPU zámek vlastní RemotePlayService; změna
výstupu, zastavení spojení i zánik služby ho uvolňují. Původní Android TV,
Samsung, místní výstup a neznámá platforma mají původní pravidla.

JVM `TelefonPozadiTest` ověřuje rozhodnutí pro LG, ostatní platformy a změnu
výstupu/stavu. Neověřuje skutečné chování Android napájení.

Fyzická přejímka zatím neproběhla (tablet není dostupný). Při nejbližším testu:

1. Připojit konzoli a vybrat LG; ověřit pohyb snímků, OCR a titulky na TV.
2. Ručně zamknout Android zařízení na alespoň dvě minuty. Ze záznamu ověřit,
   že snímky/OCR i odesílání pokračují; samostatně Standardní a Story.
3. Odemknout, změnit výstup na místní/Android TV a ověřit původní předávání.
4. Po Zastavit ověřit v `adb shell dumpsys power`, že nezůstává zámek
   `playcaption:lg-stream`. Prověřit i ukončení služby a opětovné připojení.

CPU zámek a foreground služba nejsou důkaz funkčnosti při všech úsporách energie,
Doze a zásazích výrobce telefonu. Bez fyzického testu netvrdit podporu zamčeného
telefonu za potvrzenou; nepřidávat kvůli tomu automaticky další oprávnění.
