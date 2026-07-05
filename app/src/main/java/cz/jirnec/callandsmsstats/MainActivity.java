package cz.jirnec.callandsmsstats;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_CONTACTS = 101;

    /** Povinná oprávnění – bez nich nelze statistiky vůbec sestavit. */
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS
    };

    /** Žádáme i kontakty (pro jména u SMS), ale jsou nepovinné. */
    private static final String[] REQUESTED_PERMISSIONS = {
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS
    };

    private static final String PREFS = "main_prefs";
    private static final String KEY_PERIOD = "period";
    private static final String KEY_ASKED_USAGE = "asked_usage_access";

    private final PeriodStatsAdapter adapter = new PeriodStatsAdapter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService dataExecutor = Executors.newSingleThreadExecutor();

    private RecyclerView recyclerView;
    private TextView emptyView;
    private View progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private ChipGroup periodChips;
    private GestureDetector chipSwipe;
    private SharedPreferences prefs;
    private boolean usageAccessAtLastLoad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setSupportActionBar(findViewById(R.id.toolbar));

        // Edge-to-edge (vynuceno od Androidu 15): odsadíme obsah o systémové lišty,
        // aby horní panel ani spodní navigace nepřekrývaly seznam.
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(this::loadStats);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        adapter.setOnPeriodClickListener(this::openDetail);
        adapter.setOnEnableDataClick(() ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        adapter.setMobileDataLoader(this::loadMobileDataFor);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        periodChips = findViewById(R.id.periodChips);
        chipSwipe = ChipGestures.detector(recyclerView, periodChips);
        selectPeriodChip(Period.valueOf(prefs.getString(KEY_PERIOD, Period.MONTH.name())));
        periodChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            prefs.edit().putString(KEY_PERIOD, currentPeriod().name()).apply();
            if (hasRequiredPermissions()) {
                loadStats();
            }
        });

        if (hasRequiredPermissions()) {
            loadStats();
            requestContactsIfNeeded();
            promptUsageAccessIfNeeded();
        } else {
            ActivityCompat.requestPermissions(this, REQUESTED_PERMISSIONS, REQ_PERMISSIONS);
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
    protected void onResume() {
        super.onResume();
        // Po návratu z Nastavení (Usage access) přenačteme, pokud se stav změnil.
        if (hasRequiredPermissions()
                && StatsRepository.hasUsageAccess(this) != usageAccessAtLastLoad) {
            loadStats();
        }
    }

    /** Jednorázově nabídne zapnutí Usage access kvůli mobilním datům (nepovinné). */
    private void promptUsageAccessIfNeeded() {
        if (StatsRepository.hasUsageAccess(this) || prefs.getBoolean(KEY_ASKED_USAGE, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_ASKED_USAGE, true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.usage_access_title)
                .setMessage(R.string.usage_access_message)
                .setPositiveButton(R.string.usage_access_enable, (d, w) ->
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
                .setNegativeButton(R.string.action_later, null)
                .show();
    }

    /** Nepovinné: požádá o kontakty kvůli jménům u SMS, pokud ještě nejsou povolené. */
    private void requestContactsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
        }
    }

    private boolean hasRequiredPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasRequiredPermissions()) {
                loadStats();
                promptUsageAccessIfNeeded();
            } else {
                showMessage(getString(R.string.permissions_required));
            }
        }
    }

    private void loadStats() {
        Period period = currentPeriod();
        usageAccessAtLastLoad = StatsRepository.hasUsageAccess(this);
        adapter.setUsageAccessGranted(usageAccessAtLastLoad);
        // Při pull-to-refresh ukazuje spinner SwipeRefreshLayout, nedublujeme středový.
        if (!swipeRefresh.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }
        StatsRepository repository = new StatsRepository(this);
        executor.execute(() -> {
            final List<PeriodStat> stats = repository.loadStats(period);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                if (stats.isEmpty()) {
                    showMessage(getString(R.string.no_data));
                } else {
                    emptyView.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                    adapter.setItems(stats);
                }
            });
        });
    }

    /** Líné dotažení mobilních dat pro jedno (zobrazené) období na pozadí. */
    private void loadMobileDataFor(PeriodStat stat) {
        StatsRepository repository = new StatsRepository(this);
        dataExecutor.execute(() -> {
            long bytes = repository.queryMobileData(stat);
            runOnUiThread(() -> adapter.updateMobileData(stat, bytes));
        });
    }

    private void openDetail(PeriodStat stat) {
        ZoneId zone = ZoneId.systemDefault();
        long start = stat.start.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = stat.period.next(stat.start).atStartOfDay(zone).toInstant().toEpochMilli();
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra(DetailActivity.EXTRA_START, start);
        intent.putExtra(DetailActivity.EXTRA_END, end);
        intent.putExtra(DetailActivity.EXTRA_PERIOD, stat.period.name());
        startActivity(intent);
    }

    /** Aktuálně zvolené období podle zaškrtnutého chipu (výchozí měsíc). */
    private Period currentPeriod() {
        int checkedId = periodChips.getCheckedChipId();
        if (checkedId == View.NO_ID) {
            return Period.MONTH;
        }
        Chip chip = periodChips.findViewById(checkedId);
        return Period.valueOf(chip.getTag().toString());
    }

    private void selectPeriodChip(Period period) {
        for (int i = 0; i < periodChips.getChildCount(); i++) {
            View child = periodChips.getChildAt(i);
            if (child instanceof Chip && period.name().equals(child.getTag())) {
                ((Chip) child).setChecked(true);
                return;
            }
        }
    }

    private void showMessage(String message) {
        recyclerView.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(message);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export) {
            onExportClicked();
            return true;
        }
        if (id == R.id.action_info) {
            showInfoDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showInfoDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_info, null);
        ((TextView) view.findViewById(R.id.infoName)).setText(R.string.app_name);

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // versionName zůstane prázdný – nemělo by nastat.
        }
        ((TextView) view.findViewById(R.id.infoVersion))
                .setText(getString(R.string.info_version, version));

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void onExportClicked() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.export_choose_format)
                .setItems(new CharSequence[]{"CSV", "JSON"}, (dialog, which) ->
                        exportData(which == 0))
                .show();
    }

    private void exportData(boolean csv) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setView(getLayoutInflater().inflate(R.layout.dialog_progress, null))
                .setCancelable(false)
                .create();
        progress.show();

        Period period = currentPeriod();
        StatsRepository repository = new StatsRepository(this);
        executor.execute(() -> {
            try {
                List<PeriodStat> periods = repository.loadStats(period);
                if (StatsRepository.hasUsageAccess(this)) {
                    repository.fillMobileData(periods);
                }
                List<DetailEntry> entries = repository.loadAllEntries();
                File file = csv
                        ? Exporter.writeCsv(this, periods, entries, period)
                        : Exporter.writeJson(this, periods, entries, period);
                Uri uri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
                String mime = csv ? "text/csv" : "application/json";
                runOnUiThread(() -> {
                    dismissDialog(progress);
                    shareFile(uri, mime, file.getName());
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    dismissDialog(progress);
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void dismissDialog(AlertDialog dialog) {
        if (dialog.isShowing() && !isFinishing()) {
            dialog.dismiss();
        }
    }

    private void shareFile(Uri uri, String mimeType, String fileName) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mimeType);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_SUBJECT, fileName);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, getString(R.string.export_share_title)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        dataExecutor.shutdownNow();
    }
}
