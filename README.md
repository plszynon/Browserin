# ZenStar

Prosta przeglądarka internetowa (WebView) z:
- pobieraniem plików (przez systemowy DownloadManager)
- historią przeglądania
- zakładkami
- trybem incognito (nie zapisuje historii, gdy włączony)
- wbudowanym adblockiem (lista domen w `app/src/main/assets/adblock_hosts.txt`
  — możesz ją dowolnie rozszerzać, jedna domena na linię)

**Uwaga o rozszerzeniach:** prawdziwe wtyczki/rozszerzenia (jak w Chrome na
komputerze) nie są tu wspierane — to wymagałoby osobnego silnika przeglądarki.
Adblock jest wbudowany na stałe i działa automatycznie.

## Budowa APK (GitHub Actions)

Tak samo jak przy MC Launcherze:

1. Wrzuć zawartość tego folderu do nowego repo na GitHubie
2. Zakładka Actions zbuduje APK automatycznie
3. Pobierz gotowy plik z sekcji Artifacts
