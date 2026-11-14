package com.spindletrack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LaundryStageTest {

    @Test
    void stagesAdvanceExactlyOneStep() {
        assertEquals(LaundryStage.WASHING, LaundryStage.RECEIVED.next());
        assertTrue(LaundryStage.RECEIVED.canAdvanceTo(LaundryStage.WASHING));
        assertFalse(LaundryStage.RECEIVED.canAdvanceTo(LaundryStage.DRYING));
        assertFalse(LaundryStage.WASHING.canAdvanceTo(LaundryStage.RECEIVED));
    }

    @Test
    void claimedCannotAdvanceFurther() {
        assertThrows(IllegalStateException.class, LaundryStage.CLAIMED::next);
    }
}
