package com.aurora.tvplayer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

final class TvUi {
    static final int BG = Color.rgb(8, 14, 23);
    static final int PANEL = Color.rgb(22, 34, 48);
    static final int TEXT = Color.rgb(237, 246, 248);
    static final int MUTED = Color.rgb(153, 174, 185);
    static final int ACCENT = Color.rgb(128, 227, 212);

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static TextView text(Context c, String value, int sp, int color) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundTintList(ColorStateList.valueOf(PANEL));
        b.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        b.setOnFocusChangeListener((v, focused) -> {
            b.setBackgroundTintList(ColorStateList.valueOf(focused ? ACCENT : PANEL));
            b.setTextColor(focused ? BG : TEXT);
            b.setScaleX(focused ? 1.035f : 1f);
            b.setScaleY(focused ? 1.035f : 1f);
        });
        return b;
    }

    static void space(View v, int pixels) {
        v.setMinimumHeight(dp(v.getContext(), pixels));
    }

    private TvUi() {}
}
