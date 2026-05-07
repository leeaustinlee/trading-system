# 預計異動檔案

## P0 決策可信度

- `src/main/java/com/austin/trading/dto/request/FinalDecisionCandidateRequest.java`
- `src/main/java/com/austin/trading/service/FinalDecisionService.java`
- `src/main/java/com/austin/trading/dto/response/DashboardCurrentResponse.java`
- `src/main/java/com/austin/trading/controller/DashboardController.java`
- `src/main/java/com/austin/trading/engine/PositionDecisionEngine.java`
- `src/main/java/com/austin/trading/service/PositionReviewService.java`
- `src/main/java/com/austin/trading/dto/response/PositionResponse.java`
- `src/main/java/com/austin/trading/service/PositionService.java`

## 三策略模型

- `src/main/java/com/austin/trading/domain/enums/StrategyType.java`
- `src/main/java/com/austin/trading/dto/internal/StrategyGateResult.java`
- `src/main/java/com/austin/trading/engine/StrategyClassifier.java`
- `src/main/java/com/austin/trading/engine/BreakoutGate.java`
- `src/main/java/com/austin/trading/engine/PullbackGate.java`
- `src/main/java/com/austin/trading/engine/ContinuationGate.java`
- `src/main/java/com/austin/trading/service/StrategyGateService.java`

## Tracking

- `src/main/java/com/austin/trading/entity/MissedRallyTrackingEntity.java`
- `src/main/java/com/austin/trading/entity/CandidateForwardTrackingEntity.java`
- `src/main/java/com/austin/trading/repository/MissedRallyTrackingRepository.java`
- `src/main/java/com/austin/trading/repository/CandidateForwardTrackingRepository.java`
- `src/main/java/com/austin/trading/service/MissedRallyTrackingService.java`
- `src/main/java/com/austin/trading/service/CandidateForwardTrackingService.java`
- `src/main/java/com/austin/trading/controller/MissedRallyTrackingController.java`
- `src/main/java/com/austin/trading/controller/CandidateForwardTrackingController.java`
- `sql/V28__strategy_tracking_foundation.sql`

## 測試

- `src/test/java/com/austin/trading/dto/request/FinalDecisionCandidateRequestTests.java`
- `src/test/java/com/austin/trading/engine/PositionDecisionEngineTests.java`
- `src/test/java/com/austin/trading/engine/StrategyGateTests.java`
- `src/test/java/com/austin/trading/service/MissedRallyTrackingServiceTests.java`
