package com.example.airplanerefresh;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "settings";

    private static final String PREF_CHECK_PHONE =
            "check_phone_network";

    private static final String PREF_CHECK_INTERNET =
            "check_internet";

    /*
     * מרווח הבדיקה האוטומטית.
     *
     * 30 שניות.
     *
     * אפשר לשנות ל:
     *
     * 60000L  = דקה
     * 120000L = שתי דקות
     * 300000L = חמש דקות
     */
    private static final long AUTO_REFRESH_INTERVAL = 30000L;

    private CheckBox checkPhoneNetwork;
    private CheckBox checkInternet;

    private Button refreshButton;
    private TextView statusText;

    private SharedPreferences preferences;

    private Handler handler;

    private boolean autoRefresh = true;

    /*
     * מצב השירות הסלולרי האחרון שהתקבל
     * מ-PhoneStateListener.
     */
    private volatile boolean phoneServiceAvailable = false;

    /*
     * מצב השיחה.
     *
     * אם יש שיחה פעילה אנחנו מתייחסים
     * אליה כאל רשת טלפונית פעילה.
     */
    private volatile boolean callActive = false;

    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;

    private final Runnable autoRefreshRunnable =
            new Runnable() {
                @Override
                public void run() {

                    if (autoRefresh) {
                        refreshNetwork();
                    }

                    handler.postDelayed(
                            this,
                            AUTO_REFRESH_INTERVAL
                    );
                }
            };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
        );

        handler = new Handler();

        initViews();

        loadSettings();

        registerPhoneListener();

        updateStatus();

        startAutoRefresh();
    }


    private void initViews() {

        checkPhoneNetwork =
                (CheckBox) findViewById(
                        R.id.check_phone_network
                );

        checkInternet =
                (CheckBox) findViewById(
                        R.id.check_internet
                );

        refreshButton =
                (Button) findViewById(
                        R.id.refresh_button
                );

        statusText =
                (TextView) findViewById(
                        R.id.status
                );


        checkPhoneNetwork.setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {

                        saveSettings();

                        updateStatus();
                    }
                }
        );


        checkInternet.setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {

                        saveSettings();

                        updateStatus();
                    }
                }
        );


        refreshButton.setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {

                        refreshNetwork();
                    }
                }
        );
    }


    private void loadSettings() {

        /*
         * ברירת המחדל:
         *
         * בדיקת רשת טלפונית = פעילה
         * בדיקת Internet = כבויה
         *
         * זה מתאים למכשיר כשר שמשמש בעיקר לטלפון.
         */

        boolean checkPhone =
                preferences.getBoolean(
                        PREF_CHECK_PHONE,
                        true
                );

        boolean checkInternetValue =
                preferences.getBoolean(
                        PREF_CHECK_INTERNET,
                        false
                );

        checkPhoneNetwork.setChecked(
                checkPhone
        );

        checkInternet.setChecked(
                checkInternetValue
        );
    }


    private void saveSettings() {

        preferences.edit()

                .putBoolean(
                        PREF_CHECK_PHONE,
                        checkPhoneNetwork.isChecked()
                )

                .putBoolean(
                        PREF_CHECK_INTERNET,
                        checkInternet.isChecked()
                )

                .apply();
    }


    /*
     * הפונקציה החשובה ביותר.
     *
     * לפני שמפעילים Airplane Mode,
     * בודקים את שתי האפשרויות שהמשתמש בחר.
     */
    private void refreshNetwork() {

        /*
         * בדיקת רשת טלפונית
         */
        if (checkPhoneNetwork.isChecked()) {

            if (isPhoneNetworkAvailable()) {

                setStatus(
                        "רשת טלפונית פעילה - לא מרענן"
                );

                return;
            }
        }


        /*
         * בדיקת Internet
         */
        if (checkInternet.isChecked()) {

            if (isInternetAvailable()) {

                setStatus(
                        "אינטרנט פעיל - לא מרענן"
                );

                return;
            }
        }


        /*
         * אם הגענו לכאן:
         *
         * אף אחת מהבדיקות הפעילות
         * לא מצאה רשת שצריך לשמור עליה.
         */
        runRootRefresh();
    }


    /*
     * בדיקת Internet.
     *
     * API 19:
     * משתמשים ב-NetworkInfo ולא ב-NetworkCapabilities.
     *
     * זה חשוב מאוד ל-Android 4.4.
     */
    private boolean isInternetAvailable() {

        try {

            ConnectivityManager cm =
                    (ConnectivityManager)
                            getSystemService(
                                    Context.CONNECTIVITY_SERVICE
                            );

            if (cm == null) {
                return false;
            }

            NetworkInfo info =
                    cm.getActiveNetworkInfo();

            if (info == null) {
                return false;
            }

            if (!info.isConnected()) {
                return false;
            }

            /*
             * יש חיבור רשת.
             *
             * במכשיר Android 4.4 אין
             * NET_CAPABILITY_VALIDATED.
             *
             * לכן אנחנו לא מנסים להשתמש
             * ב-API חדש יותר.
             */
            return true;

        } catch (Exception e) {

            return false;
        }
    }


    /*
     * בדיקת רשת טלפונית.
     *
     * המטרה היא לא לבדוק Internet.
     *
     * אנחנו רוצים לדעת אם יש שירות
     * סלולרי / אפשרות לשיחת טלפון.
     */
    private boolean isPhoneNetworkAvailable() {

        /*
         * אם קיימת כרגע שיחה:
         *
         * אסור לבצע רענון.
         */
        if (callActive) {
            return true;
        }


        /*
         * אם PhoneStateListener דיווח
         * שהשירות הסלולרי פעיל.
         */
        if (phoneServiceAvailable) {
            return true;
        }


        try {

            if (telephonyManager == null) {

                telephonyManager =
                        (TelephonyManager)
                                getSystemService(
                                        Context.TELEPHONY_SERVICE
                                );
            }

            if (telephonyManager == null) {
                return false;
            }


            /*
             * צריך SIM תקין.
             */
            int simState =
                    telephonyManager.getSimState();

            if (simState !=
                    TelephonyManager.SIM_STATE_READY) {

                return false;
            }


            /*
             * אם יש Network Operator,
             * זה סימן שיש רשת סלולרית מזוהה.
             */
            String operator =
                    telephonyManager.getNetworkOperator();

            if (operator != null &&
                    operator.length() > 0) {

                return true;
            }


        } catch (SecurityException e) {

            /*
             * אם Android לא מאפשר לקרוא
             * את מצב הטלפון:
             *
             * לא מניחים שיש רשת.
             */

        } catch (Exception e) {

            // לא לבצע פעולה מסוכנת במקרה של שגיאה.
        }

        return false;
    }


    /*
     * Listener ישן שמתאים ל-Android 4.4.
     */
    private void registerPhoneListener() {

        try {

            telephonyManager =
                    (TelephonyManager)
                            getSystemService(
                                    Context.TELEPHONY_SERVICE
                            );

            if (telephonyManager == null) {
                return;
            }


            phoneStateListener =
                    new PhoneStateListener() {

                        @Override
                        public void onCallStateChanged(
                                int state,
                                String incomingNumber) {

                            if (state ==
                                    TelephonyManager.CALL_STATE_OFFHOOK ||
                                state ==
                                    TelephonyManager.CALL_STATE_RINGING) {

                                callActive = true;

                            } else {

                                callActive = false;
                            }
                        }


                        @Override
                        public void onServiceStateChanged(
                                ServiceState serviceState) {

                            if (serviceState == null) {

                                phoneServiceAvailable =
                                        false;

                                return;
                            }

                            phoneServiceAvailable =
                                    serviceState.getState()
                                    ==
                                    ServiceState.STATE_IN_SERVICE;
                        }
                    };


            telephonyManager.listen(
                    phoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE |
                    PhoneStateListener.LISTEN_SERVICE_STATE
            );


        } catch (SecurityException e) {

            phoneServiceAvailable = false;

        } catch (Exception e) {

            phoneServiceAvailable = false;
        }
    }


    /*
     * כאן נשמר מנגנון הרענון המקורי.
     *
     * הוא משתמש ב-root.
     */
    private void runRootRefresh() {

        setStatus("מרענן רשת...");

        new Thread(
                new Runnable() {

                    @Override
                    public void run() {

                        try {

                            Process process =
                                    Runtime.getRuntime().exec(
                                            new String[]{
                                                    "su",
                                                    "-c",
                                                    "settings put global airplane_mode_on 1; " +
                                                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true; " +
                                                    "sleep 3; " +
                                                    "settings put global airplane_mode_on 0; " +
                                                    "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
                                            }
                                    );


                            BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream()
                                            )
                                    );

                            StringBuilder output =
                                    new StringBuilder();

                            String line;

                            while (
                                    (line = reader.readLine())
                                            != null
                            ) {

                                output.append(line);
                                output.append('\n');
                            }


                            int exitCode =
                                    process.waitFor();


                            if (exitCode == 0) {

                                setStatus(
                                        "הרענון הסתיים בהצלחה"
                                );

                            } else {

                                setStatus(
                                        "הרענון נכשל"
                                );
                            }


                        } catch (Exception e) {

                            setStatus(
                                    "אין הרשאת Root"
                            );
                        }
                    }

                }
        ).start();
    }


    private void startAutoRefresh() {

        stopAutoRefresh();

        autoRefresh = true;

        handler.postDelayed(
                autoRefreshRunnable,
                AUTO_REFRESH_INTERVAL
        );
    }


    private void stopAutoRefresh() {

        if (handler != null) {

            handler.removeCallbacks(
                    autoRefreshRunnable
            );
        }
    }


    private void updateStatus() {

        StringBuilder text =
                new StringBuilder();

        text.append("הגדרות פעילות:\n");

        text.append("רשת טלפונית: ");

        if (checkPhoneNetwork.isChecked()) {

            text.append("בדיקה פעילה");

        } else {

            text.append("בדיקה כבויה");
        }


        text.append("\n");


        text.append("Internet: ");

        if (checkInternet.isChecked()) {

            text.append("בדיקה פעילה");

        } else {

            text.append("בדיקה כבויה");
        }


        statusText.setText(
                text.toString()
        );
    }


    private void setStatus(
            final String text
    ) {

        runOnUiThread(
                new Runnable() {

                    @Override
                    public void run() {

                        if (statusText != null) {

                            statusText.setText(
                                    text
                            );
                        }
                    }
                }
        );
    }


    @Override
    protected void onDestroy() {

        stopAutoRefresh();


        try {

            if (telephonyManager != null &&
                    phoneStateListener != null) {

                telephonyManager.listen(
                        phoneStateListener,
                        PhoneStateListener.LISTEN_NONE
                );
            }

        } catch (Exception ignored) {
        }


        super.onDestroy();
    }
}