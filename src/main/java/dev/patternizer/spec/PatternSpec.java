package dev.patternizer.spec;

import java.util.ArrayList;
import java.util.List;

/**
 * 样板规格（§5.3 内部契约）。双端共用：客户端解析 LLM 输出，服务端编码前再校验。
 */
public final class PatternSpec {

    public enum Type {
        CRAFTING, PROCESSING, STONECUTTING, SMITHING
    }

    public enum Role {
        CONSUMED, CATALYST_RETURNED, CATALYST_PREPLACED, CATALYST_DURABILITY
    }

    /** 一个输入/输出格：item 与 fluid 二选一。 */
    public static final class Entry {
        public String item; // "modid:name"，与 fluid 互斥
        public String fluid; // "modid:name"，与 item 互斥
        public int count = 1; // 物品数量
        public int amount; // 流体 mB
        public Role role = Role.CONSUMED;

        public boolean isFluid() {
            return fluid != null && !fluid.isEmpty();
        }

        public String key() {
            return isFluid() ? "fluid:" + fluid : "item:" + item;
        }
    }

    /** 耐久折批参数（§5.3 durability_batch）。 */
    public static final class DurabilityBatch {
        public String tool;
        public int usesPerTool = 1;
    }

    public Type type = Type.PROCESSING;
    public String target; // crafting/stonecutting/smithing 的目标产物 "modid:name"
    public final List<Entry> inputs = new ArrayList<>();
    public final List<Entry> outputs = new ArrayList<>();
    public DurabilityBatch durabilityBatch;
    public String note;
    public boolean allowSubstitutes;
    public boolean allowFluidSubstitutes;
}
