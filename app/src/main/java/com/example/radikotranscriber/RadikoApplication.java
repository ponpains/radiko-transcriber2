package com.example.radikotranscriber;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-level housekeeping that requires no user interaction. */
public class RadikoApplication extends Application {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private BroadcastReceiver receiver;

    @Override public void onCreate() {
        super.onCreate();
        // Synchronous on purpose: unsafe legacy rules must be gone before a recognizer can start.
        int removed = CorrectionSanitizer.sanitize(this);
        new DiagnosticStore(this).log(-1L, "correction_sanitizer",
                "removed=" + removed + ";singlePass=true;guard=v17;profile="
                        + KerekereContextProfile.PROFILE_VERSION);

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!TranscribeService.ACTION_UPDATE.equals(intent.getAction())) return;
                if (intent.getBooleanExtra("postContextCorrected", false)) return;
                boolean running = intent.getBooleanExtra("running", false);
                long episodeId = intent.getLongExtra("episodeId", -1L);
                if (running || episodeId <= 0) return;

                worker.execute(() -> {
                    EpisodeStore store = new EpisodeStore(RadikoApplication.this);
                    DiagnosticStore diagnostics = new DiagnosticStore(RadikoApplication.this);
                    try {
                        ContextCorrectionEngine.Result r = ContextCorrectionEngine.refineEpisode(
                                RadikoApplication.this, store, episodeId);
                        diagnostics.log(episodeId, "context_postprocess",
                                "profile=" + KerekereContextProfile.PROFILE_VERSION
                                        + ";changed=" + r.changed() + ";segments=" + r.segmentChanges
                                        + ";changedChars=" + r.changedChars);
                        if (r.changed()) {
                            Intent update = new Intent(TranscribeService.ACTION_UPDATE);
                            update.setPackage(getPackageName());
                            update.putExtra("postContextCorrected", true);
                            update.putExtra("episodeId", episodeId);
                            update.putExtra("running", false);
                            update.putExtra("status", "けれけれ文脈補正を適用して保存しました。");
                            update.putExtra("text", r.finalText);
                            update.putExtra("mode", "internal");
                            update.putExtra("peak", 0);
                            update.putExtra("reconnects", 0);
                            sendBroadcast(update);
                        }
                    } catch (Exception e) {
                        diagnostics.log(episodeId, "context_postprocess_error",
                                e.getClass().getSimpleName() + ":" + e.getMessage());
                    } finally {
                        try { store.close(); } catch (Exception ignored) {}
                    }
                });
            }
        };
        IntentFilter f = new IntentFilter(TranscribeService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    @Override public void onTerminate() {
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onTerminate();
    }
}
