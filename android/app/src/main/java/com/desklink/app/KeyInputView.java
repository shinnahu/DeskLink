package com.desklink.app;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import com.desklink.core.Vk;

/**
 * Invisible editor that receives the phone's normal soft keyboard (or a hardware keyboard) and forwards
 * every keystroke in real time. The editable is kept empty so the IME never re-composes old text.
 */
final class KeyInputView extends View {
    interface Sink {
        /** Printable text, typed immediately. */
        void typeText(String text);
        /** Press + release a Windows virtual key. */
        void keyTap(int vk);
        /** Raw key down/up (hardware keyboards, IME key events). */
        void keyEvent(int vk, boolean down);
        /** True while Ctrl/Alt/Win/Shift is latched or held on the shortcut bar. */
        boolean modifiersActive();
        /** Called on the keyboard's Paste, before Ctrl+V; returns how long to wait (ms) before sending it. */
        default int beforePaste() { return 0; }
    }

    private Sink sink;
    private boolean predictive;

    KeyInputView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setSink(Sink s) { sink = s; }

    /** Switching modes restarts the IME connection so the keyboard picks up the new input type. */
    void setPredictive(boolean p) {
        if (p == predictive) return;
        predictive = p;
        restartInput();
    }

    private void restartInput() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(this);
    }

    /**
     * The caret on the PC moved (click, arrow keys, shortcut keys): in predictive mode the IME must forget
     * the word it is composing, otherwise its next correction would edit text at the wrong place.
     */
    void resetComposition() {
        if (!predictive) return;
        RemoteInputConnection ic = current;
        if (ic != null) ic.composing = "";
        post(this::restartInput); // never restart from inside an IME callback
    }

    private RemoteInputConnection current;

    private static boolean movesCaret(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MOVE_HOME: case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onCheckIsTextEditor() { return true; }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        if (predictive) {
            out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        } else {
            out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        out.initialSelStart = 0;
        out.initialSelEnd = 0;
        current = new RemoteInputConnection(this);
        return current;
    }

    // ------------------------------------------------------------------ hardware / IME key events

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.getCharacters() != null) {
            if (sink != null) sink.typeText(event.getCharacters());
            return true;
        }
        return super.onKeyMultiple(keyCode, repeatCount, event);
    }

    boolean handleKey(KeyEvent e) {
        if (sink == null) return false;
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE || kc == KeyEvent.KEYCODE_POWER || kc == KeyEvent.KEYCODE_HOME) {
            return false;
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (e.getAction() == KeyEvent.ACTION_MULTIPLE) {
            if (kc == KeyEvent.KEYCODE_UNKNOWN && e.getCharacters() != null) sink.typeText(e.getCharacters());
            return true;
        }
        int vk = keycodeToVk(kc);
        boolean special = isSpecial(kc);
        boolean hwModifiers = e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed();
        if (down && (movesCaret(kc) || hwModifiers)) resetComposition();
        if (!special && !hwModifiers && !sink.modifiersActive()) {
            int uc = e.getUnicodeChar(e.getMetaState());
            if (uc != 0 && (uc & KeyCharacterMapCombining) == 0) {
                if (down) sink.typeText(new String(Character.toChars(uc)));
                return true;
            }
        }
        if (vk != 0) {
            sink.keyEvent(vk, down);
            return true;
        }
        return false;
    }

    private static final int KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT;

    private static boolean isSpecial(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DEL: case KeyEvent.KEYCODE_FORWARD_DEL: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_TAB: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MOVE_HOME: case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN: case KeyEvent.KEYCODE_INSERT:
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT: case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT: case KeyEvent.KEYCODE_META_RIGHT: case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SYSRQ:
                return true;
            default:
                return kc >= KeyEvent.KEYCODE_F1 && kc <= KeyEvent.KEYCODE_F12;
        }
    }

    static int keycodeToVk(int kc) {
        if (kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z) return 0x41 + (kc - KeyEvent.KEYCODE_A);
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) return 0x30 + (kc - KeyEvent.KEYCODE_0);
        if (kc >= KeyEvent.KEYCODE_F1 && kc <= KeyEvent.KEYCODE_F12) return Vk.F1 + (kc - KeyEvent.KEYCODE_F1);
        if (kc >= KeyEvent.KEYCODE_NUMPAD_0 && kc <= KeyEvent.KEYCODE_NUMPAD_9) return Vk.NUMPAD0 + (kc - KeyEvent.KEYCODE_NUMPAD_0);
        switch (kc) {
            case KeyEvent.KEYCODE_DEL: return Vk.BACK;
            case KeyEvent.KEYCODE_FORWARD_DEL: return Vk.DELETE;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return Vk.RETURN;
            case KeyEvent.KEYCODE_TAB: return Vk.TAB;
            case KeyEvent.KEYCODE_ESCAPE: return Vk.ESCAPE;
            case KeyEvent.KEYCODE_SPACE: return Vk.SPACE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Vk.LEFT;
            case KeyEvent.KEYCODE_DPAD_UP: return Vk.UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Vk.RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Vk.DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return Vk.HOME;
            case KeyEvent.KEYCODE_MOVE_END: return Vk.END;
            case KeyEvent.KEYCODE_PAGE_UP: return Vk.PRIOR;
            case KeyEvent.KEYCODE_PAGE_DOWN: return Vk.NEXT;
            case KeyEvent.KEYCODE_INSERT: return Vk.INSERT;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return Vk.LSHIFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return Vk.RSHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT: return Vk.LCONTROL;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return Vk.RCONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT: return Vk.LMENU;
            case KeyEvent.KEYCODE_ALT_RIGHT: return Vk.RMENU;
            case KeyEvent.KEYCODE_META_LEFT: return Vk.LWIN;
            case KeyEvent.KEYCODE_META_RIGHT: return Vk.RWIN;
            case KeyEvent.KEYCODE_MENU: return Vk.APPS;
            case KeyEvent.KEYCODE_CAPS_LOCK: return Vk.CAPITAL;
            case KeyEvent.KEYCODE_NUM_LOCK: return Vk.NUMLOCK;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return Vk.SCROLL;
            case KeyEvent.KEYCODE_SYSRQ: return Vk.SNAPSHOT;
            case KeyEvent.KEYCODE_BREAK: return Vk.PAUSE;
            case KeyEvent.KEYCODE_GRAVE: return Vk.OEM_3;
            case KeyEvent.KEYCODE_MINUS: return Vk.OEM_MINUS;
            case KeyEvent.KEYCODE_EQUALS: return Vk.OEM_PLUS;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return Vk.OEM_4;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return Vk.OEM_6;
            case KeyEvent.KEYCODE_BACKSLASH: return Vk.OEM_5;
            case KeyEvent.KEYCODE_SEMICOLON: return Vk.OEM_1;
            case KeyEvent.KEYCODE_APOSTROPHE: return Vk.OEM_7;
            case KeyEvent.KEYCODE_COMMA: return Vk.OEM_COMMA;
            case KeyEvent.KEYCODE_PERIOD: return Vk.OEM_PERIOD;
            case KeyEvent.KEYCODE_SLASH: return Vk.OEM_2;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return Vk.MULTIPLY;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return Vk.ADD;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return Vk.SUBTRACT;
            case KeyEvent.KEYCODE_NUMPAD_DOT: return Vk.DECIMAL;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return Vk.DIVIDE;
            default: return 0;
        }
    }

    // ------------------------------------------------------------------ input connection

    /** Turns IME commits / composing updates into keystrokes; never stores text. */
    private static final class RemoteInputConnection extends BaseInputConnection {
        private final KeyInputView owner;
        private String composing = "";

        RemoteInputConnection(KeyInputView v) {
            super(v, false);
            owner = v;
        }

        private Sink sink() { return owner.sink; }

        /** Diff what is on the PC (current composing text) against the new text. */
        private void replaceComposing(String newText) {
            Sink s = sink();
            if (s == null) return;
            String old = composing;
            int p = 0;
            int max = Math.min(old.length(), newText.length());
            while (p < max && old.charAt(p) == newText.charAt(p)) p++;
            if (p > 0 && p < old.length() && Character.isLowSurrogate(old.charAt(p))) p--; // don't split a pair
            int remove = old.codePointCount(p, old.length());
            for (int i = 0; i < remove; i++) s.keyTap(Vk.BACK);
            String add = newText.substring(p);
            if (!add.isEmpty()) s.typeText(add);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            replaceComposing(text == null ? "" : text.toString());
            composing = "";
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            String t = text == null ? "" : text.toString();
            replaceComposing(t);
            composing = t;
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            return false; // we keep no text, so there is nothing to re-compose
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            Sink s = sink();
            if (s == null) return true;
            for (int i = 0; i < beforeLength; i++) s.keyTap(Vk.BACK);
            for (int i = 0; i < afterLength; i++) s.keyTap(Vk.DELETE);
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            return deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return owner.handleKey(event);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            Sink s = sink();
            if (s != null) s.keyTap(Vk.RETURN);
            return true;
        }

        @Override
        public boolean performContextMenuAction(int id) {
            Sink s = sink();
            if (s == null) return false;
            int key;
            if (id == android.R.id.selectAll) key = Vk.A;
            else if (id == android.R.id.cut) key = Vk.X;
            else if (id == android.R.id.copy) key = Vk.C;
            else if (id == android.R.id.paste) key = Vk.V;
            else return false;
            final int k = key;
            Runnable send = () -> {
                s.keyEvent(Vk.CONTROL, true);
                s.keyTap(k);
                s.keyEvent(Vk.CONTROL, false);
            };
            int wait = k == Vk.V ? s.beforePaste() : 0; // a copy just made on the phone goes to the PC first
            if (wait > 0) owner.postDelayed(send, wait);
            else send.run();
            return true;
        }

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            return composing.length() <= n ? composing : composing.substring(composing.length() - n);
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) { return ""; }

        @Override
        public CharSequence getSelectedText(int flags) { return null; }

        @Override
        public int getCursorCapsMode(int reqModes) { return 0; }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) { return null; }

        @Override
        public boolean setSelection(int start, int end) { return true; }

        @Override
        public boolean commitCompletion(CompletionInfo text) {
            return commitText(text.getText(), 1);
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) { return true; }

        @Override
        public boolean clearMetaKeyStates(int states) { return true; }

        @Override
        public boolean beginBatchEdit() { return true; }

        @Override
        public boolean endBatchEdit() { return true; }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) { return false; }
    }
}
