package com.example.fiberdemo;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat logTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final FiberRuntime.NativeEventListener nativeEventListener =
            eventJson -> runOnUiThread(() -> appendLog("event: " + eventJson));

    private LinearLayout contentView;
    private TextView logView;
    private ScrollView logScrollView;
    private Button startStopButton;
    private Button nodeInfoButton;
    private Button peersButton;
    private Button channelsButton;
    private TextView addressView;
    private TextView pubkeyView;
    private Page currentPage = Page.HOME;

    private enum Page {
        HOME,
        PEERS
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FiberRuntime.addNativeEventListener(nativeEventListener);

        int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(
                    padding + safeInsets.left,
                    padding + safeInsets.top,
                    padding + safeInsets.right,
                    padding + safeInsets.bottom
            );
            return insets;
        });

        contentView = new LinearLayout(this);
        contentView.setOrientation(LinearLayout.VERTICAL);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);

        logScrollView = new ScrollView(this);
        logScrollView.setFillViewport(true);
        logScrollView.setBackgroundColor(0xfff2f2f2);
        logScrollView.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(contentView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                3f
        ));
        root.addView(logScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                2f
        ));

        setContentView(root);
        showHome();
    }

    @Override
    protected void onDestroy() {
        FiberRuntime.removeNativeEventListener(nativeEventListener);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentPage == Page.PEERS) {
            showHome();
            return;
        }
        super.onBackPressed();
    }

    private void showHome() {
        currentPage = Page.HOME;
        contentView.removeAllViews();

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        startStopButton = new Button(this);
        startStopButton.setAllCaps(false);
        startStopButton.setOnClickListener(view -> toggleNode());

        nodeInfoButton = new Button(this);
        nodeInfoButton.setText("NodeInfo");
        nodeInfoButton.setAllCaps(false);
        nodeInfoButton.setOnClickListener(view -> refreshNodeInfo());

        buttonRow.addView(startStopButton, weightedWrapParams(1f));
        buttonRow.addView(nodeInfoButton, weightedWrapParams(1f));
        contentView.addView(buttonRow, matchWrapParams());

        addressView = labelView("Address: ");
        pubkeyView = labelView("Pubkey: ");
        contentView.addView(addressView, matchWrapParams());
        contentView.addView(pubkeyView, matchWrapParams());

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);

        peersButton = new Button(this);
        peersButton.setText("Peers");
        peersButton.setAllCaps(false);
        peersButton.setOnClickListener(view -> showPeers());

        channelsButton = new Button(this);
        channelsButton.setText("Channels");
        channelsButton.setAllCaps(false);
        channelsButton.setEnabled(false);

        navRow.addView(peersButton, weightedWrapParams(1f));
        navRow.addView(channelsButton, weightedWrapParams(1f));
        contentView.addView(navRow, matchWrapParams());

        View spacer = new View(this);
        contentView.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        updateHomeButtons();
        if (FiberRuntime.isRunning()) {
            refreshNodeInfo();
        }
    }

    private void showPeers() {
        currentPage = Page.PEERS;
        contentView.removeAllViews();

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        Button backButton = new Button(this);
        backButton.setText("<");
        backButton.setAllCaps(false);
        backButton.setOnClickListener(view -> showHome());

        Button refreshButton = new Button(this);
        refreshButton.setText("ListPeer");
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(view -> refreshPeers());

        Button connectButton = new Button(this);
        connectButton.setText("Connect Peer");
        connectButton.setAllCaps(false);
        connectButton.setOnClickListener(view -> showConnectPeerDialog());

        topRow.addView(backButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f));
        topRow.addView(refreshButton, weightedWrapParams(1f));
        topRow.addView(connectButton, weightedWrapParams(1f));
        contentView.addView(topRow, matchWrapParams());

        TextView placeholder = labelView("No peers loaded");
        placeholder.setGravity(Gravity.CENTER);
        contentView.addView(placeholder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        refreshPeers();
    }

    private void toggleNode() {
        setHomeBusy(true);
        if (FiberRuntime.isRunning()) {
            runFiberCall("stop", FiberRuntime::stop, result -> {
                appendLog(result);
                updateHomeButtons();
            });
        } else {
            runFiberCall("start", () -> FiberRuntime.start(this), result -> {
                appendLog(result);
                updateHomeButtons();
                if (FiberRuntime.isRunning()) {
                    refreshNodeInfo();
                }
            });
        }
    }

    private void refreshNodeInfo() {
        if (!FiberRuntime.isRunning()) {
            appendLog("NodeInfo skipped: node is not running");
            return;
        }

        nodeInfoButton.setEnabled(false);
        executor.execute(() -> {
            FiberRuntime.NativeResult result = FiberRuntime.nodeInfo();
            mainHandler.post(() -> {
                nodeInfoButton.setEnabled(true);
                if (!result.success) {
                    appendLog(result.error);
                    return;
                }
                appendLog("NodeInfo refreshed");
                applyNodeInfo(result.value);
            });
        });
    }

    private void refreshPeers() {
        executor.execute(() -> {
            FiberRuntime.NativeResult result = FiberRuntime.listPeers();
            mainHandler.post(() -> {
                if (!result.success) {
                    appendLog(result.error);
                    setPeerListError(result.error);
                    return;
                }
                appendLog("ListPeer refreshed");
                applyPeerList(result.value);
            });
        });
    }

    private void showConnectPeerDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
        form.setPadding(padding, 0, padding, 0);

        EditText addressInput = new EditText(this);
        addressInput.setHint("Address");
        form.addView(addressInput, matchWrapParams());

        EditText pubkeyInput = new EditText(this);
        pubkeyInput.setHint("Pubkey");
        form.addView(pubkeyInput, matchWrapParams());

        EditText addrTypeInput = new EditText(this);
        addrTypeInput.setHint("Addr Type: tcp, ws, or wss");
        form.addView(addrTypeInput, matchWrapParams());

        CheckBox saveInput = new CheckBox(this);
        saveInput.setText("Save peer address");
        form.addView(saveInput, matchWrapParams());

        new AlertDialog.Builder(this)
                .setTitle("Connect Peer")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> connectPeer(
                        addressInput.getText().toString(),
                        pubkeyInput.getText().toString(),
                        addrTypeInput.getText().toString(),
                        saveInput.isChecked()
                ))
                .show();
    }

    private void connectPeer(String address, String pubkey, String addrType, boolean save) {
        executor.execute(() -> {
            FiberRuntime.NativeResult result = FiberRuntime.connectPeer(address, pubkey, addrType, save);
            mainHandler.post(() -> {
                if (!result.success) {
                    appendLog(result.error);
                    return;
                }
                appendLog(result.value);
                refreshPeers();
            });
        });
    }

    private void applyNodeInfo(String json) {
        try {
            JSONObject object = new JSONObject(json);
            JSONArray addresses = object.optJSONArray("addresses");
            String address = "";
            if (addresses != null && addresses.length() > 0) {
                address = addresses.optString(0, "");
            }
            addressView.setText("Address: " + address);
            pubkeyView.setText("Pubkey: " + object.optString("pubkey", ""));
        } catch (JSONException exception) {
            appendLog("NodeInfo parse failed: " + exception.getMessage());
        }
    }

    private void applyPeerList(String json) {
        if (currentPage != Page.PEERS) {
            return;
        }

        removePeerContent();

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        try {
            JSONArray peers = new JSONObject(json).optJSONArray("peers");
            if (peers == null || peers.length() == 0) {
                TextView empty = labelView("No peers");
                empty.setGravity(Gravity.CENTER);
                list.addView(empty, matchWrapParams());
            } else {
                for (int i = 0; i < peers.length(); i++) {
                    JSONObject peer = peers.getJSONObject(i);
                    TextView row = labelView("Pubkey: " + peer.optString("pubkey", "")
                            + "\nAddress: " + peer.optString("address", ""));
                    row.setPadding(0, 12, 0, 12);
                    list.addView(row, matchWrapParams());
                }
            }
        } catch (JSONException exception) {
            TextView error = labelView("Peer list parse failed: " + exception.getMessage());
            list.addView(error, matchWrapParams());
            appendLog(error.getText().toString());
        }

        contentView.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
    }

    private void setPeerListError(String errorMessage) {
        if (currentPage != Page.PEERS) {
            return;
        }
        removePeerContent();
        TextView error = labelView(errorMessage);
        error.setGravity(Gravity.CENTER);
        contentView.addView(error, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
    }

    private void runFiberCall(String action, FiberCall call, FiberResultConsumer consumer) {
        appendLog(action + " requested");
        executor.execute(() -> {
            String result = call.run();
            mainHandler.post(() -> {
                consumer.accept(result);
                setHomeBusy(false);
            });
        });
    }

    private void removePeerContent() {
        int childCount = contentView.getChildCount();
        if (childCount > 1) {
            contentView.removeViews(1, childCount - 1);
        }
    }

    private void updateHomeButtons() {
        boolean running = FiberRuntime.isRunning();
        if (startStopButton != null) {
            startStopButton.setText(running ? R.string.fiber_stop : R.string.fiber_start);
            startStopButton.setEnabled(true);
        }
        if (nodeInfoButton != null) {
            nodeInfoButton.setEnabled(running);
        }
        if (peersButton != null) {
            peersButton.setEnabled(running);
        }
        if (channelsButton != null) {
            channelsButton.setEnabled(false);
        }
    }

    private void setHomeBusy(boolean busy) {
        if (startStopButton != null) {
            startStopButton.setEnabled(!busy);
        }
        if (nodeInfoButton != null) {
            nodeInfoButton.setEnabled(!busy && FiberRuntime.isRunning());
        }
        if (peersButton != null) {
            peersButton.setEnabled(!busy && FiberRuntime.isRunning());
        }
    }

    private void appendLog(String message) {
        if (TextUtils.isEmpty(message)) {
            return;
        }
        String line = logTimeFormat.format(new Date()) + "  " + message + "\n";
        logView.append(line);
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private TextView labelView(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(14);
        textView.setTextIsSelectable(true);
        textView.setPadding(0, 8, 0, 8);
        return textView;
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedWrapParams(float weight) {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight
        );
    }

    private interface FiberCall {
        String run();
    }

    private interface FiberResultConsumer {
        void accept(String result);
    }
}
