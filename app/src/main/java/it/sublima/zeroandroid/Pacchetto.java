package it.sublima.zeroandroid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lo ZIP che Sublima manda dentro `corpo_scontrino`.
 *
 * Contiene N file di stampa piu' un `service_file_*.pickle` che dice, per
 * ciascuno, a quale stampante va e come. Si tiene tutto in memoria: sono
 * decine di KB, e cosi' non si sporca il disco del dispositivo.
 *
 * ⚠️ Si stampa quello che il MANIFEST elenca, non quello che l'archivio
 * contiene: un file in piu' nello ZIP va ignorato, ed e' il modo in cui
 * Sublima puo' aggiungere contenuti senza rompere i tramiti piu' vecchi.
 */
public final class Pacchetto {

    private final Map<String, byte[]> file = new LinkedHashMap<String, byte[]>();
    private Map<String, Object> manifest;
    private String nomeManifest;

    public static class PacchettoNonValido extends Exception {
        public PacchettoNonValido(String messaggio) {
            super(messaggio);
        }
    }

    public static Pacchetto apri(byte[] zip) throws PacchettoNonValido {
        Pacchetto p = new Pacchetto();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip));
        try {
            ZipEntry voce;
            while ((voce = zis.getNextEntry()) != null) {
                if (voce.isDirectory()) {
                    continue;
                }
                p.file.put(nomeSemplice(voce.getName()), leggiTutto(zis));
            }
        } catch (IOException e) {
            throw new PacchettoNonValido("archivio illeggibile: " + e.getMessage());
        } finally {
            try {
                zis.close();
            } catch (IOException e) {
                // niente da fare
            }
        }

        if (p.file.isEmpty()) {
            throw new PacchettoNonValido("archivio vuoto");
        }

        for (Map.Entry<String, byte[]> e : p.file.entrySet()) {
            if (e.getKey().contains("service_file")) {
                p.nomeManifest = e.getKey();
                try {
                    p.manifest = Pickle.leggiManifest(e.getValue());
                } catch (Pickle.PickleNonLeggibile ex) {
                    throw new PacchettoNonValido("manifest illeggibile: " + ex.getMessage());
                }
                break;
            }
        }
        if (p.manifest == null) {
            throw new PacchettoNonValido(
                    "manca il service_file: senza non si sa a quale stampante mandare");
        }
        return p;
    }

    /** I nomi dei file da stampare, nell'ordine in cui il manifest li elenca. */
    public List<String> daStampare() {
        return new ArrayList<String>(manifest.keySet());
    }

    /** I dati della stampante per quel file. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> stampantePer(String nomefile) {
        Object v = manifest.get(nomefile);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    /** Il contenuto del file di stampa, o null se il manifest lo elenca ma non c'e'. */
    public byte[] contenuto(String nomefile) {
        return file.get(nomeSemplice(nomefile));
    }

    public String nomeManifest() {
        return nomeManifest;
    }

    public int quantiFile() {
        return file.size();
    }

    /**
     * Solo il nome, senza percorso: gli archivi possono contenere separatori di
     * entrambi i sistemi, e non si scrive comunque nulla su disco.
     */
    private static String nomeSemplice(String nome) {
        if (nome == null) {
            return "";
        }
        int barra = Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\'));
        return barra >= 0 ? nome.substring(barra + 1) : nome;
    }

    private static byte[] leggiTutto(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
