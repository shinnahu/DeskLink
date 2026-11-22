package com.desklink.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.os.SystemClock;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private int enters;                                   // Enter presses: in a chat, each sends the message
    private int enterUps;                                 // ...and their key-ups, when Enter came as a key
    private final List<String> pastes = new ArrayList<>(); // what went to the PC as a paste
    private String clipboard;                             // what the app knows the phone clipboard holds
    private KeyInputView view;
    private InputConnection ic;

    @Before
    public void setUp() {
        view = new KeyInputView(RuntimeEnvironment.getApplication());
        view.setSink(new KeyInputView.Sink() {
            @Override public void typeText(String text) {
                pc.append(text);
                keystrokes += text.codePointCount(0, text.length());
                enters += KeyInputView.lines(text).length - 1; // the PC types a line break as Enter
            }
            @Override public void pasteText(String text) {
                pastes.add(text);
                pc.append(text);
            }
            @Override public boolean isClipboard(String text) { return text.equals(clipboard); }
            @Override public void keyTap(int vk) { key(vk); }
            @Override public void keyEvent(int vk, boolean down) {
                if (down) key(vk);
                else if (vk == Vk.RETURN) enterUps++;
            }
            @Override public boolean modifiersActive() { return false; }
        });
        view.setPredictive(true);
        ic = view.onCreateInputConnection(new EditorInfo());
    }

    private void key(int vk) {
        keystrokes++;
        if (vk == Vk.BACK && pc.length() > 0) pc.setLength(pc.offsetByCodePoints(pc.length(), -1));
        else if (vk == Vk.RETURN) {
            pc.append('\n');
            enters++;
        }
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

    /** A moment passes, shorter than between two taps of a finger: what the keyboard put in at once is over. */
    private void pause() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120)); }

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
        pause();
        int was = keystrokes;
        ic.deleteSurroundingText(2, 0);
        assertEquals("one Backspace for the emoji", was + 1, keystrokes);
        assertEquals("a", pc.toString());
        assertEquals("a", before());
        ic.commitText("😀b", 1);
        pause();
        ic.deleteSurroundingTextInCodePoints(2, 0);
        assertEquals("a", pc.toString());
        assertEquals("a", before());
    }

    @Test
    public void pastedLineIsPastedNotTypedWithEnter() {
        // 2026-11-14: a paste on the phone into a chat on the PC sent the message by itself. The PC types a line break
        // as an Enter press, which sends a chat message, so the keyboard's paste must not be typed as it is.
        String line = "Edge has an enhance videos feature, is this option available in tacit? If not add it and put it as default.\n";
        ic.commitText(line, 1);
        pause();
        assertEquals(Arrays.asList(line), pastes);
        assertEquals("no Enter press", 0, enters);
        assertEquals(line, pc.toString());
        assertEquals("the keyboard starts over, as after Enter", "", before());
    }

    @Test
    public void pastedLinesArePasted() {
        compose("see");
        ic.finishComposingText();
        ic.commitText(" ", 1);
        ic.commitText("first line\r\nsecond line", 1);
        pause();
        assertEquals(Arrays.asList("first line\r\nsecond line"), pastes);
        assertEquals(0, enters);
        assertEquals("see first line\r\nsecond line", pc.toString());
    }

    @Test
    public void enterWithTheWordIsStillEnter() {
        // a keyboard may commit the word it corrected together with Enter: that's typing, Enter included
        compose("teh");
        ic.commitText("the\n", 1);
        pause();
        assertTrue(pastes.isEmpty());
        assertEquals(1, enters);
        assertEquals("the\n", pc.toString());
        ic.commitText("\n", 1); // Enter alone
        assertTrue(pastes.isEmpty());
        assertEquals(2, enters);
    }

    @Test
    public void theClipboardsTextIsPasted() {
        clipboard = "Edge has an enhance videos feature";
        ic.commitText(clipboard, 1);
        pause();
        assertEquals(Arrays.asList(clipboard), pastes);
        assertEquals("the same line stays text before the caret", clipboard, before());
        ic.commitText("hello world", 1); // not the clipboard, no line break: typed
        pause();
        assertEquals(1, pastes.size());
        assertEquals(clipboard + "hello world", pc.toString());
    }

    @Test
    public void aCorrectionIntoTheClipboardsTextPastesOnlyWhatsNew() {
        // the keyboard replaces the word it was composing with the clipboard's text: "Edg" is on the PC already
        clipboard = "Edge has an enhance videos feature";
        compose("Edg");
        ic.commitText(clipboard, 1);
        pause();
        assertEquals(Arrays.asList("e has an enhance videos feature"), pastes);
        assertEquals(clipboard, pc.toString());
    }

    @Test
    public void keysWithLinesArePasted() {
        long t = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, "one\ntwo", 0, 0)); // text sent as one key event (ACTION_MULTIPLE)
        pause();
        assertEquals(Arrays.asList("one\ntwo"), pastes);
        assertEquals(0, enters);
        ic.sendKeyEvent(new KeyEvent(t, "ab", 0, 0)); // two letters: typed
        pause();
        assertEquals(1, pastes.size());
        assertEquals("one\ntwoab", pc.toString());
    }

    @Test
    public void linesSplitAtEveryKindOfLineBreak() {
        assertArrayEquals(new String[]{"a", "b", "c", "d", ""}, KeyInputView.lines("a\r\nb\rc\nd\n"));
        assertArrayEquals(new String[]{"one line"}, KeyInputView.lines("one line"));
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
        pause();
        assertTrue(pc.toString().endsWith("xx help"));
        assertEquals(2500, pc.length());
    }

    // A paste that the keyboard hands over in pieces. Each line break would be an Enter: the chat sends the first line.

    @Test
    public void linesWithEnterKeysBetweenAreOnePaste() {
        ic.commitText("first line of it", 1);
        press(KeyEvent.KEYCODE_ENTER);
        ic.commitText("second line", 1);
        press(KeyEvent.KEYCODE_ENTER);
        press(KeyEvent.KEYCODE_ENTER);
        ic.commitText("after an empty one", 1);
        pause();
        assertEquals(Arrays.asList("first line of it\nsecond line\n\nafter an empty one"), pastes);
        assertEquals("no Enter press", 0, enters);
        assertEquals("no Enter key-up on its own", 0, enterUps);
        assertEquals("the keyboard starts over, as after Enter", "", before());
    }

    @Test
    public void linesWithCommittedBreaksBetweenAreOnePaste() {
        ic.commitText("first line of it", 1);
        ic.commitText("\n", 1);
        ic.commitText("second line", 1);
        ic.commitText("\r\n", 1);
        ic.commitText("3", 1);
        pause();
        assertEquals(Arrays.asList("first line of it\nsecond line\r\n3"), pastes);
        assertEquals(0, enters);
    }

    @Test
    public void linesWithEditorActionsBetweenAreOnePaste() {
        ic.commitText("first line of it", 1);
        ic.performEditorAction(EditorInfo.IME_ACTION_DONE);
        ic.commitText("second line", 1);
        pause();
        assertEquals(Arrays.asList("first line of it\nsecond line"), pastes);
        assertEquals(0, enters);
    }

    @Test
    public void aPasteMayStartWithAOneWordLine() {
        ic.commitText("Hi,", 1);
        press(KeyEvent.KEYCODE_ENTER);
        ic.commitText("second line", 1);
        pause();
        assertEquals(Arrays.asList("Hi,\nsecond line"), pastes);
        assertEquals(0, enters);
    }

    @Test
    public void aLineComposedAndThenCommittedWithItsBreakIsPasted() {
        ic.setComposingText("first line of it", 1);
        ic.commitText("first line of it\n", 1);
        pause();
        assertEquals(Arrays.asList("first line of it\n"), pastes);
        assertEquals(0, enters);
    }

    @Test
    public void wholeLinesCommittedOneByOneAreOnePaste() {
        // two pastes right after each other would both paste what's on the PC's clipboard last
        ic.commitText("first line of it\n", 1);
        ic.commitText("second line\n", 1);
        pause();
        assertEquals(Arrays.asList("first line of it\nsecond line\n"), pastes);
        assertEquals("first line of it\nsecond line\n", pc.toString());
    }

    @Test
    public void enterAMomentAfterTextIsStillEnter() {
        ic.commitText("first line of it", 1); // a suggestion, dictation, or a paste of one line
        pause();
        press(KeyEvent.KEYCODE_ENTER);
        assertTrue(pastes.isEmpty());
        assertEquals(1, enters);
        assertEquals(1, enterUps);
        assertEquals("first line of it\n", pc.toString());
    }

    @Test
    public void aWordAndEnterAtOnceIsStillEnter() {
        ic.commitText("the", 1); // a whole word put in at once, with Enter right behind it
        press(KeyEvent.KEYCODE_ENTER);
        pause();
        assertTrue(pastes.isEmpty());
        assertEquals(1, enters);
        assertEquals("the\n", pc.toString());
        assertEquals("the keyboard starts over, as after Enter", "", before());
    }

    @Test
    public void aCorrectionAndEnterAtOnceIsStillEnter() {
        compose("helo");
        ic.commitText("hello", 1);
        press(KeyEvent.KEYCODE_ENTER);
        assertTrue(pastes.isEmpty());
        assertEquals("at once: nothing waits", 1, enters);
        assertEquals("hello\n", pc.toString());
    }

    @Test
    public void typingDoesNotWait() {
        compose("word");
        assertEquals("word", pc.toString());
        ic.commitText("word", 1);
        ic.commitText(" ", 1);
        assertEquals("word ", pc.toString());
        press(KeyEvent.KEYCODE_A);
        press(KeyEvent.KEYCODE_ENTER);
        assertEquals("word a\n", pc.toString());
    }

    @Test
    public void textPutInAtOnceKeepsItsPlaceAmongKeysAndClicks() {
        ic.commitText("two words", 1);
        press(KeyEvent.KEYCODE_DEL); // Backspace right behind it deletes its last letter
        assertEquals("two word", pc.toString());
        ic.commitText("more words", 1);
        view.resetComposition(); // a click: the text goes before it
        assertEquals("two wordmore words", pc.toString());
    }

    @Test
    public void textPutInAtOnceCanBeEditedBeforeItGoes() {
        ic.commitText("two wrds", 1);
        ic.deleteSurroundingText(3, 0);
        ic.commitText("ords", 1);
        pause();
        assertEquals("two words", pc.toString());
    }
}
