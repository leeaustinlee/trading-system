package com.austin.trading.entity;

/**
 * 資金流水類型。所有 {@link CapitalLedgerEntity#getAmount()} 使用 signed amount：
 * 現金流入為正、現金流出為負。
 *
 * <p>Enum 與 DB 字串一對一；新增類型時只擴充 enum，不破壞舊資料。</p>
 */
public enum LedgerType {

    /** 入金（+）*/
    DEPOSIT,

    /** 出金（-）*/
    WITHDRAW,

    /** 建倉成交金額（-；qty × avgCost）*/
    BUY_OPEN,

    /** 全部出清成交金額（+；qty × closePrice）*/
    SELL_CLOSE,

    /** 部分出清成交金額（+；sellQty × closePrice）*/
    SELL_PARTIAL,

    /** 手續費（-）*/
    FEE,

    /** 交易稅（-）*/
    TAX,

    /** 手動調帳（正負皆可）*/
    MANUAL_ADJUST,

    /** 啟動遷移：將 capital_config.available_cash 寫成 opening balance（+）*/
    INITIAL_BALANCE
}
