# Zero Android

Un agente di stampa che parla il protocollo dello Zero di Sublima. Riceve i
lavori sulla porta **55226** — oppure se li fa spingere da Sublima, restando in
ascolto — apre lui il socket verso stampanti e registratori, e risponde nella
forma che Sublima si aspetta.

Serve a togliere il PC dalle piccole installazioni: dove oggi serve uno Zero
acceso su Windows, Linux o Raspberry, basta un telefono o un tablet Android in
rete.

Il contratto che implementa e' descritto in `PROTOCOLLO.md`, nel repo dello Zero.

## Cosa sa fare

| Driver | Come |
|---|---|
| ESC/POS (comande) | socket sulla 9100 |
| Micrelec | dialogo su socket, con numero e chiusura restituiti a Sublima |
| Epson | `POST /cgi-bin/fpmate.cgi` |
| Custom | `POST /xml/printer.htm` con autenticazione |
| POS Ingenico | protocollo 17 su socket, con recupero anti-doppio-addebito |
| Comande in ascolto | si collega a Sublima e aspetta che sia il server a spingerle |

**Non interpreta il metalinguaggio a tag**, ed e' una scelta: la traduzione in
byte ESC/POS avviene dentro Sublima, in un posto solo, e non viene riscritta
qui per non farla divergere. L'agente lo dichiara in `/test_zero` con
`escpos_tag: false`, e la scheda del punto cassa avvisa se la configurazione non
combacia.

Restano fuori: seriale, USB e le casse automatiche (Cashmatic, VNE, Pagamico).

## Le comande in ascolto

Di solito e' il browser a bussare all'agente in rete locale, e per farlo deve
raggiungerlo: da li' nascono CORS, preflight, contenuto misto e Private Network
Access, su ogni palmare e su ogni rete. Con l'ascolto il verso si capovolge:
l'agente chiama Sublima e tiene aperta la connessione, e quando c'e' una comanda
e' il server a spingerla. **Il palmare non contatta piu' nessuno.**

**Nasce spento**, e non per prudenza generica: serve solo dove c'e' la
ristorazione, cioe' a una installazione su dieci. Sulle altre nove una
connessione tentata non sarebbe innocua — riuscirebbe, perche' il livello del
punto cassa non viene controllato all'apertura del flusso, e terrebbe occupato
per sempre uno dei sei thread di uWSGI del profilo per non consegnare niente.

Si accende in tre modi, tutti espliciti:

- dalla **schermata dell'app** (`Ascolto comande: SPENTO / ATTIVO`);
- dalla **pagina di stato** del tramite, con un pulsante;
- da **TEST ZERO**, ma solo se quel punto cassa e' impostato su SSE — cosi' chi
  ha la ristorazione lo ottiene senza toccare il tablet.

⚠️ Da TEST ZERO si accende e non si spegne mai: un tramite serve tutti i punti
cassa del punto vendita, e premerlo dalla cassa fiscale spegnerebbe i palmari.

L'aggancio al profilo invece si impara sempre dal TEST ZERO — indirizzo, punto
vendita e licenza. Nella schermata dell'app ci sono anche i tre campi per
scriverli a mano, se quello non riesce.

⚠️ Il pagamento con carta e il TEST ZERO restano browser → agente: l'ascolto
porta solo le stampe.

## Provarlo dal PC

    ./tools/avvia.sh            # sulla 55226, al posto dello Zero
    ./tools/avvia.sh 55227      # su un'altra porta, senza spegnere lo Zero

E' lo stesso codice che gira sul telefono: il modo piu' rapido per vedere cosa
arriva davvero da una stampa.

## Verifiche

    ./tools/prova.sh

277 controlli su JVM, senza emulatore ne' hardware: stampante, registratore
Micrelec, registratori HTTP, terminale POS e Sublima finti, piu' i casi di
errore. I lavori di prova li costruisce Python con gli stessi `zipfile` e
`pickle` che usa Sublima.

## Costruire l'APK

    ./tools/costruisci-apk.sh

Non serve Gradle ne' Android Studio: solo `aapt2`, `javac`, `d8` e `apksigner`,
che sono gia' nell'SDK. Niente da scaricare.

La chiave di firma si cerca in `~/.zero_android/firma.jks`; se non c'e' ne viene
generata una di sviluppo. **Conservala**: cambiare chiave su un'app gia'
installata obbliga a disinstallarla, perdendo la configurazione.

    KEYSTORE=/percorso/tua.jks KEYPASS=... ./tools/costruisci-apk.sh

## Installazione sul dispositivo

    adb install -r zero-android.apk

Poi, sul dispositivo:

1. apri l'app e concedi la notifica: il servizio ne ha bisogno per restare vivo;
2. **togli l'app dal risparmio energetico** e concedile l'avvio automatico —
   senza, Xiaomi, Huawei e Oppo la chiudono nel giro di qualche ora e le comande
   smettono di arrivare senza che nessuno capisca perche';
3. dai al dispositivo un **indirizzo fisso** (o una prenotazione sul router):
   l'indirizzo e' quello che Sublima chiama, e se cambia le stampe si fermano;
4. scrivi quell'indirizzo come **IP cassa** nella scheda del punto cassa, e
   premi TEST ZERO ESTERNO.

L'app mostra il proprio indirizzo, lo stato e le ultime righe di registro:
quando qualcosa non stampa, si guarda li' prima di ogni altra cosa.
