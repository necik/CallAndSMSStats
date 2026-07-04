package cz.jirnec.callandsmsstats;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MonthStatsAdapter extends RecyclerView.Adapter<MonthStatsAdapter.ViewHolder> {

    public interface OnMonthClickListener {
        void onMonthClick(MonthStat stat);
    }

    private final List<MonthStat> items = new ArrayList<>();
    private final DateTimeFormatter monthFormatter =
            DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault());
    private OnMonthClickListener clickListener;

    public void setOnMonthClickListener(OnMonthClickListener listener) {
        this.clickListener = listener;
    }

    public void setItems(List<MonthStat> newItems) {
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
        MonthStat stat = items.get(position);
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onMonthClick(stat);
            }
        });
        holder.title.setText(capitalize(stat.month.format(monthFormatter)));
        holder.incomingCalls.setText(callValue(holder, stat.incomingCallSeconds, stat.incomingCallCount));
        holder.outgoingCalls.setText(callValue(holder, stat.outgoingCallSeconds, stat.outgoingCallCount));
        holder.missedCalls.setText(String.valueOf(stat.missedCalls));
        holder.rejectedCalls.setText(String.valueOf(stat.rejectedCalls));
        holder.incomingSms.setText(String.valueOf(stat.incomingSms));
        holder.outgoingSms.setText(String.valueOf(stat.outgoingSms));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Hodnota buňky hovorů: celkový čas a v závorce počet, např. "02:15:43 · 12×". */
    private String callValue(ViewHolder holder, long seconds, int count) {
        return holder.itemView.getContext().getString(
                R.string.call_value_format, MonthStat.formatDuration(seconds), count);
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Locale locale = Locale.getDefault();
        return text.substring(0, 1).toUpperCase(locale) + text.substring(1);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView incomingCalls;
        final TextView outgoingCalls;
        final TextView missedCalls;
        final TextView rejectedCalls;
        final TextView incomingSms;
        final TextView outgoingSms;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.monthTitle);
            incomingCalls = itemView.findViewById(R.id.incomingCallsValue);
            outgoingCalls = itemView.findViewById(R.id.outgoingCallsValue);
            missedCalls = itemView.findViewById(R.id.missedCallsValue);
            rejectedCalls = itemView.findViewById(R.id.rejectedCallsValue);
            incomingSms = itemView.findViewById(R.id.incomingSmsValue);
            outgoingSms = itemView.findViewById(R.id.outgoingSmsValue);
        }
    }
}
