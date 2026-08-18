package it.sublima.zeroandroid;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Il server che Sublima chiama, sulla porta dello Zero.
 *
 * La porta e' 55226 e non e' negoziabile: il client di Sublima la usa come
 * costante e ignora quella configurata. Vedi il paragrafo 1 di PROTOCOLLO.md.
 *
 * Ogni risposta porta gli header CORS, compreso Access-Control-Allow-Private-Network
 * che Chrome pretende per chiamare un indirizzo di rete locale da una pagina
 * servita altrove: senza, il browser scarta la risposta e l'utente vede solo
 * "Zero non raggiungibile".
 */
public class ServerHttp {

    public static final int PORTA_ZERO = 55226;

    private final int portaRichiesta;
    private final Stampe stampe;
    private ServerSocket socket;
    private Thread accoglienza;
    private volatile boolean attivo;

    /** Chi sa eseguire un lavoro di stampa. Finche' e' null l'agente lo dichiara. */
    public interface Stampe {
        String esegui(Richiesta richiesta) throws Exception;
    }

    public ServerHttp(int porta, Stampe stampe) {
        this.portaRichiesta = porta;
        this.stampe = stampe;
    }

    public void avvia() throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(portaRichiesta));
        attivo = true;
        accoglienza = new Thread(new Runnable() {
            public void run() {
                accogli();
            }
        }, "zero-http");
        accoglienza.setDaemon(true);
        accoglienza.start();
        registra("in ascolto sulla porta " + porta());
    }

    public void ferma() {
        attivo = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // chiudere un socket gia' chiuso non e' un problema
        }
    }

    /** Porta davvero in uso: con 0 il sistema ne sceglie una, e serve ai test. */
    public int porta() {
        return socket != null ? socket.getLocalPort() : portaRichiesta;
    }

    private void accogli() {
        while (attivo) {
            final Socket cliente;
            try {
                cliente = socket.accept();
            } catch (IOException e) {
                if (attivo) {
                    registra("errore in accettazione: " + e.getMessage());
                }
                continue;
            }
            Thread t = new Thread(new Runnable() {
                public void run() {
                    servi(cliente);
                }
            }, "zero-http-cliente");
            t.setDaemon(true);
            t.start();
        }
    }

    private void servi(Socket cliente) {
        try {
            cliente.setSoTimeout(30000);
            InputStream in = cliente.getInputStream();
            OutputStream out = cliente.getOutputStream();
            Richiesta richiesta;
            try {
                richiesta = Richiesta.leggi(in);
            } catch (Richiesta.RichiestaNonValida e) {
                rispondi(out, 400, new Json()
                        .testo("Result", "KO")
                        .testo("Message", "Richiesta non valida: " + e.getMessage())
                        .toString());
                return;
            }

            // Il preflight va soddisfatto prima di ogni altra cosa, o il browser
            // non manda nemmeno la richiesta vera.
            if ("OPTIONS".equals(richiesta.metodo())) {
                rispondi(out, 204, null);
                return;
            }

            String percorso = richiesta.percorso();
            // La pagina si ricarica da sola ogni 5 secondi: registrarne le visite
            // riempirebbe il registro di "GET /" e in un quarto d'ora spingerebbe
            // fuori le righe delle stampe, che sono l'unica diagnosi sul posto.
            if (!vieneSoloAGuardare(percorso)) {
                registra(richiesta.metodo() + " " + percorso);
            }

            if ("/test_zero".equals(percorso)) {
                rispondi(out, 200, testZero(richiesta));
            } else if ("/ping".equals(percorso)) {
                rispondi(out, 200, testZero());
            } else if ("/stampa_all".equals(percorso)) {
                rispondi(out, 200, stampa(richiesta));
            } else if ("/".equals(percorso) || "/stato".equals(percorso)) {
                rispondiHtml(out, pagina());
            } else if ("/log".equals(percorso)) {
                rispondiTesto(out, registroRecente());
            } else if ("/ascolto".equals(percorso)) {
                // L'interruttore dell'ascolto comande, dalla pagina di stato.
                // Si risponde con un rimando a "/" invece della pagina: cosi' il
                // ricaricamento automatico ogni 5 secondi non ripete il comando
                // e non riempie il registro di righe "/ascolto".
                if (vero(richiesta.campo("attiva"))) {
                    Conf.accendi(true);
                    registra("ascolto comande SSE ACCESO dalla pagina di stato");
                } else if (richiesta.campo("attiva") != null) {
                    Conf.accendi(false);
                    registra("ascolto comande SSE SPENTO dalla pagina di stato");
                }
                rispondiAltrove(out, "/");
            } else {
                rispondi(out, 404, new Json()
                        .testo("Result", "KO")
                        .testo("Message", "Indirizzo sconosciuto: " + percorso)
                        .toString());
            }
        } catch (Exception e) {
            registra("errore servendo la richiesta: " + e);
        } finally {
            try {
                cliente.close();
            } catch (IOException e) {
                // niente da fare
            }
        }
    }

    private String stampa(Richiesta richiesta) {
        if (stampe == null) {
            return new Json()
                    .testo("Result", "KO")
                    .testo("Message", "Questo agente non sa ancora stampare: "
                            + "risponde solo a /test_zero")
                    .toString();
        }
        try {
            return stampe.esegui(richiesta);
        } catch (Exception e) {
            registra("stampa fallita: " + e);
            return new Json()
                    .testo("Result", "KO")
                    .testo("Message", "Errore durante la stampa: " + e.getMessage())
                    .toString();
        }
    }

    /**
     * La presentazione a Sublima. Result e Message vanno lasciati come sono:
     * c'e' chi li legge da anni e non si aspetta altro.
     */
    static String testZero() {
        return testZero(null);
    }

    /**
     * La presentazione, e insieme l'aggancio al profilo.
     *
     * Il pulsante TEST ZERO della scheda punto cassa e' il gesto che
     * l'installatore fa comunque, ed e' l'unico momento in cui browser e agente
     * si parlano di sicuro. Se Sublima manda anche il proprio indirizzo, il
     * magazzino e la licenza, l'agente li ricorda e si mette in ascolto da solo:
     * niente da digitare sul tablet.
     *
     * Chi non li manda ottiene la presentazione di prima, tale e quale.
     */
    static String testZero(Richiesta richiesta) {
        Json j = new Json()
                .testo("Result", "OK")
                .testo("Message", "CONNESSIONE CON ZERO OK")
                .testo("versione", Versione.NUMERO)
                .testo("agente", Versione.NOME)
                .testo("piattaforma", Versione.piattaforma())
                .oggetto("capacita", Versione.capacita());
        String aggancio = aggancia(richiesta);
        if (aggancio != null) {
            j.testo("aggancio", aggancio);
        }
        return j.oggetto("sse", ClienteSse.stato()).toString();
    }

    /**
     * Ricorda profilo, magazzino e licenza se Sublima li ha mandati.
     *
     * ⚠️ Un cambio di aggancio si scrive nel registro a lettere grosse: due
     * punti cassa di magazzini diversi puntati sullo stesso agente lo farebbero
     * ballare da un punto vendita all'altro, e quella e' una configurazione
     * sbagliata che va vista, non subita in silenzio.
     */
    private static String aggancia(Richiesta richiesta) {
        if (richiesta == null) {
            return null;
        }
        String url = richiesta.campo("url_profilo");
        String magazzino = richiesta.campo("id_mag");
        if (url == null && magazzino == null) {
            return null;
        }
        String primaUrl = Conf.urlProfilo();
        String primaMag = Conf.idMagazzino();

        String problema = Conf.aggancia(url, magazzino, richiesta.campo("zero_licenza"));
        if (problema != null) {
            registra("aggancio rifiutato: " + problema);
            return problema;
        }
        // Il nome del punto vendita non serve al protocollo, che instrada sul
        // numero: serve a chi guarda il tablet e deve capire a quale locale e'
        // attaccato senza andare a cercare a cosa corrisponde un 4.
        String nome = richiesta.campo("nome_mag");
        if (nome != null && !nome.trim().isEmpty()) {
            Conf.scrivi(Conf.NOME_MAGAZZINO, nome.trim());
        }

        // L'aggancio si ricorda sempre; l'ascolto si accende **solo** se Sublima
        // lo chiede. Il campo lo manda la scheda del punto cassa quando quel
        // punto cassa e' impostato su SSE: cosi' chi ha la ristorazione lo
        // ottiene senza toccare il tablet, e le nove installazioni su dieci che
        // non c'entrano niente non si collegano mai.
        //
        // ⚠️ Da qui si accende e non si spegne MAI, anche se il campo dice no.
        // Un tramite serve tutti i punti cassa del punto vendita: premere TEST
        // ZERO dalla cassa fiscale, che l'SSE non lo usa, spegnerebbe l'ascolto
        // di cui i palmari hanno bisogno.
        boolean chiedeAcceso = vero(richiesta.campo("sse_attiva"));
        if (chiedeAcceso && !Conf.acceso()) {
            Conf.accendi(true);
            registra("ascolto comande SSE ACCESO dal TEST ZERO");
        }

        String dove = "magazzino " + Conf.magazzinoLeggibile();
        if (!primaUrl.equals(Conf.urlProfilo()) || !primaMag.equals(Conf.idMagazzino())) {
            registra("AGGANCIO CAMBIATO: ora ascolto " + Conf.urlProfilo()
                    + " " + dove
                    + (primaMag.isEmpty() ? "" : " (prima era " + primaUrl
                       + " magazzino " + primaMag + ")"));
        }
        if (Conf.acceso()) {
            return "in ascolto sul " + dove;
        }
        return "agganciato al " + dove + ", ma l'ascolto comande SSE e' SPENTO:"
                + " accendilo dall'app o dalla pagina di stato del tramite,"
                + " oppure metti questo punto cassa su SSE e ripremi TEST ZERO";
    }

    private static boolean vero(String valore) {
        if (valore == null) {
            return false;
        }
        String v = valore.trim().toLowerCase();
        return v.equals("1") || v.equals("on") || v.equals("true") || v.equals("si");
    }

    /**
     * La pagina di stato, da aprire con un browser qualsiasi della rete.
     *
     * Serve a chi assiste: il dispositivo sara' appeso a un muro, e la domanda a
     * cui rispondere e' sempre la stessa — sta girando, e cosa ha fatto finora.
     * Si aggiorna da sola, cosi' la si puo' lasciare aperta durante una prova.
     */
    private String pagina() {
        String trovato = indirizzoLocale();
        String indirizzo = (trovato == null) ? "non rilevato: controlla la rete" : trovato;

        StringBuilder capacita = new StringBuilder();
        for (Versione.Capacita c : Versione.elenco()) {
            capacita.append("<tr><td>").append(c.supportata ? "&#10003;" : "&#10007;")
                    .append("</td><td>").append(c.etichetta).append("</td><td><small>")
                    .append(c.nota == null ? "" : c.nota).append("</small></td></tr>");
        }
        StringBuilder fuori = new StringBuilder();
        for (String v : Versione.fuoriPortata()) {
            fuori.append("<li>").append(v).append("</li>");
        }

        return "<!doctype html><html lang=\"it\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + Versione.NOME + "</title>"
            + "<meta http-equiv=\"refresh\" content=\"5\">"
            + "<style>"
            + "body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
            + "margin:0;padding:24px;background:#f6f6f7;color:#222}"
            + "h1{font-size:20px;margin:0 0 4px}"
            + ".acceso{display:inline-block;padding:4px 12px;border-radius:12px;"
            + "background:#1B7F3B;color:#fff;font-size:13px}"
            + "table{border-collapse:collapse;margin:16px 0;background:#fff;"
            + "box-shadow:0 1px 3px rgba(0,0,0,.12);border-radius:6px;overflow:hidden}"
            + "td{padding:7px 14px;border-bottom:1px solid #eee;font-size:14px}"
            + "td:first-child{color:#666}"
            + "pre{background:#1e1e1e;color:#d4d4d4;padding:14px;border-radius:6px;"
            + "font-size:12px;max-height:60vh;overflow:auto;white-space:pre-wrap}"
            + "small{color:#666}"
            + ".ascolto{margin:16px 0;padding:12px 14px;border-radius:6px;font-size:14px;"
            + "border-left:5px solid}"
            + ".vivo{background:#e8f5ec;border-color:#1B7F3B}"
            + ".fermo{background:#fdecea;border-color:#B00020}"
            + ".spento{background:#f0f0f2;border-color:#999}"
            + "</style></head><body>"
            + "<h1>" + Versione.NOME + " <span class=\"acceso\">in ascolto</span></h1>"
            + "<small>si aggiorna da sola ogni 5 secondi</small>"
            + "<table>"
            + "<tr><td>IP cassa</td><td><b>" + indirizzo + "</b></td></tr>"
            + "<tr><td>Versione</td><td><b>" + Versione.NUMERO + "</b></td></tr>"
            + "<tr><td>Dispositivo</td><td>" + Versione.piattaforma() + "</td></tr>"
            + "<tr><td>Porta</td><td>" + porta() + "</td></tr>"
            + capacita
            + "</table>"
            + riquadroAscolto()
            + "<p><small>Scrivi <b>" + indirizzo + "</b> come <b>IP cassa</b> nella scheda"
            + " del punto cassa di Sublima. Non usare l'indirizzo della barra qui sopra:"
            + " aprendo questa pagina sul dispositivo stesso sarebbe <i>localhost</i>,"
            + " che da Sublima non porta da nessuna parte.</small></p>"
            + "<h2 style=\"font-size:15px\">Registro</h2>"
            + "<pre>" + perHtml(registroRecente()) + "</pre>"
            + "<p><small>Solo registro, in testo: <a href=\"/log\">/log</a> &middot; "
            + "Presentazione: <a href=\"/test_zero\">/test_zero</a></small></p>"
            + "</body></html>";
    }

    /**
     * Lo stato dell'ascolto comande, che e' la domanda vera di chi assiste.
     *
     * "Collegato" da solo non basta e sarebbe anzi ingannevole: il collegamento
     * regge anche quando nessun punto cassa del magazzino e' impostato per
     * mandare le comande da questa parte, e in quel caso non arriva niente pur
     * essendo tutto verde. Il numero dei battiti dice che il canale e' vivo,
     * l'ultimo lavoro dice che ci passa davvero qualcosa.
     */
    private static String riquadroAscolto() {
        if (!Conf.agganciato()) {
            return "<div class=\"ascolto spento\"><b>Ascolto comande SSE: non configurato</b>"
                + "<br><small>Premi <b>TEST ZERO</b> nella scheda del punto cassa di"
                + " Sublima: l'agente impara da li' a quale profilo e magazzino"
                + " agganciarsi.</small>" + interruttore() + "</div>";
        }
        String dove = "profilo <b>" + perHtml(Conf.urlProfilo())
                + "</b>, magazzino <b>" + perHtml(Conf.idMagazzino()) + "</b>";
        if (!Conf.sseAttivo()) {
            return "<div class=\"ascolto spento\"><b>Ascolto comande SSE: SPENTO</b>"
                + "<br><small>" + dove
                + "<br>Serve solo dove c'e' la ristorazione, quindi nasce spento:"
                + " accendilo qui se questo tramite deve ricevere le comande."
                + "</small>" + interruttore() + "</div>";
        }
        if (!ClienteSse.collegato()) {
            return "<div class=\"ascolto fermo\"><b>Ascolto comande SSE: non collegato</b>"
                + "<br>" + perHtml(ClienteSse.descrizione())
                + "<br><small>" + dove + "</small>" + interruttore() + "</div>";
        }
        long battito = ClienteSse.secondiDallUltimoBattito();
        return "<div class=\"ascolto vivo\"><b>Ascolto comande SSE: collegato</b>"
            + "<br><small>" + dove
            + " &middot; da " + ClienteSse.secondiCollegato() + "s"
            + " &middot; " + ClienteSse.battiti() + " battiti"
            + (battito >= 0 ? " (l'ultimo " + battito + "s fa)" : "")
            + (ClienteSse.ultimoLavoro().isEmpty() ? ""
               : "<br>Ultima comanda: " + perHtml(ClienteSse.ultimoLavoro()))
            + "</small>" + interruttore() + "</div>";
    }

    /**
     * Accendi/spegni l'ascolto dalla pagina, per chi non ha l'app in mano.
     *
     * Un solo pulsante, quello che serve adesso: due bottoni affiancati fanno
     * chiedere quale sia lo stato attuale, e la risposta e' scritta due righe
     * sopra.
     */
    private static String interruttore() {
        boolean acceso = Conf.acceso();
        return "<form method=\"post\" action=\"/ascolto\" style=\"margin:8px 0 0\">"
            + "<input type=\"hidden\" name=\"attiva\" value=\"" + (acceso ? "0" : "1") + "\">"
            + "<button type=\"submit\">"
            + (acceso ? "Spegni ascolto comande SSE" : "Attiva ascolto comande SSE")
            + "</button></form>";
    }

    /** Gli indirizzi che si limitano a mostrare lo stato, senza farlo cambiare. */
    private static boolean vieneSoloAGuardare(String percorso) {
        return "/".equals(percorso) || "/stato".equals(percorso) || "/log".equals(percorso);
    }

    /**
     * L'indirizzo IPv4 del dispositivo in rete, o null se non si trova.
     *
     * Sta qui e non nella schermata perche' lo usano entrambe, e perche' con
     * NetworkInterface funziona anche quando l'agente gira da PC su JVM.
     */
    static String indirizzoLocale() {
        String ripiego = null;
        try {
            java.util.Enumeration<java.net.NetworkInterface> schede =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (schede.hasMoreElements()) {
                java.net.NetworkInterface scheda = schede.nextElement();
                if (scheda.isLoopback() || !scheda.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> indirizzi = scheda.getInetAddresses();
                while (indirizzi.hasMoreElements()) {
                    java.net.InetAddress a = indirizzi.nextElement();
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    // Col cavo attaccato ci sono due schede buone: si sceglie il
                    // Wi-Fi, che e' la rete su cui il dispositivo e' pensato per
                    // stare, e cosi' pagina e schermata dicono sempre lo stesso.
                    if (scheda.getName().startsWith("wlan")) {
                        return a.getHostAddress();
                    }
                    if (ripiego == null) {
                        ripiego = a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // niente da fare: chi chiama mostra un ripiego
        }
        return ripiego;
    }

    /** Il registro puo' contenere testo di stampa: va reso innocuo nella pagina. */
    private static String perHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void rispondiTesto(OutputStream out, String testo) throws IOException {
        byte[] dati = testo.getBytes("UTF-8");
        StringBuilder testa = new StringBuilder();
        testa.append("HTTP/1.1 200 OK\r\n");
        testa.append("Content-Type: text/plain; charset=utf-8\r\n");
        testa.append("Content-Length: ").append(dati.length).append("\r\n");
        intestazioniCors(testa);
        testa.append("Connection: close\r\n\r\n");
        out.write(testa.toString().getBytes("UTF-8"));
        out.write(dati);
        out.flush();
    }

    // ---- risposte -----------------------------------------------------------

    /**
     * ⚠ Il corpo e' JSON ma il tipo dichiarato e' text/plain, ed e' voluto: e'
     * quello che manda lo Zero (default di Werkzeug), e il client di Sublima fa
     * `$.parseJSON(data)` sulla risposta. Dichiarando application/json jQuery
     * farebbe il parsing da solo, parseJSON riceverebbe un oggetto invece di una
     * stringa e il TEST ZERO fallirebbe con "[object Object]" is not valid JSON.
     */
    private void rispondi(OutputStream out, int codice, String corpo) throws IOException {
        byte[] dati = corpo == null ? new byte[0] : corpo.getBytes("UTF-8");
        StringBuilder testa = new StringBuilder();
        testa.append("HTTP/1.1 ").append(codice).append(' ').append(spiegazione(codice))
             .append("\r\n");
        testa.append("Content-Type: text/plain; charset=utf-8\r\n");
        testa.append("Content-Length: ").append(dati.length).append("\r\n");
        intestazioniCors(testa);
        testa.append("Connection: close\r\n\r\n");
        out.write(testa.toString().getBytes("UTF-8"));
        out.write(dati);
        out.flush();
    }

    /** Rimanda altrove, per non ripetere un comando a ogni ricaricamento. */
    private void rispondiAltrove(OutputStream out, String dove) throws IOException {
        StringBuilder testa = new StringBuilder();
        testa.append("HTTP/1.1 303 See Other\r\n");
        testa.append("Location: ").append(dove).append("\r\n");
        testa.append("Content-Length: 0\r\n");
        intestazioniCors(testa);
        testa.append("Connection: close\r\n\r\n");
        out.write(testa.toString().getBytes("UTF-8"));
        out.flush();
    }

    private void rispondiHtml(OutputStream out, String html) throws IOException {
        byte[] dati = html.getBytes("UTF-8");
        StringBuilder testa = new StringBuilder();
        testa.append("HTTP/1.1 200 OK\r\n");
        testa.append("Content-Type: text/html; charset=utf-8\r\n");
        testa.append("Content-Length: ").append(dati.length).append("\r\n");
        intestazioniCors(testa);
        testa.append("Connection: close\r\n\r\n");
        out.write(testa.toString().getBytes("UTF-8"));
        out.write(dati);
        out.flush();
    }

    private static void intestazioniCors(StringBuilder testa) {
        testa.append("Access-Control-Allow-Origin: *\r\n");
        testa.append("Access-Control-Allow-Private-Network: true\r\n");
        testa.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        testa.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n");
        testa.append("Access-Control-Max-Age: 86400\r\n");
    }

    private static String spiegazione(int codice) {
        switch (codice) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            default: return "Response";
        }
    }

    /**
     * Ultime righe di registro, per la schermata dell'app.
     *
     * Su Android non c'e' una console da guardare, e chiedere a un installatore
     * di collegare adb per capire perche' non stampa non e' una risposta: le
     * righe recenti restano qui, leggibili dal dispositivo.
     */
    private static final java.util.LinkedList<String> RECENTI =
            new java.util.LinkedList<String>();
    private static final int QUANTE_RIGHE = 200;

    static void registra(String messaggio) {
        System.out.println("[zero] " + messaggio);
        synchronized (RECENTI) {
            RECENTI.addLast(orario() + "  " + messaggio);
            while (RECENTI.size() > QUANTE_RIGHE) {
                RECENTI.removeFirst();
            }
        }
    }

    /** Le righe recenti, dalla piu' nuova alla piu' vecchia. */
    public static String registroRecente() {
        StringBuilder sb = new StringBuilder();
        synchronized (RECENTI) {
            for (int i = RECENTI.size() - 1; i >= 0; i--) {
                sb.append(RECENTI.get(i)).append('\n');
            }
        }
        return sb.length() == 0 ? "(ancora niente)" : sb.toString();
    }

    private static String orario() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format("%02d:%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND));
    }
}
