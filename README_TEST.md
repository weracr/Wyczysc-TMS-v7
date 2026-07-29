# Wyczyść TMS - v6.3 PIN 1010 + nowoczesny wygląd

## Zmiany

- Dodany PIN administratora: `1010`.
- Kierowca widzi uproszczony ekran główny.
- Panel administratora jest ukryty za PIN-em.
- Dodana nowocześniejsza karta statusu i przyciski.
- Dodana ikona aplikacji inspirowana motywem miotły i szufelki.
- Aplikacja próbuje odczytać package name TMS z pliku `app/src/main/assets/tms.apk`.
- Nadal działa usługa dostępności `Wyczyść TMS - auto zgody`.

## Ważne przed buildem

Dodaj prawdziwy APK TMS jako:

`app/src/main/assets/tms.apk`

Jeśli TMS będzie miał inny package name w kolejnej wersji, aplikacja spróbuje wykryć go z APK.

## Test

1. Usuń stare wersje `Wyczyść TMS` z PM90.
2. Wgraj nową APK z GitHub Actions.
3. Otwórz aplikację.
4. Wejdź w `Panel administratora`.
5. Wpisz PIN: `1010`.
6. Włącz dostępność i administratora urządzenia.
7. Sprawdź status.
8. Testuj odinstalowanie, instalację i otwarcie TMS.
