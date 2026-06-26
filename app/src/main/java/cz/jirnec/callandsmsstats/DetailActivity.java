package cz.jirnec.callandsmsstats;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_YEAR = "year";
    public static final String EXTRA_MONTH = "month";

    private static final Locale CZECH = new Locale("cs");

    /** Hodnota tagu chipu "Vše" – žádné filtrování podle typu. */
    private static final int FILTER_ALL = -1;

    private static final String PREFS = "detail_prefs";
    private static final String KEY_FILTER = "last_filter";

    private final DetailAdapter adapter = new DetailAdapter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView recyclerView;
    private TextView emptyView;
    private View filterScroll;
    private ChipGroup filterChips;
    private List<DetailEntry> allEntries;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        recyclerView = findViewById(R.id.detailRecyclerView);
        emptyView = findViewById(R.id.detailEmptyView);
        filterScroll = findViewById(R.id.filterScroll);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        filterChips = findViewById(R.id.filterChips);
        selectChip(prefs.getInt(KEY_FILTER, FILTER_ALL));
        filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            int filter = currentFilter();
            prefs.edit().putInt(KEY_FILTER, filter).apply();
            if (allEntries != null) {
                applyFilter(filter);
            }
        });

        int year = getIntent().getIntExtra(EXTRA_YEAR, 0);
        int month = getIntent().getIntExtra(EXTRA_MONTH, 0);
        YearMonth yearMonth = YearMonth.of(year, month);
        setTitle(formatTitle(yearMonth));

        loadEntries(yearMonth);
    }

    private String formatTitle(YearMonth yearMonth) {
        String text = yearMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", CZECH));
        return text.substring(0, 1).toUpperCase(CZECH) + text.substring(1);
    }

    private void loadEntries(YearMonth yearMonth) {
        StatsRepository repository = new StatsRepository(this);
        executor.execute(() -> {
            final List<DetailEntry> entries = repository.loadEntriesForMonth(yearMonth);
            runOnUiThread(() -> {
                allEntries = entries;
                if (entries.isEmpty()) {
                    filterScroll.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.GONE);
                    emptyView.setText(R.string.detail_empty);
                    emptyView.setVisibility(View.VISIBLE);
                } else {
                    filterScroll.setVisibility(View.VISIBLE);
                    applyFilter(currentFilter());
                }
            });
        });
    }

    /** Zaškrtne chip odpovídající danému filtru (podle tagu). */
    private void selectChip(int kind) {
        for (int i = 0; i < filterChips.getChildCount(); i++) {
            View child = filterChips.getChildAt(i);
            if (child instanceof Chip && Integer.parseInt(child.getTag().toString()) == kind) {
                ((Chip) child).setChecked(true);
                return;
            }
        }
    }

    /** Vrátí tag aktuálně zaškrtnutého chipu (FILTER_ALL nebo některý DetailEntry kind). */
    private int currentFilter() {
        int checkedId = filterChips.getCheckedChipId();
        if (checkedId == View.NO_ID) {
            return FILTER_ALL;
        }
        Chip chip = filterChips.findViewById(checkedId);
        return Integer.parseInt(chip.getTag().toString());
    }

    private void applyFilter(int kind) {
        if (allEntries == null) {
            return;
        }
        List<DetailEntry> filtered;
        if (kind == FILTER_ALL) {
            filtered = allEntries;
        } else {
            filtered = new ArrayList<>();
            for (DetailEntry entry : allEntries) {
                if (entry.kind == kind) {
                    filtered.add(entry);
                }
            }
        }

        if (filtered.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setText(R.string.filter_empty);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.setItems(filtered);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
