package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import java.util.*;

/** 원본 기준값, 실행 결과, 사람의 검토를 독립적으로 보관한다. */
public final class EvaluationModels {
    private EvaluationModels() {}
    public record Dataset(String id, String filename, String sourceHash, String importedAt,
                          int rows, int products, int options, int issueRows) {}
    public record Reference(int row, String optionId, String target, String baseline,
                            String colorSizeName, String sourceDetail, List<String> issues) {}
    public record Case(String id, InferenceRequest request, List<Reference> references, List<String> issues) {}
    public record Imported(Dataset dataset, List<Case> cases) {}
    public record Comparison(int row, String optionId, String target, String baseline,
                             String confirmedValue, String actual, String status, String evidence) {}
    public record Run(String id, String caseId, String startedAt, String finishedAt, String mode,
                      String error, InferenceResponse response, List<Comparison> comparisons) {}
    public record Review(int row, String status, String note, String confirmedValue,
                         String runId, String reviewedValue, String updatedAt) {}
    public record Detail(Case testCase, Run latest, List<Run> history, Map<Integer, Review> reviews) {}
    public record Row(String id, String goodsId, String goodsName, String categoryName, int options,
                      int references, String state, String mode, int matched, int differences,
                      int confirmed, int regressions, int issues, String lastRunAt) {}
    public record Page(List<Row> rows, int total, int page, int pageSize, Map<String, Long> counts) {}
    public record RunRequest(String mode, boolean allowPaidCall) {}
    public record ReviewRequest(String status, String note, String confirmedValue, String runId) {}
}
