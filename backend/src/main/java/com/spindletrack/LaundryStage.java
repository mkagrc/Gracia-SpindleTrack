package com.spindletrack;

import java.util.List;

public enum LaundryStage {
    RECEIVED,
    WASHING,
    DRYING,
    FOLDING,
    READY,
    CLAIMED;

    private static final List<LaundryStage> ORDERED = List.of(values());

    public LaundryStage next() {
        int nextIndex = ORDERED.indexOf(this) + 1;
        if (nextIndex >= ORDERED.size()) {
            throw new IllegalStateException("A claimed order cannot advance to another stage.");
        }
        return ORDERED.get(nextIndex);
    }

    public boolean canAdvanceTo(LaundryStage candidate) {
        return candidate != null && ORDERED.indexOf(candidate) == ORDERED.indexOf(this) + 1;
    }
}
