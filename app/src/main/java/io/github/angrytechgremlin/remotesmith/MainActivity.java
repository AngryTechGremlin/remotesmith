package io.github.angrytechgremlin.remotesmith;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The whole app: a home page, the setup wizard, find my remote and a few extras, shown one page
 * at a time. A page is a heading, some text, an optional status line and a column of choices.
 *
 * The wizard's decisions live in {@link Setup} and the Bluetooth work in {@link RemoteLink};
 * this class shows pages and passes answers between the two.
 */
public final class MainActivity extends Activity implements RemoteLink.Listener {
    static final String TAG = "Remotesmith";
    /** Starts Find my remote directly, for when the remote is the thing that is lost. Declared in the manifest. */
    private static final String ACTION_FIND = "io.github.angrytechgremlin.remotesmith.action.FIND_REMOTE";
    private static final String GOOGLE_SETUP = "com.google.android.tv.axel";
    private static final String STATE_SETUP = "setup";
    /** How long Find my remote waits for a sleeping remote to wake up. */
    private static final long FIND_PATIENCE_MS = 15 * 60_000;

    private static final class Choice {
        final CharSequence label;
        final Runnable action;

        Choice(CharSequence label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private ScrollView page;
    private TextView title, body, status;
    private LinearLayout choices;
    private LinearLayout brandPage;
    private ListView brandList;

    private CodeDb db;
    private Store store;
    private RemoteLink link;
    /** Continuations of the jobs given to the link, in the order they were given. */
    private final ArrayDeque<Consumer<RemoteLink.Failure>> jobs = new ArrayDeque<>();
    private Runnable afterPermission;
    private Runnable back;

    private Setup setup;                       // the wizard run in progress, if any
    private SortedMap<Integer, byte[]> onRemote;   // the table this session last put on the remote
    private boolean toldOwnDone;               // the "now trying other brands" page was shown
    private boolean watching;                  // the remote is reporting key presses
    private boolean asking;                    // a wizard question is on screen
    private boolean finding;                   // the Find my remote page is on screen
    private boolean beepPending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // A screensaver would stop this screen and freeze the app in the middle of a long wait.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildViews();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (back != null) back.run(); else finish();
        });
        store = new Store(this);
        link = new RemoteLink(this, this);
        try (InputStream in = getAssets().open("codes.txt")) {
            db = CodeDb.read(in);
        } catch (IOException e) {
            show(getString(R.string.app_name), e.getMessage(), this::finish);
            return;
        }
        Setup saved = state == null ? null : Setup.restore(db, state.getString(STATE_SETUP, ""));
        if (saved != null) {
            setup = saved;
            reach(this::send, this::home);
        } else {
            route(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (db != null) route(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (setup != null) state.putString(STATE_SETUP, setup.save());
    }

    @Override
    protected void onDestroy() {
        link.close();
        super.onDestroy();
    }

    private void route(Intent intent) {
        if (ACTION_FIND.equals(intent.getAction())) {
            find();
            return;
        }
        home();
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && intent.hasExtra("cmd")) DebugCommands.run(this, link, intent);
    }

    // --- pages ------------------------------------------------------------------------------

    private void buildViews() {
        int side = Ui.dp(this, 48), top = Ui.dp(this, 27);   // the 5% a TV may cut off

        title = Ui.text(this, 30, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body = Ui.text(this, 19, Ui.MUTED);
        body.setMaxWidth(Ui.dp(this, 760));
        status = Ui.text(this, 19, Ui.ACCENT);
        status.setMaxWidth(Ui.dp(this, 760));
        choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(side, top, side, top);
        column.addView(title);
        column.addView(body, margins(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        column.addView(status, margins(ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        column.addView(choices, margins(Ui.dp(this, 520), 14));
        page = new ScrollView(this);
        page.setFillViewport(true);
        page.addView(column);

        TextView brandTitle = Ui.text(this, 30, Ui.TEXT);
        brandTitle.setTypeface(Typeface.DEFAULT_BOLD);
        brandTitle.setText(R.string.brand_title);
        brandList = new ListView(this);
        brandList.setDivider(null);
        brandList.setSelector(new ColorDrawable(0x668AB4F8));
        brandList.setVerticalScrollBarEnabled(false);
        brandPage = new LinearLayout(this);
        brandPage.setOrientation(LinearLayout.VERTICAL);
        brandPage.setPadding(side, top, side, top);
        brandPage.addView(brandTitle);
        LinearLayout.LayoutParams listSize = new LinearLayout.LayoutParams(Ui.dp(this, 520), 0, 1f);
        listSize.topMargin = Ui.dp(this, 16);
        brandPage.addView(brandList, listSize);
        brandPage.setVisibility(View.GONE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.addView(page);
        root.addView(brandPage);
        setContentView(root);
    }

    private LinearLayout.LayoutParams margins(int width, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private Choice choice(int label, Runnable action) {
        return new Choice(getString(label), action);
    }

    /** Show a page. onBack is what the Back button does while it is up. */
    private void show(CharSequence heading, CharSequence text, Runnable onBack, Choice... options) {
        asking = false;
        finding = false;
        back = onBack;
        brandPage.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
        title.setText(heading);
        body.setText(text);
        status.setVisibility(View.GONE);
        choices.removeAllViews();
        for (Choice option : options) {
            choices.addView(Ui.choice(this, option.label, option.action), margins(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        }
        if (choices.getChildCount() > 0) choices.getChildAt(0).requestFocus();
        page.scrollTo(0, 0);
    }

    private void say(CharSequence line) {
        status.setText(line);
        status.setVisibility(View.VISIBLE);
    }

    private void showSending() {
        show(getString(R.string.sending_title), getString(R.string.sending_body), null);
        back = () -> { };   // a programming session should not be left half-way
    }

    private void home() {
        setup = null;
        link.cancelWaiting();
        StringBuilder text = new StringBuilder(getString(R.string.home_intro));
        if (store.exists()) {
            text.append("\n\n").append(getString(R.string.home_saved, brandLabel(store.brand())));
        }
        if (googleSetupPresent()) text.append("\n\n").append(getString(R.string.home_google));
        List<Choice> options = new ArrayList<>();
        options.add(choice(R.string.action_setup, this::intro));
        options.add(choice(R.string.action_find, this::find));
        if (store.exists()) options.add(choice(R.string.action_resend, () -> put(store.table(), this::home)));
        options.add(choice(R.string.action_more, this::more));
        show(getString(R.string.app_name), text, this::finish, options.toArray(new Choice[0]));
    }

    private String brandLabel(String brand) {
        return brand == null ? getString(R.string.brand_unlisted) : brand;
    }

    private boolean googleSetupPresent() {
        try {
            getPackageManager().getPackageInfo(GOOGLE_SETUP, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void more() {
        show(getString(R.string.more_title), getString(R.string.home_intro), this::home,
                choice(R.string.action_clear, this::confirmClear),
                choice(R.string.action_import, this::importPage),
                choice(R.string.action_about, this::about),
                choice(R.string.action_back, this::home));
    }

    private void about() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "";
        }
        show(getString(R.string.app_name), getString(R.string.about_body, version), this::more,
                choice(R.string.action_back, this::more));
    }

    private void confirmClear() {
        show(getString(R.string.clear_title), getString(R.string.clear_body), this::more,
                choice(R.string.action_cancel, this::more),
                choice(R.string.clear_confirm, () -> put(new TreeMap<>(), this::more)));
    }

    private void importPage() {
        File file = new File(getExternalFilesDir(null), "profile.json");
        show(getString(R.string.import_title), getString(R.string.import_body, file.getPath()), this::more,
                choice(R.string.import_send, () -> importFrom(file)),
                choice(R.string.action_back, this::more));
    }

    private void importFrom(File file) {
        SortedMap<Integer, byte[]> table;
        try {
            table = Profiles.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            show(getString(R.string.import_title), getString(R.string.import_problem, e.getMessage()), this::importPage,
                    choice(R.string.action_back, this::importPage));
            return;
        }
        store.save(getString(R.string.brand_own), table);
        put(table, this::more);
    }

    /** Put a whole table on the remote (an empty one clears it), then say so. */
    private void put(SortedMap<Integer, byte[]> table, Runnable cancel) {
        showSending();
        job(() -> {
            if (table.isEmpty()) link.clear(); else link.upload(table);
        }, failure -> {
            if (failure != null) {
                problem(failure, () -> put(table, cancel), cancel);
            } else if (table.isEmpty()) {
                show(getString(R.string.cleared_title), getString(R.string.cleared_body), this::home, choice(R.string.action_done, this::home));
            } else {
                show(getString(R.string.sent_title), getString(R.string.sent_body), this::home, choice(R.string.action_done, this::home));
            }
        });
    }

    private void problem(RemoteLink.Failure failure, Runnable retry, Runnable cancel) {
        show(getString(R.string.problem_title), problemText(failure), cancel,
                choice(R.string.action_retry, retry), choice(R.string.action_cancel, cancel));
    }

    /** For {@link DebugCommands}: shows a problem page so its text can be checked on a real screen. */
    void showProblem(RemoteLink.Failure failure) {
        problem(failure, this::home, this::home);
    }

    private String problemText(RemoteLink.Failure failure) {
        switch (failure) {
            case NO_PERMISSION: return getString(R.string.problem_permission);
            case BLUETOOTH_OFF: return getString(R.string.problem_bluetooth_off);
            case NOT_PAIRED: return getString(R.string.problem_not_paired);
            case UNSUPPORTED: return getString(R.string.problem_unsupported);
            case REFUSED: return getString(R.string.problem_refused);
            default: return getString(R.string.problem_unreachable);
        }
    }

    // --- setup wizard -----------------------------------------------------------------------

    private void intro() {
        show(getString(R.string.intro_title), getString(R.string.intro_body), this::home,
                choice(R.string.action_start, () -> reach(this::pickBrand, this::home)),
                choice(R.string.action_cancel, this::home));
    }

    /**
     * Make sure the remote answers before the wizard goes on, by turning on its key-press
     * reports. A remote without them can still be set up; the wizard just cannot show presses.
     */
    private void reach(Runnable then, Runnable cancel) {
        showSending();
        job(() -> link.watchKeys(true), failure -> {
            if (failure == null || failure == RemoteLink.Failure.UNSUPPORTED) {
                watching = failure == null;
                then.run();
            } else {
                problem(failure, () -> reach(then, cancel), cancel);
            }
        });
    }

    private void pickBrand() {
        asking = false;
        finding = false;
        back = this::leaveSetup;
        page.setVisibility(View.GONE);
        brandPage.setVisibility(View.VISIBLE);
        BrandAdapter adapter = new BrandAdapter();
        brandList.setAdapter(adapter);
        brandList.setOnItemClickListener((parent, view, position, id) -> begin(adapter.brandAt(position)));
        brandList.setSelection(0);
        brandList.requestFocus();
    }

    private void begin(String brand) {
        setup = new Setup(db, brand);
        toldOwnDone = false;
        send();
    }

    /** Put what the wizard wants on the remote, unless it is already there, then ask about it. */
    private void send() {
        SortedMap<Integer, byte[]> table = setup.step() == Setup.Step.DONE ? setup.confirmed() : setup.table();
        if (sameTable(table, onRemote)) {
            ask();
            return;
        }
        showSending();
        job(() -> link.upload(table), failure -> {
            if (setup == null) return;
            if (failure == null) {
                onRemote = table;
                ask();
            } else {
                problem(failure, this::send, this::confirmStop);
            }
        });
    }

    private static boolean sameTable(SortedMap<Integer, byte[]> a, SortedMap<Integer, byte[]> b) {
        if (b == null || !a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<Integer, byte[]> e : a.entrySet()) {
            if (!java.util.Arrays.equals(e.getValue(), b.get(e.getKey()))) return false;
        }
        return true;
    }

    private void ask() {
        Setup.Step step = setup.step();
        if (step == Setup.Step.DONE) {
            finishSetup();
            return;
        }
        int own = setup.ownVolumeAttempts();
        if (step == Setup.Step.VOLUME && own > 0 && setup.attempt() == own + 1 && !toldOwnDone) {
            toldOwnDone = true;
            show(getString(R.string.own_done_title, setup.brand()), getString(R.string.own_done_body), this::confirmStop,
                    choice(R.string.action_keep_going, this::ask),
                    choice(R.string.action_other_brand, this::pickBrand),
                    choice(R.string.answer_stop, this::confirmStop));
            return;
        }
        String name = stepName(step);
        // A count helps only while it is short: the brand's own volume codes.
        String heading = step == Setup.Step.VOLUME && setup.attempt() <= own
                ? getString(R.string.question_title, name, setup.attempt(), own)
                : getString(R.string.question_title_open, name, setup.attempt());
        List<Choice> options = new ArrayList<>();
        int text;
        switch (step) {
            case VOLUME:
                text = R.string.volume_body;
                options.add(choice(R.string.answer_volume_yes, this::works));
                options.add(choice(R.string.answer_next, this::next));
                if (setup.attempt() > 1) options.add(choice(R.string.answer_previous, this::previous));
                break;
            case POWER:
                text = R.string.power_body;
                options.add(choice(R.string.answer_power_yes, this::works));
                options.add(choice(R.string.answer_next, this::next));
                options.add(choice(R.string.answer_power_stuck, this::powerStuck));
                options.add(new Choice(getString(R.string.answer_skip, name), this::skip));
                break;
            default:
                text = step == Setup.Step.MUTE ? R.string.mute_body : R.string.input_body;
                options.add(choice(R.string.answer_yes, this::works));
                options.add(choice(R.string.answer_next, this::next));
                options.add(new Choice(getString(R.string.answer_skip, name), this::skip));
                break;
        }
        options.add(choice(R.string.answer_stop, this::confirmStop));
        show(heading, getString(text), this::confirmStop, options.toArray(new Choice[0]));
        asking = true;
    }

    private String stepName(Setup.Step step) {
        switch (step) {
            case VOLUME: return getString(R.string.step_volume);
            case MUTE: return getString(R.string.step_mute);
            case POWER: return getString(R.string.step_power);
            default: return getString(R.string.step_input);
        }
    }

    private void works() {
        setup.works();
        send();
    }

    private void previous() {
        setup.previous();
        send();
    }

    private void skip() {
        setup.skip();
        send();
    }

    private void next() {
        if (setup.next()) {
            send();
        } else if (setup.step() == Setup.Step.VOLUME) {
            show(getString(R.string.none_title), getString(R.string.none_body), this::leaveSetup,
                    choice(R.string.action_start_over, this::pickBrand),
                    choice(R.string.answer_stop, this::leaveSetup));
        } else {
            String name = stepName(setup.step());
            show(getString(R.string.button_none_title, name), getString(R.string.button_none_body), this::confirmStop,
                    choice(R.string.action_continue, this::skip));
        }
    }

    private void powerStuck() {
        show(getString(R.string.power_stuck_title), getString(R.string.power_stuck_body), this::ask,
                choice(R.string.action_same_again, this::ask),
                choice(R.string.answer_next, this::next),
                new Choice(getString(R.string.answer_skip, getString(R.string.step_power)), this::skip));
    }

    private void confirmStop() {
        boolean some = !setup.confirmed().isEmpty();
        show(getString(R.string.stop_title), getString(some ? R.string.stop_body_some : R.string.stop_body_none), this::send,
                choice(R.string.action_keep_setting_up, this::send),
                choice(R.string.action_stop_confirm, this::leaveSetup));
    }

    /**
     * Leave the wizard early. The remote must not keep untested guesses: it gets what was
     * confirmed, or failing that what it had before, or nothing.
     */
    private void leaveSetup() {
        SortedMap<Integer, byte[]> keep = setup == null ? new TreeMap<>() : setup.confirmed();
        if (!keep.isEmpty()) {
            store.save(setup.brand(), keep);
        } else {
            keep = store.table();
        }
        boolean touched = onRemote != null;
        setup = null;
        stopWatching();
        if (!touched) {
            home();
            return;
        }
        SortedMap<Integer, byte[]> table = keep;
        showSending();
        job(() -> {
            if (table.isEmpty()) link.clear(); else link.upload(table);
        }, failure -> {
            onRemote = null;
            if (failure == null) home(); else problem(failure, this::home, this::home);
        });
    }

    private void finishSetup() {
        Setup done = setup;
        setup = null;
        store.save(done.brand(), done.confirmed());
        stopWatching();
        List<String> with = new ArrayList<>(), without = new ArrayList<>();
        with.add(getString(R.string.step_volume));
        (done.has(CodeDb.MUTE) ? with : without).add(getString(R.string.step_mute));
        (done.has(CodeDb.POWER) ? with : without).add(getString(R.string.step_power));
        (done.has(CodeDb.INPUT) ? with : without).add(getString(R.string.step_input));
        String text = getString(R.string.done_body, String.join(", ", with));
        if (!without.isEmpty()) text += getString(R.string.done_body_without, String.join(", ", without));
        show(getString(R.string.done_title), text, this::home, choice(R.string.action_done, this::home));
    }

    private void stopWatching() {
        if (!watching) return;
        watching = false;
        job(() -> link.watchKeys(false), failure -> { });
    }

    // --- find my remote ---------------------------------------------------------------------

    private void find() {
        show(getString(R.string.find_title), getString(R.string.find_body), this::home,
                choice(R.string.action_beep_again, this::beep),
                choice(R.string.action_beep_stop, this::stopBeep),
                choice(R.string.action_back, this::home));
        finding = true;
        if (beepPending) say(getString(R.string.find_looking)); else beep();
    }

    private void beep() {
        if (beepPending) return;
        beepPending = true;
        say(getString(R.string.find_looking));
        job(() -> link.beep(RemoteLink.FMR_MAX_TENTHS, FIND_PATIENCE_MS), failure -> {
            beepPending = false;
            if (!finding || failure == null) return;
            say(failure == RemoteLink.Failure.UNSUPPORTED ? getString(R.string.find_no_beeper) : problemText(failure));
        });
    }

    private void stopBeep() {
        job(() -> link.beep(0, 0), failure -> { });
    }

    // --- the link ---------------------------------------------------------------------------

    /** Give the link a job; then is called on the main thread with why it failed, or null. */
    void job(Runnable submit, Consumer<RemoteLink.Failure> then) {
        Runnable go = () -> {
            jobs.add(then);
            submit.run();
        };
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            go.run();
        } else {
            afterPermission = () -> {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    go.run();
                } else {
                    then.accept(RemoteLink.Failure.NO_PERMISSION);
                }
            };
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        Runnable next = afterPermission;
        afterPermission = null;
        if (next != null) next.run();
    }

    @Override
    public void log(String line) {
        Log.i(TAG, line);
    }

    @Override
    public void jobDone(String job, RemoteLink.Failure failure) {
        Consumer<RemoteLink.Failure> then = jobs.poll();
        if (then != null) then.accept(failure);
    }

    @Override
    public void waiting() {
        if (finding) say(getString(R.string.find_waiting));
    }

    @Override
    public void keyEvent(int keycode, boolean down) {
        Log.i(TAG, "key " + keycode + (down ? " down" : " up"));
        if (!asking || !down) return;
        int name;
        switch (keycode) {
            case 24: name = R.string.key_volume_up; break;
            case 25: name = R.string.key_volume_down; break;
            case 26: name = R.string.key_power; break;
            case 164: name = R.string.key_mute; break;
            default: name = R.string.key_input; break;
        }
        say(getString(R.string.status_pressed, getString(name)));
    }

    @Override
    public void beepEvent(int event, int tenths) {
        Log.i(TAG, "beep event " + event + ", " + tenths / 10.0 + " s left");
        if (!finding) return;
        switch (event) {
            case RemoteLink.BEEP_ACCEPTED:
                say(getString(tenths > 0 ? R.string.find_beeping : R.string.find_stopped));
                break;
            case RemoteLink.BEEP_CAPPED:
                say(getString(R.string.find_beeping));
                break;
            case RemoteLink.BEEP_FINISHED:
                say(getString(R.string.find_finished));
                break;
            case RemoteLink.BEEP_FOUND:
                say(getString(R.string.find_found));
                break;
            case RemoteLink.BEEP_LOW_BATTERY:
                say(getString(R.string.find_low_battery));
                break;
            default:
                say(getString(R.string.problem_refused));
                break;
        }
    }

    // --- brand list -------------------------------------------------------------------------

    /** Not listed, the most common brands, then every brand; the two captions cannot be picked. */
    private final class BrandAdapter extends BaseAdapter {
        private final List<String> rows = new ArrayList<>();
        private final List<Boolean> caption = new ArrayList<>();

        BrandAdapter() {
            add(getString(R.string.brand_not_listed), false);
            add(getString(R.string.brand_popular), true);
            for (String brand : db.popularBrands()) add(brand, false);
            add(getString(R.string.brand_all), true);
            for (String brand : db.brands()) add(brand, false);
        }

        private void add(String row, boolean isCaption) {
            rows.add(row);
            caption.add(isCaption);
        }

        /** The brand on a row, or null for "not listed". */
        String brandAt(int position) {
            return position == 0 ? null : rows.get(position);
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return !caption.get(position);
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return caption.get(position) ? 1 : 0;
        }

        @Override
        public View getView(int position, View reuse, ViewGroup parent) {
            boolean isCaption = caption.get(position);
            TextView row = reuse instanceof TextView ? (TextView) reuse
                    : Ui.text(MainActivity.this, isCaption ? 15 : 20, isCaption ? Ui.MUTED : Ui.TEXT);
            int pad = Ui.dp(MainActivity.this, 16);
            row.setPadding(pad, Ui.dp(MainActivity.this, isCaption ? 18 : 9), pad, Ui.dp(MainActivity.this, isCaption ? 4 : 9));
            row.setGravity(Gravity.START);
            row.setText(rows.get(position));
            return row;
        }
    }
}
