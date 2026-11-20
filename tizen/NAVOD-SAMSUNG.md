# PlayCaption na Samsung TV, návod pre používateľa

Aktuálny návod a ďalšie tipy nájdete na [bestranger.eu/gta-vi-v-slovencine](https://bestranger.eu/gta-vi-v-slovencine/).

Táto verzia kreslí preložené titulky nad HDMI obraz na Samsung TV (systém Tizen). Čítanie textu,
preklad aj čítaciu frontu rieši Android telefón alebo tablet, televízor titulky iba zobrazuje.
Nie je to aplikácia z obchodu Samsung, inštaluje sa cez Developer Mode. Podpora je experimentálna.

## Čo potrebuješ

- Samsung TV so systémom Tizen (zhruba modely od roku 2017) a konzolu zapojenú cez HDMI.
- Android telefón alebo tablet, na ktorom už funguje PlayCaption a preklad hry.
- Televízor aj telefón v rovnakej domácej sieti, nie v sieti pre hostí.
- Bezplatný účet Samsung (ten istý ako na prihlásenie v televízore).
- Na prvé nastavenie asi 15 minút.

APK patrí do Android zariadenia, súbor WGT patrí do televízora. Na televízore sa neprihlasuješ k PSN.

## Prečo to nejde jedným kliknutím

Samsung TV prijme iba aplikáciu podpísanú certifikátom, ktorý Samsung vystavil na tvoj účet
a v ktorom je zapísané číslo (DUID) tvojho televízora. Jeden hotový podpísaný súbor pre všetkých
sa preto vyrobiť nedá a balíček, ktorý funguje u niekoho iného, tvoj televízor odmietne.
PlayCaption preto pribaľuje nepodpísaný inštalačný súbor a podpis vznikne až pri inštalácii,
v nástroji, ktorý sa prihlási tvojím účtom Samsung.

## 1. Otvor sprievodcu v PlayCaption

V Android PlayCaption otvor **Výstup titulkov → Zmeniť**, zvoľ platformu **Samsung (Tizen)**
a potom **Ako pripojiť televízor**. Sprievodca má štyri kroky a pamätá si, kde si skončil.

## 2. Zapni Developer Mode na televízii

1. Na ovládači stlač **Home** a otvor **Apps** (Aplikácie).
2. Na ovládači napíš **1 2 3 4 5**. Ak ovládač nemá číslice, stlač tlačidlo **123** a čísla vyber
   na klávesnici na obrazovke. Na niektorých modeloch treba čísla zadať, kým je označená ikona Apps.
3. Otvorí sa okno **Developer mode**. Prepni ho na **On**.
4. Do poľa **Host PC IP** zadaj adresu zariadenia, z ktorého budeš inštalovať:
   - pri inštalácii z telefónu adresu telefónu, sprievodca v PlayCaption ju ukáže v kroku 2,
   - pri inštalácii z počítača adresu počítača.
5. Potvrď **OK**.
6. Televízor reštartuj naozaj, nie len uspaním: drž tlačidlo napájania na ovládači, kým sa
   televízor nevypne a znova nezapne (objaví sa logo Samsung), alebo ho na 15 sekúnd odpoj zo zásuvky.
7. Po reštarte otvor Apps. Hore má byť nápis **Developer mode**. Ak tam nie je, zopakuj kroky 2 až 6.

Časté problémy:

- **Okno sa neotvorí.** Skús čísla zadať pomalšie, alebo cez tlačidlo 123. Na televízoroch
  v hotelovom alebo obchodnom režime Developer Mode nie je dostupný.
- **Adresa sa uloží odzadu.** Niektoré modely zapíšu `192.168.1.20` ako `20.1.168.192`.
  Ak inštalátor televízor nevidí, otvor Developer mode znova a pozri, čo je v poli naozaj uložené.
  Ak je adresa otočená, zadaj ju odzadu.
- **Zmenila sa adresa telefónu alebo počítača.** Zadaj novú adresu a televízor znova reštartuj.
  Pomôže nastaviť v routeri pre telefón stálu adresu.
- **Bezpečnosť.** Developer Mode otvára v domácej sieti ladiaci port televízora (26101) pre
  zariadenie s adresou z poľa Host PC IP. Zapínaj ho len v dôveryhodnej domácej sieti.
  Vypneš ho rovnako: Apps, 1 2 3 4 5, Off a reštart.

Oficiálny postup Samsungu: [Developer Mode na TV](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html).

## 3. Nainštaluj prijímač

Vyber jednu cestu. Developer Mode z kroku 2 treba pre obe.

### 3a. Inštalácia cez Apps2Samsung (odporúčané, z telefónu aj z počítača)

[Apps2Samsung](https://github.com/Apps2Samsung/Apps2Samsung) beží na Androide, Windowse, macOS aj Linuxe.
Sám vytvorí certifikát s tvojím účtom Samsung a DUID televízora a nainštaluje vlastný súbor `.wgt`.
Osvedčil sa lepšie ako TV Installer.

1. V sprievodcovi PlayCaption, krok 3, stlač **Uložiť Samsung inštalačný súbor (.wgt)** a ulož ho
   napríklad do Stiahnutých súborov. Súbor je pribalený v aplikácii, nič sa nesťahuje.
2. Nainštaluj Apps2Samsung. Android: súbor APK z [vydaní](https://github.com/Apps2Samsung/Apps2Samsung/releases).
   Windows: `winget install Apps2Samsung.Apps2Samsung`. macOS a Linux: balík z tých istých vydaní.
3. Ak už PlayCaption v televízore máš z iného inštalátora, odstráň ju v televízore
   (Apps, ozubené koliesko, PlayCaption Samsung, Odstrániť). Inak môže inštalácia skončiť chybou
   „Author certificate not match“.
4. Nechaj Apps2Samsung nájsť televízor v sieti (ak sa nenájde, v ponuke ⋯ zvoľ **Add TV by IP…**).
5. Na karte **Install** zvoľ **Install a file…**, vyber súbor `.wgt` z bodu 1 a potvrď. Pri prvej
   inštalácii sa otvorí prihlásenie účtom Samsung, certifikát sa vytvorí sám a uloží.
6. Počkaj na potvrdenie inštalácie a pokračuj krokom 4.

Apps2Samsung je nezávislý projekt tretej strany, nie súčasť PlayCaption.

### 3b. Inštalácia z počítača

Oficiálna cesta cez Tizen Studio. Do poľa Host PC IP v televízore musí byť zadaná adresa počítača.

1. Nainštaluj [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download).
   V Package Manageri doinštaluj **TV Extensions** a **Samsung Certificate Extension**.
2. Otvor **Device Manager**, pridaj televízor cez **Remote Device Manager** (adresa televízora,
   port 26101) a zapni spojenie.
3. Otvor **Certificate Manager**, vytvor nový profil typu **Samsung**, zariadenie **TV**.
   Pri autorskom certifikáte sa prihlás účtom Samsung. Pri distribučnom certifikáte nechaj úroveň
   **Public**, DUID pripojeného televízora sa doplní sám.
4. V Device Manageri klikni pravým na televízor a zvoľ **Permit to install applications**.
5. Zober nepodpísaný súbor `PlayCaption-Samsung-<verzia>-nepodpisany.wgt` (z kroku 3a bod 1,
   z vydania na GitHube, alebo z `tizen/dist/` po `python3 tizen/build.py`) a v príkazovom riadku:

```bash
# Podpíše existujúci WGT tvojím profilom z Certificate Managera (názov profilu daj do úvodzoviek)
tizen package -t wgt -s "MojProfil" -- PlayCaption-Samsung-1.3.0-nepodpisany.wgt
```

```bash
# Pripojí počítač k televízoru cez ladiaci port (adresa televízora)
sdb connect 192.168.1.50
```

```bash
# Vypíše pripojené zariadenia, v treťom stĺpci je názov televízora pre ďalší príkaz
sdb devices
```

```bash
# Nainštaluje podpísaný balíček do televízora (za -t patrí názov z predošlého príkazu)
tizen install -n PlayCaption-Samsung-1.3.0-nepodpisany.wgt -t UE55XXXX
```

Namiesto príkazov môžeš v Tizen Studiu otvoriť priečinok `tizen/app` ako projekt
a zvoliť **Run As → Tizen Web Application**.

Časté chyby pri inštalácii:

- **Chyba certifikátu (kód 118, „Invalid certificate chain“).** V certifikáte nie je DUID tohto
  televízora, alebo bol balíček podpísaný iným profilom. Vytvor profil s pripojeným televízorom
  a podpíš súbor znova.
- **„Author certificate not match“.** V televízore je staršia inštalácia podpísaná iným autorským
  certifikátom. Odinštaluj ju v Apps a nainštaluj znova.
- **Televízor sa nedá pripojiť.** Skontroluj Host PC IP, reštartuj televízor odpojením zo zásuvky
  a over, že obe zariadenia sú v rovnakej sieti.

## 4. Spusti prijímač a over ho

1. Na televízore otvor Apps a spusti **PlayCaption Samsung**.
2. Šípkami vľavo a vpravo vyber HDMI vstup, do ktorého je zapojená konzola. Vľavo hore má byť
   „HDMI … beží“ a „Príjem pripravený, čakám na telefón“.
3. V sprievodcovi PlayCaption, krok 4, stlač **Overiť prijímač**. Sprievodca ukáže názov
   televízora, jej adresu a verziu prijímača:
   - verzia je aktuálna: netreba nič robiť,
   - v televízore je staršia verzia: prijímač funguje, aktualizuješ ho rovnakým postupom ako v kroku 3,
   - v televízore je novšia verzia: neprepisuj ju staršou, aktualizuj PlayCaption v telefóne,
   - prijímač verziu nehlási: je to 1.2.0 alebo staršia, pre Príbehový režim nainštaluj novú.
4. Stlač **Vyhľadať prijímač** a vyber „Samsung PlayCaption · …“. Titulky sa odteraz posielajú do televízora.

Inštalácia sa považuje za hotovú až vtedy, keď sa prijímač z televízora sám ohlási. Samotné
odoslanie súboru ani prejdenie návodu to nepotvrdzuje.

## Používanie

- Prihlásenie ku konzole (Remote Play) rob priamo v telefóne. Samsung prijímač ho telefónu odovzdať
  nevie, preto sa tlačidlo na prevzatie prihlásenia z televízora pri platforme Samsung neukazuje.
- Ovládanie na televízore: šípky vľavo a vpravo menia HDMI vstup, OK ukáže alebo schová ladiaci
  panel, Späť aplikáciu ukončí.
- Priehľadnosť pozadia titulkov zmeníš na ovládači televízora: šípka hore ju zvyšuje, šípka dole
  znižuje, po 10 %. Hodnota sa uloží v televízore. Predvolene je pozadie čierne ako doteraz.
- Dva televízory Samsung majú každý vlastný profil a vlastné nastavenie polohy titulkov.
- Príbehový režim funguje s prijímačom od verzie 1.3.0.

## Platnosť

Certifikát Samsung má obmedzenú platnosť a aplikácie nainštalované cez Developer Mode môže
televízor po čase alebo po väčšej aktualizácii systému odstrániť. Vtedy zopakuj krok 3.
Developer Mode ostáva zapnutý, kým ho nevypneš.

## Keď to nejde

Odfoť obrazovku televízora s ladiacim panelom (tlačidlo OK) a opíš text z ľavého horného rohu:

- „Služba nemá sieťové moduly“ alebo „Príjem UDP zlyhal“: televízor nepovolil službe sieť.
- „Službu príjmu sa nepodarilo spustiť“ alebo „Služba príjmu neodpovedá“: chyba inštalácie alebo certifikátu.
- „Príjem pripravený“, ale telefón televízor nenájde: sieť (iná Wi-Fi, izolácia zariadení v routeri).
