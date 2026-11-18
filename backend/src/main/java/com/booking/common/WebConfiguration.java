package com.booking.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    public WebConfiguration(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 必須先註冊，才會優先於下面的 /api/v1/**：付款完成後，綠界頁面會讓使用者瀏覽器
        // 以 Origin https://payment-stage.ecpay.com.tw（或 "null"）POST 到這裡。
        // 這些端點靠 CheckMacValue 簽章保護，不靠 CORS，也不使用 cookie。
        registry.addMapping("/api/v1/payments/ecpay/**")
                .allowedOrigins("*")
                .allowedMethods("POST")
                .allowCredentials(false);
        registry.addMapping("/api/v1/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .allowedHeaders("*");
    }
}
