package it.sublima.zeroandroid;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * La configurazione ricordata in un file, per quando l'agente gira su un PC.
 *
 * Sull'app il deposito sono le preferenze di sistema; qui serve a provare tutto
 * il percorso da riga di comando senza tirare in ballo Android. E' anche il
 * deposito che usano i test, puntato su una cartella temporanea.
 *
 * Un guasto in lettura o scrittura non ferma l'agente: al massimo la
 * configurazione non sopravvive al riavvio, e chi chiama lo dice a video.
 */
public class DepositoFile implements Conf.Deposito {

    private final File file;
    private final Properties valori = new Properties();

    public DepositoFile() {
        this(new File(new File(System.getProperty("user.home", "."), ".zero_android"),
                "config.properties"));
    }

    public DepositoFile(File file) {
        this.file = file;
        carica();
    }

    private void carica() {
        if (!file.isFile()) {
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            valori.load(in);
        } catch (Exception e) {
            ServerHttp.registra("configurazione illeggibile in " + file + ": " + e.getMessage());
        } finally {
            chiudi(in);
        }
    }

    public synchronized String leggi(String chiave) {
        return valori.getProperty(chiave);
    }

    public synchronized void scrivi(String chiave, String valore) {
        if (valore == null || valore.isEmpty()) {
            valori.remove(chiave);
        } else {
            valori.setProperty(chiave, valore);
        }
        salva();
    }

    private void salva() {
        FileOutputStream out = null;
        try {
            File cartella = file.getParentFile();
            if (cartella != null && !cartella.isDirectory()) {
                cartella.mkdirs();
            }
            out = new FileOutputStream(file);
            valori.store(out, "Zero Android - configurazione dell'ascolto comande");
        } catch (Exception e) {
            ServerHttp.registra("configurazione non salvata in " + file + ": " + e.getMessage());
        } finally {
            chiudi(out);
        }
    }

    private static void chiudi(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception e) {
            // niente da fare
        }
    }
}
