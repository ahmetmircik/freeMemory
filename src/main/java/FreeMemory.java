import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;
import static java.lang.System.out;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;

/**
 * Example run:
 * <p>
 * CMS: (FAIL)
 * java -verbose:gc  -Xms32G -Xmx32G -XX:CMSInitiatingOccupancyFraction=1 -XX:+UseConcMarkSweepGC -XX:+UseParNewGC -cp target/free-memory-issue-1.0-SNAPSHOT.jar FreeMemory
 * <p>
 * G1: (PASS)
 * java -verbose:gc  -Xms32G -Xmx32G -XX:+UseG1GC -cp target/free-memory-issue-1.0-SNAPSHOT.jar FreeMemory
 */
public class FreeMemory {

    static final int MB = 1024 * 1024;
    static final String MESSAGE = "runtime.maxMemory=%d%s, runtime.totalMemory=%d%s, runtime.freeMemory=%d%s," +
            " runtime.usedMemory=%d%s, runTime.availableMemory=%d%s, freeHeapPercentage=%.2f";

    public static void main(String[] args) throws InterruptedException {
        final ConcurrentHashMap map = new ConcurrentHashMap();

        Runtime runtime = Runtime.getRuntime();
        int availableProcessors = runtime.availableProcessors();
        int threadCount = availableProcessors * 4;

        ArrayList<Thread> threads = new ArrayList<Thread>();

        for (int i = 0; i < threadCount; i++) {
            threads.add(new Thread(new Runnable() {

                @Override
                public void run() {
                    Random random = new Random();
                    while (true) {
                        final byte[] value = new byte[1024];
                        random.nextBytes(value);
                        map.put(random.nextInt(), value);

                        if (printMemoryAndBreakLoop(false)) {
                            break;
                        }
                    }
                }
            }));
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        out.print("\n\n\nEnd of run...now we expect to see actual used-memory value\n");
        while (true) {
            printMemoryAndBreakLoop(true);
            parkNanos(SECONDS.toNanos(5));
        }
    }

    protected static boolean printMemoryAndBreakLoop(boolean printAlways) {
        Runtime runtime = Runtime.getRuntime();

        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = freeMemory + (maxMemory - totalMemory);
        double freeHeapPercentage = 100D * availableMemory / maxMemory;

        if (printAlways || freeHeapPercentage < 12) {
            String unit = "M";
            out.println(format("[done]" + MESSAGE, toMB(maxMemory), unit, toMB(totalMemory), unit, toMB(freeMemory), unit,
                    toMB(totalMemory - freeMemory), unit, toMB(availableMemory), unit, freeHeapPercentage));
            return true;
        }

        return false;
    }

    private static int toMB(long bytes) {
        return (int) Math.rint(bytes / MB);
    }
}
