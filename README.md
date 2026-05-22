# omnis-mobile

Natywna aplikacja Android do obsługi kont bibliotecznych w systemie OMNIS (Ex Libris Primo).

## Funkcjonalności
- Dodawanie wielu kont bibliotecznych.
- Bezpieczne przechowywanie haseł (`EncryptedSharedPreferences`).
- Podgląd wypożyczonych książek.
- Przedłużanie terminów zwrotu (Renew).

## Stos Technologiczny
- Kotlin + Jetpack Compose
- Retrofit + OkHttp
- MVVM Architecture

## Budowanie
Projekt używa Gradle. Aby zbudować aplikację, wykonaj:
```bash
./gradlew assembleDebug
```
(Wymaga zainstalowanego JDK 17).

## CI/CD
Aplikacja jest automatycznie budowana na GitHubie przy każdym pushu do gałęzi `main`.
