package dev.patternizer.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.patternizer.spec.PatternSpec.Entry;
import dev.patternizer.spec.PatternSpec.Role;

/**
 * 催化剂策略（§6.2 选型规则与 §10.15 黑名单）。
 * 已知「放进机器后无法被 ME 接口收回」的催化剂，强制从返还式改为预置式，
 * 否则合成 CPU 会一直等副产物回流而卡死（注魔水晶是社区经典案例）。
 * 双端共用：客户端在预览前应用（含玩家配置扩展），服务端编码前以内置名单防御。
 */
public final class CatalystPolicy {

    /** 内置「不可收回催化剂」黑名单（社区已确认案例，可经客户端配置扩充）。 */
    public static final Set<String> BUILTIN_UNRETURNABLE = Set.of(
            "mysticalagriculture:infusion_crystal",
            "mysticalagriculture:master_infusion_crystal");

    private CatalystPolicy() {
    }

    /**
     * 命中黑名单的 catalyst_returned 强制改为 catalyst_preplaced。
     *
     * @return 被改写的物品 id 列表（用于 GUI/聊天提示）
     */
    public static List<String> apply(PatternSpec spec, Set<String> extraUnreturnable) {
        List<String> hits = new ArrayList<>();
        for (Entry e : spec.inputs) {
            if (e.role == Role.CATALYST_RETURNED && !e.isFluid()
                    && (BUILTIN_UNRETURNABLE.contains(e.item) || extraUnreturnable.contains(e.item))) {
                e.role = Role.CATALYST_PREPLACED;
                hits.add(e.item);
                String policyNote = "【系统】" + e.item + " 已知不可收回，已改为预置式。";
                spec.note = spec.note == null || spec.note.isBlank()
                        ? policyNote
                        : spec.note + " " + policyNote;
            }
        }
        return hits;
    }
}
