package com.fincore.transaction.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Explicit connect and read timeouts on every outbound call: no request may wait forever. */
@Configuration
public class DownstreamConfig {

    private static RestClient build(String baseUrl, Duration connect, Duration read) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(connect).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    RestClient accountRestClient(@Value("${fincore.downstream.account.base-url}") String url,
                                 @Value("${fincore.downstream.account.connect-timeout-ms:1000}") long connect,
                                 @Value("${fincore.downstream.account.read-timeout-ms:2000}") long read) {
        return build(url, Duration.ofMillis(connect), Duration.ofMillis(read));
    }

    @Bean
    RestClient riskRestClient(@Value("${fincore.downstream.risk.base-url}") String url,
                              @Value("${fincore.downstream.risk.connect-timeout-ms:1000}") long connect,
                              @Value("${fincore.downstream.risk.read-timeout-ms:2000}") long read) {
        return build(url, Duration.ofMillis(connect), Duration.ofMillis(read));
    }
}
