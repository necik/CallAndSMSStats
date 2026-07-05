package cz.jirnec.callandsmsstats;

import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

public class PeriodStatsAdapter extends RecyclerView.Adapter<PeriodStatsAdapter.ViewHolder> {

    public interface OnPeriodClickListener {
        void onPeriodClick(PeriodStat stat);
    }

    /** Požadavek na líné dotažení mobilních dat pro dané období. */
    public interface MobileDataLoader {
        void load(PeriodStat stat);
    }

    private final List<PeriodStat> items = new ArrayList<>();
    private OnPeriodClickListener clickListener;
    private Runnable onEnableDataClick;
    private MobileDataLoader dataLoader;
    private boolean usageAccessGranted;

    public void setOnPeriodClickListener(OnPeriodClickListener listener) {
        this.clickListener = listener;
    }

    /** Akce po klepnutí na „—" u mobilních dat, když chybí Usage access. */
    public void setOnEnableDataClick(Runnable listener) {
        this.onEnableDataClick = listener;
    }

    public void setMobileDataLoader(MobileDataLoader loader) {
        this.dataLoader = loader;
    }

    public void setUsageAccessGranted(boolean granted) {
        this.usageAccessGranted = granted;
    }

    /** Zapíše dotažená mobilní data a překreslí příslušnou položku (je-li stále v seznamu). */
    public void updateMobileData(PeriodStat stat, long bytes) {
        stat.mobileDataBytes = bytes;
        stat.dataLoaded = true;
        stat.dataLoading = false;
        int index = items.indexOf(stat);
        if (index >= 0) {
            notifyItemChanged(index);
        }
    }

    public void setItems(List<PeriodStat> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_month, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PeriodStat stat = items.get(position);
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onPeriodClick(stat);
            }
        });
        holder.title.setText(
                PeriodLabels.label(holder.itemView.getContext(), stat.period, stat.start));
        holder.incomingCalls.setText(callValue(holder, stat.incomingCallSeconds, stat.incomingCallCount));
        holder.outgoingCalls.setText(callValue(holder, stat.outgoingCallSeconds, stat.outgoingCallCount));
        holder.missedCalls.setText(String.valueOf(stat.missedCalls));
        holder.rejectedCalls.setText(String.valueOf(stat.rejectedCalls));
        holder.incomingSms.setText(String.valueOf(stat.incomingSms));
        holder.outgoingSms.setText(String.valueOf(stat.outgoingSms));
        bindMobileData(holder, stat);
    }

    private void bindMobileData(ViewHolder holder, PeriodStat stat) {
        TextView view = holder.mobileData;

        if (!usageAccessGranted) {
            // Bez Usage access – klikatelné „—", které otevře Nastavení.
            view.setText("—");
            view.setTextColor(MaterialColors.getColor(view,
                    com.google.android.material.R.attr.colorPrimary));
            view.setPaintFlags(view.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            view.setOnClickListener(v -> {
                if (onEnableDataClick != null) {
                    onEnableDataClick.run();
                }
            });
            return;
        }

        // Přístup je – zrušíme případné „odkazové" formátování z recyklované položky.
        view.setTextColor(holder.defaultValueColor);
        view.setPaintFlags(view.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
        view.setOnClickListener(null);
        view.setClickable(false);

        if (stat.dataLoaded) {
            view.setText(stat.mobileDataBytes >= 0
                    ? PeriodStat.formatDataSize(stat.mobileDataBytes)
                    : "—");
        } else {
            // Líné načtení jen pro zobrazená období.
            view.setText("…");
            if (!stat.dataLoading && dataLoader != null) {
                stat.dataLoading = true;
                dataLoader.load(stat);
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Hodnota buňky hovorů: celkový čas a počet, např. "02:15:43 (12)". */
    private String callValue(ViewHolder holder, long seconds, int count) {
        return holder.itemView.getContext().getString(
                R.string.call_value_format, PeriodStat.formatDuration(seconds), count);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView incomingCalls;
        final TextView outgoingCalls;
        final TextView missedCalls;
        final TextView rejectedCalls;
        final TextView incomingSms;
        final TextView outgoingSms;
        final TextView mobileData;
        final ColorStateList defaultValueColor;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.monthTitle);
            incomingCalls = itemView.findViewById(R.id.incomingCallsValue);
            outgoingCalls = itemView.findViewById(R.id.outgoingCallsValue);
            missedCalls = itemView.findViewById(R.id.missedCallsValue);
            rejectedCalls = itemView.findViewById(R.id.rejectedCallsValue);
            incomingSms = itemView.findViewById(R.id.incomingSmsValue);
            outgoingSms = itemView.findViewById(R.id.outgoingSmsValue);
            mobileData = itemView.findViewById(R.id.mobileDataValue);
            defaultValueColor = mobileData.getTextColors();
        }
    }
}
