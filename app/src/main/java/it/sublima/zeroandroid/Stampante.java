package it.sublima.zeroandroid;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * L'invio vero alla stampante di rete.
 *
 * ⚠️ La porta e' 9100 fissa per i tracciati ESC/POS, e non si usa `ip_port` del
 * manifest: lo Zero istanzia la stampante di rete senza indicare la porta e
 * finisce sul default della libreria, cioe' 9100. Un tramite che rispettasse
 * `ip_port` stamperebbe da un'altra parte. Vedi il paragrafo 3 di PROTOCOLLO.md.
 *
 * I tempi sono corti apposta: una stampante spenta deve dare errore in pochi
 * secondi, non tenere ferma la cassa.
 */
public final class Stampante {

    public static final int PORTA_ESCPOS = 9100;
    public static final int ATTESA_CONNESSIONE = 6000;
    public static final int ATTESA_SCRITTURA = 12000;

    private Stampante() {
    }

    public static class NonRaggiungibile extends Exception {
        public NonRaggiungibile(String messaggio) {
            super(messaggio);
        }
    }

    /** Apre il socket, scrive i byte cosi' come sono e chiude. */
    public static void invia(String ip, int porta, byte[] dati) throws NonRaggiungibile {
        if (ip == null || ip.trim().isEmpty()) {
            throw new NonRaggiungibile("indirizzo della stampante non configurato");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip.trim(), porta), ATTESA_CONNESSIONE);
            socket.setSoTimeout(ATTESA_SCRITTURA);
            OutputStream out = socket.getOutputStream();
            out.write(dati);
            out.flush();
        } catch (java.net.SocketTimeoutException e) {
            throw new NonRaggiungibile("la stampante " + ip + " non ha risposto entro "
                    + (ATTESA_CONNESSIONE / 1000) + " secondi");
        } catch (java.net.ConnectException e) {
            throw new NonRaggiungibile("la stampante " + ip + ":" + porta
                    + " rifiuta la connessione: spenta o indirizzo sbagliato");
        } catch (Exception e) {
            throw new NonRaggiungibile("errore parlando con " + ip + ":" + porta
                    + " - " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // chiudere un socket gia' chiuso non e' un problema
            }
        }
    }

    /** Verifica che qualcuno risponda, senza stampare nulla. */
    public static boolean risponde(String ip, int porta) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip.trim(), porta), ATTESA_CONNESSIONE);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // niente da fare
            }
        }
    }
}
