package com.cware.ai;

import com.cware.ai.config.AiClientConfig;
import com.cware.ai.dto.InferenceRequest;
import com.cware.ai.dto.AiCallUsage;
import com.cware.ai.inference.*;
import com.cware.ai.service.PurchaseOptionInferenceService;
import com.cware.ai.util.InputHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PromptCostComparisonTest {
    final ObjectMapper mapper = new ObjectMapper();
    InferenceRequest request(String name) throws Exception {
        return mapper.readValue(Files.readString(Path.of("examples/" + name + "-request.json")), InferenceRequest.class);
    }

    @Test void compareTextTokensOfflineWithoutApiCalls() throws Exception {
        var encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        var provider = new PurchaseOptionPromptProvider("AUTO");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : List.of("ai", "quantity", "capacity", "weight", "set", "tv")) {
            var request = request(name);
            var mode = provider.select(request);
            String user = Files.readString(Path.of("src/main/resources/prompts/coupang-purchase-option-user.txt"))
                    + "\n" + mapper.writeValueAsString(Map.of("product", com.cware.ai.dto.AiProductData.from(request)));
            String fullSchema = mapper.writeValueAsString(PurchaseOptionAiService.schema(request, PurchaseOptionPromptMode.FULL));
            String selectedSchema = mapper.writeValueAsString(PurchaseOptionAiService.schema(request, mode));
            int fullSystem = encoding.countTokensOrdinary(provider.system(PurchaseOptionPromptMode.FULL));
            int selectedSystem = encoding.countTokensOrdinary(provider.system(mode));
            int userTokens = encoding.countTokensOrdinary(user);
            int fullSchemaTokens = encoding.countTokensOrdinary(fullSchema);
            int selectedSchemaTokens = encoding.countTokensOrdinary(selectedSchema);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("fixture", name); row.put("mode", mode);
            row.put("full_system_text_tokens", fullSystem); row.put("selected_system_text_tokens", selectedSystem);
            row.put("user_product_text_tokens", userTokens);
            row.put("full_schema_text_tokens", fullSchemaTokens); row.put("selected_schema_text_tokens", selectedSchemaTokens);
            row.put("baseline_input_text_tokens", fullSystem + userTokens + fullSchemaTokens);
            row.put("optimized_input_text_tokens", selectedSystem + userTokens + selectedSchemaTokens);
            row.put("saved_input_text_tokens", fullSystem + fullSchemaTokens - selectedSystem - selectedSchemaTokens);
            rows.add(row);
            if (mode == PurchaseOptionPromptMode.LIGHT) assertThat(selectedSystem).isLessThan(fullSystem);
            else assertThat(selectedSystem).isEqualTo(fullSystem);
        }
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/prompt-token-comparison.json").toFile(),
                Map.of("measurement", "o200k_base text-only estimate; excludes API framing/schema overhead; cached/output/total tokens and billed cost require actual usage", "rows", rows));
    }

    /** 명시적으로 비교를 활성화한 경우만 실행한다. 키·본문·응답은 보고서에 저장하지 않는다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_RUN_COST_COMPARISON", matches = "true")
    void compareActualUsageAndAccuracyOnSameClothingRequest() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assertThat(apiKey != null && !apiKey.isBlank()).as("OPENAI_API_KEY availability").isTrue();
        String modelName = Optional.ofNullable(System.getenv("OPENAI_MODEL")).orElse("gpt-4.1-mini");
        ChatModel real = new AiClientConfig().openAiChatModel(Fixtures.properties(), apiKey, modelName, 0, 4096);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String setting : List.of("FULL", "AUTO")) {
            var provider = new PurchaseOptionPromptProvider(setting);
            var request = request("ai");
            var mode = provider.select(request);
            List<AiCallUsage> usage = new ArrayList<>();
            ChatModel measured = prompt -> {
                var response = real.call(prompt);
                usage.add(AiUsageLogger.measure(mode, usage.size() + 1, response));
                return response;
            };
            var gateway = new PurchaseOptionAiService(measured, mapper, provider, new AiUsageLogger(true));
            var service = new PurchaseOptionInferenceService(new RequestValidator(), gateway, new ResultValidator(),
                    new InputHashService(mapper), Fixtures.properties());
            var result = service.infer(request);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("setting", setting); row.put("mode", mode); row.put("calls", usage);
            row.put("input_tokens", sum(usage, AiCallUsage::input_tokens));
            row.put("cached_tokens", sum(usage, AiCallUsage::cached_tokens));
            row.put("output_tokens", sum(usage, AiCallUsage::output_tokens));
            row.put("total_tokens", sum(usage, AiCallUsage::total_tokens));
            row.put("estimated_cost_usd", usage.stream().allMatch(u -> u.estimated_cost_usd() != null)
                    ? usage.stream().map(AiCallUsage::estimated_cost_usd).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                    : null);
            row.put("accepted", result.success()); row.put("items", result.items().size());
            row.put("mapping_count", result.optionMappings().size());
            boolean accurate = result.success() && result.items().size() == 4
                    && result.optionMappings().size() == 8;
            if (accurate) for (int i = 0; i < 4; i++) {
                var values = result.items().get(i).purchaseOptions();
                accurate &= "블랙".equals(values.get("색상"))
                        && String.valueOf(90 + i * 5).equals(values.get("패션의류/잡화 사이즈"));
            }
            row.put("expected_values_match", accurate);
            rows.add(row);
            // 실패 결과도 사용량과 함께 남겨 정확도 회귀를 숨기지 않는다.
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/prompt-actual-usage-comparison.json").toFile(), rows);
            assertThat(accurate).as("mode %s preserves all four clothing items", mode).isTrue();
        }
    }

    private static Integer sum(List<AiCallUsage> calls,
            java.util.function.Function<AiCallUsage, Integer> field) {
        return calls.stream().anyMatch(u -> field.apply(u) == null) ? null : calls.stream().mapToInt(u -> field.apply(u)).sum();
    }
}
