package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.inference.RequestValidator;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RequestValidationTest {
    private final RequestValidator validator = new RequestValidator();

    @Test void allowedAndRequiredNamesMayBeIdenticalLists() {
        validator.validate(Fixtures.request());
        assertThat(Fixtures.request().effectiveRequiredOptions()).containsExactly("핏", "색상", "사이즈");
    }

    @Test void rejectsDuplicateIdsAndBlankNames() {
        var base = Fixtures.request();
        assertThatThrownBy(() -> validator.validate(Fixtures.withOptions(base,
                List.of(base.options().get(0), base.options().get(0))))).hasMessageContaining("입력");
        assertThatThrownBy(() -> validator.validate(Fixtures.withOptions(base,
                List.of(new SourceOption("1", " "))))).hasMessageContaining("입력");
    }

    @Test void requiredNamesMustBeAllowed() {
        var base = Fixtures.request();
        var request = new InferenceRequest(base.goodsId(), base.goodsName(), base.brand(), base.categoryName(),
                base.coupangCategoryId(), base.coupangCategoryName(), base.allowedPurchaseOptions(),
                List.of("색상", "없는 이름"), base.options());
        assertThatThrownBy(() -> validator.validate(request)).hasMessageContaining("입력");
    }

    @Test void beanValidationRejectsNullRowsAndMissingNames() {
        var base = Fixtures.request();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var beanValidator = factory.getValidator();
            assertThat(beanValidator.validate(Fixtures.withOptions(base, java.util.Arrays.asList((SourceOption) null)))).isNotEmpty();
            assertThat(beanValidator.validate(Fixtures.withOptions(base, List.of(new SourceOption("1", null))))).isNotEmpty();
        }
    }
}
