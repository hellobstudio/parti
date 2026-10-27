import hu.partytime.core.Game;
import hu.partytime.core.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain-JVM sanity tests of the game logic (run in CI before the APK build). */
public class CoreTest {
    static void check(boolean c, String msg) {
        if (!c) throw new AssertionError(msg);
        System.out.println("OK  " + msg);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sec(Game g, String s, String key) {
        return (Map<String, Object>) g.view(s, "").get(key);
    }

    public static void main(String[] a) {
        Game g = new Game("GM");
        String host = g.hostSecret();
        List<String> s = new ArrayList<>();
        for (int i = 0; i < 11; i++) s.add((String) g.join("P" + i).get("secret"));

        // duplicate names are rejected while the player is online
        boolean dup = false;
        try { g.join("p0"); } catch (Game.GameException e) { dup = true; }
        check(dup, "duplicate name rejected");

        // secret missions are unique
        g.action(host, Json.map("type", "missionStart"));
        Set<Object> titles = new HashSet<>();
        for (String x : s) titles.add(((Map<?, ?>) sec(g, x, "mission").get("my")).get("title"));
        titles.add(((Map<?, ?>) sec(g, host, "mission").get("my")).get("title"));
        check(titles.size() == 12, "12 players got 12 different missions");

        // completing a mission
        String p1pid = (String) ((Map<?, ?>) g.view(s.get(1), "").get("me")).get("pid");
        List<Object> targets = new ArrayList<>();
        targets.add(p1pid);
        g.action(s.get(0), Json.map("type", "missionComplete", "targets", targets, "note", "x"));
        check(sec(g, s.get(0), "mission").get("my") == null, "completed mission cleared");
        check(((List<?>) sec(g, s.get(0), "mission").get("mine")).size() == 1, "completion recorded");
        check(((List<?>) g.view(s.get(1), "").get("inbox")).size() == 1, "target notified");
        g.action(s.get(0), Json.map("type", "missionRequest"));
        check(sec(g, s.get(0), "mission").get("my") != null, "new mission requested");

        // TOP10 with 12 people: host sits out, one more player sits out, numbers unique 1..10
        g.action(host, Json.map("type", "top10Deal"));
        Set<Object> nums = new HashSet<>();
        int none = 0;
        for (String x : s) {
            Object n = sec(g, x, "top10").get("number");
            if (n == null) none++; else nums.add(n);
        }
        check(sec(g, host, "top10").get("number") == null, "host excluded from TOP10 when >10 players");
        check(nums.size() == 10 && none == 1, "10 unique numbers, 1 player sitting out");

        // Activity
        g.action(host, Json.map("type", "shuffleTeams"));
        for (int i = 0; i < s.size(); i++) g.action(s.get(i), Json.map("type", "riddleAdd", "text", "R" + i, "cat", "animal"));
        g.action(host, Json.map("type", "actNext", "team", "blue"));
        Map<String, Object> r = (Map<String, Object>) sec(g, host, "activity").get("round");
        check("ready".equals(r.get("phase")), "round ready");
        String perfPid = (String) r.get("performer");
        String perfSecret = null;
        for (String x : s) if (((Map<?, ?>) g.view(x, "").get("me")).get("pid").equals(perfPid)) perfSecret = x;
        if (perfSecret == null) perfSecret = host;
        Map<String, Object> pr = (Map<String, Object>) sec(g, perfSecret, "activity").get("round");
        check(pr.get("text") != null, "performer sees the riddle");
        g.action(perfSecret, Json.map("type", "actStart"));
        g.action(perfSecret, Json.map("type", "actResult", "guessed", true));
        Map<String, Object> sc = (Map<String, Object>) sec(g, host, "activity").get("scores");
        check(((Number) sc.get("blue")).intValue() == 1, "blue scored");

        // persistence round-trip
        String js = g.toJson();
        check(js.equals(Game.fromJson(js).toJson()), "save/restore round-trip");
        System.out.println("All core tests passed.");
    }
}
