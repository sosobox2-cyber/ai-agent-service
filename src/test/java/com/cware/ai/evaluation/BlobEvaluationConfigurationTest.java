package com.cware.ai.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.evaluation.enabled=true","app.evaluation.storage=blob","app.evaluation.blob-store-id=test-store",
        "BLOB_READ_WRITE_TOKEN=test-token","app.security.enabled=false","app.ai.usage-jsonl-enabled=false"})
class BlobEvaluationConfigurationTest {
    @Autowired ApplicationContext context;
    @Test void onlyCloudStoreIsSelectedForProductionConfiguration() {
        assertThat(context.getBeansOfType(EvaluationStore.class)).hasSize(1);
        assertThat(context.getBean(EvaluationStore.class)).isInstanceOf(BlobEvaluationStore.class);
    }
}
