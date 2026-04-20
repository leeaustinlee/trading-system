package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Claude File Bridge 的錯誤稽核表（v2.1）。
 *
 * <p>每次 Bridge 解析失敗、task 狀態非法、找不到 task 等事件都會寫入一筆，
 * 供維運人員追查問題。</p>
 */
@Entity
@Table(
        name = "file_bridge_error_log",
        indexes = {
                @Index(name = "idx_fbel_trading_date", columnList = "trading_date"),
                @Index(name = "idx_fbel_error_code",   columnList = "error_code"),
                @Index(name = "idx_fbel_task_id",      columnList = "task_id")
        }
)
public class FileBridgeErrorLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "task_type", length = 30)
    private String taskType;

    @Column(name = "trading_date")
    private LocalDate tradingDate;

    /** FILE_BRIDGE_PARSE_ERROR / AI_TASK_INVALID_STATE / TASK_NOT_FOUND / TASK_EXPIRED / ... */
    @Column(name = "error_code", nullable = false, length = 60)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
