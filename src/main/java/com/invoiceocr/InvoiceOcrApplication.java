package com.invoiceocr;

import com.invoiceocr.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableAsync
@EnableConfigurationProperties(AppProperties.class)
public class InvoiceOcrApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvoiceOcrApplication.class, args);
    }
}
