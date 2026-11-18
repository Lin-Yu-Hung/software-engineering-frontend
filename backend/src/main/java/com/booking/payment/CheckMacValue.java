package com.booking.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 綠界檢查碼 CheckMacValue（EncryptType=1，SHA256），規格見 https://developers.ecpay.com.tw/?p=2902。
 * 已用官方文件範例驗證，見 CheckMacValueTests。
 */
public final class CheckMacValue {
    private CheckMacValue() { }

    public static String of(Map<String, String> params, String hashKey, String hashIv) {
        // 步驟 1：參數依名稱 A-Z 排序（不分大小寫），排除 CheckMacValue 本身。
        var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        params.forEach((k, v) -> { if (!"CheckMacValue".equalsIgnoreCase(k)) sorted.put(k, v == null ? "" : v); });
        // 步驟 2：組成 HashKey=...&k=v&...&HashIV=...
        var raw = new StringBuilder("HashKey=").append(hashKey);
        sorted.forEach((k, v) -> raw.append('&').append(k).append('=').append(v));
        raw.append("&HashIV=").append(hashIv);
        // 步驟 3～4：依 .NET 規則 URL encode 後轉小寫。Java 本來就保留 - _ . * 不編碼、空白轉 +；
        // .NET 另外保留 ! ( ) 不編碼，所以要換回來。
        String encoded = URLEncoder.encode(raw.toString(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)
                .replace("%21", "!").replace("%28", "(").replace("%29", ")");
        // 步驟 5～6：SHA256 雜湊後轉成大寫十六進位。
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 以固定時間比對，避免偽造的通知透過回應時間差逐字猜出簽章。 */
    public static boolean verify(Map<String, String> params, String hashKey, String hashIv) {
        String given = params.get("CheckMacValue");
        if (given == null) return false;
        return MessageDigest.isEqual(of(params, hashKey, hashIv).getBytes(StandardCharsets.US_ASCII),
                given.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
