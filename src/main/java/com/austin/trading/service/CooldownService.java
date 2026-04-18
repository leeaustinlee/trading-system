package com.austin.trading.service;

import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PositionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易冷卻查詢服務。
 * <p>從 position close history 推導某 symbol / theme 是否仍在冷卻期。</p>
 */
@Service
public class CooldownService {

    private final PositionRepository positionRepository;
    private final ScoreConfigService config;

    public CooldownService(PositionRepository positionRepository, ScoreConfigService config) {
        this.positionRepository = positionRepository;
        this.config = config;
    }

    /** 檢查指定 symbol 是否在冷卻期 */
    public boolean isSymbolInCooldown(String symbol) {
        if (!config.getBoolean("trading.cooldown.enabled", true)) return false;

        int normalMinutes = config.getInt("trading.cooldown.after_exit_minutes", 60);
        int lossMinutes = config.getInt("trading.cooldown.after_loss_exit_minutes", 1440);
        int maxMinutes = Math.max(normalMinutes, lossMinutes);

        LocalDateTime since = LocalDateTime.now().minusMinutes(maxMinutes);
        List<PositionEntity> recent = positionRepository.findRecentlyClosedBySymbol(symbol, since);

        for (PositionEntity p : recent) {
            if (p.getClosedAt() == null) continue;
            boolean isLoss = p.getRealizedPnl() != null && p.getRealizedPnl().signum() < 0;
            int cooldownMin = isLoss ? lossMinutes : normalMinutes;
            LocalDateTime cooldownUntil = p.getClosedAt().plusMinutes(cooldownMin);
            if (LocalDateTime.now().isBefore(cooldownUntil)) return true;
        }
        return false;
    }

    /** 檢查指定題材是否在冷卻期 */
    public boolean isThemeInCooldown(String themeTag) {
        if (themeTag == null || themeTag.isBlank()) return false;
        if (!config.getBoolean("trading.cooldown.enabled", true)) return false;
        if (!config.getBoolean("trading.cooldown.same_theme_enabled", true)) return false;

        int themeMinutes = config.getInt("trading.cooldown.same_theme_minutes", 720);
        LocalDateTime since = LocalDateTime.now().minusMinutes(themeMinutes);

        List<PositionEntity> recent = positionRepository.findRecentlyClosedByTheme(themeTag, since);
        for (PositionEntity p : recent) {
            if (p.getClosedAt() == null) continue;
            LocalDateTime cooldownUntil = p.getClosedAt().plusMinutes(themeMinutes);
            if (LocalDateTime.now().isBefore(cooldownUntil)) return true;
        }
        return false;
    }

    /** 綜合判斷：symbol 或 theme 任一在冷卻中 */
    public boolean isInCooldown(String symbol, String themeTag) {
        boolean symbolOnly = config.getBoolean("trading.cooldown.same_symbol_only", true);
        if (isSymbolInCooldown(symbol)) return true;
        if (!symbolOnly && isThemeInCooldown(themeTag)) return true;
        return false;
    }
}
