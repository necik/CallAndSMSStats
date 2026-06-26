package cz.jirnec.callandsmsstats;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    private final MonthStatsAdapter adapter = new MonthStatsAdapter();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView recyclerView;
    private TextView emptyView;

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
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        adapter.setOnMonthClickListener(stat -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_YEAR, stat.month.getYear());
            intent.putExtra(DetailActivity.EXTRA_MONTH, stat.month.getMonthValue());
            startActivity(intent);
        });

        if (hasRequiredPermissions()) {
            loadStats();
            requestContactsIfNeeded();
        } else {
            ActivityCompat.requestPermissions(this, REQUESTED_PERMISSIONS, REQ_PERMISSIONS);
        }
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
            } else {
                showMessage(getString(R.string.permissions_required));
            }
        }
    }

    private void loadStats() {
        StatsRepository repository = new StatsRepository(this);
        executor.execute(() -> {
            final List<MonthStat> stats = repository.loadStats();
            runOnUiThread(() -> {
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

    private void showMessage(String message) {
        recyclerView.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
