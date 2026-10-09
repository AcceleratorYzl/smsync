import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 短信同步服务器（纯 JDK 实现，零依赖）
 *  - POST /upload       接收 App 上传的短信 JSON，按 id 去重合并进 data/sms_store.json（需 X-Sms-Key 头）
 *  - GET  /             托管网页 web/index.html（网页内置访问口令，口令校验在前端）
 *  - GET  /data         返回已存储的短信 JSON 供网页前端渲染（需 ?token= 访问口令，前端携带）
 *  - GET  /data?keyword=xxx   按关键字过滤
 *  - POST /command      网页点「立即上传」时写入一条待执行指令（需 token）
 *  - GET  /command      App 轮询：取出并消费待执行指令（需 X-Sms-Key）
 *
 * 安全：
 *  - 共享密钥 X-Sms-Key（默认 SmsSync#2026#）保护 /upload 与 /command(GET)
 *  - 网页访问口令 SMSYNC_VIEW（默认 smsync2026）保护 /data
 */
public class SmsServer {

    private static final int PORT = 8080;
    private static final Path DATA_DIR = Paths.get("data");
    private static final Path DATA_FILE = DATA_DIR.resolve("sms_store.json");
    private static final Path WEB_DIR = Paths.get("web");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 共享密钥：App 上传 / 轮询指令时携带在 X-Sms-Key 头 */
    private static final String SHARE_KEY = "SmsSync#2026#";
    /** 网页访问口令：前端调 /data、/command(POST) 时通过查询参数 token 携带 */
    private static final String VIEW_TOKEN = "smsync2026";

    /** 待执行指令队列（网页 POST /command 写入，App GET /command 取出） */
    private static final Object CMD_LOCK = new Object();
    private static final List<String> CMD_QUEUE = new ArrayList<>();
    private static final AtomicLong CMD_SEQ = new AtomicLong(0);

    public static void main(String[] args) {
        try {
            Files.createDirectories(DATA_DIR);
            Files.createDirectories(WEB_DIR);
        } catch (IOException e) {
            System.err.println("创建目录失败: " + e.getMessage());
            return;
        }
        if (!Files.exists(DATA_FILE)) {
            try { Files.write(DATA_FILE, "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8)); }
            catch (IOException ignored) {}
        }

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        } catch (IOException e) {
            System.err.println("监听端口 " + PORT + " 失败: " + e.getMessage());
            return;
        }
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/upload", new UploadHandler());
        server.createContext("/data", new DataHandler());
        server.createContext("/command", new CommandHandler());
        server.createContext("/", new WebHandler());
        server.start();

        String lanIp = getLanIp();
        System.out.println("===================================================");
        System.out.println(" 短信同步服务器已启动");
        System.out.println(" 共享密钥 X-Sms-Key : " + SHARE_KEY);
        System.out.println(" 网页访问口令 token   : " + VIEW_TOKEN);
        System.out.println(" 本机访问:   http://localhost:" + PORT + "/  (token=" + VIEW_TOKEN + ")");
        System.out.println(" 局域网访问: http://" + lanIp + ":" + PORT + "/");
        System.out.println(" App 上传到: http://" + lanIp + ":" + PORT + "/upload");
        System.out.println(" 数据文件:   " + DATA_FILE.toAbsolutePath());
        System.out.println(" 按 Ctrl+C 停止");
        System.out.println("===================================================");
    }

    // ---------- 权限校验 ----------
    private static boolean keyOk(HttpExchange ex) {
        String k = ex.getRequestHeaders().getFirst("X-Sms-Key");
        return SHARE_KEY.equals(k);
    }
    private static boolean tokenOk(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        if (q != null && q.startsWith("token=")) {
            int end = q.indexOf('&', 6);
            String tok = end < 0 ? q.substring(6) : q.substring(6, end);
            return VIEW_TOKEN.equals(tok);
        }
        // 也允许通过 X-View-Token 头携带
        String h = ex.getRequestHeaders().getFirst("X-View-Token");
        return VIEW_TOKEN.equals(h);
    }

    // ---------- POST /upload ----------
    static class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); ex.close(); return;
            }
            if (!keyOk(ex)) { sendJson(ex, 401, "{\"error\":\"bad key\"}"); ex.close(); return; }
            String body = readBody(ex);
            if (body == null || body.trim().isEmpty()) {
                sendJson(ex, 400, "{\"error\":\"empty body\"}"); ex.close(); return;
            }
            synchronized (SmsServer.class) {
                mergeIntoStore(body.trim());
            }
            sendJson(ex, 200, "{\"ok\":true,\"note\":\"saved\"}");
            ex.close();
            System.out.println("[" + FMT.format(LocalDateTime.now()) + "] 收到上传: " + countMessages(body) + " 条短信");
        }

        /** 把上传的 {messages:[...]} 按 id 去重合并进本地存储 */
        private void mergeIntoStore(String uploadedJson) throws IOException {
            List<Object> current = parseMessages(readStore());
            List<Object> incoming = parseMessages(uploadedJson);
            List<Object> merged = new ArrayList<>(current);
            for (Object m : incoming) {
                String idStr = extractId(m);
                boolean exists = false;
                for (Object x : merged) { if (idStr.equals(extractId(x))) { exists = true; break; } }
                if (!exists) merged.add(m);
            }
            StringBuilder sb = new StringBuilder("{\"messages\":[");
            for (int i = 0; i < merged.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(merged.get(i));
            }
            sb.append("]}");
            Files.write(DATA_FILE, sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        private List<Object> parseMessages(String json) {
            List<Object> list = new ArrayList<>();
            int start = json.indexOf("[");
            int end = json.lastIndexOf("]");
            if (start < 0 || end <= start) return list;
            String arr = json.substring(start + 1, end);
            int i = 0, depth = 0, objStart = -1;
            boolean inStr = false; char esc = 0;
            for (; i < arr.length(); i++) {
                char ch = arr.charAt(i);
                if (inStr) { if (ch == esc) inStr = false; }
                else {
                    if (ch == '"') { inStr = true; esc = ch; }
                    else if (ch == '{') { if (depth == 0) objStart = i; depth++; }
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0 && objStart >= 0) {
                            list.add(arr.substring(objStart, i + 1));
                            objStart = -1;
                        }
                    }
                }
            }
            return list;
        }

        private String extractId(Object m) {
            String s = m.toString();
            int p = s.indexOf("\"id\":");
            if (p < 0) return s;
            p += 5;
            int e = p;
            while (e < s.length() && Character.isDigit(s.charAt(e))) e++;
            return s.substring(p, e);
        }

        private int countMessages(String json) { return parseMessages(json).size(); }
    }

    // ---------- GET /data ----------
    static class DataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!tokenOk(ex)) { sendJson(ex, 401, "{\"error\":\"bad token\"}"); ex.close(); return; }
            String store = readStore();
            String q = ex.getRequestURI().getQuery();
            String out = store;
            if (q != null && q.startsWith("keyword=")) {
                out = filter(store, q.substring("keyword=".length()));
            }
            sendJson(ex, 200, out);
            ex.close();
        }

        private String filter(String json, String kw) {
            if (kw.isEmpty()) return json;
            String lower = kw.toLowerCase();
            List<Object> msgs = new ArrayList<>();
            int start = json.indexOf("[");
            int end = json.lastIndexOf("]");
            if (start < 0 || end <= start) return json;
            String arr = json.substring(start + 1, end);
            int i = 0, depth = 0, objStart = -1;
            boolean inStr = false; char esc = 0;
            for (; i < arr.length(); i++) {
                char ch = arr.charAt(i);
                if (inStr) { if (ch == esc) inStr = false; }
                else {
                    if (ch == '"') { inStr = true; esc = ch; }
                    else if (ch == '{') { if (depth == 0) objStart = i; depth++; }
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0 && objStart >= 0) {
                            String obj = arr.substring(objStart, i + 1);
                            if (obj.toLowerCase().contains(lower)) msgs.add(obj);
                            objStart = -1;
                        }
                    }
                }
            }
            StringBuilder sb = new StringBuilder("{\"messages\":[");
            for (int k = 0; k < msgs.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append(msgs.get(k));
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    // ---------- /command ----------
    /** POST /command：网页「立即上传」写入待执行指令；GET /command：App 轮询取出指令 */
    static class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod().toUpperCase();
            if ("POST".equalsIgnoreCase(method)) {
                if (!tokenOk(ex)) { sendJson(ex, 401, "{\"error\":\"bad token\"}"); ex.close(); return; }
                String body = readBody(ex);
                String action = "upload";
                if (body != null && body.contains("\"action\"")) {
                    int p = body.indexOf("\"action\"");
                    int a = body.indexOf('"', p + 8);
                    int b = body.indexOf('"', a + 1);
                    if (a > 0 && b > a) action = body.substring(a + 1, b);
                }
                long seq;
                synchronized (CMD_LOCK) {
                    seq = CMD_SEQ.incrementAndGet();
                    CMD_QUEUE.add("{\"seq\":" + seq + ",\"action\":\"" + action + "\",\"ts\":" + System.currentTimeMillis() + "}");
                    System.out.println("[" + FMT.format(LocalDateTime.now()) + "] 网页下发指令: " + action);
                }
                sendJson(ex, 200, "{\"ok\":true,\"queued\":" + seq + "}");
                ex.close();
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                if (!keyOk(ex)) { sendJson(ex, 401, "{\"error\":\"bad key\"}"); ex.close(); return; }
                String out;
                synchronized (CMD_LOCK) {
                    out = CMD_QUEUE.isEmpty() ? "{\"has\":false}" :
                          "{\"has\":true,\"cmd\":" + CMD_QUEUE.remove(0) + "}";
                }
                sendJson(ex, 200, out);
                ex.close();
                return;
            }
            sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
            ex.close();
        }
    }

    // ---------- GET / ----------
    static class WebHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) path = "/index.html";
            Path file = WEB_DIR.resolve(path.startsWith("/") ? path.substring(1) : path);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                sendText(ex, 404, "404 Not Found", "text/plain; charset=utf-8");
                ex.close(); return;
            }
            byte[] bytes = Files.readAllBytes(file);
            String type = "text/html";
            if (file.toString().endsWith(".css")) type = "text/css";
            else if (file.toString().endsWith(".js")) type = "text/javascript";
            ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            ex.close();
        }
    }

    // ---------- 工具 ----------
    private static String readStore() {
        try { return new String(Files.readAllBytes(DATA_FILE), StandardCharsets.UTF_8); }
        catch (IOException e) { return "{\"messages\":[]}"; }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendText(HttpExchange ex, int code, String text, String type) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String getLanIp() {
        try { return InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { return "127.0.0.1"; }
    }
}
