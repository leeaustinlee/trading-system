package com.austin.trading.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClient 全局設定
 * - 連線逾時 10s、回應逾時 15s
 * - 預設 User-Agent 模擬瀏覽器（TWSE/TAIFEX API 需要此 header）
 * - 啟用 gzip 壓縮
 * - codec 緩衝 16MB（TAIFEX DailyMarketReportFut 回傳 2000+ 筆 ~800KB，預設 256KB 不夠）
 */
@Configuration
public class WebClientConfig {

    private static final int DEFAULT_MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(15))
                .compress(true);

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(DEFAULT_MAX_IN_MEMORY_SIZE))
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "zh-TW,zh;q=0.9,en;q=0.8")
                .defaultHeader(HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE + ", */*;q=0.8");
    }
}
