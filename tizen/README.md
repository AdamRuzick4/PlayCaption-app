# PlayCaption pre Samsung TV (prijímač titulkov)

Tizen webová aplikácia pre Samsung TV. Ukáže HDMI vstup s konzolou a nad ním kreslí titulky,
ktoré posiela Android aplikácia PlayCaption z telefónu alebo tabletu. Je to obdoba LG prijímača
z priečinka [`webos/`](../webos/README.md). Pôvodne vznikla v repozitári
[bestranger/samsungappka](https://github.com/bestranger/samsungappka), kde ostáva aj jej staršia história.

- Používateľ: [návod pre Samsung](NAVOD-SAMSUNG.md), vrátane zapnutia Developer Mode.
- Vývojár: [inštalácia a pripojenie z Androidu](INSTALACE-Z-ANDROIDU.md), certifikáty a čo ešte chýba.

## Ako to celé funguje

1. Konzola (PS5 alebo Xbox) posiela obraz hry cez HDMI do televízora, v plnej kvalite.
2. Android PlayCaption sa ku konzole pripojí cez vzdialené hranie, z tejto kópie obrazu prečíta
   anglické titulky a preloží ich.
3. Preložený text pošle po domácej Wi-Fi do televízora (UDP port 41989).
4. Táto aplikácia text nakreslí nad HDMI obraz.

Televízor sa telefónu sám ohlasuje každé 2 sekundy (UDP port 41988) správou
`PLAYCAPTION_TV	samsung-<id>	<názov>	0	<verzia>`.

## Čo je v priečinku

- `app/`: obsah balíčka WGT (projekt pre Tizen Studio).
  - `config.xml`: oprávnenia, verzia a zápis služby.
  - `index.html`, `css/style.css`, `js/main.js`: obrazovka, HDMI a ladiaci panel.
  - `js/prijimac-ui.js`: kreslenie titulkov, volieb a rámčekov. Vzniká z LG zdroja, neupravovať ručne.
  - `service/service.js`: služba na pozadí, ktorá počúva sieť. Webová stránka v Samsung TV sama UDP nevie.
  - `service/protocol.js`: čítanie správ z telefónu. Vzniká z LG zdroja, neupravovať ručne.
- `sync.py`: prenesie spoločný kód z `webos/` s úpravami pre staršie Samsung TV.
- `build.py`: skúška a zostavenie nepodpísaného WGT s manifestom do `dist/`.
- `test/test.cjs`: skúška bez televízora.

## Vývoj

```bash
# Po zmene webos/service/protocol.js alebo webos/app/receiver-ui.js prenesie zmeny do tizen/app
python3 tizen/sync.py
```

```bash
# Spustí kontroly a skúšku a vyrobí tizen/dist/PlayCaption-Samsung-<verzia>-nepodpisany.wgt
python3 tizen/build.py
```

```bash
# Iba skúška: služba dostane UDP pakety ako z telefónu, stránka ich vykreslí v Chromiu
node tizen/test/test.cjs
```

Vykreslenie v skúške potrebuje Playwright (`npm install playwright`), bez neho sa overí iba služba.
Pri zmene verzie uprav `version` v `app/config.xml` aj `VERZIA` v `app/service/service.js`,
`build.py` ich zhodu stráži.

V Tizen Studiu sa dá priečinok `app` otvoriť priamo ako projekt (Run As, Tizen Web Application).
Ak má tvoj certifikát iné `package` ako `PCaption01`, uprav `tizen:application` aj
`tizen:service id` v `config.xml`.

## Licencia

GNU AGPLv3, rovnako ako celý PlayCaption (súbor `LICENSE` v koreni repozitára). Aplikácia používa
časti LG prijímača (© Adam Růžička).
