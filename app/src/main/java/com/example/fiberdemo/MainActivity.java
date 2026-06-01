package com.example.fiberdemo;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
        root.setPadding(padding, padding, padding, padding);

        statusView = new TextView(this);
        statusView.setText(R.string.fiber_status_stopped);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextSize(18);

        Button startButton = new Button(this);
        startButton.setText(R.string.fiber_start);
        startButton.setOnClickListener(view -> updateStatus(FiberRuntime.start(this)));

        Button stopButton = new Button(this);
        stopButton.setText(R.string.fiber_stop);
        stopButton.setOnClickListener(view -> updateStatus(FiberRuntime.stop()));

        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        root.addView(startButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        root.addView(stopButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        setContentView(root);
    }

    private void updateStatus(String status) {
        statusView.setText(status);
    }
}