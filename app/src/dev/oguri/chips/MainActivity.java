package dev.oguri.chips;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import dev.oguri.chips.engine.Engine;
import dev.oguri.chips.engine.Engine.Game;
import dev.oguri.chips.engine.Engine.Player;
import dev.oguri.chips.engine.Engine.Street;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final int BG = 0xFF14100E, CARD = 0xFF241B16, BORDER = 0xFF3A2C20,
            GOLD = 0xFFD9A441, GREEN = 0xFF2FA866, GREEN_D = 0xFF1E7A48,
            TXT = 0xFFFFF7ED, DIM = 0xFFB8A99A, RED = 0xFFFF8FA3, FIELD = 0xFF1A130E;

    Engine engine;
    String screen = "setup";
    boolean[][] boardWinners; // showdown selections [board][player index in bySeat list]
    long lastSavedAt = 0;

    // ---------- helpers ----------
    int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
    LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    LinearLayout.LayoutParams lpw() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return p;
    }
    GradientDrawable roundBg(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }
    Button btn(String label, int bgColor, int txtColor, float sp) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(txtColor); b.setTextSize(sp);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(roundBg(bgColor, 14, 0));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        return b;
    }
    LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(roundBg(CARD, 16, BORDER));
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams p = lpw(); p.setMargins(0, dp(6), 0, dp(6));
        l.setLayoutParams(p);
        return l;
    }
    TextView label(String s) {
        TextView t = tv(s.toUpperCase(), 11.5f, DIM, true);
        LinearLayout.LayoutParams p = lpw(); p.setMargins(dp(2), dp(12), 0, dp(4));
        t.setLayoutParams(p);
        return t;
    }
    ScrollView page(LinearLayout root) {
        ScrollView sv = new ScrollView(this);
        root.setPadding(dp(16), dp(10), dp(16), dp(20));
        root.setBackgroundColor(BG);
        sv.addView(root);
        sv.setBackgroundColor(BG);
        return sv;
    }
    LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    EditText numField(int value) {
        EditText e = new EditText(this);
        e.setText(String.valueOf(value));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(TXT); e.setTextSize(18);
        e.setBackground(roundBg(FIELD, 10, BORDER));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setSelectAllOnFocus(true);
        return e;
    }
    int parseNum(EditText e, int dflt) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return dflt; }
    }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void textDialog(String title, String initial, final java.util.function.Consumer<String> done) {
        final EditText e = new EditText(this);
        e.setText(initial); e.setTextColor(TXT); e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { done.accept(e.getText().toString().trim()); }
            }).setNegativeButton("Cancel", null).show();
    }
    void numDialog(String title, int initial, final java.util.function.Consumer<Integer> done) {
        textDialog(title, String.valueOf(initial), new java.util.function.Consumer<String>() {
            public void accept(String s) {
                try { done.accept(Integer.parseInt(s)); } catch (Exception ex) { toast("Not a number"); }
            }
        });
    }

    // ---------- persistence ----------
    SharedPreferences prefs() { return getSharedPreferences("ogurichips", MODE_PRIVATE); }
    void saveGame(boolean manual) {
        prefs().edit().putString("save", engine.encode())
                .putLong("savedAt", System.currentTimeMillis()).apply();
        lastSavedAt = System.currentTimeMillis();
        if (manual) toast("Game saved 💾");
    }
    boolean hasSave() { return prefs().contains("save"); }
    void loadGame() {
        String s = prefs().getString("save", null);
        if (s != null) { engine.decode(s); lastSavedAt = prefs().getLong("savedAt", 0); }
    }

    // ---------- app ----------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new Engine();
        addDefaultPlayers(engine);
        showSetup();
    }

    ImageView appIcon(int sizeDp) {
        ImageView iv = new ImageView(this);
        try { iv.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager())); }
        catch (Exception e) { /* icon is decorative in the header */ }
        return iv;
    }
    Button undoButton() {
        Button u = btn("↩ Undo", GOLD, 0xFF231A02, 13.5f);
        u.setPadding(dp(14), dp(7), dp(14), dp(7));
        u.setEnabled(engine.canUndo());
        u.setAlpha(engine.canUndo() ? 1f : 0.4f);
        u.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                engine.undo(); saveGame(false); refresh();
            }
        });
        return u;
    }
    void refresh() {
        if (screen.equals("setup")) showSetup();
        else if (screen.equals("prehand")) showPreHand();
        else if (screen.equals("table")) showTable();
        else if (screen.equals("showdown")) showShowdown();
        else if (screen.equals("settle")) showSettlement();
        else if (screen.equals("menu")) showMenu();
    }
    LinearLayout topBar(String title, String sub) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = col();
        t.addView(tv(title, 20, TXT, true));
        if (sub != null) t.addView(tv(sub, 12.5f, DIM, false));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        bar.addView(t, tp);
        Button menu = btn("☰ Menu", 0xFF332619, GOLD, 13.5f);
        menu.setPadding(dp(14), dp(7), dp(14), dp(7));
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(); }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.setMargins(0, 0, dp(8), 0);
        bar.addView(menu, mp);
        bar.addView(undoButton());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(44), dp(44));
        ip.setMargins(dp(8), 0, 0, 0);
        bar.addView(appIcon(44), ip);
        return bar;
    }

    // ================= SETUP =================
    EditText fBuyin, fSb, fBb, fAnte;
    void showSetup() {
        screen = "setup";
        LinearLayout root = col();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv("Oguri's Chips", 26, GOLD, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(appIcon(48), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(head);

        if (hasSave()) {
            LinearLayout c = card();
            c.addView(tv("You have a saved game", 15, TXT, true));
            c.addView(tv("Resume exactly where you left it — stacks, seats, everything.", 12.5f, DIM, false));
            Button resume = btn("Resume saved game", GREEN, Color.WHITE, 15);
            resume.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { loadGame(); toast("Game loaded"); showPreHand(); }
            });
            LinearLayout.LayoutParams rp = lpw(); rp.setMargins(0, dp(8), 0, 0);
            c.addView(resume, rp);
            root.addView(c);
        }

        root.addView(label("Game"));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(roundBg(CARD, 16, BORDER));
        seg.setPadding(dp(5), dp(5), dp(5), dp(5));
        final Button bH = btn("Texas Hold'em\nNo limit · blinds", engine.game == Game.HOLDEM ? GREEN_D : CARD, TXT, 14);
        final Button bO = btn("Omaha Bomb Pot\nPot limit · ante only", engine.game == Game.OMAHA_BOMB ? GREEN_D : CARD, TXT, 14);
        View.OnClickListener gl = new View.OnClickListener() {
            public void onClick(View v) {
                engine.game = (v == bH) ? Game.HOLDEM : Game.OMAHA_BOMB;
                showSetup();
            }
        };
        bH.setOnClickListener(gl); bO.setOnClickListener(gl);
        seg.addView(bH, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        seg.addView(bO, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(seg);

        root.addView(label("Players & seats · tap a seat chip to change"));
        LinearLayout pc = card();
        for (final Player pl : engine.bySeat()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView av = tv(pl.name.isEmpty() ? "?" : pl.name.substring(0, 1).toUpperCase(), 16, 0xFF08130C, true);
            av.setGravity(Gravity.CENTER);
            av.setBackground(roundBg(TableView.AVATAR_COLORS[(pl.seat - 1) % 6], 23, 0));
            row.addView(av, lp(dp(46), dp(46)));
            TextView nm = tv(pl.name.isEmpty() ? "Tap to set name" : pl.name, 17,
                    pl.name.isEmpty() ? DIM : TXT, !pl.name.isEmpty());
            nm.setPadding(dp(10), 0, 0, 0);
            nm.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    textDialog("Player name", pl.name, new java.util.function.Consumer<String>() {
                        public void accept(String s) { if (!s.isEmpty()) { pl.name = s; showSetup(); } }
                    });
                }
            });
            row.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button seat = btn("🪑 Seat " + pl.seat + " ▾", 0xFF332619, GOLD, 14);
            seat.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cycleSeat(pl); showSetup(); }
            });
            row.addView(seat);
            if (engine.players.size() > 2) {
                Button x = btn("✕", CARD, DIM, 14);
                x.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { engine.players.remove(pl); showSetup(); }
                });
                row.addView(x);
            }
            row.setPadding(0, dp(5), 0, dp(5));
            pc.addView(row);
        }
        if (engine.players.size() < 6) {
            Button add = btn("＋ Add player", CARD, GOLD, 15);
            add.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    textDialog("New player", "", new java.util.function.Consumer<String>() {
                        public void accept(String s) {
                            if (s.isEmpty()) return;
                            int seat = firstFreeSeat();
                            if (seat == 0) { toast("All 6 seats taken"); return; }
                            engine.addPlayer(s, seat, 0);
                            showSetup();
                        }
                    });
                }
            });
            pc.addView(add, lpw());
        }
        root.addView(pc);

        root.addView(label("Buy-in · Kč = chips 1:1"));
        LinearLayout bc = card();
        fBuyin = numField(engine.buyin);
        bc.addView(fBuyin, lpw());
        root.addView(bc);

        if (engine.game == Game.HOLDEM) {
            root.addView(label("Blinds · Hold'em"));
            LinearLayout bl = card();
            LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
            fSb = numField(engine.sb); fBb = numField(engine.bb);
            LinearLayout c1 = col(); c1.addView(tv("Small blind", 12, DIM, true)); c1.addView(fSb);
            LinearLayout c2 = col(); c2.addView(tv("Big blind", 12, DIM, true)); c2.addView(fBb);
            r.addView(c1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(c2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            bl.addView(r);
            root.addView(bl);
        } else {
            root.addView(label("Bomb pot ante · Omaha only · default 25"));
            LinearLayout ac = card();
            LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
            fAnte = numField(engine.ante);
            LinearLayout c1 = col(); c1.addView(tv("Ante per player / hand", 12, 0xFFC79A3F, true)); c1.addView(fAnte);
            r.addView(c1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ac.addView(r);
            TextView n = tv("No blinds in bomb pots. Everyone antes, flop is dealt, betting starts there.", 12.5f, DIM, false);
            ac.addView(n);
            root.addView(ac);
        }

        Button start = btn("Start table →", GREEN, Color.WHITE, 18);
        LinearLayout.LayoutParams sp = lpw(); sp.setMargins(0, dp(16), 0, 0);
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (Player pl : engine.players)
                    if (pl.name.trim().isEmpty()) pl.name = "Player " + pl.seat;
                applySetup();
                for (Player pl : engine.players)
                    if (pl.stack == 0 && pl.totalIn == 0) { pl.stack = engine.buyin; pl.totalIn = engine.buyin; }
                engine.clearUndo();
                saveGame(false);
                showPreHand();
            }
        });
        root.addView(start, sp);
        setContentView(page(root));
    }

    /** the shipped defaults: four empty seats, names are set by whoever uses the app */
    void addDefaultPlayers(Engine e) {
        for (int s = 1; s <= 4; s++) e.addPlayer("", s, 0);
    }
    String displayName(Player pl) { return pl.name.isEmpty() ? "Player " + pl.seat : pl.name; }
    void applySetup() {
        if (fBuyin != null) engine.buyin = Math.max(5, parseNum(fBuyin, engine.buyin));
        if (engine.game == Game.HOLDEM) {
            if (fSb != null) engine.sb = Math.max(1, parseNum(fSb, engine.sb));
            if (fBb != null) engine.bb = Math.max(1, parseNum(fBb, engine.bb));
        } else {
            if (fAnte != null) engine.ante = Math.max(0, parseNum(fAnte, engine.ante));
        }
    }
    int firstFreeSeat() {
        for (int s = 1; s <= 6; s++) if (engine.playerAtSeat(s) == null) return s;
        return 0;
    }
    void cycleSeat(Player pl) {
        for (int k = 1; k <= 6; k++) {
            int cand = ((pl.seat - 1 + k) % 6) + 1;
            if (engine.playerAtSeat(cand) == null) { pl.seat = cand; return; }
        }
    }

    // ================= BETWEEN HANDS =================
    void showPreHand() {
        screen = "prehand";
        LinearLayout root = col();
        String sub = engine.handNumber == 0 ? "Ready when you are"
                : "Hand #" + engine.handNumber + " done" + (engine.carry > 0 ? " · pot carried over: " + engine.carry : "");
        root.addView(topBar("Menu", sub));

        root.addView(label("Next hand · switch freely — stacks & seats stay"));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(roundBg(CARD, 16, BORDER));
        seg.setPadding(dp(5), dp(5), dp(5), dp(5));
        final Button bH = btn("Texas Hold'em\nNo limit · blinds", engine.game == Game.HOLDEM ? GREEN_D : CARD, TXT, 14);
        final Button bO = btn("Omaha Bomb Pot\nPot limit · ante " + engine.ante, engine.game == Game.OMAHA_BOMB ? GREEN_D : CARD, TXT, 14);
        View.OnClickListener gl = new View.OnClickListener() {
            public void onClick(View v) { engine.game = (v == bH) ? Game.HOLDEM : Game.OMAHA_BOMB; saveGame(false); showPreHand(); }
        };
        bH.setOnClickListener(gl); bO.setOnClickListener(gl);
        seg.addView(bH, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        seg.addView(bO, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(seg);

        if (engine.game == Game.OMAHA_BOMB) {
        root.addView(label("Boards for this hand"));
        LinearLayout bc = card();
        LinearLayout brow = new LinearLayout(this); brow.setOrientation(LinearLayout.HORIZONTAL);
        for (int n = 1; n <= 4; n++) {
            final int bn = n;
            Button bb2 = btn(String.valueOf(n), engine.boards == n ? GOLD : 0xFF332619,
                    engine.boards == n ? 0xFF241B02 : TXT, 16);
            bb2.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { engine.boards = bn; saveGame(false); showPreHand(); }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            bp.setMargins(dp(3), 0, dp(3), 0);
            brow.addView(bb2, bp);
        }
        Button cust = btn("Custom", 0xFF332619, TXT, 14);
        cust.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                numDialog("Boards (1–8)", engine.boards, new java.util.function.Consumer<Integer>() {
                    public void accept(Integer val) {
                        engine.boards = Math.max(1, Math.min(8, val)); saveGame(false); showPreHand();
                    }
                });
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cp.setMargins(dp(3), 0, dp(3), 0);
        brow.addView(cust, cp);
        bc.addView(brow);
        bc.addView(tv("Default is 2 — it stays on your last choice until you change it here.", 12.5f, DIM, false));
        root.addView(bc);
        }

        root.addView(label("Stacks · rebuy before the next hand"));
        LinearLayout sc = card();
        for (final Player pl : engine.bySeat()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView av = tv(pl.name.isEmpty() ? "?" : pl.name.substring(0, 1).toUpperCase(), 15, 0xFF08130C, true);
            av.setGravity(Gravity.CENTER);
            av.setBackground(roundBg(TableView.AVATAR_COLORS[(pl.seat - 1) % 6], 22, 0));
            row.addView(av, lp(dp(44), dp(44)));
            LinearLayout mid = col();
            mid.setPadding(dp(10), 0, 0, 0);
            mid.addView(tv(pl.name, 16.5f, TXT, true));
            mid.addView(tv("Seat " + pl.seat + " · in " + TableView.fmt(pl.totalIn), 12.5f, DIM, false));
            row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(tv(TableView.fmt(pl.stack) + (pl.stack == 0 ? " BUSTED" : ""),
                    17, pl.stack == 0 ? RED : TXT, true));
            Button rb = btn(pl.stack == 0 ? "＋ Rebuy " + engine.buyin : "＋ Rebuy",
                    pl.stack == 0 ? GOLD : 0xFF14301F, pl.stack == 0 ? 0xFF231A02 : 0xFF5FE39A, 13);
            rb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { rebuyDialog(pl); }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(dp(8), 0, 0, 0);
            row.addView(rb, rp);
            row.setPadding(0, dp(6), 0, dp(6));
            sc.addView(row);
        }
        root.addView(sc);

        root.addView(label("Save game"));
        LinearLayout svc = card();
        LinearLayout srow = new LinearLayout(this); srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout st = col();
        st.addView(tv("Save whole game state", 15.5f, TXT, true));
        st.addView(tv("Stacks, seats, settings, carry-over, history.\nAuto-saves after every action.", 12.5f, DIM, false));
        srow.addView(st, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button sv = btn("Save now", 0xFF14301F, 0xFF5FE39A, 14);
        sv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveGame(true); showPreHand(); }
        });
        srow.addView(sv);
        svc.addView(srow);
        root.addView(svc);

        int withChips = 0; for (Player pl : engine.players) if (pl.stack > 0) withChips++;
        Button deal = btn("Deal hand #" + (engine.handNumber + 1) + " →", GREEN, Color.WHITE, 18);
        deal.setEnabled(withChips >= 2);
        deal.setAlpha(withChips >= 2 ? 1f : 0.4f);
        LinearLayout.LayoutParams dlp = lpw(); dlp.setMargins(0, dp(14), 0, 0);
        deal.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (engine.handInProgress) { toast("A hand is already in progress"); showTable(); return; }
                try { engine.startHand(); saveGame(false); showTable(); }
                catch (Exception ex) { toast(ex.getMessage()); }
            }
        });
        root.addView(deal, dlp);

        Button end = btn("End session · standings & settlement", 0xFF3A1620, RED, 14);
        LinearLayout.LayoutParams elp = lpw(); elp.setMargins(0, dp(10), 0, 0);
        end.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettlement(); }
        });
        root.addView(end, elp);
        setContentView(page(root));
    }

    void rebuyDialog(final Player pl) {
        final String[] opts = {"250", "500", "1000", "Custom amount"};
        new AlertDialog.Builder(this).setTitle("Rebuy for " + pl.name)
            .setItems(opts, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w < 3) {
                        engine.rebuy(pl, Integer.parseInt(opts[w])); saveGame(false); refresh();
                    } else {
                        numDialog("Rebuy amount", engine.buyin, new java.util.function.Consumer<Integer>() {
                            public void accept(Integer val) {
                                if (val > 0) { engine.rebuy(pl, val); saveGame(false); refresh(); }
                            }
                        });
                    }
                }
            }).show();
    }

    // ================= MENU =================
    void showMenu() {
        if (!engine.handInProgress) { showPreHand(); return; }
        screen = "menu";
        LinearLayout root = col();
        root.addView(topBar("Menu", "Hand #" + engine.handNumber + " in progress"));
        root.addView(tv("You're mid-hand, so game switching, boards and rebuys unlock as soon as this hand ends — that's what keeps the pot maths honest.", 13.5f, DIM, false));
        root.addView(label("Current game"));
        LinearLayout c = card();
        c.addView(tv(engine.game == Game.HOLDEM ? "Texas Hold'em · no limit" : "Omaha Bomb Pot · pot limit · ante " + engine.ante, 16, TXT, true));
        c.addView(tv("Switch from the table the moment this hand finishes, or here in the menu between hands.", 12.5f, DIM, false));
        root.addView(c);
        Button back = btn("← Back to the table", GREEN, Color.WHITE, 16);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTable(); }
        });
        LinearLayout.LayoutParams bp = lpw(); bp.setMargins(0, dp(14), 0, 0);
        root.addView(back, bp);
        Button sv = btn("💾 Save game now", 0xFF332619, TXT, 14);
        sv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveGame(true); }
        });
        LinearLayout.LayoutParams vp = lpw(); vp.setMargins(0, dp(8), 0, 0);
        root.addView(sv, vp);
        setContentView(page(root));
    }

    // ================= TABLE =================
    boolean raiseOpen = false;
    int raiseVal = 0;
    void showTable() {
        screen = "table";
        LinearLayout root = col();
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(12));
        root.addView(topBar("Hand #" + engine.handNumber,
                engine.game == Game.HOLDEM ? "Texas Hold'em · blinds " + engine.sb + "/" + engine.bb
                        : "Omaha bomb pot · ante " + engine.ante + " · " + engine.effectiveBoards() + (engine.effectiveBoards() > 1 ? " boards" : " board")));

        TableView table = new TableView(this);
        table.setEngine(engine);
        root.addView(table, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(engine.effectiveBoards() >= 3 ? 540 : 470)));

        if (!engine.handInProgress) {
            root.addView(tv(engine.lastAwardNote, 16, GOLD, true));
            Button deal = btn("Deal hand #" + (engine.handNumber + 1) + " →", GREEN, Color.WHITE, 17);
            deal.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try { engine.startHand(); saveGame(false); showTable(); }
                    catch (Exception ex) { toast(ex.getMessage()); showPreHand(); }
                }
            });
            LinearLayout.LayoutParams np = lpw(); np.setMargins(0, dp(12), 0, 0);
            root.addView(deal, np);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button sw = btn(engine.game == Game.HOLDEM ? "⇄ To Omaha" : "⇄ To Hold'em", 0xFF332619, GOLD, 14);
            sw.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    engine.game = engine.game == Game.HOLDEM ? Game.OMAHA_BOMB : Game.HOLDEM;
                    saveGame(false); showTable();
                }
            });
            Button setup2 = btn("☰ Menu", 0xFF332619, TXT, 14);
            setup2.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showPreHand(); }
            });
            row.addView(sw, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            LinearLayout.LayoutParams s2p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            s2p.setMargins(dp(8), 0, 0, 0);
            row.addView(setup2, s2p);
            LinearLayout.LayoutParams rp2 = lpw(); rp2.setMargins(0, dp(8), 0, 0);
            root.addView(row, rp2);
            Button end = btn("End session", 0xFF1A1216, RED, 13);
            end.setPadding(dp(10), dp(5), dp(10), dp(5));
            end.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showSettlement(); }
            });
            LinearLayout.LayoutParams ep = lpw(); ep.setMargins(0, dp(8), 0, 0);
            root.addView(end, ep);
            setContentView(scrollWrap(root));
            return;
        }

        if (engine.street == Street.SHOWDOWN) {
            root.addView(tv("All betting done — time to see who takes it.", 15, TXT, true));
            Button sd = btn("Go to showdown →", GOLD, 0xFF231A02, 17);
            sd.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { initShowdown(); showShowdown(); }
            });
            LinearLayout.LayoutParams sp2 = lpw(); sp2.setMargins(0, dp(12), 0, 0);
            root.addView(sd, sp2);
            setContentView(scrollWrap(root));
            return;
        }

        final Player actor = engine.toAct();
        if (actor == null) { setContentView(scrollWrap(root)); return; }
        int call = engine.callAmount(actor);
        TextView banner = tv("👉 " + actor.name + " to act" + (call > 0 ? " · " + call + " to call" : " · no bet to face"),
                17, GOLD, true);
        banner.setBackground(roundBg(0xFF2A1E08, 14, 0xFF8A6A24));
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams bp = lpw(); bp.setMargins(0, dp(10), 0, dp(10));
        root.addView(banner, bp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        // action order: Bet/Raise first (coloured), then Check/Call, then Fold
        final Button raiseBtn = btn(engine.currentBet == 0 ? "Bet…" : "Raise…", GOLD, 0xFF231A02, 17);
        raiseBtn.setPadding(dp(8), dp(14), dp(8), dp(14));
        boolean canRaise = actor.canRaise && engine.maxRaiseTo(actor) > engine.currentBet;
        raiseBtn.setEnabled(canRaise); raiseBtn.setAlpha(canRaise ? 1f : 0.4f);
        raiseBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { raiseOpen = true; raiseVal = raiseFloor(actor); showTable(); }
        });
        Button cc = btn(call > 0 ? "Call " + call : "Check", 0xFF14301F, 0xFF5FE39A, 17);
        cc.setPadding(dp(8), dp(14), dp(8), dp(14));
        cc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { engine.actCheckCall(); afterEngineAction(); }
        });
        Button fold = btn("Fold", 0xFF3A1620, RED, 17);
        fold.setPadding(dp(8), dp(14), dp(8), dp(14));
        fold.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { engine.actFold(); afterEngineAction(); }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ap2.setMargins(dp(6), 0, 0, 0);
        LinearLayout.LayoutParams ap3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ap3.setMargins(dp(6), 0, 0, 0);
        actions.addView(raiseBtn, ap); actions.addView(cc, ap2); actions.addView(fold, ap3);
        root.addView(actions);

        if (raiseOpen) root.addView(raisePanel(actor));
        setContentView(scrollWrap(root));
    }
    ScrollView scrollWrap(LinearLayout root) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        return sv;
    }

    boolean anyBusted() {
        for (Player pl : engine.players) if (pl.stack == 0 && pl.totalIn > 0) return true;
        return false;
    }
    String bustedNames() {
        StringBuilder sb = new StringBuilder();
        for (Player pl : engine.players)
            if (pl.stack == 0 && pl.totalIn > 0) { if (sb.length() > 0) sb.append(", "); sb.append(pl.name); }
        return sb.toString();
    }
    /** after a hand: stay on the table; only a bust-out opens the setup prompt */
    void routeAfterHand() {
        saveGame(false);
        if (anyBusted()) {
            toast(bustedNames() + " busted — rebuy or play on");
            showPreHand();
        } else showTable();
    }
    void afterEngineAction() {
        raiseOpen = false;
        if (!engine.handInProgress) {
            toast(engine.lastAwardNote);
            routeAfterHand();
        } else { saveGame(false); showTable(); }
    }

    LinearLayout raisePanel(final Player actor) {
        LinearLayout panel = card();
        final int min = raiseFloor(actor);
        final int max = engine.maxRaiseTo(actor);
        final int fullMin = engine.minRaiseTo(actor);
        final int allIn = actor.streetBet + actor.stack;
        if (raiseVal < min) raiseVal = min;
        if (raiseVal > max) raiseVal = max;
        final boolean opening = engine.currentBet == 0;
        panel.addView(tv(opening ? "Bet — " + actor.name : "Raise — " + actor.name, 18, TXT, true));
        // the minimum-raise rule, spelled out for this exact situation
        String rule;
        if (opening) rule = "Minimum opening bet is " + fullMin
                + (engine.game == Game.OMAHA_BOMB ? " (the ante). Maximum " : " (the big blind). Maximum ") + max + ".";
        else if (max < fullMin) rule = "A full minimum raise would be to " + fullMin + ", but your whole stack only reaches "
                + max + " — going all-in short is allowed.";
        else rule = "Minimum raise to " + fullMin + " — current bet " + engine.currentBet
                + " plus the last raise of " + (fullMin - engine.currentBet) + ". Maximum " + max + ".";
        panel.addView(tv(rule, 13.5f, 0xFFC79A3F, false));

        // controls are created first and wired together below (slider <-> number field)
        final boolean[] syncing = {false};
        final EditText amt = numField(raiseVal);
        amt.setTextSize(22);
        amt.setGravity(Gravity.CENTER);
        amt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        final SeekBar sb = new SeekBar(this);
        final int steps = Math.max(1, (max - min) / 5);
        final Button confirm = btn("", GOLD, 0xFF231A02, 16);

        // presets
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        final int call = engine.callAmount(actor);
        int potRaise = engine.currentBet + engine.totalPot() + call; // pot-sized raise-to
        final int[] pr = {
            min, clamp(round5(engine.currentBet + (engine.totalPot() + call) / 2), min, max),
            clamp(potRaise, min, max), max
        };
        String[] names = {"Min", "½ Pot", "Pot", engine.game == Game.HOLDEM ? "All-in" : "Max"};
        for (int i = 0; i < 4; i++) {
            final int val = pr[i];
            Button pb = btn(names[i] + "\n" + val, 0xFF332619, TXT, 13);
            pb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { updateRaiseUi(val, amt, sb, confirm, min, max, steps, syncing); }
            });
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) pp.setMargins(dp(6), 0, 0, 0);
            presets.addView(pb, pp);
        }
        LinearLayout.LayoutParams prp = lpw(); prp.setMargins(0, dp(10), 0, 0);
        panel.addView(presets, prp);

        // direct number entry with steppers
        LinearLayout inRow = new LinearLayout(this);
        inRow.setOrientation(LinearLayout.HORIZONTAL);
        inRow.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = btn("− 5", 0xFF332619, TXT, 16);
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateRaiseUi(raiseVal - 5, amt, sb, confirm, min, max, steps, syncing); }
        });
        Button plus = btn("+ 5", 0xFF332619, TXT, 16);
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateRaiseUi(raiseVal + 5, amt, sb, confirm, min, max, steps, syncing); }
        });
        inRow.addView(minus, lp(dp(66), dp(54)));
        LinearLayout.LayoutParams amp = new LinearLayout.LayoutParams(0, dp(54), 1);
        amp.setMargins(dp(8), 0, dp(8), 0);
        inRow.addView(amt, amp);
        inRow.addView(plus, lp(dp(66), dp(54)));
        LinearLayout.LayoutParams irp = lpw(); irp.setMargins(0, dp(10), 0, 0);
        panel.addView(inRow, irp);

        // slider: tappable as well as draggable; keep the page scroll from stealing its touches
        sb.setMax(steps);
        sb.setProgress(raiseVal >= max ? steps : Math.max(0, (raiseVal - min) / 5));
        sb.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            }
        });
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int prog, boolean user) {
                if (syncing[0]) return;
                int val = (prog >= steps) ? max : min + prog * 5;
                raiseVal = val;
                syncing[0] = true;
                amt.setText(String.valueOf(val));
                confirm.setText(raiseConfirmLabel(val));
                syncing[0] = false;
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams sbp = lpw(); sbp.setMargins(0, dp(6), 0, 0);
        panel.addView(sb, sbp);
        panel.addView(tv("Type the amount, tap the slider where you want it, drag it, or use −/+.", 12, DIM, false));

        // typing in the field drives the slider and the confirm button
        amt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (syncing[0]) return;
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    raiseVal = v;
                    syncing[0] = true;
                    sb.setProgress(v >= max ? steps : Math.max(0, Math.min(steps, (v - min) / 5)));
                    confirm.setText(raiseConfirmLabel(v));
                    syncing[0] = false;
                } catch (Exception ex) { /* mid-typing: validation happens on confirm */ }
            }
        });
        confirm.setText(raiseConfirmLabel(raiseVal));

        // action row
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        confirm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int val = parseNum(amt, raiseVal);
                if (!validateRaise(val, actor)) return;
                raiseOpen = false;
                engine.actRaiseTo(val);
                afterEngineAction();
            }
        });
        Button maxBtn = btn(allIn <= max ? "All-in " + allIn : "Max " + max, 0xFF2C1A08, 0xFFFFB35C, 14);
        maxBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                raiseOpen = false;
                engine.actRaiseTo(max);
                afterEngineAction();
            }
        });
        Button cancel = btn("Cancel", CARD, DIM, 14);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { raiseOpen = false; showTable(); }
        });
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        c2.setMargins(dp(6), 0, 0, 0);
        LinearLayout.LayoutParams c3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        c3.setMargins(dp(6), 0, 0, 0);
        row.addView(confirm, c1); row.addView(maxBtn, c2); row.addView(cancel, c3);
        LinearLayout.LayoutParams rwp = lpw(); rwp.setMargins(0, dp(10), 0, 0);
        panel.addView(row, rwp);
        if (engine.game == Game.OMAHA_BOMB)
            panel.addView(tv("Pot-limit: the app caps your raise at the pot.", 12.5f, 0xFFB79BE0, false));
        return panel;
    }
    String raiseConfirmLabel(int val) {
        return (engine.currentBet == 0 ? "Bet " : "Raise ") + "to " + val + " ✓";
    }
    void updateRaiseUi(int val, EditText amt, SeekBar sb, Button confirm,
                       int min, int max, int steps, boolean[] syncing) {
        raiseVal = clamp(val, min, max);
        syncing[0] = true;
        amt.setText(String.valueOf(raiseVal));
        amt.setSelection(amt.getText().length());
        sb.setProgress(raiseVal >= max ? steps : Math.max(0, (raiseVal - min) / 5));
        confirm.setText(raiseConfirmLabel(raiseVal));
        syncing[0] = false;
    }
    /** enforce the minimum-raise rule on a typed or chosen amount before it reaches the engine */
    boolean validateRaise(int val, Player actor) {
        int fullMin = engine.minRaiseTo(actor);
        int max = engine.maxRaiseTo(actor);
        int allIn = actor.streetBet + actor.stack;
        if (val <= engine.currentBet) {
            toast("A raise has to be above the current bet of " + engine.currentBet);
            return false;
        }
        if (val > max) {
            toast(engine.game == Game.OMAHA_BOMB
                    ? "Pot limit: the most you can raise to is " + max
                    : "You only have " + max + " in front of you");
            return false;
        }
        if (val < fullMin && val != allIn) {
            toast("Minimum raise is to " + fullMin + " (last raise was " + (fullMin - engine.currentBet) + ")");
            return false;
        }
        return true;
    }
    /** lowest target the raise control may use: the full min-raise, or the player's all-in if that is less */
    int raiseFloor(Player p) { return Math.min(engine.minRaiseTo(p), engine.maxRaiseTo(p)); }
    int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    int round5(int v) { return (v / 5) * 5; }

    // ================= SHOWDOWN =================
    void initShowdown() {
        List<Player> seated = engine.bySeat();
        boardWinners = new boolean[engine.effectiveBoards()][seated.size()];
    }
    void showShowdown() {
        screen = "showdown";
        LinearLayout root = col();
        root.addView(topBar("Showdown", "Hand #" + engine.handNumber + " · pot " + TableView.fmt(engine.totalPot())));
        root.addView(tv("Tap the winner of each board. Two or more tapped = chopped board.", 13, DIM, false));
        final List<Player> live = engine.livePlayers();
        final List<Player> seated = engine.bySeat();
        for (int b = 0; b < engine.effectiveBoards(); b++) {
            root.addView(label("Board " + (b + 1)));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackground(roundBg(CARD, 16, BORDER));
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            for (final Player pl : live) {
                final int bi = b;
                final int idx = seated.indexOf(pl);
                boolean sel = boardWinners != null && boardWinners[bi][idx];
                Button chip = btn((sel ? "✓ " : "") + pl.name, sel ? GOLD : 0xFF1A130E,
                        sel ? 0xFF241B02 : TXT, 14);
                chip.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        boardWinners[bi][idx] = !boardWinners[bi][idx];
                        showShowdown();
                    }
                });
                row.addView(chip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            root.addView(row);
        }
        Button award = btn("Award pot · next hand →", GREEN, Color.WHITE, 17);
        LinearLayout.LayoutParams ap = lpw(); ap.setMargins(0, dp(16), 0, 0);
        award.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<Set<Integer>> w = new ArrayList<Set<Integer>>();
                for (int b = 0; b < engine.effectiveBoards(); b++) {
                    Set<Integer> s = new HashSet<Integer>();
                    for (int i = 0; i < seated.size(); i++)
                        if (boardWinners[b][i]) s.add(seated.get(i).seat);
                    if (s.isEmpty()) { toast("Pick a winner for board " + (b + 1)); return; }
                    w.add(s);
                }
                engine.awardShowdown(w);
                toast(engine.lastAwardNote);
                routeAfterHand();
            }
        });
        root.addView(award, ap);
        Button back = btn("← Back to table", CARD, DIM, 13);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTable(); }
        });
        LinearLayout.LayoutParams bp2 = lpw(); bp2.setMargins(0, dp(8), 0, 0);
        root.addView(back, bp2);
        setContentView(page(root));
    }

    // ================= SETTLEMENT =================
    void showSettlement() {
        screen = "settle";
        LinearLayout root = col();
        root.addView(topBar("Session standings", "If you stopped now"));
        LinearLayout c = card();
        for (Player pl : engine.bySeat()) {
            int net = pl.stack - pl.totalIn;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(tv(pl.name, 15.5f, TXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(tv("in " + TableView.fmt(pl.totalIn) + " · stack " + TableView.fmt(pl.stack), 12.5f, DIM, false));
            TextView n = tv((net > 0 ? "+" : "") + TableView.fmt(net), 16, net > 0 ? 0xFF5FE39A : net < 0 ? RED : DIM, true);
            n.setPadding(dp(10), 0, 0, 0);
            row.addView(n);
            row.setPadding(0, dp(7), 0, dp(7));
            c.addView(row);
        }
        root.addView(c);
        root.addView(label("Who pays whom"));
        LinearLayout sc = card();
        List<Engine.Transfer> ts = engine.settlement();
        if (ts.isEmpty()) sc.addView(tv("All square. Nobody owes anybody. 🤝", 14.5f, TXT, false));
        for (Engine.Transfer t : ts) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(tv(t.from + " → " + t.to, 15, TXT, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(tv(TableView.fmt(t.amount), 15, TXT, true));
            row.setPadding(0, dp(6), 0, dp(6));
            sc.addView(row);
        }
        root.addView(sc);
        Button copy = btn("📋 Copy summary", 0xFF332619, TXT, 15);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                StringBuilder sb = new StringBuilder();
                sb.append("Oguri's Chips — session summary\n");
                for (Player pl : engine.bySeat()) {
                    int net = pl.stack - pl.totalIn;
                    sb.append(pl.name).append(": in ").append(pl.totalIn)
                      .append(", stack ").append(pl.stack)
                      .append(", net ").append(net > 0 ? "+" : "").append(net).append("\n");
                }
                sb.append("Settlement:\n");
                for (Engine.Transfer t : engine.settlement()) sb.append(t.toString()).append("\n");
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("summary", sb.toString()));
                toast("Summary copied — send it to Oguri for Settle Up");
            }
        });
        LinearLayout.LayoutParams cp = lpw(); cp.setMargins(0, dp(12), 0, 0);
        root.addView(copy, cp);
        Button back = btn("← Back to the game", CARD, DIM, 13);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPreHand(); }
        });
        LinearLayout.LayoutParams bp3 = lpw(); bp3.setMargins(0, dp(8), 0, 0);
        root.addView(back, bp3);
        Button fresh = btn("Start a brand-new session", 0xFF3A1620, RED, 13);
        fresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("New session?")
                    .setMessage("This clears all stacks, totals and the saved game.")
                    .setPositiveButton("Yes, fresh start", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            prefs().edit().remove("save").apply();
                            engine = new Engine();
                            addDefaultPlayers(engine);
                            showSetup();
                        }
                    }).setNegativeButton("Cancel", null).show();
            }
        });
        LinearLayout.LayoutParams fp = lpw(); fp.setMargins(0, dp(8), 0, 0);
        root.addView(fresh, fp);
        setContentView(page(root));
    }
}
