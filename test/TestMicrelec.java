import it.sublima.zeroandroid.Micrelec;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Prova il dialogo con un registratore Micrelec finto.
 *
 * Il finto registratore risponde come quello vero: codici in esadecimale, e i
 * dati fiscali nei campi 9, 10 e 5 delle risposte a "0/" e "i/".
 *
 * Gira su JVM: nessun registratore, nessuno scontrino fiscale emesso.
 */
public class TestMicrelec {

    private static int passati = 0;
    private static int falliti = 0;

    /** Cosa il finto registratore ha ricevuto, nell'ordine. */
    private static final List<String> ricevuti = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        System.out.println("Lettura dei codici di errore (in esadecimale!)");
        System.out.println("----------------------------------------------");
        verifica("00 vale pronta", Micrelec.codice("00/1/2/") == 0);
        // 0x33 e' 51 in decimale: leggerlo in decimale darebbe 33, cioe' un
        // errore diverso. E' l'inciampo che questo test serve a evitare.
        verifica("33 e' sportello aperto (0x33 = 51)", Micrelec.codice("33/1/") == 51);
        verifica("2C e' fine carta (0x2C = 44)", Micrelec.codice("2C/1/") == 44);
        verifica("36 e' cassa sul menu (0x36 = 54)", Micrelec.codice("36/1/") == 54);
        verifica("una risposta vuota non passa per pronta", Micrelec.codice("") == -1);
        verifica("lo dice in parole", "fine carta".equals(Micrelec.descrizione(44)));
        System.out.println();

        stampaNormale();
        conDatiFiscali();
        sportelloAperto();
        cassaSulMenu();
        registratoreSpento();

        System.out.println();
        System.out.println("=================================================");
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    private static void stampaNormale() throws Exception {
        System.out.println("Scontrino senza richiesta dei dati fiscali");
        Registratore r = new Registratore("00/0/0/");
        try {
            Micrelec.Esito e = Micrelec.stampa("127.0.0.1", r.porta,
                    "W/2\n3/S/CAFFE//1.000/1.20/1/////33\n".getBytes("ISO-8859-1"), false);
            verifica("riesce", e.riuscito);
            verifica("ha chiesto lo stato per primo",
                    !ricevuti.isEmpty() && "?".equals(ricevuti.get(0)));
            verifica("ha mandato le due righe", ricevuti.size() == 3);
            verifica("non ha chiesto il numero", e.numero == null);
        } finally {
            r.chiudi();
        }
        System.out.println();
    }

    private static void conDatiFiscali() throws Exception {
        System.out.println("Scontrino con numero e chiusura da restituire a Sublima");
        Registratore r = new Registratore("00/0/0/");
        // "0/" -> il numero sta nel campo 9 e il reso nel 10
        r.rispostaA("0/", "00/a/b/c/d/e/f/g/h/1234/0/x/");
        // "i/" -> la chiusura sta nel campo 5, ed e' l'ultima fatta
        r.rispostaA("i/", "00/a/b/c/d/57/x/");
        try {
            Micrelec.Esito e = Micrelec.stampa("127.0.0.1", r.porta,
                    "W/2\n".getBytes("ISO-8859-1"), true);
            verifica("riesce", e.riuscito);
            verifica("legge il numero dal campo 9", "1234".equals(e.numero));
            verifica("legge il reso dal campo 10", "0".equals(e.reso));
            verifica("la chiusura e' l'ultima piu' uno", "58".equals(e.chiusura));
        } finally {
            r.chiudi();
        }
        System.out.println();
    }

    private static void sportelloAperto() throws Exception {
        System.out.println("Sportello aperto: non si stampa e si dice perche'");
        Registratore r = new Registratore("33/0/0/");
        try {
            long inizio = System.currentTimeMillis();
            Micrelec.Esito e = Micrelec.stampa("127.0.0.1", r.porta,
                    "W/2\n".getBytes("ISO-8859-1"), false);
            long durata = System.currentTimeMillis() - inizio;
            verifica("non riesce", !e.riuscito);
            verifica("nomina lo sportello", e.messaggio.contains("sportello aperto"));
            verifica("rinuncia in tempo utile (meno di 10 s)", durata < 10000);
            System.out.println("      ha insistito per " + durata + " ms");
        } finally {
            r.chiudi();
        }
        System.out.println();
    }

    private static void cassaSulMenu() throws Exception {
        System.out.println("Cassa sui menu: la si riporta fuori e si ripete la riga");
        Registratore r = new Registratore("00/0/0/");
        r.rispostaA("W/2", "36/0/0/");   // la prima volta risponde "sono sul menu"
        r.rispostaDopoEsc("W/2", "00/0/0/");
        try {
            Micrelec.Esito e = Micrelec.stampa("127.0.0.1", r.porta,
                    "W/2\n".getBytes("ISO-8859-1"), false);
            verifica("alla fine riesce", e.riuscito);
            int quantiEsc = 0;
            for (String c : ricevuti) {
                if (")/118/0/".equals(c)) {
                    quantiEsc++;
                }
            }
            verifica("ha mandato quattro volte il comando di uscita", quantiEsc == 4);
        } finally {
            r.chiudi();
        }
        System.out.println();
    }

    private static void registratoreSpento() throws Exception {
        System.out.println("Registratore spento");
        Micrelec.Esito e = Micrelec.stampa("127.0.0.9", 9101,
                "W/2\n".getBytes("ISO-8859-1"), false);
        verifica("non riesce", !e.riuscito);
        verifica("lo dice in chiaro", e.messaggio.toLowerCase().contains("spento")
                || e.messaggio.toLowerCase().contains("rifiuta"));
        System.out.println();
    }

    // ---- il finto registratore ---------------------------------------------

    private static class Registratore {
        final ServerSocket socket;
        final int porta;
        final java.util.Map<String, String> risposte = new java.util.HashMap<String, String>();
        final java.util.Map<String, String> dopoEsc = new java.util.HashMap<String, String>();
        final String predefinita;
        volatile boolean escRicevuto;

        Registratore(String predefinita) throws Exception {
            this.predefinita = predefinita;
            ricevuti.clear();
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            porta = socket.getLocalPort();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    servi();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void rispostaA(String comando, String risposta) {
            risposte.put(comando, risposta);
        }

        void rispostaDopoEsc(String comando, String risposta) {
            dopoEsc.put(comando, risposta);
        }

        private void servi() {
            try {
                Socket c = socket.accept();
                c.setSoTimeout(20000);
                InputStream in = c.getInputStream();
                OutputStream out = c.getOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    String comando = new String(buf, 0, n, "ISO-8859-1");
                    ricevuti.add(comando);
                    if (")/118/0/".equals(comando)) {
                        escRicevuto = true;
                    }
                    String risposta;
                    if (escRicevuto && dopoEsc.containsKey(comando)) {
                        risposta = dopoEsc.get(comando);
                    } else if (risposte.containsKey(comando)) {
                        risposta = risposte.get(comando);
                    } else {
                        risposta = predefinita;
                    }
                    out.write(risposta.getBytes("ISO-8859-1"));
                    out.flush();
                }
                c.close();
            } catch (Exception e) {
                // il mittente ha chiuso: e' la fine normale della conversazione
            }
        }

        void chiudi() {
            try {
                socket.close();
            } catch (Exception e) {
                // niente da fare
            }
        }
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
