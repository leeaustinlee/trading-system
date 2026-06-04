package com.austin.trading.engine;

import com.austin.trading.entity.PositionEntity;

import java.math.BigDecimal;

public record ExitArbiterInput(
        PositionEntity position,
        String tradeRefType,
        Long tradeRefId,
        PositionDecisionEngine.PositionDecisionResult sourceDecision,
        BigDecimal entryPrice,
        BigDecimal currentPrice,
        BigDecimal hardStopPrice,
        BigDecimal trailingStopPrice,
        BigDecimal dynamicStopPrice,
        BigDecimal ma5,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal previousLow,
        BigDecimal recentHigh,
        BigDecimal atr,
        BigDecimal volumeRatio,
        BigDecimal return5d,
        BigDecimal benchmarkReturn5d,
        BigDecimal return10d,
        BigDecimal benchmarkReturn10d,
        Integer healthScore,
        String structureStatus,
        String volumeStatus,
        String relativeStrengthStatus,
        String chipStatus,
        String themeStage,
        Integer themeRank,
        BigDecimal themeScore,
        Boolean mainstreamTheme,
        BigDecimal drawdownPct,
        Boolean momentumExitSignal
) {
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private PositionEntity position; private String tradeRefType; private Long tradeRefId;
        private PositionDecisionEngine.PositionDecisionResult sourceDecision;
        private BigDecimal entryPrice,currentPrice,hardStopPrice,trailingStopPrice,dynamicStopPrice,ma5,ma10,ma20,previousLow,recentHigh,atr,volumeRatio,return5d,benchmarkReturn5d,return10d,benchmarkReturn10d,themeScore,drawdownPct;
        private Integer healthScore,themeRank; private String structureStatus,volumeStatus,relativeStrengthStatus,chipStatus,themeStage; private Boolean mainstreamTheme,momentumExitSignal;
        public Builder position(PositionEntity v){position=v;return this;} public Builder tradeRefType(String v){tradeRefType=v;return this;} public Builder tradeRefId(Long v){tradeRefId=v;return this;}
        public Builder sourceDecision(PositionDecisionEngine.PositionDecisionResult v){sourceDecision=v;return this;} public Builder entryPrice(BigDecimal v){entryPrice=v;return this;} public Builder currentPrice(BigDecimal v){currentPrice=v;return this;}
        public Builder hardStopPrice(BigDecimal v){hardStopPrice=v;return this;} public Builder trailingStopPrice(BigDecimal v){trailingStopPrice=v;return this;} public Builder dynamicStopPrice(BigDecimal v){dynamicStopPrice=v;return this;}
        public Builder ma5(BigDecimal v){ma5=v;return this;} public Builder ma10(BigDecimal v){ma10=v;return this;} public Builder ma20(BigDecimal v){ma20=v;return this;} public Builder previousLow(BigDecimal v){previousLow=v;return this;} public Builder recentHigh(BigDecimal v){recentHigh=v;return this;} public Builder atr(BigDecimal v){atr=v;return this;}
        public Builder volumeRatio(BigDecimal v){volumeRatio=v;return this;} public Builder return5d(BigDecimal v){return5d=v;return this;} public Builder benchmarkReturn5d(BigDecimal v){benchmarkReturn5d=v;return this;} public Builder return10d(BigDecimal v){return10d=v;return this;} public Builder benchmarkReturn10d(BigDecimal v){benchmarkReturn10d=v;return this;}
        public Builder healthScore(Integer v){healthScore=v;return this;} public Builder structureStatus(String v){structureStatus=v;return this;} public Builder volumeStatus(String v){volumeStatus=v;return this;} public Builder relativeStrengthStatus(String v){relativeStrengthStatus=v;return this;} public Builder chipStatus(String v){chipStatus=v;return this;}
        public Builder themeStage(String v){themeStage=v;return this;} public Builder themeRank(Integer v){themeRank=v;return this;} public Builder themeScore(BigDecimal v){themeScore=v;return this;} public Builder mainstreamTheme(Boolean v){mainstreamTheme=v;return this;} public Builder drawdownPct(BigDecimal v){drawdownPct=v;return this;} public Builder momentumExitSignal(Boolean v){momentumExitSignal=v;return this;}
        public ExitArbiterInput build(){return new ExitArbiterInput(position,tradeRefType,tradeRefId,sourceDecision,entryPrice,currentPrice,hardStopPrice,trailingStopPrice,dynamicStopPrice,ma5,ma10,ma20,previousLow,recentHigh,atr,volumeRatio,return5d,benchmarkReturn5d,return10d,benchmarkReturn10d,healthScore,structureStatus,volumeStatus,relativeStrengthStatus,chipStatus,themeStage,themeRank,themeScore,mainstreamTheme,drawdownPct,momentumExitSignal);}
    }
}
