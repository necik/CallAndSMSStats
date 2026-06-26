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

    private static final Locale CZECH = new Locale("cs");

    private final List<MonthStat> items = new ArrayList<>();
    private final DateTimeFormatter monthFormatter =
            DateTimeFormatter.ofPattern("LLLL yyyy", CZECH);

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
        holder.title.setText(capitalize(stat.month.format(monthFormatter)));
        holder.incomingCalls.setText(MonthStat.formatDuration(stat.incomingCallSeconds));
        holder.outgoingCalls.setText(MonthStat.formatDuration(stat.outgoingCallSeconds));
        holder.missedCalls.setText(String.valueOf(stat.missedCalls));
        holder.rejectedCalls.setText(String.valueOf(stat.rejectedCalls));
        holder.incomingSms.setText(String.valueOf(stat.incomingSms));
        holder.outgoingSms.setText(String.valueOf(stat.outgoingSms));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.substring(0, 1).toUpperCase(CZECH) + text.substring(1);
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
