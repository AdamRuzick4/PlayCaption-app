# Titulky TV – krok 1 (kostra + overlay)

1. Otevři složku v Android Studiu (Hedgehog nebo novější), počkej na Gradle sync
   (wrapper se vygeneruje sám; případně File → Sync Project with Gradle Files).
2. Spusť na Android TV (emulátor "Android TV 1080p" nebo skutečná TV přes ADB).
3. V aplikaci: povol overlay → Spustit titulky → otevři jinou aplikaci (např. YouTube).
   Přes obraz se musí ukázat testovací titulek.

ADB na skutečné TV:
    adb connect <IP_TV>:5555
    adb shell appops set cz.titulky.tv SYSTEM_ALERT_WINDOW allow   # pokud TV nemá obrazovku s oprávněním

Další kroky: capture/ (Remote Play) → ocr/ (ML Kit) → translate/ (EN→CS + cache).
