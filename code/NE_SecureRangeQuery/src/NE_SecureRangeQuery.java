import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 基于排序映射的保序加密（每维独立）
 */
class OPE {
    private final TreeMap<Double, Long> encMap = new TreeMap<>();
    private final TreeMap<Long, Double> decMap = new TreeMap<>();
    private final long base;
    private final long step;
    private final byte[] key;

    public OPE(List<Double> values, byte[] key, long base, long step) {
        this.key = key.clone();
        this.base = base;
        this.step = step;
        buildMap(values);
    }

    private void buildMap(List<Double> values) {
        TreeSet<Double> uniqueSet = new TreeSet<>(values);
        List<Double> sorted = new ArrayList<>(uniqueSet);
        Collections.sort(sorted);

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));

            for (int i = 0; i < sorted.size(); i++) {
                double v = sorted.get(i);
                mac.update(String.valueOf(i).getBytes(StandardCharsets.UTF_8));
                byte[] digest = mac.doFinal();
                long offset = ((long) (digest[0] & 0xFF) << 24) |
                        ((long) (digest[1] & 0xFF) << 16) |
                        ((long) (digest[2] & 0xFF) << 8)  |
                        ((long) (digest[3] & 0xFF));
                offset = offset % (step / 2);
                long encVal = base + i * step + offset;
                encMap.put(v, encVal);
                decMap.put(encVal, v);
            }
        } catch (Exception e) {
            throw new RuntimeException("OPE 初始化失败", e);
        }
    }

    public long encrypt(double val) {
        Map.Entry<Double, Long> entry = encMap.floorEntry(val);
        if (entry == null) {
            return encMap.firstEntry().getValue();
        }
        return entry.getValue();
    }

    public Double decrypt(long encVal) {
        return decMap.get(encVal);
    }
}

/**
 * KD 树节点
 */
class KDNode {
    final long x, y;
    KDNode left, right;
    final int axis;

    KDNode(long x, long y, int axis) {
        this.x = x;
        this.y = y;
        this.axis = axis;
    }
}

/**
 * KD 树工具类
 */
class KDTree {
    public static KDNode build(List<long[]> points, int depth) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        int axis = depth % 2;

        int finalAxis = axis;
        points.sort(Comparator.comparingLong(p -> p[finalAxis]));

        int median = points.size() / 2;
        long[] medianPoint = points.get(median);

        KDNode node = new KDNode(medianPoint[0], medianPoint[1], axis);
        node.left = build(points.subList(0, median), depth + 1);
        node.right = build(points.subList(median + 1, points.size()), depth + 1);
        return node;
    }

    public static void rangeQuery(KDNode node, long xMin, long xMax, long yMin, long yMax,
                                  List<long[]> result) {
        if (node == null) return;

        long x = node.x;
        long y = node.y;

        if (x >= xMin && x <= xMax && y >= yMin && y <= yMax) {
            result.add(new long[]{x, y});
        }

        int axis = node.axis;
        if (axis == 0) {
            if (xMin <= x) {
                rangeQuery(node.left, xMin, xMax, yMin, yMax, result);
            }
            if (x <= xMax) {
                rangeQuery(node.right, xMin, xMax, yMin, yMax, result);
            }
        } else {
            if (yMin <= y) {
                rangeQuery(node.left, xMin, xMax, yMin, yMax, result);
            }
            if (y <= yMax) {
                rangeQuery(node.right, xMin, xMax, yMin, yMax, result);
            }
        }
    }
}

/**
 * 主程序 - 交互式查询
 */
public class NE_SecureRangeQuery {
    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        try {
            // 1. 固定文件名读取数据
            String fileName = "src/NE.txt";
            List<Double> xList = new ArrayList<>();
            List<Double> yList = new ArrayList<>();
            List<double[]> rawPoints = new ArrayList<>();

            readDataFile(fileName, xList, yList, rawPoints);
            System.out.println("成功读取数据点数: " + rawPoints.size());
            if (rawPoints.isEmpty()) {
                System.out.println("数据为空，程序退出。");
                return;
            }

            // 2. 构建 OPE
            byte[] key = "this-is-a-secret-key-for-ope-32b".getBytes(StandardCharsets.UTF_8);
            OPE opeX = new OPE(xList, Arrays.copyOf(key, key.length), 0L, 10000L);
            OPE opeY = new OPE(yList, Arrays.copyOf(key, key.length), 0L, 10000L);

            // 3. 加密所有点
            List<long[]> encPoints = new ArrayList<>();
            for (double[] p : rawPoints) {
                long encX = opeX.encrypt(p[0]);
                long encY = opeY.encrypt(p[1]);
                encPoints.add(new long[]{encX, encY});
            }

            // 4. 构建 KD 树
            System.out.print("正在构建 KD 树... ");
            long start = System.currentTimeMillis();
            KDNode root = KDTree.build(encPoints, 0);
            long end = System.currentTimeMillis();
            System.out.println("完成，耗时: " + (end - start) + " ms");

            // 5. 交互式查询循环
            System.out.println("\n========== 交互式范围查询 ==========");
            System.out.println("输入格式: xMin xMax yMin yMax (用空格分隔)");
            System.out.println("输入 'q' 退出, 'help' 显示帮助");

            while (true) {
                System.out.print("\n请输入查询范围: ");
                String input = scanner.nextLine().trim();

                if (input.equalsIgnoreCase("q") || input.equalsIgnoreCase("quit")) {
                    System.out.println("程序退出。");
                    break;
                }
                if (input.equalsIgnoreCase("help")) {
                    showHelp();
                    continue;
                }
                if (input.isEmpty()) {
                    continue;
                }

                String[] parts = input.split("\\s+");
                if (parts.length != 4) {
                    System.out.println("错误: 需要4个数值 (xMin xMax yMin yMax)");
                    continue;
                }

                try {
                    double qxMin = Double.parseDouble(parts[0]);
                    double qxMax = Double.parseDouble(parts[1]);
                    double qyMin = Double.parseDouble(parts[2]);
                    double qyMax = Double.parseDouble(parts[3]);

                    // 自动纠正大小关系
                    if (qxMin > qxMax) {
                        double tmp = qxMin;
                        qxMin = qxMax;
                        qxMax = tmp;
                    }
                    if (qyMin > qyMax) {
                        double tmp = qyMin;
                        qyMin = qyMax;
                        qyMax = tmp;
                    }

                    performQuery(root, opeX, opeY, qxMin, qxMax, qyMin, qyMax);

                } catch (NumberFormatException e) {
                    System.out.println("错误: 请输入有效的数字");
                }
            }

        } catch (Exception e) {
            System.err.println("程序运行出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }

    /**
     * 读取数据文件
     */
    private static void readDataFile(String fileName, List<Double> xList,
                                     List<Double> yList, List<double[]> rawPoints) throws IOException {
        File file = new File(fileName);
        if (!file.exists()) {
            throw new FileNotFoundException("文件不存在: " + fileName);
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNum = 0;
            while ((line = br.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    System.out.println("警告: 第 " + lineNum + " 行格式错误，已跳过");
                    continue;
                }
                try {
                    double x = Double.parseDouble(parts[0]);
                    double y = Double.parseDouble(parts[1]);
                    xList.add(x);
                    yList.add(y);
                    rawPoints.add(new double[]{x, y});
                } catch (NumberFormatException e) {
                    System.out.println("警告: 第 " + lineNum + " 行数字解析失败，已跳过");
                }
            }
        }
    }

    /**
     * 执行一次范围查询，显示密文返回量、有效点数和假阳性统计
     */
    private static void performQuery(KDNode root, OPE opeX, OPE opeY,
                                     double qxMin, double qxMax, double qyMin, double qyMax) {
        // 加密查询边界
        long encXMin = opeX.encrypt(qxMin);
        long encXMax = opeX.encrypt(qxMax);
        long encYMin = opeY.encrypt(qyMin);
        long encYMax = opeY.encrypt(qyMax);

        System.out.println("\n========== 查询结果 ==========");
        System.out.printf("查询矩形明文: x∈[%.6f, %.6f], y∈[%.6f, %.6f]%n",
                qxMin, qxMax, qyMin, qyMax);
        System.out.printf("查询密文边界: x∈[%d, %d], y∈[%d, %d]%n",
                encXMin, encXMax, encYMin, encYMax);

        // KD 树检索
        List<long[]> encResults = new ArrayList<>();
        long start = System.nanoTime();
        KDTree.rangeQuery(root, encXMin, encXMax, encYMin, encYMax, encResults);
        long end = System.nanoTime();
        double queryTimeMs = (end - start) / 1e6;

        System.out.println("KD树检索耗时: " + queryTimeMs + " ms");
        System.out.println("---------------------------------");
        System.out.println("密文返回点数量: " + encResults.size() + "  (包含假阳性)");

        if (encResults.isEmpty()) {
            System.out.println("未找到任何密文点。");
            return;
        }

        // 解密并统计有效点与假阳性
        List<double[]> validResults = new ArrayList<>();
        int falsePositiveCount = 0;

        for (long[] ep : encResults) {
            Double x = opeX.decrypt(ep[0]);
            Double y = opeY.decrypt(ep[1]);
            if (x != null && y != null) {
                if (x >= qxMin && x <= qxMax && y >= qyMin && y <= qyMax) {
                    validResults.add(new double[]{x, y});
                } else {
                    falsePositiveCount++;
                }
            }
        }

        System.out.println("后过滤有效点数量: " + validResults.size());
        System.out.println("假阳性点数量: " + falsePositiveCount);
        if (encResults.size() > 0) {
            System.out.printf("假阳性率: %.2f%%%n", 100.0 * falsePositiveCount / encResults.size());
        }
        System.out.println("---------------------------------");

        // 询问是否显示解密结果
        System.out.print("是否显示有效点的明文坐标? (y/n, 默认n): ");
        String show = scanner.nextLine().trim();
        if (show.equalsIgnoreCase("y") || show.equalsIgnoreCase("yes")) {
            int displayCount = Math.min(10, validResults.size());
            System.out.println("有效点示例 (前 " + displayCount + " 个):");
            for (int i = 0; i < displayCount; i++) {
                double[] p = validResults.get(i);
                System.out.printf("  (%.6f, %.6f)%n", p[0], p[1]);
            }
            if (validResults.size() > 10) {
                System.out.println("  ... 共 " + validResults.size() + " 个有效点");
            }


            boolean allValid = validResults.stream().allMatch(p ->
                    p[0] >= qxMin && p[0] <= qxMax && p[1] >= qyMin && p[1] <= qyMax);
            System.out.println("验证有效点均在查询范围内: " + allValid);
        }
    }

    private static void showHelp() {
        System.out.println("========== 菜单 ==========");
        System.out.println("1. 输入四个数值查询: xMin xMax yMin yMax");
        System.out.println("2. 输入 'q' 或 'quit' 退出程序");
        System.out.println("===============================");
    }
}