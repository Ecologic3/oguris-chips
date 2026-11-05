import dev.oguri.chips.engine.Engine;
import dev.oguri.chips.engine.Engine.Game;
import dev.oguri.chips.engine.Engine.Player;
import dev.oguri.chips.engine.Engine.Street;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class EngineTest {
    static int checks = 0, failures = 0;
    static void ok(boolean cond, String msg) {
        checks++;
        if (!cond) { failures++; System.out.println("FAIL: " + msg); }
    }
    static void eq(int a, int b, String msg) { ok(a == b, msg + " (got " + a + ", want " + b + ")"); }

    static Engine fourPlayers(Game g) {
        Engine e = new Engine();
        e.game = g; e.sb = 5; e.bb = 10; e.ante = 25; e.buyin = 500; e.boards = 2;
        e.addPlayer("Anna", 1, 500); e.addPlayer("Boris", 2, 500);
        e.addPlayer("Cecil", 3, 500); e.addPlayer("Dana", 4, 500);
        e.buttonSeat = 4; // so first hand button goes to seat 1
        return e;
    }

    public static void main(String[] args) {
        testHoldemBlindsAndOrder();
        testFirstHandButton();
        testAllInRunout();
        testSettlementCarry();
        testShortAllInNoReopen();
        testMultiBoardSidePotFallback();
        testHoldemMinRaise();
        testHeadsUp();
        testEffectiveBoards();
        testBombPotBasics();
        testPotLimitMax();
        testAwardMath();
        testUndoAndSave();
        testSettlement();
        simulate(Game.HOLDEM, 400, 12345);
        simulate(Game.OMAHA_BOMB, 400, 777);
        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures > 0) System.exit(1);
        System.out.println("ALL TESTS PASSED");
    }

    static void testHoldemBlindsAndOrder() {
        Engine e = fourPlayers(Game.HOLDEM);
        e.startHand();
        eq(e.buttonSeat, 1, "button seat 1");
        eq(e.playerAtSeat(2).streetBet, 5, "SB posts 5");
        eq(e.playerAtSeat(3).streetBet, 10, "BB posts 10");
        eq(e.street == Street.PREFLOP ? 1 : 0, 1, "NLH starts preflop");
        eq(e.toActSeat, 4, "UTG (seat 4) acts first preflop");
        eq(e.totalChipsInSession(), 2000, "chips conserved after blinds");
        // UTG calls, button calls, SB completes, BB checks -> flop, SB first
        e.actCheckCall(); // Dana calls 10
        e.actCheckCall(); // Anna calls
        e.actCheckCall(); // Boris completes
        eq(e.toActSeat, 3, "BB has the option");
        e.actCheckCall(); // BB checks
        ok(e.street == Street.FLOP, "flop after preflop closes");
        eq(e.pot, 40, "pot 40 after preflop");
        eq(e.toActSeat, 2, "SB acts first on flop");
    }

    static void testFirstHandButton() {
        // a fresh session (buttonSeat still 0) must put the first button on the lowest seat
        Engine e = new Engine();
        e.game = Game.HOLDEM; e.sb = 5; e.bb = 10;
        e.addPlayer("Anna", 1, 500); e.addPlayer("Boris", 2, 500);
        e.addPlayer("Cecil", 3, 500); e.addPlayer("Dana", 4, 500);
        e.startHand();
        eq(e.buttonSeat, 1, "first hand of a session: button on seat 1");
    }

    static void testAllInRunout() {
        // everyone all-in except one caller with chips behind: no empty checks,
        // the hand runs straight out to showdown
        Engine e = new Engine();
        e.game = Game.HOLDEM; e.sb = 5; e.bb = 10;
        e.addPlayer("Anna", 1, 500); e.addPlayer("Boris", 2, 500); e.addPlayer("Cecil", 3, 2000);
        e.startHand(); // button seat 1, SB seat 2, BB seat 3, button acts first (3-handed)
        e.actRaiseTo(500);                       // Anna shoves
        eq(e.toActSeat, 2, "SB still gets the call/fold decision facing the shove");
        e.actCheckCall();                        // Boris calls all-in
        e.actCheckCall();                        // Cecil calls, keeps 1500 behind
        ok(e.street == Street.SHOWDOWN, "all-in hand runs out to showdown with no checks asked");
        eq(e.toActSeat, 0, "nobody to act at showdown");
        eq(e.totalPot(), 1500, "pot holds all three 500s");

        // heads-up: BB is all-in just by posting the blind; after SB completes,
        // the hand must run out instead of SB checking down three streets
        Engine h = new Engine();
        h.game = Game.HOLDEM; h.sb = 5; h.bb = 10;
        h.addPlayer("Anna", 1, 100); h.addPlayer("Boris", 2, 8);
        h.startHand(); // button seat 1 posts SB 5, Boris posts BB all-in for 8
        eq(h.toActSeat, 1, "SB still decides whether to complete");
        h.actCheckCall();                        // Anna completes to 8
        ok(h.street == Street.SHOWDOWN, "HU all-in blind: hand runs out at once");
        eq(h.playerAtSeat(1).stack, 92, "SB stack 100 - 8");
        eq(h.totalPot(), 16, "pot is 8 + 8");

        // bomb pot: two players all-in on the flop, big stack calls -> showdown at once
        Engine o = new Engine();
        o.game = Game.OMAHA_BOMB; o.ante = 25; o.boards = 2;
        o.addPlayer("Anna", 1, 100); o.addPlayer("Boris", 2, 100); o.addPlayer("Cecil", 3, 1000);
        o.startHand(); // antes paid, flop betting starts left of the button
        eq(o.toActSeat, 2, "bomb pot: first actor left of button");
        o.actRaiseTo(75);                        // Boris bets the pot, all-in
        o.actCheckCall();                        // Cecil calls
        o.actCheckCall();                        // Anna calls all-in
        ok(o.street == Street.SHOWDOWN, "bomb pot all-ins run out to showdown");
        eq(o.toActSeat, 0, "bomb pot: nobody to act at showdown");
    }

    static void testSettlementCarry() {
        // a chopped pot with odd bets leaves odd chips in the carry; the
        // settlement must hand them to somebody or the books come up short
        Engine e = new Engine();
        e.game = Game.HOLDEM; e.sb = 5; e.bb = 10;
        e.addPlayer("Anna", 1, 500); e.addPlayer("Boris", 2, 500); e.addPlayer("Cecil", 3, 500);
        e.startHand();                    // button 1, SB Boris 5, BB Cecil 10
        e.actRaiseTo(333);                // Anna bets an odd, typed amount
        eq(e.toActSeat, 2, "SB to act after the raise");
        e.actFold();                      // Boris folds
        e.actCheckCall();                 // Cecil calls
        while (e.street != Street.SHOWDOWN) e.actCheckCall();   // check it down
        List<Set<Integer>> w = new ArrayList<Set<Integer>>();
        Set<Integer> s = new HashSet<Integer>(); s.add(1); s.add(3); w.add(s);
        e.awardShowdown(w);               // Anna and Cecil chop
        eq(e.carry, 11, "odd chips from the chop sit in the carry");
        ok(e.carryRecipient() != null && "Anna".equals(e.carryRecipient().name),
                "carry goes to the best net, ties by lowest seat");
        List<Engine.Transfer> ts = e.settlement();
        int paid = 0; for (Engine.Transfer t : ts) paid += t.amount;
        eq(paid, 8, "settlement pays out every lost koruna (5 + 3, carry included)");
        int balanced = 0;
        for (Player p : e.players) balanced += p.stack - p.totalIn + (p == e.carryRecipient() ? e.carry : 0);
        eq(balanced, 0, "nets plus the carry sum to zero");
    }

    static void testShortAllInNoReopen() {
        // a short all-in raise must not reopen the betting for players who
        // already acted on this street; a full raise must reopen it
        Engine e = new Engine();
        e.game = Game.HOLDEM; e.sb = 5; e.bb = 10;
        e.addPlayer("Anna", 1, 500); e.addPlayer("Boris", 2, 500);
        e.addPlayer("Cecil", 3, 500); e.addPlayer("Dana", 4, 160);
        e.buttonSeat = 4; // first hand button -> seat 1
        e.startHand();
        e.actCheckCall(); // Dana (UTG) calls 10, 150 behind
        e.actCheckCall(); // Anna calls
        e.actCheckCall(); // Boris completes
        e.actCheckCall(); // Cecil checks -> flop, Boris first
        ok(e.street == Street.FLOP, "flop reached");
        e.actRaiseTo(100); // Boris bets 100
        e.actCheckCall();  // Cecil calls
        e.actRaiseTo(150); // Dana shoves 150: short raise, increment 50 < 100
        eq(e.toActSeat, 1, "action back to Anna, who has not acted on the flop");
        ok(e.playerAtSeat(1).canRaise, "a player yet to act may still raise");
        e.actFold();       // Anna folds
        e.actCheckCall();  // Boris calls the extra 50
        eq(e.toActSeat, 3, "Cecil faces the short shove");
        ok(!e.playerAtSeat(3).canRaise, "short all-in does not reopen for the earlier caller");
        e.actCheckCall();  // Cecil calls -> turn
        ok(e.street == Street.TURN, "turn reached");
        e.actRaiseTo(100); // Boris bets 100 on the turn
        e.actRaiseTo(300); // Cecil makes a full raise (increment 200 >= 100)
        ok(e.playerAtSeat(2).canRaise, "a full raise reopens the betting");
        e.actCheckCall();  // Boris calls -> river
        ok(e.street == Street.RIVER, "river reached");
    }

    static void testMultiBoardSidePotFallback() {
        // KNOWN COMPROMISE, pinned on purpose: winners are tapped per board,
        // not per side pot. If a board's winner is all-in short and cannot
        // win an upper side-pot layer, that layer's share for the board is
        // split among the players who CAN win it, rather than going to the
        // best hand among them (the app never learns their relative hands).
        Engine e = new Engine();
        e.game = Game.OMAHA_BOMB; e.ante = 25; e.boards = 2;
        e.addPlayer("Xena", 1, 100); e.addPlayer("Yann", 2, 500); e.addPlayer("Zed", 3, 500);
        e.buttonSeat = 3; // first hand button -> seat 1
        e.startHand();                    // antes in, flop betting starts at seat 2
        e.actRaiseTo(75);                 // Yann bets the pot
        e.actCheckCall();                 // Zed calls
        e.actCheckCall();                 // Xena calls all-in (100 total in)
        e.actRaiseTo(300);                // turn: Yann bets 300
        e.actCheckCall();                 // Zed calls
        e.actRaiseTo(100);                // river: Yann all-in (500 total in)
        e.actCheckCall();                 // Zed calls all-in -> showdown
        ok(e.street == Street.SHOWDOWN, "bomb pot reaches showdown");
        List<Set<Integer>> w = new ArrayList<Set<Integer>>();
        Set<Integer> b1 = new HashSet<Integer>(); b1.add(1); w.add(b1); // Xena wins board 1
        Set<Integer> b2 = new HashSet<Integer>(); b2.add(2); w.add(b2); // Yann wins board 2
        e.awardShowdown(w);
        eq(e.playerAtSeat(1).stack, 150, "short stack takes board 1's share of the main pot");
        eq(e.playerAtSeat(2).stack, 750, "board 2 winner plus half the orphaned upper layer");
        eq(e.playerAtSeat(3).stack, 200, "other half of the orphaned upper layer");
        eq(e.totalChipsInSession(), 1100, "chips conserved through the fallback split");
    }

    static void testHoldemMinRaise() {
        Engine e = fourPlayers(Game.HOLDEM);
        e.startHand();
        e.actRaiseTo(30); // UTG raises to 30 (increment 20)
        eq(e.currentBet, 30, "bet 30");
        Player btn = e.playerAtSeat(1);
        eq(e.minRaiseTo(btn), 50, "min re-raise to 50");
        e.actRaiseTo(50);
        eq(e.currentBet, 50, "re-raise to 50 ok");
    }

    static void testHeadsUp() {
        Engine e = new Engine();
        e.game = Game.HOLDEM; e.sb = 5; e.bb = 10;
        e.addPlayer("Anna", 1, 500); e.addPlayer("Dana", 2, 500);
        e.buttonSeat = 2;
        e.startHand();
        eq(e.buttonSeat, 1, "HU button seat 1");
        eq(e.playerAtSeat(1).streetBet, 5, "HU button posts SB");
        eq(e.playerAtSeat(2).streetBet, 10, "HU other posts BB");
        eq(e.toActSeat, 1, "HU button acts first preflop");
        e.actCheckCall(); // SB calls
        eq(e.toActSeat, 2, "BB option");
        e.actCheckCall(); // BB checks
        ok(e.street == Street.FLOP, "HU flop reached");
        eq(e.toActSeat, 2, "HU BB acts first postflop");
    }

    static void testEffectiveBoards() {
        Engine e = fourPlayers(Game.HOLDEM);
        e.boards = 4;
        eq(e.effectiveBoards(), 1, "Hold'em always plays 1 board");
        e.game = Game.OMAHA_BOMB;
        eq(e.effectiveBoards(), 4, "Omaha uses the chosen boards");
    }

    static void testBombPotBasics() {
        Engine e = fourPlayers(Game.OMAHA_BOMB);
        e.startHand();
        ok(e.street == Street.FLOP, "bomb pot starts on the flop, no preflop");
        eq(e.totalPot(), 100, "pot = 4 x ante 25");
        for (Player p : e.players) eq(p.stack, 475, "ante deducted for " + p.name);
        eq(e.toActSeat, 2, "first to act on flop = left of button");
        eq(e.currentBet, 0, "no bet yet on flop");
        eq(e.minRaiseTo(e.toAct()), 25, "Omaha min opening bet = ante (25), not the Hold'em big blind");
        e.actRaiseTo(25); // minimum bet
        eq(e.minRaiseTo(e.toAct()), 50, "next Omaha min raise = bet + ante-sized increment");
    }

    static void testPotLimitMax() {
        Engine e = fourPlayers(Game.OMAHA_BOMB);
        e.startHand(); // pot 100
        Player first = e.toAct();
        eq(e.maxRaiseTo(first), 100, "opening bet max = pot (100)");
        e.actRaiseTo(100); // bet the pot
        Player second = e.toAct();
        // pot now 200, call 100 -> pot after call 300 -> max raise to = 100 + 300 = 400
        eq(e.maxRaiseTo(second), 400, "pot-limit max raise to 400");
        boolean threw = false;
        try { e.actRaiseTo(100000); } catch (Exception ex) { threw = true; }
        eq(e.currentBet, 400, "raise clamped to pot-limit max 400");
    }

    static void testAwardMath() {
        // Crafted: contributions P1 200, P2 200, P3 25 (folded). Pot 425, 2 boards.
        Engine e = new Engine();
        e.game = Game.OMAHA_BOMB; e.boards = 2;
        e.addPlayer("P1", 1, 0); e.addPlayer("P2", 2, 0); e.addPlayer("P3", 3, 0);
        for (Player p : e.players) { p.inHand = true; p.totalIn = 500; }
        e.playerAtSeat(1).handBet = 200;
        e.playerAtSeat(2).handBet = 200;
        e.playerAtSeat(3).handBet = 25; e.playerAtSeat(3).folded = true;
        e.pot = 425; e.handInProgress = true; e.street = Street.SHOWDOWN;
        List<Set<Integer>> w = new ArrayList<Set<Integer>>();
        Set<Integer> b1 = new HashSet<Integer>(); b1.add(1);
        Set<Integer> b2 = new HashSet<Integer>(); b2.add(1); b2.add(2);
        w.add(b1); w.add(b2);
        e.awardShowdown(w);
        eq(e.playerAtSeat(1).stack, 310, "P1 wins 310");
        eq(e.playerAtSeat(2).stack, 100, "P2 wins 100");
        eq(e.carry, 15, "15 carries over");
        eq(e.playerAtSeat(1).stack + e.playerAtSeat(2).stack + e.carry, 425, "award conserves pot");
    }

    static void testUndoAndSave() {
        Engine e = fourPlayers(Game.HOLDEM);
        e.startHand();
        String before = e.encode();
        e.actRaiseTo(40);
        ok(!before.equals(e.encode()), "state changed after raise");
        e.undo();
        ok(before.equals(e.encode()), "undo restores exact prior state");
        // save/load mid-hand
        e.actRaiseTo(40); e.actCheckCall();
        String saved = e.encode();
        Engine e2 = new Engine(); e2.decode(saved);
        ok(saved.equals(e2.encode()), "save/load round-trips exactly");
        eq(e2.totalChipsInSession(), 2000, "chips conserved after load");
    }

    static void testSettlement() {
        Engine e = new Engine();
        e.addPlayer("Anna", 1, 0); e.addPlayer("Boris", 2, 0);
        e.addPlayer("Cecil", 3, 0); e.addPlayer("Dana", 4, 0);
        int[] stacks = {925, 155, 360, 560};
        for (int i = 0; i < 4; i++) { e.players.get(i).stack = stacks[i]; e.players.get(i).totalIn = 500; }
        List<Engine.Transfer> t = e.settlement();
        int paid = 0; for (Engine.Transfer tr : t) paid += tr.amount;
        // nets: Anna +425, Boris -345, Cecil -140, Dana +60 -> debtors pay 485 total
        eq(paid, 485, "debtors pay 485 total");
    }

    /** random legal actions; asserts chip conservation and clean hand completion */
    static void simulate(Game g, int hands, long seed) {
        Random rnd = new Random(seed);
        Engine e = new Engine();
        e.game = g; e.sb = 5; e.bb = 10; e.ante = 25; e.buyin = 500; e.boards = 2;
        String[] names = {"Anna", "Boris", "Cecil", "Dana", "Guest5", "Guest6"};
        int n = 2 + rnd.nextInt(5);
        for (int i = 0; i < n; i++) e.addPlayer(names[i], i + 1, 500);
        int expectedTotal = 500 * n;
        for (int h = 0; h < hands; h++) {
            // rebuy busted players sometimes
            for (Player p : e.players) if (p.stack <= 0) {
                e.rebuy(p, 500); expectedTotal += 500;
            }
            int withChips = 0; for (Player p : e.players) if (p.stack > 0) withChips++;
            if (withChips < 2) break;
            e.startHand();
            int guard = 0;
            while (e.handInProgress && e.street != Street.SHOWDOWN) {
                if (++guard > 5000) { ok(false, "hand stalled in sim " + g); return; }
                Player p = e.toAct();
                if (p == null) { ok(false, "no actor mid-hand " + g); return; }
                ok(p.stack >= 0, "negative stack");
                eq(e.totalChipsInSession(), expectedTotal, "chip conservation mid-hand " + g);
                int r = rnd.nextInt(100);
                if (r < 25) e.actFold();
                else if (r < 70) e.actCheckCall();
                else {
                    int min = e.minRaiseTo(p), max = e.maxRaiseTo(p);
                    if (max <= e.currentBet || !p.canRaise) e.actCheckCall();
                    else {
                        int range = Math.max(1, (max - min) / 5);
                        int to = min + 5 * rnd.nextInt(range + 1);
                        e.actRaiseTo(Math.min(to, max));
                    }
                }
            }
            if (e.handInProgress && e.street == Street.SHOWDOWN) {
                List<Player> live = e.livePlayers();
                List<Set<Integer>> w = new ArrayList<Set<Integer>>();
                for (int b = 0; b < e.boards; b++) {
                    Set<Integer> s = new HashSet<Integer>();
                    s.add(live.get(rnd.nextInt(live.size())).seat);
                    if (rnd.nextInt(4) == 0 && live.size() > 1) s.add(live.get(rnd.nextInt(live.size())).seat);
                    w.add(s);
                }
                e.awardShowdown(w);
            }
            eq(e.totalChipsInSession(), expectedTotal, "chip conservation after hand " + g + " #" + h);
            for (Player p : e.players) ok(p.stack >= 0, "negative stack after hand");
        }
    }
}
