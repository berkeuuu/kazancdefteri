package app.kazancdefteri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Cuma 12:00'de "hangi gün izinlisin?" bildirimi gösterir. */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel("izin", "İzin hatırlatma",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
                Intent open = new Intent(ctx, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(ctx, 2, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(ctx, "izin")
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle("Gelecek hafta hangi gün izinlisin?")
                        .setContentText("Kazanç Defteri'ni aç, izin gününü seç.")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify(1001, n);
            }
        } catch (Exception e) {
            // bildirim gösterilemedi
        }
        Reminder.schedule(ctx);
    }
}
