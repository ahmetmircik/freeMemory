import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION;
import static java.lang.String.format;
import static java.lang.System.out;
import static java.lang.management.MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED;
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
 * <p>
 * <p>
 * Problematic output :
 * <p>
 * runtime.maxMemory=32659M, runtime.totalMemory=32659M, runtime.freeMemory=663M, runtime.usedMemory=31996M, runTime.availableMemory=663M, freeHeapPercentage=2.03
 * runtime.maxMemory=32659M, runtime.totalMemory=32659M, runtime.freeMemory=663M, runtime.usedMemory=31996M, runTime.availableMemory=663M, freeHeapPercentage=2.03
 * runtime.maxMemory=32659M, runtime.totalMemory=32659M, runtime.freeMemory=663M, runtime.usedMemory=31996M, runTime.availableMemory=663M, freeHeapPercentage=2.03
 * <p>
 * End of run...now we expect to see actual used-memory value
 * runtime.maxMemory=32659M, runtime.totalMemory=32659M, runtime.freeMemory=30469M, runtime.usedMemory=2190M, runTime.availableMemory=30469M, freeHeapPercentage=93.29
 * [GC (CMS Initial Mark)  2248585K(33443712K), 0.0049429 secs]
 * runtime.maxMemory=32659M, runtime.totalMemory=32659M, runtime.freeMemory=30463M, runtime.usedMemory=2195M, runTime.availableMemory=30463M, freeHeapPercentage=93.28
 * [GC (CMS Final Remark)  2248585K(33443712K), 0.0106258 secs]
 * [GC (CMS Initial Mark)  2215981K(33443712K), 0.0048871 secs]
 * runtime.maxMemory=32659M, runtime.totalMemory=32659M, runtime.freeMemory=30495M, runtime.usedMemory=2164M, runTime.availableMemory=30495M, freeHeapPercentage=93.37
 */
public class FreeMemory {

    static final int MB = 1024 * 1024;
    static final String MESSAGE = "runtime.maxMemory=%d%s, runtime.totalMemory=%d%s, runtime.freeMemory=%d%s," +
            " runtime.usedMemory=%d%s, runTime.availableMemory=%d%s, used=%d%s, freeHeapPercentage=%.2f";
    private static AtomicBoolean start = new AtomicBoolean();

    public static void main(String[] args) throws InterruptedException {
        installGCMonitoring();

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
                        byte[] value = new byte[1024];
                        random.nextBytes(value);

                        map.put(random.nextInt(), value);

                        if (hasReachedMinFreeHeapPercentage(12)) {
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
            printCurrentMemoryInfo();
            parkNanos(SECONDS.toNanos(5));
        }
    }

    private static long getUsed() {
        long used = 0;

        List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean memoryPoolMXBean : memoryPoolMXBeans) {
            MemoryUsage memoryUsage = memoryPoolMXBean.getUsage();
            if (memoryUsage != null) {
                used += memoryUsage.getUsed();
            }
        }
        return used;
    }

    static void init() {
        MemoryPoolMXBean tenuredGenPool = null;
        for (MemoryPoolMXBean pool :
                ManagementFactory.getMemoryPoolMXBeans()) {
            // see http://www.javaspecialists.eu/archive/Issue092.html
            if (pool.getType() == MemoryType.HEAP && pool.isUsageThresholdSupported()) {
                tenuredGenPool = pool;
            }
        }

        // setting the threshold to 80% usage of the memory
        long max = tenuredGenPool.getUsage().getMax();
        System.out.println("max = " + max);
        tenuredGenPool.setCollectionUsageThreshold((int) Math.floor(max * 0.88));
        tenuredGenPool.setUsageThreshold((int) Math.floor(tenuredGenPool.getUsage().getMax() * 0.88));


        final MemoryPoolMXBean tenuredGenPoolParam = tenuredGenPool;
        MemoryMXBean mbean = ManagementFactory.getMemoryMXBean();
        NotificationEmitter emitter = (NotificationEmitter) mbean;
        emitter.addNotificationListener(new NotificationListener() {
            public void handleNotification(Notification n, Object hb) {
                if (MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(n.getType())) {
                    start.set(true);

                    long maxMemory = tenuredGenPoolParam.getUsage().getMax();
                    long usedMemory = tenuredGenPoolParam.getUsage().getUsed();

                    System.out.println("usedMemory/maxMemory * 100D = " + (usedMemory / maxMemory) * 100D);

                }
            }
        }, null, null);
    }

    static boolean hasReachedMinFreeHeapPercentage(int minFreeHeapPercentage) {
        if (!start.get()) {
            return false;
        }

        Runtime runtime = Runtime.getRuntime();

        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = freeMemory + (maxMemory - totalMemory);
        double freeHeapPercentage = 100D * availableMemory / maxMemory;

        if (freeHeapPercentage < minFreeHeapPercentage) {
            String unit = "M";
            out.println(format(MESSAGE, toMB(maxMemory), unit, toMB(totalMemory), unit, toMB(freeMemory), unit,
                    toMB(totalMemory - freeMemory), unit, toMB(availableMemory), unit, toMB(getUsed()), unit, freeHeapPercentage));
            return true;
        }

        return false;
    }

    static void printCurrentMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();

        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = freeMemory + (maxMemory - totalMemory);
        double freeHeapPercentage = 100D * availableMemory / maxMemory;

        String unit = "M";
        out.println(format(MESSAGE, toMB(maxMemory), unit, toMB(totalMemory), unit, toMB(freeMemory), unit,
                toMB(totalMemory - freeMemory), unit, toMB(availableMemory), unit, toMB(getUsed()), unit, freeHeapPercentage));
    }

    static int toMB(long bytes) {
        return (int) Math.rint(bytes / MB);
    }

    public static void installGCMonitoring() {
        //get all the GarbageCollectorMXBeans - there's one for each heap generation
        //so probably two - the old generation and young generation
        List<GarbageCollectorMXBean> gcbeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        //Install a notifcation handler for each bean
        for (GarbageCollectorMXBean gcbean : gcbeans) {
            NotificationEmitter emitter = (NotificationEmitter) gcbean;
            //use an anonymously generated listener for this example
            // - proper code should really use a named class
            NotificationListener listener = new NotificationListener() {

                //implement the notifier callback handler
                @Override
                public void handleNotification(Notification notification, Object handback) {
                    //we only handle GARBAGE_COLLECTION_NOTIFICATION notifications here
                    if (notification.getType().equals(GARBAGE_COLLECTION_NOTIFICATION)) {
                        //get the information associated with this notification
                        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());

                        //Get the information about each memory space, and pretty print it
                        Map<String, MemoryUsage> mem = info.getGcInfo().getMemoryUsageAfterGc();

                        long memUsed = 0;
                        for (MemoryUsage memoryUsage : mem.values()) {
                            memUsed += memoryUsage.getUsed();
                        }

                        double ratio = (memUsed / Runtime.getRuntime().maxMemory()) * 100D;
                        if (ratio > 80) {
                            start.set(true);
                            System.out.println("memUsed = " + memUsed);
                        }

                    }
                }
            };

            //Add the listener
            emitter.addNotificationListener(listener, null, null);
        }
    }
}
