package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 可設定的評分與策略參數表。
 * <p>
 * 取代所有 Engine 中的 hard-code 常數。
 * 例：scoring.java_weight=0.50、candidate.scan.maxCount=10。
 * </p>
 */
@Entity
@Table(name = "score_config")
public class ScoreConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 500)
    private String configValue;

    /** STRING / INTEGER / DECIMAL / BOOLEAN */
    @Column(name = "value_type", length = 20)
    private String valueType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "updated_at", insertable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
