package com.saggitt.omega.smartspace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;
import com.saggitt.omega.smartspace.nano.SmartspaceProto;
import com.saggitt.omega.smartspace.nano.SmartspaceProto.b;
import com.saggitt.omega.util.Config;

public class SmartspaceBroadcastReceiver extends BroadcastReceiver {
    private void cg(b b, Context context, Intent intent, boolean b2) throws PackageManager.NameNotFoundException {
        if (b.cy) {
            SmartspaceController.get(context).cV(null);
            return;
        }
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(Config.GOOGLE_QSB, 0);
            SmartspaceController.get(context).cV(new NewCardInfo(b, intent, b2, SystemClock.uptimeMillis(), packageInfo));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    public void onReceive(Context context, Intent intent) {
        byte[] byteArrayExtra = intent.getByteArrayExtra("com.google.android.apps.nexuslauncher.extra.SMARTSPACE_CARD");
        if (byteArrayExtra != null) {
            SmartspaceProto.a a = new SmartspaceProto.a();
            try {
                com.google.protobuf.nano.MessageNano.mergeFrom(a, byteArrayExtra);
                b[] cw = a.cw;
                int length = cw.length;
                int i = 0;
                while (i < length) {
                    b b2 = cw[i];
                    boolean b3 = b2.cz == 1;
                    if (b3 || b2.cz == 2) {
                        cg(b2, context, intent, b3);
                    } else {
                        Log.w("SmartspaceReceiver", "unrecognized card priority");
                    }
                    ++i;
                }
            } catch (InvalidProtocolBufferNanoException | PackageManager.NameNotFoundException ex) {
                Log.e("SmartspaceReceiver", "proto", ex);
            }
        } else {
            Log.e("SmartspaceReceiver", "receiving update with no proto: " + intent.getExtras());
        }
    }
}
