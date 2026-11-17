package io.github.yashringe.drift.diff;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class QuantityTest {

    @ParameterizedTest
    @CsvSource({
            "0.1, 100m",
            "0.5, 500m",
            "1, 1000m",
            "1Gi, 1024Mi",
            "1Mi, 1024Ki",
            "1k, 1000",
            "1e3, 1k",
            "2G, 2000M"})
    void sameAmountWrittenDifferently(String a, String b) {
        assertThat(Quantity.sameAmount(a, b)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "1G, 1Gi",      // decimal vs binary
            "100m, 200m",
            "web, web",     // not a quantity at all
            "1Zi, 1Zi"})    // unknown suffix
    void differentOrNotQuantities(String a, String b) {
        assertThat(Quantity.sameAmount(a, b)).isFalse();
    }
}
