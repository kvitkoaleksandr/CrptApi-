import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class CrptApi {
    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;
    private final TokenProvider tokenProvider;
    private final RateLimiter limiter;

    public CrptApi(TimeUnit unit, int requestLimit, TokenProvider tokenProvider) {
        this(unit, requestLimit, URI.create("https://ismp.crpt.ru/api/v3"), tokenProvider);
    }

    public CrptApi(TimeUnit unit, int requestLimit, URI baseUri, TokenProvider tokenProvider) {
        this(unit, requestLimit, baseUri,
                tokenProvider,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                defaultMapper());
    }

    CrptApi(TimeUnit unit,
            int requestLimit,
            URI baseUri,
            TokenProvider tokenProvider,
            HttpClient http,
            ObjectMapper mapper) {
        if (unit == null) throw new IllegalArgumentException("timeUnit is null");
        if (requestLimit <= 0) throw new IllegalArgumentException("requestLimit must be > 0");
        this.baseUri = notNull(baseUri, "baseUri");
        this.tokenProvider = notNull(tokenProvider, "tokenProvider");
        this.http = notNull(http, "http");
        this.json = notNull(mapper, "mapper");
        this.limiter = new SlidingWindowRateLimiter(unit, requestLimit);
    }

    private static <T> T notNull(T value, String name) {
        if (value == null) throw new NullPointerException(name + " is null");
        return value;
    }

    public interface TokenProvider {
        String getToken();
    }

    interface RateLimiter {
        void acquire() throws InterruptedException;
    }

    public static final class DocumentId {
        public final String value;

        public DocumentId(String value) {
            if (value == null) throw new NullPointerException("value is null");
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public static class Document {
        public String participant_inn;
        public String producer_inn;
        public String production_date;
        public String production_type;
        public List<Product> products;

        public static class Product {
            public String certificate_document;
            public String certificate_document_date;
            public String certificate_document_number;
            public String owner_inn;
            public String producer_inn;
            public String production_date;
            public String tnved_code;
            public String uit_code;
            public String uitu_code;
        }
    }

    public DocumentId createIntroduceGoods(Object document, String signature, String productGroup) {
        if (document == null) {
            throw new IllegalArgumentException("document is null");
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("signature is blank");
        }
        if (productGroup == null || productGroup.isBlank()) {
            throw new IllegalArgumentException("productGroup is blank");
        }

        try {
            limiter.acquire();
            String bodyJson = writeJson(new CreateDocumentRequest(
                    "MANUAL",
                    base64EncodeJson(document),
                    "LP_INTRODUCE_GOODS",
                    signature
            ));

            URI uri = baseUri.resolve("/lk/documents/create?pg=" + encode(productGroup));
            String token = safeToken(tokenProvider.getToken());

            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> resp = sendOnce(req);
            int sc = resp.statusCode();

            if (sc >= 200 && sc < 300) {
                CreateDocumentResponse ok = readJson(resp.body(), CreateDocumentResponse.class);
                String id = (ok != null && ok.value != null && !ok.value.isBlank()) ? ok.value : null;
                if (id == null) throw new TransportException("Empty id in 2xx response");
                return new DocumentId(id);
            }

            if (sc == 401 || sc == 403) {
                throw new AuthException(extractError(resp));
            }
            if (sc >= 400 && sc < 500) {
                throw new ApiException(extractError(resp));
            }

            String err = extractError(resp);
            log.error("server error {} pg={} err={}", sc, productGroup, err);
            throw new TransportException("Server error " + sc + ": " + err);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("rate limiter interrupted pg={}", productGroup, e);
            throw new RateLimitInterruptedException("Interrupted while waiting in rate limiter");
        } catch (JsonProcessingException e) {
            log.error("json serialization error pg={}", productGroup, e);
            throw new TransportException("JSON serialization error", e);
        } catch (IOException e) {
            log.error("i/o error pg={}", productGroup, e);
            throw new TransportException("I/O error", e);
        }
    }

    static final class CreateDocumentRequest {
        public final String document_format;
        public final String product_document;
        public final String type;
        public final String signature;

        CreateDocumentRequest(String document_format, String product_document, String type, String signature) {
            this.document_format = document_format;
            this.product_document = product_document;
            this.type = type;
            this.signature = signature;
        }
    }

    static final class CreateDocumentResponse {
        public String value;
    }

    static final class ErrorResponse {
        public String error_message;
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper om = new ObjectMapper();
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return om;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String base64EncodeJson(Object pojo) throws JsonProcessingException {
        String jsonStr = writeJson(pojo);
        byte[] bytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String writeJson(Object pojo) throws JsonProcessingException {
        return json.writeValueAsString(pojo);
    }

    private <T> T readJson(String body, Class<T> type) throws JsonProcessingException {
        return json.readValue(body, type);
    }

    private HttpResponse<String> sendOnce(HttpRequest req) throws IOException, InterruptedException {
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String extractError(HttpResponse<String> resp) {
        String body = resp.body();
        if (body == null || body.isBlank()) return "HTTP " + resp.statusCode();
        try {
            ErrorResponse er = json.readValue(body, ErrorResponse.class);
            if (er != null && er.error_message != null && !er.error_message.isBlank()) return er.error_message;
        } catch (Exception ignored) {
        }
        return "HTTP " + resp.statusCode() + " body: " + trim(body, 512);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String safeToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is blank");
        return token;
    }

    public static class ApiException extends RuntimeException {
        public ApiException(String m) {
            super(m);
        }
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String m) {
            super(m);
        }
    }

    public static class TransportException extends RuntimeException {
        public TransportException(String m, Throwable c) {
            super(m, c);
        }

        public TransportException(String m) {
            super(m);
        }
    }

    public static class RateLimitInterruptedException extends RuntimeException {
        public RateLimitInterruptedException(String m) {
            super(m);
        }
    }

    static final class SlidingWindowRateLimiter implements RateLimiter {
        private final long windowNanos;
        private final int limit;
        private final Deque<Long> stamps = new ArrayDeque<>();
        private final Object monitor = new Object();

        SlidingWindowRateLimiter(TimeUnit unit, int limit) {
            this.windowNanos = unit.toNanos(1L);
            this.limit = limit;
        }

        @Override
        public void acquire() throws InterruptedException {
            long now = nanoNow();
            synchronized (monitor) {
                cleanup(now);
                while (stamps.size() >= limit) {
                    long oldest = stamps.peekFirst();
                    long waitNanos = (oldest + windowNanos) - now;
                    if (waitNanos <= 0) {
                        cleanup(nanoNow());
                        now = nanoNow();
                        continue;
                    }
                    long waitMillis = Math.max(1L, waitNanos / 1_000_000L);
                    monitor.wait(waitMillis);
                    cleanup(nanoNow());
                    now = nanoNow();
                }
                stamps.addLast(nanoNow());
                monitor.notifyAll();
            }
        }

        private long nanoNow() {
            return System.nanoTime();
        }

        private void cleanup(long now) {
            while (!stamps.isEmpty()) {
                long oldest = stamps.peekFirst();
                if (now - oldest >= windowNanos) stamps.removeFirst();
                else break;
            }
        }
    }
}