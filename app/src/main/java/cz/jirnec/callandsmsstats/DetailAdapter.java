package cz.jirnec.callandsmsstats;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DetailAdapter extends RecyclerView.Adapter<DetailAdapter.ViewHolder> {

    private static final Locale CZECH = new Locale("cs");

    private static final int[] LABEL_RES = {
            R.string.detail_incoming_call,
            R.string.detail_outgoing_call,
            R.string.detail_missed_call,
            R.string.detail_rejected_call,
            R.string.detail_incoming_sms,
            R.string.detail_outgoing_sms
    };

    private final List<DetailEntry> items = new ArrayList<>();
    private final ZoneId zone = ZoneId.systemDefault();
    private final DateTimeFormatter dateTimeFormatter =
            DateTimeFormatter.ofPattern("d.M.yyyy H:mm", CZECH);

    public void setItems(List<DetailEntry> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_detail, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DetailEntry entry = items.get(position);

        holder.type.setText(LABEL_RES[entry.kind]);

        if (entry.contact == null || entry.contact.isEmpty()) {
            holder.contact.setText(R.string.detail_unknown_number);
        } else {
            holder.contact.setText(entry.contact);
        }

        String dateTime = Instant.ofEpochMilli(entry.timestamp)
                .atZone(zone)
                .format(dateTimeFormatter);
        holder.dateTime.setText(dateTime);

        if (entry.hasDuration()) {
            holder.duration.setVisibility(View.VISIBLE);
            holder.duration.setText(MonthStat.formatDuration(entry.durationSeconds));
        } else {
            holder.duration.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView type;
        final TextView contact;
        final TextView dateTime;
        final TextView duration;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            type = itemView.findViewById(R.id.detailType);
            contact = itemView.findViewById(R.id.detailContact);
            dateTime = itemView.findViewById(R.id.detailDateTime);
            duration = itemView.findViewById(R.id.detailDuration);
        }
    }
}
