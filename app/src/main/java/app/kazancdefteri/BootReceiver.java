package app.kazancdefteri;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Telefon yeniden başlayınca cuma hatırlatmasını tekrar kurar. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Reminder.schedule(ctx);
    }
}
