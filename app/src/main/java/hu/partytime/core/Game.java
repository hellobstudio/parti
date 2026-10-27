package hu.partytime.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The whole party state. All public methods are synchronized; the HTTP server
 * calls them from many threads.
 */
public final class Game {

    public static final class GameException extends RuntimeException {
        public GameException(String msg) { super(msg); }
    }

    static final long ONLINE_MS = 15000;
    static final String BLUE = "blue", RED = "red";

    private final Random rnd = new SecureRandom();

    // ------------------------------------------------------------------ players
    static final class Player {
        String pid, secret, name, team = "";
        boolean host;
        long lastSeen, joinedAt;
        int performed;
        final List<String> inbox = new ArrayList<>();
    }

    private final LinkedHashMap<String, Player> players = new LinkedHashMap<>();
    private final Map<String, Player> bySecret = new HashMap<>();
    private Player hostPlayer;

    // ----------------------------------------------------------------- missions
    static final class Completion {
        String pid;
        int mission;
        List<String> targets = new ArrayList<>();
        String note = "";
        long time;
    }

    private final List<String[]> missions = new ArrayList<>();
    private boolean missionActive;
    private final Set<Integer> usedMissions = new HashSet<>();
    private final Map<String, Integer> currentMission = new HashMap<>();
    private final List<Completion> completions = new ArrayList<>();
    private boolean missionsRevealed;

    // ----------------------------------------------------------------- activity
    static final class Riddle {
        int id;
        String author, text, cat;
        boolean used;
    }

    private final List<Riddle> riddles = new ArrayList<>();
    private int nextRiddleId = 1;
    private int scoreBlue, scoreRed;
    private String nextTeam = BLUE;
    private int roundSeq;
    private String rState = "idle"; // idle | ready | running | done
    private String rTeam = "", rPerformer = "", rMethod = "", rResult = "";
    private int rRiddle = -1;
    private long rStart;
    private int durationSec = 60;
    private boolean useMethods = true;
    private final List<Map<String, Object>> history = new ArrayList<>();

    // -------------------------------------------------------------------- top10
    private int tRound;
    private final Map<String, Integer> tNumbers = new HashMap<>();
    private final Set<String> tParticipants = new HashSet<>();
    private boolean tHostOut;
    private String tTopic = "";
    private boolean tRevealed;
    private final Set<Integer> usedTopics = new HashSet<>();

    // ------------------------------------------------------------------- misc
    private Runnable onChange;

    public Game(String hostName) {
        for (String[] m : Content.MISSIONS) missions.add(m);
        hostPlayer = newPlayer(cleanName(hostName));
        hostPlayer.host = true;
    }

    public synchronized void setOnChange(Runnable r) { this.onChange = r; }

    private void changed() {
        if (onChange != null) {
            try { onChange.run(); } catch (Exception ignored) {}
        }
    }

    public synchronized String hostSecret() { return hostPlayer.secret; }
    public synchronized String hostName() { return hostPlayer.name; }

    // ================================================================ players
    private String randomId(int len) {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(abc.charAt(rnd.nextInt(abc.length())));
        return sb.toString();
    }

    private Player newPlayer(String name) {
        Player p = new Player();
        do { p.pid = randomId(6); } while (players.containsKey(p.pid));
        p.secret = randomId(24);
        p.name = name;
        p.lastSeen = p.joinedAt = System.currentTimeMillis();
        players.put(p.pid, p);
        bySecret.put(p.secret, p);
        return p;
    }

    private static String cleanName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\p{Cntrl}<>]", "").trim().replaceAll("\\s+", " ");
        if (n.length() > 20) n = n.substring(0, 20).trim();
        if (n.isEmpty()) throw new GameException("Adj meg egy nevet!");
        return n;
    }

    private boolean online(Player p, long now) {
        return p.host || now - p.lastSeen < ONLINE_MS;
    }

    private List<Player> onlinePlayers() {
        long now = System.currentTimeMillis();
        List<Player> l = new ArrayList<>();
        for (Player p : players.values()) if (online(p, now)) l.add(p);
        return l;
    }

    /** Returns {secret, pid}. Re-joining with the name of an offline player takes over that player. */
    public synchronized Map<String, Object> join(String rawName) {
        String name = cleanName(rawName);
        long now = System.currentTimeMillis();
        for (Player p : players.values()) {
            if (p.name.equalsIgnoreCase(name)) {
                if (p.host || online(p, now)) throw new GameException("Ez a név már foglalt, válassz másikat!");
                p.lastSeen = now;
                changed();
                return Json.map("secret", p.secret, "pid", p.pid, "rejoined", true);
            }
        }
        Player p = newPlayer(name);
        if (missionActive) {
            p.inbox.add("A titkos küldetés már fut – kérj magadnak egy küldetést a Küldetés fülön!");
        }
        changed();
        return Json.map("secret", p.secret, "pid", p.pid, "rejoined", false);
    }

    public synchronized boolean isKnown(String secret) {
        return secret != null && bySecret.containsKey(secret);
    }

    private Player auth(String secret) {
        Player p = secret == null ? null : bySecret.get(secret);
        if (p == null) throw new GameException("unknown");
        p.lastSeen = System.currentTimeMillis();
        return p;
    }

    private void requireHost(Player p) {
        if (!p.host) throw new GameException("Ezt csak a játékmester teheti meg.");
    }

    private Player player(String pid) {
        Player p = pid == null ? null : players.get(pid);
        if (p == null) throw new GameException("Nincs ilyen játékos.");
        return p;
    }

    private static String teamName(String t) {
        return BLUE.equals(t) ? "Kékek" : RED.equals(t) ? "Pirosak" : "";
    }

    private static String other(String t) { return BLUE.equals(t) ? RED : BLUE; }

    // ================================================================ actions
    /** Generic action entry point. Returns an optional message for the caller. */
    public synchronized String action(String secret, Map<String, Object> a) {
        Player p = auth(secret);
        String type = Json.str(a, "type", "");
        String msg = null;
        switch (type) {
            // ---- players / teams
            case "kick": requireHost(p); kick(player(Json.str(a, "pid", ""))); break;
            case "setTeam": {
                String pid = Json.str(a, "pid", p.pid);
                if (!pid.equals(p.pid)) requireHost(p);
                String team = Json.str(a, "team", "");
                if (!team.equals(BLUE) && !team.equals(RED) && !team.isEmpty()) throw new GameException("Hibás csapat.");
                player(pid).team = team;
                break;
            }
            case "shuffleTeams": requireHost(p); shuffleTeams(); msg = "Csapatok kisorsolva!"; break;

            // ---- missions
            case "missionStart": requireHost(p); msg = missionStart(); break;
            case "missionRequest": missionRequest(p); break;
            case "missionSkip": missionSkip(p); break;
            case "missionComplete":
                missionComplete(p, Json.strings(Json.list(a, "targets")), Json.str(a, "note", ""));
                msg = "Szép munka! +1 pont";
                break;
            case "missionReveal": requireHost(p); missionsRevealed = Json.bool(a, "on", true); break;
            case "missionReset": requireHost(p); missionReset(); break;
            case "missionAdd": requireHost(p); missionAdd(Json.str(a, "title", ""), Json.str(a, "text", "")); msg = "Küldetés hozzáadva."; break;

            // ---- activity
            case "riddleAdd": riddleAdd(p, Json.str(a, "text", ""), Json.str(a, "cat", "")); msg = "Feladvány elküldve!"; break;
            case "riddleDelete": riddleDelete(p, (int) Json.num(a, "id", -1)); break;
            case "actNext": requireHost(p); actNext(Json.str(a, "team", "")); break;
            case "actStart": actStart(p); break;
            case "actResult": actResult(p, Json.bool(a, "guessed", false)); break;
            case "actSkipRiddle": actSkipRiddle(p); break;
            case "actOtherPerformer": requireHost(p); actOtherPerformer(); break;
            case "actCancel": requireHost(p); actCancel(); break;
            case "actSettings": requireHost(p); actSettings((int) Json.num(a, "duration", durationSec), Json.bool(a, "methods", useMethods)); break;
            case "actReset": requireHost(p); actReset(); break;

            // ---- top10
            case "top10Deal": requireHost(p); top10Deal(); break;
            case "top10Topic": requireHost(p); top10Topic(Json.str(a, "text", "")); break;
            case "top10Reveal": requireHost(p); tRevealed = true; break;

            default: throw new GameException("Ismeretlen művelet: " + type);
        }
        changed();
        return msg;
    }

    private void kick(Player t) {
        if (t.host) throw new GameException("A játékmestert nem lehet kidobni.");
        if (t.pid.equals(rPerformer) && (rState.equals("ready") || rState.equals("running"))) actCancel();
        players.remove(t.pid);
        bySecret.remove(t.secret);
        currentMission.remove(t.pid);
        tNumbers.remove(t.pid);
        tParticipants.remove(t.pid);
    }

    private void shuffleTeams() {
        List<Player> l = onlinePlayers();
        Collections.shuffle(l, rnd);
        boolean blueFirst = rnd.nextBoolean();
        for (int i = 0; i < l.size(); i++) {
            l.get(i).team = ((i % 2 == 0) == blueFirst) ? BLUE : RED;
        }
    }

    // ================================================================ missions
    private boolean assignMission(Player p) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < missions.size(); i++) if (!usedMissions.contains(i)) pool.add(i);
        if (pool.isEmpty()) return false;
        int idx = pool.get(rnd.nextInt(pool.size()));
        usedMissions.add(idx);
        currentMission.put(p.pid, idx);
        return true;
    }

    private String missionStart() {
        missionActive = true;
        int given = 0, missing = 0;
        for (Player p : onlinePlayers()) {
            if (currentMission.containsKey(p.pid)) continue;
            if (assignMission(p)) {
                given++;
                if (!p.host) p.inbox.add("🕵️ Megérkezett a titkos küldetésed! Nézd meg a Küldetés fülön – de ne mutasd meg senkinek!");
            } else missing++;
        }
        String m = "Küldetés kiosztva " + given + " játékosnak.";
        if (missing > 0) m += " " + missing + " játékosnak már nem jutott (elfogytak) – adj hozzá újakat!";
        return m;
    }

    private void missionRequest(Player p) {
        if (!missionActive) throw new GameException("A játékmester még nem indította el a titkos küldetést.");
        if (currentMission.containsKey(p.pid)) throw new GameException("Már van egy aktív küldetésed.");
        if (!assignMission(p)) throw new GameException("Elfogytak a küldetések – szólj a játékmesternek!");
    }

    private void missionSkip(Player p) {
        Integer cur = currentMission.get(p.pid);
        if (cur == null) throw new GameException("Nincs aktív küldetésed.");
        if (!assignMission(p)) throw new GameException("Nincs több szabad küldetés, ezt kell teljesítened!");
    }

    private void missionComplete(Player p, List<String> targets, String note) {
        Integer cur = currentMission.get(p.pid);
        if (cur == null) throw new GameException("Nincs aktív küldetésed.");
        List<String> valid = new ArrayList<>();
        for (String t : targets) if (players.containsKey(t) && !t.equals(p.pid) && !valid.contains(t)) valid.add(t);
        if (valid.isEmpty()) throw new GameException("Jelöld meg, kit sikerült rávenned!");
        Completion c = new Completion();
        c.pid = p.pid;
        c.mission = cur;
        c.targets = valid;
        c.note = note == null ? "" : note.trim();
        if (c.note.length() > 200) c.note = c.note.substring(0, 200);
        c.time = System.currentTimeMillis();
        completions.add(c);
        currentMission.remove(p.pid);
        String[] m = missions.get(cur);
        for (String t : valid) {
            Player tp = players.get(t);
            tp.inbox.add("😈 Lebuktál! " + p.name + " titkos küldetése ez volt: „" + m[0] + "” – " + m[1] + " És te segítettél neki!");
        }
    }

    private void missionReset() {
        missionActive = false;
        usedMissions.clear();
        currentMission.clear();
        completions.clear();
        missionsRevealed = false;
    }

    private void missionAdd(String title, String text) {
        title = title.trim();
        text = text.trim();
        if (title.isEmpty() || text.isEmpty()) throw new GameException("Add meg a küldetés nevét és leírását!");
        if (title.length() > 40) title = title.substring(0, 40);
        if (text.length() > 200) text = text.substring(0, 200);
        missions.add(new String[]{title, text});
    }

    private int completedCount(String pid) {
        int n = 0;
        for (Completion c : completions) if (c.pid.equals(pid)) n++;
        return n;
    }

    // ================================================================ activity
    private void riddleAdd(Player p, String text, String cat) {
        text = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (text.isEmpty()) throw new GameException("Írd be a feladványt!");
        if (text.length() > 80) throw new GameException("Túl hosszú (max. 80 karakter).");
        if (!Content.validCategory(cat)) throw new GameException("Válassz kategóriát!");
        if (p.team.isEmpty()) throw new GameException("Előbb kerülj be egy csapatba!");
        for (Riddle r : riddles) {
            if (!r.used && r.text.equalsIgnoreCase(text)) throw new GameException("Ez a feladvány már be van küldve.");
        }
        Riddle r = new Riddle();
        r.id = nextRiddleId++;
        r.author = p.pid;
        r.text = text;
        r.cat = cat;
        riddles.add(r);
    }

    private void riddleDelete(Player p, int id) {
        for (int i = 0; i < riddles.size(); i++) {
            Riddle r = riddles.get(i);
            if (r.id == id) {
                if (!r.author.equals(p.pid) && !p.host) throw new GameException("Csak a saját feladványodat törölheted.");
                if (r.used || (r.id == rRiddle && !rState.equals("idle"))) throw new GameException("Ez a feladvány már játékban van.");
                riddles.remove(i);
                return;
            }
        }
    }

    private String authorTeam(Riddle r) {
        Player a = players.get(r.author);
        return a == null ? "" : a.team;
    }

    private List<Riddle> riddlePoolFor(String performingTeam) {
        String from = other(performingTeam);
        List<Riddle> l = new ArrayList<>();
        for (Riddle r : riddles) if (!r.used && from.equals(authorTeam(r))) l.add(r);
        return l;
    }

    private Player pickPerformer(String team, String exclude) {
        List<Player> cands = new ArrayList<>();
        for (Player p : onlinePlayers()) if (team.equals(p.team) && !p.pid.equals(exclude)) cands.add(p);
        if (cands.isEmpty()) return null;
        Collections.shuffle(cands, rnd);
        Player best = cands.get(0);
        for (Player p : cands) if (p.performed < best.performed) best = p;
        return best;
    }

    private boolean roundActive() { return rState.equals("ready") || rState.equals("running"); }

    private void actNext(String team) {
        if (roundActive()) throw new GameException("Előbb fejezd be az aktuális kört!");
        if (!team.equals(BLUE) && !team.equals(RED)) team = nextTeam;
        Player perf = pickPerformer(team, "");
        if (perf == null) throw new GameException("A(z) " + teamName(team) + " csapatban nincs elérhető játékos.");
        List<Riddle> pool = riddlePoolFor(team);
        if (pool.isEmpty()) throw new GameException("A(z) " + teamName(other(team)) + " csapat még nem küldött be (több) feladványt.");
        Riddle r = pool.get(rnd.nextInt(pool.size()));
        perf.performed++;
        rTeam = team;
        rPerformer = perf.pid;
        rRiddle = r.id;
        rMethod = useMethods ? Content.METHODS[rnd.nextInt(Content.METHODS.length)][0] : "mime";
        rResult = "";
        rStart = 0;
        rState = "ready";
        roundSeq++;
        nextTeam = other(team);
        if (!perf.host) perf.inbox.add("🎭 Te következel! Nézd meg a feladványt az Activity fülön.");
    }

    private void requireHostOrPerformer(Player p) {
        if (!p.host && !p.pid.equals(rPerformer)) throw new GameException("Ezt a játékmester vagy az előadó teheti meg.");
    }

    private void actStart(Player p) {
        requireHostOrPerformer(p);
        if (!rState.equals("ready")) throw new GameException("Nincs elindítható kör.");
        rState = "running";
        rStart = System.currentTimeMillis();
    }

    private Riddle riddle(int id) {
        for (Riddle r : riddles) if (r.id == id) return r;
        return null;
    }

    private void actResult(Player p, boolean guessed) {
        requireHostOrPerformer(p);
        if (!roundActive()) throw new GameException("Nincs folyamatban lévő kör.");
        rState = "done";
        rResult = guessed ? "guessed" : "failed";
        Riddle r = riddle(rRiddle);
        if (r != null) r.used = true;
        if (guessed) {
            if (BLUE.equals(rTeam)) scoreBlue++; else scoreRed++;
        }
        Player perf = players.get(rPerformer);
        history.add(0, Json.map(
                "team", rTeam,
                "performer", perf == null ? "?" : perf.name,
                "text", r == null ? "?" : r.text,
                "cat", r == null ? "" : r.cat,
                "result", rResult));
        while (history.size() > 30) history.remove(history.size() - 1);
    }

    private void actSkipRiddle(Player p) {
        requireHostOrPerformer(p);
        if (!rState.equals("ready")) throw new GameException("Csak az indítás előtt lehet feladványt cserélni.");
        List<Riddle> pool = riddlePoolFor(rTeam);
        pool.remove(riddle(rRiddle));
        if (pool.isEmpty()) throw new GameException("Nincs másik feladvány.");
        rRiddle = pool.get(rnd.nextInt(pool.size())).id;
    }

    private void actOtherPerformer() {
        if (!rState.equals("ready")) throw new GameException("Csak az indítás előtt lehet előadót cserélni.");
        Player cur = players.get(rPerformer);
        Player perf = pickPerformer(rTeam, rPerformer);
        if (perf == null) throw new GameException("Nincs másik elérhető játékos a csapatban.");
        if (cur != null && cur.performed > 0) cur.performed--;
        perf.performed++;
        rPerformer = perf.pid;
        roundSeq++;
        if (!perf.host) perf.inbox.add("🎭 Te következel! Nézd meg a feladványt az Activity fülön.");
    }

    private void actCancel() {
        if (!roundActive()) { rState = "idle"; return; }
        Player cur = players.get(rPerformer);
        if (cur != null && cur.performed > 0) cur.performed--;
        nextTeam = rTeam;
        rState = "idle";
        rRiddle = -1;
        roundSeq++;
    }

    private void actSettings(int d, boolean methods) {
        if (d < 10 || d > 600) throw new GameException("Az idő 10 és 600 mp között lehet.");
        durationSec = d;
        useMethods = methods;
    }

    private void actReset() {
        riddles.clear();
        history.clear();
        scoreBlue = scoreRed = 0;
        rState = "idle";
        rRiddle = -1;
        nextTeam = BLUE;
        for (Player p : players.values()) p.performed = 0;
        roundSeq++;
    }

    // =================================================================== top10
    private void top10Deal() {
        List<Player> part = onlinePlayers();
        tHostOut = false;
        if (part.size() > 10) {
            part.remove(hostPlayer);
            tHostOut = true;
        }
        if (part.size() > 10) {
            Collections.shuffle(part, rnd);
            part = new ArrayList<>(part.subList(0, 10));
        }
        List<Integer> nums = new ArrayList<>();
        for (int i = 1; i <= 10; i++) nums.add(i);
        Collections.shuffle(nums, rnd);
        tNumbers.clear();
        tParticipants.clear();
        for (int i = 0; i < part.size(); i++) {
            tNumbers.put(part.get(i).pid, nums.get(i));
            tParticipants.add(part.get(i).pid);
        }
        tRound++;
        tRevealed = false;
        top10Topic("");
    }

    private void top10Topic(String custom) {
        custom = custom == null ? "" : custom.trim();
        if (!custom.isEmpty()) {
            tTopic = custom.length() > 150 ? custom.substring(0, 150) : custom;
            return;
        }
        if (usedTopics.size() >= Content.TOP10_TOPICS.length) usedTopics.clear();
        int i;
        do { i = rnd.nextInt(Content.TOP10_TOPICS.length); } while (usedTopics.contains(i));
        usedTopics.add(i);
        tTopic = Content.TOP10_TOPICS[i];
    }

    // ==================================================================== view
    public synchronized Map<String, Object> view(String secret, String joinUrl) {
        Player me = auth(secret);
        long now = System.currentTimeMillis();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("me", Json.map("pid", me.pid, "name", me.name, "host", me.host, "team", me.team));
        if (me.host) v.put("joinUrl", joinUrl);

        List<Object> pl = new ArrayList<>();
        for (Player p : players.values()) {
            pl.add(Json.map("pid", p.pid, "name", p.name, "host", p.host, "team", p.team,
                    "online", online(p, now), "missions", completedCount(p.pid)));
        }
        v.put("players", pl);

        // ---- missions
        Map<String, Object> ms = new LinkedHashMap<>();
        ms.put("active", missionActive);
        Integer cur = currentMission.get(me.pid);
        ms.put("my", cur == null ? null : Json.map("title", missions.get(cur)[0], "text", missions.get(cur)[1]));
        ms.put("left", missions.size() - usedMissions.size());
        ms.put("total", missions.size());
        ms.put("revealed", missionsRevealed);
        List<Object> mine = new ArrayList<>();
        List<Object> log = new ArrayList<>();
        for (int i = completions.size() - 1; i >= 0; i--) {
            Completion c = completions.get(i);
            Map<String, Object> e = completionView(c);
            if (c.pid.equals(me.pid)) mine.add(e);
            if (me.host || missionsRevealed) log.add(e);
        }
        ms.put("mine", mine);
        ms.put("log", log);
        if (me.host) {
            List<Object> act = new ArrayList<>();
            for (Map.Entry<String, Integer> e : currentMission.entrySet()) {
                Player p = players.get(e.getKey());
                if (p != null) act.add(Json.map("name", p.name, "title", missions.get(e.getValue())[0]));
            }
            ms.put("assigned", act);
        }
        v.put("mission", ms);

        // ---- activity
        Map<String, Object> ac = new LinkedHashMap<>();
        ac.put("scores", Json.map(BLUE, scoreBlue, RED, scoreRed));
        ac.put("nextTeam", nextTeam);
        ac.put("duration", durationSec);
        ac.put("methods", useMethods);
        int poolBlue = 0, poolRed = 0;
        List<Object> myR = new ArrayList<>();
        for (Riddle r : riddles) {
            String t = authorTeam(r);
            if (!r.used) {
                if (BLUE.equals(t)) poolBlue++;
                else if (RED.equals(t)) poolRed++;
            }
            if (r.author.equals(me.pid)) myR.add(Json.map("id", r.id, "text", r.text, "cat", r.cat, "used", r.used));
        }
        // riddles written by blue are performed by red and vice versa
        ac.put("pool", Json.map(BLUE, poolRed, RED, poolBlue));
        ac.put("myRiddles", myR);
        Map<String, Object> rd = new LinkedHashMap<>();
        rd.put("seq", roundSeq);
        String phase = rState;
        long remaining = 0;
        if (rState.equals("running")) {
            remaining = durationSec * 1000L - (now - rStart);
            if (remaining <= 0) { remaining = 0; phase = "timeup"; }
        } else if (rState.equals("ready")) {
            remaining = durationSec * 1000L;
        }
        rd.put("phase", phase);
        rd.put("remainingMs", remaining);
        if (!rState.equals("idle")) {
            Player perf = players.get(rPerformer);
            Riddle r = riddle(rRiddle);
            rd.put("team", rTeam);
            rd.put("performer", rPerformer);
            rd.put("performerName", perf == null ? "?" : perf.name);
            rd.put("method", rMethod);
            rd.put("cat", r == null ? "" : r.cat);
            rd.put("result", rResult);
            boolean guessing = rTeam.equals(me.team) && !me.pid.equals(rPerformer);
            boolean show = rState.equals("done") || me.pid.equals(rPerformer) || !guessing;
            // the host only peeks if he is not on the guessing team
            rd.put("text", show && r != null ? r.text : null);
            rd.put("iAmPerformer", me.pid.equals(rPerformer));
            rd.put("iAmGuessing", guessing);
        }
        ac.put("round", rd);
        ac.put("history", history);
        v.put("activity", ac);

        // ---- top10
        Map<String, Object> tt = new LinkedHashMap<>();
        tt.put("round", tRound);
        tt.put("topic", tTopic);
        tt.put("number", tNumbers.get(me.pid));
        tt.put("sittingOut", tRound > 0 && !tParticipants.contains(me.pid));
        tt.put("hostOut", tHostOut);
        tt.put("count", tParticipants.size());
        tt.put("revealed", tRevealed);
        if (tRevealed) {
            List<Object> all = new ArrayList<>();
            List<Map.Entry<String, Integer>> es = new ArrayList<>(tNumbers.entrySet());
            Collections.sort(es, (x, y) -> x.getValue() - y.getValue());
            for (Map.Entry<String, Integer> e : es) {
                Player p = players.get(e.getKey());
                all.add(Json.map("name", p == null ? "?" : p.name, "number", e.getValue()));
            }
            tt.put("all", all);
        }
        v.put("top10", tt);

        // ---- inbox (delivered once)
        v.put("inbox", new ArrayList<>(me.inbox));
        if (!me.inbox.isEmpty()) {
            me.inbox.clear();
        }
        return v;
    }

    private Map<String, Object> completionView(Completion c) {
        Player p = players.get(c.pid);
        List<Object> names = new ArrayList<>();
        for (String t : c.targets) {
            Player tp = players.get(t);
            names.add(tp == null ? "?" : tp.name);
        }
        String[] m = missions.get(c.mission);
        return Json.map("name", p == null ? "?" : p.name, "title", m[0], "text", m[1],
                "targets", names, "note", c.note, "time", c.time);
    }

    // ============================================================= persistence
    public synchronized String toJson() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("v", 1);
        List<Object> pl = new ArrayList<>();
        for (Player p : players.values()) {
            pl.add(Json.map("pid", p.pid, "secret", p.secret, "name", p.name, "team", p.team,
                    "host", p.host, "performed", p.performed, "joinedAt", p.joinedAt));
        }
        s.put("players", pl);
        List<Object> ml = new ArrayList<>();
        for (String[] m : missions) { List<Object> x = new ArrayList<>(); x.add(m[0]); x.add(m[1]); ml.add(x); }
        s.put("missions", ml);
        s.put("missionActive", missionActive);
        s.put("usedMissions", new ArrayList<>(usedMissions));
        s.put("currentMission", new LinkedHashMap<String, Object>(currentMission));
        List<Object> cl = new ArrayList<>();
        for (Completion c : completions) {
            cl.add(Json.map("pid", c.pid, "mission", c.mission, "targets", c.targets, "note", c.note, "time", c.time));
        }
        s.put("completions", cl);
        s.put("missionsRevealed", missionsRevealed);
        List<Object> rl = new ArrayList<>();
        for (Riddle r : riddles) rl.add(Json.map("id", r.id, "author", r.author, "text", r.text, "cat", r.cat, "used", r.used));
        s.put("riddles", rl);
        s.put("nextRiddleId", nextRiddleId);
        s.put("scoreBlue", scoreBlue);
        s.put("scoreRed", scoreRed);
        s.put("nextTeam", nextTeam);
        s.put("history", history);
        s.put("durationSec", durationSec);
        s.put("useMethods", useMethods);
        s.put("usedTopics", new ArrayList<>(usedTopics));
        return Json.write(s);
    }

    /** Restores a saved game (players, missions, scores). Running rounds are not restored. */
    @SuppressWarnings("unchecked")
    public static Game fromJson(String json) {
        Map<String, Object> s = Json.parseObject(json);
        Game g = new Game("x");
        g.players.clear();
        g.bySecret.clear();
        g.hostPlayer = null;
        for (Object o : Json.list(s, "players")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Player p = new Player();
            p.pid = Json.str(m, "pid", "");
            p.secret = Json.str(m, "secret", "");
            p.name = Json.str(m, "name", "?");
            p.team = Json.str(m, "team", "");
            p.host = Json.bool(m, "host", false);
            p.performed = (int) Json.num(m, "performed", 0);
            p.joinedAt = Json.num(m, "joinedAt", 0);
            p.lastSeen = 0;
            if (p.pid.isEmpty() || p.secret.isEmpty()) continue;
            g.players.put(p.pid, p);
            g.bySecret.put(p.secret, p);
            if (p.host) g.hostPlayer = p;
        }
        if (g.hostPlayer == null) throw new IllegalArgumentException("No host in save");
        g.hostPlayer.lastSeen = System.currentTimeMillis();
        g.missions.clear();
        for (Object o : Json.list(s, "missions")) {
            List<Object> x = (List<Object>) o;
            g.missions.add(new String[]{String.valueOf(x.get(0)), String.valueOf(x.get(1))});
        }
        if (g.missions.isEmpty()) for (String[] m : Content.MISSIONS) g.missions.add(m);
        g.missionActive = Json.bool(s, "missionActive", false);
        for (Object o : Json.list(s, "usedMissions")) g.usedMissions.add(((Number) o).intValue());
        for (Map.Entry<String, Object> e : Json.obj(s, "currentMission").entrySet()) {
            int idx = ((Number) e.getValue()).intValue();
            if (g.players.containsKey(e.getKey()) && idx < g.missions.size()) g.currentMission.put(e.getKey(), idx);
        }
        for (Object o : Json.list(s, "completions")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Completion c = new Completion();
            c.pid = Json.str(m, "pid", "");
            c.mission = (int) Json.num(m, "mission", 0);
            c.targets = Json.strings(Json.list(m, "targets"));
            c.note = Json.str(m, "note", "");
            c.time = Json.num(m, "time", 0);
            if (c.mission < g.missions.size()) g.completions.add(c);
        }
        g.missionsRevealed = Json.bool(s, "missionsRevealed", false);
        for (Object o : Json.list(s, "riddles")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Riddle r = new Riddle();
            r.id = (int) Json.num(m, "id", 0);
            r.author = Json.str(m, "author", "");
            r.text = Json.str(m, "text", "");
            r.cat = Json.str(m, "cat", "other");
            r.used = Json.bool(m, "used", false);
            g.riddles.add(r);
        }
        g.nextRiddleId = (int) Json.num(s, "nextRiddleId", g.riddles.size() + 1);
        g.scoreBlue = (int) Json.num(s, "scoreBlue", 0);
        g.scoreRed = (int) Json.num(s, "scoreRed", 0);
        g.nextTeam = Json.str(s, "nextTeam", BLUE);
        for (Object o : Json.list(s, "history")) g.history.add((Map<String, Object>) o);
        g.durationSec = (int) Json.num(s, "durationSec", 60);
        g.useMethods = Json.bool(s, "useMethods", true);
        for (Object o : Json.list(s, "usedTopics")) g.usedTopics.add(((Number) o).intValue());
        return g;
    }
}
