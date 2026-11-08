package com.desklink.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

/**
 * The gamepad's PlayStation-style icons (outlines from Kenney's Input Prompts, CC0, as vector drawables: small and
 * sharp at any size). △ ○ ✕ □ can be drawn in their colors.
 */
final class PadIcons {
    private PadIcons() {}

    private static final int[] FACE = { // TRIANGLE … SQUARE
            R.drawable.ps_triangle_outline, R.drawable.ps_circle_outline, R.drawable.ps_cross_outline,
            R.drawable.ps_square_outline};

    /** The icon of a button (not the four directions); 0 if it has none. */
    static int res(int control) {
        switch (control) {
            case Gamepad.TRIANGLE:
            case Gamepad.CIRCLE:
            case Gamepad.CROSS:
            case Gamepad.SQUARE:
                return FACE[control - Gamepad.TRIANGLE];
            case Gamepad.L1: return R.drawable.ps_l1_outline;
            case Gamepad.L2: return R.drawable.ps_l2_outline;
            case Gamepad.R1: return R.drawable.ps_r1_outline;
            case Gamepad.R2: return R.drawable.ps_r2_outline;
            case Gamepad.L3: return R.drawable.ps_l3_outline;
            case Gamepad.R3: return R.drawable.ps_r3_outline;
            case Gamepad.SELECT: return R.drawable.ps_select_outline;
            case Gamepad.START: return R.drawable.ps_start_outline;
            default: return 0;
        }
    }

    /** The color of △ ○ ✕ □ (when the buttons are colored), else white. */
    static int color(int control, boolean colored) {
        boolean face = control >= Gamepad.TRIANGLE && control <= Gamepad.SQUARE;
        return colored && face ? GamepadControls.FACE_COLORS[control - Gamepad.TRIANGLE] : 0xFFFFFFFF;
    }

    /** A control's icon for the profile editor; a direction is the D-pad with that arm lit in {@code armColor}. */
    static Drawable icon(Context c, int control, boolean colored, int armColor) {
        if (control >= Gamepad.UP && control <= Gamepad.RIGHT) {
            Drawable pad = mutated(c, R.drawable.ps_dpad_outline);
            pad.setTint(0x99FFFFFF);
            Drawable arm = mutated(c, arm(control));
            arm.setTint(armColor | 0xFF000000);
            return new LayerDrawable(new Drawable[] {pad, arm});
        }
        Drawable d = mutated(c, res(control));
        d.setTint(color(control, colored));
        return d;
    }

    /** The D-pad's arm for a direction (Gamepad.UP … RIGHT). */
    static int arm(int direction) {
        switch (direction) {
            case Gamepad.UP: return R.drawable.ps_dpad_up;
            case Gamepad.DOWN: return R.drawable.ps_dpad_down;
            case Gamepad.LEFT: return R.drawable.ps_dpad_left;
            default: return R.drawable.ps_dpad_right;
        }
    }

    static Drawable mutated(Context c, int res) {
        Drawable d = c.getDrawable(res);
        if (d == null) throw new IllegalStateException("missing drawable");
        return d.mutate();
    }
}
