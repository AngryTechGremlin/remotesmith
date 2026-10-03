package io.github.angrytechgremlin.remotesmith;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.SortedMap;

/**
 * Drives the remote from adb in debuggable builds, with the answers in logcat (tag Remotesmith):
 * <pre>
 *   adb shell am start -n io.github.angrytechgremlin.remotesmith/.MainActivity --es cmd CMD \
 *       [--es profile JSON] [--ei seconds N]
 *   CMD: profile | clear | dump | suppress | unsuppress
 *        listen  (report key presses for N seconds, default 30)
 *        beep    (N seconds, 0 to 30, 0 stops; --ei wait M waits up to M minutes for a sleeping remote)
 *        problem (--es failure NAME: shows that problem page without the failure, NAME as in RemoteLink.Failure)
 * </pre>
 * The profile format is the one {@link Profiles} reads.
 */
final class DebugCommands {
    private DebugCommands() {}

    static void run(MainActivity app, RemoteLink link, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        int seconds = intent.getIntExtra("seconds", 30);
        switch (cmd) {
            case "profile":
                SortedMap<Integer, byte[]> table;
                try {
                    table = Profiles.parse(intent.getStringExtra("profile"));
                } catch (RuntimeException e) {
                    Log.i(MainActivity.TAG, "profile error: " + e.getMessage());
                    return;
                }
                Log.i(MainActivity.TAG, "programming " + table.keySet());
                app.job(() -> link.upload(table), failure -> { });
                break;
            case "clear":
                app.job(link::clear, failure -> { });
                break;
            case "dump":
                app.job(link::listServices, failure -> { });
                break;
            case "suppress":
                app.job(() -> link.suppress(24, 25, 164), failure -> { });
                break;
            case "unsuppress":
                app.job(() -> link.suppress(), failure -> { });
                break;
            case "listen":
                app.job(() -> link.watchKeys(true), failure -> { });
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> app.job(() -> link.watchKeys(false), failure -> { }), seconds * 1000L);
                break;
            case "beep":
                int tenths = Math.max(0, Math.min(30, seconds)) * 10;
                long patience = intent.getIntExtra("wait", 0) * 60_000L;
                app.job(() -> link.beep(tenths, patience), failure -> { });
                break;
            case "problem":
                try {
                    app.showProblem(RemoteLink.Failure.valueOf(intent.getStringExtra("failure")));
                } catch (RuntimeException e) {
                    Log.i(MainActivity.TAG, "problem: no such failure");
                }
                break;
            default:
                Log.i(MainActivity.TAG, "unknown command: " + cmd);
                break;
        }
    }
}
