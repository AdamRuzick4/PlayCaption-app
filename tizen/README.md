# PlayCaption pre Samsung TV (prijímač titulkov)

Tizen webová apka pre Samsung TV. Ukáže HDMI vstup s konzolou a nad ním kreslí titulky, ktoré posiela Android aplikácia [PlayCaption](https://github.com/AdamRuzick4/PlayCaption-app) z telefónu alebo tabletu. Je to obdoba LG prijímača z pôvodného projektu.

## Ako to celé funguje

1. Konzola (PS5 alebo Xbox) posiela obraz hry cez HDMI do televízie, v plnej kvalite.
2. Android PlayCaption v telefóne sa ku konzole pripojí cez vzdialené hranie (Remote Play), z tejto kópie obrazu prečíta anglické titulky a preloží ich.
3. Preložený text pošle po domácej Wi-Fi do televízie (UDP port 41989).
4. Táto apka text nakreslí nad HDMI obraz.

Televízia sa telefónu sama ohlasuje každé 2 sekundy (UDP port 41988), preto ju telefón nájde cez "Vyhledat televizi". Android aplikáciu netreba meniť.

## Čo je v projekte

- `index.html`, `css/style.css`, `js/main.js`: obrazovka, HDMI a vykreslenie titulkov.
- `js/prijimac-ui.js`: kreslenie titulkov, volieb a rámčekov (prevzaté z LG prijímača).
- `service/service.js`: služba na pozadí, ktorá počúva sieť. Webová stránka v Samsung TV sama UDP nevie, služba áno.
- `service/protocol.js`: čítanie správ z telefónu (prevzaté z LG prijímača).
- `config.xml`: oprávnenia a zápis služby.
- `test/test.cjs`: skúška bez televízie (`node test/test.cjs`).

## Inštalácia v Tizen Studiu

1. Do existujúceho projektu skopíruj všetko z tohto repozitára: `index.html`, `config.xml`, priečinky `css`, `js` a nový priečinok `service`.
2. Ak má tvoj projekt v `config.xml` iné `package` ako `PCaption01`, nechaj si svoje `tizen:application` a v riadku `tizen:service id="PCaption01.Prijimac"` prepíš `PCaption01` na svoje `package`. Ostatné riadky prevezmi.
3. Pravým na projekt, Run As, Tizen Web Application.

## Použitie

1. Na televízii spusti apku. Vľavo hore má byť "HDMI 3 beží" a "Príjem pripravený, čakám na telefón".
2. V telefóne sa v PlayCaption prihlás ku konzole priamo v telefóne (kroky 1 až 3 na obrazovke "Pripojiť ku konzole"). Zelené tlačidlo "Vyhľadať televízor" na tejto obrazovke nepoužívaj, to slúži len na prevzatie prihlásenia z Android TV.
3. Na hlavnej obrazovke v časti "Výstup titulkov" daj "Zmeniť", platforma Android TV, "Vybrať / zmeniť televízor" a vyber "Samsung PlayCaption · ...". Telefón a televízia musia byť na rovnakej Wi-Fi.
4. Spusti titulky ako zvyčajne.

Ovládanie: šípky vľavo a vpravo menia HDMI vstup, OK ukáže alebo schová ladiaci panel a stav, Späť apku ukončí.

## Ladenie

Po spustení je vpravo hore ladiaci panel (zapnutý, kým ho tlačidlom OK neschováš). Ukazuje, či beží služba, adresu televízie v sieti, počty prijatých správ podľa druhu a posledných 14 udalostí s časom. Pri hlásení problému stačí odfotiť obrazovku.

## Čo hlásiť, ak to nejde

Opíš text z ľavého horného rohu. Najdôležitejšie hlášky:

- "Služba nemá sieťové moduly" alebo "Príjem UDP zlyhal": televízia nepovolí službe sieť, treba inú cestu.
- "Službu príjmu sa nepodarilo spustiť" alebo "Služba príjmu neodpovedá": chyba v `config.xml` (id služby) alebo v certifikáte.
- "Príjem pripravený", ale telefón televíziu nenájde: sieť (iná Wi-Fi, izolácia zariadení v routeri).

## Licencia

GNU AGPLv3, rovnako ako PlayCaption, pretože apka používa časti jeho LG prijímača (© Adam Růžička). Text licencie je v súbore `LICENSE`.
