package de.zimmerei.holzliste;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ein Werkzeug / Gerät für die Baustelle (Reiter „Werkzeug“ einer Liste) und die automatischen Vorschläge. */
public class Werkzeug {

    public String name = "";
    public Integer anzahl;          // null = 1 / nicht angegeben
    public String bemerkung = "";
    public boolean eingepackt;      // beim Laden abgehakt
    /** Priorität: ROT = muss unbedingt mit, GELB = wichtig, GRUEN = optional. */
    public int prio = GELB;

    public static final int GRUEN = 0, GELB = 1, ROT = 2;

    public static String prioName(int p) { return p == ROT ? "Muss mit" : p == GRUEN ? "Optional" : "Wichtig"; }
    public static String prioSymbol(int p) { return p == ROT ? "🔴" : p == GRUEN ? "🟢" : "🟡"; }

    public Werkzeug() {}

    public Werkzeug(String name, String bemerkung) {
        this.name = name;
        this.bemerkung = bemerkung == null ? "" : bemerkung;
    }

    public Werkzeug copy() {
        Werkzeug w = new Werkzeug(name, bemerkung);
        w.anzahl = anzahl;
        w.eingepackt = eingepackt;
        w.prio = prio;
        return w;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        if (anzahl != null) o.put("anzahl", anzahl);
        o.put("bemerkung", bemerkung);
        o.put("eingepackt", eingepackt);
        o.put("prio", prio);
        return o;
    }

    public static Werkzeug fromJson(JSONObject o) {
        Werkzeug w = new Werkzeug(o.optString("name", ""), o.optString("bemerkung", ""));
        w.anzahl = o.has("anzahl") && !o.isNull("anzahl") ? o.optInt("anzahl") : null;
        w.eingepackt = o.optBoolean("eingepackt", false);
        w.prio = o.optInt("prio", GELB);
        return w;
    }

    // ================================================================ Vorschläge

    /** Ein vorgeschlagenes Werkzeug mit Begründung („wegen Dämmung 200 mm“). */
    public static final class Vorschlag {
        public final String name, grund;
        public final int prio;
        public boolean gewaehlt = true;

        Vorschlag(String name, String grund, int prio) { this.name = name; this.grund = grund; this.prio = prio; }
    }

    private static final class Regel {
        final Pattern muster;
        final String[] werkzeuge;

        Regel(String regex, String... werkzeuge) {
            this.muster = Pattern.compile(regex.replace("\\b", GRENZE));
            this.werkzeuge = werkzeuge;
        }
    }

    // Wortgrenze mit Umlauten (Android kennt UNICODE_CHARACTER_CLASS nicht)
    private static final String W = "[\\p{L}\\p{N}_]";
    private static final String GRENZE = "(?:(?<=" + W + ")(?!" + W + ")|(?<!" + W + ")(?=" + W + "))";

    /** Vorschlag für „Meine Standard-Ausrüstung“ (kann in der App angepasst werden). */
    public static final String[] GRUNDAUSSTATTUNG = {
            "Akkuschrauber + Akkus & Ladegerät", "Bit-Satz", "Zollstock / Maßband", "Wasserwaage", "Zimmermannsbleistift",
            "Zimmermannswinkel", "Hammer", "Cuttermesser", "Kabeltrommel", "Erste-Hilfe-Kasten"};

    // Material (Bezeichnung, Material, Kategorie) → Werkzeug
    private static final Regel[] MATERIAL = {
            new Regel("\\bkvh\\b|\\bbsh\\b|kantholz|balken|sparren|pfette|konstruktionsvollholz|brettschicht|stiel|schwelle",
                    "Handkreissäge", "Kappsäge", "Zimmermannswinkel", "Schraubzwingen"),
            new Regel("latte|lattung|brett|schalung|rauhspund|rauspund|diele|terrasse|balkonbrett",
                    "Kappsäge", "Handkreissäge", "Schlagschnur"),
            new Regel("\\bosb|dreischicht|sperrholz|multiplex|spanplatte|holzfaserplatte|unterdeckplatte|\\bdwd\\b|agepan",
                    "Handkreissäge mit Führungsschiene", "Stichsäge", "Plattenheber"),
            new Regel("gipskarton|\\bgk[bfi]?\\b|rigips|fermacell|gipsfaser|trockenbau|\\b(cw|uw|cd|ud)\\b|ständerwerk",
                    "Gipskartonmesser", "Gipsraspel", "Plattenheber", "Blechschere", "Spachtel & Glätter"),
            new Regel("dämm|daemm|mineralwolle|glaswolle|steinwolle|klemmfilz|zellulose|holzfaser(?!platte)",
                    "Dämmstoffmesser", "Dämmstoffsäge", "FFP2-Masken", "Schutzbrille"),
            new Regel("dampfbrems|dampfsperr|folie|unterdeckbahn|unterspann|\\budb\\b|\\busb\\b|intello|bahn\\b",
                    "Tacker + Klammern", "Andrückroller", "Cuttermesser"),
            new Regel("klebeband|tescon|anschlussband|dichtband|kompriband", "Andrückroller", "Schere"),
            new Regel("schraube|schrauben|\\bspax|tellerkopf|vollgewinde", "Akkuschrauber + Akkus & Ladegerät", "Bit-Satz"),
            new Regel("(1[6-9]\\d|[2-5]\\d\\d) ?mm.*schraub|schraub.*\\b(1[6-9]\\d|[2-5]\\d\\d)\\b|\\b[8-9] ?[x×] ?(1[6-9]\\d|[2-5]\\d\\d)\\b|\\b1[0-2] ?[x×] ?\\d{3}\\b",
                    "Schlagschrauber"),
            new Regel("nagel|nägel|klammer|rillennagel|ankernagel", "Druckluftnagler + Kompressor", "Hammer"),
            new Regel("winkelverbinder|balkenschuh|pfettenanker|lochband|schlitzband|holzverbinder|knagge",
                    "Ankernagelgerät oder Hammer", "Akkuschrauber + Akkus & Ladegerät"),
            new Regel("dübel|duebel|\\banker\\b|gewindestange|schwerlast|verankerung|bolzenanker",
                    "Bohrhammer", "Betonbohrer-Satz", "Ausblaspumpe", "Ratsche + Nüsse"),
            new Regel("injektion|verbundmörtel|vmz|\\bfis\\b", "Auspresspistole für Injektionsmörtel"),
            new Regel("silikon|acryl|dichtstoff|kartusche|kleber|kleb(?!eband)", "Kartuschenpistole"),
            new Regel("bauschaum|montageschaum|pu-schaum", "Schaumpistole", "Schaumreiniger"),
            new Regel("lasur|holzschutz|grundier|imprägnier|imprõgnier|anstrich|farbe|\\böl\\b|pullex",
                    "Pinsel & Farbroller", "Rührholz", "Abdeckfolie", "Exzenterschleifer + Schleifpapier"),
            new Regel("ziegel|dachstein|dachpfanne|eindeckung|first|grat",
                    "Ziegelschneider / Trennschleifer", "Dachdeckerhammer", "Dachleiter", "Absturzsicherung (PSA)"),
            new Regel("rinne|fallrohr|blech|verwahrung|attika|titanzink|\\bzink",
                    "Blechschere", "Falzzange", "Lötkolben", "Nieter"),
            new Regel("bitumen|schweißbahn|schweissbahn", "Gasbrenner", "Andrückroller", "Feuerlöscher"),
            new Regel("mörtel|moertel|beton|estrich|zement|putz", "Mörteleimer", "Kelle", "Rührwerk"),
            new Regel("dachfenster|velux|\\broto\\b|eindeckrahmen", "Keile & Unterlegplättchen", "Montageschaum + Schaumpistole"),
    };

    // Arbeitsschritte / Text aus der Arbeitsanweisung → Werkzeug
    private static final Regel[] ARBEIT = {
            new Regel("demontage|demontier|abbruch|abbrechen|rückbau|zurückbauen|ausbauen",
                    "Säbelsäge", "Kuhfuß / Brecheisen", "Schutzhandschuhe"),
            new Regel("entsorg", "Schuttsäcke", "Schubkarre"),
            new Regel("abfangen|abstützen|stützen", "Baustützen", "Kanthölzer zum Unterlegen"),
            new Regel("gerüst|deckenmontage|über kopf|überkopf", "Fahrgerüst / Arbeitsgerüst"),
            new Regel("dach|first|traufe|sparren|höhe|absturz", "Leiter", "Absturzsicherung (PSA)"),
            new Regel("balkon|geländer", "Leiter", "Absperrband"),
            new Regel("lagern|zwischenlager", "Abdeckplane"),
            new Regel("fenster|tür|tuer", "Keile & Unterlegplättchen", "Montageschaum + Schaumpistole"),
            new Regel("schleif", "Exzenterschleifer + Schleifpapier"),
            new Regel("bohr|dübel|verankern", "Bohrhammer", "Betonbohrer-Satz"),
            new Regel("sockel|beton|fundament", "Bohrhammer", "Mörteleimer", "Kelle"),
            new Regel("abbund|abbinden|zapfen|blatt|kerve|klaue", "Kettenstemmer", "Abbundsäge", "Stemmeisen"),
            new Regel("hobel", "Elektrohobel"),
            new Regel("prüfen|überprüfen|ertüchtig|feuchte", "Feuchtemessgerät", "Taschenlampe"),
    };

    /**
     * Werkzeug-Vorschläge für eine Liste: aus Material-Positionen, Überschriften, Notiz (Arbeitsschritte) und
     * optional weiterem Text (z. B. Arbeitsanweisung). Bereits vorhandenes Werkzeug wird weggelassen.
     */
    public static List<Vorschlag> vorschlagen(Holzliste h, List<String> zusatzText) {
        return vorschlagen(h, zusatzText, java.util.Arrays.asList(GRUNDAUSSTATTUNG));
    }

    /**
     * Wie oben; Werkzeug aus der Standard-Ausrüstung wird nicht einzeln vorgeschlagen (das hat man sowieso dabei).
     * Werkzeug wegen Material = ROT (speziell, z. B. Schaumpistole), wegen Arbeitsschritten = GELB.
     */
    public static List<Vorschlag> vorschlagen(Holzliste h, List<String> zusatzText, List<String> standard) {
        return vorschlagen(h, zusatzText, standard, null);
    }

    /**
     * Wie oben, dazu die Erfahrung des Betriebs: Was meistens abgewählt wird, ist nicht mehr vorangehakt (und fällt nach
     * mehrmaligem Abwählen ganz weg); was ihr bei ähnlichen Baustellen selbst dazugeschrieben habt, wird vorgeschlagen.
     */
    public static List<Vorschlag> vorschlagen(Holzliste h, List<String> zusatzText, List<String> standard, Erfahrung erf) {
        Map<String, Vorschlag> out = new LinkedHashMap<>();
        java.util.Set<String> vorhanden = new java.util.HashSet<>();
        for (Werkzeug w : h.werkzeug) vorhanden.add(schluessel(w.name));
        if (standard != null) for (String st : standard) vorhanden.add(schluessel(st));

        for (Position p : h.positionen) {
            if (p.isTitel()) continue;
            String text = (p.bezeichnung + " " + p.holzart + " " + p.typ).toLowerCase(Locale.GERMANY);
            if (p.isHolz() || p.isLfm()) text += " balken"; // jedes Holz → Säge
            String grund = "wegen " + kurz(p.bezeichnung.isEmpty() ? p.holzart : p.bezeichnung);
            for (Regel r : MATERIAL) if (r.muster.matcher(text).find()) for (String w : r.werkzeuge) add(out, vorhanden, w, grund, ROT);
        }

        List<String> texte = new ArrayList<>();
        for (Position p : h.positionen) if (p.isTitel()) texte.add(p.bezeichnung);
        if (h.notiz != null) for (String z : h.notiz.split("\n")) texte.add(z);
        for (Aufgabe x : h.aufgaben) texte.add(x.text);
        if (h.bauteil != null) texte.add(h.bauteil);
        if (zusatzText != null) texte.addAll(zusatzText);
        for (String z : texte) {
            String t = z.replaceAll("^[\\s•\\-–*]+", "").trim();
            if (t.isEmpty()) continue;
            String k = t.toLowerCase(Locale.GERMANY);
            for (Regel r : ARBEIT) {
                Matcher m = r.muster.matcher(k);
                if (m.find()) for (String w : r.werkzeuge) add(out, vorhanden, w, "wegen „" + kurz(t) + "“", GELB);
            }
        }
        if (erf != null) {
            // aus eurer Erfahrung: selbst dazugeschriebenes Werkzeug bei ähnlichen Baustellen
            java.util.Set<String> merk = merkmale(h, zusatzText);
            for (Map.Entry<String, Map<String, Integer>> e : erf.eigen.entrySet()) {
                String best = null;
                int n = 0;
                for (String m : merk) {
                    Integer c = e.getValue().get(m);
                    if (c != null && c > n) { n = c; best = m; }
                }
                if (n >= Erfahrung.AB_WANN) add(out, vorhanden, erf.name(e.getKey()), "aus eurer Erfahrung: schon " + n + "× bei „" + best + "“", GELB);
            }
            // oft abgewählt: nicht mehr vorangehakt bzw. ganz weg
            for (java.util.Iterator<Map.Entry<String, Vorschlag>> it = out.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Vorschlag> e = it.next();
                int ab = erf.ab.getOrDefault(e.getKey(), 0), an = erf.an.getOrDefault(e.getKey(), 0);
                if (ab >= Erfahrung.WEG_AB && an == 0) it.remove();
                else if (ab > an) e.getValue().gewaehlt = false;
            }
        }
        List<Vorschlag> l = new ArrayList<>(out.values());
        // Wichtigstes zuerst (stabil: innerhalb gleicher Priorität bleibt die Reihenfolge)
        java.util.Collections.sort(l, (a, b) -> Integer.compare(b.prio, a.prio));
        return l;
    }

    /**
     * Woran man ähnliche Baustellen erkennt: Kategorien des Materials (Holz, Platten, Dämmung …) und Arbeiten aus den
     * Texten (Demontage, Dach, Fenster …) – jeweils als lesbares Wort.
     */
    static java.util.Set<String> merkmale(Holzliste h, List<String> zusatzText) {
        java.util.Set<String> m = new java.util.TreeSet<>();
        for (Position p : h.positionen) {
            if (p.isTitel()) continue;
            if (p.isHolz() || p.isLfm()) m.add("Holz");
            else if (p.isPlatte()) m.add("Platten");
            else {
                String k = Kategorien.zuordnen(p.bezeichnung + " " + p.holzart);
                if (k != null && !k.isEmpty() && !"Sonstiges".equals(k)) m.add(k);
            }
        }
        List<String> texte = new ArrayList<>();
        for (Aufgabe x : h.aufgaben) texte.add(x.text);
        if (h.notiz != null) texte.add(h.notiz);
        if (h.bauteil != null) texte.add(h.bauteil);
        if (zusatzText != null) texte.addAll(zusatzText);
        for (String t : texte) {
            String k = t.toLowerCase(Locale.GERMANY);
            for (Regel r : ARBEIT) {
                Matcher x = r.muster.matcher(k);
                if (x.find()) m.add(x.group().trim());
            }
        }
        return m;
    }

    /**
     * Was der Betrieb mit den Vorschlägen macht – gespeichert auf dem Handy, damit die Vorschläge mit der Zeit passen.
     * ab/an: wie oft ein Vorschlag abgewählt bzw. übernommen wurde; eigen: selbst dazugeschriebenes Werkzeug und bei
     * welchen Merkmalen der Baustelle.
     */
    public static final class Erfahrung {
        static final int AB_WANN = 2;  // eigenes Werkzeug ab dem 2. Mal bei gleichem Merkmal vorschlagen
        static final int WEG_AB = 3;   // nach 3× abwählen (und nie übernommen) nicht mehr vorschlagen

        final Map<String, Integer> ab = new java.util.HashMap<>(), an = new java.util.HashMap<>();
        final Map<String, Map<String, Integer>> eigen = new java.util.TreeMap<>();
        final Map<String, String> namen = new java.util.HashMap<>();

        String name(String schluessel) { return namen.getOrDefault(schluessel, schluessel); }

        /** Nach „Übernehmen“ im Vorschlagsfenster: was blieb angehakt, was nicht. */
        public void entschieden(List<Vorschlag> v, boolean[] angehakt) {
            for (int i = 0; i < v.size(); i++) {
                Map<String, Integer> m = angehakt[i] ? an : ab;
                String k = schluessel(v.get(i).name);
                m.put(k, m.getOrDefault(k, 0) + 1);
            }
        }

        /** Werkzeug von Hand hinzugefügt: für die Merkmale dieser Baustelle merken. */
        public void selbstHinzugefuegt(Holzliste h, String name) {
            String k = schluessel(name);
            if (k.isEmpty()) return;
            namen.put(k, name.trim());
            Map<String, Integer> m = eigen.computeIfAbsent(k, x -> new java.util.TreeMap<>());
            for (String merk : merkmale(h, null)) m.put(merk, m.getOrDefault(merk, 0) + 1);
            // wer es von Hand dazuschreibt, will es: zählt auch als übernommen
            an.put(k, an.getOrDefault(k, 0) + 1);
        }

        public org.json.JSONObject toJson() throws org.json.JSONException {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ab", new org.json.JSONObject(ab));
            o.put("an", new org.json.JSONObject(an));
            org.json.JSONObject e = new org.json.JSONObject();
            for (Map.Entry<String, Map<String, Integer>> x : eigen.entrySet()) e.put(x.getKey(), new org.json.JSONObject(x.getValue()));
            o.put("eigen", e);
            o.put("namen", new org.json.JSONObject(namen));
            return o;
        }

        public static Erfahrung fromJson(org.json.JSONObject o) {
            Erfahrung f = new Erfahrung();
            if (o == null) return f;
            zahlen(o.optJSONObject("ab"), f.ab);
            zahlen(o.optJSONObject("an"), f.an);
            org.json.JSONObject e = o.optJSONObject("eigen");
            if (e != null) for (java.util.Iterator<String> it = e.keys(); it.hasNext(); ) {
                String k = it.next();
                Map<String, Integer> m = new java.util.TreeMap<>();
                zahlen(e.optJSONObject(k), m);
                f.eigen.put(k, m);
            }
            org.json.JSONObject n = o.optJSONObject("namen");
            if (n != null) for (java.util.Iterator<String> it = n.keys(); it.hasNext(); ) {
                String k = it.next();
                f.namen.put(k, n.optString(k, k));
            }
            return f;
        }

        private static void zahlen(org.json.JSONObject o, Map<String, Integer> m) {
            if (o == null) return;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.optInt(k, 0));
            }
        }

        private static final String PREF = "werkzeug_erfahrung";

        public static Erfahrung laden(android.content.Context c) {
            try {
                return fromJson(new org.json.JSONObject(c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).getString("daten", "{}")));
            } catch (org.json.JSONException e) {
                return new Erfahrung();
            }
        }

        public void speichern(android.content.Context c) {
            try {
                c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit().putString("daten", toJson().toString()).apply();
            } catch (org.json.JSONException ignored) {
                // nur Komfort
            }
        }

        /** Alles vergessen (Menü in den Werkzeug-Vorschlägen). */
        public static void vergessen(android.content.Context c) {
            c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    /** Alle bekannten Werkzeugnamen (für die Eingabe-Vorschläge). */
    public static List<String> alleNamen() {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        java.util.Collections.addAll(s, GRUNDAUSSTATTUNG);
        for (Regel r : MATERIAL) java.util.Collections.addAll(s, r.werkzeuge);
        for (Regel r : ARBEIT) java.util.Collections.addAll(s, r.werkzeuge);
        java.util.Collections.addAll(s, "Kettensäge", "Multifunktionswerkzeug", "Laser / Linienlaser", "Nivelliergerät",
                "Stromerzeuger", "Baustrahler", "Sackkarre", "Spanngurte", "Stichsäge", "Winkelschleifer", "Bandschleifer");
        return new ArrayList<>(s);
    }

    private static void add(Map<String, Vorschlag> out, java.util.Set<String> vorhanden, String name, String grund, int prio) {
        String k = schluessel(name);
        if (vorhanden.contains(k) || out.containsKey(k)) return;
        out.put(k, new Vorschlag(name, grund, prio));
    }

    static boolean gleicherName(String a, String b) { return schluessel(a).equals(schluessel(b)); }

    private static String schluessel(String name) {
        return name == null ? "" : name.toLowerCase(Locale.GERMANY).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String kurz(String s) {
        s = s == null ? "" : s.trim();
        return s.length() <= 40 ? s : s.substring(0, 38).trim() + "…";
    }
}
