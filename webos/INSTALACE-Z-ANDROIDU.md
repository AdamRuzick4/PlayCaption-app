# Instalace LG přijímače z Androidu

V Android průvodci pro LG webOS je krok **Ověřit TV a verzi přijímače**,
po kterém se nabídne instalace, aktualizace nebo spuštění bez přeinstalování.
Uživatel zadá místní IPv4 adresu a šestiznakovou Passphrase z Developer Mode.
Na TV musí běžet Developer Mode; Key Server je potřeba jen pro první či nové spárování. Účet a heslo LG zadává pouze na TV.
Tlačítko **Další možnosti instalace** nabízí nejprve externí Android správce
Aphanes, potom volitelně počítač. Přibalený IPK lze uložit přes systémový dialog
Androidu (Storage Access Framework); není potřeba oprávnění k celému úložišti.
Export ověřuje stejný manifest a SHA256 jako přímý instalátor a sám nic neinstaluje.
[Uživatelský návod](NAVOD-LG.md) popisuje přípravu a první spuštění.

## Balíček a sestavení

Android Gradle `preBuild` zavolá `python3 webos/build.py`: testy přijímače,
sestavení připnutým CLI a kontrola IPK proti zdrojům. Ověří manifest, ID, verzi,
velikost a SHA256; do assets přidá `lg/receiver.ipk` a `lg/receiver-manifest.json`.
IPK 0.1.0 má 31 060 bajtů. Generované soubory jsou pouze v build adresářích.
Build potřebuje Python 3.9+, Node.js 20+ a dostupné CLI přes npx.
Při instalaci z hotové APK se nic nestahuje z internetu a není potřeba počítač.
Založení účtu LG a získání Developer Mode jsou samostatné přípravné kroky.

`LgZdrojBalicku` odděluje instalaci od zdroje; nyní existuje pouze přibalený asset.
Vzdálené aktualizace, vlastní backend ani výběr cizího IPK nejsou implementované.
Manifest uvádí také protokoly a schopnosti; nevydáváme jej za zjištěnou verzi TV.

## Instalační tok

- `LgInstalator` mimo hlavní vlákno získá zašifrovaný RSA klíč z místního
  Key Serveru (`9991/webos_rsa`) a odemkne jej Passphrase.
- Udržovaná knihovna [JSch](https://github.com/mwiede/jsch) připojí `prisoner:9922`.
  SSH veřejný otisk se při prvním úspěšném připojení zapamatuje. Změna se odmítne;
  nové spárování vyžaduje výslovné potvrzení uživatelem. První spojení důvěřuje
  domácí síti; nejde o nezávislé ověření výrobce TV. C5 Developer Mode nabízí
  pouze starší podpis `ssh-rsa`; kompatibilita je povolená jen pro tuto SSH relaci,
  bez vypnutí kontroly otisku a bez změny globální konfigurace JSch.
- Po autentizaci ověříme stabilní ID stejným výpočtem jako discovery přijímače
  (hostname a aktivní MAC adresy). SSH klíč se uloží AES-GCM s Android Keystore,
  vázaný AAD na ID TV a účel `:ssh`. IP je jen vyhledávací pomůcka; kontrola otisku
  i skutečného ID chrání proti záměně po změně IP. Session token se načte ve stejném
  spojení, uloží odděleně a připraví pro ověření času. Jeho nedostupnost neblokuje IPK.
- Přes SFTP nejdříve načteme skutečné `appinfo.json`. Pouze chyba chybějícího souboru
  znamená nenainstalovaný přijímač. ID a číselnou verzi ověřujeme; neznámý formát
  nesmí vést k přepsání. Stejná či novější verze se jen spustí, bez uploadu/install.
  Verze se znovu čte i při následném potvrzení instalace, aby starší výsledek UI
  neumožnil downgrade. Samotná shoda verze není tvrzení o všech schopnostech přijímače.
- Při potřebné instalaci SFTP nahraje vlastní náhodně pojmenovaný IPK do `/media/developer/temp`, přečte
  jej zpět a porovná bajty. Předchozí přijímač se neodinstalovává.
- `com.webos.appInstallService/dev/install` musí potvrdit konečný úspěch.
  Samotné přijetí požadavku nestačí. Následuje kontrola skutečného `appinfo.json`
  proti očekávanému ID/verzi a pokus o spuštění přes Application Manager.
- Pokud spuštění selže, potvrzená instalace zůstává úspěšná a průvodce požádá
  o ruční spuštění. Výběr HDMI a discovery jsou další kroky.

Operace má limit 120 s a možnost zrušit čekání. Již zahájená instalace LG může
pokračovat i po zavření Android dialogu. Při přerušení takového požadavku IPK
ponecháváme, aby jej LG mohla dočíst; po potvrzeném konci odstraníme pouze vlastní
přenesený soubor. Neprovádíme hromadný úklid TV.

Passphrase se neukládá. Soukromý klíč se ukládá pouze zašifrovaný pomocí
Android Keystore; nikdy do logů ani v čitelné podobě. Vstupní pole se nezálohují; veřejný SSH otisk není přihlašovací tajemství.
Veřejné/hostitelské adresy, přesměrování HTTP a příliš velké odpovědi se odmítají.
MVP podporuje místní IPv4; IPv6 a DNS jména nejsou implementované.

## Ověření a omezení

JVM testy pokrývají adresy, manifest a změněný obsah, HTTP odpovědi,
koncové stavy instalace, ID/verzi, odemknutí syntetického klíče, zrušení
před připojením a změnu SSH identity. Skutečná přejímka musí oddělit aktualizaci
na C5 od první instalace na čisté TV a od výpadků v jednotlivých fázích.
Na C5 uživatel potvrdil aktualizaci, správné potvrzení výsledku i spuštění
přijímače z test06; podrobnosti jsou v [TESTOVANI.md](TESTOVANI.md). První
instalace na čisté TV a jiné modely tím nejsou ověřené.

## Zdroje

- [LG Developer Mode](https://webostv.developer.lge.com/develop/getting-started/developer-mode-app)
- [Oficiální webOS CLI](https://github.com/webos-tools/cli): `lib/base/novacom.js`,
  `lib/install.js`, `files/conf/command-service.json`.


## Informace o platnosti Developer Mode v Androidu

`LgInstalator.nactiRelaci` sdílí ověřené SSH připojení s instalátorem, ale pouze
čte `/var/luna/preferences/devmode_enabled`. Nepřenáší IPK, nevolá instalační
službu a neprodlužuje relaci. Po prvním propojení se tento session token uloží
AES-GCM šifrovaně s klíčem v Android Keystore; AAD jej váže na stabilní ID TV.
Android zálohování je vypnuté. Token, URL s tokenem, Passphrase ani soukromý klíč
se nelogují. Passphrase zůstává pouze v paměti operace. SSH klíč pro další spojení je uložený
odděleně, šifrovaně a s jiným AAD než session token.

`LgDevMode` ověřuje zbývající čas přes HTTPS GET na pevné službě LG
`developer.lge.com/secure/CheckDevModeSession.dev`, bez přesměrování a cache HTTP.
Odpověď musí mít úspěšný výsledek a rozpoznaný čas; neznámý formát není nula.
Cache uchovává počet sekund a čas ověření pro každou TV zvlášť. Starší odpověď
z předchozího propojení nesmí přepsat novou relaci. Ruční kontrola je dostupná
v detailu TV, automatická nejvýše hodinově při viditelné kartě.
Žádná nová služba na pozadí, plánovač, vlastní backend ani změna LG přijímače.
Čerstvost má limit 24 hodin; při chybě se ponechá označený poslední údaj.
Pod tři dny se zobrazí připomínka ručního prodloužení. Po výslovném zapnutí u konkrétní TV kontroluje čas také aktivní přehrávání. Při 1 s až 7 dnech zbývajícího času zkusí nejvýše jednou denně ResetDevModeSession.dev na stejném hostiteli. Úspěch vyžaduje nové načtení delšího času.
