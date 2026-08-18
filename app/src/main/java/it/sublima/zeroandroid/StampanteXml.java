package it.sublima.zeroandroid;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * I registratori che si comandano via HTTP invece che via socket: Epson e Custom.
 *
 * Qui non c'e' nessun dialogo da condurre: il tracciato XML arriva gia' fatto da
 * Sublima e va consegnato tale e quale. Cambiano l'indirizzo, il tipo di
 * contenuto e, per Custom, l'autenticazione.
 *
 * Vedi il paragrafo 3 di PROTOCOLLO.md nel repo dello Zero.
 */
public final class StampanteXml {

    /** Epson risponde in fretta o non risponde: attesa corta, come nello Zero. */
    public static final int ATTESA_EPSON = 3000;
    public static final int ATTESA_CUSTOM = 10000;

    private StampanteXml() {
    }

    public static class NonRiuscita extends Exception {
        public NonRiuscita(String messaggio) {
            super(messaggio);
        }
    }

    public static String epson(String ip, byte[] xml) throws NonRiuscita {
        return consegna("http://" + ip.trim() + "/cgi-bin/fpmate.cgi",
                "text/xml; charset=UTF-8", xml, null, ATTESA_EPSON, "Epson");
    }

    /**
     * Custom vuole l'autenticazione, e usa la matricola sia come utente sia
     * come password.
     */
    public static String custom(String ip, byte[] xml, String matricola)
            throws NonRiuscita {
        String credenziali = null;
        if (matricola != null && !matricola.trim().isEmpty()) {
            String coppia = matricola.trim() + ":" + matricola.trim();
            try {
                credenziali = "Basic " + Base64.codifica(coppia.getBytes("UTF-8"));
            } catch (Exception e) {
                credenziali = null;
            }
        }
        return consegna("http://" + ip.trim() + "/xml/printer.htm",
                "text/plain", xml, credenziali, ATTESA_CUSTOM, "Custom");
    }

    private static String consegna(String indirizzo, String tipo, byte[] contenuto,
                                   String autorizzazione, int attesa, String chi)
            throws NonRiuscita {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(indirizzo).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(attesa);
            c.setReadTimeout(attesa);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", tipo);
            if (autorizzazione != null) {
                c.setRequestProperty("Authorization", autorizzazione);
            }
            OutputStream out = c.getOutputStream();
            out.write(contenuto);
            out.flush();
            out.close();

            int codice = c.getResponseCode();
            String risposta = leggi(codice < 400 ? c.getInputStream() : c.getErrorStream());
            if (codice < 200 || codice >= 300) {
                throw new NonRiuscita("il registratore " + chi + " ha risposto " + codice
                        + (risposta.isEmpty() ? "" : ": " + accorcia(risposta)));
            }
            return risposta;
        } catch (NonRiuscita e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new NonRiuscita("il registratore " + chi + " non ha risposto entro "
                    + (attesa / 1000) + " secondi");
        } catch (java.net.ConnectException e) {
            throw new NonRiuscita("il registratore " + chi
                    + " rifiuta la connessione: spento o indirizzo sbagliato");
        } catch (Exception e) {
            throw new NonRiuscita("errore parlando col registratore " + chi + ": "
                    + e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String leggi(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception e) {
                // niente da fare
            }
        }
    }

    private static String accorcia(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
