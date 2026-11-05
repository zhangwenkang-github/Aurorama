package dev.aurorama.kokoro.harness;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public final class HarnessActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 40;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final KokoroRuntimeService.RuntimeListener runtimeListener =
            this::renderState;

    private TextView stateView;
    private TextView consistencyView;
    private KokoroRuntimeService.LocalBinder runtimeBinder;
    private boolean bound;
    private boolean binding;

    private final ServiceConnection connection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    runtimeBinder = (KokoroRuntimeService.LocalBinder) service;
                    bound = true;
                    binding = false;
                    runtimeBinder.addListener(runtimeListener);
                    renderState(runtimeBinder.getStateJson());
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    runtimeBinder = null;
                    bound = false;
                    binding = false;
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermissionIfNeeded();
        setContentView(buildContent());
        ensureServiceStarted(commandFromIntent(getIntent()));
        bindRuntime();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindRuntime();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatchIntentCommand(intent);
    }

    @Override
    protected void onStop() {
        if (bound) {
            runtimeBinder.removeListener(runtimeListener);
            unbindService(connection);
            bound = false;
            binding = false;
            runtimeBinder = null;
        }
        super.onStop();
    }

    private View buildContent() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(24, 25, 28));

        TextView title = new TextView(this);
        title.setText("Kokoro service-owned audio runtime");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "隔离 harness：前台 service、单 MediaSession、AudioFocus、真实 PCM 与持久暂停恢复。"
                        + "该页面不构成生产 TTS / D203 全闭环。");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(210, 210, 210));
        subtitle.setPadding(0, dp(8), 0, dp(12));
        root.addView(subtitle);

        stateView = new TextView(this);
        stateView.setText("等待 runtime service…");
        stateView.setTextSize(15);
        stateView.setTextColor(Color.rgb(255, 224, 178));
        stateView.setPadding(0, dp(8), 0, dp(8));
        stateView.setTextIsSelectable(true);
        root.addView(stateView);

        consistencyView = new TextView(this);
        consistencyView.setText("UI / notification / MediaSession：等待状态快照");
        consistencyView.setTextSize(14);
        consistencyView.setTextColor(Color.rgb(170, 220, 190));
        consistencyView.setPadding(0, 0, 0, dp(12));
        consistencyView.setTextIsSelectable(true);
        root.addView(consistencyView);

        addButton(root, "Start short TTS sample", KokoroRuntimeService.COMMAND_PLAY_SAMPLE, false);
        addButton(root, "Start 60-second TTS queue", KokoroRuntimeService.COMMAND_PLAY_FULL, false);
        addButton(root, "Start 31-minute continuous TTS", KokoroRuntimeService.COMMAND_PLAY_LONG, false);
        addButton(root, "Pause", KokoroRuntimeService.COMMAND_PAUSE, false);
        addButton(root, "Resume", KokoroRuntimeService.COMMAND_RESUME, false);
        addButton(root, "Previous sentence", KokoroRuntimeService.COMMAND_PREVIOUS, false);
        addButton(root, "Next sentence", KokoroRuntimeService.COMMAND_NEXT, false);
        addButton(root, "Stop TTS", KokoroRuntimeService.COMMAND_STOP, false);
        addButton(root, "Toggle approved voice", KokoroRuntimeService.COMMAND_TOGGLE_VOICE, false);
        addButton(root, "Play local music simulation", KokoroRuntimeService.COMMAND_MUSIC_PLAY, false);
        addButton(root, "Pause local music", KokoroRuntimeService.COMMAND_MUSIC_PAUSE, false);
        addButton(root, "Restore paused state", KokoroRuntimeService.COMMAND_RESTORE, false);
        addButton(root, "Dump runtime state", KokoroRuntimeService.COMMAND_DUMP_STATE, false);
        addButton(root, "Cancel TTS / cleanup", KokoroRuntimeService.COMMAND_CANCEL, true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void addButton(
            LinearLayout root, String label, String command, boolean finishAfterCommand) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setContentDescription("runtime_command:" + command);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        button.setLayoutParams(params);
        button.setOnClickListener(
                view -> {
                    sendCommand(command);
                    if (finishAfterCommand) {
                        mainHandler.postDelayed(this::finishAndRemoveTask, 800L);
                    }
                });
        root.addView(button);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void ensureServiceStarted(String command) {
        Intent serviceIntent =
                new Intent(this, KokoroRuntimeService.class)
                        .setAction(KokoroRuntimeService.ACTION_COMMAND)
                        .putExtra(KokoroRuntimeService.EXTRA_COMMAND, command);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void bindRuntime() {
        if (bound || binding) {
            return;
        }
        Intent bindIntent = new Intent(this, KokoroRuntimeService.class);
        binding = true;
        bindService(bindIntent, connection, Context.BIND_AUTO_CREATE);
    }

    private void dispatchIntentCommand(Intent intent) {
        if (runtimeBinder == null) {
            return;
        }
        String command = intent == null ? null : intent.getStringExtra(KokoroRuntimeService.EXTRA_COMMAND);
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        runtimeBinder.sendCommand(command.trim());
        intent.removeExtra(KokoroRuntimeService.EXTRA_COMMAND);
    }

    private String commandFromIntent(Intent intent) {
        String command =
                intent == null
                        ? null
                        : intent.getStringExtra(KokoroRuntimeService.EXTRA_COMMAND);
        return command == null || command.trim().isEmpty()
                ? KokoroRuntimeService.COMMAND_RESTORE
                : command.trim();
    }

    private void sendCommand(String command) {
        if (runtimeBinder == null) {
            ensureServiceStarted(command);
            return;
        }
        runtimeBinder.sendCommand(command);
    }

    private void renderState(String stateJson) {
        if (stateJson == null || stateJson.isEmpty()) {
            return;
        }
        try {
            JSONObject state = new JSONObject(stateJson);
            String status = state.optString("status", "UNKNOWN");
            String owner = state.optString("activeAudioOwner", "UNKNOWN");
            int index = state.optInt("queueIndex", -1);
            int size = state.optInt("queueSize", 0);
            stateView.setText(
                    "Status: "
                            + status
                            + "\nActive audio owner: "
                            + owner
                            + "\nSentence: "
                            + (index < 0 ? "none" : (index + 1) + "/" + size)
                            + "\nSpeaker: "
                            + state.optString("speaker")
                            + " sid="
                            + state.optInt("sid")
                            + "\nPack: "
                            + state.optString("packId")
                            + "\nAudioTrack playing: "
                            + state.optBoolean("audioTrackPlaying")
                            + "\nAudioFocus held: "
                            + state.optBoolean("focusHeld")
                            + "\nLong-run audio: "
                            + state.optLong("longRunAudioMillis")
                            + " / "
                            + state.optLong("longRunTargetMillis")
                            + " ms"
                            + "\nMusic: "
                            + state.optString("musicStatus")
                            + " index="
                            + state.optInt("musicIndex")
                            + "\nAutoPlay: false"
                            + "\nStable sentence: "
                            + state.optString("stableSentence"));
            consistencyView.setText(
                    "UI / notification / MediaSession 共用同一快照"
                            + "\nNotification: "
                            + state.optString("notificationTitle")
                            + " · "
                            + state.optString("notificationText")
                            + "\nMediaSession: "
                            + state.optString("mediaSessionState")
                            + "\nLast command: "
                            + state.optString("lastCommand")
                            + "\nLast error: "
                            + state.optString("lastError"));
        } catch (Exception error) {
            stateView.setText("State parse error: " + error);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
