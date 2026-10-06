import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用 Google 官方的 apksig 库给 APK 签名，同时开启 v1 + v2 + v3。
 *
 * 用法: ApkSign <keystore> <storepass> <keypass> <alias> <in.apk> <out.apk>
 *
 * 为什么不用 jarsigner：jarsigner 只做 v1（JAR 签名），
 * 而 v2/v3 是 Android 7.0+ 引入的 APK Signature Scheme，
 * 保护强度更高、安装更快，且 v3 支持密钥轮换。
 */
public class ApkSign {
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("用法: ApkSign <keystore> <storepass> <keypass> <alias> <in> <out>");
            System.exit(2);
        }
        String ksPath = args[0], storePass = args[1], keyPass = args[2], alias = args[3];
        File in = new File(args[4]), out = new File(args[5]);

        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(ksPath)) {
            ks.load(fis, storePass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass.toCharArray());
        if (key == null) { System.err.println("找不到私钥，alias 或密码不对"); System.exit(1); }

        Certificate[] chain = ks.getCertificateChain(alias);
        if (chain == null || chain.length == 0) { System.err.println("证书链为空"); System.exit(1); }
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) certs.add((X509Certificate) c);

        ApkSigner.SignerConfig sc =
                new ApkSigner.SignerConfig.Builder(alias, key, certs).build();

        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)     // JAR 签名（Android 6 及以下）
                .setV2SigningEnabled(true)     // APK Signature Scheme v2（7.0+）
                .setV3SigningEnabled(true)     // v3（9.0+，支持密钥轮换）
                .setMinSdkVersion(21)
                .build()
                .sign();

        System.out.println("已签名: v1 + v2 + v3");
    }
}
