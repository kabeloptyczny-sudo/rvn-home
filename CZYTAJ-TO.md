# Frutiger Home – wersja 0.1 alpha

Wersja testowa. **Nie była uruchomiona na prawdziwym telewizorze** – to pierwsza wersja do przetestowania.
Skrypty niczego nie usuwają z TV (poza samym Frutiger Home przy przywracaniu). Aplikacje są tylko wyłączane.

## Co jest w paczce
- `uruchom.bat` – menu: łączenie z TV, kopia, odchudzanie, instalacja, przywracanie
- `przywroc.bat` – od razu cofa wszystko, co skrypt zmienił
- `tv-toolkit.ps1` – właściwy skrypt (PowerShell, Windows)
- `launcher/` – projekt ekranu głównego (Kotlin, Android Studio)
- `.github/workflows/build.yml` – automatyczne budowanie APK na GitHubie (opcja bez instalowania czegokolwiek)

## Krok 1: zdobądź plik APK (ekran główny)
**Wariant A – Android Studio:** otwórz folder `launcher`, poczekaj na synchronizację, wybierz
Build → Build APK(s). Plik: `launcher/app/build/outputs/apk/debug/app-debug.apk`.

**Wariant B – bez instalowania:** załóż darmowe konto na github.com, utwórz repozytorium, wgraj całą zawartość tej paczki
(razem z ukrytym folderem `.github`), wejdź w zakładkę Actions → Build APK → Run workflow. Po kilku minutach pobierz `app-debug.apk` z Artifacts.

Skopiuj `app-debug.apk` do folderu ze skryptem.

## Krok 2: telewizor
Ustawienia → Informacje → 7× kliknij „Kompilacja” → Opcje programisty → włącz debugowanie USB i
debugowanie sieciowe / bezprzewodowe. Komputer i TV w tej samej sieci Wi-Fi/LAN.

## Krok 3: uruchom `uruchom.bat`
1. Opcja 1 – połącz (na TV zatwierdź „Zawsze zezwalaj”)
2. Opcja 2 – kopia zapasowa
3. Opcja 3 – odchudzanie (decydujesz o każdej rzeczy)
4. Opcja 4 – instalacja ekranu głównego
5. Opcja 5 – własne tapety (opcjonalnie)

Po każdym kroku skrypt zapisuje zrzut ekranu z TV w `data\<marka_model>\screenshots`.
Kopie i dziennik zmian są w `data\<marka_model>\`.

## Przywracanie
`przywroc.bat` albo opcja 6. Włącza z powrotem wszystko, co wyłączył skrypt, i (za Twoją zgodą) odinstalowuje Frutiger Home.
Gdyby TV został bez ekranu głównego: zrestartuj go (odłącz zasilanie na 30 s) i uruchom `przywroc.bat`.

## Tapety
Wbudowane są 3 tapety w stylu Frutiger Aero (rysowane kodem, bez internetu), zmieniają się co 3 minuty.
Własne zdjęcia: opcja 5 (wgrywa je do folderu aplikacji). Zdjęcia z przyrodą pojawiają się wtedy przed wbudowanymi.

## Obsługa ekranu głównego
Strzałki + OK. Ostatnia ikona „Aplikacje” otwiera listę wszystkich; **przytrzymaj OK**, żeby dodać lub usunąć aplikację z doku (★).
Bez ustawień ukrytych – dok pokazuje do 8 aplikacji.

## Znane ograniczenia 0.1
- Brak polecanych treści (to zamknięte dane Netflixa/YouTube).
- Lista „znanych” aplikacji do wyłączenia jest krótka; nazwy pakietów Sony/TCL pokazuje skrypt do ręcznego wyboru.
- Po aktualizacji TV wyłączone aplikacje mogą wrócić – uruchom opcję 3 jeszcze raz.
- Wyłączenie starego ekranu głównego jest opcjonalne i na niektórych TV może zepsuć Asystenta lub zmianę wejść.
