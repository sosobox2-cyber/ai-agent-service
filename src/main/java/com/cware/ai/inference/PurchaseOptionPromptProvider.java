package com.cware.ai.inference;

import com.cware.ai.dto.InferenceRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public final class PurchaseOptionPromptProvider {
    private final String full;
    private final String light;
    private final boolean forceFull;
    private final PurchaseOptionPromptSelector selector = new PurchaseOptionPromptSelector();

    public PurchaseOptionPromptProvider(@Value("${app.ai.prompt-mode:AUTO}") String mode) throws IOException {
        if (!mode.equals("AUTO") && !mode.equals("FULL")) throw new IllegalArgumentException("prompt-mode must be AUTO or FULL");
        forceFull = mode.equals("FULL");
        full = read("coupang-purchase-option-system.txt");
        light = read("coupang-purchase-option-system-light.txt");
    }

    public PurchaseOptionPromptMode select(InferenceRequest request) {
        return forceFull ? PurchaseOptionPromptMode.FULL : selector.select(request);
    }

    public String system(PurchaseOptionPromptMode mode) { return mode == PurchaseOptionPromptMode.LIGHT ? light : full; }

    private static String read(String name) throws IOException {
        return new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
