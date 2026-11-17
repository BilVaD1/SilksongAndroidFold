package dev.silksong.shell;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

// The on-screen controls editor: the same view the game draws, in edit mode,
// full screen in the same orientation as the game so that what is placed here
// is where it appears in play.
//
// Two profiles, because a foldable held half open is a different shape to
// hold: "Full screen" for normal play and "Half folded" for tabletop, where the
// game is above the fold and the dual-screen panel sits in the middle below it.
// Their outlines are drawn while editing that profile, split where this
// device's fold really is (TabletopGeometry). On a device that stands upright
// to be half folded -- a passport-shaped Fold, a Flip -- the editor turns
// upright too for that profile, so the layout is edited in the shape it is
// played in.
//
// Opened from the launcher's settings by class name. It needs nothing of
// Unity's, so it runs in the launcher's process like the other settings.
public class ControlsEditorActivity extends Activity
{
    private FrameLayout root;
    private TouchControlsView view;
    private LinearLayout toolbar;
    private TextView profileNormal, profileTabletop, sizeLabel, hideButton, opacityLabel, title, actionButton;
    private android.widget.HorizontalScrollView toolbarScroll;
    private String profile = ControlsLayout.PROFILE_NORMAL;
    private boolean toolbarAtTop = true;
    private float density;
    private FoldInfo foldInfo;
    private FoldInfo.Fold fold;
    /** This device stands upright when half folded: its fold runs down a landscape window. */
    private boolean uprightTabletop;

    @Override protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        String requested = getIntent().getStringExtra("profile");
        if (ControlsLayout.PROFILE_TABLETOP.equals(requested)) profile = requested;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF15161A);

        TextView hint = new TextView(this);
        hint.setText("Tap a control to select it \u00b7 drag to move \u00b7 pinch to resize");
        hint.setTextColor(0x88FFFFFF);
        hint.setTextSize(14);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = Math.round(84 * getResources().getDisplayMetrics().density);
        root.addView(hint, hp);

        view = new TouchControlsView(this);
        view.setEditing(true);
        view.setSelectionListener(c -> updateToolbar());
        root.addView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (FoldInfo.available())
            foldInfo = new FoldInfo(this, f -> {
                fold = f;
                Rect b = foldInRoot();
                int w = root.getWidth(), h = root.getHeight();
                // Judged in a landscape window only; upright, the fold runs across either way.
                if (b != null && w > h) uprightTabletop = b.height() > b.width();
                applyOrientation();
                root.post(this::load);
            });

        buildToolbar();
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) root.post(this::load);
        });
    }

    @Override protected void onResume()
    {
        super.onResume();
        if (foldInfo != null) foldInfo.start();
    }

    @Override protected void onPause()
    {
        if (foldInfo != null) foldInfo.stop();
        super.onPause();
    }

    private Rect foldInRoot()
    {
        if (fold == null) return null;
        Rect b = new Rect(fold.bounds);
        int[] at = new int[2];
        root.getLocationInWindow(at);
        b.offset(-at[0], -at[1]);
        return b;
    }

    private void applyOrientation()
    {
        boolean upright = uprightTabletop && ControlsLayout.PROFILE_TABLETOP.equals(profile);
        int want = upright ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        if (getRequestedOrientation() != want) setRequestedOrientation(want);
    }

    /** How a half-folded window this shape is split on this device. */
    private TabletopGeometry tabletopGeometry(int w, int h)
    {
        Rect b = foldInRoot();
        if (b != null && b.width() > b.height() && b.left <= 1 && b.right >= w - 1 && b.top > 0 && b.bottom < h)
            return new TabletopGeometry(w, h, b.top, b.bottom, density);
        return TabletopGeometry.halves(w, h, density);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullscreen();
    }

    private void fullscreen()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null)
            {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        else
        {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void load()
    {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean tabletop = ControlsLayout.PROFILE_TABLETOP.equals(profile);
        // Turning upright for this profile: wait for the window to turn.
        if (tabletop && uprightTabletop && w > h && !isInMultiWindowMode()) return;
        TabletopGeometry geo = tabletop ? tabletopGeometry(w, h) : null;
        view.setLayout(ControlsLayout.load(this, profile, w, h, geo));

        List<RectF> rects = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (geo != null)
        {
            rects.add(new RectF(0, 0, w, geo.gameBottom));
            names.add("Game");
            rects.add(geo.panelF());
            names.add("Dual-screen panel");
        }
        view.setGuides(rects, names);
        title.setText(view.getLayout().name);
        updateToolbar();
    }

    private void save()
    {
        if (view.getLayout() != null) view.getLayout().save(this);
    }

    @Override public void onBackPressed()
    {
        save();
        Toast.makeText(this, "Controls saved", Toast.LENGTH_SHORT).show();
        super.onBackPressed();
    }

    // -- toolbar -------------------------------------------------------------

    private void buildToolbar()
    {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        toolbar.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0202228);
        bg.setCornerRadius(dp(14));
        toolbar.setBackground(bg);

        title = label("");
        title.setTextColor(0xFFE0B04A);
        toolbar.addView(title);

        profileNormal = button("Full screen", v -> switchProfile(ControlsLayout.PROFILE_NORMAL));
        profileTabletop = button("Half folded", v -> switchProfile(ControlsLayout.PROFILE_TABLETOP));
        toolbar.addView(gap());
        toolbar.addView(profileNormal);
        toolbar.addView(profileTabletop);
        toolbar.addView(gap());

        toolbar.addView(button("\u2212", v -> resize(1f / 1.1f)));
        sizeLabel = label("\u2014");
        fixedWidth(sizeLabel, 56);
        toolbar.addView(sizeLabel);
        toolbar.addView(button("+", v -> resize(1.1f)));
        hideButton = button("Hide", v -> toggleHidden());
        fixedWidth(hideButton, 96);
        toolbar.addView(hideButton);
        actionButton = button("Action", v -> pickAction());
        fixedWidth(actionButton, 150);
        toolbar.addView(actionButton);
        toolbar.addView(button("+ Add", v -> addButton()));
        toolbar.addView(gap());

        toolbar.addView(button("\u25d0\u2212", v -> opacity(-0.1f)));
        opacityLabel = label("");
        toolbar.addView(opacityLabel);
        toolbar.addView(button("\u25d0+", v -> opacity(0.1f)));
        toolbar.addView(gap());

        toolbar.addView(button("Reset", v -> {
            if (view.getLayout() == null) return;
            new android.app.AlertDialog.Builder(this)
                .setMessage("Put every control in this layout back where it started? "
                    + "Buttons you added are removed and changed actions are undone.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Reset", (d, w) -> {
                    view.getLayout().reset();
                    view.select(null);
                    view.invalidate();
                    updateToolbar();
                })
                .show();
        }));
        toolbar.addView(button("\u21c5", v -> {
            toolbarAtTop = !toolbarAtTop;
            placeToolbar();
        }));
        toolbar.addView(button("Done", v -> {
            save();
            Toast.makeText(this, "Controls saved", Toast.LENGTH_SHORT).show();
            finish();
        }));

        toolbarScroll = new android.widget.HorizontalScrollView(this);
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

    private void switchProfile(String next)
    {
        if (next.equals(profile)) return;
        save();
        profile = next;
        applyOrientation();
        load();
    }

    private void resize(float factor)
    {
        ControlsLayout.Control c = view.getSelected();
        if (c == null) { Toast.makeText(this, "Select a control first", Toast.LENGTH_SHORT).show(); return; }
        c.scale = ControlsLayout.clamp(c.scale * factor, 0.3f, 4f);
        view.invalidate();
        updateToolbar();
    }

    /** What the selected button does: one of the game's actions. */
    private void pickAction()
    {
        ControlsLayout.Control c = view.getSelected();
        if (c == null) { Toast.makeText(this, "Select a button first", Toast.LENGTH_SHORT).show(); return; }
        if (c.isJoystick()) { Toast.makeText(this, "The stick moves Hornet; it has no action to choose", Toast.LENGTH_SHORT).show(); return; }
        chooseAction("What should this button do?", vk -> {
            c.setKey(vk);
            view.invalidate();
            updateToolbar();
        });
    }

    /** A new button, for an action the layout has no button for. */
    private void addButton()
    {
        ControlsLayout layout = view.getLayout();
        if (layout == null) return;
        chooseAction("Add a button for", vk -> {
            ControlsLayout.Control c = layout.addButton(vk);
            view.select(c);
            view.invalidate();
            Toast.makeText(this, "Added; drag it where you want it", Toast.LENGTH_SHORT).show();
        });
    }

    private interface OnAction { void chosen(int vk); }

    private void chooseAction(String title, OnAction then)
    {
        String[] names = new String[ControlsLayout.ACTIONS.length];
        for (int i = 0; i < names.length; i++) names[i] = ControlsLayout.ACTIONS[i].name;
        new android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names, (d, which) -> then.chosen(ControlsLayout.ACTIONS[which].vk))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void toggleHidden()
    {
        ControlsLayout.Control c = view.getSelected();
        if (c == null) { Toast.makeText(this, "Select a control first", Toast.LENGTH_SHORT).show(); return; }
        if (c.added)
        {
            // A button the player added is removed outright rather than hidden.
            view.getLayout().controls.remove(c);
            view.select(null);
            view.invalidate();
            return;
        }
        c.hidden = !c.hidden;
        view.invalidate();
        updateToolbar();
    }

    private void opacity(float delta)
    {
        ControlsLayout layout = view.getLayout();
        if (layout == null) return;
        layout.opacity = ControlsLayout.clamp(Math.round((layout.opacity + delta) * 10f) / 10f, 0.1f, 1f);
        view.invalidate();
        updateToolbar();
    }

    private void updateToolbar()
    {
        boolean tabletop = ControlsLayout.PROFILE_TABLETOP.equals(profile);
        highlight(profileNormal, !tabletop);
        highlight(profileTabletop, tabletop);
        ControlsLayout.Control c = view.getSelected();
        sizeLabel.setText(c == null ? "size" : Math.round(c.scale * 100) + "%");
        hideButton.setText(c != null && c.added ? "Remove" : (c != null && c.hidden ? "Show" : "Hide"));
        actionButton.setText(c == null ? "Action" : c.isJoystick() ? "Stick" : ControlsLayout.actionName(c.vk));
        ControlsLayout layout = view.getLayout();
        opacityLabel.setText(layout == null ? "" : Math.round(layout.opacity * 100) + "%");
    }

    private void highlight(TextView b, boolean on)
    {
        GradientDrawable bg = (GradientDrawable) b.getBackground();
        bg.setColor(on ? 0xFF7A3443 : 0xFF33363F);
    }

    private TextView button(String text, View.OnClickListener click)
    {
        TextView b = new TextView(this);
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
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(14);
        t.setPadding(dp(8), 0, dp(8), 0);
        return t;
    }

    /** A width that does not follow the text, for labels that change. */
    private void fixedWidth(TextView t, float dp)
    {
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = t.getLayoutParams() instanceof LinearLayout.LayoutParams
            ? (LinearLayout.LayoutParams) t.getLayoutParams()
            : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.width = dp(dp);
        t.setLayoutParams(lp);
    }

    private View gap()
    {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        return v;
    }

    private int dp(float v) { return Math.round(v * density); }
}
