import it.sublima.zeroandroid.Base64;
import it.sublima.zeroandroid.StampanteXml;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Prova i registratori che si comandano via HTTP: Epson e Custom.
 *
 * Il finto registratore registra indirizzo, intestazioni e corpo di quello che
 * riceve, cosi' si verifica che l'agente bussi alla porta giusta e consegni il
 * tracciato tale e quale.
 *
 * Gira su JVM: nessun registratore vero, nessun documento emesso.
 */
public class TestXml {

    private static int passati = 0;
    private static int falliti = 0;

    private static volatile String richiestaRicevuta = "";
    private static volatile String corpoRicevuto = "";
    private static volatile String rispostaDaDare = "<risposta>ok</risposta>";
    private static volatile int codiceDaDare = 200;

    public static void main(String[] args) throws Exception {
        System.out.println("Codifica per l'autenticazione");
        System.out.println("-----------------------------");
        // valore di riferimento noto: e' quello che si trova su qualsiasi tabella
        verifica("'matricola:matricola' viene codificata come deve",
                "OEFJR0UwMDAzMDk6OEFJR0UwMDAzMDk="
                        .equals(Base64.codifica("8AIGE000309:8AIGE000309".getBytes("UTF-8"))));
        System.out.println();

        epsonBussaAlPostoGiusto();
        customBussaAlPostoGiusto();
        customSiAutentica();
        registratoreCheProtesta();
        registratoreSpento();

        System.out.println();
        System.out.println("=================================================");
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    private static void epsonBussaAlPostoGiusto() throws Exception {
        System.out.println("Epson");
        Registratore r = new Registratore();
        try {
            String xml = "<printerCommand><directIO command=\"1\"/></printerCommand>";
            StampanteXml.epson("127.0.0.1:" + r.porta, xml.getBytes("UTF-8"));
            attendi();
            verifica("bussa a /cgi-bin/fpmate.cgi",
                    richiestaRicevuta.startsWith("POST /cgi-bin/fpmate.cgi"));
            verifica("dichiara text/xml",
                    richiestaRicevuta.toLowerCase().contains("content-type: text/xml"));
            verifica("il tracciato arriva tale e quale", corpoRicevuto.equals(xml));
        } finally {
            r.chiudi();
        }
        System.out.println();
    }

    private static void customBussaAlPostoGiusto() throws Exception {
        System.out.println("Custom");
        Registratore r = new Registratore();
        try {
            String xml = "<Service><printRecItem/></Service>";
            StampanteXml.custom("127.0.0.1:" + r.porta, xml.getBytes("UTF-8"), "8AIGE000309");
            attendi();
            verifica("bussa a /xml/printer.htm",
                    richiestaRicevuta.startsWith("POST /xml/printer.htm"));
            verifica("dichiara text/plain",
                    richiestaRicevuta.toLowerCase().contains("content-type: text/plain"));
            verifica("il tracciato arriva tale e quale", corpoRicevuto.equals(xml));
        } finally {
            r.chiudi();
        }
        System.out.println();
    }

    private static void customSiAutentica() throws Exception {
        System.out.println("Custom: la matricola vale come utente e password");
        Registratore r = new Registratore();
        try {
            StampanteXml.custom("127.0.0.1:" + r.porta, "<x/>".getBytes("UTF-8"),
                    "8AIGE000309");
            attendi();
            verifica("manda l'autenticazione",
                    richiestaRicevuta.contains("Authorization: Basic "));
            verifica("con la matricola ripetuta due volte",
                    richiestaRicevuta.contains("OEFJR0UwMDAzMDk6OEFJR0UwMDAzMDk="));
        } finally {
            r.chiudi();
        }
        System.out.println();
    }

    private static void registratoreCheProtesta() throws Exception {
        System.out.println("Il registratore risponde con un errore");
        Registratore r = new Registratore();
        codiceDaDare = 500;
        rispostaDaDare = "errore interno del registratore";
        try {
            StampanteXml.epson("127.0.0.1:" + r.porta, "<x/>".getBytes("UTF-8"));
            verifica("l'errore non passa inosservato", false);
        } catch (StampanteXml.NonRiuscita e) {
            verifica("l'errore viene riportato", true);
            verifica("dice il codice", e.getMessage().contains("500"));
            verifica("riporta cosa ha detto il registratore",
                    e.getMessage().contains("errore interno"));
        } finally {
            codiceDaDare = 200;
            rispostaDaDare = "<risposta>ok</risposta>";
            r.chiudi();
        }
        System.out.println();
    }

    private static void registratoreSpento() throws Exception {
        System.out.println("Registratore spento");
        try {
            StampanteXml.epson("127.0.0.9:9", "<x/>".getBytes("UTF-8"));
            verifica("non passa per riuscita", false);
        } catch (StampanteXml.NonRiuscita e) {
            verifica("lo dice in chiaro", e.getMessage().toLowerCase().contains("spento")
                    || e.getMessage().toLowerCase().contains("rifiuta")
                    || e.getMessage().toLowerCase().contains("errore"));
        }
        System.out.println();
    }

    // ---- il finto registratore ---------------------------------------------

    private static class Registratore {
        final ServerSocket socket;
        final int porta;

        Registratore() throws Exception {
            richiestaRicevuta = "";
            corpoRicevuto = "";
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

        private void servi() {
            try {
                Socket c = socket.accept();
                c.setSoTimeout(5000);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream tutto = new ByteArrayOutputStream();
                byte[] buf = new byte[1];
                StringBuilder testa = new StringBuilder();
                int quanti = 0;
                // le intestazioni, fino alla riga vuota
                while (in.read(buf) > 0) {
                    testa.append((char) (buf[0] & 0xFF));
                    if (testa.toString().endsWith("\r\n\r\n")) {
                        break;
                    }
                }
                richiestaRicevuta = testa.toString();
                for (String riga : richiestaRicevuta.split("\r\n")) {
                    if (riga.toLowerCase().startsWith("content-length:")) {
                        quanti = Integer.parseInt(riga.split(":")[1].trim());
                    }
                }
                for (int i = 0; i < quanti; i++) {
                    if (in.read(buf) <= 0) {
                        break;
                    }
                    tutto.write(buf[0]);
                }
                corpoRicevuto = new String(tutto.toByteArray(), "UTF-8");

                byte[] risposta = rispostaDaDare.getBytes("UTF-8");
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 " + codiceDaDare + " x\r\nContent-Length: "
                        + risposta.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes("UTF-8"));
                out.write(risposta);
                out.flush();
                c.close();
            } catch (Exception e) {
                // fine normale
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

    private static void attendi() throws Exception {
        for (int i = 0; i < 30 && richiestaRicevuta.isEmpty(); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(100);
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
