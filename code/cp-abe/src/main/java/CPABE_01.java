import it.unisa.dia.gas.jpbc.Element;
import it.unisa.dia.gas.jpbc.Pairing;
import it.unisa.dia.gas.plaf.jpbc.pairing.PairingFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CP-ABE 属性基加密系统
 * 访问策略：AND 门限
 */
public class CPABE_01 {
    private static Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("=====================================");
        System.out.println("   CP-ABE 属性基加密系统");
        System.out.println("=====================================");

        CPABEAlgorithm cpabe = new CPABEAlgorithm();

        while (true) {
            showMenu();
            int choice = getIntInput();

            switch (choice) {
                case 1: cpabe.setup(); break;
                case 2: cpabe.keyGen(scanner); break;
                case 3: cpabe.encrypt(scanner); break;
                case 4: cpabe.decrypt(scanner); break;
                case 5: cpabe.testIllegalKey(); break;
                case 6: cpabe.testCollusion(); break;
                case 0: System.out.println("\n程序退出"); return;
                default: System.out.println("无效输入，请重新选择！");
            }
        }
    }


    private static void showMenu() {
        System.out.println("\n========== 功能菜单 ==========");
        System.out.println("1. 系统初始化 (Setup)");
        System.out.println("2. 用户私钥生成 (KeyGen)");
        System.out.println("3. 明文加密 (Encrypt)");
        System.out.println("4. 密文解密 (Decrypt)");
        System.out.println("5. 验证非法用户无法解密");
        System.out.println("6. 验证抗私钥共谋攻击");
        System.out.println("0. 退出系统");
        System.out.print("请选择操作 [0-6]: ");
    }

    private static int getIntInput() {
        try {
            return scanner.nextInt();
        } catch (Exception e) {
            scanner.nextLine();
            return -1;
        } finally {
            scanner.nextLine();
        }
    }
}

/**
 * CP-ABE 核心算法
 */
class CPABEAlgorithm {
    private Pairing pairing;
    private Element g, h, e_gg_alpha;
    private Element alpha, beta;
    private final Map<String, UserKey> userKeyMap = new HashMap<>();
    private CipherText currentCipherText;

    /**
     * 用户私钥结构
     */
    static class UserKey {
        String username;
        List<String> attributes;
        Element D;
        Map<String, Element> D_i;
        Map<String, Element> D_i_prime;
        // 用户私钥 D_i = D * D_i_prime
        UserKey(String username, List<String> attributes, Element D,
                Map<String, Element> D_i, Map<String, Element> D_i_prime) {
            this.username = username;
            this.attributes = attributes;
            this.D = D;
            this.D_i = D_i;
            this.D_i_prime = D_i_prime;
        }
    }

    /**
     * 密文结构
     */
    static class CipherText {
        String policy;
        List<String> policyAttrs;
        Element C, C_prime, C_0;
        Map<String, Element> C_i;
        String originalMsg;

        CipherText(String policy, List<String> policyAttrs, Element C, Element C_prime,
                   Element C_0, Map<String, Element> C_i, String originalMsg) {
            this.policy = policy;
            this.policyAttrs = policyAttrs;
            this.C = C;
            this.C_prime = C_prime;
            this.C_0 = C_0;
            this.C_i = C_i;
            this.originalMsg = originalMsg;
        }
    }

    /**
     * 系统初始化：生成公钥和主密钥
     */
    public void setup() {
        try {
            System.out.println("\n[系统初始化]");
            pairing = PairingFactory.getPairing("a.properties");

            g = pairing.getG1().newRandomElement().getImmutable();
            alpha = pairing.getZr().newRandomElement().getImmutable();
            beta = pairing.getZr().newRandomElement().getImmutable();
            

            h = g.powZn(beta).getImmutable();
            e_gg_alpha = pairing.pairing(g, g).powZn(alpha).getImmutable();
            
            System.out.println(" 系统初始化成功");
            System.out.println("  公钥 PK = {g, h, e(g,g)^α}");
            System.out.println("  主密钥 MK = {α, β}");
        } catch (Exception e) {
            System.out.println("初始化失败：" + e.getMessage());
        }
    }

    /**
     * 哈希函数：将属性字符串映射到 G1 群
     */
    private Element hash(String attr) {
        Element h = pairing.getG1().newElement();
        h.setFromHash(attr.getBytes(StandardCharsets.UTF_8), 0, attr.length());
        return h.getImmutable();
    }

    /**
     * 计算拉格朗日系数 Δ_i(0)
     */
    private BigInteger lagrangeCoefficient(int i, int n) {
        BigInteger num = BigInteger.ONE;
        BigInteger den = BigInteger.ONE;
        for (int j = 1; j <= n; j++) {
            if (i == j) continue;
            num = num.multiply(BigInteger.valueOf(j));
            den = den.multiply(BigInteger.valueOf(j - i));
        }
        return num.multiply(den.modInverse(pairing.getZr().getOrder())).mod(pairing.getZr().getOrder());
    }

    /**
     * 密钥生成：为用户属性集生成私钥
     */
    public void keyGen(Scanner scanner) {
        if (pairing == null) {
            System.out.println(" 请先执行系统初始化！");
            return;
        }

        System.out.print("输入用户名：");
        String username = scanner.nextLine().trim();
        if (username.isEmpty() || userKeyMap.containsKey(username)) {
            System.out.println(" 用户名无效或已存在！");
            return;
        }

        System.out.print("输入用户属性 (逗号分隔，如 IT,MANAGER)：");
        List<String> attrs = new ArrayList<>(Arrays.asList(scanner.nextLine().split(",")));
        attrs.replaceAll(String::trim);
        attrs.removeIf(String::isEmpty);

        if (attrs.isEmpty()) {
            System.out.println(" 属性不能为空！");
            return;
        }

        // 为每个用户生成独立随机数 t
        Element t = pairing.getZr().newRandomElement().getImmutable();
        Element D = g.powZn(alpha.add(t).mul(beta.invert())).getImmutable();

        Map<String, Element> D_i = new HashMap<>();
        Map<String, Element> D_i_prime = new HashMap<>();
        int n = attrs.size();

        // 为每个属性生成私钥分量
        for (int idx = 0; idx < n; idx++) {
            String attr = attrs.get(idx);
            Element r_i = pairing.getZr().newRandomElement().getImmutable();
            BigInteger delta = lagrangeCoefficient(idx + 1, n);
            Element deltaElem = pairing.getZr().newElement(delta).getImmutable();
            
            D_i.put(attr, g.powZn(t.mul(deltaElem)).mul(hash(attr).powZn(r_i)).getImmutable());
            D_i_prime.put(attr, g.powZn(r_i).getImmutable());
        }

        userKeyMap.put(username, new UserKey(username, attrs, D, D_i, D_i_prime));
        System.out.println(" 私钥生成成功 | 用户：" + username + " | 属性：" + attrs);
    }

    /**
     * 加密：使用访问策略加密明文
     */
    public void encrypt(Scanner scanner) {
        if (pairing == null) {
            System.out.println("请先执行系统初始化！");
            return;
        }

        System.out.print("输入明文：");
        String msg = scanner.nextLine();
        System.out.print("输入访问策略属性 (逗号分隔)：");
        List<String> policyAttrs = new ArrayList<>(Arrays.asList(scanner.nextLine().split(",")));
        policyAttrs.replaceAll(String::trim);
        policyAttrs.removeIf(String::isEmpty);

        if (msg.isEmpty() || policyAttrs.isEmpty()) {
            System.out.println(" 明文或策略不能为空！");
            return;
        }

        // 生成加密随机数 s
        Element s = pairing.getZr().newRandomElement().getImmutable();
        
        // 将明文映射到 GT 群
        Element M = pairing.getGT().newElement();
        M.setFromHash(msg.getBytes(StandardCharsets.UTF_8), 0, msg.length());


        Element C = M.mul(e_gg_alpha.powZn(s)).getImmutable();
        Element C_prime = h.powZn(s).getImmutable();
        Element C_0 = g.powZn(s).getImmutable();
        Map<String, Element> C_i = new HashMap<>();
        for (String attr : policyAttrs) {
            C_i.put(attr, hash(attr).powZn(s).getImmutable());  // C_i = H(attr)^s
        }

        currentCipherText = new CipherText(String.join(",", policyAttrs), policyAttrs, C, C_prime, C_0, C_i, msg);
        System.out.println(" 加密成功 | 策略：" + currentCipherText.policy);
    }

    /**
     * 解密：使用用户私钥解密密文
     */
    public void decrypt(Scanner scanner) {
        if (currentCipherText == null) {
            System.out.println(" 请先执行加密！");
            return;
        }

        System.out.print("输入用户名：");
        String username = scanner.nextLine().trim();
        UserKey sk = userKeyMap.get(username);

        if (sk == null) {
            System.out.println(" 用户不存在！");
            return;
        }

        try {
            String result = decryptCore(sk, currentCipherText);
            System.out.println(" 解密成功：" + result);
        } catch (Exception e) {
            System.out.println(" 解密失败：" + e.getMessage());
        }
    }

    /**
     * 解密
     */
    private String decryptCore(UserKey sk, CipherText ct) throws Exception {
        // 检查属性是否满足策略
        if (!sk.attributes.containsAll(ct.policyAttrs)) {
            List<String> missing = new ArrayList<>(ct.policyAttrs);
            missing.removeAll(sk.attributes);
            throw new Exception("属性不足，缺少：" + missing);
        }

        Element A = pairing.pairing(ct.C_prime, sk.D);

        Element B = null;
        for (String attr : ct.policyAttrs) {
            Element C_i = ct.C_i.get(attr);
            Element D_i = sk.D_i.get(attr);
            Element D_i_prime = sk.D_i_prime.get(attr);
            
            if (C_i == null || D_i == null || D_i_prime == null) {
                throw new Exception("缺少属性组件：" + attr);
            }
            
            Element ratio = pairing.pairing(ct.C_0, D_i).div(pairing.pairing(C_i, D_i_prime));
            B = (B == null) ? ratio : B.mul(ratio);
        }

        if (B == null) throw new Exception("策略属性列表为空");
        // 恢复明文
        Element M_recovered = ct.C.mul(B).div(A);

        Element expectedM = pairing.getGT().newElement();
        expectedM.setFromHash(ct.originalMsg.getBytes(StandardCharsets.UTF_8), 0, ct.originalMsg.length());

        if (!M_recovered.isEqual(expectedM)) {
            throw new Exception("私钥无效或共谋攻击失败（消息验证不通过）");
        }
        return ct.originalMsg;
    }

    /**
     * 验证非法用户无法解密
     */
    public void testIllegalKey() {
        if (currentCipherText == null) {
            System.out.println(" 请先执行加密！");
            return;
        }

        System.out.println("\n非法用户解密验证");
        System.out.println("当前策略：" + currentCipherText.policy);

        // 构造伪造私钥
        Map<String, Element> fakeD_i = new HashMap<>();
        Map<String, Element> fakeD_i_prime = new HashMap<>();
        for (String attr : currentCipherText.policyAttrs) {
            fakeD_i.put(attr, pairing.getG1().newRandomElement());
            fakeD_i_prime.put(attr, pairing.getG1().newRandomElement());
        }

        UserKey fakeKey = new UserKey("伪造用户", currentCipherText.policyAttrs,
                pairing.getG1().newRandomElement(), fakeD_i, fakeD_i_prime);

        try {
            decryptCore(fakeKey, currentCipherText);
            System.out.println(" 验证失败：伪造私钥解密成功");
        } catch (Exception e) {
            System.out.println(" 验证成功：非法用户无法解密\n  原因：" + e.getMessage());
        }
    }

    /**
     * 抗共谋攻击
     */
    public void testCollusion() {
        if (currentCipherText == null || userKeyMap.size() < 2) {
            System.out.println(" 需要加密且至少 2 个用户！");
            return;
        }

        // 筛选属性不全的用户
        List<UserKey> insufficientUsers = userKeyMap.values().stream()
                .filter(u -> !u.attributes.containsAll(currentCipherText.policyAttrs))
                .collect(Collectors.toList());

        if (insufficientUsers.size() < 2) {
            System.out.println("需要至少 2 个属性不全的用户！");
            return;
        }

        System.out.println("\n抗共谋攻击验证");
        UserKey u1 = insufficientUsers.get(0);
        UserKey u2 = insufficientUsers.get(1);
        System.out.println("用户 1：" + u1.username + " | 用户 2：" + u2.username);
        System.out.println("当前策略：" + currentCipherText.policy);

        // 合并两个用户的私钥组件
        Map<String, Element> combined_D_i = new HashMap<>();
        Map<String, Element> combined_D_i_prime = new HashMap<>();
        combined_D_i.putAll(u1.D_i);
        combined_D_i.putAll(u2.D_i);
        combined_D_i_prime.putAll(u1.D_i_prime);
        combined_D_i_prime.putAll(u2.D_i_prime);

        List<String> combinedAttrs = new ArrayList<>();
        combinedAttrs.addAll(u1.attributes);
        combinedAttrs.addAll(u2.attributes);

        UserKey collusionKey = new UserKey("共谋者", combinedAttrs, u1.D, combined_D_i, combined_D_i_prime);

        try {
            decryptCore(collusionKey, currentCipherText);
            System.out.println(" 验证失败：共谋攻击成功");
        } catch (Exception e) {
            System.out.println(" 验证成功：系统抵抗共谋攻击");
        }
    }
}
