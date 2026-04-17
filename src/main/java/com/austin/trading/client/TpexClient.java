package com.austin.trading.client;

import com.austin.trading.client.dto.StockQuote;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 上櫃（OTC）股票報價客戶端。
 * <p>
 * TWSE MIS API 以 {@code otc_} 前綴同樣支援上櫃即時報價，
 * 因此本類直接委派給 {@link TwseMisClient}。
 * </p>
 */
@Component
public class TpexClient {

    private final TwseMisClient twseMisClient;

    public TpexClient(TwseMisClient twseMisClient) {
        this.twseMisClient = twseMisClient;
    }

    public Optional<StockQuote> getQuote(String symbol) {
        return twseMisClient.getOtcQuote(symbol);
    }

    public List<StockQuote> getQuotes(List<String> symbols) {
        return twseMisClient.getOtcQuotes(symbols);
    }
}
