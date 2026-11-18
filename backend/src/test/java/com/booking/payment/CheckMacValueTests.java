package com.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;

class CheckMacValueTests {
    private static final String KEY = "pwFHCqoQZGmho4w6", IV = "EkRm7iFT261dpevs";

    /** 綠界官方文件範例 https://developers.ecpay.com.tw/?p=2902（TradeDesc = 促銷方案，以 Unicode 跳脫避免編碼問題）。 */
    private static Map<String, String> officialExample() {
        var p = new HashMap<String, String>();
        p.put("ChoosePayment", "ALL"); p.put("EncryptType", "1"); p.put("ItemName", "Apple iphone 15");
        p.put("MerchantID", "3002607"); p.put("MerchantTradeDate", "2023/03/12 15:30:23");
        p.put("MerchantTradeNo", "ecpay20230312153023"); p.put("PaymentType", "aio");
        p.put("ReturnURL", "https://www.ecpay.com.tw/receive.php"); p.put("TotalAmount", "30000");
        p.put("TradeDesc", "促銷方案");
        return p;
    }

    @Test void matchesEcpayOfficialExample() {
        assertThat(CheckMacValue.of(officialExample(), KEY, IV))
                .isEqualTo("6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840");
    }

    @Test void ignoresExistingCheckMacValueAndInputOrder() {
        var p = new LinkedHashMap<String, String>();
        p.put("CheckMacValue", "WHATEVER");
        new TreeMap<>(officialExample()).descendingMap().forEach(p::put);
        assertThat(CheckMacValue.of(p, KEY, IV)).isEqualTo(CheckMacValue.of(officialExample(), KEY, IV));
    }

    @Test void verifyAcceptsGenuineCaseInsensitiveAndRejectsTampering() {
        var p = officialExample();
        p.put("CheckMacValue", CheckMacValue.of(p, KEY, IV).toLowerCase(Locale.ROOT));
        assertThat(CheckMacValue.verify(p, KEY, IV)).isTrue();
        p.put("TotalAmount", "30001");
        assertThat(CheckMacValue.verify(p, KEY, IV)).isFalse();
        p.remove("CheckMacValue");
        assertThat(CheckMacValue.verify(p, KEY, IV)).isFalse();
        assertThat(CheckMacValue.verify(officialExample(), KEY, "wrong-iv")).isFalse();
    }
}
