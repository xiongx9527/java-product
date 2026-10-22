package com.xiongx9527.askdata.system.sso;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 可Mock的IdP HTTP 客户端（A7）：真实实现用 JDK HttpClient；测试用 stub 验证协议流程。
 */
public interface IdpHttpClient {

    class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    Response get(String url, Map<String, String> headers);

    Response postForm(String url, Map<String, String> form, Map<String, String> headers);

    static IdpHttpClient defaultClient() {
        return new DefaultIdpHttpClient();
    }

    class DefaultIdpHttpClient implements IdpHttpClient {
        private final HttpClient hc = HttpClient.newHttpClient();

        @Override
        public Response get(String url, Map<String, String> headers) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
                if (headers != null) {
                    headers.forEach(b::header);
                }
                HttpResponse<String> r = hc.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body());
            } catch (Exception e) {
                return new Response(0, "");
            }
        }

        @Override
        public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
                if (headers != null) {
                    headers.forEach(b::header);
                }
                String body = form.entrySet().stream()
                        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
                b.header("Content-Type", "application/x-www-form-urlencoded");
                HttpResponse<String> r = hc.send(
                        b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body());
            } catch (Exception e) {
                return new Response(0, "");
            }
        }
    }
}
