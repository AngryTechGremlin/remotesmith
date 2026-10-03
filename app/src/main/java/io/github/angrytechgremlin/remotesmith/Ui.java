package io.github.angrytechgremlin.remotesmith;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** The few colours, sizes and widgets every page is made of. Made for a TV: big, and D-pad first. */
final class Ui {
    static final int BACKGROUND = 0xFF10141C;
    static final int TEXT = 0xFFF2F4F8;
    static final int MUTED = 0xFFB4BCC8;
    static final int ACCENT = 0xFF8AB4F8;
    private static final int IDLE = 0x22FFFFFF;
    private static final int ON_ACCENT = 0xFF10141C;

    private Ui() {}

    static int dp(Context c, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** A choice: plain when idle, filled with the accent colour when the D-pad is on it. */
    static Button choice(Context c, CharSequence label, Runnable action) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        b.setStateListAnimator(null);
        b.setBackground(highlight(c));
        b.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[] {ON_ACCENT, ON_ACCENT, TEXT}));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Background for anything the D-pad can land on. */
    static Drawable highlight(Context c) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, rounded(c, ACCENT));
        states.addState(new int[] {android.R.attr.state_pressed}, rounded(c, ACCENT));
        states.addState(new int[] {}, rounded(c, IDLE));
        return states;
    }

    private static Drawable rounded(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, 8));
        return d;
    }
}
