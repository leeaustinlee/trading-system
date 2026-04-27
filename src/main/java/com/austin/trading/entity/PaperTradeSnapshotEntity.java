package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Subagent C: full point-in-time decision trace snapshot for a {@link PaperTradeEntity}.
 *
 * <p>One row per (paperTradeId, snapshotType) - typically one ENTRY at open and one EXIT at close.
 * {@code payload_json} contains the entire decision context bundle so the trade can be
 * 100% reconstructed for backtesting / AI calibration without joining 5+ tables.</p>
 *
 * <p>FK to {@code paper_trade.id} is implicit (no real DB constraint, just convention) so
 * we don't introduce a hard coupling that would block schema migrations on the parent table.</p>
 */
@Entity
@Table(name = "paper_trade_snapshot",
        indexes = {
                @Index(name = "idx_pts_paper_trade_id", columnList = "paper_trade_id"),
                @Index(name = "idx_pts_paper_type",     columnList = "paper_trade_id, snapshot_type"),
                @Index(name = "idx_pts_captured_at",    columnList = "captured_at")
        })
public class PaperTradeSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_trade_id", nullable = false)
    private Long paperTradeId;

    /** ENTRY / EXIT / DAILY_KLINE */
    @Column(name = "snapshot_type", nullable = false, length = 20)
    private String snapshotType;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    /** "v1.0" - increments when payload structure changes. */
    @Column(name = "schema_version", length = 10)
    private String schemaVersion;

    public PaperTradeSnapshotEntity() {}

    public PaperTradeSnapshotEntity(Long paperTradeId, String snapshotType,
                                    LocalDateTime capturedAt, String payloadJson,
                                    String schemaVersion) {
        this.paperTradeId = paperTradeId;
        this.snapshotType = snapshotType;
        this.capturedAt = capturedAt;
        this.payloadJson = payloadJson;
        this.schemaVersion = schemaVersion;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaperTradeId() { return paperTradeId; }
    public void setPaperTradeId(Long paperTradeId) { this.paperTradeId = paperTradeId; }
    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime capturedAt) { this.capturedAt = capturedAt; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
}
