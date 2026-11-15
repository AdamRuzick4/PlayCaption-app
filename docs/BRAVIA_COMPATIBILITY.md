# Oprava pádu OCR na 32bitové Sony Bravia a navazující úpravy

Na jedné Sony Bravia BF1 s Androidem 12 / API 31 a 32bitovým uživatelským systémem
se aplikace po úspěšném připojení k PS5 ukončovala signálem SIGILL. Dvě nativní
stopy ukázaly první rámec v `libmlkit_google_ocr_pipeline.so` (OCR 16.0.1), na
instrukcích VSDOT. Poslední dostupné vzorky neukazovaly vyčerpání paměti.

Pro soukromý kompatibilní test byla použita OCR verze `16.0.0-beta5`; uživatel pak
potvrdil funkční hraní. Jde o workaround, ne o opravu uzavřené nativní knihovny.
Historicky podobné hlášení: https://github.com/googlesamples/mlkit/issues/655

Další změny: paměťový profil slabší TV, lokální překladač vytvářený až při potřebě,
jedna rozpracovaná OCR úloha, uvolnění obrazových prostředků po stop/dispose,
průběžná diagnostika a historie ukončení, ZIP export přes SAF, D-pad ovládání,
načítání logů mimo hlavní vlákno a omezené čtení log-tail. Poslední verze zkracuje
interval kontroly na 500 ms, stabilizační opakování na zbývající část 200 ms a
omezuje síťové překlady na dva běžící a osm čekajících požadavků.

## Změny chování k rozhodnutí správce projektu

- Na TV pod 2500 MiB nebo s lowRam příznakem je vypnut místní překlad, jeho offline
  záloha, rychlý náhled a další OCR voleb odpovědí; překlad potřebuje internet.
- Starší beta OCR je globální závislost. Před širším vydáním je nutné rozhodnout,
  zda workaround ponechat, oddělit kompatibilní variantu, nebo řešit s dodavatelem.
- Gradle wrapper je 8.7 pro použitou kombinaci AGP 8.5.2 a JDK 17.
- Čekání na nativní dispose nemá pevný timeout; po odpojení se objevovalo delší
  čekání. Tento bod vyžaduje další ověření při integraci.

## Ověření a rozsah

Čisté sestavení samostatné testovací aplikace prošlo, stejně jako 9 jednotkových
testů. Tento návrh bez testovacího balíčku/podpisu prošel kompilací Kotlinu a 9 testy.
Fyzický test provedl uživatel pouze na uvedené Bravii; finální testovací verzi
slovně potvrdil jako funkční. Pro poslední optimalizaci není nové výkonové měření.
Nebyl proveden test na LG ani implementován port pro webOS.

Soukromé logy, lokální IP adresy, herní texty, fotografie, APK a podpisové klíče
nejsou součástí tohoto veřejného návrhu. Původní licence AGPL-3.0 i autorská označení
zůstávají zachovány. Testovací značka, applicationId a soukromá signing konfigurace
se do tohoto návrhu nepřenášejí.

## Verze knihoven a licence

Přesné vyřešené runtime závislosti sestavení Test 4 včetně SHA-256 artefaktů
jsou v [BRAVIA_DEPENDENCIES.tsv](BRAVIA_DEPENDENCIES.tsv). Přímé závislosti a jejich
požadované verze jsou v `app/build.gradle.kts`. Použité nástroje: AGP 8.5.2,
Kotlin 1.9.24, Gradle 8.7, JDK 17, Android SDK / build-tools 34 / 34.0.0.

Původní LICENSE a autorská označení zůstávají beze změny; vlastní úpravy jsou
předávány pod stejnou AGPL-3.0. Převzaté SDK si ponechávají vlastní podmínky.
Úpravy vznikly s pomocí OpenAI Codex ve dnech 14.–15. 11. 2026; fyzické testy
prováděl uživatel, nikoli AI asistent. Naměřené systémové údaje TV: 2393 MiB RAM,
32bitový Android 12 / API 31, limit Java heapu 192 MiB. Přesný obchodní model
nebyl doložen a LG nebylo testováno.
