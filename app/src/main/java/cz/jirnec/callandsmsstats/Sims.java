package cz.jirnec.callandsmsstats;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** Výčet aktivních SIM karet (vyžaduje READ_PHONE_STATE). */
public final class Sims {

    public static final class SimInfo {
        public final int subId;
        public final String label;

        SimInfo(int subId, String label) {
            this.subId = subId;
            this.label = label;
        }
    }

    private Sims() {
    }

    @SuppressLint("MissingPermission")
    public static List<SimInfo> active(Context context) {
        List<SimInfo> result = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return result;
        }
        SubscriptionManager sm = (SubscriptionManager)
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (sm == null) {
            return result;
        }
        List<SubscriptionInfo> subs;
        try {
            subs = sm.getActiveSubscriptionInfoList();
        } catch (Exception e) {
            return result;
        }
        if (subs == null) {
            return result;
        }
        for (SubscriptionInfo si : subs) {
            String label = si.getDisplayName() != null ? si.getDisplayName().toString().trim() : "";
            if (label.isEmpty()) {
                label = "SIM " + (si.getSimSlotIndex() + 1);
            }
            result.add(new SimInfo(si.getSubscriptionId(), label));
        }
        return result;
    }
}
