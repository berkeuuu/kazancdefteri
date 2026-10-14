package app.kazancdefteri;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** Her cuma 12:00 için bir sonraki hatırlatmayı kurar. */
public class Reminder {

    public static void schedule(Context ctx) {
        try {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 12);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            int diff = Calendar.FRIDAY - c.get(Calendar.DAY_OF_WEEK);
            if (diff < 0) {
                diff += 7;
            }
            c.add(Calendar.DAY_OF_MONTH, diff);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) {
                c.add(Calendar.DAY_OF_MONTH, 7);
            }
            Intent i = new Intent(ctx, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            }
        } catch (Exception e) {
            // hatırlatma kurulamadıysa uygulama yine çalışır
        }
    }
}
