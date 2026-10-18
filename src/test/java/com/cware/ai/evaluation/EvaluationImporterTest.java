package com.cware.ai.evaluation;

import jakarta.validation.Validation;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;

class EvaluationImporterTest {
    static byte[] workbook(boolean conflict) throws Exception {
        try (var book = new HSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("SQL Results");
            String[] headers = {"상품코드","상품명","스토아CAT","정보고시","기술서","단품코드","색상_사이즈명","단품상세","구매옵션명","필수옵션단위","옵션단위","구매옵션 정답"};
            var header = sheet.createRow(0);
            for (int i=0;i<headers.length;i++) header.createCell(i).setCellValue(headers[i]);
            String[][] rows = {{"20019170","구운계란 30개","식품>계란","수량: 30개","계란 30개","001","없음>없음","단일상품","수량","개","개,박스,세트","30개"},
                    {"20019170","구운계란 30개","식품>계란","수량: 30개","계란 30개","001","없음>없음","단일상품",conflict?"수량":"색상",conflict?"개":"없음",conflict?"개,박스,세트":"",conflict?"31개":""}};
            for (int r=0;r<rows.length;r++) {
                var row = sheet.createRow(r+1);
                for (int c=0;c<rows[r].length;c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            book.write(bytes); return bytes.toByteArray();
        }
    }
    @Test void groupsProductAndOptionWithoutLosingSourceRowsOrBlankReferences() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var imported = new EvaluationImporter(factory.getValidator()).read(workbook(false),"sample.xls");
            assertThat(imported.dataset().rows()).isEqualTo(2);
            assertThat(imported.dataset().products()).isEqualTo(1);
            assertThat(imported.dataset().options()).isEqualTo(1);
            var test = imported.cases().get(0);
            assertThat(test.request().options().get(0).optionId()).isEqualTo("001");
            assertThat(test.request().allowedPurchaseOptions()).containsExactly("수량","색상");
            assertThat(test.references().get(1).row()).isEqualTo(3);
            assertThat(test.references().get(1).issues()).contains("기준값 없음");
            assertThat(test.issues()).isEmpty();
        }
    }
    @Test void preservesConflictingHumanValuesInsteadOfChoosingOne() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var imported = new EvaluationImporter(factory.getValidator()).read(workbook(true),"sample.xls");
            assertThat(imported.dataset().issueRows()).isEqualTo(2);
            assertThat(imported.cases().get(0).references()).extracting(EvaluationModels.Reference::baseline).containsExactly("30개","31개");
            assertThat(imported.cases().get(0).references()).allSatisfy(r -> assertThat(r.issues()).contains("동일 단품·옵션의 기준값 충돌"));
        }
    }
}
