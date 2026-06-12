package com.duoshield.app.util;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.graphics.Typeface;
import android.widget.TextView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextStyleHelper {

    private static final Pattern BOLD     = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern ITALIC   = Pattern.compile("_([^_]+)_");
    private static final Pattern STRIKE   = Pattern.compile("~([^~]+)~");
    private static final Pattern MONO     = Pattern.compile("`([^`]+)`");

    public static void apply(TextView tv, String text) {
        if (text == null) { tv.setText(""); return; }
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        applyPattern(sb, BOLD,   () -> new StyleSpan(Typeface.BOLD));
        applyPattern(sb, ITALIC, () -> new StyleSpan(Typeface.ITALIC));
        applyPattern(sb, STRIKE, StrikethroughSpan::new);
        applyPattern(sb, MONO,   () -> new TypefaceSpan("monospace"));
        tv.setText(sb);
    }

    private interface SpanFactory { Object create(); }

    private static void applyPattern(SpannableStringBuilder sb, Pattern p, SpanFactory f) {
        Matcher m = p.matcher(sb.toString());
        int offset = 0;
        while (m.find()) {
            int s = m.start() - offset;
            int e = m.end()   - offset;
            String inner = m.group(1);
            sb.replace(s, e, inner);
            sb.setSpan(f.create(), s, s + inner.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            offset += (e - s) - inner.length();
        }
    }
}
