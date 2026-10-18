package com.cware.ai.evaluation;

import com.cware.ai.dto.*;
import com.cware.ai.exception.InferenceException;
import jakarta.validation.Validator;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static com.cware.ai.evaluation.EvaluationModels.*;

@Component
public class EvaluationImporter {
    private final Validator validator;
    public EvaluationImporter(Validator validator) { this.validator = validator; }
    private record Source(int row, String goodsId, String name, String category, String notice, String composition,
                          String optionId, String colorSize, String detail, String target, String unit, String units, String baseline) {}
    public Imported read(byte[] bytes, String filename) throws IOException {
        Map<String, List<Source>> products = new LinkedHashMap<>();
        int rowCount = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("SQL Results");
            if (sheet == null) sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.KOREA);
            formatter.setUseCachedValuesForFormulaCells(true);
            Map<String, Integer> columns = new HashMap<>();
            org.apache.poi.ss.usermodel.Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw invalid("첫 행에 열 이름이 필요합니다.");
            for (Cell cell : header) columns.put(formatter.formatCellValue(cell).strip(), cell.getColumnIndex());
            List<String> required = List.of("상품코드", "상품명", "스토아CAT", "정보고시", "기술서", "단품코드",
                    "색상_사이즈명", "단품상세", "구매옵션명", "필수옵션단위", "옵션단위", "구매옵션 정답");
            for (String column : required) if (!columns.containsKey(column)) throw invalid("필수 열이 없습니다: " + column);
            if (sheet.getLastRowNum() > 100000) throw invalid("한 파일은 100,000행까지 지원합니다.");
            for (int index = header.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(index);
                if (row == null) continue;
                List<String> values = required.stream().map(name -> formatter.formatCellValue(row.getCell(columns.get(name))).strip()).toList();
                if (values.stream().allMatch(String::isBlank)) continue;
                if (values.get(0).isBlank()) throw invalid((index + 1) + "행의 상품코드가 비어 있습니다.");
                Source source = new Source(index + 1, values.get(0), values.get(1), values.get(2), values.get(3),
                        values.get(4), values.get(5), values.get(6), values.get(7), values.get(8), values.get(9), values.get(10), values.get(11));
                products.computeIfAbsent(source.goodsId(), key -> new ArrayList<>()).add(source);
                rowCount++;
            }
        } catch (InferenceException e) { throw e; }
        catch (RuntimeException | IOException e) { throw invalid("읽을 수 없는 Excel 파일입니다. .xls 또는 .xlsx 파일을 확인하세요."); }
        if (products.isEmpty()) throw invalid("가져올 데이터가 없습니다.");
        List<Case> cases = new ArrayList<>();
        int totalOptions = 0, issueRows = 0;
        for (List<Source> rows : products.values()) {
            Source first = rows.get(0);
            Set<String> issues = new LinkedHashSet<>();
            Map<String, SourceOption> options = new LinkedHashMap<>();
            Map<String, PurchaseOptionUnit> units = new LinkedHashMap<>();
            Set<String> targets = new LinkedHashSet<>();
            Map<List<String>, Set<String>> answers = new HashMap<>();
            for (Source row : rows) {
                answers.computeIfAbsent(List.of(row.optionId(), row.target()), key -> new LinkedHashSet<>()).add(row.baseline());
                if (!List.of(first.name(), first.category(), first.notice(), first.composition()).equals(
                        List.of(row.name(), row.category(), row.notice(), row.composition()))) issues.add("상품 공통 입력값이 행마다 다릅니다.");
                String detail = row.detail().isBlank() ? row.colorSize().replace('>', '/') : row.detail();
                if (detail.isBlank() || detail.equals("없음/없음")) detail = "단일상품";
                SourceOption option = new SourceOption(row.optionId(), detail);
                if (options.containsKey(option.optionId()) && !options.get(option.optionId()).equals(option))
                    issues.add("동일 단품코드의 단품상세가 서로 다릅니다: " + option.optionId());
                options.putIfAbsent(option.optionId(), option);
                targets.add(row.target());
                if (!row.unit().isBlank() && !row.unit().equals("없음")) {
                    List<String> choices = Arrays.stream(row.units().split(",")).map(String::strip).filter(v -> !v.isBlank()).distinct().toList();
                    // 선택지가 없는 단위는 명시된 기본단위 하나로 설정한다.
                    if (choices.isEmpty()) choices = List.of(row.unit());
                    PurchaseOptionUnit unit = new PurchaseOptionUnit(row.target(), row.unit(), choices);
                    if (units.containsKey(row.target()) && !units.get(row.target()).equals(unit))
                        issues.add("같은 구매옵션의 단위 설정이 행마다 다릅니다: " + row.target());
                    units.putIfAbsent(row.target(), unit);
                }
            }
            List<Reference> references = new ArrayList<>();
            for (Source row : rows) {
                List<String> warnings = new ArrayList<>();
                if (row.baseline().isBlank()) warnings.add("기준값 없음");
                if (answers.get(List.of(row.optionId(), row.target())).size() > 1) warnings.add("동일 단품·옵션의 기준값 충돌");
                if (row.baseline().matches("(?i).*(cmcm|mmmm|mlml|kgkg).*")) warnings.add("단위 중복 표기 확인 필요");
                if (!warnings.isEmpty()) issueRows++;
                references.add(new Reference(row.row(), row.optionId(), row.target(), row.baseline(), row.colorSize(), row.detail(), warnings));
            }
            InferenceRequest request = new InferenceRequest(first.goodsId(), first.name(), first.category(), List.copyOf(targets),
                    List.copyOf(options.values()), first.notice(), List.copyOf(units.values()), first.composition());
            validator.validate(request).stream().map(v -> v.getPropertyPath() + ": 입력 조건 확인 필요")
                    .sorted().forEach(issues::add);
            cases.add(new Case(String.format(Locale.ROOT, "c%05d", cases.size() + 1), request, references, List.copyOf(issues)));
            totalOptions += options.size();
        }
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        String name = filename == null ? "dataset.xls" : filename.replace('\\','/').replaceAll(".*/", "");
        return new Imported(new Dataset(UUID.randomUUID().toString(), name, hash, Instant.now().toString(),
                rowCount, cases.size(), totalOptions, issueRows), List.copyOf(cases));
    }
    private static InferenceException invalid(String message) {
        return new InferenceException("EVALUATION_INVALID_DATA", HttpStatus.BAD_REQUEST, message);
    }
}
