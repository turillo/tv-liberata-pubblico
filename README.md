# TV Liberata

Una Home pulita per Fire TV Stick (e per le TV Android TV / Google TV): niente pubblicità, niente righe
consigliate, solo le tue app su uno sfondo che cambia, con meteo e uno screensaver vero. Una sola app per Fire TV e
Android TV: riconosce da sola su quale gira.

Non serve root, non si disinstalla niente di Amazon: la Home Amazon resta installata e si torna indietro in un
attimo (vedi [Tornare alla Home Amazon](#tornare-alla-home-amazon)).

## Cosa fa

- **Home con sfondi**: foto del giorno di Bing e di Wikimedia Commons, scaricate sulla TV e tenute in un
  archivio. Le puoi attivare, disattivare, mettere tra le preferite o eliminare; cambiano da sole ogni 15, 30 o
  60 minuti e, se vuoi, a ogni accensione.
- **Le tue app**: una riga in basso e una griglia, che riordini e da cui nascondi quello che non usi (tieni premuto
  OK su un'app: Sposta, Togli dalla Home, **Disinstalla**). L'elenco delle app nascoste è in Impostazioni TV Liberata,
  Sistema. Lo store (Appstore su Fire TV, Play Store su Android TV) resta sempre in Home.
- **Meteo** in alto, per la tua città: trovata da sola dalla connessione internet, o scelta a mano.
- **Screensaver**: Orologio, "Preferite" (le tue foto preferite con dissolvenza e zoom lento) e, su Fire TV, Amazon
  Foto; "Avvia dopo" 1, 3, 5, 10, 15 o 30 minuti.
- **Margini schermo** per le TV che tagliano i bordi, **pulsante ⏻ "Spegni tutto"**, sezione **Sistema** (memoria,
  spazio, rete, pulizia, stato dei servizi, versione e canale).
- **Filtro DNS integrato** (Sistema → Filtro): blocca telemetria, pubblicità e aggiornamenti di Fire OS per tutta la
  TV, con una VPN locale che porta solo il DNS (lo streaming non passa di lì). Eccezioni e statistiche dal telecomando.
- **Catalogo**: app utili dalla loro fonte ufficiale (per ora Aerial Views), installate con un clic dopo il controllo
  dell'impronta e della firma.
- **Ripristina tutto**: riattiva le app di sistema che TV Liberata ha disattivato (serve il debug della TV, vedi sotto).
- **Aggiornamenti automatici**: di notte, a TV spenta, oppure proposti all'accensione.

Tutto si regola da **Impostazioni TV Liberata**, l'app delle impostazioni che arriva insieme alla Home.

## Requisiti

- Fire TV Stick con Fire OS 7 o più recente (provata su Fire TV Stick Lite e Fire TV Stick 3ª gen.), oppure una TV
  Android TV / Google TV con Android 8 o più recente.
- Connessione internet (per sfondi e meteo; senza rete restano le foto già scaricate).

## Installazione su Fire TV

La guida passo passo, solo col telecomando: [Guida-installazione-TV-Liberata.txt](Guida-installazione-TV-Liberata.txt).

**Fire OS 7.7**: Amazon non permette a TV Liberata di configurarsi da sola. Dopo averla installata con Downloader, la
configura una volta un PC Windows sulla stessa rete: scarica
[Configura-TV-Liberata.zip](https://github.com/turillo/tv-liberata-pubblico/releases/latest/download/Configura-TV-Liberata.zip)
(o apri `tinyurl.com/Tv-liberata-pc`), estrailo e fai doppio clic su `Configura-TV-Liberata.bat`. Le liste che usa
sono in questo repository: [pacchetto-app.json](pacchetto-app.json) e [pulizia-fireos.json](pulizia-fireos.json).

### Con un PC e adb (consigliata: fa anche diventare Home)

1. Sulla Fire TV: Impostazioni, La mia Fire TV, Informazioni, premi 7 volte sul nome del dispositivo; poi in
   Opzioni sviluppatore attiva **Debug ADB**. Annota l'indirizzo IP (Impostazioni, La mia Fire TV, Informazioni, Rete).
2. Sul PC installa gli [strumenti di piattaforma Android](https://developer.android.com/tools/releases/platform-tools)
   e scarica `tv-liberata.apk` dall'ultima [Release](https://github.com/turillo/tv-liberata-pubblico/releases/latest).
3. Da PowerShell, nella cartella `launcher` di questo repository:

   ```powershell
   .\firetv-home.ps1 -TvHost 192.168.1.60 -NoBuild -Apk C:\percorso\tv-liberata.apk
   ```

   Lo script salva prima le impostazioni che tocca, installa l'app, la rende Home e controlla che il tasto Home
   porti qui; se qualcosa non va rimette da solo la Home Amazon. Sulla TV conferma la richiesta di debug.
4. Facoltativo: lo screensaver di TV Liberata come screensaver di sistema si sceglie in Impostazioni TV Liberata
   (Screensaver, "Scene TV Liberata"): lo script le ha già dato il permesso che serve.
5. Filtro DNS: la prima volta che lo accendi la TV chiede il permesso VPN (una volta sola). Da PC si può dare con
   `adb shell appops set com.gathin.tvhome.liberata ACTIVATE_VPN allow`.

### Solo dal telecomando, con Downloader

1. Installa **Downloader** (AFTVnews) dall'Appstore e consenti "Installa app sconosciute" per Downloader
   (Impostazioni, La mia Fire TV, Opzioni sviluppatore).
2. In Downloader apri questo indirizzo e installa:

   ```
   https://github.com/turillo/tv-liberata-pubblico/releases/latest/download/tv-liberata.apk
   ```

3. Apri **TV Liberata** dalle tue app. Così è un'app normale: per farla diventare la Home al posto di quella
   Amazon serve il passo 3 della sezione adb (Fire OS non lo permette dal solo telecomando).

## Aggiornamenti

Ogni notte verso le 4, se la TV è spenta, l'app controlla le Release di questo repository. Se hai autorizzato il
debug della TV (Impostazioni TV Liberata, Sistema, Pulizia avanzata, **Autorizza**, poi "Consenti sempre" sulla TV)
installa la nuova versione da sola, e alla prima accensione ti dice "TV Liberata è stata aggiornata alla versione X"
con le novità. Senza quell'autorizzazione te la propone all'accensione; in Sistema, "Cerca aggiornamenti" controlla
subito. In quel caso l'installazione va consentita una volta: Impostazioni, La mia Fire TV, Opzioni sviluppatore,
**Installa app sconosciute**, attiva **TV Liberata**. Si installano solo aggiornamenti firmati con la stessa chiave.

Su Fire OS 7.7 Amazon non permette alle app di usare il debug della TV stessa (provato su Fire TV Stick Lite):
lì la configurazione la fa una volta il programma per PC (Configura-TV-Liberata.zip, vedi sopra). Dopo, TV Liberata
scarica gli aggiornamenti, ne controlla la firma e conferma da sola l'installazione di sistema; "Ripristina tutto" si
fa dal programma per PC ("Annulla tutto e torna come prima").

L'autorizzazione del debug permette all'app di dare comandi alla TV come farebbe un PC collegato: la usa solo per
installare i propri aggiornamenti, per la configurazione guidata e per "Ripristina tutto". Il debug ADB deve restare
acceso; chi è sulla stessa rete di casa può collegarsi alla porta 5555, ma solo dopo una conferma sullo schermo.

## Tornare alla Home Amazon

- Dal telecomando: Impostazioni, Accessibilità, disattiva **Home Fire TV** (se la tua Fire TV la elenca). Oppure
  disinstalla TV Liberata: la Home Amazon torna subito.
- Via adb: `adb shell settings delete secure enabled_accessibility_services`, poi premi Home.

Se dopo un aggiornamento di Fire OS ricompare la Home Amazon, TV Liberata si riprende la Home da sola al
riavvio successivo; nel frattempo la trovi tra le tue app ("Le tue app e i tuoi canali"), e con il tasto Opzioni
puoi spostarla in prima fila.

## Privacy

Nessun account, nessuna statistica, nessuna pubblicità. L'app chiede solo: le foto a Bing e Wikimedia Commons, il
meteo a [Open-Meteo](https://open-meteo.com), la città dalla connessione a [ipwho.is](https://ipwho.is) (una volta a
settimana, se non l'hai scelta a mano), le liste del filtro a jsDelivr (HaGeZi), e le Release, il catalogo e
`produzione.json` a GitHub.

## Compilare da sé

JDK 17 e Android SDK 34. Nella cartella `launcher`: `./gradlew assembleRelease`. Senza la chiave di firma
del progetto l'APK è firmato con la chiave di debug e non si aggiorna dalle Release.

## Crediti e licenza

TV Liberata nasce da [cleanyourtv](https://github.com/audiedv/cleanyourtv) di Gathin (licenza MIT), di cui
riusa il launcher; le aggiunte sono rilasciate con la stessa licenza MIT (file [LICENSE](LICENSE)). Il carattere
Outfit è distribuito con la SIL Open Font License 1.1 ([launcher/FONT-LICENSE-OFL.txt](launcher/FONT-LICENSE-OFL.txt)).
Foto: Bing (© dei rispettivi autori, uso come sfondo personale) e Wikimedia Commons (autore e licenza mostrati
sotto ogni foto). Meteo: Open-Meteo (CC BY 4.0). Immagini: NASA, ESA/Webb, ESA/Hubble, EUMETSAT.
"Fire TV" e "Firestick" sono marchi di Amazon; questo progetto non è affiliato ad Amazon.
