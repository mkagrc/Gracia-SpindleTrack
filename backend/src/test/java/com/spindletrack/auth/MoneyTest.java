package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void convertsPesosToAndFromIntegerCentavosWithoutFloatingPointDrift() {
        assertEquals(12_345L, Money.toCentavos(new BigDecimal("123.45")));
        assertEquals(new BigDecimal("123.45"), Money.fromCentavos(12_345L));
        assertEquals(new BigDecimal("0.10"), Money.fromCentavos(Money.toCentavos(new BigDecimal("0.10"))));
    }

    @Test
    void readsCanonicalCentavosAndLegacyDecimalFields() {
        assertEquals(new BigDecimal("88.20"), Money.fromFirestore(8_820L, new BigDecimal("999.99")));
        assertEquals(new BigDecimal("19.99"), Money.fromFirestore(null, 19.99));
        assertEquals(new BigDecimal("7.50"), Money.fromPriceLog("CENTAVOS", 750L));
        assertEquals(new BigDecimal("7.50"), Money.fromPriceLog(null, new BigDecimal("7.50")));
    }

    @Test
    void rejectsValuesThatCannotBeExactlyRepresentedAsCentavos() {
        assertThrows(ArithmeticException.class, () -> Money.toCentavos(new BigDecimal("1.001")));
    }
}
