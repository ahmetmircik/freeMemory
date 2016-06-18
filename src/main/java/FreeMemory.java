import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;
import static java.lang.System.out;

/**
 * Example run:
 * <p>
 * G1:
 * java -verbose:gc  -Xms2G -Xmx2G --XX:+UseG1GC -cp hazelcast/target/hazelcast-3.5.5.3.jar com.hazelcast.map.impl.eviction.FreeMemory > log_G1.txt
 * <p>
 * CMS:
 * java -verbose:gc  -Xms2G -Xmx2G -XX:CMSInitiatingOccupancyFraction=10 -XX:+UseConcMarkSweepGC -XX:+UseParNewGC -cp hazelcast/target/hazelcast-3.5.5.3.jar com.hazelcast.map.impl.eviction.FreeMemory > log_CMS.txt
 */
public class FreeMemory {

    static final int MB = 1024 * 1024;
    static final String MESSAGE = "runtime.maxMemory=%d%s, runtime.totalMemory=%d%s, runtime.freeMemory=%d%s," +
            " runtime.usedMemory=%d%s, runTime.availableMemory=%d%s, memoryMXBean.used=%d%s, freeHeapPercentage=%.2f";

    public static void main(String[] args) {
        Runtime runtime = Runtime.getRuntime();
        int availableProcessors = runtime.availableProcessors();

        for (int i = 0; i < availableProcessors * 4; i++) {
            new Thread(new Runnable() {

                ConcurrentHashMap map = new ConcurrentHashMap();

                @Override
                public void run() {
                    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
                    Runtime runtime = Runtime.getRuntime();
                    Random random = new Random();
                    while (true) {
                        final byte[] value = new byte[1024];
                        random.nextBytes(value);
                        map.put(random.nextInt(), value);
                        printMemory(runtime, memoryMXBean);
                    }
                }
            }).start();

        }
    }

    protected static void printMemory(Runtime runtime, MemoryMXBean memoryMXBean) {
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = freeMemory + (maxMemory - totalMemory);
        double freeHeapPercentage = 100D * availableMemory / maxMemory;
        long used = memoryMXBean.getHeapMemoryUsage().getUsed();

        if (freeHeapPercentage < 12) {
            String unit = "M";
            out.println(format("[done]" + MESSAGE, toMB(maxMemory), unit, toMB(totalMemory), unit, toMB(freeMemory), unit,
                    toMB(totalMemory - freeMemory), unit, toMB(availableMemory), unit, toMB(used), unit, freeHeapPercentage));
        }
    }

    private static int toMB(long bytes) {
        return (int) Math.rint(bytes / MB);
    }
}
