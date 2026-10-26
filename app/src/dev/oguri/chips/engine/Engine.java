package dev.oguri.chips.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Poker chip engine for Oguri's Chips.
 * Two games:
 *  - HOLDEM: classical No-Limit Hold'em (blinds, preflop, min-raise rules).
 *  - OMAHA_BOMB: Omaha bomb pots (everyone antes, NO blinds, NO preflop;
 *    betting starts on the flop, pot-limit, multiple boards split the pot).
 * All amounts are whole Kc, bets are quantised to multiples of 5 by the UI;
 * the engine itself works with any positive integers.
 * The engine never sees cards: winners are declared by the players.
 */
public class Engine {

    public enum Game { HOLDEM, OMAHA_BOMB }
    public enum Street { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN, COMPLETE }

    public static class Player {
        public String name;
        public int seat;            // 1..6, unique
        public int stack;
        public int totalIn;         // total buy-ins this session
        public boolean inHand;
        public boolean folded;
        public int streetBet;       // put in on the current street
        public int handBet;         // put in during the whole hand
        public boolean acted;       // has acted since last full raise / street start
        public boolean canRaise;    // betting not closed for this player on this street

        public Player(String name, int seat) { this.name = name; this.seat = seat; }

        public Player copy() {
            Player p = new Player(name, seat);
            p.stack = stack; p.totalIn = totalIn; p.inHand = inHand; p.folded = folded;
            p.streetBet = streetBet; p.handBet = handBet; p.acted = acted; p.canRaise = canRaise;
            return p;
        }
        public boolean allIn() { return inHand && !folded && stack == 0; }
    }

    // ---- settings ----
    public Game game = Game.HOLDEM;
    public int sb = 5, bb = 10, ante = 25, buyin = 500, boards = 2;

    // ---- session ----
    public final ArrayList<Player> players = new ArrayList<Player>();
    public int handNumber = 0;
    public int buttonSeat = 0;      // seat number of the button, 0 = none yet
    public int carry = 0;           // leftover carried into the next pot

    // ---- current hand ----
    public boolean handInProgress = false;
    public Street street = Street.COMPLETE;
    public int pot = 0;             // collected pot (previous streets + antes + carry-in)
    public int currentBet = 0;      // highest streetBet on this street
    public int lastRaise = 10;      // size of last full raise increment (min-raise unit)
    public int toActSeat = 0;       // seat to act, 0 = nobody

    private final ArrayList<String> undoStack = new ArrayList<String>();

    // ================= helpers =================
    public List<Player> bySeat() {
        ArrayList<Player> l = new ArrayList<Player>(players);
        Collections.sort(l, new Comparator<Player>() {
            public int compare(Player a, Player b) { return a.seat - b.seat; }
        });
        return l;
    }
    public Player playerAtSeat(int seat) {
        for (Player p : players) if (p.seat == seat) return p;
        return null;
    }
    public int totalPot() {
        int t = pot;
        for (Player p : players) if (p.inHand) t += p.streetBet;
        return t;
    }
    public int totalChipsInSession() {
        int t = carry + totalPot();
        for (Player p : players) t += p.stack;
        // street bets are still part of stacks accounting: stacks were already
        // reduced when bets were placed, and totalPot includes them.
        return t;
    }
    public int totalBuyins() {
        int t = 0; for (Player p : players) t += p.totalIn; return t;
    }
    public List<Player> livePlayers() {   // in hand and not folded
        ArrayList<Player> l = new ArrayList<Player>();
        for (Player p : bySeat()) if (p.inHand && !p.folded) l.add(p);
        return l;
    }
    private List<Player> actionablePlayers() {
        ArrayList<Player> l = new ArrayList<Player>();
        for (Player p : bySeat()) if (p.inHand && !p.folded && p.stack > 0) l.add(p);
        return l;
    }
    /** next seat (in seat order) strictly after 'seat' satisfying a cyclic scan */
    private Player nextInOrder(int afterSeat, List<Player> set) {
        if (set.isEmpty()) return null;
        List<Player> s = bySeat();
        int start = 0;
        for (int i = 0; i < s.size(); i++) if (s.get(i).seat == afterSeat) { start = i; break; }
        for (int k = 1; k <= s.size(); k++) {
            Player cand = s.get((start + k) % s.size());
            if (set.contains(cand)) return cand;
        }
        return null;
    }

    // ================= undo =================
    public void pushUndo() {
        undoStack.add(encode());
        if (undoStack.size() > 300) undoStack.remove(0);
    }
    public boolean canUndo() { return !undoStack.isEmpty(); }
    public void undo() {
        if (undoStack.isEmpty()) return;
        String s = undoStack.remove(undoStack.size() - 1);
        decode(s);
    }
    public void clearUndo() { undoStack.clear(); }

    /** boards actually in play: Hold'em is always a single board */
    public int effectiveBoards() { return game == Game.HOLDEM ? 1 : Math.max(1, boards); }

    // ================= session ops =================
    public void addPlayer(String name, int seat, int stack) {
        Player p = new Player(name, seat);
        p.stack = stack; p.totalIn = stack;
        players.add(p);
    }
    public void rebuy(Player p, int amount) {
        pushUndo();
        p.stack += amount;
        p.totalIn += amount;
    }

    // ================= hand lifecycle =================
    public void startHand() {
        pushUndo();
        List<Player> seated = bySeat();
        // choose participants: everyone with chips
        int participants = 0;
        for (Player p : seated) if (p.stack > 0) participants++;
        if (participants < 2) throw new IllegalStateException("Need at least 2 players with chips");
        handNumber++;
        // rotate button to next participant
        Player btn = nextInOrder(buttonSeat, participantsList());
        if (btn == null) btn = participantsList().get(0);
        buttonSeat = btn.seat;
        for (Player p : seated) {
            p.inHand = p.stack > 0;
            p.folded = false; p.streetBet = 0; p.handBet = 0; p.acted = false; p.canRaise = true;
        }
        pot = carry; carry = 0;
        currentBet = 0; lastRaise = minBetUnit();
        handInProgress = true;
        if (game == Game.HOLDEM) {
            street = Street.PREFLOP;
            postBlinds();
        } else {
            // bomb pot: everyone antes, no blinds, flop is dealt, betting starts there
            for (Player p : seated) if (p.inHand) {
                int a = Math.min(ante, p.stack);
                moveToPot(p, a);
            }
            // antes go straight into the pot; flop betting starts clean
            for (Player p : seated) if (p.inHand) { pot += p.streetBet; p.streetBet = 0; }
            street = Street.FLOP;
            beginStreet(firstActorPostflop());
        }
    }

    private List<Player> participantsList() {
        ArrayList<Player> l = new ArrayList<Player>();
        for (Player p : bySeat()) if (p.stack > 0) l.add(p);
        return l;
    }

    private void moveToPot(Player p, int amt) {
        p.stack -= amt; p.streetBet += amt; p.handBet += amt;
    }

    private void postBlinds() {
        List<Player> parts = participantsList();
        Player sbP, bbP;
        if (parts.size() == 2) {           // heads-up: button posts SB
            sbP = playerAtSeat(buttonSeat);
            bbP = nextInOrder(buttonSeat, parts);
        } else {
            sbP = nextInOrder(buttonSeat, parts);
            bbP = nextInOrder(sbP.seat, parts);
        }
        int sbAmt = Math.min(sb, sbP.stack);
        moveToPot(sbP, sbAmt);
        int bbAmt = Math.min(bb, bbP.stack);
        moveToPot(bbP, bbAmt);
        currentBet = Math.max(sbAmt, bbAmt);
        lastRaise = bb;
        Player first;
        if (parts.size() == 2) first = sbP;                    // button acts first preflop HU
        else first = nextInOrder(bbP.seat, actionablePlayers());
        beginStreet(first);
    }

    private Player firstActorPostflop() {
        return nextInOrder(buttonSeat, actionablePlayers());
    }

    /** set up action for a fresh street */
    private void beginStreet(Player first) {
        if (first == null) { advanceStreet(); return; }
        toActSeat = first.seat;
        settleActionPointer();
    }

    /** If the player to act cannot/needs not act, move on or close the street. */
    private void settleActionPointer() {
        while (handInProgress && street != Street.SHOWDOWN && street != Street.COMPLETE) {
            if (livePlayers().size() <= 1) { finishByFolds(); return; }
            Player cur = playerAtSeat(toActSeat);
            if (cur != null && needsAction(cur)) return;
            // does anyone still need to act?
            Player nxt = nextNeedingAction(toActSeat);
            if (nxt == null) { advanceStreet(); }
            else toActSeat = nxt.seat;
        }
    }

    private boolean needsAction(Player p) {
        return p.inHand && !p.folded && p.stack > 0 && (!p.acted || p.streetBet < currentBet);
    }

    private Player nextNeedingAction(int afterSeat) {
        List<Player> s = bySeat();
        int start = -1;
        for (int i = 0; i < s.size(); i++) if (s.get(i).seat == afterSeat) { start = i; break; }
        for (int k = 1; k <= s.size(); k++) {
            Player cand = s.get((start + k) % s.size());
            if (needsAction(cand)) return cand;
        }
        return null;
    }

    /** return the part of a bet nobody called (vs. the best other live bet) */
    private void returnUncalled() {
        for (Player p : players) {
            if (!p.inHand || p.folded || p.streetBet == 0) continue;
            int maxOther = 0;
            // a bet counts as called up to the biggest other bet on this street,
            // including bets of players who folded later on the same street
            for (Player q : players)
                if (q != p && q.inHand) maxOther = Math.max(maxOther, q.streetBet);
            int excess = p.streetBet - maxOther;
            if (excess > 0) {
                p.stack += excess; p.streetBet -= excess; p.handBet -= excess;
            }
        }
    }

    private void advanceStreet() {
        returnUncalled();
        // collect street bets into the pot
        for (Player p : players) if (p.inHand) { pot += p.streetBet; p.streetBet = 0; }
        currentBet = 0; lastRaise = minBetUnit();
        for (Player p : players) { p.acted = false; p.canRaise = true; }
        switch (street) {
            case PREFLOP: street = Street.FLOP; break;
            case FLOP: street = Street.TURN; break;
            case TURN: street = Street.RIVER; break;
            case RIVER: street = Street.SHOWDOWN; toActSeat = 0; return;
            default: street = Street.SHOWDOWN; toActSeat = 0; return;
        }
        // if nobody can act (everyone all-in / one stack left), run it out
        Player first = firstActorPostflop();
        if (first == null) { advanceStreet(); return; }
        toActSeat = first.seat;
        settleActionPointer();
    }

    // ================= actions =================
    public Player toAct() { return toActSeat == 0 ? null : playerAtSeat(toActSeat); }

    public int callAmount(Player p) {
        return Math.min(Math.max(0, currentBet - p.streetBet), p.stack);
    }
    /** the minimum opening bet / raise unit of the current game:
        the big blind in Hold'em, the ante in Omaha bomb pots */
    public int minBetUnit() { return game == Game.OMAHA_BOMB ? (ante > 0 ? ante : bb) : bb; }
    public int minRaiseTo(Player p) {
        if (currentBet == 0) return Math.min(minBetUnit(), p.streetBet + p.stack);
        return currentBet + lastRaise;
    }
    /** maximum total street bet this player may make */
    public int maxRaiseTo(Player p) {
        int allIn = p.streetBet + p.stack;
        if (game == Game.HOLDEM) return allIn;
        // pot-limit: may raise by the pot as it stands after the call
        int c = callAmount(p);
        int potAfterCall = totalPot() + c;
        return Math.min(allIn, currentBet + potAfterCall);
    }
    public boolean canCheck(Player p) { return p.streetBet >= currentBet; }

    public void actFold() {
        Player p = requireActor();
        pushUndo();
        p.folded = true; p.acted = true;
        afterAction(p);
    }
    public void actCheckCall() {
        Player p = requireActor();
        pushUndo();
        int c = callAmount(p);
        if (c > 0) moveToPot(p, c);
        p.acted = true;
        afterAction(p);
    }
    /** raise so that the player's total street bet becomes raiseTo */
    public void actRaiseTo(int raiseTo) {
        Player p = requireActor();
        int max = maxRaiseTo(p);
        if (raiseTo > max) raiseTo = max;
        int min = minRaiseTo(p);
        boolean allIn = raiseTo >= p.streetBet + p.stack;
        if (raiseTo < min && !allIn) raiseTo = min;
        if (raiseTo <= currentBet) { actCheckCall(); return; }
        pushUndo();
        int add = raiseTo - p.streetBet;
        moveToPot(p, add);
        int increment = raiseTo - currentBet;
        boolean fullRaise = increment >= lastRaise || currentBet == 0;
        currentBet = raiseTo;
        if (fullRaise) {
            lastRaise = increment;
            for (Player q : players) if (q != p && q.inHand && !q.folded) {
                q.acted = false; q.canRaise = true;
            }
        }
        p.acted = true; p.canRaise = false;
        afterAction(p);
    }

    private Player requireActor() {
        Player p = toAct();
        if (p == null) throw new IllegalStateException("Nobody to act");
        return p;
    }

    private void afterAction(Player p) {
        if (livePlayers().size() <= 1) { finishByFolds(); return; }
        Player nxt = nextNeedingAction(p.seat);
        if (nxt == null) { advanceStreet(); return; }
        toActSeat = nxt.seat;
        settleActionPointer();
    }

    /** hand ends because all but one folded: last player wins, uncalled excess returned */
    private void finishByFolds() {
        List<Player> live = livePlayers();
        if (live.size() != 1) return;
        Player w = live.get(0);
        // collect street bets
        for (Player p : players) if (p.inHand) { pot += p.streetBet; p.streetBet = 0; }
        // return uncalled excess of the winner's hand contribution
        int maxOther = 0;
        for (Player p : players) if (p != w && p.inHand) maxOther = Math.max(maxOther, p.handBet);
        int excess = Math.max(0, w.handBet - maxOther);
        // excess is part of pot right now (winner's chips); award pot minus excess, return excess
        w.stack += pot; // winner receives the whole pot incl. his own bets...
        // ...of which 'excess' is simply his own money back; accounting identical.
        pot = 0;
        street = Street.COMPLETE;
        handInProgress = false;
        toActSeat = 0;
        lastAwardNote = w.name + " wins (everyone else folded)";
    }

    public String lastAwardNote = "";

    // ================= showdown award =================
    /**
     * Award the pot at showdown.
     * winnersPerBoard: for each board, the seats of its winners (1+; 2+ = chopped board).
     * Side pots are computed from hand contributions. Each board's share of
     * each side pot is floored to a multiple of 5; every remainder goes to
     * the carry for the next hand.
     */
    public void awardShowdown(List<Set<Integer>> winnersPerBoard) {
        final int B = effectiveBoards();
        pushUndo();
        // collect any remaining street bets
        for (Player p : players) if (p.inHand) { pot += p.streetBet; p.streetBet = 0; }
        int totalToAward = pot;
        // build side-pot layers from hand contributions
        TreeSet<Integer> levels = new TreeSet<Integer>();
        for (Player p : players) if (p.inHand && p.handBet > 0) levels.add(p.handBet);
        int carryOut = 0;
        int prev = 0;
        StringBuilder note = new StringBuilder();
        for (int level : levels) {
            int contributors = 0;
            ArrayList<Player> eligible = new ArrayList<Player>();
            for (Player p : players) if (p.inHand && p.handBet >= level) {
                contributors++;
                if (!p.folded) eligible.add(p);
            }
            int layerAmt = (level - prev) * contributors;
            prev = level;
            if (layerAmt == 0) continue;
            if (eligible.isEmpty()) { carryOut += layerAmt; continue; } // safety: never destroy chips
            int perBoard = (layerAmt / B / 5) * 5;
            carryOut += layerAmt - perBoard * B;
            for (int b = 0; b < B; b++) {
                Set<Integer> w = b < winnersPerBoard.size() ? winnersPerBoard.get(b) : null;
                ArrayList<Player> winners = new ArrayList<Player>();
                for (Player e : eligible) if (w != null && w.contains(e.seat)) winners.add(e);
                if (winners.isEmpty()) winners = eligible; // safety: should not happen via UI
                int share = winners.isEmpty() ? 0 : (perBoard / winners.size() / 5) * 5;
                carryOut += perBoard - share * winners.size();
                for (Player win : winners) {
                    win.stack += share;
                    if (share > 0) note.append(win.name).append(" +").append(share).append("  ");
                }
            }
        }
        // seed money in the pot that is not part of anyone's hand contribution
        // (the carry-in from the previous hand) is split the same way
        int contributed = 0;
        for (Player p : players) if (p.inHand) contributed += p.handBet;
        int seed = totalToAward - contributed;
        if (seed > 0) {
            ArrayList<Player> eligible = new ArrayList<Player>();
            for (Player p : players) if (p.inHand && !p.folded) eligible.add(p);
            int perBoard = (seed / B / 5) * 5;
            carryOut += seed - perBoard * B;
            for (int b = 0; b < B; b++) {
                Set<Integer> w = b < winnersPerBoard.size() ? winnersPerBoard.get(b) : null;
                ArrayList<Player> winners = new ArrayList<Player>();
                for (Player e : eligible) if (w != null && w.contains(e.seat)) winners.add(e);
                if (winners.isEmpty()) winners = eligible;
                int share = winners.isEmpty() ? 0 : (perBoard / winners.size() / 5) * 5;
                carryOut += perBoard - share * winners.size();
                for (Player win : winners) win.stack += share;
            }
        }
        pot = 0;
        carry = carryOut;
        street = Street.COMPLETE;
        handInProgress = false;
        toActSeat = 0;
        lastAwardNote = note.toString().trim() + (carryOut > 0 ? " · " + carryOut + " carries over" : "");
    }

    // ================= settlement =================
    public static class Transfer {
        public final String from, to; public final int amount;
        public Transfer(String f, String t, int a) { from = f; to = t; amount = a; }
        public String toString() { return from + " → " + to + " " + amount; }
    }
    public List<Transfer> settlement() {
        ArrayList<Player> debtors = new ArrayList<Player>(), creditors = new ArrayList<Player>();
        java.util.Map<Player, Integer> net = new java.util.HashMap<Player, Integer>();
        for (Player p : players) {
            int n = p.stack - p.totalIn;
            net.put(p, n);
            if (n < 0) debtors.add(p); else if (n > 0) creditors.add(p);
        }
        ArrayList<Transfer> out = new ArrayList<Transfer>();
        java.util.Map<Player, Integer> dLeft = new java.util.HashMap<Player, Integer>();
        java.util.Map<Player, Integer> cLeft = new java.util.HashMap<Player, Integer>();
        for (Player d : debtors) dLeft.put(d, -net.get(d));
        for (Player c : creditors) cLeft.put(c, net.get(c));
        for (Player d : debtors) {
            for (Player c : creditors) {
                int pay = Math.min(dLeft.get(d), cLeft.get(c));
                if (pay > 0) {
                    out.add(new Transfer(d.name, c.name, pay));
                    dLeft.put(d, dLeft.get(d) - pay);
                    cLeft.put(c, cLeft.get(c) - pay);
                }
            }
        }
        return out;
    }

    // ================= save / load =================
    public String encode() {
        StringBuilder out = new StringBuilder();
        out.append("V1|").append(game.name()).append('|').append(sb).append('|').append(bb)
          .append('|').append(ante).append('|').append(buyin).append('|').append(boards)
          .append('|').append(handNumber).append('|').append(buttonSeat).append('|').append(carry)
          .append('|').append(handInProgress ? 1 : 0).append('|').append(street.name())
          .append('|').append(pot).append('|').append(currentBet).append('|').append(lastRaise)
          .append('|').append(toActSeat);
        for (Player p : players) {
            out.append("|P,").append(escape(p.name)).append(',').append(p.seat).append(',')
              .append(p.stack).append(',').append(p.totalIn).append(',')
              .append(p.inHand ? 1 : 0).append(',').append(p.folded ? 1 : 0).append(',')
              .append(p.streetBet).append(',').append(p.handBet).append(',')
              .append(p.acted ? 1 : 0).append(',').append(p.canRaise ? 1 : 0);
        }
        return out.toString();
    }

    public void decode(String s) {
        String[] tok = s.split("\\|", -1);
        if (tok.length < 16 || !"V1".equals(tok[0])) throw new IllegalArgumentException("bad save");
        int i = 1;
        game = Game.valueOf(tok[i++]);
        sb = Integer.parseInt(tok[i++]); bb = Integer.parseInt(tok[i++]);
        ante = Integer.parseInt(tok[i++]); buyin = Integer.parseInt(tok[i++]);
        boards = Integer.parseInt(tok[i++]);
        handNumber = Integer.parseInt(tok[i++]); buttonSeat = Integer.parseInt(tok[i++]);
        carry = Integer.parseInt(tok[i++]);
        handInProgress = "1".equals(tok[i++]);
        street = Street.valueOf(tok[i++]);
        pot = Integer.parseInt(tok[i++]); currentBet = Integer.parseInt(tok[i++]);
        lastRaise = Integer.parseInt(tok[i++]); toActSeat = Integer.parseInt(tok[i++]);
        players.clear();
        for (; i < tok.length; i++) {
            if (!tok[i].startsWith("P,")) continue;
            String[] f = tok[i].substring(2).split(",", -1);
            Player p = new Player(unescape(f[0]), Integer.parseInt(f[1]));
            p.stack = Integer.parseInt(f[2]); p.totalIn = Integer.parseInt(f[3]);
            p.inHand = "1".equals(f[4]); p.folded = "1".equals(f[5]);
            p.streetBet = Integer.parseInt(f[6]); p.handBet = Integer.parseInt(f[7]);
            p.acted = "1".equals(f[8]); p.canRaise = "1".equals(f[9]);
            players.add(p);
        }
    }

    private static String escape(String s) {
        return s.replace("%", "%25").replace(",", "%2C").replace("|", "%7C");
    }
    private static String unescape(String s) {
        return s.replace("%7C", "|").replace("%2C", ",").replace("%25", "%");
    }
}
