package com.heracaresolutions.kiosk;

import android.graphics.Rect;
import android.text.method.TransformationMethod;
import android.view.View;
import android.widget.EditText;

/**
 * Shows every character of a PIN as a dot, including the one just typed.
 *
 * Android's own password masking briefly shows the last digit when the tablet's
 * "Show passwords" setting is on (it is on by default on most tablets). On a
 * shared tablet in a corridor that is enough for someone looking over a shoulder,
 * so the kiosk's PIN fields use this instead: there is no reveal, whatever the
 * system setting says.
 */
final class NoEchoMask implements TransformationMethod {
    private static final NoEchoMask INSTANCE = new NoEchoMask();

    static void apply(EditText e) {
        e.setTransformationMethod(INSTANCE);
    }

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        return new Dots(source);
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction, Rect previouslyFocusedRect) {
    }

    /** A live view of the text: same length, every character a dot. */
    private static final class Dots implements CharSequence {
        private final CharSequence source;

        Dots(CharSequence source) {
            this.source = source;
        }

        @Override
        public int length() {
            return source.length();
        }

        @Override
        public char charAt(int index) {
            return '•';
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            StringBuilder b = new StringBuilder(Math.max(0, end - start));
            for (int i = start; i < end; i++) b.append('•');
            return b.toString();
        }

        @Override
        public String toString() {
            return subSequence(0, length()).toString();
        }
    }
}
