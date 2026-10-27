import hu.partytime.core.Game;
import hu.partytime.core.PartyServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs the PartyTime server on a desktop JVM for UI development / testing.
 * Usage: see devserver/run.sh
 */
public class DevServer {
    public static void main(String[] args) throws Exception {
        Path assets = Paths.get(args.length > 0 ? args[0] : "app/src/main/assets");
        Game g = new Game(args.length > 1 ? args[1] : "Mester");
        PartyServer s = new PartyServer(g, p -> {
            Path f = assets.resolve(p);
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        }, null);
        s.start(8765);
        s.setJoinUrl("http://127.0.0.1:" + s.port() + "/");
        System.out.println("PORT " + s.port());
        System.out.println("HOST http://127.0.0.1:" + s.port() + "/?host=" + g.hostSecret());
        System.out.flush();
        Thread.currentThread().join();
    }
}
