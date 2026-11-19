# Changelog

## Nevydáno

- LG toast režim pro TV, které neudrží overlay přes externí HDMI vstup (ověřeno na LG UP81, webOS 6.5.3 „webOS TV Lite"). Když uživatel zvolí HDMI, UI zapne příkaz služby `toastMode`: služba se udrží aktivitou `com.webos.service.activitymanager` naživu i po zavření UI a příchozí titulek bez živého overlay-odběratele zobrazí jako systémový toast (`com.webos.notification/createToast`, stejný text se do 4 s neopakuje, předchozí toast se zkusí zavřít). Když overlay žije, toast se neposílá – režim je hybridní. Nový regresní test `test-toast.cjs`. Chování TV s funkčním overlayem přes HDMI (např. C5) se nemění.
- Android při aktivním výstupu do LG nepoužije minutové ukončení po zhasnutí displeje. Služba pro tento stream drží CPU zámek a uvolní jej při změně výstupu, zastavení a zániku; původní Android TV a ostatní režimy zachovávají dosavadní limit. Chování na zamčeném fyzickém zařízení ještě vyžaduje přejímku.
- README upřesňuje společný překladač a proměnlivé zpoždění Story i rozdíl HDMI signálu a interního vykreslování hry.


- Samsung (Tizen): přijímač titulků sloučen z bestranger/samsungappka do `tizen/`. Přijímač 1.3.0 podporuje Příběhový režim a hlásí svou verzi. Android: platforma Samsung, průvodce s Developer Mode, uložení přibaleného nepodepsaného WGT a ověření běžícího přijímače včetně verze. Přímá instalace z Androidu zatím není; důvody a další kroky jsou v `tizen/INSTALACE-Z-ANDROIDU.md`. LG ani Android TV cesta se nemění. Po sloučení s LG discovery: broadcast s verzí Samsungu se dál přijímá (nonce se kontroluje jen u přímé odpovědi) a Samsung, stejně jako LG, nevstupuje do převzetí přihlášení ke konzoli.

- LG přijímač 0.2.3: discovery obnovuje síťové adresy při běhu, reaguje na přímý dotaz uložené TV a ignoruje opožděné události starého socketu. Android zachovává broadcast pro Android TV/starší LG a navíc ověřuje známé LG přes IP, ID a náhodný identifikátor dotazu. Převzetí přihlášení z Android TV nepouští LG do nepodporovaného postupu. LG UI hlásí chybějící síť a nepotvrzený start příjmu do 10 s. Fyzické ověření problémové B4 ještě čeká.

- Android LG instalátor: průvodce ukazuje kód, důvod a stav chyby vrácené TV, s možností text zkopírovat. Ukončené spojení a neplatná odpověď mají vlastní hlášky. Diagnostika vybírá jen omezená chybová pole, bez úplných odpovědí a přihlašovacích údajů.

- LG přijímač 0.2.2: balení IPK používá pevné datum 1. 1. 2000 místo roku 1970, které může webOS 5.x odmítat chybou `-5: ipk verified failed`. Build kontroluje data v obou TAR archivech; regresní testy hlídají obsah, oprávnění a opakovatelnost. Chování přijímače a protokoly se nemění; ověření na webOS 5.6.2 ještě čeká na uživatele.

## 0.21

- Story experiment: konzervativní skládání nedokončených anglických vět před překladem, ID/revize zdrojů, omezené čekání a oddělení následující věty. Volby a přetížená fronta čekání ukončí; Standardní režim a LG protokol zůstávají. Bez oprav jednotlivých slov a bez AI služby. Herní přejímka ještě probíhá.

- Story: od tří čekajících replik se časování automaticky exponenciálně zkracuje, nejvýše na polovinu uživatelské doby a ne pod podporovaných 50 %. Platí od další stránky, bez zahazování replik; po odbavení se vrací nastavená rychlost. Stav ukazuje aktivní dohánění. LG aktualizace není potřeba.


- Story: podle herní přejímky GTA V je základní čtecí doba o 20 % kratší; výchozích 100 % nyní odpovídá předchozím 80 %. Shodný výpočet Android/LG 0.2.1, Standardní režim beze změny.

- Integrační větev Story spojuje správu TV profilů, instalátor a Developer Mode s Android čtecí frontou. LG 0.2.0 podporuje Standardní i Příběhový režim; jeho nabídka, skrytí a lokální test pozastaví Story bez falešného dočtení. Master ani veřejné vydání se tím nemění.

### Změněno

- Příběhový režim má kratší výchozí časování při 100 %, nastavitelnou délku zobrazení 50–200 % a skutečná tlačítka Standardní/Příběhový. Procenta se ukládají na Androidu a řídí i LG přijímač 0.2.0; změna se použije od další stránky. Standardní režim zachovává dosavadní časování.

### Přidáno

- Samostatné jádro čtecí fronty Příběhového režimu s pořadím/revizemi replik, časováním českých stránek, pauzou a simulovanými regresními testy.

- Experimentální napojení Příběhového režimu na Android překladač a LG 0.0.8: volitelný přepínač, jeden finální překlad, pořadí replik, potvrzení dočtení poslední stránky, pauza pro volby a návrat do Standardního režimu.
- Android LG: společné šifrované spárování pro správu přijímače a platnost Developer Mode. Skutečná verze se kontroluje před instalací; stejný přijímač se nepřeinstaluje a novější se nevrací na starší.

- Android LG: skutečný zbývající čas Developer Mode pro konkrétní TV, datum ověření, ruční kontrola a upozornění pod tři dny. Šifrované uložení relace, označení staršího/chybového údaje; bez prodlužování Developer Mode a bez změny TV přijímače.

- Android LG průvodce: oddělená tlačítka, upozornění na platnost Developer Mode u přípravy i instalace, záložní správce Aphanes pro Android před počítačem a uložení přibaleného IPK do zvolené složky.

- Android: průvodce připojením LG po jednotlivých krocích, odkazy na LG Developer a instalační nástroje, návrat k rozpracovanému kroku a vyhledání přijímače na konci. Přímý instalátor přibaleného IPK přes Developer Mode/Key Server ověřuje přenos i nainstalovanou verzi; ruční postup zůstává zálohou.
- Android telefon/tablet: oddělená volba TV platformy a konkrétního přijímače, vlastní nastavení polohy a oblastí pro každou TV, zachování původního profilu při změně IP. Přepnutí výstupu vyčistí starý přijímač; jazyk a účet konzole zůstávají společné.
- LG dokumentace: odstraněn interní pracovní plán z repozitáře i distribučního ZIPu, upřesněno předávání instalačního IPK přes Releases.
- LG 0.1.0 (kandidát P0): nabídka přes OK/Zpět, lokální test vykreslení, dočasné skrytí a diagnostika známých stavů včetně upozornění na nepodporovaný Story režim. Jeden aktivní zdroj do 5 s bez jeho dat. Reprodukovatelný IPK a manifest verze, schopností, velikosti a SHA256; bez změny Android kódu nebo Story protokolu.

- LG přijímač: jednotný název PlayCaption LG, Android ikona v obou velikostech launcheru a hlášení pro obecné odesílající zařízení. HDMI a discovery ověřené i s jinými vstupy/podsítěmi; aktualizovaný návod včetně možnosti instalace IPK z Android správce bez PC.
