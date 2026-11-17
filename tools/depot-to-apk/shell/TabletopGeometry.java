package dev.silksong.shell;

import android.graphics.Rect;
import android.graphics.RectF;

// How a half-folded window is shared out: the game above the fold, and below
// it the dual-screen panel in the middle with a strip either side for the
// thumbs. Shared by the game (TabletopMode), the controls' automatic placement
// (ControlsLayout) and the controls editor, so all three agree.
//
// Foldables come in very different shapes, so nothing here assumes one:
//
//   - Book-style with a portrait inner screen (Galaxy Z Fold up to 7, Fold
//     Ultra), held as a laptop: a landscape window cut across the middle.
//     The bottom half is wide and short, the panel's height decides its size,
//     and the strips are wide.
//   - Passport-shaped (Galaxy Z Fold 8, Pixel Fold, OnePlus Open), held as a
//     laptop: the inner screen stands upright, a portrait window cut across
//     the middle. Each half is roughly 3:2.
//   - Clamshell (Galaxy Z Flip, Razr): a tall portrait window cut across the
//     middle; each half is close to square and narrow.
//
// The fold is where the device says it is, not assumed to be the middle, and
// any gap a hinge has is left empty.
//
// The panel keeps the shape the dual-screen UI was designed at (1240x1080),
// because its display has a fixed size and the compositor scales it into the
// panel's view: a panel of another shape would be stretched. It is as large
// as fits while leaving each strip at least MIN_STRIP_DP wide -- room for the
// stick -- or, on a narrow screen, MAX_STRIP_FRACTION of the width.
final class TabletopGeometry
{
    /** The dual-screen UI's design size; the panel always has this shape. */
    static final float PANEL_ASPECT = 1240f / 1080f;
    /** A side strip wide enough for the stick (120dp) and a margin. */
    static final float MIN_STRIP_DP = 140f;
    /** ...but never so wide that the panel is under 40% of the width. */
    static final float MAX_STRIP_FRACTION = 0.3f;

    final int windowW, windowH;
    /** The game: the full width, from the top down to here. */
    final int gameBottom;
    /** The area under the fold: the full width, from here to the bottom. */
    final int areaTop;
    /** The panel, in the window's coordinates. */
    final Rect panel = new Rect();

    /**
     * @param foldTop    the top of the fold (or of a hinge's gap), in window pixels
     * @param foldBottom its bottom; the same as foldTop for a fold with no gap
     */
    TabletopGeometry(int windowW, int windowH, int foldTop, int foldBottom, float density)
    {
        this.windowW = Math.max(1, windowW);
        this.windowH = Math.max(2, windowH);
        gameBottom = clamp(foldTop, 1, this.windowH - 1);
        areaTop = clamp(foldBottom, gameBottom, this.windowH - 1);

        float w = this.windowW, areaH = this.windowH - areaTop;
        float minStrip = Math.min(w * MAX_STRIP_FRACTION, MIN_STRIP_DP * density);
        float pw = Math.max(1f, Math.min(w - 2f * minStrip, areaH * PANEL_ASPECT));
        float ph = pw / PANEL_ASPECT;
        float top = areaTop + (areaH - ph) / 2f;
        panel.set(Math.round((w - pw) / 2f), Math.round(top),
            Math.round((w + pw) / 2f), Math.round(top + ph));
    }

    /** No fold known: the window cut across its middle. */
    static TabletopGeometry halves(int windowW, int windowH, float density)
    {
        return new TabletopGeometry(windowW, windowH, windowH / 2, windowH / 2, density);
    }

    RectF panelF() { return new RectF(panel); }

    int gameHeight() { return gameBottom; }

    /** How much of the width each side strip takes. */
    float stripFraction() { return panel.left / (float) windowW; }

    /** Where the area under the fold starts, as a fraction of the height. */
    float areaTopFraction() { return areaTop / (float) windowH; }

    /**
     * The panel display's size, chosen before the engine starts and fixed for
     * the session: the panel's shape, as tall as half the screen's short side.
     * Whatever size the panel's view turns out to be, the display is scaled
     * into it without distortion, since both have PANEL_ASPECT.
     */
    static int[] displaySize(int screenLong, int screenShort)
    {
        int h = Math.max(1, screenShort - screenShort / 2);
        return new int[] { Math.round(h * PANEL_ASPECT), h };
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override public String toString()
    {
        return "window " + windowW + "x" + windowH + ", game " + windowW + "x" + gameBottom
            + ", panel " + panel.width() + "x" + panel.height() + " at " + panel.left + "," + panel.top;
    }
}
