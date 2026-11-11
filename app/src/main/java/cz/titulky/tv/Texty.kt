package cz.titulky.tv

import android.content.Context

/** Jazyky, do kterých PlayCaption překládá (a ve kterých má prostředí). */
data class Jazyk(val kod: String, val nazev: String, val vlajka: String)

object Texty {

    val JAZYKY = listOf(
        Jazyk("cs", "Čeština", "🇨🇿"),
        Jazyk("sk", "Slovenčina", "🇸🇰"),
        Jazyk("de", "Deutsch", "🇩🇪"),
        Jazyk("pl", "Polski", "🇵🇱"),
        Jazyk("fr", "Français", "🇫🇷"),
        Jazyk("es", "Español", "🇪🇸"),
        Jazyk("it", "Italiano", "🇮🇹")
    )

    private val KODY = listOf("cs", "sk", "de", "pl", "fr", "es", "it")

    /** Texty v pořadí: cs, sk, de, pl, fr, es, it. */
    private val T: Map<String, List<String>> = mapOf(
        "vyberJazyk" to listOf("Vyber jazyk", "Vyber jazyk", "Sprache wählen", "Wybierz język", "Choisis ta langue", "Elige tu idioma", "Scegli la lingua"),
        "vyberJazykPopis" to listOf(
            "Do tohoto jazyka se budou hry překládat.", "Do tohto jazyka sa budú hry prekladať.",
            "In diese Sprache werden deine Spiele übersetzt.", "Na ten język będą tłumaczone gry.",
            "Tes jeux seront traduits dans cette langue.", "Tus juegos se traducirán a este idioma.",
            "I tuoi giochi verranno tradotti in questa lingua."),
        "pokracovat" to listOf("Pokračovat", "Pokračovať", "Weiter", "Dalej", "Continuer", "Continuar", "Continua"),
        "jazykPrekladu" to listOf("Jazyk překladu", "Jazyk prekladu", "Übersetzungssprache", "Język tłumaczenia", "Langue de traduction", "Idioma de traducción", "Lingua di traduzione"),
        "zdroj" to listOf("Zdrojový jazyk: angličtina", "Zdrojový jazyk: angličtina", "Ausgangssprache: Englisch", "Język źródłowy: angielski", "Langue source : anglais", "Idioma de origen: inglés", "Lingua di origine: inglese"),
        "pripojitKonzoli" to listOf("Připojit ke konzoli", "Pripojiť ku konzole", "Mit Konsole verbinden", "Połącz z konsolą", "Connecter la console", "Conectar con la consola", "Collega la console"),
        "pripojit" to listOf("Připojit", "Pripojiť", "Verbinden", "Połącz", "Connecter", "Conectar", "Collega"),
        "znovuSparovat" to listOf("Znovu spárovat", "Znova spárovať", "Neu koppeln", "Sparuj ponownie", "Réassocier", "Volver a vincular", "Riassocia"),
        "pripojitPopis" to listOf(
            "Připojení ke konzoli pomocí Remote Play a spárování PINem.", "Pripojenie ku konzole cez Remote Play a spárovanie PINom.",
            "Verbindung zur Konsole über Remote Play, Kopplung per PIN.", "Połączenie z konsolą przez Remote Play i parowanie kodem PIN.",
            "Connexion à la console via Lecture à distance, association par PIN.", "Conexión con la consola mediante Juego remoto y vinculación con PIN.",
            "Connessione alla console tramite Riproduzione remota e associazione con PIN."),
        "sparovano" to listOf("Spárováno", "Spárované", "Gekoppelt", "Sparowano", "Associée", "Vinculada", "Associata"),
        "stavPripojeno" to listOf("Připojeno – překlad běží", "Pripojené – preklad beží", "Verbunden – Übersetzung läuft", "Połączono – tłumaczenie działa", "Connecté – traduction active", "Conectado – traducción activa", "Connesso – traduzione attiva"),
        "stavCekam" to listOf("Čekám na zapnutí PS5…", "Čakám na zapnutie PS5…", "Warte auf die PS5…", "Czekam na włączenie PS5…", "En attente de la PS5…", "Esperando a la PS5…", "In attesa della PS5…"),
        "stavPripojuji" to listOf("Připojuji k PS5… může to trvat až 2 min", "Pripájam k PS5… môže to trvať až 2 min", "Verbinde mit PS5… kann bis zu 2 Min. dauern", "Łączę z PS5… może to potrwać do 2 min", "Connexion à la PS5… cela peut prendre jusqu'à 2 min", "Conectando con la PS5… puede tardar hasta 2 min", "Connessione alla PS5… può richiedere fino a 2 min"),
        "stavVypnuto" to listOf("Překlad vypnutý – zapni OK", "Preklad vypnutý – zapni OK", "Übersetzung aus – OK zum Starten", "Tłumaczenie wyłączone – naciśnij OK", "Traduction arrêtée – OK pour démarrer", "Traducción apagada – pulsa OK", "Traduzione spenta – premi OK"),
        "stavNesparovano" to listOf("Nejdřív připoj konzoli", "Najprv pripoj konzolu", "Zuerst Konsole verbinden", "Najpierw połącz konsolę", "Connecte d'abord la console", "Primero conecta la consola", "Prima collega la console"),
        "zobrazeni" to listOf("Zobrazení titulků", "Zobrazenie titulkov", "Untertitel-Anzeige", "Wyświetlanie napisów", "Affichage des sous-titres", "Visualización de subtítulos", "Visualizzazione sottotitoli"),
        "prekryvat" to listOf("Překrývat původní titulky", "Prekrývať pôvodné titulky", "Originaluntertitel überdecken", "Zakrywaj oryginalne napisy", "Recouvrir les sous-titres d'origine", "Cubrir los subtítulos originales", "Coprire i sottotitoli originali"),
        "coPrekladat" to listOf("Co překládat", "Čo prekladať", "Was übersetzen", "Co tłumaczyć", "Que traduire", "Qué traducir", "Cosa tradurre"),
        "dialogy" to listOf("Mluvené dialogy", "Hovorené dialógy", "Gesprochene Dialoge", "Dialogi mówione", "Dialogues parlés", "Diálogos hablados", "Dialoghi parlati"),
        "napovedy" to listOf("Menu a ovládací nápověda", "Menu a ovládacia nápoveda", "Menüs und Steuerungshinweise", "Menu i podpowiedzi sterowania", "Menus et aides de contrôle", "Menús y ayudas de control", "Menu e suggerimenti dei comandi"),
        "ano" to listOf("Ano", "Áno", "Ja", "Tak", "Oui", "Sí", "Sì"),
        "ne" to listOf("Ne", "Nie", "Nein", "Nie", "Non", "No", "No"),
        "zapnuto" to listOf("Zapnuto", "Zapnuté", "An", "Wł.", "Activé", "Activado", "Attivo"),
        "vypnuto" to listOf("Vypnuto", "Vypnuté", "Aus", "Wył.", "Désactivé", "Desactivado", "Spento"),
        "hotovo" to listOf("Hotovo – hrát", "Hotovo – hrať", "Fertig – spielen", "Gotowe – graj", "Terminé – jouer", "Listo – a jugar", "Fatto – gioca"),
        "qrNadpis" to listOf("Přihlášení přes telefon", "Prihlásenie cez telefón", "Anmeldung per Handy", "Logowanie przez telefon", "Connexion via le téléphone", "Iniciar sesión con el móvil", "Accesso tramite telefono"),
        "qrPopis" to listOf(
            "1. Naskenuj QR kód telefonem (stejná Wi-Fi jako televize) a přihlas se k PlayStation – DRUHÝM účtem z PS5, ne hlavním.\n2. V telefonu se ukáže BÍLÁ stránka – to je správně. Klepni na adresu stránky (iPhone dole, Android nahoře) a zkopíruj ji.\n3. Naskenuj QR kód znovu a adresu vlož.\nAdresa pro prohlížeč v telefonu:",
            "1. Naskenuj QR kód telefónom (rovnaká Wi-Fi ako televízor) a prihlás sa k PlayStation – DRUHÝM účtom z PS5, nie hlavným.\n2. V telefóne sa ukáže BIELA stránka – to je správne. Ťukni na adresu stránky (iPhone dole, Android hore) a skopíruj ju.\n3. Naskenuj QR kód znova a adresu vlož.\nAdresa pre prehliadač v telefóne:",
            "Scanne den QR-Code mit dem Handy (gleiches WLAN wie der Fernseher). Dort meldest du dich bei PlayStation an. Oder gib im Handy-Browser ein:",
            "Zeskanuj kod QR telefonem (ta sama sieć Wi-Fi co telewizor). Tam zalogujesz się do PlayStation. Albo wpisz w przeglądarce telefonu:",
            "Scanne le QR code avec ton téléphone (même Wi-Fi que la TV). Tu t'y connecteras à PlayStation. Ou saisis dans le navigateur du téléphone :",
            "Escanea el código QR con el móvil (misma Wi-Fi que la TV). Allí inicias sesión en PlayStation. O escribe en el navegador del móvil:",
            "Scansiona il codice QR con il telefono (stessa Wi-Fi della TV). Lì accedi a PlayStation. Oppure digita nel browser del telefono:"),
        "qrPopisTelefon" to listOf(
            "1. Klepni na „Otevřít v prohlížeči“ a přihlas se k PlayStation – DRUHÝM účtem z PS5, ne hlavním.\n2. Ukáže se BÍLÁ stránka – to je správně. Klepni na adresu stránky a zkopíruj ji (ikona dva čtverečky).\n3. Vrať se sem, klepni znovu na „Otevřít v prohlížeči“ a adresu vlož.",
            "1. Ťukni na „Otvoriť v prehliadači“ a prihlás sa k PlayStation – DRUHÝM účtom z PS5, nie hlavným.\n2. Ukáže sa BIELA stránka – to je správne. Ťukni na adresu stránky a skopíruj ju (ikona dva štvorčeky).\n3. Vráť sa sem, ťukni znova na „Otvoriť v prehliadači“ a adresu vlož.",
            "Sony erlaubt keine Anmeldung direkt in der App. Öffne die Anmeldung im Browser und folge der Seite.",
            "Sony nie pozwala logować się bezpośrednio w aplikacji. Otwórz logowanie w przeglądarce i postępuj zgodnie ze stroną.",
            "Sony n'autorise pas la connexion dans l'application. Ouvre la connexion dans le navigateur et suis la page.",
            "Sony no permite iniciar sesión dentro de la app. Abre el inicio de sesión en el navegador y sigue la página.",
            "Sony non consente l'accesso nell'app. Apri l'accesso nel browser e segui la pagina."),
        "qrOtevrit" to listOf("Otevřít v prohlížeči", "Otvoriť v prehliadači", "Im Browser öffnen", "Otwórz w przeglądarce", "Ouvrir dans le navigateur", "Abrir en el navegador", "Apri nel browser"),
        "qrCekam" to listOf("Čekám na přihlášení z telefonu…", "Čakám na prihlásenie z telefónu…", "Warte auf die Anmeldung vom Handy…", "Czekam na logowanie z telefonu…", "En attente de la connexion depuis le téléphone…", "Esperando el inicio de sesión desde el móvil…", "In attesa dell'accesso dal telefono…"),
        "qrVTelevizi" to listOf("Zkusit přihlášení přímo v televizi", "Skúsiť prihlásenie priamo v televízore", "Anmeldung direkt am Fernseher versuchen", "Spróbuj zalogować się na telewizorze", "Essayer la connexion sur la TV", "Probar a iniciar sesión en la TV", "Prova l'accesso sulla TV"),
        "betaNadpis" to listOf("Vítej v testovací verzi", "Vitaj v testovacej verzii", "Willkommen in der Testversion", "Witaj w wersji testowej", "Bienvenue dans la version de test", "Bienvenido a la versión de prueba", "Benvenuto nella versione di prova"),
        "betaText" to listOf(
            "PlayCaption je zatím ve zkušebním provozu, takže občas něco nemusí fungovat.\nRychlost a přesnost titulků závisí hlavně na rychlosti internetového připojení a výkonu zařízení – titulky se čtou z obrazu, který přichází přes Remote Play. Nejlépe funguje síťový kabel nebo 5 GHz Wi-Fi.\nKdyž narazíš na problém, napiš do Facebook skupiny PlayCaption – testování, co se stalo a v jaké hře.\nDíky, že pomáháš aplikaci vylepšit!",
            "PlayCaption je zatiaľ v skúšobnej prevádzke, takže občas niečo nemusí fungovať.\nRýchlosť a presnosť titulkov závisí hlavne od rýchlosti internetového pripojenia a výkonu zariadenia – titulky sa čítajú z obrazu, ktorý prichádza cez Remote Play. Najlepšie funguje sieťový kábel alebo 5 GHz Wi-Fi.\nKeď narazíš na problém, napíš do Facebook skupiny PlayCaption – testovanie, čo sa stalo a v akej hre.\nVďaka, že pomáhaš aplikáciu vylepšiť!",
            "PlayCaption befindet sich noch im Testbetrieb – manches funktioniert vielleicht noch nicht.\nTempo und Genauigkeit der Untertitel hängen vor allem von der Geschwindigkeit deiner Internetverbindung und der Leistung deines Geräts ab – die Untertitel werden aus dem Bild gelesen, das über Remote Play kommt. Am besten funktionieren LAN-Kabel oder 5-GHz-WLAN.\nWenn du ein Problem findest, schreib in die Facebook-Gruppe PlayCaption – Testing, was passiert ist und in welchem Spiel.\nDanke, dass du hilfst, die App zu verbessern!",
            "PlayCaption jest jeszcze w fazie testów, więc czasem coś może nie działać.\nSzybkość i dokładność napisów zależą głównie od szybkości połączenia z internetem i wydajności urządzenia – napisy są odczytywane z obrazu przesyłanego przez Remote Play. Najlepiej działa kabel sieciowy lub Wi-Fi 5 GHz.\nJeśli trafisz na problem, napisz w grupie na Facebooku PlayCaption – testowanie, co się stało i w jakiej grze.\nDzięki, że pomagasz ulepszyć aplikację!",
            "PlayCaption est encore en phase de test, certaines choses peuvent ne pas fonctionner.\nLa rapidité et la précision des sous-titres dépendent surtout de la vitesse de ta connexion internet et de la puissance de ton appareil – les sous-titres sont lus dans l'image transmise par Remote Play. Le câble réseau ou le Wi-Fi 5 GHz fonctionnent le mieux.\nSi tu rencontres un problème, écris dans le groupe Facebook PlayCaption – tests ce qui s'est passé et dans quel jeu.\nMerci d'aider à améliorer l'application !",
            "PlayCaption está todavía en fase de pruebas, así que algo puede no funcionar.\nLa rapidez y precisión de los subtítulos dependen sobre todo de la velocidad de tu conexión a internet y de la potencia de tu dispositivo: los subtítulos se leen de la imagen que llega por Remote Play. Funciona mejor con cable de red o Wi-Fi de 5 GHz.\nSi encuentras un problema, escribe en el grupo de Facebook PlayCaption – pruebas qué pasó y en qué juego.\n¡Gracias por ayudar a mejorar la aplicación!",
            "PlayCaption è ancora in fase di test, quindi qualcosa potrebbe non funzionare.\nVelocità e precisione dei sottotitoli dipendono soprattutto dalla velocità della connessione internet e dalle prestazioni del dispositivo: i sottotitoli vengono letti dall'immagine che arriva tramite Remote Play. Funziona meglio con cavo di rete o Wi-Fi a 5 GHz.\nSe trovi un problema, scrivi nel gruppo Facebook PlayCaption – test cosa è successo e in quale gioco.\nGrazie per aiutare a migliorare l'app!"),
        "betaDal" to listOf("Pokračuje samo za pár sekund · OK = hned dál", "Pokračuje samo o pár sekúnd · OK = hneď ďalej", "Geht in ein paar Sekunden weiter · OK = sofort weiter", "Za chwilę przejdzie dalej · OK = od razu dalej", "Continue dans quelques secondes · OK = continuer", "Continúa en unos segundos · OK = seguir ya", "Prosegue tra pochi secondi · OK = vai avanti"),
        "betaSkupina" to listOf("Naskenuj a napiš nám", "Naskenuj a napíš nám", "Scannen und schreiben", "Zeskanuj i napisz", "Scanne et écris-nous", "Escanea y escríbenos", "Scansiona e scrivici"),
        "tipPripojeni" to listOf(
            "💡 Rychlost a přesnost titulků závisí hlavně na rychlosti internetového připojení a výkonu zařízení. Nejlépe funguje síťový kabel nebo 5 GHz Wi-Fi.",
            "💡 Rýchlosť a presnosť titulkov závisí hlavne od rýchlosti internetového pripojenia a výkonu zariadenia. Najlepšie funguje sieťový kábel alebo 5 GHz Wi-Fi.",
            "💡 Tempo und Genauigkeit der Untertitel hängen vor allem von der Geschwindigkeit deiner Internetverbindung und der Leistung deines Geräts ab. Am besten: LAN-Kabel oder 5-GHz-WLAN.",
            "💡 Szybkość i dokładność napisów zależą głównie od szybkości połączenia z internetem i wydajności urządzenia. Najlepiej działa kabel sieciowy lub Wi-Fi 5 GHz.",
            "💡 La rapidité et la précision des sous-titres dépendent surtout de la vitesse de ta connexion internet et de la puissance de ton appareil. Idéal : câble réseau ou Wi-Fi 5 GHz.",
            "💡 La rapidez y precisión de los subtítulos dependen sobre todo de la velocidad de tu conexión a internet y de la potencia de tu dispositivo. Mejor con cable de red o Wi-Fi de 5 GHz.",
            "💡 Velocità e precisione dei sottotitoli dipendono soprattutto dalla velocità della connessione internet e dalle prestazioni del dispositivo. Meglio con cavo di rete o Wi-Fi a 5 GHz."),
        "brzy" to listOf("připravujeme", "pripravujeme", "in Vorbereitung", "wkrótce", "bientôt", "próximamente", "in arrivo"),
        "podpora" to listOf("Podpora", "Podpora", "Unterstützung", "Wsparcie", "Soutien", "Apoyo", "Supporto"),
        "podporaNadpis" to listOf("Líbí se ti PlayCaption?", "Páči sa ti PlayCaption?", "Gefällt dir PlayCaption?", "Podoba ci się PlayCaption?", "Tu aimes PlayCaption ?", "¿Te gusta PlayCaption?", "Ti piace PlayCaption?"),
        "podporaText" to listOf(
            "Podpoř další vývoj – kup mi kafe ☕\nNaskenuj QR kód v bankovní aplikaci, částku si zvol sám.",
            "Podpor ďalší vývoj – kúp mi kávu ☕\nNaskenuj QR kód v bankovej aplikácii, sumu si zvoľ sám.",
            "Unterstütze die Weiterentwicklung – spendier mir einen Kaffee ☕\nScanne den QR-Code in deiner Banking-App, den Betrag wählst du selbst.",
            "Wesprzyj dalszy rozwój – postaw mi kawę ☕\nZeskanuj kod QR w aplikacji bankowej, kwotę wybierasz sam.",
            "Soutiens le développement – offre-moi un café ☕\nScanne le QR code dans ton appli bancaire, tu choisis le montant.",
            "Apoya el desarrollo – invítame a un café ☕\nEscanea el código QR en tu app del banco, tú eliges la cantidad.",
            "Sostieni lo sviluppo – offrimi un caffè ☕\nScansiona il codice QR nella tua app bancaria, l'importo lo scegli tu."),
        "hratInfo" to listOf("✔ PlayCaption běží – přepni na PlayStation a hraj", "✔ PlayCaption beží – prepni na PlayStation a hraj", "✔ PlayCaption läuft – wechsle zur PlayStation und spiel", "✔ PlayCaption działa – przełącz na PlayStation i graj", "✔ PlayCaption est actif – passe sur la PlayStation et joue", "✔ PlayCaption activo – cambia a la PlayStation y juega", "✔ PlayCaption attivo – passa alla PlayStation e gioca"),
        "zpet" to listOf("Zpět", "Späť", "Zurück", "Wstecz", "Retour", "Atrás", "Indietro"),
        "povoleni" to listOf("Povolit zobrazení přes jiné aplikace", "Povoliť zobrazenie cez iné aplikácie", "Über anderen Apps anzeigen erlauben", "Zezwól na wyświetlanie nad innymi aplikacjami", "Autoriser l'affichage par-dessus", "Permitir mostrar sobre otras apps", "Consenti la visualizzazione sopra altre app"),
        "povoleniPopis" to listOf(
            "Bez tohoto povolení nemůže PlayCaption ukázat překlad přes hru.", "Bez tohto povolenia nemôže PlayCaption ukázať preklad cez hru.",
            "Ohne diese Erlaubnis kann PlayCaption die Übersetzung nicht über dem Spiel zeigen.", "Bez tego PlayCaption nie pokaże tłumaczenia nad grą.",
            "Sans cette autorisation, PlayCaption ne peut pas afficher la traduction.", "Sin este permiso PlayCaption no puede mostrar la traducción.",
            "Senza questo permesso PlayCaption non può mostrare la traduzione."),
        "predemNadpis" to listOf("Než začneš", "Skôr než začneš", "Bevor du beginnst", "Zanim zaczniesz", "Avant de commencer", "Antes de empezar", "Prima di iniziare"),
        "predem" to listOf(
            "Potřebuješ na PS5 druhý účet – přes něj se televize připojí. Hrát budeš dál na hlavním.\nNemáš ho? Na PS5: tlačítko PS → profil → Přepnout uživatele → Přidat uživatele.",
            "Potrebuješ na PS5 druhý účet – cez neho sa televízor pripojí. Hrať budeš ďalej na hlavnom.\nNemáš ho? Na PS5: tlačidlo PS → profil → Prepnúť používateľa → Pridať používateľa.",
            "Du brauchst ein zweites Konto auf der PS5 – darüber verbindet sich der Fernseher. Spielen tust du weiter mit dem Hauptkonto.\nKeins? Auf der PS5: PS-Taste → Profil → Benutzer wechseln → Benutzer hinzufügen.",
            "Potrzebujesz drugiego konta na PS5 – przez nie łączy się telewizor. Grasz dalej na głównym.\nNie masz? Na PS5: przycisk PS → profil → Przełącz użytkownika → Dodaj użytkownika.",
            "Il te faut un deuxième compte sur la PS5 – la TV s'y connecte. Tu continues à jouer sur le principal.\nPas de compte ? Sur la PS5 : bouton PS → profil → Changer d'utilisateur → Ajouter un utilisateur.",
            "Necesitas una segunda cuenta en la PS5: con ella se conecta la TV. Sigues jugando con la principal.\n¿No la tienes? En la PS5: botón PS → perfil → Cambiar de usuario → Añadir usuario.",
            "Serve un secondo account sulla PS5: la TV si collega con quello. Continui a giocare con il principale.\nNon ce l'hai? Sulla PS5: tasto PS → profilo → Cambia utente → Aggiungi utente."),
        "krok1" to listOf(
            "1. Přihlas se k PlayStation DRUHÝM účtem z PS5 – ne hlavním, na kterém hraješ. Přihlášení proběhne přes telefon.",
            "1. Prihlás sa k PlayStation DRUHÝM účtom z PS5 – nie hlavným, na ktorom hráš. Prihlásenie prebehne cez telefón.",
            "1. Melde dich mit dem ZWEITEN PS5-Konto bei PlayStation an – nicht mit dem Hauptkonto, mit dem du spielst. Die Anmeldung läuft über dein Handy.",
            "1. Zaloguj się do PlayStation DRUGIM kontem z PS5 – nie głównym, na którym grasz. Logowanie odbywa się przez telefon.",
            "1. Connecte-toi à PlayStation avec le DEUXIÈME compte de la PS5 – pas le principal sur lequel tu joues. La connexion se fait via ton téléphone.",
            "1. Inicia sesión en PlayStation con la SEGUNDA cuenta de la PS5, no con la principal con la que juegas. El inicio de sesión se hace con el móvil.",
            "1. Accedi a PlayStation con il SECONDO account della PS5, non quello principale con cui giochi. L'accesso avviene tramite il telefono."),
        "prihlasit" to listOf("Přihlásit se", "Prihlásiť sa", "Anmelden", "Zaloguj się", "Se connecter", "Iniciar sesión", "Accedi"),
        "zmenitUcet" to listOf("Změnit účet", "Zmeniť účet", "Konto ändern", "Zmień konto", "Changer de compte", "Cambiar cuenta", "Cambia account"),
        "ucetOk" to listOf("Účet PlayStation: ✔", "Účet PlayStation: ✔", "PlayStation-Konto: ✔", "Konto PlayStation: ✔", "Compte PlayStation : ✔", "Cuenta PlayStation: ✔", "Account PlayStation: ✔"),
        "ucetChyba" to listOf("Přihlášení se nepovedlo – zkus to znovu.", "Prihlásenie sa nepodarilo – skús znova.", "Anmeldung fehlgeschlagen – bitte erneut versuchen.", "Logowanie nie powiodło się – spróbuj ponownie.", "Échec de la connexion – réessaie.", "No se pudo iniciar sesión – inténtalo de nuevo.", "Accesso non riuscito – riprova."),
        "krok2" to listOf("2. Vyhledání PS5 pro Remote Play", "2. Vyhľadanie PS5 pre Remote Play", "2. PS5 für Remote Play suchen", "2. Wyszukiwanie PS5 dla Remote Play", "2. Recherche de la PS5 (Lecture à distance)", "2. Buscar la PS5 para Juego remoto", "2. Ricerca della PS5 per Riproduzione remota"),
        "hledam" to listOf("Hledám PS5…", "Hľadám PS5…", "Suche PS5…", "Szukam PS5…", "Recherche…", "Buscando…", "Ricerca…"),
        "nalezena" to listOf("PS5 nalezena", "PS5 nájdená", "PS5 gefunden", "Znaleziono PS5", "PS5 trouvée", "PS5 encontrada", "PS5 trovata"),
        "nenalezena" to listOf(
            "PS5 nenalezena – je zapnutá a ve stejné síti?", "PS5 nenájdená – je zapnutá a v rovnakej sieti?",
            "Keine PS5 gefunden – ist sie an und im selben Netz?", "Nie znaleziono PS5 – czy jest włączona i w tej samej sieci?",
            "PS5 introuvable – est-elle allumée et sur le même réseau ?", "No se encontró la PS5: ¿está encendida y en la misma red?",
            "PS5 non trovata – è accesa e nella stessa rete?"),
        "hledatZnovu" to listOf("Hledat znovu", "Hľadať znova", "Erneut suchen", "Szukaj ponownie", "Rechercher à nouveau", "Buscar de nuevo", "Cerca di nuovo"),
        "krok3" to listOf(
            "3. Na PS5 nejdřív zapni Nastavení → Systém → Aplikace Remote Play → Povolit aplikaci Remote Play – a to na OBOU účtech (hlavním i druhém).\n   Pak se přepni na DRUHÝ účet (ne hlavní, na kterém hraješ) a dej Aplikace Remote Play → Spárovat zařízení. Kód si opiš nebo vyfoť.\n   Obrazovku s kódem na PS5 NEZAVÍREJ – jen na ovladači televize zmáčkni Domů (domeček) a vrať se sem do PlayCaption.\n   Kód zadej dřív, než na PS5 vyprší čas. Když vyprší, vygeneruj na PS5 nový.",
            "3. Na PS5 najprv zapni Nastavenia → Systém → Aplikácia Remote Play → Povoliť aplikáciu Remote Play – a to na OBOCH účtoch (hlavnom aj druhom).\n   Potom sa prepni na DRUHÝ účet (nie hlavný, na ktorom hráš) a daj Aplikácia Remote Play → Spárovať zariadenie. Kód si opíš alebo odfoť.\n   Obrazovku s kódom na PS5 NEZATVÁRAJ – len na ovládači televízora stlač Domov (domček) a vráť sa sem do PlayCaption.\n   Kód zadaj skôr, než na PS5 vyprší čas. Keď vyprší, vygeneruj na PS5 nový.",
            "3. Aktiviere auf der PS5 zuerst die Remote Play-App auf BEIDEN Konten (Haupt- und Zweitkonto).\n   Wechsle dann zum ZWEITEN Konto (nicht dem Hauptkonto, mit dem du spielst): Einstellungen → System → Remote Play-App → Gerät verbinden. Code notieren oder fotografieren.\n   Den Code-Bildschirm auf der PS5 NICHT schließen – nur auf der TV-Fernbedienung Home drücken und zurück zu PlayCaption.\n   Code eingeben, bevor die Zeit auf der PS5 abläuft – sonst einen neuen erzeugen.",
            "3. Najpierw włącz na PS5 aplikację Remote Play na OBU kontach (głównym i drugim).\n   Potem przełącz się na DRUGIE konto (nie główne, na którym grasz): Ustawienia → System → Aplikacja Remote Play → Połącz urządzenie. Przepisz lub sfotografuj kod.\n   NIE zamykaj ekranu z kodem na PS5 – na pilocie TV naciśnij Home i wróć do PlayCaption.\n   Wpisz kod, zanim na PS5 skończy się czas – inaczej wygeneruj nowy.",
            "3. Sur la PS5, active d'abord l'application Lecture à distance sur les DEUX comptes (principal et deuxième).\n   Puis passe sur le DEUXIÈME compte (pas le principal sur lequel tu joues) : Paramètres → Système → Application Lecture à distance → Associer un appareil. Note ou photographie le code.\n   Ne FERME PAS l'écran du code sur la PS5 – appuie seulement sur Accueil sur la télécommande de la TV et reviens à PlayCaption.\n   Saisis le code avant la fin du délai sur la PS5, sinon génère-en un nouveau.",
            "3. En la PS5 activa primero la app Juego remoto en AMBAS cuentas (principal y segunda).\n   Luego cambia a la SEGUNDA cuenta (no la principal con la que juegas): Ajustes → Sistema → App Juego remoto → Vincular dispositivo. Apunta o fotografía el código.\n   NO cierres la pantalla del código en la PS5: solo pulsa Inicio en el mando de la TV y vuelve a PlayCaption.\n   Escribe el código antes de que se acabe el tiempo en la PS5; si no, genera uno nuevo.",
            "3. Sulla PS5 attiva prima l'app Riproduzione remota su ENTRAMBI gli account (principale e secondo).\n   Poi passa al SECONDO account (non quello principale con cui giochi): Impostazioni → Sistema → App Riproduzione remota → Collega dispositivo. Annota o fotografa il codice.\n   NON chiudere la schermata del codice sulla PS5: premi solo Home sul telecomando della TV e torna a PlayCaption.\n   Inserisci il codice prima che scada il tempo sulla PS5, altrimenti generane uno nuovo."),
        "sparovat" to listOf("Spárovat", "Spárovať", "Koppeln", "Sparuj", "Associer", "Vincular", "Associa"),
        "paruji" to listOf("Páruji…", "Párujem…", "Kopple…", "Paruję…", "Association…", "Vinculando…", "Associazione…"),
        "pinChyba" to listOf("PIN musí mít 8 číslic.", "PIN musí mať 8 číslic.", "Der PIN hat 8 Ziffern.", "PIN musi mieć 8 cyfr.", "Le PIN comporte 8 chiffres.", "El PIN tiene 8 cifras.", "Il PIN ha 8 cifre."),
        "nejdrivUcet" to listOf("Nejdřív se přihlas (krok 1).", "Najprv sa prihlás (krok 1).", "Zuerst anmelden (Schritt 1).", "Najpierw zaloguj się (krok 1).", "Connecte-toi d'abord (étape 1).", "Primero inicia sesión (paso 1).", "Prima accedi (passo 1)."),
        "zastavit" to listOf("Zastavit", "Zastaviť", "Stopp", "Zatrzymaj", "Arrêter", "Detener", "Ferma"),
        "telefonCekam" to listOf("Čekám na titulky ze hry…", "Čakám na titulky z hry…", "Warte auf Untertitel…", "Czekam na napisy z gry…", "En attente des sous-titres…", "Esperando subtítulos…", "In attesa dei sottotitoli…"),
        "telefonTip" to listOf(
            "Ťukni na text pro změnu velikosti písma", "Ťukni na text pre zmenu veľkosti písma",
            "Tippe auf den Text, um die Schriftgröße zu ändern", "Dotknij tekstu, aby zmienić rozmiar czcionki",
            "Touche le texte pour changer la taille", "Toca el texto para cambiar el tamaño",
            "Tocca il testo per cambiare la dimensione"),
        "prevzato" to listOf("PS5 teď používá televize", "PS5 teraz používa televízor", "Die PS5 wird jetzt vom Fernseher genutzt", "PS5 używa teraz telewizor", "La PS5 est maintenant utilisée par la TV", "La PS5 la está usando ahora la TV", "La PS5 ora è usata dalla TV"),
        "infoStart" to listOf("PlayCaption: překlad se spouští…", "PlayCaption: preklad sa spúšťa…", "PlayCaption: Übersetzung startet…", "PlayCaption: uruchamiam tłumaczenie…", "PlayCaption : démarrage de la traduction…", "PlayCaption: iniciando traducción…", "PlayCaption: avvio traduzione…"),
        "infoAktivni" to listOf("✔ PlayCaption: překlad aktivní", "✔ PlayCaption: preklad aktívny", "✔ PlayCaption: Übersetzung aktiv", "✔ PlayCaption: tłumaczenie aktywne", "✔ PlayCaption : traduction active", "✔ PlayCaption: traducción activa", "✔ PlayCaption: traduzione attiva")
    )

    fun t(kod: String, klic: String): String {
        val i = KODY.indexOf(kod).coerceAtLeast(0)
        return T[klic]?.getOrNull(i) ?: T[klic]?.first() ?: klic
    }

    fun t(context: Context, klic: String) = t(Nastaveni(context).jazykNeboCs, klic)
}
