package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.desklink.core.Vk;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The calls keyboards make, and the text they leave on the PC. Keyboards look back at the text before the caret
 * and re-edit what they typed; only the keystrokes that differ may reach the PC.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KeyInputViewTest {
    /** The PC: text is typed at its caret, at the end; Backspace deletes one character before it. */
    private final StringBuilder pc = new StringBuilder();
    private int keystrokes;
    private KeyInputView view;
    private InputConnection ic;

    @Before
    public void setUp() {
        view = new KeyInputView(RuntimeEnvironment.getApplication());
        view.setSink(new KeyInputView.Sink() {
            @Override public void typeText(String text) {
                pc.append(text);
                keystrokes += text.codePointCount(0, text.length());
            }
            @Override public void keyTap(int vk) { key(vk); }
            @Override public void keyEvent(int vk, boolean down) { if (down) key(vk); }
            @Override public boolean modifiersActive() { return false; }
        });
        view.setPredictive(true);
        ic = view.onCreateInputConnection(new EditorInfo());
    }

    private void key(int vk) {
        keystrokes++;
        if (vk == Vk.BACK && pc.length() > 0) pc.setLength(pc.offsetByCodePoints(pc.length(), -1));
        else if (vk == Vk.RETURN) pc.append('\n');
    }

    /** A word typed letter by letter, composing as it goes. */
    private void compose(String word) {
        for (int i = 1; i <= word.length(); i++) ic.setComposingText(word.substring(0, i), 1);
    }

    private String before() { return ic.getTextBeforeCursor(10000, 0).toString(); }

    private void press(int keyCode) {
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    @Test
    public void letterAfterWordAndDotReEditsThatWord() {
        // Samsung's keyboard: "program" composes, "." ends it; "e" right after makes "program." composing again
        compose("program");
        ic.finishComposingText();
        ic.commitText(".", 1);
        assertEquals("program.", before());
        assertTrue(ic.setComposingRegion(0, 8));
        int was = keystrokes;
        ic.setComposingText("program.e", 1);
        assertEquals("only the e is typed", was + 1, keystrokes);
        ic.setComposingText("program.ex", 1);
        ic.setComposingText("program.exe", 1);
        ic.finishComposingText();
        assertEquals("program.exe", pc.toString());
        assertEquals("program.exe", before());
    }

    @Test
    public void backspaceIntoAWordReEditsIt() {
        // Gboard: after "world " a Backspace deletes the space and makes "world" composing again
        compose("hello");
        ic.commitText("hello", 1);
        ic.commitText(" ", 1);
        compose("world");
        ic.commitText("world", 1);
        ic.commitText(" ", 1);
        assertEquals("hello world ", pc.toString());
        ic.deleteSurroundingText(1, 0);
        assertTrue(ic.setComposingRegion(6, 11));
        int was = keystrokes;
        ic.setComposingText("worl", 1);
        assertEquals("one Backspace", was + 1, keystrokes);
        ic.commitText("word", 1); // a suggestion picked: "worl" -> "word"
        assertEquals("hello word", pc.toString());
        assertEquals("hello word", before());
    }

    @Test
    public void correctionReplacesOnlyWhatDiffers() {
        compose("teh");
        int was = keystrokes;
        ic.commitText("the", 1);
        assertEquals("the", pc.toString());
        assertEquals("2 Backspaces and \"he\"", was + 4, keystrokes);
    }

    @Test
    public void textNotEndingAtTheCaretIsRefused() {
        compose("hello");
        ic.commitText("hello world", 1);
        assertFalse(ic.setComposingRegion(0, 5));  // "hello": keystrokes can't reach it
        assertFalse(ic.setComposingRegion(-3, 11)); // starts before the text
        assertTrue(ic.setComposingRegion(6, 11));   // "world" ends at the caret
        assertTrue(ic.setComposingRegion(11, 11));  // empty: nothing composing
        assertEquals("hello world", pc.toString());
    }

    @Test
    public void caretMovedOnThePcForgetsTheText() {
        compose("hello");
        ic.commitText("hello", 1);
        view.resetComposition(); // a click on the PC
        assertEquals("", before());
        assertFalse(ic.setComposingRegion(0, 5));
        ic.setComposingText("x", 1);
        assertEquals("hellox", pc.toString());
    }

    @Test
    public void enterForgetsTheText() {
        compose("ls");
        ic.commitText("ls", 1);
        press(KeyEvent.KEYCODE_ENTER);
        assertEquals("ls\n", pc.toString());
        assertFalse(ic.setComposingRegion(0, 2));
    }

    @Test
    public void keysAreRemembered() {
        press(KeyEvent.KEYCODE_A);
        press(KeyEvent.KEYCODE_B);
        press(KeyEvent.KEYCODE_SPACE);
        assertEquals("ab ", pc.toString());
        assertEquals("ab ", before());
        press(KeyEvent.KEYCODE_DEL);
        assertEquals("ab", pc.toString());
        assertEquals("ab", before());
        assertTrue(ic.setComposingRegion(0, 2));
        ic.setComposingText("abc", 1);
        assertEquals("abc", pc.toString());
    }

    @Test
    public void deletingCountsCharactersOnThePc() {
        ic.commitText("a😀", 1); // "a" and an emoji (two Java chars, one character on the PC)
        int was = keystrokes;
        ic.deleteSurroundingText(2, 0);
        assertEquals("one Backspace for the emoji", was + 1, keystrokes);
        assertEquals("a", pc.toString());
        assertEquals("a", before());
        ic.commitText("😀b", 1);
        ic.deleteSurroundingTextInCodePoints(2, 0);
        assertEquals("a", pc.toString());
        assertEquals("a", before());
    }

    @Test
    public void longTextKeepsItsPositions() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2495; i++) sb.append('x');
        ic.commitText(sb.toString(), 1);
        ic.commitText(" hello", 1); // the caret is at 2501; the oldest text is no longer kept
        assertTrue(before().length() < 2501);
        assertTrue(before().endsWith(" hello"));
        assertFalse(ic.setComposingRegion(0, 5));       // forgotten
        assertTrue(ic.setComposingRegion(2496, 2501));  // "hello"
        ic.setComposingText("help", 1);
        assertTrue(pc.toString().endsWith("xx help"));
        assertEquals(2500, pc.length());
    }
}
