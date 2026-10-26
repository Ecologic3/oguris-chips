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
