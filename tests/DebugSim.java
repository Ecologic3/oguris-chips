import dev.oguri.chips.engine.Engine;
import dev.oguri.chips.engine.Engine.Game;
import dev.oguri.chips.engine.Engine.Player;
import dev.oguri.chips.engine.Engine.Street;
import java.util.*;

public class DebugSim {
    public static void main(String[] args) {
        Random rnd = new Random(12345);
        Engine e = new Engine();
        e.game = Game.HOLDEM; e.sb = 5; e.bb = 10; e.ante = 25; e.buyin = 500; e.boards = 2;
        String[] names = {"Anna", "Boris", "Cecil", "Dana", "Guest5", "Guest6"};
        int n = 2 + rnd.nextInt(5);
        for (int i = 0; i < n; i++) e.addPlayer(names[i], i + 1, 500);
        int expectedTotal = 500 * n;
        System.out.println("players=" + n);
        for (int h = 0; h < 30; h++) {
            for (Player p : e.players) if (p.stack <= 0 && rnd.nextBoolean()) {
                e.rebuy(p, 500); expectedTotal += 500;
                System.out.println("rebuy " + p.name);
            }
            int withChips = 0; for (Player p : e.players) if (p.stack > 0) withChips++;
            if (withChips < 2) break;
            e.startHand();
            System.out.println("--- hand " + h + " button seat " + e.buttonSeat + " total " + e.totalChipsInSession());
            int guard = 0;
            while (e.handInProgress && e.street != Street.SHOWDOWN) {
                if (++guard > 5000) { System.out.println("STALL"); return; }
                Player p = e.toAct();
                int before = e.totalChipsInSession();
                int r = rnd.nextInt(100);
                String act;
                if (r < 25) { e.actFold(); act = "fold"; }
                else if (r < 70) { e.actCheckCall(); act = "check/call"; }
                else {
                    int min = e.minRaiseTo(p), max = e.maxRaiseTo(p);
                    if (max <= e.currentBet || !p.canRaise) { e.actCheckCall(); act = "check/call(noraise)"; }
                    else {
                        int range = Math.max(1, (max - min) / 5);
                        int to = min + 5 * rnd.nextInt(range + 1);
                        e.actRaiseTo(Math.min(to, max)); act = "raiseTo " + Math.min(to, max);
                    }
                }
                int after = e.totalChipsInSession();
                if (h == 23) System.out.println("  " + p.name + " " + act + " street=" + e.street + " pot=" + e.totalPot() + " hb: A" + e.playerAtSeat(1).handBet + " D" + e.playerAtSeat(2).handBet + " F" + e.playerAtSeat(3).handBet + " sb: A" + e.playerAtSeat(1).streetBet + " D" + e.playerAtSeat(2).streetBet + " F" + e.playerAtSeat(3).streetBet + " folded: A" + e.playerAtSeat(1).folded + " D" + e.playerAtSeat(2).folded + " F" + e.playerAtSeat(3).folded);
                if (after != before) System.out.println("  !! total changed by " + act + " of " + p.name + ": " + before + " -> " + after + " street " + e.street);
                if (after != expectedTotal) System.out.println("  action " + act + " " + p.name + " total=" + after);
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
                int before = e.totalChipsInSession();
                e.awardShowdown(w);
                System.out.println("  award: " + before + " -> " + e.totalChipsInSession() + " note: " + e.lastAwardNote + " carry=" + e.carry);
            }
            System.out.println("hand " + h + " end total=" + e.totalChipsInSession() + " expected=" + expectedTotal);
            if (e.totalChipsInSession() != expectedTotal) {
                for (Player p : e.players) System.out.println("   " + p.name + " stack=" + p.stack + " in=" + p.totalIn + " handBet=" + p.handBet);
                return;
            }
        }
    }
}
