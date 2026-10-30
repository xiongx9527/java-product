package com.xiongx9527.askdata.system.sso;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 可Mock的IdP HTTP 客户端（A7）：真实实现用 JDK {@link HttpURLConnection}（Java 8 可用）；
 * 测试用 stub 验证协议流程。
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

        @Override
        public Response get(String url, Map<String, String> headers) {
            return execute(url, "GET", null, headers);
        }

        @Override
        public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(e.getKey()).append('=').append(encode(e.getValue()));
            }
            return execute(url, "POST", body.toString(), headers);
        }

        /** URL 编码（UTF-8）；Java 8 的 String 重载需处理受检异常。 */
        private static String encode(String value) {
            try {
                return URLEncoder.encode(value == null ? "" : value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return value == null ? "" : value;
            }
        }

        /** 执行请求；连接或读取失败返回 status=0，由调用方按失败处理。 */
        private static Response execute(String url, String method, String body, Map<String, String> headers) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                if (headers != null) {
                    for (Map.Entry<String, String> h : headers.entrySet()) {
                        conn.setRequestProperty(h.getKey(), h.getValue());
                    }
                }
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(payload.length);
                    OutputStream out = conn.getOutputStream();
                    try {
                        out.write(payload);
                    } finally {
                        out.close();
                    }
                }
                int status = conn.getResponseCode();
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                return new Response(status, readAll(in));
            } catch (Exception e) {
                return new Response(0, "");
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private static String readAll(InputStream in) throws Exception {
            if (in == null) {
                return "";
            }
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        }
    }
}
