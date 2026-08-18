package it.sublima.zeroandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Tiene acceso l'agente anche quando l'app non e' in primo piano.
 *
 * E' un servizio in primo piano con notifica fissa: e' l'unico modo perche'
 * Android non lo chiuda dopo qualche minuto. In piu' tiene due appigli:
 *
 *  - WakeLock, perche' il processore non si addormenti fra una comanda e l'altra;
 *  - WifiLock, perche' il Wi-Fi resti attivo a schermo spento — senza, il
 *    telefono lo mette a riposo e le stampe smettono di arrivare, con il
 *    dispositivo che pero' sembra acceso e collegato.
 *
 * Tutto questo non basta su tutti i telefoni: Xiaomi, Huawei e Oppo chiudono
 * comunque le app che non hanno l'avvio automatico concesso a mano. La
 * schermata lo ricorda.
 */
public class Servizio extends Service {

    public static final String CANALE = "zero_android";
    public static final int NOTIFICA = 1;

    private static volatile boolean acceso;
    private static volatile String statoUltimo = "";

    private ServerHttp server;
    private ClienteSse ascolto;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static boolean acceso() {
        return acceso;
    }

    public static String stato() {
        return statoUltimo;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Versione.dichiaraPiattaforma("Android " + Build.VERSION.RELEASE
                + " - " + Build.MANUFACTURER + " " + Build.MODEL);
        // Va fatto per primo: l'aggancio al profilo si legge da qui, e senza
        // deposito l'agente ripartirebbe ogni volta senza sapere chi ascoltare.
        Conf.usaDeposito(new DepositoPreferenze(this));
        preparaCanale();
        startForeground(NOTIFICA, notifica("Avvio in corso..."));
        prendiGliAppigli();
        avviaServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Se Android lo chiude per fare spazio, lo fa ripartire da solo.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (ascolto != null) {
            ascolto.ferma();
        }
        if (server != null) {
            server.ferma();
        }
        rilasciaGliAppigli();
        acceso = false;
        // senza, la schermata continua a mostrare l'ultima frase di avviaServer():
        // "In ascolto sulla porta ..." scritto in rosso a servizio ormai fermo
        statoUltimo = "Spento";
        super.onDestroy();
    }

    private void avviaServer() {
        // Un solo oggetto Stampa per i due ingressi — la porta 55226 e l'ascolto
        // delle comande — cosi' lo stesso lavoro arrivato da tutt'e due le
        // strade viene riconosciuto e stampato una volta sola.
        Stampa stampa = new Stampa();
        try {
            server = new ServerHttp(ServerHttp.PORTA_ZERO, stampa);
            server.avvia();
            acceso = true;
            statoUltimo = "In ascolto sulla porta " + ServerHttp.PORTA_ZERO;
        } catch (java.net.BindException e) {
            acceso = false;
            statoUltimo = "Porta " + ServerHttp.PORTA_ZERO
                    + " gia' occupata da un'altra app";
        } catch (Exception e) {
            acceso = false;
            statoUltimo = "Non riesco ad aprire la porta: " + e.getMessage();
        }

        // ⚠️ FUORI dal try della porta, e di proposito: l'ascolto delle comande
        // non usa la 55226, quindi se quella e' occupata da un'altra app le
        // comande possono comunque arrivare. Legarlo al server voleva dire
        // perdere tutto per un conflitto che non lo riguarda.
        // Parte anche senza aggancio: aspetta in silenzio, e il TEST ZERO lo
        // mette in moto senza riavviare l'app.
        ascolto = new ClienteSse(stampa);
        ascolto.avvia();

        aggiornaNotifica(statoUltimo);
    }

    private void prendiGliAppigli() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zero:agente");
            wakeLock.acquire();

            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            int modo = (Build.VERSION.SDK_INT >= 12)
                    ? WifiManager.WIFI_MODE_FULL_HIGH_PERF : WifiManager.WIFI_MODE_FULL;
            wifiLock = wm.createWifiLock(modo, "zero:wifi");
            wifiLock.acquire();
        } catch (Exception e) {
            // senza appigli l'agente funziona lo stesso finche' il telefono e'
            // sveglio: non e' un motivo per non partire
            ServerHttp.registra("appigli non ottenuti: " + e.getMessage());
        }
    }

    private void rilasciaGliAppigli() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            // niente da fare
        }
    }

    private void preparaCanale() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel canale = new NotificationChannel(CANALE, "Agente di stampa",
                NotificationManager.IMPORTANCE_LOW);
        canale.setDescription("Tiene acceso il servizio che stampa comande e scontrini");
        canale.setShowBadge(false);
        nm.createNotificationChannel(canale);
    }

    private Notification notifica(String testo) {
        Intent apri = new Intent(this, Schermata.class);
        int bandiere = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            bandiere |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent tocco = PendingIntent.getActivity(this, 0, apri, bandiere);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CANALE) : new Notification.Builder(this);
        return b.setContentTitle(Versione.NOME + " " + Versione.NUMERO)
                .setContentText(testo)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(tocco)
                .setOngoing(true)
                .build();
    }

    private void aggiornaNotifica(String testo) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIFICA, notifica(testo));
        } catch (Exception e) {
            // niente da fare
        }
    }
}
