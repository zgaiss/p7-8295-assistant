package cp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only loopback cache server. Protocol errors are JSON, never HTML. */
public final class Agent implements AutoCloseable {
    static final int MAX_CLIENTS = 8, MAX_LINE = 1024, MAX_HEADERS = 8192, MAX_HEADER_LINES = 32;
    static final int IDLE_MS = 5000, WRITE_MS = 3000, REQUEST_MS = 5000;
    private final Core core;
    private final ServerSocket listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Peer> peers = Collections.synchronizedSet(new HashSet<Peer>());
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService watchdog;
    private Thread acceptor;
    private Path pidPath;
    private byte[] pidBytes;
    private Object pidFileKey;
    private static ThreadFactory threads(final String prefix) {
        return new ThreadFactory() {
            private int next;
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + (++next)); t.setDaemon(true); return t;
            }
        };
    }
    public Agent(Core core, int port) throws IOException {
        this.core = core;
        listener = new ServerSocket();
        try {
            listener.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), MAX_CLIENTS);
        } catch (IOException e) { listener.close(); throw e; }
        workers = new ThreadPoolExecutor(MAX_CLIENTS, MAX_CLIENTS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1), threads("carprobe-client-"));
        watchdog = Executors.newSingleThreadScheduledExecutor(threads("carprobe-timeout-"));
        watchdog.scheduleAtFixedRate(new Runnable() { public void run() {
            long now = System.nanoTime();
            synchronized (peers) {
                for (Peer p : peers) {
                    if ((p.writeStarted != 0 && now - p.writeStarted > WRITE_MS * 1000000L)
                            || (p.requestStarted != 0 && now - p.requestStarted > REQUEST_MS * 1000000L))
                        p.close();
                }
            }
        } }, 100, 100, TimeUnit.MILLISECONDS);
    }
    public int port() { return listener.getLocalPort(); }
    public void start() {
        core.start();
        acceptor = new Thread(new Runnable() { public void run() {
            while (!closed.get()) {
                try {
                    final Socket socket = listener.accept();
                    try {
                        socket.setSoTimeout(IDLE_MS); socket.setTcpNoDelay(true); socket.setSendBufferSize(8192);
                    } catch (IOException e) { socket.close(); throw e; }
                    final Peer peer = new Peer(socket);
                    synchronized (peers) {
                        if (peers.size() >= MAX_CLIENTS) { socket.close(); continue; }
                        peers.add(peer);
                    }
                    try { workers.execute(new Runnable() { public void run() {
                        try { peer.serve(); } catch (IOException ignored) { /* peer closed/timeout */ }
                        finally { peer.close(); peers.remove(peer); }
                    } }); } catch (RejectedExecutionException e) { peers.remove(peer); peer.close(); }
                } catch (IOException e) {
                    if (!closed.get()) System.err.println("carprobe: terminal_accept_error");
                    break; // No busy-loop/log flood on EMFILE or persistent accept failure.
                }
            }
        } }, "carprobe-accept");
        acceptor.setDaemon(true); acceptor.start();
    }
    private final class Peer {
        final Socket socket;
        volatile long writeStarted, requestStarted;
        InputStream in; OutputStream out;
        Peer(Socket socket) { this.socket = socket; }
        void close() { try { socket.close(); } catch (IOException ignored) { } }
        void serve() throws IOException {
            in = socket.getInputStream(); out = socket.getOutputStream();
            while (!closed.get()) {
                requestStarted = System.nanoTime();
                String line;
                try { line = line(MAX_LINE); }
                catch (ProtocolException e) { sendLine(error(e.getMessage())); return; }
                if (line == null) return;
                if (line.startsWith("GET ") || line.indexOf(" HTTP/") >= 0) {
                    http(line); return;
                }
                requestStarted = 0;
                if ("stream".equals(line)) { stream(); return; }
                String response = command(line); sendLine(response);
                if ("quit".equals(line)) return;
            }
        }
        String line(int limit) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (true) {
                int c = in.read();
                if (c == -1) {
                    if (b.size() != 0) throw new ProtocolException("incomplete_line");
                    return null;
                }
                if (c == '\n') break;
                if (b.size() >= limit) throw new ProtocolException("line_too_long");
                if ((c < 32 && c != '\r') || c > 126) throw new ProtocolException("invalid_line");
                b.write(c);
            }
            byte[] bytes = b.toByteArray(); int n = bytes.length;
            if (n > 0 && bytes[n - 1] == '\r') n--;
            for (int i = 0; i < n; i++) if (bytes[i] == '\r') throw new ProtocolException("invalid_line");
            return new String(bytes, 0, n, StandardCharsets.US_ASCII);
        }
        void sendLine(String body) throws IOException { write((body + "\n").getBytes(StandardCharsets.UTF_8)); }
        void write(byte[] data) throws IOException {
            writeStarted = System.nanoTime();
            try { out.write(data); out.flush(); } finally { writeStarted = 0; }
        }
        void stream() throws IOException {
            long last = -1, lastWrite = 0;
            while (!closed.get() && !socket.isClosed()) {
                long seq = core.sequence(), now = System.nanoTime();
                // Cache-only heartbeat also refreshes ages and detects disconnected peers
                // when a vendor Binder call stalls the single sampler indefinitely.
                if (seq != last || now - lastWrite >= 1000000000L) {
                    sendLine(Json.encode(core.snapshot(null))); last = seq; lastWrite = System.nanoTime();
                }
                try { core.awaitAfter(last, 1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
        void http(String first) throws IOException {
            int status = 200; String body;
            try {
                String[] fields = first.split(" ", -1);
                if (fields.length != 3 || !"GET".equals(fields[0])
                        || !("HTTP/1.0".equals(fields[2]) || "HTTP/1.1".equals(fields[2])))
                    throw new ProtocolException("invalid_http_request");
                int size = 0, count = 0; Set<String> seen = new HashSet<String>();
                while (true) {
                    String h = line(MAX_LINE);
                    if (h == null) throw new ProtocolException("incomplete_headers");
                    size += h.length() + 2;
                    if (size > MAX_HEADERS || ++count > MAX_HEADER_LINES)
                        throw new ProtocolException("headers_too_large");
                    if (h.isEmpty()) break;
                    int colon = h.indexOf(':');
                    if (colon <= 0 || !h.substring(0, colon).matches("[A-Za-z0-9-]+"))
                        throw new ProtocolException("invalid_header");
                    String name = h.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                    if (!seen.add(name)) throw new ProtocolException("duplicate_header");
                    if ("transfer-encoding".equals(name)
                            || ("content-length".equals(name) && !"0".equals(h.substring(colon + 1).trim())))
                        throw new ProtocolException("request_body_not_supported");
                }
                requestStarted = 0;
                String path = fields[1];
                if ("/snapshot".equals(path)) body = command("snapshot");
                else if ("/speed".equals(path)) body = command("get speed");
                else if ("/ping".equals(path)) body = command("ping");
                else if ("/list".equals(path)) body = command("list");
                else if (path.equals("/raw") || path.startsWith("/raw?") || path.equals("/log")
                        || path.startsWith("/log?") || path.equals("/applog") || path.startsWith("/applog?")) {
                    status = 501; body = error("unsupported_in_poc");
                } else { status = 404; body = error("not_found"); }
            } catch (ProtocolException e) { status = 400; body = error(e.getMessage()); }
            requestStarted = 0;
            byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
            String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                    : status == 404 ? "Not Found" : "Not Implemented";
            byte[] head = ("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream response = new ByteArrayOutputStream(); response.write(head); response.write(bytes);
            write(response.toByteArray());
        }
    }
    private String command(String line) {
        if ("ping".equals(line)) return "{\"ok\":true,\"service\":\"carprobe\",\"protocol\":1}";
        if ("quit".equals(line)) return "{\"ok\":true,\"bye\":true}";
        if ("snapshot".equals(line)) return Json.encode(core.snapshot(null));
        if ("list".equals(line)) {
            Map<String, Object> result = Core.map(); result.put("props", Core.PROPERTIES);
            result.put("unsupported", new String[]{"raw", "log", "applog", "derived", "trip_a", "trip_b", "trip_ign", "trip_chrg"});
            return Json.encode(result);
        }
        if (line.startsWith("get ")) {
            String name = line.substring(4);
            return Core.PROPERTIES.containsKey(name) ? Json.encode(core.snapshot(name)) : error("unknown_property");
        }
        String name = line.split(" ", 2)[0];
        if ("raw".equals(name) || "log".equals(name) || "applog".equals(name)) return error("unsupported_in_poc");
        return error("unknown_command");
    }
    static String error(String message) { return "{\"ok\":false,\"error\":" + Json.encode(message) + "}"; }
    private static final class ProtocolException extends IOException {
        ProtocolException(String message) { super(message); }
    }
    /** CREATE_NEW prevents following an existing symlink or replacing someone else's PID file. */
    void writePid(Path path, String pid) throws IOException {
        byte[] bytes = (pid + "\n").getBytes(StandardCharsets.US_ASCII);
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        pidPath = path; pidBytes = bytes;
        pidFileKey = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { listener.close(); } catch (IOException ignored) { }
        synchronized (peers) { for (Peer p : peers) p.close(); }
        workers.shutdownNow(); watchdog.shutdownNow(); core.close();
        try {
            if (acceptor != null) acceptor.join(500);
            workers.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // Keep the file if its inode/content changed. Never follow a replacement symlink.
        if (pidPath != null) try {
            java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(pidPath,
                    java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (pidFileKey != null && pidFileKey.equals(a.fileKey()) && a.isRegularFile()
                    && a.size() == pidBytes.length && java.util.Arrays.equals(pidBytes, Files.readAllBytes(pidPath)))
                Files.delete(pidPath);
        } catch (IOException ignored) { }
    }
    public static void main(String[] args) {
        Agent agent = null;
        try {
            int port = 17666, period = 1000; Path pid = null;
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                if (!seen.add(option) || i + 1 == args.length) throw new IllegalArgumentException("invalid_options");
                String value = args[++i];
                if ("--port".equals(option)) port = Integer.parseInt(value);
                else if ("--period".equals(option)) period = Integer.parseInt(value);
                else if ("--pid".equals(option)) pid = Paths.get(value);
                else throw new IllegalArgumentException("unknown_option");
            }
            if (port < 1 || port > 65535 || period < 1000) throw new IllegalArgumentException("invalid_options");
            agent = new Agent(new Core(new AndroidSource(), AndroidSource.clock(), period), port);
            if (pid != null) agent.writePid(pid, processId());
            final Agent server = agent;
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() { public void run() { server.close(); } }, "carprobe-shutdown"));
            agent.start(); System.err.println("carprobe: started loopback port=" + port);
            agent.acceptor.join();
            agent.close();
        } catch (Exception e) {
            System.err.println("carprobe: startup_or_runtime_error " + e.getClass().getSimpleName());
            if (agent != null) agent.close(); System.exit(1);
        }
    }
    private static String processId() throws Exception {
        try { return String.valueOf(Class.forName("android.os.Process").getMethod("myPid").invoke(null)); }
        catch (ClassNotFoundException e) {
            // /proc is not portable; ManagementFactory would add unavailable Android classes.
            throw new IllegalStateException("pid_option_requires_android");
        }
    }
}
