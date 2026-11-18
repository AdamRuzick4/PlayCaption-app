# Reprodukovatelné měření OCR

P0 základ: existující benchmark šesti variant je nyní v `OcrKvalityBenchmark.kt` v `app/src/androidTest`. Neupravuje produkční OCR. `score.py` porovnává jeho JSON se skutečným přepisem a kontroluje stejnou sadu snímků ve všech variantách. Python používá jen standardní knihovnu.

## Vyhodnocení uložených měření

Z rootu repozitáře:

```sh
python3 -m unittest discover -s tools/ocr -v
python3 tools/ocr/score.py tools/ocr/reference-good-scene.json tools/ocr/local/historical-42.json --images tools/ocr/local
```

`reference-good-scene.json` obsahuje ruční přepis sedmi uložených PNG, jejich SHA-256 a počet řádků. Jde o **čtyři různé texty v sedmi snímcích dobře čitelné scény**. Není to referenční sada pro problémovou denní scénu.

Lokální PNG a původní 42 měření byly při P0 zkopírovány z předchozí diagnostiky do ignorované `tools/ocr/local/`. Nejsou součástí Gitu. Na jiném počítači je třeba dodat přesné PNG odpovídající hashům a výsledný JSON; bez nich benchmark není reprodukovatelný. Nenahrazuj je podobnými screenshoty se stejnými jmény.

CER/WER používají Levenshteinovu vzdálenost nad Unicode znaky / slovy oddělenými whitespace. Normalizace: NFC a sloučení whitespace; **velikost písmen, apostrofy a interpunkce zůstávají významné**. Celkové CER/WER jsou vážené počty referenčních znaků/slov, nikoliv průměr procent jednotlivých snímků. P50/P95 jsou nearest-rank ze sedmi čtení dané varianty. Počet shodných řádků je pouze geometrická metrika, ne důkaz obsahové úplnosti.

Starých 42 měření neobsahuje hash vstupu: report to přiznává `input_sha256_verified:false`. `--images` ověří dnešní lokální PNG proti manifestu, zpětně ale neprokáže totožnost vstupu historického běhu. Nový Android runner již hash ukládá ke každému měření.

## Nový běh na Androidu

Použij samostatné diagnostické ID. **Neinstaluj sestavení `cz.titulky.tv` nebo `cz.titulky.tv.ocrfixes` kvůli benchmarku.** Následující build používá `cz.titulky.tv.ocrbenchmark`; před instalací zkontroluj `applicationId` v obou `app/build/outputs/apk/**/output-metadata.json`.

1. Zkopíruj přesná PNG z `tools/ocr/local/` do `app/src/androidTest/assets/ocr-bench/`. Cílová složka je ignorovaná. Pro tento manifest patří do assets jen sedm `*-caption.png`, bez dalších PNG.
2. Nastav `ANDROID_HOME` a Java 21 podle vlastního prostředí. Sestav:

```sh
bash gradlew --init-script tools/ocr/isolated.gradle :app:assembleDebug :app:assembleDebugAndroidTest
```

3. Na připojeném vyhrazeném Androidu nainstaluj obě **ověřené diagnostické** APK a spusť pouze benchmark:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class cz.titulky.tv.OcrKvalityBenchmark cz.titulky.tv.ocrbenchmark.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as cz.titulky.tv.ocrbenchmark cat files/ocr-porovnani.json > tools/ocr/local/measurements.json
python3 tools/ocr/score.py tools/ocr/reference-good-scene.json tools/ocr/local/measurements.json --images tools/ocr/local
```

Výstupní JSON je v datech diagnostické aplikace; ruční instrumentation běh ji po dokončení neodinstaluje. `connectedDebugAndroidTest` může testované aplikace uklidit dřív, než výsledek stáhneš. Původní aplikace ani její přihlášení se nemažou. U neznámého podpisu diagnostického APK instalaci řeš odděleně; neodinstalovávej používaný PlayCaption.

Runner měří pouze ML Kit nad uloženými výřezy. Varianty zachovávají dosavadní experiment: 1×/1,5×/2×/3×, inverze 2× a práh 160 při 2×. Pro barevné PNG zatím inverse/threshold čtou červený kanál; **nejsou vhodnou implementací RGB luminance**. P1 musí pro RGB srovnání doplnit vědomě zvolený postup.

Bez lokálních PNG se tento volitelný test označí jako přeskočený, aby neblokoval ostatní instrumentační testy. Pro měření vyžaduj skutečně provedený test bez přeskočení; samotné zelené sestavení nebo starý JSON v datech aplikace nejsou nový výsledek.

Čas zahrnuje volání ML Kit a čekání na výsledek; ne konverzi obrazu, síť, překlad ani čtení hráčem. První inference může obsahovat cold-start režii. Nejde o limit latence celé aplikace.

## Doplnění problematických dat

Před laděním P1 dodat stejný snímek před/po produkčním ořezu, původní I420 Y/U/V nebo RGB referenci, ruční přepis a geometrii řádků. Zaznamenat rozlišení/stride, požadovaný profil, dekodér a monotónní časy jednotlivých fází. Den/noc, pohyb, font, HUD a režim jsou samostatné štítky; samotná tma není doložená příčina lepšího OCR.

Pro každé A/B srovnání použít totožnou sadu a zaznamenat verzi kódu, model Androidu/API, ML Kit verzi, variantu, cold/warm pořadí a počet opakování. CPU/RAM, přijaté FPS/datový tok, úplnost replik a celková P50/P95 se musí měřit zvlášť. Současný runner tyto hodnoty neměří.

## Záchranné čtení bílých titulků z projektu playcaption-lg-native

Android ponechá první výsledek ML Kit. Pouze v režimu telefonu/tabletu, po
prázdném dialogu bez nápovědy na světlé scéně, hledá ve stejném pásu řádky
s hranami bílých znaků. Z LG 0.1.17 používá podmínku alespoň 25 % světlých
a 2 % téměř bílých pixelů uvnitř vybrané oblasti a převod jasu 160–240 na
0–255. Opakuje nejvýše jedno čtení téhož pásu se stejnými souřadnicemi.
Původní barvy pro jména, běžné čtení voleb a cesta „Jen televize“ tuto úpravu
nepoužívají.

Párový replay na tabletu 2405CRPFDG použil 177 výřezů a variant z manifestu
LG, včetně 41 bez titulku. Při měřítku 1,05× přes produkční čtečku dialogu bylo
přesných 152/177 původních výsledků. Použití zvýrazněné varianty jen po
prázdném výsledku dalo 153/177. Prázdných čtení na obrazech s titulkem ubylo
ze 17 na 15, u druhé zachráněné repliky zůstalo chybné první písmeno. Všech
41 výřezů bez dialogu zůstalo prázdných. Přesná shoda zde ignoruje velikost
písmen a interpunkci. Jde o přehrání připravených výřezů GTA V bez živého
Remote Play, výběru pásu službou či překladu. Latence při hraní ani jiné hry
nebyly změřeny.

Pro opakování použij izolovaná diagnostická APK z postupu výše. Do jejich
soukromého úložiště připrav pouze snímky a anotace (Python potřebuje Pillow):

```sh
replay=$(mktemp -d)
python3 tools/ocr/lg_replay.py prepare ../playcaption-lg-native "$replay/lg-ocr"
(cd "$replay" && COPYFILE_DISABLE=1 tar czf - lg-ocr) | adb shell -T 'run-as cz.titulky.tv.ocrbenchmark sh -c "mkdir -p files && cd files && tar xzf -"'
adb shell am instrument -w -e class cz.titulky.tv.SvetlyDialogBenchmark cz.titulky.tv.ocrbenchmark.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as cz.titulky.tv.ocrbenchmark cat files/lg-ocr/result.json > "$replay/result.json"
python3 tools/ocr/lg_replay.py score "$replay/result.json"
```
