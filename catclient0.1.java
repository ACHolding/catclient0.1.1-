/*
 * Cat Client 0.1 - original Java Swing Minecraft launcher UI
 * Cat-themed branding. Layout loosely inspired by common launcher patterns
 * (sidebar + play + version + skin). Not affiliated with Mojang, Microsoft,
 * or TLauncher. All colors, icons, and copy are original.
 * Always-on Fabric FPS pack (Modrinth) inspired by performance-client FPS features.
 */
import javax.imageio.ImageIO;
import javax.net.ssl.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class CatClient extends JFrame {

    static final String APP_NAME = "Cat Client 0.1";
    static final String USER_AGENT = "CatClient/0.1";
    static final String SKIN_URL = "https://mc-heads.net/head/%s/140.png";
    static final String ASSETS_URL = "https://resources.download.minecraft.net";
    static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    static final String MODRINTH_API = "https://api.modrinth.com/v2";
    static final String[] VERSION_MANIFEST_URLS = {
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    };
    static final int DOWNLOAD_CHUNK = 1024 * 1024;
    static final int LIB_WORKERS = 32;
    static final int ASSET_WORKERS = 48;
    static final int MOD_WORKERS = 12;
    /** Fabric API + Sodium + Lunar-style FPS/QoL pack (slug, label). Soft-fail per mod. */
    static final String[][] FPS_MODS = {
            {"fabric-api", "Fabric API"},
            {"sodium", "Sodium"},
            {"lithium", "Lithium"},
            {"entityculling", "Entity Culling"},
            {"immediatelyfast", "ImmediatelyFast"},
            {"moreculling", "More Culling"},
            {"badoptimizations", "BadOptimizations"},
            {"dynamic-fps", "Dynamic FPS"},
            {"sodium-extra", "Sodium Extra"},
            {"reeses-sodium-options", "Reese's Sodium Options"},
            {"ferrite-core", "FerriteCore"},
            {"modernfix", "ModernFix"},
            {"krypton", "Krypton Network"},
            {"noisium", "Noisium"},
            {"memoryleakfix", "Memory Leak Fix"},
            {"fpsdisplay", "FPS Display"},
            {"modmenu", "Mod Menu"},
            {"cloth-config", "Cloth Config"},
            {"lazydfu", "LazyDFU"},
            {"cull-less-leaves", "Cull Less Leaves"},
            {"threadtweak", "ThreadTweak"},
            {"starlight", "Starlight"},
            {"alternate-current", "Alternate Current"},
            {"packet-fixer", "Packet Fixer"},
    };
    static final String CLASSPATH_SEP = Util.isWindows() ? ";" : ":";
    static final Path GAME_DIR = Util.defaultGameDir();

    // ===================== Minimal JSON =====================
    static final class J {
        abstract static class Val {
            J.Obj asObj() { throw new IllegalStateException("not object"); }
            J.Arr asArr() { throw new IllegalStateException("not array"); }
            String asStr() { throw new IllegalStateException("not string"); }
            boolean asBool() { throw new IllegalStateException("not bool"); }
            Number asNum() { throw new IllegalStateException("not number"); }
            boolean isNull() { return false; }
            String getStr(String k, String d) { return d; }
            J.Val get(String k) { return Null.INSTANCE; }
            boolean has(String k) { return false; }
        }

        static final class Null extends Val {
            static final Null INSTANCE = new Null();
            boolean isNull() { return true; }
            public String toString() { return "null"; }
        }

        static final class Bool extends Val {
            final boolean v;
            Bool(boolean v) { this.v = v; }
            boolean asBool() { return v; }
            public String toString() { return Boolean.toString(v); }
        }

        static final class Num extends Val {
            final Number v;
            Num(Number v) { this.v = v; }
            Number asNum() { return v; }
            public String toString() { return v.toString(); }
        }

        static final class Str extends Val {
            final String v;
            Str(String v) { this.v = v; }
            String asStr() { return v; }
            public String toString() { return v; }
        }

        static final class Obj extends Val {
            final LinkedHashMap<String, Val> map = new LinkedHashMap<>();
            Obj asObj() { return this; }
            Val get(String k) { Val v = map.get(k); return v == null ? Null.INSTANCE : v; }
            boolean has(String k) { return map.containsKey(k); }
            String getStr(String k, String d) {
                Val v = map.get(k);
                return (v instanceof Str) ? ((Str) v).v : d;
            }
            long getLong(String k, long d) {
                Val v = map.get(k);
                return (v instanceof Num) ? ((Num) v).v.longValue() : d;
            }
            int getInt(String k, int d) {
                Val v = map.get(k);
                return (v instanceof Num) ? ((Num) v).v.intValue() : d;
            }
            boolean getBool(String k, boolean d) {
                Val v = map.get(k);
                return (v instanceof Bool) ? ((Bool) v).v : d;
            }
            Obj getObj(String k) {
                Val v = map.get(k);
                return (v instanceof Obj) ? (Obj) v : null;
            }
            Arr getArr(String k) {
                Val v = map.get(k);
                return (v instanceof Arr) ? (Arr) v : null;
            }
            void put(String k, Val v) { map.put(k, v); }
            public String toString() { return stringify(this); }
        }

        static final class Arr extends Val {
            final ArrayList<Val> list = new ArrayList<>();
            Arr asArr() { return this; }
            int size() { return list.size(); }
            Val get(int i) { return list.get(i); }
            void add(Val v) { list.add(v); }
            public String toString() { return stringify(this); }
        }

        static Val parse(String s) {
            return new Parser(s).parseValue();
        }

        static Obj parseObj(String s) {
            Val v = parse(s);
            if (!(v instanceof Obj)) throw new RuntimeException("Expected JSON object");
            return (Obj) v;
        }

        static String stringify(Val v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0, true);
            return sb.toString();
        }

        static String stringifyPretty(Val v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0, false);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Val v, int indent, boolean compact) {
            if (v instanceof Null || v == null) {
                sb.append("null");
            } else if (v instanceof Bool) {
                sb.append(((Bool) v).v);
            } else if (v instanceof Num) {
                sb.append(((Num) v).v);
            } else if (v instanceof Str) {
                sb.append('"').append(escape(((Str) v).v)).append('"');
            } else if (v instanceof Arr) {
                Arr a = (Arr) v;
                sb.append('[');
                if (!compact && a.size() > 0) sb.append('\n');
                for (int i = 0; i < a.size(); i++) {
                    if (!compact) indent(sb, indent + 1);
                    write(sb, a.get(i), indent + 1, compact);
                    if (i < a.size() - 1) sb.append(',');
                    if (!compact) sb.append('\n');
                }
                if (!compact && a.size() > 0) indent(sb, indent);
                sb.append(']');
            } else if (v instanceof Obj) {
                Obj o = (Obj) v;
                sb.append('{');
                if (!compact && !o.map.isEmpty()) sb.append('\n');
                int i = 0;
                int n = o.map.size();
                for (Map.Entry<String, Val> e : o.map.entrySet()) {
                    if (!compact) indent(sb, indent + 1);
                    sb.append('"').append(escape(e.getKey())).append('"').append(compact ? ":" : ": ");
                    write(sb, e.getValue(), indent + 1, compact);
                    if (i++ < n - 1) sb.append(',');
                    if (!compact) sb.append('\n');
                }
                if (!compact && !o.map.isEmpty()) indent(sb, indent);
                sb.append('}');
            }
        }

        private static void indent(StringBuilder sb, int n) {
            for (int i = 0; i < n; i++) sb.append("  ");
        }

        private static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.toString();
        }

        static final class Parser {
            final String s;
            int i;

            Parser(String s) { this.s = s; }

            Val parseValue() {
                skip();
                if (i >= s.length()) throw new RuntimeException("Unexpected end of JSON");
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return new Str(parseString());
                if (c == 't') { expect("true"); return new Bool(true); }
                if (c == 'f') { expect("false"); return new Bool(false); }
                if (c == 'n') { expect("null"); return Null.INSTANCE; }
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw new RuntimeException("Unexpected char at " + i + ": " + c);
            }

            Obj parseObject() {
                expect("{");
                Obj o = new Obj();
                skip();
                if (peek('}')) { i++; return o; }
                while (true) {
                    skip();
                    String key = parseString();
                    skip();
                    expect(":");
                    Val val = parseValue();
                    o.put(key, val);
                    skip();
                    if (peek('}')) { i++; break; }
                    expect(",");
                }
                return o;
            }

            Arr parseArray() {
                expect("[");
                Arr a = new Arr();
                skip();
                if (peek(']')) { i++; return a; }
                while (true) {
                    a.add(parseValue());
                    skip();
                    if (peek(']')) { i++; break; }
                    expect(",");
                }
                return a;
            }

            String parseString() {
                expect("\"");
                StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (i >= s.length()) throw new RuntimeException("Bad escape");
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': case '\\': case '/': sb.append(e); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (i + 4 > s.length()) throw new RuntimeException("Bad unicode");
                                int code = Integer.parseInt(s.substring(i, i + 4), 16);
                                sb.append((char) code);
                                i += 4;
                                break;
                            default: throw new RuntimeException("Bad escape: " + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new RuntimeException("Unterminated string");
            }

            Num parseNumber() {
                int start = i;
                if (peek('-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                boolean frac = false, exp = false;
                if (peek('.')) {
                    frac = true;
                    i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
                if (peek('e') || peek('E')) {
                    exp = true;
                    i++;
                    if (peek('+') || peek('-')) i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
                String num = s.substring(start, i);
                if (frac || exp) return new Num(Double.parseDouble(num));
                try {
                    return new Num(Long.parseLong(num));
                } catch (NumberFormatException e) {
                    return new Num(Double.parseDouble(num));
                }
            }

            void skip() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                    else break;
                }
            }

            boolean peek(char c) {
                return i < s.length() && s.charAt(i) == c;
            }

            void expect(String lit) {
                skip();
                if (!s.startsWith(lit, i)) throw new RuntimeException("Expected " + lit + " at " + i);
                i += lit.length();
            }
        }
    }

    // ===================== Util =====================
    static final class Util {
        static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }

        static boolean isMac() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("mac") || os.contains("darwin");
        }

        static Path defaultGameDir() {
            String home = System.getProperty("user.home");
            if (isWindows()) {
                String appdata = System.getenv("APPDATA");
                if (appdata != null && !appdata.isBlank()) {
                    return Paths.get(appdata, ".minecraft");
                }
                return Paths.get(home, "AppData", "Roaming", ".minecraft");
            }
            if (isMac()) {
                return Paths.get(home, "Library", "Application Support", "minecraft");
            }
            return Paths.get(home, ".minecraft");
        }

        static String getOsName() {
            if (isWindows()) return "windows";
            if (isMac()) return "osx";
            return "linux";
        }

        static String getArch() {
            String machine = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (machine.equals("x86_64") || machine.equals("amd64")) return "x64";
            if (machine.equals("aarch64") || machine.equals("arm64")) return "arm64";
            return "x86";
        }

        static String findJava() {
            List<Path> candidates = new ArrayList<>();
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null && !javaHome.isBlank()) {
                candidates.add(Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java"));
            }
            String javaHomeProp = System.getProperty("java.home");
            if (javaHomeProp != null) {
                candidates.add(0, Paths.get(javaHomeProp, "bin", isWindows() ? "java.exe" : "java"));
            }
            if (isWindows()) {
                candidates.add(Paths.get("C:/Program Files/Java/jdk-17/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/Java/jdk-21/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/Eclipse Adoptium/jdk-17/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/Eclipse Adoptium/jdk-21/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/BellSoft/LibericaJDK-21/bin/java.exe"));
                candidates.add(Paths.get("C:/Program Files/BellSoft/LibericaJDK-17/bin/java.exe"));
            } else if (isMac()) {
                candidates.add(Paths.get("/opt/homebrew/opt/openjdk@17/bin/java"));
                candidates.add(Paths.get("/opt/homebrew/opt/openjdk@21/bin/java"));
                candidates.add(Paths.get("/opt/homebrew/opt/openjdk/bin/java"));
                candidates.add(Paths.get("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java"));
                candidates.add(Paths.get("/usr/bin/java"));
            } else {
                candidates.add(Paths.get("/usr/lib/jvm/java-17-openjdk/bin/java"));
                candidates.add(Paths.get("/usr/lib/jvm/java-17-openjdk-amd64/bin/java"));
                candidates.add(Paths.get("/usr/bin/java"));
            }
            for (Path p : candidates) {
                if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
            }
            return "java";
        }

        static boolean matchOsVersionRule(String versionPattern) {
            if (versionPattern == null || versionPattern.isEmpty()) return true;
            String osVersion = System.getProperty("os.version", "");
            try {
                return Pattern.compile(versionPattern).matcher(osVersion).find();
            } catch (Exception e) {
                return false;
            }
        }

        static boolean checkRules(J.Arr rules) {
            if (rules == null || rules.size() == 0) return true;
            String osName = getOsName();
            String arch = getArch();
            boolean result = false;
            for (int i = 0; i < rules.size(); i++) {
                J.Obj rule = rules.get(i).asObj();
                String action = rule.getStr("action", "allow");
                boolean matches = true;
                if (rule.has("os")) {
                    J.Obj osRule = rule.getObj("os");
                    if (osRule != null) {
                        if (osRule.has("name") && !osName.equals(osRule.getStr("name", ""))) matches = false;
                        if (osRule.has("arch") && !arch.equals(osRule.getStr("arch", ""))) matches = false;
                        if (matches && osRule.has("version") && !matchOsVersionRule(osRule.getStr("version", ""))) matches = false;
                    }
                }
                if (matches) result = "allow".equals(action);
            }
            return result;
        }

        static boolean checkArgRules(J.Arr rules, Map<String, Boolean> features) {
            if (rules == null || rules.size() == 0) return true;
            if (features == null) features = Collections.emptyMap();
            String osName = getOsName();
            String arch = getArch();
            boolean result = false;
            for (int i = 0; i < rules.size(); i++) {
                J.Obj rule = rules.get(i).asObj();
                String action = rule.getStr("action", "allow");
                boolean matches = true;
                if (rule.has("os")) {
                    J.Obj osRule = rule.getObj("os");
                    if (osRule != null) {
                        if (osRule.has("name") && !osName.equals(osRule.getStr("name", ""))) matches = false;
                        if (osRule.has("arch") && !arch.equals(osRule.getStr("arch", ""))) matches = false;
                        if (matches && osRule.has("version") && !matchOsVersionRule(osRule.getStr("version", ""))) matches = false;
                    }
                }
                if (rule.has("features")) {
                    J.Obj featObj = rule.getObj("features");
                    if (featObj != null) {
                        for (Map.Entry<String, J.Val> e : featObj.map.entrySet()) {
                            boolean required = (e.getValue() instanceof J.Bool) && ((J.Bool) e.getValue()).v;
                            boolean have = features.getOrDefault(e.getKey(), false);
                            if (have != required) {
                                matches = false;
                                break;
                            }
                        }
                    }
                }
                if (matches) result = "allow".equals(action);
            }
            return result;
        }

        static String substituteVars(String text, Map<String, String> variables) {
            if (text == null) return null;
            String out = text;
            for (Map.Entry<String, String> e : variables.entrySet()) {
                out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
            return out;
        }

        static List<String> expandArguments(J.Arr argList, Map<String, String> variables, Map<String, Boolean> features) {
            List<String> expanded = new ArrayList<>();
            if (argList == null) return expanded;
            for (int i = 0; i < argList.size(); i++) {
                J.Val entry = argList.get(i);
                if (entry instanceof J.Str) {
                    expanded.add(substituteVars(((J.Str) entry).v, variables));
                } else if (entry instanceof J.Obj) {
                    J.Obj obj = (J.Obj) entry;
                    if (!checkArgRules(obj.getArr("rules"), features)) continue;
                    J.Val value = obj.get("value");
                    if (value instanceof J.Arr) {
                        J.Arr arr = (J.Arr) value;
                        for (int j = 0; j < arr.size(); j++) {
                            expanded.add(substituteVars(arr.get(j).asStr(), variables));
                        }
                    } else if (value instanceof J.Str) {
                        String s = ((J.Str) value).v;
                        if (s != null && !s.isEmpty()) {
                            expanded.add(substituteVars(s, variables));
                        }
                    }
                }
            }
            return expanded;
        }

        /** Minecraft offline UUID (vanilla OfflinePlayer: + nameUUIDFromBytes). */
        static String generateOfflineUuid(String username) {
            return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
        }

        static String calculateSha1(Path filepath) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                try (InputStream in = Files.newInputStream(filepath)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
                }
                byte[] dig = md.digest();
                StringBuilder sb = new StringBuilder(dig.length * 2);
                for (byte b : dig) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        static boolean fileIsValid(Path dest, String expectedHash, Long expectedSize) {
            if (!Files.isRegularFile(dest)) return false;
            try {
                long actual = Files.size(dest);
                if (expectedSize != null && actual != expectedSize) return false;
                if (expectedHash != null) {
                    return expectedHash.equalsIgnoreCase(calculateSha1(dest));
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        /**
         * Maven coords to relative jar path.
         * Supports g:a:v, g:a:v:classifier, and optional @ext (e.g. g:a:v@zip, g:a:v:natives-windows@jar).
         */
        static String mavenToRelPath(String name) {
            if (name == null || name.isEmpty()) return name;
            String ext = "jar";
            String coord = name;
            int at = name.lastIndexOf('@');
            if (at >= 0) {
                ext = name.substring(at + 1);
                coord = name.substring(0, at);
            }
            String[] parts = coord.split(":");
            if (parts.length < 3) return name;
            String group = parts[0], artifact = parts[1], version = parts[2];
            String classifier = parts.length >= 4 ? parts[3] : null;
            String groupPath = group.replace('.', '/');
            String fileName = artifact + "-" + version + (classifier != null && !classifier.isEmpty() ? "-" + classifier : "") + "." + ext;
            return groupPath + "/" + artifact + "/" + version + "/" + fileName;
        }

        /** Escape one token for a Java @argfile (one arg per line). */
        static String escapeArgFileToken(String arg) {
            if (arg == null) return "\"\"";
            boolean needsQuote = arg.isEmpty();
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c <= ' ' || c == '"' || c == '\\') {
                    needsQuote = true;
                    break;
                }
            }
            if (!needsQuote) return arg;
            StringBuilder sb = new StringBuilder(arg.length() + 8);
            sb.append('"');
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '"' || c == '\\') sb.append('\\');
                sb.append(c);
            }
            sb.append('"');
            return sb.toString();
        }

        static Path writeArgFile(List<String> args) throws IOException {
            Path argFile = GAME_DIR.resolve("catclient-argfile.txt");
            Files.createDirectories(GAME_DIR);
            StringBuilder sb = new StringBuilder();
            for (String arg : args) {
                sb.append(escapeArgFileToken(arg)).append('\n');
            }
            Files.writeString(argFile, sb.toString(), StandardCharsets.UTF_8);
            return argFile.toAbsolutePath();
        }

        static J.Obj mergeVersionInfo(J.Obj child, J.Obj parent) {
            J.Obj merged = J.parseObj(J.stringify(parent));
            merged.put("id", child.get("id"));
            if (child.has("mainClass")) merged.put("mainClass", child.get("mainClass"));
            J.Arr childLibs = child.getArr("libraries");
            J.Arr parentLibs = parent.getArr("libraries");
            J.Arr libs = new J.Arr();
            if (childLibs != null) for (int i = 0; i < childLibs.size(); i++) libs.add(childLibs.get(i));
            if (parentLibs != null) for (int i = 0; i < parentLibs.size(); i++) libs.add(parentLibs.get(i));
            merged.put("libraries", libs);
            if (child.has("arguments")) {
                J.Obj mergedArgs = merged.getObj("arguments");
                if (mergedArgs == null) {
                    mergedArgs = new J.Obj();
                    merged.put("arguments", mergedArgs);
                }
                J.Obj childArgs = child.getObj("arguments");
                for (String key : new String[]{"jvm", "game"}) {
                    J.Arr combined = new J.Arr();
                    J.Arr cv = childArgs != null ? childArgs.getArr(key) : null;
                    J.Arr pv = mergedArgs.getArr(key);
                    if (cv != null) for (int i = 0; i < cv.size(); i++) combined.add(cv.get(i));
                    if (pv != null) for (int i = 0; i < pv.size(); i++) combined.add(pv.get(i));
                    mergedArgs.put(key, combined);
                }
            }
            return merged;
        }

        static J.Obj resolveVersionInfo(J.Obj versionInfo) throws IOException {
            if (!versionInfo.has("inheritsFrom") || versionInfo.get("inheritsFrom").isNull()) {
                return versionInfo;
            }
            String parentId = versionInfo.getStr("inheritsFrom", null);
            Path parentPath = GAME_DIR.resolve("versions").resolve(parentId).resolve(parentId + ".json");
            if (!Files.exists(parentPath)) throw new FileNotFoundException("Parent version missing: " + parentId);
            J.Obj parent = J.parseObj(Files.readString(parentPath, StandardCharsets.UTF_8));
            parent = resolveVersionInfo(parent);
            return mergeVersionInfo(versionInfo, parent);
        }
    }

    // ===================== Net =====================
    static final class Net {
        static {
            try {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            } catch (Exception ignored) {}
        }

        static String fetchText(String urlStr, int timeoutSec) throws IOException {
            HttpURLConnection conn = open(urlStr, timeoutSec);
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }
        }

        static J.Obj fetchJson(String urlStr, int timeoutSec) throws IOException {
            return J.parseObj(fetchText(urlStr, timeoutSec));
        }

        static J.Val fetchJsonVal(String urlStr, int timeoutSec) throws IOException {
            return J.parse(fetchText(urlStr, timeoutSec));
        }

        static J.Obj fetchVersionManifest(int timeoutSec) throws IOException {
            IOException last = null;
            for (String url : VERSION_MANIFEST_URLS) {
                try {
                    return fetchJson(url, timeoutSec);
                } catch (IOException e) {
                    last = e;
                }
            }
            throw new IOException("Could not fetch version manifest: " + last, last);
        }

        /** Optional helper — FPS pack uses the selected combo version instead. */
        @SuppressWarnings("unused")
        static String getLatestReleaseId() throws IOException {
            J.Obj manifest = fetchVersionManifest(10);
            J.Obj latest = manifest.getObj("latest");
            if (latest == null) throw new IOException("Manifest missing latest");
            return latest.getStr("release", null);
        }

        static J.Arr fetchModrinthVersions(String slug, String gameVersion) throws IOException {
            String params = "game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8)
                    + "&loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8);
            String url = MODRINTH_API + "/project/" + slug + "/version?" + params;
            J.Val v = fetchJsonVal(url, 20);
            if (!(v instanceof J.Arr)) throw new IOException("Expected Modrinth version array for " + slug);
            return (J.Arr) v;
        }

        static HttpURLConnection open(String urlStr, int timeoutSec) throws IOException {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setHostnameVerifier((hostname, session) -> true);
            }
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code + " for " + urlStr);
            }
            return conn;
        }

        static boolean downloadFileFast(String urlStr, Path destPath, String expectedHash, Long expectedSize, int timeoutSec) {
            Path tmp = destPath.resolveSibling(destPath.getFileName().toString() + ".part");
            if (Util.fileIsValid(destPath, expectedHash, expectedSize)) return true;
            try {
                Files.createDirectories(destPath.getParent());
                HttpURLConnection conn = open(urlStr, timeoutSec);
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buf = new byte[DOWNLOAD_CHUNK];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                } finally {
                    conn.disconnect();
                }
                if (expectedHash != null && !expectedHash.equals(Util.calculateSha1(tmp))) {
                    Files.deleteIfExists(tmp);
                    return false;
                }
                if (expectedSize != null && Files.size(tmp) != expectedSize) {
                    Files.deleteIfExists(tmp);
                    return false;
                }
                try {
                    Files.move(tmp, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, destPath, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (Exception e) {
                System.out.println("Download failed (" + destPath.getFileName() + "): " + e.getMessage());
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                return false;
            }
        }

        static Object[] libraryDownloadTask(J.Obj lib, Path libsDir) {
            if (lib.has("downloads")) {
                J.Obj downloads = lib.getObj("downloads");
                if (downloads != null && downloads.has("artifact")) {
                    J.Obj artifact = downloads.getObj("artifact");
                    String path = artifact.getStr("path", null);
                    String url = artifact.getStr("url", null);
                    String sha1 = artifact.has("sha1") ? artifact.getStr("sha1", null) : null;
                    Long size = artifact.has("size") ? artifact.getLong("size", 0) : null;
                    if (!artifact.has("size")) size = null;
                    return new Object[]{url, libsDir.resolve(path), sha1, size};
                }
            }
            if (lib.has("name")) {
                String rel = Util.mavenToRelPath(lib.getStr("name", ""));
                String base = lib.getStr("url", "https://libraries.minecraft.net/");
                if (!base.endsWith("/")) base += "/";
                String sha1 = lib.has("sha1") ? lib.getStr("sha1", null) : null;
                Long size = lib.has("size") ? lib.getLong("size", 0) : null;
                if (!lib.has("size")) size = null;
                return new Object[]{base + rel, libsDir.resolve(rel), sha1, size};
            }
            return null;
        }
    }

    // ===================== AssetDownloader =====================
    static final class AssetDownloader {
        final Path gameDir;
        final Path objectsDir;
        final Path indexesDir;
        final IntConsumer progressCb;
        final Consumer<String> statusCb;
        final AtomicInteger downloaded = new AtomicInteger();
        int total;
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());

        AssetDownloader(Path gameDir, IntConsumer progressCb, Consumer<String> statusCb) {
            this.gameDir = gameDir;
            this.objectsDir = gameDir.resolve("assets").resolve("objects");
            this.indexesDir = gameDir.resolve("assets").resolve("indexes");
            this.progressCb = progressCb;
            this.statusCb = statusCb;
        }

        boolean downloadAsset(String assetHash, Long assetSize) {
            String prefix = assetHash.substring(0, 2);
            Path assetPath = objectsDir.resolve(prefix).resolve(assetHash);
            String url = ASSETS_URL + "/" + prefix + "/" + assetHash;
            boolean ok = Net.downloadFileFast(url, assetPath, assetHash, assetSize, 30);
            int d = downloaded.incrementAndGet();
            if (progressCb != null && total > 0) progressCb.accept((int) ((d / (double) total) * 100));
            if (!ok) failed.add(assetHash);
            return ok;
        }

        boolean downloadAllAssets(String assetIndexId, String assetIndexUrl) throws Exception {
            Files.createDirectories(objectsDir);
            Files.createDirectories(indexesDir);
            Path indexPath = indexesDir.resolve(assetIndexId + ".json");
            if (!Files.exists(indexPath) && assetIndexUrl != null) {
                if (statusCb != null) statusCb.accept("Downloading asset index...");
                Net.downloadFileFast(assetIndexUrl, indexPath, null, null, 30);
            }
            if (!Files.exists(indexPath)) throw new FileNotFoundException("Asset index not found: " + indexPath);

            J.Obj assetIndex = J.parseObj(Files.readString(indexPath, StandardCharsets.UTF_8));
            J.Obj objects = assetIndex.getObj("objects");
            if (objects == null) objects = new J.Obj();
            total = objects.map.size();
            downloaded.set(0);
            failed.clear();

            if (statusCb != null) statusCb.accept("Checking " + total + " assets...");

            List<Object[]> toDownload = new ArrayList<>();
            for (Map.Entry<String, J.Val> e : objects.map.entrySet()) {
                J.Obj info = e.getValue().asObj();
                String hash = info.getStr("hash", null);
                Long size = info.has("size") ? info.getLong("size", 0) : null;
                if (!info.has("size")) size = null;
                String prefix = hash.substring(0, 2);
                Path assetPath = objectsDir.resolve(prefix).resolve(hash);
                if (Util.fileIsValid(assetPath, hash, size)) {
                    downloaded.incrementAndGet();
                    continue;
                }
                toDownload.add(new Object[]{hash, size});
            }

            if (statusCb != null) statusCb.accept("Downloading " + toDownload.size() + " assets...");

            if (!toDownload.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(ASSET_WORKERS);
                try {
                    List<Future<?>> futures = new ArrayList<>();
                    for (Object[] item : toDownload) {
                        futures.add(pool.submit(() -> downloadAsset((String) item[0], (Long) item[1])));
                    }
                    for (Future<?> f : futures) f.get();
                } finally {
                    pool.shutdown();
                }
            }

            if (statusCb != null) {
                if (!failed.isEmpty()) statusCb.accept("Assets done (" + failed.size() + " failed)");
                else statusCb.accept("All assets downloaded!");
            }
            return failed.isEmpty();
        }
    }

    /** Original palette — warm night sky + ginger-cat accents. */
    static final class Palette {
        static final Color NIGHT = new Color(0x0B0F17);
        static final Color INK = new Color(0x121826);
        static final Color PANEL = new Color(0x1A2233);
        static final Color PANEL_LIFT = new Color(0x243044);
        static final Color STROKE = new Color(0x2E3A52);
        static final Color CREAM = new Color(0xF7EFE4);
        static final Color MUTED = new Color(0x9AA6BC);
        static final Color GINGER = new Color(0xE8913A);
        static final Color GINGER_HOT = new Color(0xF5A85A);
        static final Color PAW = new Color(0xFFB347);
        static final Color MEOW = new Color(0x7EC8E3);
        static final Color DANGER = new Color(0xC44536);
    }

    final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cat-worker");
        t.setDaemon(true);
        return t;
    });

    final String javaBin = Util.findJava();
    Process gameProcess;
    OutputStream logHandle;
    Thread logPumpThread;

    final CardLayout pages = new CardLayout();
    final JPanel pageHost = new JPanel(pages);
    final Map<String, JButton> nav = new LinkedHashMap<>();

    final JLabel skinFace = new JLabel("", SwingConstants.CENTER);
    final JLabel userLabel = new JLabel("Player", SwingConstants.CENTER);
    final JTextField username = new JTextField("Player");
    final JComboBox<String> versions = new JComboBox<>();
    final JSlider ram = new JSlider(1, 16, 4);
    final JLabel ramLabel = new JLabel("4096 MB");
    final JCheckBox fullscreen = new JCheckBox("Fullscreen window");
    final JCheckBox downloadAssets = new JCheckBox("Download all assets", true);
    final JLabel fpsPackLabel = new JLabel("Lunar-style FPS boosts (always on)");
    final JButton showModsBtn = new JButton("Show My Mods");
    final JLabel status = new JLabel("Pre-baked — ready for PLAY with FPS boosts");
    final JProgressBar progress = new JProgressBar(0, 100);
    final JButton playBtn = new JButton("PLAY");

    String activeNav = "Play";
    ImageIcon skinIcon;

    public CatClient() {
        super(APP_NAME);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(980, 600);
        setResizable(false);
        setLocationRelativeTo(null);
        setIconImage(Icons.catWindowIcon());
        try { Files.createDirectories(GAME_DIR); } catch (IOException ignored) {}

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Palette.NIGHT);
        setContentPane(root);

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);

        wireEvents();
        selectNav("Play");
        workers.execute(this::loadVersions);
        SwingUtilities.invokeLater(() -> refreshSkin(username.getText()));
    }

    void ui(Runnable r) { SwingUtilities.invokeLater(r); }
    void setStatus(String s) { ui(() -> status.setText(s)); }
    void setProgress(int p) { ui(() -> progress.setValue(p)); }

    // ---------- Header ----------
    JPanel buildHeader() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Palette.INK);
        bar.setPreferredSize(new Dimension(980, 42));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.STROKE));

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        brand.setOpaque(false);
        JLabel mark = new JLabel(new ImageIcon(Icons.catMark(22)));
        JLabel title = new JLabel("CAT CLIENT");
        title.setFont(new Font("Segoe UI", Font.BOLD, 14));
        title.setForeground(Palette.GINGER);
        JLabel sub = new JLabel("0.1  -  purrfect launches + FPS boosts");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        sub.setForeground(Palette.MUTED);
        brand.add(mark);
        brand.add(title);
        brand.add(sub);
        bar.add(brand, BorderLayout.WEST);

        JPanel chrome = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 6));
        chrome.setOpaque(false);
        chrome.add(chromeBtn("-", e -> setState(ICONIFIED)));
        chrome.add(chromeBtn("[]", e -> {}));
        JButton x = chromeBtn("x", e -> dispose());
        x.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                x.setBackground(Palette.DANGER);
                x.setForeground(Color.WHITE);
            }
            public void mouseExited(MouseEvent e) {
                x.setBackground(Palette.INK);
                x.setForeground(Palette.MUTED);
            }
        });
        chrome.add(x);
        bar.add(chrome, BorderLayout.EAST);
        return bar;
    }

    JButton chromeBtn(String text, ActionListener al) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        b.setForeground(Palette.MUTED);
        b.setBackground(Palette.INK);
        b.setBorder(new EmptyBorder(4, 12, 4, 12));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        return b;
    }

    // ---------- Body: sidebar + pages ----------
    JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Palette.NIGHT);
        body.add(buildSidebar(), BorderLayout.WEST);
        body.add(pageHost, BorderLayout.CENTER);

        pageHost.setBackground(Palette.NIGHT);
        pageHost.add(buildPlayPage(), "Play");
        pageHost.add(placeholderPage("Skins", "Wardrobe coming soon - bring your own whiskers."), "Skins");
        pageHost.add(placeholderPage("Settings", "Tweaks dens later. For now, use Play."), "Settings");
        pageHost.add(placeholderPage("About",
                "Cat Client 0.1 - original Swing UI.\n"
                        + "Every PLAY installs Fabric + a Lunar-style FPS mod pack for the selected version (Modrinth).\n"
                        + "Inspired by performance-client FPS features. Not affiliated with Mojang, Microsoft, or Lunar Client."), "About");
        return body;
    }

    JPanel buildSidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(Palette.INK);
        side.setPreferredSize(new Dimension(210, 0));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Palette.STROKE));

        side.add(Box.createVerticalStrut(16));

        JPanel skinCard = new JPanel(new BorderLayout());
        skinCard.setBackground(Palette.PANEL);
        skinCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.STROKE),
                new EmptyBorder(10, 10, 10, 10)));
        skinCard.setMaximumSize(new Dimension(180, 170));
        skinCard.setAlignmentX(Component.CENTER_ALIGNMENT);
        skinFace.setPreferredSize(new Dimension(140, 140));
        skinFace.setOpaque(true);
        skinFace.setBackground(Palette.PANEL_LIFT);
        skinFace.setIcon(new ImageIcon(Icons.catSilhouette(100)));
        skinCard.add(skinFace, BorderLayout.CENTER);
        side.add(skinCard);
        side.add(Box.createVerticalStrut(8));

        userLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        userLabel.setForeground(Palette.CREAM);
        userLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(userLabel);

        JLabel tag = new JLabel("Offline litter-box account");
        tag.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        tag.setForeground(Palette.MEOW);
        tag.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(tag);
        side.add(Box.createVerticalStrut(18));

        for (String name : new String[]{"Play", "Skins", "Settings", "About"}) {
            JButton b = navButton(name, Icons.navIcon(name, 16));
            nav.put(name, b);
            side.add(b);
            side.add(Box.createVerticalStrut(4));
        }

        side.add(Box.createVerticalGlue());

        JLabel paw = new JLabel(new ImageIcon(Icons.pawPrint(28)), SwingConstants.CENTER);
        paw.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(paw);
        JLabel tip = new JLabel("Made with cat energy");
        tip.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        tip.setForeground(Palette.MUTED);
        tip.setAlignmentX(Component.CENTER_ALIGNMENT);
        side.add(tip);
        side.add(Box.createVerticalStrut(14));
        return side;
    }

    JButton navButton(String name, Image icon) {
        JButton b = new JButton("  " + name, new ImageIcon(icon));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        b.setForeground(Palette.MUTED);
        b.setBackground(Palette.INK);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(10, 16, 10, 12));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.addActionListener(e -> selectNav(name));
        return b;
    }

    void selectNav(String name) {
        activeNav = name;
        for (Map.Entry<String, JButton> e : nav.entrySet()) {
            boolean on = e.getKey().equals(name);
            e.getValue().setBackground(on ? Palette.PANEL_LIFT : Palette.INK);
            e.getValue().setForeground(on ? Palette.GINGER : Palette.MUTED);
            e.getValue().setFont(new Font("Segoe UI", on ? Font.BOLD : Font.PLAIN, 13));
        }
        pages.show(pageHost, name);
    }

    JPanel buildPlayPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Palette.NIGHT);
        page.setBorder(new EmptyBorder(20, 24, 16, 24));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Palette.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.STROKE),
                new EmptyBorder(22, 24, 22, 24)));

        JLabel h = new JLabel("Launch nest");
        h.setFont(new Font("Segoe UI", Font.BOLD, 18));
        h.setForeground(Palette.CREAM);
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(h);

        JLabel hint = new JLabel("Pick a name, a version, and hit PLAY - the yarn ball awaits.");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        hint.setForeground(Palette.MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(hint);
        card.add(Box.createVerticalStrut(18));

        card.add(fieldRow("Username", username));
        card.add(Box.createVerticalStrut(12));

        JPanel verRow = new JPanel(new BorderLayout(8, 0));
        verRow.setOpaque(false);
        verRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        verRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel vl = new JLabel("Version");
        vl.setPreferredSize(new Dimension(90, 28));
        vl.setForeground(Palette.MUTED);
        versions.setBackground(Palette.PANEL_LIFT);
        versions.setForeground(Palette.CREAM);
        versions.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JButton refresh = iconButton(Icons.refresh(16), e -> {
            status.setText("Fetching version yarn...");
            workers.execute(this::loadVersions);
        });
        verRow.add(vl, BorderLayout.WEST);
        verRow.add(versions, BorderLayout.CENTER);
        verRow.add(refresh, BorderLayout.EAST);
        card.add(verRow);
        card.add(Box.createVerticalStrut(14));

        JPanel ramRow = new JPanel(new BorderLayout(8, 0));
        ramRow.setOpaque(false);
        ramRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        ramRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel rl = new JLabel("RAM");
        rl.setPreferredSize(new Dimension(90, 28));
        rl.setForeground(Palette.MUTED);
        ram.setBackground(Palette.PANEL);
        ram.setForeground(Palette.CREAM);
        ram.setOpaque(false);
        ramLabel.setForeground(Palette.GINGER);
        ramLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        ramLabel.setPreferredSize(new Dimension(80, 28));
        JPanel ramMid = new JPanel(new BorderLayout(8, 0));
        ramMid.setOpaque(false);
        ramMid.add(ramLabel, BorderLayout.WEST);
        ramMid.add(ram, BorderLayout.CENTER);
        ramRow.add(rl, BorderLayout.WEST);
        ramRow.add(ramMid, BorderLayout.CENTER);
        card.add(ramRow);
        card.add(Box.createVerticalStrut(12));

        fullscreen.setOpaque(false);
        fullscreen.setForeground(Palette.MUTED);
        fullscreen.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        fullscreen.setFocusPainted(false);
        fullscreen.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(fullscreen);
        card.add(Box.createVerticalStrut(6));

        downloadAssets.setOpaque(false);
        downloadAssets.setForeground(Palette.MUTED);
        downloadAssets.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        downloadAssets.setFocusPainted(false);
        downloadAssets.setAlignmentX(Component.LEFT_ALIGNMENT);
        downloadAssets.setSelected(true);
        downloadAssets.setEnabled(false);
        card.add(downloadAssets);
        card.add(Box.createVerticalStrut(6));

        fpsPackLabel.setOpaque(false);
        fpsPackLabel.setForeground(Palette.GINGER);
        fpsPackLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        fpsPackLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(fpsPackLabel);
        card.add(Box.createVerticalStrut(8));

        showModsBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        showModsBtn.setBackground(Palette.PANEL_LIFT);
        showModsBtn.setForeground(Palette.CREAM);
        showModsBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        showModsBtn.setFocusPainted(false);
        showModsBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.STROKE),
                new EmptyBorder(6, 12, 6, 12)));
        showModsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        showModsBtn.addActionListener(e -> showModsDialog());
        card.add(showModsBtn);

        page.add(card, BorderLayout.CENTER);
        return page;
    }

    JPanel fieldRow(String label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(90, 28));
        l.setForeground(Palette.MUTED);
        if (field instanceof JTextField) {
            JTextField tf = (JTextField) field;
            tf.setBackground(Palette.PANEL_LIFT);
            tf.setForeground(Palette.CREAM);
            tf.setCaretColor(Palette.CREAM);
            tf.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Palette.STROKE),
                    new EmptyBorder(6, 10, 6, 10)));
            tf.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        }
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    JPanel placeholderPage(String title, String body) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Palette.NIGHT);
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Palette.PANEL);
        card.setBorder(new EmptyBorder(28, 32, 28, 32));
        JLabel t = new JLabel(title);
        t.setFont(new Font("Segoe UI", Font.BOLD, 20));
        t.setForeground(Palette.GINGER);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel b = new JLabel("<html><body style='width:420px;color:#9AA6BC'>" + body.replace("\n", "<br>") + "</body></html>");
        b.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(t);
        card.add(Box.createVerticalStrut(10));
        card.add(b);
        p.add(card);
        return p;
    }

    // ---------- Footer ----------
    JPanel buildFooter() {
        JPanel foot = new JPanel(new BorderLayout());
        foot.setBackground(Palette.INK);
        foot.setPreferredSize(new Dimension(980, 78));
        foot.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.STROKE));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);
        left.setBorder(new EmptyBorder(14, 20, 10, 10));
        status.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        status.setForeground(Palette.MUTED);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setPreferredSize(new Dimension(420, 8));
        progress.setMaximumSize(new Dimension(420, 8));
        progress.setForeground(Palette.GINGER);
        progress.setBackground(Palette.PANEL_LIFT);
        progress.setBorderPainted(false);
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(status);
        left.add(Box.createVerticalStrut(8));
        left.add(progress);
        foot.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 14));
        right.setOpaque(false);
        playBtn.setFont(new Font("Segoe UI", Font.BOLD, 18));
        playBtn.setForeground(Palette.NIGHT);
        playBtn.setBackground(Palette.GINGER);
        playBtn.setIcon(new ImageIcon(Icons.playPaw(22)));
        playBtn.setFocusPainted(false);
        playBtn.setBorder(new EmptyBorder(10, 36, 10, 36));
        playBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        playBtn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                if (playBtn.isEnabled()) playBtn.setBackground(Palette.GINGER_HOT);
            }
            public void mouseExited(MouseEvent e) {
                if (playBtn.isEnabled()) playBtn.setBackground(Palette.GINGER);
            }
        });
        right.add(playBtn);
        foot.add(right, BorderLayout.EAST);
        return foot;
    }

    JButton iconButton(Image img, ActionListener al) {
        JButton b = new JButton(new ImageIcon(img));
        b.setBackground(Palette.PANEL_LIFT);
        b.setBorder(new EmptyBorder(6, 10, 6, 10));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(Palette.STROKE); }
            public void mouseExited(MouseEvent e) { b.setBackground(Palette.PANEL_LIFT); }
        });
        return b;
    }

    // ---------- Events / data ----------
    void wireEvents() {
        username.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { onUserEdit(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { onUserEdit(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onUserEdit(); }
        });
        ram.addChangeListener(e -> ramLabel.setText((ram.getValue() * 1024) + " MB"));
        playBtn.addActionListener(e -> onPlay());
    }

    javax.swing.Timer skinDebounce;

    void onUserEdit() {
        String u = username.getText().trim();
        userLabel.setText(u.isEmpty() ? "-" : u);
        if (skinDebounce != null) skinDebounce.stop();
        skinDebounce = new javax.swing.Timer(500, ev -> refreshSkin(username.getText().trim()));
        skinDebounce.setRepeats(false);
        skinDebounce.start();
    }

    void refreshSkin(String name) {
        if (name == null || name.isEmpty()) {
            skinFace.setIcon(new ImageIcon(Icons.catSilhouette(100)));
            skinFace.setText("");
            return;
        }
        workers.execute(() -> {
            try {
                String url = String.format(SKIN_URL, name);
                HttpURLConnection c = Net.open(url, 5);
                try (InputStream in = c.getInputStream()) {
                    BufferedImage img = ImageIO.read(in);
                    if (img != null) {
                        Image scaled = img.getScaledInstance(140, 140, Image.SCALE_SMOOTH);
                        skinIcon = new ImageIcon(scaled);
                        SwingUtilities.invokeLater(() -> {
                            skinFace.setIcon(skinIcon);
                            skinFace.setText("");
                        });
                        return;
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {}
            String shortName = name.length() > 8 ? name.substring(0, 8) : name;
            SwingUtilities.invokeLater(() -> {
                skinFace.setIcon(new ImageIcon(Icons.catSilhouette(80)));
                skinFace.setText(shortName);
                skinFace.setForeground(Palette.MUTED);
                skinFace.setHorizontalTextPosition(SwingConstants.CENTER);
                skinFace.setVerticalTextPosition(SwingConstants.BOTTOM);
            });
        });
    }

    void loadVersions() {
        try {
            J.Obj data = Net.fetchVersionManifest(10);
            J.Arr arr = data.getArr("versions");
            List<String> releases = new ArrayList<>();
            List<String> snapshots = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    J.Obj v = arr.get(i).asObj();
                    String type = v.getStr("type", "");
                    String id = v.getStr("id", "");
                    if ("release".equals(type)) {
                        releases.add(id + " (release)");
                    } else if ("snapshot".equals(type) && snapshots.size() < 20) {
                        snapshots.add(id + " (snapshot)");
                    }
                    if (releases.size() + snapshots.size() >= 80) break;
                }
            }
            List<String> list = new ArrayList<>(releases.size() + snapshots.size());
            list.addAll(releases);
            list.addAll(snapshots);
            SwingUtilities.invokeLater(() -> {
                versions.removeAllItems();
                for (String v : list) versions.addItem(v);
                if (!list.isEmpty()) versions.setSelectedIndex(0);
                status.setText("Pre-baked — ready for PLAY with FPS boosts");
                progress.setValue(0);
            });
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
                versions.removeAllItems();
                for (String v : new String[]{"1.21.4 (release)", "1.20.1 (release)", "1.19.4 (release)"}) {
                    versions.addItem(v);
                }
                versions.setSelectedIndex(0);
                status.setText("Offline catalog - using fallback versions");
            });
        }
    }

    // ===================== Download / Launch =====================
    List<Path> downloadLibraries(J.Obj versionInfo, Consumer<String> statusCb) throws Exception {
        Path libsDir = GAME_DIR.resolve("libraries");
        Files.createDirectories(libsDir);
        String osName = Util.getOsName();
        List<Object[]> downloadTasks = new ArrayList<>();
        List<Path> nativePaths = new ArrayList<>();

        J.Arr libraries = versionInfo.getArr("libraries");
        if (libraries == null) return nativePaths;

        for (int i = 0; i < libraries.size(); i++) {
            J.Obj lib = libraries.get(i).asObj();
            if (lib.has("rules") && !Util.checkRules(lib.getArr("rules"))) continue;

            Object[] task = Net.libraryDownloadTask(lib, libsDir);
            if (task != null) {
                Path path = (Path) task[1];
                String sha1 = (String) task[2];
                Long size = (Long) task[3];
                if (!Util.fileIsValid(path, sha1, size)) downloadTasks.add(task);
            }

            if (lib.has("natives") && lib.has("downloads")) {
                J.Obj natives = lib.getObj("natives");
                String nativeKey = natives.getStr(osName, "");
                if (nativeKey.contains("${arch}")) {
                    String bits = ("x64".equals(Util.getArch()) || "arm64".equals(Util.getArch())) ? "64" : "32";
                    nativeKey = nativeKey.replace("${arch}", bits);
                }
                J.Obj downloads = lib.getObj("downloads");
                if (!nativeKey.isEmpty() && downloads != null && downloads.has("classifiers")) {
                    J.Obj classifiers = downloads.getObj("classifiers");
                    if (classifiers != null && classifiers.has(nativeKey)) {
                        J.Obj nativeInfo = classifiers.getObj(nativeKey);
                        Path nativePath = libsDir.resolve(nativeInfo.getStr("path", ""));
                        String sha1 = nativeInfo.has("sha1") ? nativeInfo.getStr("sha1", null) : null;
                        Long size = nativeInfo.has("size") ? nativeInfo.getLong("size", 0) : null;
                        if (!nativeInfo.has("size")) size = null;
                        if (!Util.fileIsValid(nativePath, sha1, size)) {
                            downloadTasks.add(new Object[]{
                                    nativeInfo.getStr("url", null), nativePath, sha1, size
                            });
                        }
                        nativePaths.add(nativePath);
                    }
                }
            }
        }

        if (!downloadTasks.isEmpty()) {
            if (statusCb != null) statusCb.accept("Downloading " + downloadTasks.size() + " libraries...");
            ExecutorService pool = Executors.newFixedThreadPool(LIB_WORKERS);
            try {
                List<Future<Boolean>> futures = new ArrayList<>();
                for (Object[] t : downloadTasks) {
                    futures.add(pool.submit(() -> Net.downloadFileFast(
                            (String) t[0], (Path) t[1], (String) t[2], (Long) t[3], 60)));
                }
                for (Future<Boolean> f : futures) {
                    if (!Boolean.TRUE.equals(f.get())) throw new RuntimeException("Some libraries failed to download");
                }
            } finally {
                pool.shutdown();
            }
        }
        return nativePaths;
    }

    String buildClasspath(J.Obj resolvedInfo, String clientId) {
        Path libsDir = GAME_DIR.resolve("libraries");
        List<String> parts = new ArrayList<>();
        J.Arr libraries = resolvedInfo.getArr("libraries");
        if (libraries != null) {
            for (int i = 0; i < libraries.size(); i++) {
                J.Obj lib = libraries.get(i).asObj();
                if (lib.has("rules") && !Util.checkRules(lib.getArr("rules"))) continue;
                Object[] task = Net.libraryDownloadTask(lib, libsDir);
                if (task == null) continue;
                Path libPath = (Path) task[1];
                if (Files.exists(libPath)) parts.add(libPath.toAbsolutePath().toString());
            }
        }
        Path jarPath = GAME_DIR.resolve("versions").resolve(clientId).resolve(clientId + ".jar");
        if (Files.exists(jarPath)) parts.add(jarPath.toAbsolutePath().toString());
        return String.join(CLASSPATH_SEP, parts);
    }

    void clearNativesDir(Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(nativesDir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) Files.deleteIfExists(p);
            }
        }
    }

    boolean nativesDirHasFiles(Path nativesDir) throws IOException {
        if (!Files.isDirectory(nativesDir)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(nativesDir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) return true;
            }
        }
        return false;
    }

    void extractNatives(Path nativePath, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(nativePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("META-INF/")) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")) {
                    String base = Paths.get(name).getFileName().toString();
                    Path target = nativesDir.resolve(base);
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zis.transferTo(out);
                    }
                    if (!Util.isWindows()) {
                        try {
                            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
                            Files.setPosixFilePermissions(target, perms);
                        } catch (Exception ex) {
                            File f = target.toFile();
                            f.setReadable(true, false);
                            f.setWritable(true, false);
                            f.setExecutable(true, false);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IOException("Native extract failed for " + nativePath.getFileName() + ": " + e.getMessage(), e);
        }
    }

    Object[] installFabric(String gameVersion, Consumer<String> statusCb) throws Exception {
        if (statusCb != null) statusCb.accept("Installing Fabric for " + gameVersion + "...");
        J.Val loadersVal = Net.fetchJsonVal(FABRIC_META + "/versions/loader/" + gameVersion, 15);
        if (!(loadersVal instanceof J.Arr) || ((J.Arr) loadersVal).size() == 0) {
            throw new RuntimeException("No Fabric loader for Minecraft " + gameVersion);
        }
        J.Arr loaders = (J.Arr) loadersVal;
        J.Obj stable = null;
        for (int i = 0; i < loaders.size(); i++) {
            J.Obj entry = loaders.get(i).asObj();
            J.Obj loader = entry.getObj("loader");
            if (loader != null && loader.getBool("stable", false)) {
                stable = entry;
                break;
            }
        }
        if (stable == null) stable = loaders.get(0).asObj();
        String loaderVersion = stable.getObj("loader").getStr("version", null);
        J.Obj profile = Net.fetchJson(
                FABRIC_META + "/versions/loader/" + gameVersion + "/" + loaderVersion + "/profile/json", 15);
        String fabricId = profile.getStr("id", null);
        Path fabricDir = GAME_DIR.resolve("versions").resolve(fabricId);
        Files.createDirectories(fabricDir);
        Files.writeString(fabricDir.resolve(fabricId + ".json"), J.stringifyPretty(profile), StandardCharsets.UTF_8);
        downloadLibraries(profile, statusCb);
        return new Object[]{profile, fabricId};
    }

    Object[] installSingleFpsMod(String slug, String label, String gameVersion, Path modsDir) {
        try {
            J.Arr versions = Net.fetchModrinthVersions(slug, gameVersion);
            if (versions.size() == 0) return new Object[]{null, label};
            J.Obj release = null;
            for (int i = 0; i < versions.size(); i++) {
                J.Obj v = versions.get(i).asObj();
                if ("release".equals(v.getStr("version_type", ""))) {
                    release = v;
                    break;
                }
            }
            if (release == null) release = versions.get(0).asObj();
            J.Arr files = release.getArr("files");
            if (files == null || files.size() == 0) return new Object[]{null, label};
            J.Obj primary = null;
            for (int i = 0; i < files.size(); i++) {
                J.Obj f = files.get(i).asObj();
                if (f.getBool("primary", false)) {
                    primary = f;
                    break;
                }
            }
            if (primary == null) primary = files.get(0).asObj();
            String filename = primary.getStr("filename", null);
            Path dest = modsDir.resolve(filename);
            J.Obj hashes = primary.getObj("hashes");
            String sha1 = hashes != null ? hashes.getStr("sha1", null) : null;
            Long size = primary.has("size") ? primary.getLong("size", 0) : null;
            if (!primary.has("size")) size = null;
            if (Net.downloadFileFast(primary.getStr("url", null), dest, sha1, size, 90)) {
                return new Object[]{filename, null};
            }
            return new Object[]{null, label};
        } catch (Exception e) {
            return new Object[]{null, label};
        }
    }

    List<String> installFpsMods(String gameVersion, Consumer<String> statusCb, IntConsumer progressCb) throws Exception {
        Path modsDir = GAME_DIR.resolve("mods");
        Files.createDirectories(modsDir);
        Path marker = modsDir.resolve(".catclient-fps.json");

        if (Files.exists(marker)) {
            try {
                J.Val parsed = J.parse(Files.readString(marker, StandardCharsets.UTF_8));
                if (parsed instanceof J.Obj) {
                    J.Obj mark = (J.Obj) parsed;
                    if (gameVersion.equals(mark.getStr("gameVersion", ""))) {
                        J.Arr files = mark.getArr("files");
                        boolean allOk = files != null && files.size() > 0;
                        List<String> existing = new ArrayList<>();
                        if (allOk) {
                            for (int i = 0; i < files.size(); i++) {
                                String name = files.get(i).asStr();
                                Path p = modsDir.resolve(name);
                                if (!Files.isRegularFile(p) || Files.size(p) <= 0) {
                                    allOk = false;
                                    break;
                                }
                                existing.add(name);
                            }
                        }
                        if (allOk) {
                            if (statusCb != null) statusCb.accept("FPS pack already installed for " + gameVersion);
                            if (progressCb != null) progressCb.accept(100);
                            return existing;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        List<String> installed = Collections.synchronizedList(new ArrayList<>());
        List<String> failed = Collections.synchronizedList(new ArrayList<>());
        int total = FPS_MODS.length;
        AtomicInteger done = new AtomicInteger();

        if (statusCb != null) statusCb.accept("Installing FPS pack (" + total + " mods) for " + gameVersion + "...");

        ExecutorService pool = Executors.newFixedThreadPool(MOD_WORKERS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String[] entry : FPS_MODS) {
                String slug = entry[0];
                String label = entry[1];
                futures.add(pool.submit(() -> {
                    Object[] result = installSingleFpsMod(slug, label, gameVersion, modsDir);
                    String filename = (String) result[0];
                    String failLabel = (String) result[1];
                    if (filename != null) installed.add(filename);
                    else if (failLabel != null) failed.add(failLabel);
                    int d = done.incrementAndGet();
                    if (progressCb != null) progressCb.accept((int) ((d / (double) total) * 100));
                    if (statusCb != null && d % 3 == 0) statusCb.accept("FPS mods " + d + "/" + total + "...");
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdown();
        }

        boolean hasFabricApi = false;
        boolean hasSodium = false;
        for (String name : installed) {
            if (slugMatch("fabric-api", name)) hasFabricApi = true;
            if (slugMatch("sodium", name)) hasSodium = true;
        }

        if (!installed.isEmpty()) {
            J.Obj out = new J.Obj();
            out.put("gameVersion", new J.Str(gameVersion));
            J.Arr filesArr = new J.Arr();
            for (String name : installed) filesArr.add(new J.Str(name));
            out.put("files", filesArr);
            Files.writeString(marker, J.stringifyPretty(out), StandardCharsets.UTF_8);
        }

        if (statusCb != null) {
            if (installed.isEmpty()) {
                statusCb.accept("Warning: no FPS mods installed; continuing with Fabric launch");
            } else {
                String msg = "FPS pack: " + installed.size() + " mods installed";
                if (!failed.isEmpty()) msg += " (" + failed.size() + " skipped)";
                if (!hasFabricApi) msg += " (fabric-api missing)";
                else if (!hasSodium) msg += " (sodium missing)";
                statusCb.accept(msg);
            }
        }
        if (progressCb != null) progressCb.accept(100);
        return new ArrayList<>(installed);
    }

    static boolean slugMatch(String slug, String filename) {
        String key = slug.replace("-", "").toLowerCase(Locale.ROOT);
        String name = filename.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return name.contains(key);
    }

    Object[] getModEntries() throws IOException {
        Path modsDir = GAME_DIR.resolve("mods");
        Files.createDirectories(modsDir);
        List<Path> jarFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path p : ds) jarFiles.add(p);
        }
        List<String> managed = new ArrayList<>();
        Path marker = modsDir.resolve(".catclient-fps.json");
        if (Files.exists(marker)) {
            try {
                J.Val v = J.parse(Files.readString(marker, StandardCharsets.UTF_8));
                if (v instanceof J.Obj) {
                    J.Arr arr = ((J.Obj) v).getArr("files");
                    if (arr != null) {
                        for (int i = 0; i < arr.size(); i++) managed.add(arr.get(i).asStr());
                    }
                } else if (v instanceof J.Arr) {
                    J.Arr arr = (J.Arr) v;
                    for (int i = 0; i < arr.size(); i++) managed.add(arr.get(i).asStr());
                }
            } catch (Exception ignored) {}
        }

        List<Map<String, Object>> packMods = new ArrayList<>();
        Set<String> matchedFiles = new HashSet<>();
        for (String[] entry : FPS_MODS) {
            String slug = entry[0];
            String label = entry[1];
            String match = null;
            for (Path jar : jarFiles) {
                String name = jar.getFileName().toString();
                if (slugMatch(slug, name)) {
                    match = name;
                    matchedFiles.add(name);
                    break;
                }
            }
            if (match == null) {
                for (String name : managed) {
                    if (slugMatch(slug, name) && Files.exists(modsDir.resolve(name))) {
                        match = name;
                        matchedFiles.add(name);
                        break;
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("slug", slug);
            row.put("filename", match != null ? match : "-");
            row.put("installed", match != null);
            row.put("pack", true);
            packMods.add(row);
        }

        List<Map<String, Object>> otherMods = new ArrayList<>();
        jarFiles.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (Path jar : jarFiles) {
            String name = jar.getFileName().toString();
            if (!matchedFiles.contains(name)) {
                String stem = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", stem);
                row.put("slug", stem);
                row.put("filename", name);
                row.put("installed", true);
                row.put("pack", false);
                otherMods.add(row);
            }
        }
        return new Object[]{packMods, otherMods};
    }

    @SuppressWarnings("unchecked")
    void refreshModsDialogList(JPanel listInner, JLabel summary) {
        listInner.removeAll();
        try {
            Object[] entries = getModEntries();
            List<Map<String, Object>> packMods = (List<Map<String, Object>>) entries[0];
            List<Map<String, Object>> otherMods = (List<Map<String, Object>>) entries[1];
            int installed = 0;
            for (Map<String, Object> m : packMods) if (Boolean.TRUE.equals(m.get("installed"))) installed++;

            JLabel packHead = new JLabel("FPS pack (" + installed + "/" + packMods.size() + " installed)");
            packHead.setForeground(Palette.GINGER);
            packHead.setFont(new Font("Segoe UI", Font.BOLD, 13));
            packHead.setAlignmentX(Component.LEFT_ALIGNMENT);
            listInner.add(packHead);
            listInner.add(Box.createVerticalStrut(6));

            for (Map<String, Object> m : packMods) {
                boolean ok = Boolean.TRUE.equals(m.get("installed"));
                JLabel row = new JLabel((ok ? "[OK] " : "[--] ") + m.get("label") + "  -  " + m.get("filename"));
                row.setForeground(ok ? Palette.CREAM : Palette.DANGER);
                row.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                listInner.add(row);
                listInner.add(Box.createVerticalStrut(2));
            }

            if (!otherMods.isEmpty()) {
                listInner.add(Box.createVerticalStrut(10));
                JLabel otherHead = new JLabel("Other jars in mods/");
                otherHead.setForeground(Palette.MEOW);
                otherHead.setFont(new Font("Segoe UI", Font.BOLD, 13));
                otherHead.setAlignmentX(Component.LEFT_ALIGNMENT);
                listInner.add(otherHead);
                listInner.add(Box.createVerticalStrut(6));
                for (Map<String, Object> m : otherMods) {
                    JLabel row = new JLabel("* " + m.get("filename"));
                    row.setForeground(Palette.MUTED);
                    row.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                    row.setAlignmentX(Component.LEFT_ALIGNMENT);
                    listInner.add(row);
                    listInner.add(Box.createVerticalStrut(2));
                }
            }

            summary.setText(GAME_DIR.resolve("mods") + " - " + installed + " of " + FPS_MODS.length + " FPS mods present");
        } catch (Exception e) {
            JLabel err = new JLabel("Could not read mods: " + e.getMessage());
            err.setForeground(Palette.DANGER);
            listInner.add(err);
            summary.setText("Error reading mods folder");
        }
        listInner.revalidate();
        listInner.repaint();
    }

    void showModsDialog() {
        JDialog dlg = new JDialog(this, "My Mods", true);
        dlg.setSize(520, 480);
        dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(Palette.NIGHT);
        dlg.setLayout(new BorderLayout(0, 0));

        JLabel summary = new JLabel(" ");
        summary.setForeground(Palette.MUTED);
        summary.setBorder(new EmptyBorder(12, 16, 8, 16));
        dlg.add(summary, BorderLayout.NORTH);

        JPanel listInner = new JPanel();
        listInner.setLayout(new BoxLayout(listInner, BoxLayout.Y_AXIS));
        listInner.setBackground(Palette.PANEL);
        listInner.setBorder(new EmptyBorder(12, 16, 12, 16));
        JScrollPane scroll = new JScrollPane(listInner);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Palette.PANEL);
        dlg.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        bottom.setBackground(Palette.INK);
        JButton refresh = new JButton("Refresh");
        refresh.setBackground(Palette.PANEL_LIFT);
        refresh.setForeground(Palette.CREAM);
        refresh.setFocusPainted(false);
        refresh.addActionListener(e -> refreshModsDialogList(listInner, summary));
        JButton close = new JButton("Close");
        close.setBackground(Palette.GINGER);
        close.setForeground(Palette.NIGHT);
        close.setFocusPainted(false);
        close.addActionListener(e -> dlg.dispose());
        bottom.add(refresh);
        bottom.add(close);
        dlg.add(bottom, BorderLayout.SOUTH);

        refreshModsDialogList(listInner, summary);
        dlg.setVisible(true);
    }

    Object[] downloadVersion(String versionId, boolean needAssets, IntConsumer progressCb, Consumer<String> statusCb) throws Exception {
        String actualId = versionId.contains(" (") ? versionId.split(" \\(", 2)[0] : versionId;
        if (statusCb != null) statusCb.accept("Fetching " + actualId + "...");

        J.Obj manifest = Net.fetchVersionManifest(15);
        String versionUrl = null;
        J.Arr vers = manifest.getArr("versions");
        if (vers == null) throw new RuntimeException("Version manifest missing versions list");
        for (int i = 0; i < vers.size(); i++) {
            J.Obj v = vers.get(i).asObj();
            if (actualId.equals(v.getStr("id", ""))) {
                versionUrl = v.getStr("url", null);
                break;
            }
        }
        if (versionUrl == null) throw new IllegalArgumentException("Version " + actualId + " not found");

        J.Obj versionInfo = Net.fetchJson(versionUrl, 15);
        Path versionDir = GAME_DIR.resolve("versions").resolve(actualId);
        Files.createDirectories(versionDir);
        Path nativesDir = versionDir.resolve("natives");
        clearNativesDir(nativesDir);
        Files.createDirectories(GAME_DIR.resolve("libraries"));

        Files.writeString(versionDir.resolve(actualId + ".json"), J.stringifyPretty(versionInfo), StandardCharsets.UTF_8);

        Path jarPath = versionDir.resolve(actualId + ".jar");
        J.Obj downloads = versionInfo.getObj("downloads");
        if (downloads == null || !downloads.has("client")) {
            throw new RuntimeException("Version JSON missing downloads.client for " + actualId);
        }
        J.Obj client = downloads.getObj("client");
        if (client == null) {
            throw new RuntimeException("Version JSON missing downloads.client for " + actualId);
        }
        String clientUrl = client.getStr("url", null);
        if (clientUrl == null || clientUrl.isEmpty()) {
            throw new RuntimeException("Version JSON client download URL missing for " + actualId);
        }
        String clientSha1 = client.has("sha1") ? client.getStr("sha1", null) : null;
        Long clientSize = client.has("size") ? client.getLong("size", 0) : null;
        if (!client.has("size")) clientSize = null;
        if (!Util.fileIsValid(jarPath, clientSha1, clientSize)) {
            if (statusCb != null) statusCb.accept("Downloading " + actualId + ".jar...");
            if (!Net.downloadFileFast(clientUrl, jarPath, clientSha1, clientSize, 60)) {
                throw new RuntimeException("Failed to download " + actualId + ".jar");
            }
        }
        if (progressCb != null) progressCb.accept(20);

        List<Path> nativePaths = downloadLibraries(versionInfo, statusCb);
        for (Path np : nativePaths) {
            if (!Files.exists(np)) {
                throw new FileNotFoundException("Native library missing after download: " + np);
            }
            extractNatives(np, nativesDir);
        }
        if (!nativePaths.isEmpty() && !nativesDirHasFiles(nativesDir)) {
            throw new RuntimeException("Natives directory empty after extract for " + actualId);
        }
        if (progressCb != null) progressCb.accept(40);

        J.Obj assetIndex = versionInfo.getObj("assetIndex");
        if (assetIndex == null) {
            throw new RuntimeException("Version JSON missing assetIndex for " + actualId);
        }
        String assetIndexId = assetIndex.getStr("id", null);
        String assetIndexUrl = assetIndex.getStr("url", null);
        if (assetIndexId == null || assetIndexId.isEmpty()) {
            throw new RuntimeException("Version JSON assetIndex.id missing for " + actualId);
        }

        if (needAssets) {
            if (statusCb != null) statusCb.accept("Downloading assets...");
            AssetDownloader ad = new AssetDownloader(GAME_DIR, p -> {
                if (progressCb != null) progressCb.accept(40 + (int) (p * 0.55));
            }, statusCb);
            boolean ok = ad.downloadAllAssets(assetIndexId, assetIndexUrl);
            if (!ok || !ad.failed.isEmpty()) {
                if (statusCb != null) statusCb.accept("Retrying failed assets...");
                ad = new AssetDownloader(GAME_DIR, p -> {
                    if (progressCb != null) progressCb.accept(40 + (int) (p * 0.55));
                }, statusCb);
                ok = ad.downloadAllAssets(assetIndexId, assetIndexUrl);
                if (!ok || !ad.failed.isEmpty()) {
                    throw new RuntimeException("Asset download failed (" + ad.failed.size()
                            + " assets). Check network and retry.");
                }
            }
        } else {
            Path indexPath = GAME_DIR.resolve("assets").resolve("indexes").resolve(assetIndexId + ".json");
            if (!Files.exists(indexPath)) {
                if (assetIndexUrl == null || assetIndexUrl.isEmpty()) {
                    throw new RuntimeException("Asset index URL missing for " + actualId);
                }
                String sha1 = assetIndex.has("sha1") ? assetIndex.getStr("sha1", null) : null;
                Net.downloadFileFast(assetIndexUrl, indexPath, sha1, null, 60);
            }
        }

        if (statusCb != null) statusCb.accept(actualId + " ready");
        if (progressCb != null) progressCb.accept(100);
        return new Object[]{versionInfo, actualId};
    }

    List<String> buildLaunchArgs(J.Obj versionInfo, String actualId, String user, int ramMb,
                                 Path nativesDir, String classpath, boolean fullscreenFlag) {
        String mainClass = versionInfo.getStr("mainClass", "net.minecraft.client.main.Main");
        String offlineUuid = Util.generateOfflineUuid(user);
        String userType = "legacy";
        J.Obj assetIndex = versionInfo.getObj("assetIndex");
        String assetsIndexName = assetIndex != null ? assetIndex.getStr("id", "") : "";
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("natives_directory", nativesDir.toAbsolutePath().toString());
        variables.put("launcher_name", "CatClient");
        variables.put("launcher_version", "0.1");
        variables.put("classpath", classpath);
        variables.put("auth_player_name", user);
        variables.put("version_name", actualId);
        variables.put("game_directory", GAME_DIR.toAbsolutePath().toString());
        variables.put("assets_root", GAME_DIR.resolve("assets").toAbsolutePath().toString());
        variables.put("assets_index_name", assetsIndexName);
        variables.put("auth_uuid", offlineUuid);
        variables.put("auth_access_token", "0");
        variables.put("clientid", "");
        variables.put("auth_xuid", "");
        variables.put("user_type", userType);
        variables.put("version_type", versionInfo.getStr("type", "release"));

        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        features.put("has_custom_resolution", false);
        features.put("has_quick_plays_support", false);
        features.put("is_quick_play_singleplayer", false);
        features.put("is_quick_play_multiplayer", false);
        features.put("is_quick_play_realms", false);

        List<String> memory = new ArrayList<>(Arrays.asList(javaBin, "-Xmx" + ramMb + "M", "-Xms512M"));
        List<String> args;
        if (versionInfo.has("arguments")) {
            J.Obj arguments = versionInfo.getObj("arguments");
            List<String> jvmArgs = Util.expandArguments(arguments.getArr("jvm"), variables, features);
            List<String> gameArgs = Util.expandArguments(arguments.getArr("game"), variables, features);
            args = new ArrayList<>(memory);
            args.addAll(jvmArgs);
            args.add(mainClass);
            args.addAll(gameArgs);
        } else {
            args = new ArrayList<>(memory);
            args.addAll(Arrays.asList(
                    "-Djava.library.path=" + nativesDir.toAbsolutePath(),
                    "-Dminecraft.launcher.brand=CatClient",
                    "-Dminecraft.launcher.version=0.1",
                    "-cp", classpath,
                    mainClass,
                    "--username", user,
                    "--version", actualId,
                    "--gameDir", GAME_DIR.toAbsolutePath().toString(),
                    "--assetsDir", GAME_DIR.resolve("assets").toAbsolutePath().toString(),
                    "--assetIndex", assetsIndexName,
                    "--uuid", offlineUuid,
                    "--accessToken", "0",
                    "--userType", userType,
                    "--versionType", versionInfo.getStr("type", "release")
            ));
        }
        if (fullscreenFlag && !args.contains("--fullscreen")) args.add("--fullscreen");
        return args;
    }

    void resetPlayButton() {
        ui(() -> {
            playBtn.setEnabled(true);
            playBtn.setText("PLAY");
            playBtn.setBackground(Palette.GINGER);
        });
    }

    void monitorGame(Process process, String actualId, OutputStream logOut, Thread logPump) {
        try {
            int code = process.waitFor();
            if (logPump != null) {
                try { logPump.join(10000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            setStatus(code == 0 ? "Game closed" : "Game exited (code " + code + ") - see logs/catclient-latest.log");
        } catch (Exception e) {
            setStatus("Game monitor stopped");
        } finally {
            if (logPump != null && logPump.isAlive()) {
                try { logPump.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            try {
                if (logOut != null) {
                    logOut.flush();
                    logOut.close();
                }
            } catch (Exception ignored) {}
            logHandle = null;
            logPumpThread = null;
            gameProcess = null;
            resetPlayButton();
        }
    }

    void onPlay() {
        String user = username.getText().trim();
        if (user.length() < 3 || user.length() > 16) {
            JOptionPane.showMessageDialog(this, "Username must be 3-16 characters.", APP_NAME, JOptionPane.WARNING_MESSAGE);
            return;
        }
        for (char ch : user.toCharArray()) {
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                JOptionPane.showMessageDialog(this, "Use letters, numbers, and underscores only.", APP_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        Object ver = versions.getSelectedItem();
        if (ver == null) {
            JOptionPane.showMessageDialog(this, "Select a version first.", APP_NAME, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (gameProcess != null && gameProcess.isAlive()) {
            JOptionPane.showMessageDialog(this, "Minecraft is already running.", APP_NAME, JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final String version = ver.toString();
        final String capturedUser = user;
        final boolean needAssets = true;
        final int ramMb = ram.getValue() * 1024;
        final boolean fullscreenFlag = fullscreen.isSelected();

        playBtn.setEnabled(false);
        playBtn.setText("LAUNCHING...");
        progress.setValue(0);
        status.setText("Warming up the cat bed for " + version + "...");

        workers.execute(() -> {
            try {
                IntConsumer progressCb = this::setProgress;
                Consumer<String> statusCb = this::setStatus;

                String gameVersion = version.contains(" (") ? version.split(" \\(", 2)[0] : version;
                statusCb.accept("FPS pack - Minecraft " + gameVersion);
                Object[] dv = downloadVersion(gameVersion, needAssets, progressCb, statusCb);
                J.Obj versionInfo = (J.Obj) dv[0];
                String clientId = (String) dv[1];
                installFpsMods(gameVersion, statusCb, progressCb);
                Object[] fabric = installFabric(gameVersion, statusCb);
                versionInfo = (J.Obj) fabric[0];
                String launchId = (String) fabric[1];

                J.Obj resolved = Util.resolveVersionInfo(versionInfo);
                String baseId = versionInfo.has("inheritsFrom") && !versionInfo.get("inheritsFrom").isNull()
                        ? versionInfo.getStr("inheritsFrom", clientId) : clientId;
                Path jarPath = GAME_DIR.resolve("versions").resolve(baseId).resolve(baseId + ".jar");
                if (!Files.exists(jarPath)) throw new FileNotFoundException("Missing game jar: " + jarPath);
                Path nativesDir = GAME_DIR.resolve("versions").resolve(baseId).resolve("natives");

                String classpath = buildClasspath(resolved, baseId);
                List<String> args = buildLaunchArgs(resolved, launchId, capturedUser, ramMb, nativesDir, classpath, fullscreenFlag);

                setStatus("Launching " + launchId + "...");
                Path logDir = GAME_DIR.resolve("logs");
                Files.createDirectories(logDir);
                Path logPath = logDir.resolve("catclient-latest.log");
                OutputStream logOut = Files.newOutputStream(logPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                logHandle = logOut;

                long argsLen = 0;
                for (String a : args) argsLen += a.length() + 1;
                ProcessBuilder pb;
                if (Util.isWindows() || argsLen > 28000) {
                    List<String> rest = new ArrayList<>(args.subList(1, args.size()));
                    Path argFile = Util.writeArgFile(rest);
                    pb = new ProcessBuilder(javaBin, "@" + argFile.toAbsolutePath());
                } else {
                    pb = new ProcessBuilder(args);
                }
                pb.directory(GAME_DIR.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                gameProcess = process;

                Thread logPump = new Thread(() -> {
                    try (InputStream in = process.getInputStream()) {
                        in.transferTo(logOut);
                        logOut.flush();
                    } catch (IOException ignored) {}
                }, "game-log");
                logPumpThread = logPump;
                logPump.setDaemon(true);
                logPump.start();

                setStatus("Playing " + launchId);
                setProgress(100);
                Thread monitor = new Thread(() -> monitorGame(process, launchId, logOut, logPump), "game-monitor");
                monitor.setDaemon(true);
                monitor.start();
            } catch (Exception e) {
                e.printStackTrace();
                setStatus("Launch failed!");
                ui(() -> JOptionPane.showMessageDialog(this, "Error:\n" + e.getMessage(), APP_NAME, JOptionPane.ERROR_MESSAGE));
                try { if (logHandle != null) logHandle.close(); } catch (Exception ignored) {}
                logHandle = null;
                gameProcess = null;
                resetPlayButton();
            }
        });
    }

    // ---------- Hand-drawn icons (no third-party assets) ----------
    static final class Icons {
        static Image catWindowIcon() { return catMark(32); }

        static Image catMark(int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double s = size / 32.0;
            g.setColor(Palette.GINGER);
            g.fill(new Ellipse2D.Double(4 * s, 8 * s, 24 * s, 20 * s));
            Path2D earL = new Path2D.Double();
            earL.moveTo(6 * s, 12 * s);
            earL.lineTo(10 * s, 2 * s);
            earL.lineTo(14 * s, 10 * s);
            earL.closePath();
            Path2D earR = new Path2D.Double();
            earR.moveTo(18 * s, 10 * s);
            earR.lineTo(22 * s, 2 * s);
            earR.lineTo(26 * s, 12 * s);
            earR.closePath();
            g.fill(earL);
            g.fill(earR);
            g.setColor(new Color(0x3A2818));
            g.fill(new Ellipse2D.Double(11 * s, 15 * s, 3.5 * s, 4.5 * s));
            g.fill(new Ellipse2D.Double(18 * s, 15 * s, 3.5 * s, 4.5 * s));
            g.setColor(Palette.PAW);
            g.fill(new Ellipse2D.Double(14.5 * s, 20 * s, 3 * s, 2.2 * s));
            g.dispose();
            return img;
        }

        static Image catSilhouette(int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Palette.PANEL_LIFT);
            g.fillRoundRect(0, 0, size, size, 16, 16);
            int m = size / 8;
            g.setColor(Palette.GINGER);
            g.fillOval(m, m + 4, size - 2 * m, size - 2 * m - 2);
            int ear = size / 5;
            int[] x1 = {m + 2, m + ear, m + ear + 4};
            int[] y1 = {m + size / 3, m, m + size / 4};
            g.fillPolygon(x1, y1, 3);
            int[] x2 = {size - m - ear - 4, size - m - ear, size - m - 2};
            int[] y2 = {m + size / 4, m, m + size / 3};
            g.fillPolygon(x2, y2, 3);
            g.setColor(Palette.NIGHT);
            g.fillOval(size / 2 - size / 7, size / 2 - 2, size / 12, size / 9);
            g.fillOval(size / 2 + size / 14, size / 2 - 2, size / 12, size / 9);
            g.dispose();
            return img;
        }

        static Image pawPrint(int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Palette.PAW);
            g.fillOval(size / 3, size / 2, size / 3, size / 3);
            g.fillOval(2, size / 4, size / 4, size / 4);
            g.fillOval(size / 3 - 2, 2, size / 4, size / 4);
            g.fillOval(size / 2 + 2, 4, size / 4, size / 4);
            g.fillOval(size - size / 4 - 2, size / 4, size / 4, size / 4);
            g.dispose();
            return img;
        }

        static Image playPaw(int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Palette.NIGHT);
            Path2D tri = new Path2D.Double();
            tri.moveTo(size * 0.28, size * 0.18);
            tri.lineTo(size * 0.28, size * 0.82);
            tri.lineTo(size * 0.82, size * 0.5);
            tri.closePath();
            g.fill(tri);
            g.dispose();
            return img;
        }

        static Image refresh(int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Palette.MEOW);
            g.draw(new Arc2D.Double(2, 2, size - 6, size - 6, 40, 260, Arc2D.OPEN));
            Path2D tip = new Path2D.Double();
            tip.moveTo(size - 3, 4);
            tip.lineTo(size - 8, 9);
            tip.lineTo(size - 2, 11);
            tip.closePath();
            g.fill(tip);
            g.dispose();
            return img;
        }

        static Image navIcon(String name, int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Palette.GINGER);
            switch (name) {
                case "Play" -> {
                    Path2D t = new Path2D.Double();
                    t.moveTo(3, 2);
                    t.lineTo(3, size - 2);
                    t.lineTo(size - 2, size / 2.0);
                    t.closePath();
                    g.fill(t);
                }
                case "Skins" -> g.fillOval(2, 2, size - 4, size - 4);
                case "Settings" -> {
                    g.fillOval(size / 2 - 2, 2, 4, 4);
                    g.fillOval(size / 2 - 2, size / 2 - 2, 4, 4);
                    g.fillOval(size / 2 - 2, size - 6, 4, 4);
                }
                default -> {
                    g.setFont(new Font("Segoe UI", Font.BOLD, size - 4));
                    g.drawString("?", 4, size - 3);
                }
            }
            g.dispose();
            return img;
        }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.dpiaware", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {}
            UIManager.put("ComboBox.background", Palette.PANEL_LIFT);
            UIManager.put("ComboBox.foreground", Palette.CREAM);
            UIManager.put("ComboBox.selectionBackground", Palette.GINGER);
            UIManager.put("ComboBox.selectionForeground", Palette.NIGHT);
            new CatClient().setVisible(true);
        });
    }
}
