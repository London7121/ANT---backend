package uz.ant.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Component;

/**
 * Parol xeshi: `salt(hex):scrypt(hex)` — eski Node versiyasi bilan BAYT-BAYT bir xil format
 * (N=16384, r=8, p=1, 64 bayt; salt sifatida hex MATNning o'zi ishlatiladi), shuning uchun
 * db.json dan ko'chirilgan parollar o'zgarishsiz ishlaydi.
 */
@Component
public class PasswordHasher {
    private static final int N = 16384, R = 8, P = 1, KEY_LEN = 64;
    private final SecureRandom random = new SecureRandom();
    private final HexFormat hex = HexFormat.of();

    public String hash(String password) {
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = hex.formatHex(saltBytes);
        return salt + ":" + hex.formatHex(derive(password, salt));
    }

    public boolean matches(String password, String stored) {
        if (stored == null) return false;
        int i = stored.indexOf(':');
        if (i <= 0) return false;
        try {
            byte[] expected = hex.parseHex(stored.substring(i + 1));
            return MessageDigest.isEqual(expected, derive(password, stored.substring(0, i)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, String salt) {
        return SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt.getBytes(StandardCharsets.UTF_8), N, R, P, KEY_LEN);
    }
}
