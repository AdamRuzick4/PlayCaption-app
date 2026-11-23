# Příběhový režim: čtecí fronta

## Volba překladače a kontext – připravené, nevydané (18. 11. 2026)

V telefonu pod procenty Story je volba **Překladač**:

- **Automaticky** zachovává původní Google + místní zálohu. Standardní režim
  zůstává beze změny.
- **DeepL** používá vlastní API klíč uživatele, oficiální API Free/Pro podle klíče,
  zdroj EN a aktuálně vybraný cílový jazyk. Při chybě či vyčerpání limitu přejde
  na místní ML Kit; důvod je krátce viditelný ve stavu. Nezkouší následně jinou
  cloudovou službu a neprodlužuje dosavadní časový rozpočet Story.
- **Offline (ML Kit)** pro dialogy Story nevolá online překladač. Potřebuje předem
  stažený jazykový model. Volba platí i pro dočasný Standardní přenos, dokud TV
  nepotvrdí Story. Není to globální zákaz sítě: konzole, TV, stažení modelu a
  samostatně zapnuté překlady menu/nápověd mají své dosavadní cesty.

DeepL dostává pouze novou větu v `text` a krátkou historii v `context` podle
[oficiálního API](https://developers.deepl.com/api-reference/translate/request-translation).
Historie obsahuje nejvýše tři přijaté zdroje / 1600 znaků a po dvaceti sekundách
bez navazujícího zdroje vyprší. Jsou to výchozí experimentální meze; spolehlivou
automatickou detekci změny scény nemáme. Historie zachovává jména mluvčích,
OCR revize nahradí stejný zdroj a nevytvoří další repliku. Každý požadavek dostane
neměnný snímek kontextu před spuštěním asynchronního překladu. Kontext se neposílá
do čtecí fronty ani znovu nezobrazuje. Konzervativní skládání vět zůstává.

Paměť rozlišuje motor, cílový jazyk, nový text a u DeepL i kontext. Místní záloha
se neukládá pod klíčem DeepL. Změna motoru nebo klíče platí pro nově přijaté
repliky: rozpracovaná fronta doběhne s původní volbou a nezahodí se. Starý
výsledek nezaplní paměť nové konfigurace. Změna jazyka nebo relace resetuje
Story i kontext jako dosud; již rozečtený titulek se dodatečně nepřeklápí na
pozdní alternativu. Online větev má dál jeden aktivní požadavek, místní záloha
se spustí nejpozději po 2,5 s a původní celkový limit je 8 s.

Klíč je šifrovaný AES-GCM klíčem Android Keystore, nezapisuje se do běžného
nastavení, logů, URL ani paměťových klíčů překladu. Uložení neznamená ověření
účtu; první skutečný požadavek může odhalit neplatný klíč nebo kvótu. Po smazání
aktivního DeepL klíče se zvolí Offline. Zbytek účtu, PSN spárování a TV profilů
se nemění. Zadání jiného klíče zruší dočasné omezení předchozí konfigurace.

Automatické kontroly: `StoryKontextTest`, `DeepLPrekladTest`, `PrekladKlicTest`,
`PribehovaRelaceTest` a `PrekladPribehuTest` pokrývají oddělení kontextu, revize,
reset, pořadí, cache, protokol API Free/Pro, HTTP chyby/limity, neplatné odpovědi,
šifrování a zachování jednoho výsledku v časovém rozpočtu. HTTP testy používají
náhradu spojení; skutečný účet ani placená API volání nejsou potřeba. Připravený
instrumentační `StoryPrekladOvladaniTest` kontroluje uložení volby z dialogu.

Fyzická přejímka a kvalita češtiny zatím neověřené. Až budou zařízení a účet:
porovnat stejné dialogy v Automaticky/DeepL/Offline, význam a jména, ztráty či
zdvojení, prodlevu a kvótu. Vyzkoušet změnu motoru/jazyka za běhu, výpadek sítě,
nesprávný klíč, restart aplikace, smazání klíče a všechny tři TV přijímače.
Tato větev nemění jejich protokoly ani neobsahuje obnovu LG z PR #26. Gemini
zůstává případným dalším krokem po vyhodnocení DeepL; není zde implementovaný.

## Story pro Android TV přijímač – nevydané, čeká na TCL

Větev `codex/android-tv-story` navazuje na integraci #16/#17. Přidává plný
Story příjem pro režim **S telefonem**; OCR, překlad, skládání vět a jediná
čtecí fronta zůstávají v telefonu. Přímý režim **Jen televize** se nemění.

- Telefon zjišťuje podporu pomocí PCQ1 i u Android TV. Story zapne až po
  platném PCA1 z očekávané IP/portu pro aktuální token. Starší Android TV
  neodpoví a dál dostává PCT1. Je třeba aktualizovat telefon i TV.
- Přijímač rozliší PCS1 s platným Story tokenem od PCS1 ZADOST/KOD pro
  předání spárování. QR přihlášení, předání účtu a formáty LG/Samsung se nemění.
- Překryv pomocí Android StaticLayout změří nejvýše tři řádky podle skutečné
  šířky boxu a velikosti písma. Zachová pořadí, barvy jmen a zbytek textu;
  dlouhé slovo rozdělí bez roztržení Unicode code pointu. Nevytváří druhou frontu.
  Čtecí čas začíná až po předání stránky překryvu, nikoli před pomalým měřením.
- Opakovaný paket nerestartuje čtení. Změna procent platí od další stránky;
  změna boxu znovu zalomí aktuální stránku. Pauza, volby, skrytí mimo TV vstup,
  nedostupný překryv a pět sekund bez dat zastaví čtení se zbývajícím časem.
  Rozpoznání TV vstupu používá stávající oprávnění přístupu k využití.
- Překryv vrací reading/done; done vznikne až po čtecím čase poslední stránky.
  Síťové vlákno odešle ACK ze stejného soketu, kterým přijalo Story. UI nedělá
  UDP IO. Ztracené ACK se zopakuje při dalším paketu bez přehrání dočtené repliky.
- Prázdný Story paket zruší režim a dovolí znovu PCT1. Starší pořadí/generace
  a opožděné pakety nedávno ukončené relace text neobnoví. Zastavení přijímače
  odstraní časovač i vazbu ACK; po restartu se nedočtená replika čte znovu.

### Přejímka na TCL 65C805

1. Použít APK této větve na telefonu i TCL, zachovat data a spárování. Zapnout
   „S telefonem“, povolit překryv a ponechat HDMI konzole.
2. Ověřit Standardní titulky, pak Story: krátké i dlouhé repliky musí dojít
   celé ve správném pořadí, dlouhé po stránkách, bez restartů při heartbeat.
3. Vyzkoušet 50/80/100/200 %, změnu velikosti/šířky boxu během repliky,
   barvy jmen, volby odpovědí a návrat z Home na HDMI. Rozečtený text se nesmí
   potvrdit jako dočtený, když je skrytý.
4. Na chvíli odpojit síť; po návratu musí čtení pokračovat. Přepnout na
   Standardní, opět na Story, zastavit/spustit příjem i změnit cílovou TV.
5. Ověřit QR přihlášení a převzetí spárování stávajícím postupem. Starší
   Android přijímač má s novým telefonem dál zobrazovat Standardní PCT1.
6. Volitelný instrumentační `PribehAndroidPrekryvTest` spouštět mimo hraní:
   ověřuje skutečný UDP přijímač, překryv a ready/reading/pauzu/done/zrušení
   při zákazu sítě na hlavním vlákně. Vyžaduje
   povolený překryv a TV vstup nebo PlayCaption v popředí, jinak se přeskočí.

Automatické testy ani ACK nejsou důkazem fyzické viditelnosti na konkrétní TCL.
V této větvi se aplikace nevydává, netaguje a nemění číslo verze.

### Automatické ověření této větve

`bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`
prošlo: 225 JVM testů bez selhání, z toho 12 nových pro parser, jedinou čtenou
repliku, skutečné UDP ACK, bezpečné předání odpovědi ze zobrazovacího do síťového
vlákna, pauzy, procenta, Unicode, pomalé měření, staré generace a původní kanály.
Síťová přejímka podpory nyní zahrnuje také Android TV vedle LG/Samsungu.
Instrumentační APK s novým testem bylo pouze sestavené; ADB nevidí žádné zařízení.

Prošly také `node webos/test.cjs`, `node webos/test-udp.cjs`,
`node webos/test-story.cjs`, služba `node tizen/test/test.cjs` a `git diff --check`.
Samsung vykreslovací test se přeskočil kvůli chybějícímu Playwrightu.
LG/Samsung zdroje, kodeky, překladače, přihlašování a čísla verzí se nemění.

## Předchozí oprava fallbackem (#16) – 16. 11. 2026

- Větev `codex/story-receiver-capabilities` opravuje report z TCL 65C805 na
  masteru `bc5237d`: zapnutí Story přestalo posílat PCT1, ale Android přijímač
  PCS1 interpretuje jako předání spárování. Neznámý příkaz zahodil bez PCA1.
  PCV1 zůstal funkční, proto mohly fungovat volby, zatímco dialogy chyběly.
- Android TV v této předchozí opravě používá Standardní výstup PCT1. Je-li platforma známá, Story
  tlačítko se znepřístupní a telefon zobrazí vysvětlení. Uložená předvolba Story
  se nemaže. Toto není implementace Story protokolu na Android TV přijímači.
- LG a Samsung se ověřují neškodným PCQ1; platná PCA1 musí přijít z připojené
  adresy/portu a odpovídat aktuálnímu tokenu. Samsung 1.3.0 se tím nevylučuje.
  Přijímač bez potvrzení zůstane na Standardním výstupu. U známé Android TV
  se dotaz vůbec neposílá; neznámá platforma může podporu takto potvrdit.
- Přijetí PCA1 nezruší právě rozpracovaný Standardní překlad. Standardní přenos
  se potlačí až s první platnou Story stránkou; jeho starý text se odstraní
  z cache bez smazání nápovědy a voleb. Při přepnutí cíle/předvolby se stará
  fronta zruší. Ztráta jednotlivého ACK neodvolává již potvrzenou podporu.
- Původní protokol a přijímače se nemění. Pro aktuální rozšířené pakety s časováním
  nadále platí minimální podporované verze LG 0.2.1 a Samsung 1.3.0; samotné PCA1
  nenahrazuje aktualizaci starých experimentálních variant LG.
- Před opravou selhalo 5 z 8 cílených testů včetně skutečného UDP testu
  „Story + Android TV → PCT1“. Po opravě prošlo všech 199 JVM testů a sestavení
  aplikace i instrumentačního APK příkazem
  `bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
- Nové testy pokrývají Android fallback, LG/Samsung potvrzení, přijímač bez odpovědi,
  cizí port, starý token, párovací odpověď, změnu cíle, místní telefon a návrat
  na Standardní bez opakování starého textu. Používají skutečné UDP sockety.
- Prošly také `node tizen/test/test.cjs`, `node webos/test.cjs`,
  `node webos/test-udp.cjs` a `node webos/test-story.cjs`. Samsung služba skutečně
  odpověděla PCA1 ready/reading; její vykreslovací část se přeskočila, protože
  není instalovaný Playwright. Instrumentační APK se pouze sestavilo, fyzická
  TCL ani Samsung nebyly pro nové ověření dostupné. Release ani tag nevzniká.

## Původní integrační MVP

Větev `feature/story-mode`. Integrační MVP je napojené na Android překladač a LG přijímač 0.2.1. Standardní režim zůstává výchozí a zachovává dosavadní překladovou i zobrazovací cestu. Nový režim se zapíná v PlayCaption Test na obrazovce TelefonActivity.

`CteciFronta.kt` je čistý Kotlin bez Android závislostí, vláken, sítě a vlastních hodin. Volání serializuje jediný vlastník, Android. LG zde nemá vlastní příběhovou frontu.

## Vstupy a pravidla

| Volání | Odpovědnost |
| --- | --- |
| `zarad(puvodniCasMs)` | Jednou pro novou samostatnou repliku, ve zdrojovém pořadí **před spuštěním překladů**. Vrací token relace/pořadí/revize; při plné frontě `null`. |
| `oprav(token)` | Zneplatní čekající starou revizi, uvolní její stránky a zachová pořadí. Vrátí nový token; rozečtené nebo neplatné repliky odmítne `null`. |
| `priprav(token, stranky)` | Dodá finální české stránky. Výslovně rozlišuje přijetí, opakování, neplatný token, již připravený výsledek a překročený limit. |
| `zobraz(tedMs)` | Vrátí aktuální stránku s identitou, indexem a časem; nebo `null`, když není co číst / čtení stojí. Volat po změně vstupu i při dosažení `doMs`. |
| `pozastav(ano, tedMs)` | Schová výstup a uchová přesný zbývající čas. Příjem dalších replik/překladů pokračuje. Lze využít při volbách nebo odpojení výstupu. |
| `chyba(token)` | Explicitní konečné selhání dosud nepřipraveného překladu. Uvolní pořadí a zvýší počítadlo chyb; neodstraní už přijatý platný překlad. |
| `reset()` | Nová generace relace, prázdná fronta, ukončená pauza, nulová počítadla. Staré callbacky nelze znovu přijmout. |

Časy jsou milisekundy z jednoho monotónního zdroje, v běžícím Androidu například `SystemClock.elapsedRealtime()`. Reset nevrací hodiny zpět. `puvodniCasMs` se uchovává pro pozdější měření prodlevy; pořadí určuje registrace, nikoliv čas dokončení překladu.

Opakované OCR musí výrobce spojit se stejným tokenem; fronta sama nehádá identitu podle podobnosti textu. Dvě samostatně registrovaná „Ano.“ jsou dvě skutečné repliky. Režim `Text`, volby a OCR hypotézy nejsou automaticky další položky této fronty.

Překlady B/C mohou být připravené, zatímco se ještě překládá A. Zobrazí se až po A. Samostatná starší replika neztrácí platnost příchodem novější; odmítá se jen nesprávná relace/revize, odstraněný token nebo změna zmrazeného finálního výsledku.

Čekající revizi lze opravit i po jejím překladu, dokud se nezačala číst. Od první stránky je obsah celé repliky neměnný; pozdní offline/online náhled nesmí rozečtený text překlopit. Zde fronta přijímá pouze finální překlady, volbu finálního výsledku musí dodat P2-04.

## Stránky a čas

Android vlastní celou čtecí frontu a předává LG vždy jen jednu aktuální repliku. LG změří skutečné zalomení do dvouřádkového boxu a postupně vykreslí její stránky. Žádné další repliky nefrontuje. Android používá `zobraz(tedMs, externiDokonceni = true)` a čeká na potvrzení `potvrzeno(token)`; lokální odhad doby celé věty nemůže předčasně uvolnit další repliku. Potvrzení LG přichází až po čtecí době poslední stránky.

Každá stránka má dobu:

```text
ceil(max(1500 ms, ceil(počet Unicode code pointů po NFC × 1000 / 18)) × procenta × 80 / 10000)
```

Výchozí hodnota je **100 % nového kratšího základu**. Posuvník dovoluje 50–200 % po 5 %. Nižší procenta znamenají kratší zobrazení. Změna platí od další stránky; nerozběhne znovu aktuální stránku ani nezmění její zbývající čas při pauze. Po herní přejímce je základ od LG 0.2.1 ještě o 20 % kratší: nových 100 % odpovídá předchozím 80 %. Je to pracovní nastavení podle herní zpětné vazby, nikoliv tvrzení o standardu streamovacích služeb.

Původní text se nemění. NFC při počítání sjednotí například `č` a `c` + kombinující háček. Doplňkové Unicode znaky, například běžné emoji, se nepočítají jako dvě UTF-16 jednotky. Nejde o obecné počítání všech složených emoji jako jednoho grafému.

První volná připravená replika začne při prvním `zobraz()` ihned. Nové výsledky, duplicitní doručení ani změny původních anglických titulků automaticky nekrátí českou čtecí dobu. Pokud je volání pozdě, začne následující dosud neviděná stránka až nyní a dostane svůj celý čas; fronta ji nepřeskočí kvůli starému plánu. Poslední stránka má stejnou ochranu.

Po pauze zbývá přesně tolik času jako před ní. Pozastavení v okamžiku konce stránky nechá po návratu začít další stránku s celým časem. `dokoncenych` se zvýší až po doběhnutí poslední stránky; `pocet` zahrnuje čekající překlady, připravené i právě čtenou repliku. Počítadla se resetují s relací.

## Simulovaný rychlý rozhovor

Test `triRychleReplikySeZobraziJednouVPoradiADoctouVPauze`:

| Zdrojový příchod | Český dialog | Zobrazení |
| ---: | --- | --- |
| 0 ms | Trevor: Ahoj. | 0–1200 ms |
| 100 ms | Michael: Pojď. | 1200–2400 ms |
| 200 ms | Franklin: Jdu. | 2400–3600 ms |

Tři přijaté a dokončené repliky, žádné přeskočení nebo předčasné přerušení. V této simulaci čtecí prodleva je 0 / 1100 / 2200 ms a fronta se vyprázdní v 3600 ms. Není to naměřená latence při hraní.

Další test dokončí překlady v pořadí C → B → A. Fronta čeká na platné A a zachová původní pořadí. Jiný test pustí 1000 postupně čtených replik při kapacitě jediné položky; dokončené položky se odstraňují a jejich pozdní výsledky se odmítají.

## Limity bez tichého zahazování

Výchozí kapacita je 128 rozpracovaných replik a 8192 UTF-16 jednotek textu **na repliku v součtu všech jejích stránek**. Uchovávaný obsah je tedy omezený nejvýše 1 048 576 UTF-16 jednotkami plus režie seznamů a položek. Prázdné stránky jsou chybou volajícího. Limity nemají vliv na rychlost čtení již přijatého textu.

Limit textu je samostatný pro každou repliku. Společný rozpočet by mohl způsobit deadlock: B/C zaplní všechen textový prostor, opožděné A se nevejde a B/C se bez A nesmějí přečíst. Tento scénář má vlastní regresní test.

Při naplnění se nová položka nepřijme; při příliš dlouhém textu se vrátí `LIMIT_TEXTU`, nic se neořízne a dříve přijaté položky zůstanou zachované. **Napojení musí odmítnutí obsloužit viditelně nebo řízeným zpětným tlakem.** Nesmí takto odmítnutý obsah potichu zapomenout ani ho neomezeně přesunout do jiné fronty.

Fronta sama nemá překladový timeout a neposuzuje, že A je už „moc staré“. `PrekladPribehu` nyní poskytuje výrobu jednoho finálního výsledku: nejvýše jeden souběžný online požadavek bez síťové fronty a retry, po 2,5 s offline záloha, konečný limit 8 s. Obsazené online zpracování použije pro další repliku offline rovnou. Chyba offline modelu nezahodí dosud běžící online překlad. Pozdější úspěch nesmí podruhé změnit finální výsledek. Chyba je viditelná ve stavu Příběhového režimu a uvolní následující repliku; nepoužívá se anglický text vydávaný za český překlad. Standardní Google/Edge/MyMemory/offline fallback cesta zůstala zachovaná. Pro Story MVP se používá jeden Google pokus a lokální ML Kit.

## Ověření

```sh
bash gradlew :app:testDebugUnitTest --tests cz.titulky.tv.CteciFrontaTest
bash gradlew :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Testy používají JUnit a virtuální milisekundy bez `sleep`. Pokrývají krátké/dlouhé/Unicode věty, A/B/C, obrácené dokončení překladu, revize, stejné samostatné repliky, deduplikaci, celé stránky, zpožděné tick, pauzy, obnovení, reset relace, explicitní chyby, limity a neměnnost přijatých dat.

15. 11. 2026: **33 nových testů fronty a všech 44 původních JVM testů prošlo** (77 celkem, žádná chyba/selhání/přeskočení). `:app:assembleDebug` také prošlo; běh používal Java 21 a Android SDK 34.

Testy používají skutečný produkční adaptér `PribehovaRelace` a překladovou strategii s virtuálními hodinami. Testy UDP zahrnují skutečné loopback sockety a potvrzení od LG služby. Testy pauzy/obnovení samotného rendereru nenahrazují několikahodinové hraní.


## Zapnutí a návrat

Na Android tabletu otevři **PlayCaption Test**, připoj PS5/Xbox a vyber LG. Na obrazovce překladu jsou dvě tlačítka **Standardní** a **Příběhový**; vybrané je zelené. V Příběhovém režimu se pod nimi ukáže posuvník **Délka zobrazení: 100 %**. Po zapnutí se ukazuje velikost fronty nebo důvod čekání/chyby. Nastavení se ukládá, výchozí je Standardní.

Na LG spusť přijímač 0.2.1 a HDMI vyber přímo v něm. Starší LG přijímač zůstává kompatibilní se Standardním režimem. Pro Story s procenty aktualizuj obě aplikace; LG 0.0.8 nepodporuje nový paket s procenty. Při potížích přepni tlačítkem zpět na Standardní.

Přepnutí režimu, jazyka, zdrojové oblasti, cílové TV nebo relace konzole resetuje Story frontu a neplatné výsledky se již nezobrazí. Zmizení samotného anglického titulku frontu nemaže. Při aktivních dialogových volbách se pozastaví čtení aktuálního příběhového titulku. Syntetické rámečky a Text používají nadále svoje původní kanály.

V režimu jen telefonu Android časuje celé repliky; stávající TextView je přizpůsobí ploše. Toto MVP nepřidává telefonní dvouřádkový stránkovač ani přepínač do přímého Android TV režimu bez telefonu. Primární herní přejímka je Android tablet + LG C5.

## Přenos a obnova

- PCT1/PCV1/PCO1 a Standardní režim zůstávají stejné.
- PCQ1 obsahuje ID relace a ověří podporu Story přijímače.
- PCS1 obsahuje ID relace/repliky/revize, pauzu, celý text jediné aktuální repliky, layout, barvy jmen a volitelné desáté pole `D:100` pro délku zobrazení v procentech. LG 0.0.9 přijímá také staré devítipolové pakety a ponechává jim původní časování 14 znaků/s a minimum 1800 ms. Wire limit je 8192 bajtů; příliš velký paket je hlášená chyba výstupu, nikoliv úspěšné dočtení.
- PCA1 vrací pouze `ready`, `reading` nebo `done` se stejným ID. Socket je připojený na zvolenou TV a odpověď pro jiné aktuální ID ignoruje. Potvrzení dočtení starého tokenu neukončí novou repliku.
- LG služba vrací odpověď jen na adresu/port odesílatele právě přijatého Story paketu. Webové UI nemůže určit libovolný cíl odpovědi.
- Každý heartbeat opakuje aktuální stav; LG uchovává aktuální repliku, stránku a výsledek dočtení. Ztracené potvrzení se zopakuje bez návratu na první stránku.
- Změna generace obsahuje prázdný zrušovací paket. Opožděné pakety starší generace se ignorují. Při výpadku spojení se běžící LG čtení skryje a pozastaví; po návratu se obnoví jeho zbývající čas.
- Restart samotného UI může vyžadovat znovupřečtení aktuální repliky; nesmí ji chybně potvrdit jako dočtenou. Nejde o distribuovanou garanci přesně jednou přes úplné restarty zařízení.

## Aktuální důkazy na C5

15. 11. 2026, LG přijímač 0.0.8: skutečný UDP → JS služba → UI → ACK prošel. Testovací replika se rozdělila na **3 stránky**, všechny části dohromady odpovídaly celému vstupu. DOM neměřil žádné přetečení. Čtecí minimum podle stránek bylo **10 644 ms**, skutečné `done` dorazilo za **11 149 ms**.

Po zrušení Story se vykreslil Standardní PCT1 titulek. Následný starý Story paket jej nepřepsal. SHA-256 všech 11 nainstalovaných souborů na TV odpovídalo zdrojům. Lokální důkaz: `/private/tmp/playcaption-story-c5-proof.json` (není součást Git repozitáře).

Android PlayCaption Test byl aktualizovaný přes `adb install -r` pod původním testovacím ID `cz.titulky.tv.ocrfixes`; původní aplikace `cz.titulky.tv` se nepřepisovala. Podrobná herní přejímka úplnosti rychlých replik, návratu do Standardního režimu, dialogových voleb, dlouhé cutscény a delšího výpadku Wi-Fi ještě zbývá.


## Android runtime oprava story-test02

První herní pokus odhalil `NetworkOnMainThreadException`: konstrukce/otevření UDP socketu byla mimo worker. `PribehSpojeni` nyní vytváří, připojuje, obsluhuje i ruší socket na vlastním pracovním vlákně. Hlavní vlákno pouze mění opakovaný stav a požadavek ukončení.

94 JVM testů a všech pět LG testovacích sad prošlo. Na Redmi prošly také oba `PribehSitTest` instrumentační testy: otevření/odeslání/zrušení při `StrictMode.detectNetwork().penaltyDeathOnNetwork()` na hlavním vlákně a skutečný dotaz z tabletu na C5 s odpovědí `ready`. Opravená instalovaná verze je `0.20.1-story-test02`.

Uživatel následně při opětovném připojení PS5 a zapnutí Příběhového režimu potvrdil: chyba zmizela a titulky běží. Tím je potvrzený základní herní provoz opravené verze, nikoliv ještě úplnost všech replik, dlouhodobá stabilita nebo návrat do Standardního režimu při hraní.

Testy lze spustit v odděleném diagnostickém APK podle existujícího postupu; pro druhý test dodat argument `lgHost` s adresou vlastní TV. Pokud je cílem právě používaná PlayCaption Test, zachovat její ID/podpis a data, použít pouze `adb install -r`; samotný instrumentační běh neodinstalovává aplikaci. Bez `lgHost` se pouze volitelný test skutečné TV přeskočí.

## Kratší časování a ovládání story-test03

15. 11. 2026: na Redmi nainstalovaná `PlayCaption Test 0.20.1-story-test03`, na C5 LG 0.0.9. Nastavení po testech je 100 %. Prošlo **101 JVM testů**, všech **5 LG sad** a **3 instrumentační testy na tabletu** (síť se StrictMode, odpověď skutečné C5, skutečná tlačítka a ovládání procent včetně uložení a přepnutí režimu).

Přímý UDP → LG UI → ACK test stejného textu naměřil **2252 ms při 100 %** a **1932 ms při 80 %**. Rozdíl zahrnuje obnovování UI a UDP heartbeat, nejde o přesný časovač v prohlížeči. Po zrušení Story se zobrazil Standardní titulek. SHA256 nainstalovaného UI a služby se porovnal s lokálními zdroji. Herní komfort nového časování a delší provoz zbývají k přejímce.

## Integrace novější správy televizí

Sloučení `feature/tv-platform-management` (`484ed6e`) zachovává profily TV,
průvodce, přímou instalaci IPK, šifrované spárování i zbývající čas Developer Mode.
LG 0.2.0 spojuje místní ovládání 0.1.0 se Story protokolem a procenty 0.0.9;
instalátor proto nabídne aktualizaci 0.1.0. Čtecí fronta zůstává na Androidu.
Změna profilu TV zneplatní rozpracovanou Story relaci.

Kontrola integrovaného kódu: 132 JVM testů bez chyb, šest LG Node sad, build
Android APK, instrumentačního APK a LG IPK. Nové regrese pokrývají potlačení
PCT1 pouze při Story, pokračující PCV1, obnovu Standardu, zdrojovou relaci/ACK
a pozastavení skrytého Story při nabídce či lokálním testu. Toto není nová
herní přejímka na C5. Master, PR #3 ani veřejné vydání se tímto mergem nemění.

## Společná verze na tabletu a LG – 15. 11. 2026

Na Redmi je aktualizovaná existující `PlayCaption Profily Test`, verze
`0.20.1-story-platforms-test01`. Původní PlayCaption i předchozí oddělená
Story Test zůstaly zachované. Přes Android průvodce se uloženým spárováním
proběhla aktualizace LG 0.1.0 → 0.2.0; seznam skutečně instalovaných aplikací
na C5 potvrdil 0.2.0. Následná kontrola v průvodci zobrazila aktuální 0.2.0.
Pomocný UI test závěrečné instalační hlášky vypršel; samotná instalace a
následná kontrola verze jsou ověřené odděleně, nikoliv tímto neúspěšným testem.

- Dva instrumentační testy na Redmi prošly: skutečná tlačítka Standardní/
  Příběhový, ukládání procent a síťové otevření/odeslání/zrušení se StrictMode.
- Třetí instrumentační test potvrdil `PCQ1` → `PCA1 ready` přímo z Redmi do C5.
- Řízený test skutečného LG UI a UDP: Standardní titulek, volby, oba rámečky,
  výpadek/obnova, Story `reading`/`done` a návrat ke Standardnímu režimu prošly.
- Další řízený C5 test: čtyři stránky obsahovaly celý vstup ve správném pořadí
  a bez přetečení. Skrytí trvalo 3967 ms, během něj se nezměnila stránka ani
  nepřišlo `done`. Po obnovení se replika dočetla, následoval funkční PCT1.

Lokální důkazy jsou v `/private/tmp/playcaption-story-platforms-runtime.log`,
`playcaption-story-020-android-lg-protocol.log`, `playcaption-story-020-runtime-proof.json`
a `playcaption-story-020-pages.json`. Tyto místní soubory se nedistribuují.
Syntetická data neprokazují kvalitu herního OCR ani překladů. Nová herní
přejímka rychlých replik, skutečných voleb a delšího běhu je stále otevřená.

### Herní přejímka a nový výchozí základ

Uživatel potvrdil fungování společné verze na GTA V. Při sledování herního
seriálu fronta na 100 % rostla příliš; po snížení na 80 % bylo čtení v pořádku.
Na základě této přejímky používají Android i LG 0.2.1 o 20 % kratší základ.
Posuvník má nadále výchozích 100 %; ty odpovídají předchozím 80 %. Krátká
stránka má při 100 % 1200 ms, 180 znaků 8000 ms. Standardní režim a staré
devítipolové Story pakety zachovávají své časování. Dosavadní vlastní
procenta se automaticky nepřepisují. Nejde o garanci nulové fronty pro
libovolně rychlý dialog.

Ověření úpravy základu: 132 JVM testů, šest LG sad a sestavení APK/IPK prošly.
Na Redmi je `story-platforms-test02`, na C5 LG 0.2.1. V tomto testovacím
profilu se uložených 80 % změnilo na 100 %; ostatní uložené údaje zůstaly
beze změny. Uživatel následně potvrdil, že nové časování vyzkoušel při hraní a je v pořádku. Toto potvrzení se týká vyzkoušené hry a nastavení, nikoliv všech titulů nebo dlouhodobého výpadkového testu.

## Závěrečný stav pro kontrolu a sloučení

`codex/story-mode-integration` zahrnuje master `aba0735` včetně opravy cest
v ZIPu na Windows. Funkční Android/LG kód odpovídá uživatelem přijaté
verzi story-platforms-test02 / LG 0.2.1; závěr doplňuje testy a podklady.

Finálně prošlo 133 JVM testů, šest LG Node sad, osm Python testů skórování
a sestavení APK, instrumentačního APK a IPK. Simulace 16 replik po 100 ms
při výchozích 100 % zobrazuje a dokončuje všech 16 v pořadí, bez ztrát,
duplicit a předčasného přerušení. Maximum fronty je 15 včetně čtené repliky;
čekání P50/P95 je 7700/16500 ms, poslední replika skončí 17700 ms po
posledním příchodu. Tyto hodnoty ukazují chování při přetížení, ne naměřenou
herní latenci. Základní MVP je připravené ke kontrole a sloučení.

## Automatické dohánění rostoucí fronty

Následný delší provoz odhalil frontu 89 replik i při střídajících se titulcích.
Krátký osmivteřinový DOM záznam C5 potvrdil postup mezi replikami, bez
skrytého výstupu, otevřené nabídky nebo voleb. Tablet nebyl přes ADB dostupný;
přesný poměr přijatých OCR replik k dočteným tedy není změřený.

Android od tří čekajících replik (bez aktuálně čtené) násobí uživatelskou
délku faktorem `max(0.5, 0.85^(čekající − 2))`. Při nastavených 100 % jsou
efektivní procenta pro 3/4/5/6/7+ čekajících 85/72/61/52/50 %. Nejnižší
podporovaná hodnota zůstává 50 %, uložený posuvník se nemění. Pro lokální
výstup i LG se používá stejný výpočet. Rozčtená stránka ani pauza se
nerestartují; nové procento se použije až od další stránky. Stav uvádí
„doháním: … %“. Při méně než třech čekajících se vrací uživatelská hodnota.

Regrese kontrolují práh, exponenciální průběh, limit a návrat, pořadí všech
20 replik a nezkrácení rozečtené stránky; minutový simulovaný dialog
přijímá 60 replik po sekundě a fronta zůstává nejvýše šest položek.
Je to omezená adaptace, nikoliv garance bez zpoždění pro libovolný tok textu:
při trvalém přetížení nebo čekání na LG ACK může fronta dále růst.
Nic se automaticky nemaže a Standardní režim se nemění.

Oprava prošla 136 JVM testy a sestavením APK; na tablet byla nainstalovaná
`PlayCaption Profily Test 0.20.1-story-platforms-test03`, při zachovaném
Příběhovém režimu a 100 %. Uživatel po hraní potvrdil, že je chování
v pořádku a výrazně lepší. LG zůstává 0.2.1; používá dosavadní pole procent
v Story paketu a aktualizace přijímače není pro tuto opravu potřeba.

## Experiment: skládání vět před překladem (PC-007)

Nová větev `codex/story-sentence-buffer` vychází z masteru `1de9372` a obsahuje
přijatou opravu dohánění fronty z PR #5. Bez dalšího přepínače je skládání
aktivní pouze ve Story. Standardní cesta, překladové služby, čtecí fronta,
časování procent a LG protokol zůstávají zachované.

### Co je implementované

`SkladaniVet` je čistá Kotlin komponenta bez Androidu, sítě a vlastního
časovače. Hodiny dodává volající. Přijímá stabilizovaný zdroj s ID/revizí,
odlišuje jméno a barvu, drží jen neodeslaný konec věty a vydává výsledky
s přesnými rozsahy znaků původních zdrojů. V jednom zdroji může oddělit
konec předchozí věty od následující samostatné věty. Vlastní kapitolu
nevytváří ani žádné dříve přeložené části automaticky nepřidává.

Konzervativní signály jsou konstrukce končící slovesem + „such“, členem
nebo (při známém shodném mluvčím) vybranou konstrukcí s „to“. Další část
musí mít odpovídající začátek; známá změna jména/barvy, samostatný zájmenný
podmět nebo rozpoznaná pomocná slovesa spojení blokují. Neznámý mluvčí
neumožňuje obecné spojování vět končících „to“. Nejde o obecný syntaktický
parser a stejné jméno/barva není samo důkaz významové návaznosti.

„Get out of here“, „What are you doing“, „I don't know“ a „Come with me“
se pouštějí rovnou. Interpunkce respektuje běžné tituly/iniciály a čísla;
nejednoznačné zkratky se raději nerozdělí. Výpustka sama nezpůsobí čekání.

Výchozí experimentální limity:

- 2500 ms od prvního stabilního přijetí čekajícího konce. Revize ani stejné
  OCR čtení konec čekání neodsouvají; produkční kontrola běží po 100 ms.
- Po potvrzeném zmizení nejvýše dalších 600 ms, stále uvnitř celkových 2500 ms.
- Nejvýše tři vstupní repliky a 800 znaků čekajícího anglického textu.
  Dosažení limitu obsah uvolní, neořízne jej.
- Aktivní dialogová volba nebo alespoň tři položky existující čtecí fronty
  čekání vypnou a čekající text uvolní. Čtení během voleb zůstává pozastavené,
  OCR i překlad mohou pokračovat.

`PribehovaRelace.prijmiOcr` vytváří stabilní zdrojové ID jen pro událost nové
repliky a revizi pro opravu. Pořadí OCR práce brání pozdnímu starému callbacku
vložit další položku; metadata bloků ML Kit nejsou používána jako ID dialogu.
`krok` obsluhuje termín bez blokování, `zmizelo` přijímá pouze nestaré OCR
události a reset režimu/jazyka/oblasti/TV/konzole ruší i zásobník. Stav při
čekání uvádí „dokončuji větu“. Nemáme detektor dialogové změny scény:
`prerus` je hranice pro známou událost, test neprokazuje rozpoznání střihu.

Oprava celého dosud neodeslaného zdroje nahradí jeho čekající části.
Jednoduchá nesložená replika nadále využije původní revizi fronty, dokud se
nezačne číst; pozdní překlad staré revize se neuplatní. U složeného či již
částečně vydaného zdroje se nové přepisování nepřipouští, aby nevzniklo
zdvojení. Pokračování po timeoutu/předání zůstává oddělené. Již čtený text
se tímto experimentem nepřeklápí.

### Test-first a vyhodnocení

Nejdříve vzniklo 20 testů kontraktu; proti neimplementované komponentě všech
20 selhalo (`/private/tmp/playcaption-sentences-red.log`). Po implementaci
prochází 25 testů zásobníku a 17 testů skutečného Story adaptéru. Celkem
168 JVM testů, včetně původních OCR/Standardních/časovacích/síťových regresí.

Povinné scénáře 1–16 pokrývají: úplné věty s/bez tečky, dvě/tři části,
smíšený blok, rychlé samostatné věty, mluvčího, explicitní hranici scény,
OCR revizi/dopisování, nesouvisející další blok, timeout, volby, překlad
v běhu, pozdní pokračování po publikaci a starý OCR výsledek. Záporný
korpus navíc obsahuje devět dvojic, u kterých nesmí dojít ke spojení.

Na označeném syntetickém korpusu: tři správná spojení ze tří pozitivních
párů, nula chybných spojení v devíti záporných párech. Test zdrojových
rozsahů kontroluje úplnost a žádné dvojí spotřebování. Tři pozitivní páry
mají dodatečnou prodlevu 700 ms (zadaný odstup pokračování), uvedené běžné
samostatné repliky 0 ms a chybějící pokračování 2500 ms při přesném ticku.
Nejde o naměřenou přesnost nebo latenci při hraní.

Jednorázový skutečný dotaz na stejný Google endpoint jako aplikace:
„a downer. I'm going home.“ → „snižovač. Jdu domů.“;
„I'm drunk and you are such a downer.“ → „Já jsem opilý a ty jsi takový
hnusák.“; samostatné „I'm going home.“ → „Jdu domů.“. Celá věta v tomto
vzorku změnila význam slova na označení člověka, nezaručuje ale požadované
„kazisvět“. Samostatný začátek jednou vypršel na timeoutu. To je malý online
vzorek, nikoliv statistické hodnocení nebo test skutečného ML Kit fallbacku.
Výstup je lokálně v `/private/tmp/playcaption-sentences-translation-probe.json`.

### Co zbývá ověřit při hraní

Spolehlivost vstupního rozlišení nové repliky/opravy, skutečné odstupy částí,
špatná spojení při neznámém mluvčím a přínos českého překladu. Při pochybnosti
raději rozšířit záporné testy a snížit rozsah spojování než obecně prodlužovat
čekání. Žádná záruka, že libovolné tři části budou spojené; pomalé pokračování,
neznámé jméno, limit či přetížená fronta mohou zůstat oddělené.

### Nainstalovaný testovací build

Na Redmi je `PlayCaption Profily Test 0.20.1-sentences-test01` (stejný oddělený
applicationId jako profily/Story testy), LG zůstává 0.2.1. Instalace skončila
`Success` a Package Manager potvrdil verzi. Na tomto APK prošly tři existující
instrumentační testy: tlačítka/procenta, StrictMode síťové operace mimo hlavní
vlákno a skutečné LG `PCQ1`/`PCA1 ready`. Tyto tři testy nenahrazují nové
skládání vět při živém OCR; to je pokryté JVM simulacemi a čeká na hraní.
Sestavení APK a instrumentačního APK prošlo; přibalený LG IPK má verzi 0.2.1
se shodným SHA256. Výsledek je v `/private/tmp/playcaption-sentences-test-final.log`
a `/private/tmp/playcaption-sentences-device-tests.log`.

### Předání experimentu ke kontrole

Finální kontrola proti aktuálnímu masteru `1de9372` prošla znovu: 168 JVM testů,
sestavení APK a instrumentačního APK a `git diff --check`. PR #5 dosud není
sloučený; větev obsahuje jeho opravu fronty, kterou je vhodné sloučit první.

Uživatel při hraní zatím nepoznal, zda se části skutečně spojily. Bylo upřesněno,
že spojení probíhá v anglickém vstupu před překladem, zatímco LG výsledek stále
stránkuje. Jde o nejednoznačnou zpětnou vazbu, nikoliv potvrzení kvality spojování
v dané scéně. Tablet už při pokusu o získání záznamu nebyl dostupný přes ADB.
Herní účinek PC-007 tak zůstává neověřený; testovací verze není obecný jazykový
model a její úzká pravidla nepokrývají všechny rozdělené věty.
