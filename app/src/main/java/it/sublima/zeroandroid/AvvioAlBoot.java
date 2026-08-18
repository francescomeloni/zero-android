package it.sublima.zeroandroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Fa ripartire l'agente quando il dispositivo si riaccende.
 *
 * Senza questo, dopo un riavvio notturno o un calo di corrente il tablet
 * tornerebbe su ma le comande non arriverebbero piu', e nessuno saprebbe
 * perche' finche' qualcuno non riapre l'app.
 */
public class AvvioAlBoot extends BroadcastReceiver {

    @Override
    public void onReceive(Context contesto, Intent intent) {
        String azione = (intent == null) ? "" : String.valueOf(intent.getAction());
        if (!"android.intent.action.BOOT_COMPLETED".equals(azione)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(azione)) {
            return;
        }
        try {
            Intent servizio = new Intent(contesto, Servizio.class);
            if (Build.VERSION.SDK_INT >= 26) {
                contesto.startForegroundService(servizio);
            } else {
                contesto.startService(servizio);
            }
        } catch (Exception e) {
            // su alcuni telefoni l'avvio automatico va concesso a mano: se e'
            // negato non si puo' fare altro che lasciar perdere in silenzio
        }
    }
}
