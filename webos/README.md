# PlayCaption LG – podklady pro vývoj a předání

Experimentální přijímač pro LG webOS, oddělený od Android aplikace.
Pro uživatele je určen [návod bez sestavování kódu](NAVOD-LG.md).
[Důkazy a přejímací testy](TESTOVANI.md) rozlišují automatické testy a skutečný běh na TV.
[Backlog](../BACKLOG.md) obsahuje odloženou chybu víceřádkového překladu.

## Jak to funguje

Konzole posílá obraz hry přes HDMI do TV. Původní Android PlayCaption získává další
kopii přes Remote Play, čte a překládá text. Přijímač na LG pouze zobrazuje výsledek.
Standardní režim používá dosavadní Android protokol. Integrační větev `feature/story-mode`
přidává čtecí frontu na Androidu a potvrzované stránkování na LG 0.2.3.

Od verze 0.0.5 lze přijímač otevřít z nabídky aplikací na TV a vybrat HDMI přímo
v PlayCaption. `com.webos.service.eim/getAllInputStatus` poskytne seznam vstupů;
`switchInput` ověří vybrané ID a služba krátce podrží Activity Manager aktivitu.
Nejprve spustí HDMI a následně vlastní overlay s `resumeOverlay: true`, takže
uzavření původního UI při přepnutí nezastaví dokončení operace. Aktivita se uvolní
po úspěchu, chybě nebo timeoutu 12 s. Služba neběží trvale po zavření přijímače
(mimo toast režim, viz níže).

### Toast režim (TV bez overlaye přes externí HDMI, např. webOS Lite)

Některé TV (ověřeno na LG UP81, webOS 6.5.3 „webOS TV Lite") neudrží průhledný
overlay přes externí HDMI vstup – po přepnutí na HDMI se web-aplikace zavře a titulky
nemá co vykreslit. webOS ale přes jakýkoli vstup včetně HDMI zobrazí systémové toasty.
Když uživatel ve výběru zvolí HDMI, UI zapne ve službě příkaz `toastMode`:

- služba se aktivitou `com.webos.service.activitymanager` udrží naživu i po zavření UI,
  takže UDP socket poslouchá dál;
- příchozí titulek bez živého overlay-odběratele se ukáže přes
  `com.webos.notification/createToast` (stejný text se do 4 s neopakuje, předchozí
  toast se zkusí zavřít);
- když overlay žije (na TV, která ho přes HDMI udrží), toast se neposílá – přednost má
  hezčí pruh. Režim je hybridní a degraduje na toasty jen tam, kde to systém vyžaduje.

Toast má systémový vzhled a krátkou dobu zobrazení; není to plnohodnotný pruh, ale
funguje na TV, kde overlay přes HDMI k dispozici není.

Nejde o automatické obnovení po libovolném přepnutí vstupu přes systémové menu.
`getForegroundAppInfo` testované C5 webové aplikaci odmítla; takovou funkci
nepovažujeme za dostupnou jen na základě existence metody.

- `app/`: průhledné HTML/CSS/JS UI, životní cyklus, heartbeat a doba zobrazení.
- `service/`: Node.js služba používající systémový `webos-service`, UDP a parser.
- `test.cjs`, `test-udp.cjs`, `test-input.cjs`, `test-receiver.cjs`, `test-controls.cjs`, `test-story.cjs`, `test-toast.cjs`: regrese UI/protokolu, UDP, přepínání HDMI, shoda vykreslení a toast režim.
- `build.py`: testy, syntaxe, sestavení IPK, kontrola obsahu a distribuční ZIP.

Kompatibilní protokol je v Android `Propojeni.kt`: `PLAYCAPTION_TV` na UDP 41988
každé 2 s a příjem `PCT1`, `PCV1` a `PCO1` na UDP 41989. Posíláme příznak nespárované konzole (`0`),
aby přihlášení zůstávalo na Android zařízení. Přihlašovací `PCS1` z původní Android aplikace se ignoruje; žádné tokeny konzole
se nepředávají. Striktně validované Story `PCQ1`/`PCS1` používá samostatná
Android čtecí fronta, dočtení LG potvrzuje přes `PCA1`; nejde o přihlašovací zprávy.
Síťové texty se vykreslují přes `textContent`, nikoli HTML.

Aktuální rozsah zahrnuje i volby odpovědí, dlouhé texty, modrý/žlutý/zelený rámeček
a barvy jmen. [Matice shody s Android přijímačem](SHODA-ANDROID.md) uvádí detaily
a hranice ověření. Opakované ID neobnovuje dobu zobrazení; kanály titulků a voleb
mají oddělené čítače. Po 5 s bez přenosu se skryjí texty i rámečky.
Po zavření posledního odběratele skončí UDP i discovery. První platná IPv4 adresa
drží příjem do 5 s bez vlastních dat; jiné zdroje mezitím obsah nepřepisují.
Ochrana nemění protokol a není autentizací ani identitou procesu.

OK/Zpět nad hrou otevře malé menu; Zpět v něm vrací hru, explicitní Ukončit
zavře přijímač. Lokální test používá vlastní prvek a nefalšuje přijetí dat.
Skrytí maskuje prezentaci, ale nepozastavuje Standardní časování.

Přijímač obnovuje síťové adresy každé 2 s i po připojení Wi-Fi nebo změně IP.
Android nadále poslouchá původní broadcast; známé LG navíc osloví přímo na uložené
IP. Nový přijímač odpoví názvem, ID a identifikátorem dotazu. Telefon přímou odpověď
přijme jen při shodě IP, uloženého ID a dotazu; bez odpovědi TV nezpřístupní.
Starší LG přímý dotaz ignorují a fungují dále přes broadcast. První nalezení nové
TV nebo TV s novou IP stále vyžaduje průchod broadcastu v místní síti.

LG přihlášení ke konzoli nepředává: vybírá se ve **Výstupu titulků**. Tlačítko
**Převzít připojení z Android TV** je určeno pro původní Android TV postup.
Při čekání na službu přes 10 s LG ukáže chybu startu; chybějící síť hlásí zvlášť.

## Sestavení

Potřebuješ Node.js 20+, Python 3.9+ a internet pro připnuté oficiální webOS CLI.
Z rootu projektu spusť jediný příkaz:

```sh
python3 webos/build.py
```

Na Windows lze použít `py -3 webos/build.py`. Aplikace nemá přidané npm knihovny;
CLI 3.2.6 se spouští přes npx pouze pro sestavení. Výstup ve `webos/dist/`:

- `cz.playcaption.overlaytest_0.2.3_all.ipk` pro instalaci do LG;
- `receiver-manifest.json`: schema, platforma, ID, verze, protokoly/schopnosti,
  skutečná velikost IPK a SHA256; výslovně `story: true`;
- `PlayCaption-LG-0.2.3.zip` pro předání uživatelům: IPK, návod, licence,
  přesně odpovídající zdroje přijímače a SHA256SUMS.

Build normalizuje časy na 1. 1. 2000 UTC a UID/GID z CLI archivů; dvě stejná sestavení ve stejném
prostředí musí mít stejné bajty IPK. ZIP má také pevné časy. Kontrola porovnává
rozbalený obsah IPK se zdroji a odmítne starší data v control.tar.gz i data.tar.gz
(rok 1970 způsobuje odmítnutí balíčku na webOS 5.x). Regresní testy balení lze
spustit samostatně: `python3 -m unittest discover -s webos -p test_build.py`. SHA256 neprokazuje důvěryhodnost cizího downloadu;
pro přibalené Android assets ji zajistí distribuovaná Android aplikace. Verze
přibaleného IPK není důkaz verze nainstalovaného přijímače.

Adresář `dist/` se necommituje. Samotný push zdrojů tedy nenahraje instalační IPK.
Pro běžné uživatele se sestavený IPK nebo ZIP přiloží k vydání v GitHub Releases;
samostatný IPK lze také uložit z nové Android aplikace přes LG průvodce.
Build nečte žádné místní přihlašovací údaje,
nepřipojuje se k TV a nic nezveřejňuje. ID zůstává stejné jako u původního testu,
aby bylo možné aktualizovat existující instalace. Při změně verze uprav `appinfo.json`,
`service/package.json` a názvy balíčků v návodu/README; Android verzi nezvyšuj.

Android `preBuild` spouští stejný build automaticky a přibaluje IPK i manifest
do APK. Samostatné sestavení výše zůstává pro ruční distribuci.

## Instalace přes CLI pro vývojáře

Uživatelský návod upřednostňuje přímou instalaci z Android PlayCaption. Alternativa s již nastaveným CLI:

```sh
ares-config --profile tv
ares-setup-device
```

Přidej TV jako `lg-tv` s vlastní IP, portem 9922 a uživatelem `prisoner`. Na TV zapni
Key Server. Z rootu projektu pokračuj:

```sh
ares-novacom --device lg-tv --getkey
ares-install --device lg-tv webos/dist/cz.playcaption.overlaytest_0.2.3_all.ipk
```

Volitelné spuštění z počítače otevře stejný výběr HDMI jako spuštění z TV:

```sh
ares-launch --device lg-tv cz.playcaption.overlaytest
```

Passphrase ani získaný klíč nepatří do projektu. Identifikátor `lg-tv` je pouze příklad.

## Předání správci projektu a vydání

1. Android větev obsahuje profily TV, LG průvodce, přibalený IPK a správu přijímače.
   Integrovaný LG přijímač 0.2.3 zachovává nabídku a Standardní režim, přidává Story.
   Samsung není součástí.
2. Pro lokální kontrolu použij `bash gradlew :app:testDebugUnitTest :app:assembleDebug`.
   Pro veřejné vydání použij dosavadní podepsané Android release sestavení. Vedle
   Android SDK a JDK potřebuješ Python 3.9+, Node.js 20+ a npx; Gradle automaticky
   spustí LG build a vloží jeho IPK/manifest do APK. Debug APK nenahrazuje podepsanou
   aktualizaci původní aplikace.
3. Produkční číslo verze a podpis Android APK řeší správce při vlastním vydání;
   tato větev je sama nezvyšuje a nevytváří release/tag.
4. K APK přilož také `webos/dist/PlayCaption-LG-0.2.3.zip`, případně samostatný IPK
   a manifest. To slouží uživatelům ruční instalace. ZIP obsahuje odpovídající zdroje
   přijímače a licenci. Pro instalaci přímo z Androidu další download IPK potřeba není.
5. V popisu vydání odkaž na `webos/NAVOD-LG.md`: LG vyžaduje Developer Mode,
   hlavním produktem a překladačem zůstává Android. Neslibuj překlad přímo na LG.
6. Aktuální důkazy a limity jsou v `TESTOVANI.md`. Samotný push či merge ještě
   nepublikuje APK, IPK ani ZIP; `webos/dist/` a Android build výstupy se necommitují.

První spárování slouží pro instalaci/aktualizace i čas Developer Mode. Systém
neukládá Passphrase; SSH klíč a session token ukládá v Androidu šifrovaně pro každou
TV zvlášť. Přijímač se předem neodinstalovává; stejná či novější verze se nepřepíše.
Automatické prodlužování Developer Mode není implementované.

## Přenositelnost a vzhled

- Název v launcheru a nabídce vstupů: **PlayCaption LG**. Discovery zachovává
  **LG PlayCaption · identifikátor TV**. Stejný název je na LG při čekání; více
  TV lze rozlišit bez změny Android APK. ID zůstává stejné po změně IP.
- `icon.png` (80×80) a `largeIcon.png` (130×130) jsou zmenšeniny Android launcher
  ikony `app/src/main/res/drawable-nodpi/gamelingo_ikona.png`; grafika se nemění.
  Před zmenšením se ořízne nadbytečný černý okraj (středový výřez 256×256
  z původních 512×512), aby symbol na LG nebyl příliš malý.
- Runtime nemá IP, SSID, model TV, pevný HDMI port ani osobní klíč. Discovery
  počítá broadcast z aktuálních IPv4 adres/masek a identitu TV z hostitele/MAC.
  HDMI seznam i poslední výběr patří konkrétní instalaci TV.
- `cz.playcaption.overlaytest` je stabilní instalační ID, nikoli vazba na testovací
  TV. Zůstává kvůli aktualizaci starších instalací bez druhé aplikace.
- PC není předpokladem samotného protokolu, ale podporovaný odesílač nyní dodává
  Android. Obecný text „zařízení“ neslibuje hotovou PC aplikaci.
- [Instalace z Androidu](INSTALACE-Z-ANDROIDU.md): přímý instalátor v PlayCaption,
  kontrola balíčku a záložní ruční postup. Nevyžaduje root.

## Omezení platformy

`transparent` a typ okna `overlay` vycházejí z existujícího
[LG Camera Overlay](https://github.com/mbsintegrations/LG-Camera-Overlay/blob/main/source/appinfo.json).
Nedokumentovaný `restartUserActivityTimer` každých 45 s brání systémovému zavření
po 120 s; nesmí se zaměňovat s prodlužováním Developer Mode. Odmítnutí volání zůstává
viditelné i při příjmu titulků. Nejde o garantované API pro všechny LG ani o schválení v LG Store.

UDP původní aplikace nemá autentizaci, proto pouze důvěryhodná LAN bez přesměrování
portů. Licence celého přijímače je AGPLv3, viz `../LICENSE`.

Reference: [LG JS služby](https://webostv.developer.lge.com/develop/guides/js-service-usage),
[životní cyklus služby a Activity Manager](https://webostv.developer.lge.com/develop/guides/js-service-faq),
[spouštění aplikací](https://webostv.developer.lge.com/develop/references/application-manager),
[CLI](https://webostv.developer.lge.com/develop/tools/cli-dev-guide),
[Developer Mode](https://webostv.developer.lge.com/develop/getting-started/developer-mode-app).
