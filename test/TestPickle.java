import it.sublima.zeroandroid.Pickle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Verifica che il lettore Java ricostruisca gli stessi manifest che Python ha
 * scritto. I casi li genera test/genera_casi.py.
 *
 * Gira su JVM, senza emulatore e senza dispositivo.
 */
public class TestPickle {

    private static int passati = 0;
    private static int falliti = 0;

    public static void main(String[] args) throws Exception {
        File casi = new File(args.length > 0 ? args[0] : "test/casi");
        if (!casi.isDirectory()) {
            System.out.println("Casi non trovati in " + casi.getPath()
                    + ": lancia prima  python3 test/genera_casi.py");
            System.exit(2);
        }

        System.out.println("Lettura dei manifest generati da Python");
        System.out.println("---------------------------------------");

        File[] elenco = casi.listFiles();
        List<String> nomi = new ArrayList<String>();
        for (File f : elenco) {
            if (f.getName().endsWith(".pickle")) {
                nomi.add(f.getName().substring(0, f.getName().length() - 7));
            }
        }
        Collections.sort(nomi);

        for (String nome : nomi) {
            confronta(new File(casi, nome + ".pickle"), new File(casi, nome + ".atteso"), nome);
        }

        System.out.println();
        System.out.println("Manifest malformati (devono dare un errore chiaro)");
        System.out.println("-------------------------------------------------");
        rifiuta("vuoto", new byte[0]);
        rifiuta("troncato", new byte[]{(byte) 0x80, 0x05, '}'});
        rifiuta("opcode di costruzione oggetti", new byte[]{(byte) 0x80, 0x05, 'c', 'o', 's', '.'});

        System.out.println();
        System.out.println("=================================================");
        System.out.println("ESITO: " + passati + " superati, " + falliti + " falliti");
        System.out.println("=================================================");
        System.exit(falliti == 0 ? 0 : 1);
    }

    private static void confronta(File pickle, File atteso, String nome) {
        try {
            Map<String, Object> manifest = Pickle.leggiManifest(leggiTutto(pickle));
            String ottenuto = canonica(manifest);
            String voluto = new String(leggiTutto(atteso), "UTF-8");
            if (ottenuto.equals(voluto)) {
                ok(nome);
            } else {
                no(nome, primaDifferenza(voluto, ottenuto));
            }
        } catch (Exception e) {
            no(nome, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void rifiuta(String nome, byte[] dati) {
        try {
            Pickle.leggiManifest(dati);
            no(nome, "accettato, mentre doveva essere rifiutato");
        } catch (Pickle.PickleNonLeggibile e) {
            ok(nome + " -> " + e.getMessage());
        } catch (Exception e) {
            no(nome, "errore inatteso " + e.getClass().getSimpleName());
        }
    }

    /** Stessa forma canonica prodotta da genera_casi.py. */
    @SuppressWarnings("unchecked")
    private static String canonica(Map<String, Object> manifest) {
        List<String> nomi = new ArrayList<String>(manifest.keySet());
        Collections.sort(nomi);
        StringBuilder sb = new StringBuilder();
        for (String nomefile : nomi) {
            Map<String, Object> dati = (Map<String, Object>) manifest.get(nomefile);
            List<String> chiavi = new ArrayList<String>(dati.keySet());
            Collections.sort(chiavi);
            for (String chiave : chiavi) {
                Object v = dati.get(chiave);
                String tipo;
                String valore;
                if (v == null) {
                    tipo = "none";
                    valore = "";
                } else if (v instanceof Boolean) {
                    tipo = "bool";
                    valore = ((Boolean) v).booleanValue() ? "true" : "false";
                } else if (v instanceof Long || v instanceof Integer) {
                    tipo = "int";
                    valore = String.valueOf(v);
                } else if (v instanceof Double) {
                    tipo = "float";
                    valore = String.valueOf(v);
                } else {
                    tipo = "str";
                    valore = String.valueOf(v);
                }
                sb.append(nomefile).append('|').append(chiave).append('|')
                  .append(tipo).append('|').append(valore).append('\n');
            }
        }
        return sb.toString();
    }

    private static String primaDifferenza(String voluto, String ottenuto) {
        String[] a = voluto.split("\n");
        String[] b = ottenuto.split("\n");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String ra = i < a.length ? a[i] : "(manca)";
            String rb = i < b.length ? b[i] : "(manca)";
            if (!ra.equals(rb)) {
                return "riga " + (i + 1) + "\n         atteso:   " + ra
                        + "\n         ottenuto: " + rb;
            }
        }
        return "differenza non localizzata";
    }

    private static byte[] leggiTutto(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void ok(String cosa) {
        passati++;
        System.out.println("  [OK ] " + cosa);
    }

    private static void no(String cosa, String perche) {
        falliti++;
        System.out.println("  [NO ] " + cosa + " -> " + perche);
    }
}
