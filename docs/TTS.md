# Předčítání titulků na telefonu

Přeneseno z uživatelské testovací větve PlayCaption 0.21.23 do aktuálního masteru. Číslování veřejných vydání ani podpis aplikace tato změna neurčuje.

## Použití

V hlavní mobilní nabídce vyberte **Hlasový engine**: výchozí engine Androidu nebo některý z nainstalovaných enginů. V obrazovce titulků tlačítko **Hlas** otevře nastavení, kde lze předčítání zapnout či vypnout a upravit rychlost. Je vyžadován český hlas. Při chybě zvoleného enginu aplikace oznámí přechod na výchozí engine Androidu.

Slider nastavuje základ 0,5–2×. Dohánění násobí tuto rychlost podle věku titulku a odhadu fronty, nejvýše na 3×. Změna se uplatní od dalšího úryvku. Standardní režim může při přetížení vypustit staré čekající titulky; jedinečné Příběhové stránky se tímto způsobem nevypouštějí.

Předčítání je standardně vypnuté. Používá Android TextToSpeech; nepotřebuje klíč k Azure ani OpenAI. Samotný zvolený engine může vyžadovat síťové připojení. Překladač a jeho stávající poskytovatelé se tímto PR nemění.

## Tok textu a ochrana před opakováním

Standardní režim posílá do TTS jen dokončený překlad a celý skutečný anglický vstup překladače, včetně případné připojené předchozí části. Náhledy se nečtou. Příběhový režim předává vybranou stránku s identitou relace, revize a stránky, i když text vykresluje externí přijímač.

Lokální porovnání rozlišuje nový titulek, opravu, zkrácený OCR výsledek a pokračování. Čekající verze se nahrazuje na svém místě; již čtený začátek se při rozpoznaném pokračování odčítá. Kontrola aktivního a nedávno dokončeného obsahu má přednost před frontou a opakuje se před samotným spuštěním hlasu. Záměna samostatného OCR `l`/`I` se toleruje jen v delší anglické větě, nikoli v identifikátorech příběhových stránek.

Jde o heuristiku, nikoli sémantický model. Při nejasném přeformulování prodloužené věty se zachová celý nový překlad, takže může zaznít část začátku znovu. Čtení nelze garantovat pro OCR, které text nezachytí, nebo pro finální překlad zneplatněný dříve, než dorazí do TTS. Skutečnou rychlost a výslovnost ovlivňuje konkrétní engine.

## Diagnostika

**Záznam → Uložit TTS log…** otevře systémový výběr cílového souboru. Log zůstává lokální a obsahuje rozpoznaný text, skutečný vstup překladače, překlad, text předaný hlasu, důvody vynechání/nahrazení, rychlost, počet položek a odhad zpoždění. Finální překlady odmítnuté před předáním do TTS mají záznam `TRANSLATION_REJECTED`.

Log může obsahovat dialogy a text z obrazovky; exportujte a sdílejte jej vědomě. Velikost souboru je omezená rotací. PR neobsahuje uživatelské diagnostické soubory ani přihlašovací údaje.

## Ověření a přejímka

`TtsRevisionPolicyTest` zahrnuje doplňování vět, přeformulování, změněná čísla/zápory, překryv A+B → B+C, oddělení příběhových stránek, dohánění nad ručním základem a přehrání posloupnosti OCR → fronta → dokončení podle nahlášených chyb. Posloupnost se Sarah Connor ověřuje jediné čtení úvodu a zachování nových informací o výstřelech.

Po integraci je třeba fyzicky ověřit výběr enginu, zapnutí/vypnutí, rychlosti 1× a 1,4× při zpoždění, export/odmítnutí exportu, oba režimy a přepnutí mezi lokálním a externím výstupem. Uživatelské testy původní větve odhalily chyby řešené regresními testy; automatické testy nenahrazují přejímku této integrované větve na telefonu a TV.
