package dev.silksong.shell;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

// Editing the on-screen controls without leaving the game: a small pencil
// over the game that, held, turns the controls in place into their edit mode.
// While editing, a control is dragged to move it and pinched (or -/+) to
// resize it; nothing is pressed and the game is paused underneath.
//
// Deliberately less than ControlsEditorActivity: only where things sit and
// how big they are. Choosing actions, adding and hiding buttons, opacity and
// the other posture's profile stay in the launcher's editor.
//
// The pencil wants a hold, not a tap, so that a thumb slipping onto it in a
// fight does not take the controls away mid-jump. A tap says as much.
final class InGameControlsEditor
{
    interface Host
    {
        /** Editing started or stopped: pause or resume the game. */
        void onEditingChanged(boolean editing);
        /** Throw away unsaved changes by re-reading the saved layout. */
        void reload();
    }

    private final Activity activity;
    private final FrameLayout root;
    private final TouchControlsView controls;
    private final Host host;
    private final float density;
    private final TextView editButton;
    private HorizontalScrollView toolbarScroll;
    private TextView sizeLabel;
    private boolean editing;
    private boolean toolbarAtTop = true;
    private boolean buttonWanted = true;

    InGameControlsEditor(Activity activity, FrameLayout root, TouchControlsView controls, Host host)
    {
        this.activity = activity;
        this.root = root;
        this.controls = controls;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;

        editButton = new TextView(activity);
        editButton.setText("\u270e");
        editButton.setTextColor(0xCCFFFFFF);
        editButton.setTextSize(18);
        editButton.setGravity(Gravity.CENTER);
        editButton.setAlpha(0.6f);
        editButton.setContentDescription("Edit on-screen controls");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        bg.setStroke(Math.max(1, dp(1.5f)), 0x88FFFFFF);
        editButton.setBackground(bg);
        editButton.setOnClickListener(v ->
            Toast.makeText(activity, "Hold to edit the on-screen controls", Toast.LENGTH_SHORT).show());
        editButton.setOnLongClickListener(v -> { start(); return true; });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(38), dp(38),
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(8);
        root.addView(editButton, lp);

        controls.setSelectionListener(c -> updateToolbar());
    }

    boolean isEditing() { return editing; }

    /** The pencil follows the controls: away while a controller is in use. */
    void setButtonVisible(boolean visible)
    {
        buttonWanted = visible;
        if (!editing) editButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void start()
    {
        if (editing || controls.getLayout() == null) return;
        editing = true;
        controls.setEditing(true);
        controls.select(null);
        editButton.setVisibility(View.GONE);
        if (toolbarScroll == null) buildToolbar();
        toolbarScroll.setVisibility(View.VISIBLE);
        updateToolbar();
        host.onEditingChanged(true);
        Toast.makeText(activity, "Drag a control to move it \u00b7 pinch or \u2212/+ to resize",
            Toast.LENGTH_SHORT).show();
    }

    /** Leaves edit mode, keeping the changes (saved) or throwing them away. */
    void finish(boolean keep)
    {
        if (!editing) return;
        editing = false;
        ControlsLayout layout = controls.getLayout();
        if (keep && layout != null) layout.save(activity);
        controls.select(null);
        controls.setEditing(false);
        if (toolbarScroll != null) toolbarScroll.setVisibility(View.GONE);
        editButton.setVisibility(buttonWanted ? View.VISIBLE : View.GONE);
        host.onEditingChanged(false);
        if (!keep) host.reload();
        else Toast.makeText(activity, "Controls saved", Toast.LENGTH_SHORT).show();
    }

    // -- toolbar -------------------------------------------------------------

    private void buildToolbar()
    {
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        toolbar.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0202228);
        bg.setCornerRadius(dp(14));
        toolbar.setBackground(bg);

        TextView title = label("Edit controls");
        title.setTextColor(0xFFE0B04A);
        toolbar.addView(title);
        toolbar.addView(gap());
        toolbar.addView(button("\u2212", v -> resize(1f / 1.1f)));
        sizeLabel = label("size");
        sizeLabel.setSingleLine(true);
        sizeLabel.setGravity(Gravity.CENTER);
        sizeLabel.setLayoutParams(new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
        toolbar.addView(sizeLabel);
        toolbar.addView(button("+", v -> resize(1.1f)));
        toolbar.addView(gap());
        toolbar.addView(button("\u21c5", v -> {
            toolbarAtTop = !toolbarAtTop;
            placeToolbar();
        }));
        toolbar.addView(button("Cancel", v -> finish(false)));
        toolbar.addView(button("Done", v -> finish(true)));

        toolbarScroll = new HorizontalScrollView(activity);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.addView(toolbar);
        root.addView(toolbarScroll);
        placeToolbar();
    }

    private void placeToolbar()
    {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            (toolbarAtTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = lp.bottomMargin = dp(12);
        toolbarScroll.setLayoutParams(lp);
    }

    private void resize(float factor)
    {
        ControlsLayout.Control c = controls.getSelected();
        if (c == null)
        {
            Toast.makeText(activity, "Tap a control first", Toast.LENGTH_SHORT).show();
            return;
        }
        c.scale = ControlsLayout.clamp(c.scale * factor, 0.3f, 4f);
        controls.invalidate();
        updateToolbar();
    }

    private void updateToolbar()
    {
        if (sizeLabel == null) return;
        ControlsLayout.Control c = controls.getSelected();
        sizeLabel.setText(c == null ? "size" : Math.round(c.scale * 100) + "%");
    }

    private TextView button(String text, View.OnClickListener click)
    {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setMinWidth(dp(44));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF33363F);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView label(String text)
    {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(14);
        t.setPadding(dp(8), 0, dp(8), 0);
        return t;
    }

    private View gap()
    {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        return v;
    }

    private int dp(float v) { return Math.round(v * density); }
}
