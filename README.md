# Omnis Mobile 📚

Natywna aplikacja na system Android będąca mobilnym odpowiednikiem narzędzia [omnis-py](https://github.com/theundefined/omnis-py). Aplikacja służy do wygodnego zarządzania wieloma kontami bibliotecznymi działającymi w systemie OMNIS (m.in. Biblioteka Raczyńskich, Biblioteka Narodowa, biblioteki uniwersyteckie).

## ✨ Funkcje

- **Multi-konto:** Dodawaj i zarządzaj wieloma kontami z różnych bibliotek jednocześnie.
- **Bezpieczeństwo:** Dane uwierzytelniające są przechowywane w natywnym, szyfrowanym kontenerze systemowym (`EncryptedSharedPreferences`).
- **Inteligentne Grupowanie:** Przeglądaj książki posortowane według kont użytkowników lub według konkretnych filii bibliotecznych.
- **Terminy pod kontrolą:** Kolorowe oznaczenia terminów zwrotu (czerwony dla zaległych, żółty dla kończących się).
- **Przedłużanie (Renew):** Szybkie przedłużanie terminu zwrotu wybranych książek bezpośrednio z aplikacji.
- **Wygoda:**
    - Wsparcie dla Android Autofill (autouzupełnianie haseł).
    - Możliwość tymczasowego wyłączania kont bez ich usuwania.
    - Szybkie wyszukiwanie informacji o książce w Google.
    - Udostępnianie szczegółów wypożyczenia do innych aplikacji.

## 🏛️ Obsługiwane biblioteki

Aplikacja natywnie wspiera większość polskich bibliotek korzystających z systemu Ex Libris Primo, w tym:
- Biblioteka Raczyńskich w Poznaniu
- Biblioteka Narodowa
- Biblioteka UAM
- Uniwersytet Jagielloński
- Dolnośląska Biblioteka Publiczna
- ...i wiele innych.

## 🛠️ Technologia

Projekt został zbudowany z użyciem nowoczesnego stosu technologicznego Android:
- **Język:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Architektura:** MVVM + Flow
- **Sieć:** Retrofit + OkHttp
- **Zarządzanie SDK:** Java 21, Gradle 8.12, AGP 8.7.0

## 🚀 Budowanie i wydania

### GitHub Releases
Aplikacja jest automatycznie budowana przy każdym utworzeniu nowej wersji (Release) na GitHubie. Gotowe pliki APK są dołączane jako załączniki do wydania.

### Budowanie lokalne
Wymagane JDK 21 oraz zainstalowany system Android SDK.

```bash
./gradlew assembleDebug
```

## 🤝 Autor
Stworzone przez [TheUndefined](https://github.com/theundefined).
