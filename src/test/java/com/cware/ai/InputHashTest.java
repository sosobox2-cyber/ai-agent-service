package com.cware.ai;

import com.cware.ai.dto.*;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InputHashTest {
    final InputHashService hashes=new InputHashService(new ObjectMapper());
    @Test void ignoresOrderingButPreservesValues() {
        var r=Fixtures.request();
        var reverse=new ArrayList<>(r.options()); Collections.reverse(reverse);
        var reordered=new InferenceRequest(r.goodsId(),r.goodsName(),r.brand(),r.categoryName(),r.coupangCategoryId(),
                r.coupangCategoryName(),List.of("사이즈","핏","색상"),r.requiredPurchaseOptions(),reverse);
        assertThat(hashes.hash(r)).hasSize(64).isEqualTo(hashes.hash(reordered));
        reverse.set(0,new SourceOption("3","배기핏 남색 201"));
        assertThat(hashes.hash(Fixtures.withOptions(r,reverse))).isNotEqualTo(hashes.hash(r));
    }
    @Test void categoryAndBrandAffectHash() {
        var r=Fixtures.request();
        var changed=new InferenceRequest(r.goodsId(),r.goodsName(),"OTHER",r.categoryName(),r.coupangCategoryId(),
                "다른 카테고리",r.allowedPurchaseOptions(),null,r.options());
        assertThat(hashes.hash(changed)).isNotEqualTo(hashes.hash(r));
    }
}
