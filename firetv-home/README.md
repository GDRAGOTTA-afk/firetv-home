# Home TV per Fire TV Stick

App in stile Apple TV per la tua Fire TV: griglia delle app che puoi spostare, sfondo che cambia ogni
minuto, anteprima grande dell'app selezionata e icone/anteprime personalizzabili con un link bitly.

Non sostituisce la Home di Amazon (Fire OS non lo permette): si apre dalla lista delle tue app.
Consiglio di metterla nella prima riga (seleziona l'app, premi il tasto con le tre linee, "Sposta").

## Telecomando

| Tasto | Cosa fa |
|---|---|
| Frecce | Selezionano un'app; l'anteprima in alto cambia dopo una breve sosta |
| OK | Apre l'app |
| Tieni premuto OK, oppure tasto Menu (tre linee) | Apre le opzioni dell'app |
| Indietro | Esce dall'app (o conferma lo spostamento) |

Opzioni: **Sposta**, **Cambia icona**, **Cambia anteprima**, **Ripristina immagini originali**,
**Nascondi app** (e "Mostra di nuovo le app nascoste").

Per spostare un'app scegli "Sposta", usa le frecce e premi OK per confermare. L'ordine resta salvato.

## Sfondi

Di serie ci sono 14 paesaggi (dal progetto CleanYourTV, licenza MIT). Per usare i tuoi, copia le immagini
JPG, PNG o WEBP (meglio 1920x1080) nella cartella dell'app sulla Fire TV. Se la cartella contiene
immagini, usa solo quelle; se la svuoti, torna a quelle di serie. Lo sfondo cambia ogni minuto, in
ordine casuale, senza ripetere lo stesso due volte di fila.

```
/sdcard/Android/data/tv.dashboard.home/files/wallpapers/
```

La cartella si crea alla prima apertura dell'app. Con ADB:

```
adb push foto.jpg /sdcard/Android/data/tv.dashboard.home/files/wallpapers/
```

## Icone e anteprime con un link bitly

Hai due modi:

1. **Dalla TV**: seleziona l'app, tieni premuto OK, scegli "Cambia icona" o "Cambia anteprima", incolla il
   link bitly e premi Salva. Il link deve portare direttamente a un'immagine JPG o PNG.
2. **Da file**: scrivi un file `links.txt`, una riga per immagine, e copialo in
   `/sdcard/Android/data/tv.dashboard.home/files/`. Viene letto all'apertura dell'app, solo se e' cambiato.

```
# pacchetto|icon|link     (icona del riquadro)
# pacchetto|preview|link  (grande anteprima in alto)
com.netflix.ninja|icon|https://bit.ly/xxxxxxx
com.netflix.ninja|preview|https://bit.ly/yyyyyyy
```

Un'immagine 16:9 va bene sia per le icone sia per le anteprime. Le immagini scaricate restano salvate
sulla TV: dopo averle scaricate non serve piu' la connessione.

## Compilare e installare

Non serve installare nulla sul computer: l'APK lo compila GitHub.

1. Crea un repository **pubblico** sul tuo account GitHub e carica tutti i file di questa cartella.
2. Crea il file `.github/workflows/build.yml` (Add file > Create new file, scrivendo il percorso con le
   barre) e incolla il contenuto di `build.yml`.
3. Nella scheda **Actions** attendi che la compilazione diventi verde (pochi minuti).
4. L'APK e' in **Releases**. Il link diretto e'
   `https://github.com/TUO-UTENTE/TUO-REPOSITORY/releases/download/build/home-tv.apk`
5. Sulla Fire TV apri l'app **Downloader**, scrivi quel link e installa.

Per aggiornare l'app basta ricaricare i file modificati: GitHub ricompila e il link resta lo stesso.
L'aggiornamento si installa sopra la versione vecchia senza perdere le impostazioni.

In alternativa, con ADB: `adb connect <IP della Fire TV>:5555` e poi `adb install -r home-tv.apk`.

## Note

- Il progetto e' scritto con le sole librerie standard di Android, senza dipendenze esterne.
- L'app usa Internet solo per scaricare le immagini dei link che inserisci tu.
- Sfondi inclusi: dal progetto CleanYourTV (MIT), vedi `LICENSE-CleanYourTV-wallpapers.txt`.
