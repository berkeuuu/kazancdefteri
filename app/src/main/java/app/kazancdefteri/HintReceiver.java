package app.kazancdefteri;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** Okumadan 10 dk sonra saatlik hız bildirimini gösterir. */
public class HintReceiver extends BroadcastReceiver {

    public static void schedule(Context ctx, String title, String text, long delayMs) {
        try {
            Intent i = new Intent(ctx, HintReceiver.class);
            i.putExtra("t", title);
            i.putExtra("x", text);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi);
            }
        } catch (Exception e) {
            // bildirim kurulamadıysa uygulama yine çalışır
        }
    }

    public static void cancel(Context ctx) {
        try {
            Intent i = new Intent(ctx, HintReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pi);
            }
        } catch (Exception e) {
            // önemli değil
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 23) {
                return;
            }
            String t = intent.getStringExtra("t");
            String x = intent.getStringExtra("x");
            if (t == null || x == null) {
                return;
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel("hiz", "Saatlik hız",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
                Intent open = new Intent(ctx, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(ctx, 3, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(ctx, "hiz")
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle(t)
                        .setContentText(x)
                        .setStyle(new Notification.BigTextStyle().bigText(x))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify(1002, n);
            }
        } catch (Exception e) {
            // bildirim gösterilemedi
        }
    }
}
