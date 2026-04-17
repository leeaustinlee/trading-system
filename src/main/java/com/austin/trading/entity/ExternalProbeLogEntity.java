package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "external_probe_log")
public class ExternalProbeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Column(name = "taifex_date")
    private LocalDate taifexDate;

    @Column(name = "live_line", nullable = false)
    private boolean liveLine;

    @Column(name = "live_claude", nullable = false)
    private boolean liveClaude;

    @Column(name = "taifex_status", length = 20)
    private String taifexStatus;

    @Column(name = "taifex_success")
    private Boolean taifexSuccess;

    @Column(name = "taifex_detail", length = 1000)
    private String taifexDetail;

    @Column(name = "line_status", length = 20)
    private String lineStatus;

    @Column(name = "line_success")
    private Boolean lineSuccess;

    @Column(name = "line_detail", length = 1000)
    private String lineDetail;

    @Column(name = "claude_status", length = 20)
    private String claudeStatus;

    @Column(name = "claude_success")
    private Boolean claudeSuccess;

    @Column(name = "claude_detail", length = 1000)
    private String claudeDetail;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
    public LocalDate getTaifexDate() { return taifexDate; }
    public void setTaifexDate(LocalDate taifexDate) { this.taifexDate = taifexDate; }
    public boolean isLiveLine() { return liveLine; }
    public void setLiveLine(boolean liveLine) { this.liveLine = liveLine; }
    public boolean isLiveClaude() { return liveClaude; }
    public void setLiveClaude(boolean liveClaude) { this.liveClaude = liveClaude; }
    public String getTaifexStatus() { return taifexStatus; }
    public void setTaifexStatus(String taifexStatus) { this.taifexStatus = taifexStatus; }
    public Boolean getTaifexSuccess() { return taifexSuccess; }
    public void setTaifexSuccess(Boolean taifexSuccess) { this.taifexSuccess = taifexSuccess; }
    public String getTaifexDetail() { return taifexDetail; }
    public void setTaifexDetail(String taifexDetail) { this.taifexDetail = taifexDetail; }
    public String getLineStatus() { return lineStatus; }
    public void setLineStatus(String lineStatus) { this.lineStatus = lineStatus; }
    public Boolean getLineSuccess() { return lineSuccess; }
    public void setLineSuccess(Boolean lineSuccess) { this.lineSuccess = lineSuccess; }
    public String getLineDetail() { return lineDetail; }
    public void setLineDetail(String lineDetail) { this.lineDetail = lineDetail; }
    public String getClaudeStatus() { return claudeStatus; }
    public void setClaudeStatus(String claudeStatus) { this.claudeStatus = claudeStatus; }
    public Boolean getClaudeSuccess() { return claudeSuccess; }
    public void setClaudeSuccess(Boolean claudeSuccess) { this.claudeSuccess = claudeSuccess; }
    public String getClaudeDetail() { return claudeDetail; }
    public void setClaudeDetail(String claudeDetail) { this.claudeDetail = claudeDetail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
