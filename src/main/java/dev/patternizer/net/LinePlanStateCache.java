package dev.patternizer.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.patternizer.planner.GapAnalyzer.PlanResult;

/**
 * 整线方案的服务端缓存（§8：请求→确认两段式）。
 * 玩家确认后消费并移除；重发请求覆盖；玩家下线清理。
 */
public final class LinePlanStateCache {

    private static final Map<UUID, PlanResult> PLANS = new ConcurrentHashMap<>();

    private LinePlanStateCache() {
    }

    public static void put(UUID playerId, PlanResult result) {
        PLANS.put(playerId, result);
    }

    public static PlanResult take(UUID playerId) {
        return PLANS.remove(playerId);
    }
}
