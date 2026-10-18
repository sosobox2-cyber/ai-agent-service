package com.cware.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cware.ai.exception.InferenceException;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Vercel Blob의 private 객체만 서버 자격 증명으로 읽고 쓴다. 토큰과 원격 오류 본문은 노출하지 않는다. */
public class PrivateBlobClient {
    public record Value(byte[] bytes, String etag) {}
    private final String token, storeId, api, origin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    public PrivateBlobClient(String token, String storeId) {
        this(token,storeId,"https://vercel.com/api/blob", "https://"+storeId.replaceFirst("^store_", "")+".private.blob.vercel-storage.com");
    }
    PrivateBlobClient(String token, String storeId, String api, String origin) {
        if (token.isBlank() || storeId.isBlank()) throw new IllegalArgumentException("Blob 저장소 자격 증명 설정이 필요합니다.");
        this.token=token; this.storeId=storeId.replaceFirst("^store_", ""); this.api=api; this.origin=origin;
    }
    public Value get(String key) throws IOException {
        checkKey(key);
        var response=send(HttpRequest.newBuilder(URI.create(origin+"/"+key+"?cache=0"))
                .header("Authorization","Bearer "+token).GET().timeout(Duration.ofSeconds(60)).build());
        if (response.statusCode()==404) return null;
        if (response.statusCode()!=200) throw unavailable(response.statusCode());
        return new Value(response.body(),response.headers().firstValue("etag").orElseThrow(() -> new IOException("Blob ETag가 없습니다.")));
    }
    /** expected=null이면 새 객체 생성만 허용하며, 그 외에는 ETag가 일치할 때만 변경한다. */
    public boolean put(String key, byte[] bytes, String expected) throws IOException {
        checkKey(key);
        var builder=HttpRequest.newBuilder(URI.create(api+"/?pathname="+URLEncoder.encode(key,StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(90)).header("Authorization","Bearer "+token)
                .header("x-api-version","13").header("x-vercel-blob-store-id",storeId)
                .header("x-vercel-blob-access","private").header("x-add-random-suffix","0")
                .header("x-content-type","application/octet-stream").header("x-cache-control-max-age","60")
                .header("x-allow-overwrite",expected==null?"0":"1");
        if(expected!=null) builder.header("x-if-match",expected);
        var response=send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build());
        if(response.statusCode()>=200 && response.statusCode()<300) return true;
        String code="";
        try { code=mapper.readTree(response.body()).path("error").path("code").asText(); } catch(Exception ignored) { }
        if(response.statusCode()==412 || code.equals("precondition_failed") || code.equals("blob_already_exists")) return false;
        // allowOverwrite=false의 중복 생성은 버전별로 bad_request로 반환될 수 있다.
        if(expected==null && response.statusCode()==400 && get(key)!=null) return false;
        throw unavailable(response.statusCode());
    }
    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try { return http.send(request,HttpResponse.BodyHandlers.ofByteArray()); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("저장소 요청이 중단되었습니다."); }
        catch(IOException e) { throw new IOException("영구 저장소에 연결할 수 없습니다."); }
    }
    private static void checkKey(String key) {
        if(!key.matches("[a-zA-Z0-9_./-]+") || key.contains("..") || key.startsWith("/")) throw new IllegalArgumentException("잘못된 저장 경로입니다.");
    }
    private static InferenceException unavailable(int status) {
        return new InferenceException("EVALUATION_STORAGE_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE,
                "영구 저장소 요청에 실패했습니다 (HTTP "+status+"). 잠시 후 다시 시도하세요.");
    }
}
