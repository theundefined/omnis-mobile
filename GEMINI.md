# Projekt: omnis-mobile - Stan Wiedzy i Wytyczne Inżynierskie

Ten dokument stanowi centralne źródło prawdy o architekturze, procesach i standardach projektu `omnis-mobile`.

## 1. Architektura CI/CD (Model Nowoczesny)

Projekt stosuje rygorystyczny model **Continuous Delivery**, w którym GitHub Actions pełni rolę "Gatekeepera":
- **Trigger:** Skrypt `release.sh` wykonuje commit o treści `release: vX.Y.Z`. Nie tworzy tagów lokalnie.
- **Weryfikacja:** GitHub Actions buduje wersję Release/AAB. Jeśli build zawiedzie, proces jest przerywany i żadne wydanie nie powstaje.
- **Publikacja:** Tylko po pomyślnym buildzie GitHub samodzielnie tworzy tag Git oraz GitHub Release z załącznikami.
- **Czystość:** Zwykłe pushe budują tylko wersję Debug (bez sygnatur), eliminując fałszywe błędy budowania w panelu Actions.

## 2. Bezpieczeństwo i Podpisywanie

- **Sygnatura:** Aplikacja jest podpisana stałym kluczem produkcyjnym `omnis.jks`. Pozwala to na instalację aktualizacji bez odinstalowywania poprzedniej wersji.
- **Sekrety:** Dane wrażliwe (Keystore Base64, hasła, tokeny Sentry) są przechowywane wyłącznie w **GitHub Secrets**.
- **Wstrzykiwanie:** Klucze są wstrzykiwane do `AndroidManifest.xml` za pomocą `manifestPlaceholders` w Gradle, co chroni je przed upublicznieniem w repozytorium.
- **Lokalnie:** Dane uwierzytelniające w telefonie są szyfrowane za pomocą `EncryptedSharedPreferences`.

## 3. Standardy Techniczne (Android)

- **Serializacja:** Wyłącznie `kotlinx.serialization`. Unikamy GSON ze względu na problemy z optymalizacją R8 w wersji Release.
- **R8 / Optymalizacja:** Wersja Release ma tymczasowo wyłączone `isMinifyEnabled` w celu zapewnienia maksymalnej stabilności (poprzednie próby z `-dontobfuscate` wykazywały regresje).
- **Monitoring:** Integracja z **Sentry.io** (region EU: `de.sentry.io`). Automatyczny upload mapowań ProGuard (gdy R8 jest włączony) jest skonfigurowany i zweryfikowany.
- **UX Logowania:** Wsparcie dla **Android Autofill**. Błędy logowania (401) są wyłapywane natychmiast w formularzu.

## 4. Wytyczne Komunikacji (Zasady Inżynierskie)

- **Weryfikacja empiryczna:** Żadna zmiana nie jest ogłaszana jako "działająca" przed sprawdzeniem logów CI (`gh run watch`) lub testem na urządzeniu.
- **Język faktów:** Unikamy emocjonalnych zapewnień na rzecz raportów technicznych.
- **Higiena repozytorium:** Katalogi tymczasowe (`tmp/`), pliki lokalne (`.env`, `local.properties`) oraz binarne klucze (`*.jks`) są rygorystycznie ignorowane przez `.gitignore`.

## 5. Przyszłe Rozszerzenia (Backlog)

- **Kody kreskowe:** Wyświetlanie karty bibliotecznej jako Code 128.
- **Bulk Renew:** Przycisk przedłużenia wszystkich książek jednocześnie.
- **Powiadomienia:** Przypomnienia o kończących się terminach w tle.
- **Grupowanie:** Rozbudowa widoku filii o informację o użytkowniku wypożyczającym.
- **Historia wypożyczeń:** zaimplementowana (paginacja + trwały cache per stronę).
- **Wyszukiwanie książek w katalogu:** zaimplementowane — checkboxy preferowanych filii (trwałe per biblioteka), wszystkie wydania, termin zwrotu dla wypożyczonych; wyszukiwanie równoległe po unikalnych bibliotekach z flagą "preferowane do wyszukiwania" na koncie.
- **Okładki/szczegóły wydania (okładka, ISBN, gatunki), licznik rezerwacji (Requests — już pobierany w getCounters, nieużywany w UI), katalog filii Biblioteki Raczyńskich (adres/godziny/mapa):** nieużywane dziś możliwości omnis-py, odkryte przy pracy nad wyszukiwaniem.
