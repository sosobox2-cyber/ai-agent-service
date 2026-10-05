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
                r.coupangCategoryName(),List.of("사이즈","핏","색상"),reverse,r.productNoticeText());
        assertThat(hashes.hash(r)).hasSize(64).isEqualTo(hashes.hash(reordered));
        reverse.set(0,new SourceOption("3","배기핏 남색 201"));
        assertThat(hashes.hash(Fixtures.withOptions(r,reverse))).isNotEqualTo(hashes.hash(r));
    }
    @Test void categoryAndBrandAffectHash() {
        var r=Fixtures.request();
        var changed=new InferenceRequest(r.goodsId(),r.goodsName(),"OTHER",r.categoryName(),r.coupangCategoryId(),
                "다른 카테고리",r.allowedPurchaseOptions(),r.options(),r.productNoticeText());
        assertThat(hashes.hash(changed)).isNotEqualTo(hashes.hash(r));
    }
    @Test void noticeChangesAffectHash() {
        var r = Fixtures.request();
        var notice = Fixtures.withNotice(r, "제품 소재: 면 100%\n색상: 남색");
        assertThat(hashes.hash(notice)).isNotEqualTo(hashes.hash(r))
                .isNotEqualTo(hashes.hash(Fixtures.withNotice(r, "제품 소재: 폴리에스터 100%\n색상: 남색")));
    }
}
