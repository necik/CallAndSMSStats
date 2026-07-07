package cz.jirnec.callandsmsstats;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Seznam spotřeby mobilních dat po aplikacích (v detailu období). */
public class AppDataAdapter extends RecyclerView.Adapter<AppDataAdapter.ViewHolder> {

    private final List<AppDataUsage> items = new ArrayList<>();

    public void setItems(List<AppDataUsage> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_data, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppDataUsage item = items.get(position);
        holder.name.setText(item.label);
        holder.bytes.setText(PeriodStat.formatDataSize(item.bytes));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView bytes;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.appName);
            bytes = itemView.findViewById(R.id.appBytes);
        }
    }
}
