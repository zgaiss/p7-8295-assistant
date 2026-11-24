package cp;

/** Host-only injectable source; never included in production build. */
public final class HostServer {
    public static void main(String[] args) throws Exception {
        final boolean large = args.length == 1 && "large".equals(args[0]);
        final boolean hung = args.length == 1 && "hung".equals(args[0]);
        final char[] chars = new char[4096]; java.util.Arrays.fill(chars, 'x');
        final String largeValue = new String(chars);
        final class Source implements Core.Source {
            int reads, cycle;
            public void beginCycle() {
                cycle++;
                if (hung && cycle > 1) {
                    java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
                    while (true) try { never.await(); }
                    catch (InterruptedException ignored) { /* model an uninterruptible Binder call */ }
                }
            }
            public Core.Reading read(int id) {
                reads++;
                // Every speed read identifies a sampling cycle; get cannot change it.
                return new Core.Reading(large ? new String[]{largeValue, largeValue}
                        : id == Core.PROPERTIES.get("speed") ? cycle : new int[]{1, 2},
                        System.nanoTime() - 1000000L, 0);
            }
            public long connections() { return 1; }
            public void close() { }
        }
        final Agent server = new Agent(new Core(new Source(), AndroidSource.clock(), 1000), 0);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() { public void run() { server.close(); } }));
        server.start(); System.out.println(server.port()); System.out.flush();
        while (System.in.read() != -1) { /* owner closes stdin to stop */ }
        server.close();
    }
}
