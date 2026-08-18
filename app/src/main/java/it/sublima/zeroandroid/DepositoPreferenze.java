package it.sublima.zeroandroid;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * La configurazione ricordata dal sistema, sull'app.
 *
 * E' l'unica classe del deposito che tocchi Android, e sta da sola per questo:
 * `tools/prova.sh` esclude dai test tutto cio' che importa `android.*`, e il
 * resto della configurazione deve restare verificabile su JVM.
 */
public class DepositoPreferenze implements Conf.Deposito {

    private static final String ARCHIVIO = "zero_android";

    private final SharedPreferences preferenze;

    public DepositoPreferenze(Context contesto) {
        this.preferenze = contesto.getApplicationContext()
                .getSharedPreferences(ARCHIVIO, Context.MODE_PRIVATE);
    }

    public String leggi(String chiave) {
        return preferenze.getString(chiave, null);
    }

    public void scrivi(String chiave, String valore) {
        SharedPreferences.Editor e = preferenze.edit();
        if (valore == null || valore.isEmpty()) {
            e.remove(chiave);
        } else {
            e.putString(chiave, valore);
        }
        e.apply();
    }
}
