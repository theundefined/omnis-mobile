---
name: verify-change
description: Checklist and verification run for a change in omnis-mobile before commit/push — strings PL/EN, server-load rules, pure logic + tests, one Gradle run (spotless, unit tests, androidTest compile, lint filtered to the branch diff). Use after implementing a feature or fix, or before opening a PR.
---

# Weryfikacja zmiany w omnis-mobile

Maszyna deweloperska ma mało pamięci (2 rdzenie, 4 GB): **jedno wywołanie Gradle naraz**, bez
równoległych buildów i bez buildów w kilku worktree jednocześnie.

## 1. Przegląd przed buildem

- Logika decyzyjna w czystych funkcjach (bez `Context`) z testami w `app/src/test`.
- Każdy nowy tekst w `res/values/strings.xml` **i** `res/values-en/strings.xml`; polskie
  `plurals` mają `one`/`few`/`many`/`other`; argumenty formatu zgodne w obu językach.
- Ruch sieciowy zgodny z CLAUDE.md: strażnik — jedno logowanie na konto na przebieg, wyszukiwanie
  anonimowe przez guest token; widżety i UI nie robią sieci w tle.
- Zapisywane modele (kotlinx.serialization) mają wartości domyślne — stary JSON musi się wczytać;
  DTO z API (Gson) nullable.
- Intencje: eksportowane komponenty walidują wejście i zawsze kończą (`finish()`);
  `PendingIntent` jawne i `FLAG_IMMUTABLE`; `FileProvider` udostępnia tylko dedykowany katalog.
- WorkManager: `CANCEL_AND_REENQUEUE`/`REPLACE` przerywa trwający przebieg — do zmiany
  harmonogramu używaj `UPDATE`; prace wyzwalane przez użytkownika jako unikalne.
- Testy instrumentowane (`app/src/androidTest`) działają na demo/mocku i nigdy nie przedłużają
  wypożyczeń ani nie składają/anulują rezerwacji.
- CLAUDE.md (akapit o nowym zachowaniu) i lista funkcji w README.md zaktualizowane.

## 2. Jeden przebieg Gradle

```bash
./gradlew --console=plain spotlessApply testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin
```

`testDebugUnitTest assembleDebug` nie kompiluje `androidTest` — stąd ostatnie zadanie.
Gdy demon Gradle zniknie (brak pamięci): `--no-daemon -Dorg.gradle.workers.max=1`.

## 3. Lint tylko na zmienionych plikach

`main` ma już błędy lint (np. `LocalContextGetResourceValueCall`), a CI lintu nie uruchamia —
oceniaj tylko to, co wnosi gałąź:

```bash
./gradlew --console=plain --no-daemon -Dorg.gradle.workers.max=1 lintDebug || true
python3 - <<'EOF'
import xml.etree.ElementTree as ET, subprocess
changed = set(subprocess.check_output(['git', 'diff', '--name-only', 'main...HEAD']).decode().split())
for i in ET.parse('app/build/reports/lint-results-debug.xml').getroot().findall('issue'):
    f = i.find('location').get('file').split('omnis-mobile/')[-1]
    if f in changed and i.get('severity') in ('Error', 'Fatal'):
        print(i.get('id'), f, i.find('location').get('line'), '|', i.get('message')[:120])
EOF
```

## 4. Po pushu

CI (`build.yml`) sprawdza `spotlessCheck` i buduje; `ui-tests.yml` uruchamia testy
instrumentowane na emulatorze (lokalnie nie ma KVM). Sprawdź oba przebiegi przez
`gh run list --branch <gałąź>` i `gh run view <id> --log-failed`. Wydanie tylko przez
`release.sh` i tylko na wyraźne polecenie.
