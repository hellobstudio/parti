# PartyTime 🎉

Wifin játszható parti app Androidra – **Titkos küldetés**, **Activity** és **TOP10**.

## Hogyan működik?

- A **játékmester** telefonja a szerver: az app elindít rajta egy kis webszervert (8765-ös port), és a wifin hirdeti magát.
- A **játékosok** az appban a „Játékos vagyok” gombra koppintva automatikusan látják a futó játékot (ugyanazon a wifin, vagy a játékmester hotspotján).
- **App nélkül is lehet játszani** (pl. iPhone-ról): a játékmester a *Játékosok* fülön mutat egy QR-kódot és egy címet (pl. `http://192.168.1.23:8765`) – ezt böngészőben megnyitva ugyanaz a felület jelenik meg.
- Internet nem kell, csak közös wifi / hotspot.
- A játék állása folyamatosan mentődik a játékmester telefonján; ha az app bezáródik, „Előző játék folytatása” gombbal visszatölthető.
- Ha valakinek lemerül a telefonja, ugyanazzal a névvel visszaléphet, és megmaradnak a pontjai.

## Játékok

### 🕵️ Titkos küldetés
- A játékmester elindítja → minden jelenlévő kap egy véletlen küldetést; **ugyanazt a küldetést két ember soha nem kapja meg**.
- Aki később érkezik, vagy teljesítette a küldetését, kérhet magának újat.
- A küldetés le van takarva, csak koppintásra látszik (és 12 mp múlva újra eltakarja magát).
- „Küldetés teljesítve” → ki kell jelölni, kit sikerült rávenni (több is lehet), plusz megjegyzés.
- A kijelölt játékos értesítést kap: „Lebuktál! … titkos küldetése ez volt …”.
- Ranglista mindenkinek; a részletes napló csak a játékmesternél, amíg fel nem fedi.
- 40 beépített küldetés (az eredeti 15 + 25 új), és a játékmester saját küldetést is hozzáadhat.
- „Másikat kérek” gomb, ha egy küldetés teljesíthetetlen.

### 🎭 Activity
- Két csapat: **Kékek** és **Pirosak** – a játékmester kisorsolja, vagy mindenki maga választ (a játékmester át is rakhat bárkit).
- Bárki beküldhet feladványt kategóriával (Híres ember, Film / sorozat, Állat, Tárgy / fogalom).
- A feladványt mindig a **másik csapat** egyik játékosa adja elő; az app választja ki az előadót (aki még kevesebbszer volt soron).
- A feladványt az előadó és a másik csapat látja – az előadó csapata nem.
- Előadás módja: vegyes (mutogatás / rajzolás / körülírás) vagy csak mutogatás.
- A visszaszámlálás (alapból 60 mp, állítható) **minden telefonon** fut, az utolsó 5 mp-ben csipog, a végén riaszt és rezeg.
- „Kitalálták / Nem sikerült” → pontozás csapatonként, előzménylista.

### 🔟 TOP10
- Gombnyomásra mindenki kap egy különböző számot 1–10 között (letakarva).
- Ha 10-nél többen vannak, a játékmester nem kap számot (ha így is több mint 10 játékos van, a többletből véletlenszerűen kimarad valaki).
- Minden körhöz véletlen téma jár (32 beépített), vagy a játékmester saját témát ír.
- „Számok felfedése” gomb a kör végén.

## APK készítése GitHubon

1. Hozz létre egy új (akár privát) repót a GitHubon, pl. `PartyTime` néven.
2. Töltsd fel a mappa **teljes tartalmát** (Add file → Upload files, vagy `git push`).
   Ellenőrizd, hogy a `.github/workflows/build-apk.yml` fájl is felkerült (a ponttal kezdődő mappák néha rejtettek).
3. Feltöltés után az **Actions** fülön automatikusan elindul az „APK build” (kb. 3–5 perc).
4. Kész build → kattints rá → lent az **Artifacts** résznél töltsd le a `PartyTime-APK-…` zipet; benne van az APK.
5. Telefonra közvetlen letöltéshez: **Releases → Draft a new release**, tag: `v1.0` → Publish. A build ezután magát az APK-t csatolja a release-hez, amit a telefon böngészőjéből le lehet tölteni.

Telepítéskor engedélyezni kell az „ismeretlen forrásból származó alkalmazások” telepítését.
Minden build ugyanazzal a kulccsal (`app/partytime.keystore`) van aláírva, így az új verzió felülírja a régit.
(Ha a Play Áruházba is feltennéd, cserélj saját, titkos kulcsra.)

## Fejlesztés

- `app/src/main/java/hu/partytime/core/` – játéklogika + HTTP szerver (tiszta Java, Android nélkül is fut)
- `app/src/main/java/hu/partytime/app/` – Android: indítóképernyő, háttérszolgáltatás, wifis felderítés
- `app/src/main/assets/web/` – a játék felülete (HTML/CSS/JS), ezt látja mindenki
- `app/src/main/assets/start.html` – az app kezdőképernyője (játékmester / játékos)
- `devserver/` – gépen futtatható szerver a felület teszteléséhez, és a CI-ban futó logikai tesztek

Gépen kipróbálás (Java 17+):

```
javac -encoding UTF-8 -d build/dev app/src/main/java/hu/partytime/core/*.java devserver/DevServer.java
java -cp build/dev DevServer app/src/main/assets Mester
```
A kiírt `HOST …` címet nyisd meg a böngészőben (játékmester), a többi lapon pedig `http://127.0.0.1:8765`.

## Ha valami nem működik

- **A játékos nem látja a játékot:** ellenőrizd, hogy ugyanazon a wifin vagytok-e. Egyes routerek (vendég wifi, „AP isolation”) tiltják, hogy a telefonok lássák egymást – ilyenkor a játékmester kapcsoljon be hotspotot, és arra csatlakozzon mindenki. Kézzel is beírható a cím.
- **Megszakad a kapcsolat, ha a játékmester lezárja a telefont:** az app háttérszolgáltatással fut, de néhány gyártó (Xiaomi, Huawei, Samsung) agresszív energiatakarékossága leállíthatja. Ilyenkor a beállításokban az akkumulátor-optimalizálást kapcsold ki a PartyTime-nál, vagy hagyd bekapcsolva a játékmester kijelzőjét (az app ébren tartja, amíg előtérben van).
