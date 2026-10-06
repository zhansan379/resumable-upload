import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * JMX 内存采样器：连接远程 JVM，按固定间隔采样内存/线程/GC/CPU 并追加写入 CSV。
 * 用法: java scripts/MemorySampler.java <host> <port> <csvFile> [intervalMs]
 * 断线自动重连；每次连接成功/失败在 stderr 打一条日志。
 */
public class MemorySampler {
    private static final String HEADER = String.join(",",
            "ts", "heapUsedMB", "heapCommittedMB", "heapMaxMB", "nonHeapUsedMB",
            "oldUsedMB", "oldCommittedMB", "edenUsedMB", "survivorUsedMB",
            "metaUsedMB", "codeCacheUsedMB",
            "directUsedMB", "mappedUsedMB",
            "threadsLive", "threadsPeak",
            "gcYoungCount", "gcYoungTimeMs", "gcOldCount", "gcOldTimeMs",
            "procCpuLoad", "uptimeSec");

    public static void main(String[] args) throws Exception {
        String host = args[0], port = args[1], csv = args[2];
        long intervalMs = args.length > 3 ? Long.parseLong(args[3]) : 1000;
        JMXServiceURL url = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(csv, true))) {
            w.write(HEADER);
            w.newLine();
            w.flush();
            while (true) {
                try (JMXConnector cc = JMXConnectorFactory.connect(url, null)) {
                    MBeanServerConnection mbs = cc.getMBeanServerConnection();
                    System.err.println("[sampler] connected " + host + ":" + port);
                    sampleLoop(mbs, w, intervalMs);
                } catch (Exception e) {
                    System.err.println("[sampler] connection lost: " + e.getMessage());
                }
                System.err.println("[sampler] reconnect in 3s");
                Thread.sleep(3000);
            }
        }
    }

    private static void sampleLoop(MBeanServerConnection mbs, BufferedWriter w, long intervalMs)
            throws Exception {
        MemoryMXBean mem = ManagementFactory.newPlatformMXBeanProxy(
                mbs, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        ThreadMXBean th = ManagementFactory.newPlatformMXBeanProxy(
                mbs, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        java.lang.management.RuntimeMXBean rt = ManagementFactory.newPlatformMXBeanProxy(
                mbs, ManagementFactory.RUNTIME_MXBEAN_NAME, java.lang.management.RuntimeMXBean.class);
        com.sun.management.OperatingSystemMXBean os = ManagementFactory.newPlatformMXBeanProxy(
                mbs, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                com.sun.management.OperatingSystemMXBean.class);
        List<MemoryPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(mbs, MemoryPoolMXBean.class);
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getPlatformMXBeans(mbs, GarbageCollectorMXBean.class);
        List<BufferPoolMXBean> bufs = ManagementFactory.getPlatformMXBeans(mbs, BufferPoolMXBean.class);

        long oldGcC = 0, oldGcT = 0, yGcC = 0, yGcT = 0;
        // 首轮取当前累计值，之后用差值；直接记录累计值即可，分析端做差
        for (GarbageCollectorMXBean gc : gcs) {
            String n = gc.getName();
            if (n.matches(".*(Old|MarkSweep|Major|Tenured).*")) { oldGcC += gc.getCollectionCount(); oldGcT += gc.getCollectionTime(); }
            else if (n.matches(".*(Young|Scavenge|Minor|Copy|ParNew).*")) { yGcC += gc.getCollectionCount(); yGcT += gc.getCollectionTime(); }
        }

        while (true) {
            MemoryUsage h = mem.getHeapMemoryUsage(), nh = mem.getNonHeapMemoryUsage();
            long oldU = -1, oldC = -1, eden = -1, surv = -1, meta = -1, code = -1;
            for (MemoryPoolMXBean p : pools) {
                String n = p.getName();
                MemoryUsage u = p.getUsage();
                if (u == null) continue;
                if (n.contains("Old") || n.contains("Tenured")) { oldU = u.getUsed(); oldC = u.getCommitted(); }
                else if (n.contains("Eden")) eden = u.getUsed();
                else if (n.contains("Survivor")) surv = u.getUsed();
                else if (n.contains("Metaspace")) meta = u.getUsed();
                else if (n.contains("Code")) code = u.getUsed();
            }
            long direct = 0, mapped = 0;
            for (BufferPoolMXBean b : bufs) {
                if (b.getName().equals("direct")) direct = b.getMemoryUsed();
                if (b.getName().equals("mapped")) mapped = b.getMemoryUsed();
            }
            long osGcC = 0, osGcT = 0, nY = 0, nT = 0;
            for (GarbageCollectorMXBean gc : gcs) {
                String n = gc.getName();
                if (n.matches(".*(Old|MarkSweep|Major|Tenured).*")) { osGcC += gc.getCollectionCount(); osGcT += gc.getCollectionTime(); }
                else if (n.matches(".*(Young|Scavenge|Minor|Copy|ParNew).*")) { nY += gc.getCollectionCount(); nT += gc.getCollectionTime(); }
            }
            if (osGcC < oldGcC) { osGcC = oldGcC; osGcT = oldGcT; } // GC bean 顺序变化或重连时的保护
            if (nY < yGcC) { nY = yGcC; nT = yGcT; }
            oldGcC = osGcC; oldGcT = osGcT; yGcC = nY; yGcT = nT;

            w.write(String.join(",",
                    String.valueOf(System.currentTimeMillis()),
                    mb(h.getUsed()), mb(h.getCommitted()), mb(h.getMax()), mb(nh.getUsed()),
                    mb(oldU), mb(oldC), mb(eden), mb(surv), mb(meta), mb(code),
                    mb(direct), mb(mapped),
                    String.valueOf(th.getThreadCount()), String.valueOf(th.getPeakThreadCount()),
                    String.valueOf(osGcC), String.valueOf(osGcT), String.valueOf(nY), String.valueOf(nT),
                    String.format("%.3f", os.getProcessCpuLoad()),
                    String.valueOf(rt.getUptime() / 1000)));
            w.newLine();
            w.flush();
            Thread.sleep(intervalMs);
        }
    }

    private static String mb(long bytes) {
        return bytes < 0 ? "-1" : String.format("%.2f", bytes / 1048576.0);
    }
}
