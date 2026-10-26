package dev.oguri.chips;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import dev.oguri.chips.engine.Engine;
import dev.oguri.chips.engine.Engine.Player;

import java.util.List;

/** Draws the poker table: felt, seats, placeholder board cards, pot. */
public class TableView extends View {
    private Engine engine;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public static final int[] AVATAR_COLORS = {
        0xFFD9A441, 0xFF29B57F, 0xFF4A86E8, 0xFFE8547C, 0xFF8B5CF6, 0xFF38BDF8
    };

    public TableView(Context c) { super(c); }
    public void setEngine(Engine e) { engine = e; invalidate(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (engine == null) return;
        float W = getWidth(), H = getHeight();
        RectF felt = new RectF(dp(10), dp(26), W - dp(10), H - dp(26));
        // rim
        p.setShader(null); p.setColor(0xFF4A2F1D);
        cv.drawOval(felt, p);
        RectF inner = new RectF(felt.left + dp(13), felt.top + dp(13), felt.right - dp(13), felt.bottom - dp(13));
        p.setShader(new RadialGradient(inner.centerX(), inner.centerY() - dp(30),
                inner.width() * 0.62f, 0xFFA62B3A, 0xFF671623, Shader.TileMode.CLAMP));
        cv.drawOval(inner, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(0x55D9A441);
        cv.drawOval(inner, p);
        p.setStyle(Paint.Style.FILL);

        float cardsBottom = drawCards(cv, inner);
        drawPot(cv, inner, cardsBottom);
        drawSeats(cv, felt);
    }

    private int streetCardCount() {
        if (engine.street == null) return 0;
        switch (engine.street) {
            case FLOP: return 3;
            case TURN: return 4;
            case RIVER: case SHOWDOWN: case COMPLETE: return 5;
            default: return 0;
        }
    }

    private float drawCards(Canvas cv, RectF inner) {
        int boards = engine.effectiveBoards();
        int shown = streetCardCount();
        float cardW = dp(42), cardH = dp(62), gap = dp(5);
        if (boards >= 3) { cardW = dp(34); cardH = dp(54); }
        float rowH = cardH + dp(8);
        float totalH = boards * rowH - dp(8);
        float y0 = inner.centerY() - totalH / 2 - dp(14);
        float rowW = 5 * cardW + 4 * gap;
        float x0 = inner.centerX() - rowW / 2;
        for (int b = 0; b < boards; b++) {
            float y = y0 + b * rowH;
            if (boards > 1) {
                text.setColor(0xAAFFFFFF); text.setTextSize(dp(11)); text.setFakeBoldText(true);
                cv.drawText("B" + (b + 1), x0 - dp(20), y + cardH / 2 + dp(3), text);
            }
            for (int i = 0; i < 5; i++) {
                float x = x0 + i * (cardW + gap);
                RectF r = new RectF(x, y, x + cardW, y + cardH);
                if (i < shown) {
                    p.setColor(0xFF1D3970);
                    drawRoundRect(cv, r, dp(6), p);
                    p.setShader(new LinearGradient(x, y, x + cardW, y + cardH,
                            0xFF2A52A0, 0xFF1D3970, Shader.TileMode.CLAMP));
                    drawRoundRect(cv, r, dp(6), p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE); p.setColor(Color.WHITE); p.setStrokeWidth(dp(2));
                    drawRoundRectStroke(cv, r, dp(6), p);
                    RectF in = new RectF(x + dp(7), y + dp(9), x + cardW - dp(7), y + cardH - dp(9));
                    p.setColor(0x55FFFFFF); p.setStrokeWidth(dp(1.5f));
                    drawRoundRectStroke(cv, in, dp(4), p);
                    p.setStyle(Paint.Style.FILL);
                } else {
                    p.setStyle(Paint.Style.STROKE); p.setColor(0x44FFFFFF); p.setStrokeWidth(dp(1.5f));
                    drawRoundRectStroke(cv, r, dp(6), p);
                    p.setStyle(Paint.Style.FILL);
                    text.setColor(0x66FFFFFF); text.setTextSize(dp(8)); text.setFakeBoldText(true);
                    String lbl = i == 3 ? "TURN" : "RIVER";
                    float tw = text.measureText(lbl);
                    cv.drawText(lbl, x + cardW / 2 - tw / 2, y + cardH - dp(6), text);
                }
            }
        }
        return y0 + totalH;
    }

    private void drawPot(Canvas cv, RectF inner, float cardsBottom) {
        String street = engine.street == null ? "" : engine.street.name();
        String potTxt = "POT  " + fmt(engine.totalPot()) + (street.isEmpty() ? "" : "  ·  " + street);
        text.setColor(Color.WHITE); text.setFakeBoldText(true); text.setTextSize(dp(18));
        float tw = text.measureText(potTxt);
        float top = cardsBottom + dp(10);
        RectF pill = new RectF(inner.centerX() - tw / 2 - dp(16), top,
                inner.centerX() + tw / 2 + dp(16), top + dp(34));
        p.setColor(0xCC140508);
        drawRoundRect(cv, pill, dp(17), p);
        cv.drawText(potTxt, inner.centerX() - tw / 2, top + dp(23), text);
    }

    private void drawSeats(Canvas cv, RectF felt) {
        List<Player> seated = engine.bySeat();
        float cx = felt.centerX(), cy = felt.centerY();
        float rx = felt.width() / 2, ry = felt.height() / 2;
        for (Player pl : seated) {
            if (!pl.inHand && engine.handInProgress) continue;
            double ang = Math.toRadians(angleForSeat(pl.seat));
            float x = cx + (float) (rx * Math.cos(ang));
            float y = cy + (float) (ry * Math.sin(ang));
            // keep the whole bubble inside the view: side seats would otherwise
            // slide off the left/right edges
            float bw0 = dp(100), bh0 = dp(52);
            x = Math.max(bw0 / 2 + dp(1), Math.min(getWidth() - bw0 / 2 - dp(1), x));
            y = Math.max(bh0 / 2 + dp(1), Math.min(getHeight() - bh0 / 2 - dp(1), y));
            boolean active = engine.handInProgress && pl.seat == engine.toActSeat;
            boolean folded = pl.folded;
            float bw = dp(100), bh = dp(52);
            RectF bub = new RectF(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2);
            p.setColor(active ? 0xFF1D1503 : 0xF01D140E);
            if (folded) p.setColor(0x801D140E);
            drawRoundRect(cv, bub, dp(12), p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(active ? 0xFFD9A441 : 0xFF4A3826); p.setStrokeWidth(active ? dp(2.5f) : dp(1.5f));
            drawRoundRectStroke(cv, bub, dp(12), p);
            p.setStyle(Paint.Style.FILL);
            // avatar
            float avR = dp(13);
            float avX = bub.left + dp(20), avY = bub.centerY();
            p.setColor(AVATAR_COLORS[(pl.seat - 1) % AVATAR_COLORS.length]);
            cv.drawCircle(avX, avY, avR, p);
            text.setColor(0xFF08130C); text.setFakeBoldText(true); text.setTextSize(dp(13));
            String ini = pl.name.isEmpty() ? "?" : pl.name.substring(0, 1).toUpperCase();
            cv.drawText(ini, avX - text.measureText(ini) / 2, avY + dp(4.5f), text);
            // name + stack
            text.setTextSize(dp(14.5f)); text.setColor(folded ? 0xFF8A7663 : Color.WHITE);
            String nm = pl.name.isEmpty() ? "Player " + pl.seat : pl.name;
            if (nm.length() > 9) nm = nm.substring(0, 9);
            cv.drawText(nm, bub.left + dp(39), bub.top + dp(21), text);
            text.setTextSize(dp(12.5f)); text.setFakeBoldText(false);
            text.setColor(active ? 0xFFD9A441 : 0xFFCBBBA6);
            String st = folded ? fmt(pl.stack) + " · out" : fmt(pl.stack) + (pl.allIn() ? " · all-in" : "");
            cv.drawText(st, bub.left + dp(39), bub.top + dp(40), text);
            // dealer button
            if (pl.seat == engine.buttonSeat && engine.handInProgress) {
                float dx = bub.right - dp(2), dy = bub.top - dp(2);
                p.setColor(Color.WHITE); cv.drawCircle(dx, dy, dp(10.5f), p);
                text.setColor(Color.BLACK); text.setFakeBoldText(true); text.setTextSize(dp(11));
                cv.drawText("D", dx - text.measureText("D") / 2, dy + dp(4), text);
            }
            // current bet pill, attached to the bubble on the side facing away
            // from the felt centre band, so it can never reach the cards or pot
            if (engine.handInProgress && pl.streetBet > 0) {
                String bet = "bet " + fmt(pl.streetBet);
                text.setTextSize(dp(12.5f)); text.setFakeBoldText(true);
                float btw = text.measureText(bet) + dp(18);
                float bx = x;
                // shift side seats' pills outward, away from the board cards
                if (x < cx - dp(40)) bx = x - dp(12);
                else if (x > cx + dp(40)) bx = x + dp(12);
                float by = (y < cy) ? y + dp(26) + dp(13) : y - dp(26) - dp(13);
                RectF bp = new RectF(bx - btw / 2, by - dp(13), bx + btw / 2, by + dp(13));
                p.setColor(0xDD000000); drawRoundRect(cv, bp, dp(13), p);
                p.setStyle(Paint.Style.STROKE); p.setColor(0x88D9A441); p.setStrokeWidth(dp(1));
                drawRoundRectStroke(cv, bp, dp(13), p); p.setStyle(Paint.Style.FILL);
                text.setColor(0xFFEFC06A);
                cv.drawText(bet, bx - text.measureText(bet) / 2, by + dp(4.5f), text);
            }
        }
    }

    private double angleForSeat(int seat) {
        // screen coords: 90 = bottom, 270 = top
        switch (seat) {
            case 1: return 90;
            case 2: return 150;
            case 3: return 205;
            case 4: return 270;
            case 5: return 335;
            default: return 25;
        }
    }

    private void drawRoundRect(Canvas cv, RectF r, float rad, Paint paint) { cv.drawRoundRect(r, rad, rad, paint); }
    private void drawRoundRectStroke(Canvas cv, RectF r, float rad, Paint paint) { cv.drawRoundRect(r, rad, rad, paint); }

    public static String fmt(int v) {
        String s = String.valueOf(Math.abs(v));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) out.append(' ');
            out.append(s.charAt(i));
        }
        return (v < 0 ? "−" : "") + out.toString();
    }
}
