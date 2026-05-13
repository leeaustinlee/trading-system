-- KOL / Podcast Theme Signal Engine MVP.
--
-- Guardrails:
--   1. KOL / Podcast signals are weak external context only.
--   2. No production decision formula, risk gate, price gate, market grade, tradability,
--      capital rule, or FinalDecisionEngine condition is changed by this migration.
--   3. Aggregates live in separate tables and must not pollute theme_heat_score or
--      final_theme_score.

CREATE TABLE IF NOT EXISTS kol_source_profile (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_key          VARCHAR(120) NOT NULL,
    source_type         VARCHAR(40)  NOT NULL,
    display_name        VARCHAR(160) NULL,
    credibility_weight  DECIMAL(6,3) NOT NULL DEFAULT 1.000,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    payload_json        JSON         NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kol_source_key (source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kol_theme_signal (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date    DATE          NOT NULL,
    source_key      VARCHAR(120)  NOT NULL,
    source_type     VARCHAR(40)   NOT NULL,
    source_title    VARCHAR(300)  NULL,
    content_hash    CHAR(64)      NOT NULL,
    raw_content     MEDIUMTEXT    NULL,
    signal_status   VARCHAR(40)   NOT NULL DEFAULT 'RAW',
    payload_json    JSON          NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kol_content_hash (content_hash),
    KEY idx_kol_signal_date (trading_date),
    KEY idx_kol_signal_source_date (source_key, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kol_theme_stock_mapping (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    signal_id       BIGINT       NOT NULL,
    trading_date    DATE         NOT NULL,
    theme_tag       VARCHAR(120) NOT NULL,
    direction       VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    symbol          VARCHAR(20)  NOT NULL,
    stock_name      VARCHAR(120) NULL,
    confidence      DECIMAL(6,3) NULL,
    payload_json    JSON         NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kol_mapping_signal_theme_symbol (signal_id, theme_tag, symbol),
    KEY idx_kol_mapping_date_theme (trading_date, theme_tag),
    KEY idx_kol_mapping_symbol_date (symbol, trading_date),
    CONSTRAINT fk_kol_mapping_signal
        FOREIGN KEY (signal_id) REFERENCES kol_theme_signal(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS theme_signal_evidence (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    signal_id       BIGINT       NOT NULL,
    trading_date    DATE         NOT NULL,
    theme_tag       VARCHAR(120) NOT NULL,
    direction       VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    evidence_type   VARCHAR(40)  NOT NULL DEFAULT 'UNKNOWN',
    evidence_text   TEXT         NULL,
    confidence      DECIMAL(6,3) NULL,
    evidence_score  DECIMAL(8,4) NULL,
    payload_json    JSON         NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kol_evidence_date_theme (trading_date, theme_tag, direction),
    KEY idx_kol_evidence_signal (signal_id),
    CONSTRAINT fk_kol_evidence_signal
        FOREIGN KEY (signal_id) REFERENCES kol_theme_signal(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kol_signal_trace (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    signal_id       BIGINT       NULL,
    trace_stage     VARCHAR(60)  NOT NULL,
    trace_action    VARCHAR(80)  NOT NULL,
    detail_json     JSON         NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kol_trace_signal (signal_id, created_at),
    CONSTRAINT fk_kol_trace_signal
        FOREIGN KEY (signal_id) REFERENCES kol_theme_signal(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS kol_theme_signal_daily_snapshot (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date        DATE         NOT NULL,
    theme_tag           VARCHAR(120) NOT NULL,
    direction           VARCHAR(20)  NOT NULL,
    source_count        INT          NOT NULL DEFAULT 0,
    evidence_count      INT          NOT NULL DEFAULT 0,
    positive_score      DECIMAL(8,4) NOT NULL DEFAULT 0.0000,
    negative_score      DECIMAL(8,4) NOT NULL DEFAULT 0.0000,
    net_shadow_boost    DECIMAL(8,4) NOT NULL DEFAULT 0.0000,
    crowding_risk       VARCHAR(20)  NOT NULL DEFAULT 'LOW',
    top_sources_json    JSON         NULL,
    payload_json        JSON         NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kol_snapshot_date_theme_direction (trading_date, theme_tag, direction),
    KEY idx_kol_snapshot_date_boost (trading_date, net_shadow_boost),
    KEY idx_kol_snapshot_theme_date (theme_tag, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
