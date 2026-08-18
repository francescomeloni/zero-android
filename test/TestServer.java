import it.sublima.zeroandroid.Conf;
import it.sublima.zeroandroid.Richiesta;
import it.sublima.zeroandroid.ServerHttp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Verifica il server con richieste HTTP vere, su una porta effimera.
 *
 * Gira su JVM: niente emulatore, niente dispositivo, nessuna stampante.
 */
public class TestServer {

    private static int passati = 0;
    private static int falliti = 0;
    private static int porta;

    public static void main(String[] args) throws Exception {
        // porta 0: la sceglie il sistema, cosi' i test non litigano con nulla
        ServerHttp server = new ServerHttp(0, null);
        server.avvia();
        porta = server.porta();
        System.out.println("Server di prova sulla porta " + porta);
        System.out.println();

        try {
            presentazione();
            preflight();
            corsSempre();
            multipart();
            urlencoded();
            sconosciuto();
            stampaNonPronta();
            corpoEsagerato();
            agganciaDaTestZero();
        } finally {
            server.ferma();
        }

        System.out.println();
        System.out.println("=================================================");
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    /**
     * L'aggancio all'ascolto comande allegato al TEST ZERO.
     *
     * Le due cose che questa prova tiene ferme costano care se cambiano:
     * l'ascolto **non** si accende da solo (nove installazioni su dieci non lo
     * usano e una connessione tentata terrebbe occupato un thread del profilo
     * per niente), e da qui non si **spegne** mai (un tramite serve tutti i punti
     * cassa del punto vendita, e il TEST ZERO premuto dalla cassa fiscale
     * spegnerebbe i palmari).
     */
    private static void agganciaDaTestZero() throws Exception {
        System.out.println("/test_zero: l'aggancio all'ascolto comande");
        Conf.usaDeposito(new Conf.InMemoria());

        String dati = "tipo=TEST&url_profilo=http%3A%2F%2F192.0.2.77%3A55224&id_mag=4"
                + "&zero_licenza=ABC123";
        String corpo = corpo(chiama("POST", "/test_zero",
                "application/x-www-form-urlencoded", dati.getBytes("UTF-8")));

        verifica("ricorda l'indirizzo del profilo",
                "http://192.0.2.77:55224".equals(Conf.urlProfilo()));
        verifica("ricorda il magazzino", "4".equals(Conf.idMagazzino()));
        verifica("ricorda la licenza", "ABC123".equals(Conf.licenza()));
        verifica("ma NON accende l'ascolto da solo", !Conf.acceso());
        verifica("e lo dice nella risposta", corpo.contains("SPENTO"));

        corpo = corpo(chiama("POST", "/test_zero", "application/x-www-form-urlencoded",
                (dati + "&sse_attiva=1").getBytes("UTF-8")));
        verifica("si accende se Sublima lo chiede", Conf.acceso());
        verifica("e lo conferma", corpo.contains("in ascolto"));

        chiama("POST", "/test_zero", "application/x-www-form-urlencoded",
                (dati + "&sse_attiva=0").getBytes("UTF-8"));
        verifica("ma da qui non si spegne mai", Conf.acceso());

        // Un indirizzo che vale solo sul server non si accetta: dal tablet non
        // porta da nessuna parte, e il guasto sembrerebbe dell'ascolto.
        Conf.usaDeposito(new Conf.InMemoria());
        corpo = corpo(chiama("POST", "/test_zero", "application/x-www-form-urlencoded",
                "tipo=TEST&url_profilo=http%3A%2F%2F127.0.0.1%3A55224&id_mag=4"
                        .getBytes("UTF-8")));
        verifica("rifiuta un indirizzo di loopback", !Conf.agganciato());
        verifica("spiegando perche'", corpo.contains("dispositivo"));
        System.out.println();
    }

    private static void presentazione() throws Exception {
        System.out.println("/test_zero: la presentazione a Sublima");
        String r = chiama("POST", "/test_zero",
                "application/x-www-form-urlencoded", "tipo=TEST".getBytes("UTF-8"));
        String corpo = corpo(r);
        verifica("risponde 200", r.startsWith("HTTP/1.1 200"));
        verifica("Result vale OK", corpo.contains("\"Result\":\"OK\""));
        verifica("Message e' quello storico",
                corpo.contains("\"Message\":\"CONNESSIONE CON ZERO OK\""));
        verifica("dichiara la versione", corpo.contains("\"versione\":\""));
        verifica("dichiara di gestire i byte", corpo.contains("\"escpos_bytes\":true"));
        // Dichiarare cosa NON sa fare vale quanto dichiarare cosa sa: la scheda
        // del punto cassa puo' avvisare invece di lasciare l'installatore a
        // chiedersi perche' non esce niente.
        verifica("dichiara di NON interpretare i tag",
                corpo.contains("\"escpos_tag\":false"));
        // Con application/json jQuery pre-parsa e il $.parseJSON di ae_pos
        // fallisce con "[object Object]" is not valid JSON: lo Zero manda
        // text/plain e l'agente deve fare lo stesso.
        verifica("il tipo e' text/plain, come lo Zero",
                r.toLowerCase().contains("content-type: text/plain"));
        verifica("NON dichiara application/json",
                !r.toLowerCase().contains("application/json"));
        System.out.println("      " + corpo);
        System.out.println();
    }

    private static void preflight() throws Exception {
        System.out.println("OPTIONS: il preflight del browser");
        String r = chiama("OPTIONS", "/stampa_all", null, null);
        verifica("risponde 204", r.startsWith("HTTP/1.1 204"));
        verifica("concede l'origine", r.contains("Access-Control-Allow-Origin: *"));
        verifica("concede la rete locale",
                r.contains("Access-Control-Allow-Private-Network: true"));
        System.out.println();
    }

    private static void corsSempre() throws Exception {
        System.out.println("CORS presenti anche sugli errori");
        String r = chiama("GET", "/percorso/che/non/esiste", null, null);
        verifica("404 con l'origine concessa", r.contains("Access-Control-Allow-Origin: *"));
        verifica("404 con la rete locale concessa",
                r.contains("Access-Control-Allow-Private-Network: true"));
        System.out.println();
    }

    private static void multipart() throws Exception {
        System.out.println("Corpo in parti: e' cosi' che chiama il browser");
        String confine = "----ZeroTest7MA4YWxkTrZu0gW";
        String dati = "{\"tipo\":\"scontrino\",\"filescontrino\":\"prova.zip\"}";
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(confine).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"data\"\r\n\r\n");
        sb.append(dati).append("\r\n");
        sb.append("--").append(confine).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"setup\"\r\n\r\n");
        sb.append("{\"ip_cassa\":\"192.0.2.42\"}").append("\r\n");
        sb.append("--").append(confine).append("--\r\n");

        Richiesta r = leggiDaByte("POST", "/stampa_all",
                "multipart/form-data; boundary=" + confine, sb.toString());
        verifica("ritrova il campo data", dati.equals(r.campo("data")));
        verifica("ritrova anche setup",
                "{\"ip_cassa\":\"192.0.2.42\"}".equals(r.campo("setup")));
        System.out.println();
    }

    private static void urlencoded() throws Exception {
        System.out.println("Corpo codificato: e' cosi' che chiama Sublima (zero_direct)");
        String dati = "{\"tipo\":\"scontrino\"}";
        String corpo = "data=" + java.net.URLEncoder.encode(dati, "UTF-8")
                + "&setup=%7B%7D&direct=1";
        Richiesta r = leggiDaByte("POST", "/stampa_all",
                "application/x-www-form-urlencoded", corpo);
        verifica("ritrova il campo data", dati.equals(r.campo("data")));
        verifica("vede il flag direct", "1".equals(r.campo("direct")));
        System.out.println();
    }

    private static void sconosciuto() throws Exception {
        System.out.println("Percorso sconosciuto");
        String r = chiama("GET", "/boh", null, null);
        verifica("risponde 404", r.startsWith("HTTP/1.1 404"));
        verifica("lo dice in chiaro", corpo(r).contains("Indirizzo sconosciuto"));
        System.out.println();
    }

    private static void stampaNonPronta() throws Exception {
        System.out.println("/stampa_all finche' l'agente non sa stampare");
        String r = chiama("POST", "/stampa_all",
                "application/x-www-form-urlencoded", "data=%7B%7D".getBytes("UTF-8"));
        String corpo = corpo(r);
        verifica("risponde KO invece di fingere", corpo.contains("\"Result\":\"KO\""));
        verifica("spiega perche'", corpo.contains("non sa ancora stampare"));
        System.out.println();
    }

    private static void corpoEsagerato() throws Exception {
        System.out.println("Corpo dichiarato enorme: si rifiuta, non si riempie la memoria");
        Socket s = new Socket("127.0.0.1", porta);
        OutputStream out = s.getOutputStream();
        out.write(("POST /stampa_all HTTP/1.1\r\nHost: x\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: 999999999\r\n\r\n").getBytes("UTF-8"));
        out.flush();
        String r = new String(tutto(s.getInputStream()), "UTF-8");
        s.close();
        verifica("risponde 400", r.startsWith("HTTP/1.1 400"));
        verifica("dice che e' oltre il limite", r.contains("oltre il limite"));
        System.out.println();
    }

    // ---- appoggio -----------------------------------------------------------

    /** Fa una richiesta vera al server e restituisce la risposta grezza. */
    private static String chiama(String metodo, String percorso, String tipo, byte[] corpo)
            throws Exception {
        Socket s = new Socket("127.0.0.1", porta);
        s.setSoTimeout(10000);
        OutputStream out = s.getOutputStream();
        StringBuilder testa = new StringBuilder();
        testa.append(metodo).append(' ').append(percorso).append(" HTTP/1.1\r\n");
        testa.append("Host: 127.0.0.1\r\n");
        if (tipo != null) {
            testa.append("Content-Type: ").append(tipo).append("\r\n");
        }
        testa.append("Content-Length: ").append(corpo == null ? 0 : corpo.length).append("\r\n");
        testa.append("\r\n");
        out.write(testa.toString().getBytes("UTF-8"));
        if (corpo != null) {
            out.write(corpo);
        }
        out.flush();
        String risposta = new String(tutto(s.getInputStream()), "UTF-8");
        s.close();
        return risposta;
    }

    /** Verifica il solo parsing, senza passare dal server. */
    private static Richiesta leggiDaByte(String metodo, String percorso, String tipo,
                                         String corpo) throws Exception {
        byte[] dati = corpo.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append(metodo).append(' ').append(percorso).append(" HTTP/1.1\r\n");
        sb.append("Content-Type: ").append(tipo).append("\r\n");
        sb.append("Content-Length: ").append(dati.length).append("\r\n\r\n");
        ByteArrayOutputStream tutto = new ByteArrayOutputStream();
        tutto.write(sb.toString().getBytes("UTF-8"));
        tutto.write(dati);
        return Richiesta.leggi(new java.io.ByteArrayInputStream(tutto.toByteArray()));
    }

    private static byte[] tutto(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String corpo(String rispostaGrezza) {
        int stacco = rispostaGrezza.indexOf("\r\n\r\n");
        return stacco < 0 ? "" : rispostaGrezza.substring(stacco + 4);
    }

    private static void verifica(String cosa, boolean condizione) {
        if (condizione) {
            passati++;
            System.out.println("  [OK ] " + cosa);
        } else {
            falliti++;
            System.out.println("  [NO ] " + cosa);
        }
    }
}
