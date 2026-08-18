import it.sublima.zeroandroid.Richiesta;
import it.sublima.zeroandroid.Stampa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Prova /stampa_all da capo a fondo, con una stampante finta in ascolto.
 *
 * I pacchetti li costruisce test/genera_pacchetti.py con gli stessi zipfile e
 * pickle che usa Sublima, quindi quello che arriva qui e' identico a quello che
 * arriverebbe da una cassa vera.
 *
 * Gira su JVM: nessuna stampante, nessun dispositivo.
 */
public class TestStampa {

    private static int passati = 0;
    private static int falliti = 0;
    private static int portaFinta;
    private static ServerSocket stampanteFinta;
    private static volatile byte[] ultimaStampa;
    private static volatile int quanteStampe;

    public static void main(String[] args) throws Exception {
        apriStampanteFinta();
        System.out.println("Stampante finta in ascolto sulla porta " + portaFinta);
        System.out.println();

        try {
            unaStampante();
            dueStampanti();
            deduplicazione();
            tracciatoATag();
            micrelecInstradato();
            displayNonEUnoScontrino();
            driverNonGestito();
            collegamentoNonDiRete();
            fileMancante();
            senzaManifest();
            stampanteSpenta();
            corpoVuoto();
            messaggioRotto();
        } finally {
            stampanteFinta.close();
        }

        System.out.println();
        System.out.println("=================================================");
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    // ---- i casi -------------------------------------------------------------

    private static void unaStampante() throws Exception {
        System.out.println("Una comanda gia' tradotta in byte");
        quanteStampe = 0;
        ultimaStampa = null;
        String r = esegui(new Stampa(portaFinta), caso("lavoro_byte"));
        attendiStampe(1);
        verifica("risponde OK", r.contains("\"Result\":\"OK\""));
        verifica("dice che ha completato", r.contains("Tutte le stampanti"));
        verifica("la stampante ha ricevuto qualcosa", ultimaStampa != null);
        verifica("i byte arrivano intatti, con ESC in testa",
                ultimaStampa != null && ultimaStampa.length > 0 && ultimaStampa[0] == 0x1b);
        verifica("il taglio arriva in fondo",
                ultimaStampa != null && finisceCon(ultimaStampa, new byte[]{0x1d, 'V', 0}));
        System.out.println("      " + ultimaStampa.length + " byte sulla carta");
        System.out.println();
    }

    private static void dueStampanti() throws Exception {
        System.out.println("Due stampanti nello stesso lavoro");
        quanteStampe = 0;
        String r = esegui(new Stampa(portaFinta), caso("lavoro_due_stampanti"));
        attendiStampe(2);
        verifica("risponde OK", r.contains("\"Result\":\"OK\""));
        verifica("conta due su due", r.contains("(2/2)"));
        verifica("ha stampato due volte", quanteStampe == 2);
        System.out.println();
    }

    private static void deduplicazione() throws Exception {
        System.out.println("Lo stesso lavoro che arriva due volte");
        Stampa stampa = new Stampa(portaFinta);
        quanteStampe = 0;
        esegui(stampa, caso("lavoro_byte"));
        attendiStampe(1);
        int dopoLaPrima = quanteStampe;
        String r = esegui(stampa, caso("lavoro_byte"));
        Thread.sleep(300);
        verifica("la seconda volta risponde OK", r.contains("\"Result\":\"OK\""));
        verifica("dichiara il doppione", r.contains("\"dedup\":true"));
        verifica("NON ristampa", quanteStampe == dopoLaPrima);
        System.out.println();
    }

    private static void tracciatoATag() throws Exception {
        System.out.println("Tracciato a tag: l'agente non lo interpreta e lo dice");
        quanteStampe = 0;
        String r = esegui(new Stampa(portaFinta), caso("lavoro_tag"));
        Thread.sleep(300);
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("spiega cosa fare", r.contains("Byte nativi"));
        verifica("non manda i tag alla stampante", quanteStampe == 0);
        System.out.println();
    }

    private static void micrelecInstradato() throws Exception {
        System.out.println("Micrelec: instradato sul trasporto giusto, non su quello ESC/POS");
        String r = esegui(new Stampa(portaFinta), caso("lavoro_micrelec"));
        Thread.sleep(300);
        // La stampante finta scrive e basta, quindi il dialogo Micrelec non puo'
        // riuscire: quello che conta e' che l'agente abbia provato a parlare
        // Micrelec, e lo si vede da come descrive l'errore. Il dialogo vero e'
        // verificato in TestMicrelec, con un registratore finto.
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("ha parlato col registratore, non scritto e basta",
                r.contains("registratore"));
        System.out.println();
    }

    private static void displayNonEUnoScontrino() throws Exception {
        System.out.println("Display: non deve essere scambiato per uno scontrino");
        String r = esegui(new Stampa(portaFinta), caso("lavoro_display"));
        Thread.sleep(300);
        // Il tipo sta solo nel manifest: cercandolo nel messaggio esterno si
        // finiva per interrogare il registratore sui dati fiscali e leggere il
        // numero dello scontrino precedente, allarmando per nulla.
        verifica("non parla di numero dello scontrino",
                !r.toLowerCase().contains("numero"));
        verifica("non parla di registrazione su Sublima",
                !r.contains("NON registrato"));
        System.out.println();
    }

    private static void driverNonGestito() throws Exception {
        System.out.println("Driver che l'agente non gestisce");
        quanteStampe = 0;
        String r = esegui(new Stampa(portaFinta), caso("lavoro_driver_ignoto"));
        Thread.sleep(300);
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("nomina il driver", r.contains("WINECR"));
        verifica("non manda nulla", quanteStampe == 0);
        System.out.println();
    }

    private static void collegamentoNonDiRete() throws Exception {
        System.out.println("Stampante seriale: fuori dalla portata dell'agente");
        quanteStampe = 0;
        String r = esegui(new Stampa(portaFinta), caso("lavoro_seriale"));
        Thread.sleep(300);
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("lo spiega", r.contains("stampanti di rete"));
        verifica("non manda nulla", quanteStampe == 0);
        System.out.println();
    }

    private static void fileMancante() throws Exception {
        System.out.println("Il manifest elenca un file che nell'archivio non c'e'");
        String r = esegui(new Stampa(portaFinta), caso("lavoro_file_mancante"));
        Thread.sleep(300);
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("dice quale file manca", r.contains("nell'archivio non c'e'"));
        System.out.println();
    }

    private static void senzaManifest() throws Exception {
        System.out.println("Archivio senza service_file");
        String r = esegui(new Stampa(portaFinta), caso("lavoro_senza_manifest"));
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("spiega perche'", r.contains("manca il service_file"));
        System.out.println();
    }

    private static void stampanteSpenta() throws Exception {
        System.out.println("Stampante spenta: errore in fretta, senza bloccare la cassa");
        long inizio = System.currentTimeMillis();
        String r = esegui(new Stampa(portaFinta), caso("lavoro_stampante_spenta"));
        long durata = System.currentTimeMillis() - inizio;
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("nomina l'indirizzo", r.contains("127.0.0.9"));
        verifica("ci mette meno di 10 secondi", durata < 10000);
        System.out.println("      ha impiegato " + durata + " ms");
        System.out.println();
    }

    private static void corpoVuoto() throws Exception {
        System.out.println("Lavoro vuoto: non e' un errore");
        String r = esegui(new Stampa(portaFinta),
                "{\"tipo\":\"scontrino\",\"filescontrino\":\"\",\"corpo_scontrino\":\"\"}");
        verifica("risponde OK", r.contains("\"Result\":\"OK\""));
        verifica("lo dice", r.contains("Niente da stampare"));
        System.out.println();
    }

    private static void messaggioRotto() throws Exception {
        System.out.println("Messaggio illeggibile");
        String r = esegui(new Stampa(portaFinta), "{questo non e' json");
        verifica("risponde KO", r.contains("\"Result\":\"KO\""));
        verifica("dice che non si legge", r.contains("illeggibile"));
        System.out.println();
    }

    // ---- appoggio -----------------------------------------------------------

    private static String esegui(Stampa stampa, String data) throws Exception {
        String corpo = "data=" + java.net.URLEncoder.encode(data, "UTF-8");
        byte[] dati = corpo.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("POST /stampa_all HTTP/1.1\r\n");
        sb.append("Content-Type: application/x-www-form-urlencoded\r\n");
        sb.append("Content-Length: ").append(dati.length).append("\r\n\r\n");
        ByteArrayOutputStream tutto = new ByteArrayOutputStream();
        tutto.write(sb.toString().getBytes("UTF-8"));
        tutto.write(dati);
        Richiesta r = Richiesta.leggi(new ByteArrayInputStream(tutto.toByteArray()));
        return stampa.esegui(r);
    }

    private static String caso(String nome) throws Exception {
        File f = new File("test/casi/" + nome + ".data");
        if (!f.exists()) {
            System.out.println("Manca " + f.getPath()
                    + ": lancia prima  python3 test/genera_pacchetti.py");
            System.exit(2);
        }
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void apriStampanteFinta() throws Exception {
        // Legata a 127.0.0.1 e non a tutte le interfacce: su Linux l'intera
        // 127.0.0.0/8 e' locale, quindi ascoltando ovunque risponderebbe anche
        // all'indirizzo che nel caso "stampante spenta" deve invece rifiutare.
        stampanteFinta = new ServerSocket(0, 50,
                java.net.InetAddress.getByName("127.0.0.1"));
        portaFinta = stampanteFinta.getLocalPort();
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!stampanteFinta.isClosed()) {
                    try {
                        Socket c = stampanteFinta.accept();
                        c.setSoTimeout(1500);
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        try {
                            while ((n = c.getInputStream().read(b)) > 0) {
                                buf.write(b, 0, n);
                            }
                        } catch (Exception e) {
                            // il mittente ha chiuso: quello che e' arrivato basta
                        }
                        ultimaStampa = buf.toByteArray();
                        quanteStampe++;
                        c.close();
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** La stampante finta legge su un altro thread: le si da' il tempo di finire. */
    private static void attendiStampe(int quante) throws Exception {
        for (int i = 0; i < 50 && quanteStampe < quante; i++) {
            Thread.sleep(100);
        }
        Thread.sleep(150);
    }

    private static boolean finisceCon(byte[] dati, byte[] coda) {
        if (dati.length < coda.length) {
            return false;
        }
        for (int i = 0; i < coda.length; i++) {
            if (dati[dati.length - coda.length + i] != coda[i]) {
                return false;
            }
        }
        return true;
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
