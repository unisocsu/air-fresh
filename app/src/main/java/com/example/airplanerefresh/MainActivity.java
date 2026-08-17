package com.example.airplanerefresh;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private static final long AUTO_REFRESH_MS = 5 * 60 * 1000L;

    private TextView status;
    private CheckBox autoRefresh;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable autoTask = new Runnable() {
        @Override
        public void run() {
            if (autoRefresh != null && autoRefresh.isChecked()) {
                refreshNetwork();
                handler.postDelayed(this, AUTO_REFRESH_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        Button refresh = findViewById(R.id.refresh_button);
        autoRefresh = findViewById(R.id.auto_refresh);

        refresh.setOnClickListener(v -> refreshNetwork());

        autoRefresh.setOnCheckedChangeListener((buttonView, checked) -> {
            handler.removeCallbacks(autoTask);

            if (checked) {
                status.setText(getString(R.string.status_auto_on));
                handler.post(autoTask);
            } else {
                status.setText(getString(R.string.status_auto_off));
            }
        });
    }

    private void refreshNetwork() {
        status.setText(getString(R.string.status_refreshing));

        new Thread(() -> {
            boolean root = hasRoot();
            boolean success = root && runRootRefresh();

            runOnUiThread(() -> {
                if (success) {
                    status.setText(getString(R.string.status_success));
                } else if (!root) {
                    status.setText(getString(R.string.status_no_root));
                } else {
                    status.setText(getString(R.string.status_failed));
                }
            });
        }).start();
    }

    /**
     * Checks for root access by attempting to run "id" via su.
     * Returns true only if su succeeds and the output confirms uid=0.
     */
    private boolean hasRoot() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line = reader.readLine();
                int exitCode = process.waitFor();

                return exitCode == 0 && line != null && line.contains("uid=0");
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Toggles airplane mode off/on via root shell commands to force a
     * network refresh. Requires root; silently fails (returns false)
     * on any error.
     */
    private boolean runRootRefresh() {
        String command =
                "settings put global airplane_mode_on 1; "
                        + "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true; "
                        + "sleep 3; "
                        + "settings put global airplane_mode_on 0; "
                        + "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false";

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isNetworkAvailable() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (manager == null) {
            return false;
        }

        Network network = manager.getActiveNetwork();
        return network != null;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
