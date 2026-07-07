package cz.jirnec.callandsmsstats;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_START = "start";
    public static final String EXTRA_END = "end";
    public static final String EXTRA_PERIOD = "period";

    /** Hodnota tagu chipu "Vše" – žádné filtrování podle typu. */
    private static final int FILTER_ALL = -1;
    /** Hodnota tagu chipu "Mobilní data" – rozpad dat po aplikacích. */
    private static final int FILTER_DATA = 100;

    private static final String PREFS = "detail_prefs";
    private static final String KEY_FILTER = "last_filter";

    private final DetailAdapter adapter = new DetailAdapter();
    private final AppDataAdapter appDataAdapter = new AppDataAdapter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView recyclerView;
    private TextView emptyView;
    private HorizontalScrollView filterScroll;
    private SwipeRefreshLayout swipeRefresh;
    private ChipGroup filterChips;
    private GestureDetector chipSwipe;
    private List<DetailEntry> allEntries;
    private SharedPreferences prefs;
    private long rangeStart;
    private long rangeEnd;

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
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(() -> loadEntries(rangeStart, rangeEnd));
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        filterChips = findViewById(R.id.filterChips);
        chipSwipe = ChipGestures.detector(recyclerView, filterChips);
        selectChip(prefs.getInt(KEY_FILTER, FILTER_ALL));
        filterScroll.post(this::scrollToCheckedChip);
        filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            int filter = currentFilter();
            prefs.edit().putInt(KEY_FILTER, filter).apply();
            if (allEntries != null) {
                applyFilter(filter);
            }
        });

        rangeStart = getIntent().getLongExtra(EXTRA_START, 0);
        rangeEnd = getIntent().getLongExtra(EXTRA_END, 0);
        Period period = Period.valueOf(getIntent().getStringExtra(EXTRA_PERIOD));
        LocalDate startDate = Instant.ofEpochMilli(rangeStart)
                .atZone(ZoneId.systemDefault()).toLocalDate();
        setTitle(PeriodLabels.label(this, period, startDate));

        loadEntries(rangeStart, rangeEnd);
    }

    private void loadEntries(long startMillis, long endMillis) {
        StatsRepository repository = new StatsRepository(this);
        executor.execute(() -> {
            final List<DetailEntry> entries = repository.loadEntriesInRange(startMillis, endMillis);
            runOnUiThread(() -> {
                swipeRefresh.setRefreshing(false);
                allEntries = entries;
                // Chipy necháváme vždy zobrazené – i při prázdných hovorech/SMS
                // může být k dispozici čip Mobilní data.
                filterScroll.setVisibility(View.VISIBLE);
                applyFilter(currentFilter());
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

    /** Odroluje pás čipů tak, aby byl vidět aktuálně vybraný chip. */
    private void scrollToCheckedChip() {
        int checkedId = filterChips.getCheckedChipId();
        if (checkedId == View.NO_ID) {
            return;
        }
        View chip = filterChips.findViewById(checkedId);
        if (chip != null) {
            filterScroll.smoothScrollTo(Math.max(0, chip.getLeft() - 32), 0);
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
        if (kind == FILTER_DATA) {
            showAppData();
            return;
        }
        if (allEntries == null) {
            return;
        }
        // Režim událostí – správný adaptér a zrušení případného odkazu na Nastavení.
        if (recyclerView.getAdapter() != adapter) {
            recyclerView.setAdapter(adapter);
        }
        emptyView.setOnClickListener(null);
        emptyView.setClickable(false);

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
            emptyView.setText(kind == FILTER_ALL ? R.string.detail_empty : R.string.filter_empty);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            adapter.setItems(filtered);
        }
    }

    /** Zobrazí rozpad mobilních dat po aplikacích za dané období. */
    private void showAppData() {
        if (recyclerView.getAdapter() != appDataAdapter) {
            recyclerView.setAdapter(appDataAdapter);
        }
        if (!StatsRepository.hasUsageAccess(this)) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setText(R.string.data_needs_usage_access);
            emptyView.setOnClickListener(v ->
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            emptyView.setClickable(true);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setOnClickListener(null);
        emptyView.setClickable(false);

        StatsRepository repository = new StatsRepository(this);
        executor.execute(() -> {
            final List<AppDataUsage> usage = repository.loadMobileDataByApp(rangeStart, rangeEnd);
            runOnUiThread(() -> {
                if (usage.isEmpty()) {
                    recyclerView.setVisibility(View.GONE);
                    emptyView.setText(R.string.data_empty);
                    emptyView.setVisibility(View.VISIBLE);
                } else {
                    emptyView.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                    appDataAdapter.setItems(usage);
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Po návratu z Nastavení (Usage access) v režimu Mobilní data přenačteme.
        if (allEntries != null && currentFilter() == FILTER_DATA) {
            applyFilter(FILTER_DATA);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (chipSwipe != null) {
            chipSwipe.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
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
