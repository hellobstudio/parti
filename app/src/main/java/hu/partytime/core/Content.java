package hu.partytime.core;

/** Built-in game content (Hungarian). */
public final class Content {
    private Content() {}

    /** {title, description} */
    public static final String[][] MISSIONS = {
        {"A kérdező", "Érd el, hogy valaki megkérdezze tőled: „Miért?”"},
        {"A koccintás", "Érd el, hogy valaki koccintson veled."},
        {"Nevettess meg valakit", "Érd el, hogy valaki hangosan nevessen egy általad mondott mondaton."},
        {"Helycsere", "Vedd rá valakit, hogy cseréljetek helyet."},
        {"DJ", "Érd el, hogy valaki ajánljon neked egy dalt."},
        {"Fotós", "Vedd rá valakit, hogy készítsen rólad egy fényképet."},
        {"Pacsi", "Érd el, hogy három különböző emberrel pacsizz."},
        {"Kaja", "Érd el, hogy valaki megkérdezze tőled, hogy kérsz-e enni."},
        {"Témaindító", "Tereld úgy a beszélgetést, hogy valaki szóba hozza a nyaralást."},
        {"Filmkritikus", "Érd el, hogy valaki megkérdezze tőled, melyik a kedvenc filmed vagy sorozatod."},
        {"Segítség", "Vedd rá valakit, hogy segítsen neked valamiben, amit egyedül is meg tudnál csinálni."},
        {"Csereüzlet", "Cserélj italt vagy poharat valakivel legalább 10 másodpercre."},
        {"A furcsa kérdés", "Tegyél fel valakinek egy teljesen váratlan kérdést úgy, hogy ne tűnjön küldetésnek."},
        {"A szervező", "Érd el, hogy legalább három ember egyszerre ugyanarra a helyre menjen."},
        {"A manipulátor", "Vedd rá valakit, hogy mondjon ki egy általad előre kiválasztott szót. (Írd fel magadnak előre a szót!)"},
        // --- kiegészítések ---
        {"Bókvadász", "Érd el, hogy valaki megdicsérje a ruhádat vagy a kinézetedet."},
        {"Visszhang", "Érd el, hogy valaki szó szerint megismételjen egy mondatot, amit te mondtál."},
        {"Énekes", "Vedd rá valakit, hogy énekeljen el legalább egy sort egy dalból."},
        {"Táncpartner", "Vedd rá valakit, hogy táncoljon veled legalább 10 másodpercig."},
        {"Pincér", "Érd el, hogy valaki hozzon neked egy italt vagy ételt anélkül, hogy egyenesen megkérnéd rá."},
        {"Időjós", "Érd el, hogy valaki szóba hozza az időjárást."},
        {"Emlékező", "Érd el, hogy valaki meséljen egy gyerekkori emlékéről."},
        {"Állatbarát", "Érd el, hogy valaki meséljen egy háziállatról."},
        {"Csoportkép", "Készíts közös szelfit legalább két másik emberrel."},
        {"Tanácsadó", "Érd el, hogy valaki tanácsot kérjen tőled."},
        {"Tapsvihar", "Érd el, hogy legalább két ember egyszerre tapsoljon."},
        {"Ölelés", "Érd el, hogy valaki megöleljen."},
        {"Üzletkötés", "Fogj kezet valakivel úgy, mintha üzletet kötnétek."},
        {"Utánzó", "Érd el, hogy valaki utánozzon egy hírességet vagy egy állatot."},
        {"Fejtörő", "Érd el, hogy valaki megpróbáljon kitalálni egy találós kérdést vagy fejtörőt."},
        {"Képnézegető", "Érd el, hogy valaki megmutasson neked valamit a telefonján."},
        {"Szakács", "Érd el, hogy valaki elmondja a kedvenc ételét vagy egy receptet."},
        {"A becenév", "Érd el, hogy valaki egy általad kitalált becenéven szólítson."},
        {"Kacsintás", "Érd el, hogy valaki rád kacsintson."},
        {"Pohárköszöntő", "Vedd rá valakit, hogy mondjon egy rövid pohárköszöntőt."},
        {"Fogadás", "Köss valakivel egy ártatlan fogadást bármiről."},
        {"Sportriporter", "Érd el, hogy valaki szóba hozzon egy sportot vagy sporteseményt."},
        {"Ajándék", "Érd el, hogy valaki adjon neked valamit (szalvétát, chipset, tollat…)."},
        {"Pletyka", "Érd el, hogy valaki elmondjon neked egy ártalmatlan titkot vagy pletykát."},
        {"Időutazó", "Érd el, hogy valaki megkérdezze tőled, hány óra van."},
    };

    public static final String[] TOP10_TOPICS = {
        "Mennyire lenne kínos ez a helyzet egy első randin?",
        "Mennyire jó ez a szuperképesség?",
        "Mennyire finom ez az étel?",
        "Mennyire félnél ettől az állattól?",
        "Mennyire jó ez a nyaralási célpont?",
        "Mennyire hasznos ez a tárgy egy lakatlan szigeten?",
        "Mennyire menő ez a foglalkozás?",
        "Mennyire idegesítő ez a szokás?",
        "Mennyire örülnél ennek a születésnapi ajándéknak?",
        "Mennyire veszélyes ez a hobbi?",
        "Mennyire romantikus ez a gesztus?",
        "Mennyire lenne vicces ez egy esküvőn?",
        "Mennyire jó ez a bulitéma?",
        "Mennyire ijesztő ez a helyzet egy horrorfilmben?",
        "Mennyire drága ez a dolog?",
        "Mennyire utálatos ez a házimunka?",
        "Mennyire jó ez a kifogás késésre?",
        "Mennyire híres ez a személy?",
        "Mennyire lenne jó háziállat ez az állat?",
        "Mennyire lenne kínos, ha ezt az üzenetet rossz embernek küldenéd el?",
        "Mennyire jó ez a reggeli?",
        "Mennyire jó ez a film vagy sorozat?",
        "Mennyire erős ez a mesehős vagy szuperhős?",
        "Mennyire bírnád ki egy hétig ezt?",
        "Mennyire működne ez az ismerkedős duma?",
        "Mennyire jó ez az extra egy lakásban?",
        "Mennyire lenne sikeres ez az app ötlet?",
        "Mennyire büdös ez a dolog?",
        "Mennyire nehéz ezt megtanulni?",
        "Mennyire tenné tönkre a nyaralást ez a dolog?",
        "Mennyire hangos ez a dolog?",
        "Mennyire gyerekes ez a szórakozás?",
    };

    public static final String[][] CATEGORIES = {
        {"person", "Híres ember"},
        {"movie", "Film / sorozat"},
        {"animal", "Állat"},
        {"other", "Tárgy / fogalom"},
    };

    public static final String[][] METHODS = {
        {"mime", "Mutogatás"},
        {"draw", "Rajzolás"},
        {"talk", "Körülírás"},
    };

    public static boolean validCategory(String c) {
        for (String[] x : CATEGORIES) if (x[0].equals(c)) return true;
        return false;
    }
}
