package com.cware.ai.evaluation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ValueComparisonTest {
    @Test void distinguishesMissingReferenceFromMissingPrediction() {
        assertThat(ValueComparison.compare("", "30개")).isEqualTo("NO_REFERENCE");
        assertThat(ValueComparison.compare("30개", null)).isEqualTo("MISSING");
    }
    @Test void normalizesOnlyEquivalentRepresentationsAndCompatibleDimensions() {
        assertThat(ValueComparison.compare("30개", "30개")).isEqualTo("EXACT");
        assertThat(ValueComparison.compare("1,000 g", "1kg")).isEqualTo("EQUIVALENT");
        assertThat(ValueComparison.compare("100cm", "1m")).isEqualTo("EQUIVALENT");
        assertThat(ValueComparison.compare("1L", "1000ml")).isEqualTo("EQUIVALENT");
        assertThat(ValueComparison.compare("30개", "30매")).isEqualTo("DIFFERENT");
        assertThat(ValueComparison.compare("1kg", "1000ml")).isEqualTo("DIFFERENT");
        assertThat(ValueComparison.compare("21.5cm", "21.5cmcm")).isEqualTo("DIFFERENT");
        assertThat(ValueComparison.compare("31개", "30개")).isEqualTo("DIFFERENT");
    }
}
