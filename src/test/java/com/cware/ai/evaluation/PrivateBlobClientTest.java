package com.cware.ai.evaluation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PrivateBlobClientTest {
    @Test void privateReadsBypassCacheAndWritesUseConditionalEtags() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        Map<String,PrivateBlobClient.Value> files=new HashMap<>();List<String> failures=new ArrayList<>();
        server.createContext("/",e -> {
            if(!"Bearer test-secret".equals(e.getRequestHeaders().getFirst("Authorization"))) failures.add("auth");
            String key=e.getRequestMethod().equals("PUT")?java.net.URLDecoder.decode(e.getRequestURI().getRawQuery().substring(9),StandardCharsets.UTF_8):e.getRequestURI().getPath().substring(1);
            if(e.getRequestMethod().equals("GET")) {
                if(!"cache=0".equals(e.getRequestURI().getRawQuery())) failures.add("cache");
                var value=files.get(key);
                if(value==null) {e.sendResponseHeaders(404,-1);e.close();return;}
                e.getResponseHeaders().set("ETag",value.etag());e.sendResponseHeaders(200,value.bytes().length);e.getResponseBody().write(value.bytes());
            } else {
                if(!"private".equals(e.getRequestHeaders().getFirst("x-vercel-blob-access"))) failures.add("access");
                if(!"13".equals(e.getRequestHeaders().getFirst("x-api-version"))) failures.add("version");
                var old=files.get(key);String expected=e.getRequestHeaders().getFirst("x-if-match");
                if(!Objects.equals(expected,old==null?null:old.etag())) {
                    byte[] body="{\"error\":{\"code\":\"precondition_failed\"}}".getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(412,body.length);e.getResponseBody().write(body);
                } else {files.put(key,new PrivateBlobClient.Value(e.getRequestBody().readAllBytes(),UUID.randomUUID().toString()));e.sendResponseHeaders(200,2);e.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));}
            }
            e.close();
        });server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            var client=new PrivateBlobClient("test-secret","store_example",url,url);
            assertThat(client.get("test/state.json")).isNull();
            assertThat(client.put("test/state.json",new byte[]{1},null)).isTrue();
            var first=client.get("test/state.json");
            assertThat(client.put("test/state.json",new byte[]{2},null)).isFalse();
            assertThat(client.put("test/state.json",new byte[]{2},first.etag())).isTrue();
            assertThat(client.put("test/state.json",new byte[]{3},first.etag())).isFalse();
            assertThat(client.get("test/state.json").bytes()).containsExactly(2);
            assertThat(failures).isEmpty();
        } finally {server.stop(0);}
    }
}
