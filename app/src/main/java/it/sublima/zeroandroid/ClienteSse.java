package it.sublima.zeroandroid;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * L'ascolto delle comande: l'agente chiama Sublima e resta in attesa.
 *
 * E' il verso opposto a tutto il resto. Di solito e' il browser a bussare
 * all'agente sulla 55226, e per farlo deve raggiungerlo in rete locale: da li'
 * nascono CORS, preflight, contenuto misto e Private Network Access — su ogni
 * palmare e su ogni rete di ogni ristorante. Qui invece e' l'agente ad aprire
 * una connessione verso Sublima e a tenerla aperta; quando c'e' una comanda, e'
 * il server a spingerla. Il palmare non contatta piu' nessuno.
 *
 * Il canale e' quello che lo Zero Python usa da tempo. Il contratto sta nel
 * paragrafo 7 di PROTOCOLLO.md e non e' negoziabile: se questo client diverge,
 * diverge da una cosa che gira gia' in produzione.
 *
 * ⚠️ Due cose importano piu' delle altre, ed entrambe costano soldi se
 * sbagliate:
 *
 *  - la stampa **non** avviene sul filo di lettura. Una chiusura Z tiene occupato
 *    il registratore fino a due minuti e mezzo, e nel frattempo lo stream
 *    resterebbe non letto. Gli eventi vanno in una coda breve servita da un solo
 *    lavoratore, che stampa in ordine e risponde a Sublima;
 *  - non ci si riconnette mai sotto i tre secondi. Sublima risponde 429 a chi lo
 *    fa, e un ciclo di riconnessione impaziente si mette da solo alla porta.
 *
 * ⚠️ Non importa nulla di Android: `tools/prova.sh` esclude dai test ogni classe
 * che lo faccia, e questa e' la classe che va provata di piu'.
 */
public class ClienteSse {

    /** L'unico indirizzo del canale. `id_mag` e' l'unica chiave di instradamento. */
    public static final String PERCORSO = "/pg/api/zero/sse_print_actions";

    public static final int ATTESA_CONNESSIONE = 10000;

    /**
     * Novanta secondi senza un byte e la connessione si considera morta.
     *
     * Sublima manda un battito ogni trenta: tre battiti mancati sono un guasto,
     * non una pausa. Serve perche' una rete che cade in silenzio — Wi-Fi che
     * sparisce, NAT che dimentica — non chiude il socket, e senza questo limite
     * si resterebbe in ascolto per sempre di un canale che non esiste piu'.
     */
    public static final int ATTESA_LETTURA = 90000;

    static final long RITARDO_INIZIALE = 5000;
    static final long RITARDO_MASSIMO = 60000;
    static final long MINIMO_FRA_CONNESSIONI = 3000;
    static final long DURATA_CHE_VALE = 120000;
    static final int FALLIMENTI_PER_GIRO = 15;
    static final long PAUSA_PER_GIRO = 300000;
    static final long PAUSA_MASSIMA = 1800000;

    /** Come `Richiesta.CORPO_MASSIMO`: una riga `data:` porta uno ZIP intero. */
    static final int RIGA_MASSIMA = 48 * 1024 * 1024;

    /**
     * Quanti lavori si accettano prima di smettere di leggere.
     *
     * Tenuta bassa apposta: ogni voce trattiene in memoria lo ZIP in base64, e
     * su un tablet economico una coda generosa e' un modo elegante di finire la
     * memoria. A coda piena il lettore si ferma, e si degrada esattamente al
     * comportamento dello Zero Python, che stampa in linea.
     */
    static final int CODA_MASSIMA = 10;

    /** L'attesa, iniettabile: i test non devono aspettare mezz'ora davvero. */
    public interface Pausa {
        void dormi(long millisecondi) throws InterruptedException;
    }

    private static final Pausa PAUSA_VERA = new Pausa() {
        public void dormi(long millisecondi) throws InterruptedException {
            Thread.sleep(millisecondi);
        }
    };

    private final ServerHttp.Stampe stampe;
    private final Pausa pausa;
    private final BlockingQueue<Lavoro> coda =
            new ArrayBlockingQueue<Lavoro>(CODA_MASSIMA);

    private volatile boolean attivo;
    private volatile boolean spentoDaSublima;
    private volatile HttpURLConnection connessione;
    private Thread ascolto;
    private Thread lavoratore;

    public ClienteSse(ServerHttp.Stampe stampe) {
        this(stampe, PAUSA_VERA);
    }

    public ClienteSse(ServerHttp.Stampe stampe, Pausa pausa) {
        this.stampe = stampe;
        this.pausa = (pausa == null) ? PAUSA_VERA : pausa;
    }

    // ---- ciclo di vita -----------------------------------------------------

    public void avvia() {
        if (attivo) {
            return;
        }
        attivo = true;
        lavoratore = new Thread(new Runnable() {
            public void run() {
                lavora();
            }
        }, "zero-sse-stampa");
        lavoratore.setDaemon(true);
        lavoratore.start();

        ascolto = new Thread(new Runnable() {
            public void run() {
                ciclo();
            }
        }, "zero-sse");
        ascolto.setDaemon(true);
        ascolto.start();
    }

    public void ferma() {
        attivo = false;
        // Il filo di lettura e' fermo dentro una read(): l'unico modo di
        // svegliarlo e' chiudergli la connessione sotto i piedi.
        chiudiConnessione();
        if (ascolto != null) {
            ascolto.interrupt();
        }
        if (lavoratore != null) {
            lavoratore.interrupt();
        }
        segnaScollegato("Spento");
    }

    public boolean attivo() {
        return attivo;
    }

    // ---- il ciclo di riconnessione ----------------------------------------

    private void ciclo() {
        long ritardo = RITARDO_INIZIALE;
        int fallimenti = 0;
        int giro = 0;

        while (attivo) {
            if (!Conf.sseAttivo()) {
                // Se e' stato Sublima a dirci di smettere, quel motivo resta
                // scritto: sostituirlo con un generico "spento" cancellerebbe
                // l'unica indicazione utile a chi va a vedere perche'.
                segnaScollegato(spentoDaSublima ? null
                        : (Conf.agganciato() ? "Ascolto comande SSE spento"
                                             : "Non agganciato a nessun profilo"));
                if (!dormi(5000)) {
                    return;
                }
                continue;
            }
            spentoDaSublima = false;

            Esito esito = unaConnessione();
            if (!attivo) {
                return;
            }

            switch (esito.genere) {
                case Esito.SPENTO:
                    // Sublima ha detto di smettere: non e' un guasto da
                    // ritentare, e insistere sarebbe solo rumore nei suoi log.
                    spentoDaSublima = true;
                    Conf.scrivi(Conf.SSE_ATTIVO, "off");
                    segnaScollegato("Spento da Sublima: " + esito.motivo);
                    ServerHttp.registra("ascolto comande SSE spento da Sublima: " + esito.motivo);
                    continue;

                case Esito.PAUSA:
                    segnaScollegato("In pausa per " + (esito.pausa / 60000) + " minuti");
                    ServerHttp.registra("Sublima chiede una pausa di "
                            + (esito.pausa / 1000) + "s: " + esito.motivo);
                    if (!dormi(esito.pausa)) {
                        return;
                    }
                    continue;

                case Esito.RIPARTI:
                    // Riavvio del server: non e' colpa di nessuno e non conta
                    // come fallimento. (Lo Zero Python qui lo conta lo stesso,
                    // per come e' scritto il suo ciclo: divergenza voluta.)
                    ritardo = RITARDO_INIZIALE;
                    fallimenti = 0;
                    if (!dormi(RITARDO_INIZIALE)) {
                        return;
                    }
                    continue;

                default:
                    break;
            }

            if (esito.durata >= DURATA_CHE_VALE) {
                // Ha retto: quello che e' successo dopo non e' sintomo di niente.
                ritardo = RITARDO_INIZIALE;
                fallimenti = 0;
                giro = 0;
                if (!dormi(RITARDO_INIZIALE)) {
                    return;
                }
                continue;
            }

            fallimenti++;
            segnaScollegato(esito.motivo);
            if (!dormi(ritardo)) {
                return;
            }
            ritardo = Math.min(ritardo * 2, RITARDO_MASSIMO);

            if (fallimenti >= FALLIMENTI_PER_GIRO) {
                fallimenti = 0;
                giro++;
                long lunga = Math.min(PAUSA_PER_GIRO * giro, PAUSA_MASSIMA);
                ServerHttp.registra("ascolto comande SSE: " + FALLIMENTI_PER_GIRO
                        + " tentativi a vuoto, aspetto " + (lunga / 60000) + " minuti");
                segnaScollegato("Nessuna risposta: riprovo fra " + (lunga / 60000) + " minuti");
                if (!dormi(lunga)) {
                    return;
                }
                ritardo = 2 * RITARDO_INIZIALE;
            }
        }
    }

    /** Esito di un singolo tentativo di connessione. */
    static final class Esito {
        static final int CADUTA = 0;
        static final int RIPARTI = 1;
        static final int PAUSA = 2;
        static final int SPENTO = 3;

        int genere = CADUTA;
        long durata;
        long pausa;
        String motivo = "";

        static Esito di(int genere, String motivo) {
            Esito e = new Esito();
            e.genere = genere;
            e.motivo = motivo;
            return e;
        }
    }

    private Esito unaConnessione() {
        String base = Conf.urlProfilo();
        String indirizzo;
        try {
            indirizzo = base + PERCORSO
                    + "?zero_licenza=" + URLEncoder.encode(Conf.licenza(), "UTF-8")
                    + "&id_mag=" + URLEncoder.encode(Conf.idMagazzino(), "UTF-8");
        } catch (Exception e) {
            return Esito.di(Esito.CADUTA, "indirizzo non valido: " + e.getMessage());
        }

        HttpURLConnection c = null;
        long inizio = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) new URL(indirizzo).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(ATTESA_CONNESSIONE);
            c.setReadTimeout(ATTESA_LETTURA);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "text/event-stream");
            c.setRequestProperty("Cache-Control", "no-cache");
            // Sublima non comprime questo flusso, e chiedere identity toglie di
            // mezzo ogni sorpresa di bufferizzazione fra noi e i byte.
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", Versione.NOME + "/" + Versione.NUMERO);
            connessione = c;

            int codice = c.getResponseCode();
            if (codice != 200) {
                return rifiuto(c, codice);
            }
            String tipo = c.getContentType();
            if (tipo == null || tipo.indexOf("text/event-stream") < 0) {
                return Esito.di(Esito.CADUTA,
                        "risposta non e' un flusso di eventi (" + tipo + ")");
            }

            inizio = System.currentTimeMillis();
            segnaCollegato(base);
            ServerHttp.registra("ascolto comande SSE collegato a " + base
                    + " (magazzino " + Conf.idMagazzino() + ")");

            int finale = leggiEventi(new BufferedInputStream(c.getInputStream(), 32768));
            Esito e = new Esito();
            e.genere = finale;
            e.durata = System.currentTimeMillis() - inizio;
            e.motivo = (finale == Esito.RIPARTI)
                    ? "Sublima si sta riavviando"
                    : "collegamento chiuso dopo " + (e.durata / 1000) + "s";
            return e;

        } catch (SocketTimeoutException e) {
            Esito x = Esito.di(Esito.CADUTA, "nessun dato da "
                    + (ATTESA_LETTURA / 1000) + "s: rete caduta in silenzio");
            x.durata = System.currentTimeMillis() - inizio;
            return x;
        } catch (Exception e) {
            if (!attivo) {
                return Esito.di(Esito.CADUTA, "fermato");
            }
            Esito x = Esito.di(Esito.CADUTA,
                    "Sublima non raggiungibile: " + messaggio(e));
            x.durata = System.currentTimeMillis() - inizio;
            return x;
        } finally {
            connessione = null;
            if (c != null) {
                c.disconnect();
            }
            segnaScollegato(null);
        }
    }

    /**
     * Un codice diverso da 200 va letto nel corpo, non nel numero.
     *
     * Il 503 e' sovraccarico: dice "spento per sempre" e "riprova fra un'ora"
     * con lo stesso codice, e la differenza sta solo dentro il JSON. Confonderli
     * significa o martellare un server che ha detto basta, o spegnere l'ascolto
     * di un impianto che stava solo aspettando.
     */
    private Esito rifiuto(HttpURLConnection c, int codice) {
        String corpo = corpoErrore(c);
        if (codice == 503) {
            String risultato = "";
            long attesa = 3600000;
            try {
                Map<String, Object> j = JsonLettore.oggetto(corpo);
                risultato = JsonLettore.testo(j, "Result", "");
                attesa = 1000 * (long) numero(JsonLettore.testo(j, "pause_seconds", "3600"), 3600);
            } catch (Exception e) {
                // corpo illeggibile: si tratta come una pausa, che e' la
                // reazione prudente fra le due
                risultato = "";
            }
            if ("KO".equals(risultato)) {
                return Esito.di(Esito.SPENTO, breve(corpo));
            }
            Esito e = Esito.di(Esito.PAUSA, breve(corpo));
            e.pausa = Math.max(attesa, MINIMO_FRA_CONNESSIONI);
            return e;
        }
        if (codice == 429) {
            // Ci siamo riconnessi troppo in fretta: e' un difetto nostro, e va
            // scritto cosi' invece di sembrare un guasto del server.
            return Esito.di(Esito.CADUTA, "riconnessione troppo rapida (429): rallento");
        }
        return Esito.di(Esito.CADUTA, "Sublima ha risposto " + codice + " " + breve(corpo));
    }

    // ---- lettura del flusso ------------------------------------------------

    /**
     * Legge gli eventi finche' il flusso regge. Ritorna il genere di esito.
     *
     * Rispetto allo Zero Python l'evento si chiude sulla riga vuota invece che
     * sulla riga `data:`, si accumulano piu' righe `data:` e si saltano i
     * commenti: e' lo standard, costa cinque righe, e non cambia niente con quel
     * che Sublima manda oggi (un `data:` solo, su una riga sola).
     */
    private int leggiEventi(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
        StringBuilder dati = new StringBuilder();
        String evento = null;
        String riga;

        while (attivo && (riga = riga(in, buffer)) != null) {
            if (riga.isEmpty()) {
                if (dati.length() > 0) {
                    int esito = gestisci(evento, dati.toString());
                    if (esito != Esito.CADUTA) {
                        return esito;
                    }
                }
                evento = null;
                dati.setLength(0);
                continue;
            }
            if (riga.charAt(0) == ':') {
                continue;
            }
            int duePunti = riga.indexOf(':');
            String campo = (duePunti < 0) ? riga : riga.substring(0, duePunti);
            String valore = (duePunti < 0) ? "" : riga.substring(duePunti + 1);
            if (valore.startsWith(" ")) {
                valore = valore.substring(1);
            }
            if ("event".equals(campo)) {
                evento = valore.trim();
            } else if ("data".equals(campo)) {
                if (dati.length() > 0) {
                    dati.append('\n');
                }
                dati.append(valore);
            }
            // `id:` si legge e si scarta: Sublima non rigioca niente su
            // Last-Event-ID, quindi ricordarlo darebbe una falsa sicurezza.
        }
        return Esito.CADUTA;
    }

    /**
     * Una riga del flusso, senza limite pratico di lunghezza.
     *
     * Un evento di stampa e' una riga sola con dentro lo ZIP in base64: puo'
     * pesare megabyte, e un lettore con la riga corta lo troncherebbe a meta'.
     */
    public static String riga(InputStream in, ByteArrayOutputStream buffer) throws IOException {
        buffer.reset();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] d = buffer.toByteArray();
                int quanti = d.length;
                if (quanti > 0 && d[quanti - 1] == '\r') {
                    quanti--;
                }
                return new String(d, 0, quanti, "UTF-8");
            }
            buffer.write(b);
            if (buffer.size() > RIGA_MASSIMA) {
                throw new IOException("evento oltre i "
                        + (RIGA_MASSIMA / (1024 * 1024)) + " MB: lo scarto");
            }
        }
        return (buffer.size() > 0)
                ? new String(buffer.toByteArray(), "UTF-8") : null;
    }

    private int gestisci(String evento, String dati) {
        if ("keepalive".equals(evento)) {
            int quanti = battiti.incrementAndGet();
            ultimoBattito = System.currentTimeMillis();
            // Il registro tiene 200 righe: un battito ogni 30 secondi lo
            // svuoterebbe di tutto il resto in un'ora. Se ne scrive uno ogni
            // dieci minuti, il conto vero sta nello stato.
            if (quanti % 20 == 1) {
                ServerHttp.registra("ascolto comande SSE vivo (" + quanti + " battiti)");
            }
            return Esito.CADUTA;
        }
        if ("connected".equals(evento)) {
            battiti.set(0);
            return Esito.CADUTA;
        }
        if ("server_shutdown".equals(evento)) {
            ServerHttp.registra("Sublima si sta riavviando: mi ricollego");
            return Esito.RIPARTI;
        }
        if ("service_disabled".equals(evento)) {
            // Sublima oggi non lo manda: sta qui perche' il client Python lo
            // gestisce, e un giorno potrebbe tornare a mandarlo.
            return Esito.SPENTO;
        }
        if ("print_action".equals(evento)) {
            accoda(dati);
            return Esito.CADUTA;
        }
        // Un evento sconosciuto non e' un guasto: si ignora e si continua.
        return Esito.CADUTA;
    }

    private void accoda(String dati) {
        String nome;
        try {
            Map<String, Object> messaggio = JsonLettore.oggetto(dati);
            nome = JsonLettore.testo(messaggio, "filescontrino", "");
        } catch (JsonLettore.JsonNonValido e) {
            ServerHttp.registra("evento di stampa illeggibile: " + e.getMessage());
            return;
        }
        if (nome.isEmpty()) {
            ServerHttp.registra("evento di stampa senza nome del lavoro: lo scarto");
            return;
        }
        ServerHttp.registra("comanda ricevuta: " + nome);
        try {
            // Bloccante di proposito: a coda piena si smette di leggere invece
            // di buttare via un lavoro. Sublima ha una coda sua da cento.
            coda.put(new Lavoro(nome, dati, Conf.urlProfilo(), Conf.licenza()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- il lavoratore -----------------------------------------------------

    private static final class Lavoro {
        final String nome;
        final String dati;
        final String profilo;
        final String licenza;

        Lavoro(String nome, String dati, String profilo, String licenza) {
            this.nome = nome;
            this.dati = dati;
            this.profilo = profilo;
            this.licenza = licenza;
        }
    }

    private void lavora() {
        while (attivo) {
            Lavoro l;
            try {
                l = coda.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (l == null) {
                continue;
            }
            String esito;
            try {
                esito = stampe.esegui(Richiesta.sintetica(l.dati, l.profilo));
            } catch (Exception e) {
                esito = new Json()
                        .testo("Result", "KO")
                        .testo("Message", "Errore durante la stampa: " + messaggio(e))
                        .toString();
                ServerHttp.registra("comanda " + l.nome + " fallita: " + messaggio(e));
            }
            ultimoLavoro = l.nome;
            ultimoLavoroIl = System.currentTimeMillis();
            // L'esito va restituito sempre: e' quello che il browser aspetta di
            // leggere, ed e' anche cio' che fa cancellare lo ZIP dal server.
            Sublima.inviaAck(l.profilo, l.nome, esito, l.licenza);
        }
    }

    // ---- stato, per la pagina e per la schermata ---------------------------

    private static final java.util.concurrent.atomic.AtomicInteger battiti =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean collegato;
    private static volatile long collegatoDa;
    private static volatile long ultimoBattito;
    private static volatile String descrizione = "Ascolto comande SSE non configurato";
    private static volatile String profiloCollegato = "";
    private static volatile String ultimoLavoro = "";
    private static volatile long ultimoLavoroIl;

    private void segnaCollegato(String profilo) {
        collegato = true;
        collegatoDa = System.currentTimeMillis();
        ultimoBattito = collegatoDa;
        battiti.set(0);
        profiloCollegato = profilo;
        descrizione = "Collegato a " + profilo;
    }

    private void segnaScollegato(String motivo) {
        collegato = false;
        collegatoDa = 0;
        if (motivo != null) {
            descrizione = motivo;
        }
    }

    public static boolean collegato() {
        return collegato;
    }

    public static String descrizione() {
        return descrizione;
    }

    public static int battiti() {
        return battiti.get();
    }

    /** Da quanti secondi il collegamento regge, 0 se non c'e'. */
    public static long secondiCollegato() {
        return (collegatoDa == 0) ? 0 : (System.currentTimeMillis() - collegatoDa) / 1000;
    }

    /** Da quanti secondi non arriva un battito, -1 se non ne e' mai arrivato uno. */
    public static long secondiDallUltimoBattito() {
        return (ultimoBattito == 0) ? -1 : (System.currentTimeMillis() - ultimoBattito) / 1000;
    }

    public static String ultimoLavoro() {
        if (ultimoLavoro.isEmpty()) {
            return "";
        }
        long fa = (System.currentTimeMillis() - ultimoLavoroIl) / 1000;
        return ultimoLavoro + " (" + fa + "s fa)";
    }

    /** Lo stato in forma leggibile da Sublima, dentro /test_zero. */
    public static Json stato() {
        return new Json()
                .vero("attivo", Conf.sseAttivo())
                .vero("collegato", collegato)
                .testo("profilo", profiloCollegato)
                .testo("magazzino", Conf.idMagazzino())
                .numero("secondi_collegato", secondiCollegato())
                .numero("battiti", battiti.get())
                .testo("stato", descrizione);
    }

    // ---- minuzie -----------------------------------------------------------

    /** Ritorna false se e' ora di smettere: chi chiama esce dal ciclo. */
    private boolean dormi(long millisecondi) {
        try {
            pausa.dormi(Math.max(millisecondi, MINIMO_FRA_CONNESSIONI));
            return attivo;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void chiudiConnessione() {
        HttpURLConnection c = connessione;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception e) {
                // niente da fare
            }
        }
    }

    private static String corpoErrore(HttpURLConnection c) {
        InputStream in = null;
        try {
            in = c.getErrorStream();
            if (in == null) {
                in = c.getInputStream();
            }
            ByteArrayOutputStream fuori = new ByteArrayOutputStream();
            byte[] pezzo = new byte[1024];
            int letti;
            while ((letti = in.read(pezzo)) > 0 && fuori.size() < 4096) {
                fuori.write(pezzo, 0, letti);
            }
            return new String(fuori.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception e) {
                // niente da fare
            }
        }
    }

    static double numero(String s, double riserva) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return riserva;
        }
    }

    private static String breve(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace('\n', ' ').trim();
        return (v.length() > 160) ? v.substring(0, 160) + "..." : v;
    }

    private static String messaggio(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
