package com.cware.ai.evaluation;

import com.cware.ai.exception.InferenceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import static com.cware.ai.evaluation.EvaluationModels.Dataset;

/** 서버에 등록한 로컬 샘플만 읽는다. 같은 파일을 다시 불러와도 검토 이력을 유지한다. */
@Service
@ConditionalOnProperty(name="app.evaluation.enabled", havingValue="true")
public class EvaluationSampleService {
    private final String filename;
    private final String displayName;
    private final EvaluationImporter importer;
    private final EvaluationStore store;
    public EvaluationSampleService(@Value("${app.evaluation.sample-file:}") String filename,
            @Value("${app.evaluation.sample-name:}") String displayName,
            EvaluationImporter importer, EvaluationStore store) {
        this.filename = filename; this.displayName = displayName; this.importer = importer; this.store = store;
    }
    public synchronized Dataset load() throws IOException {
        if (filename.isBlank() && !store.datasets().isEmpty()) return store.datasets().get(0);
        if (filename.isBlank() || !Files.isRegularFile(Path.of(filename)))
            throw new InferenceException("EVALUATION_SAMPLE_UNAVAILABLE", HttpStatus.BAD_REQUEST,
                    "서버에 등록한 샘플 파일이 없습니다. 아래 Excel 파일 선택으로 직접 가져올 수 있습니다.");
        Path file = Path.of(filename);
        if (Files.size(file) > 32L * 1024 * 1024)
            throw new InferenceException("EVALUATION_SAMPLE_TOO_LARGE", HttpStatus.BAD_REQUEST, "샘플 파일은 32MB 이하여야 합니다.");
        byte[] bytes = Files.readAllBytes(file);
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        for (Dataset dataset : store.datasets()) if (dataset.sourceHash().equals(hash)) return dataset;
        return store.save(importer.read(bytes, displayName.isBlank() ? file.getFileName().toString() : displayName));
    }
}
