import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
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
    private final Clock clock;

    public CrptApi(TimeUnit unit, int requestLimit, TokenProvider tokenProvider) {
        this(unit, requestLimit, URI.create("https://ismp.crpt.ru/api/v3"), tokenProvider);
    }

    public CrptApi(TimeUnit unit, int requestLimit, TokenProvider tokenProvider) {
        this(
                unit,
                requestLimit,
                baseUri,
                tokenProvider,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                defaultMapper(),
                Clock.systemUTC()
        );
    }

    CrptApi(TimeUnit unit,
            int requestLimit,
            URI baseUri,
            TokenProvider tokenProvider,
            HttpClient http,
            ObjectMapper mapper,
            Clock clock) {
        if (unit == null) throw new IllegalArgumentException("timeUnit is null");
        if (requestLimit <= 0) throw new IllegalArgumentException("requestLimit must be > 0");
        this.baseUri = notNull(baseUri, "baseUri");
        this.tokenProvider = notNull(tokenProvider, "tokenProvider");
        this.http = notNull(http, "http");
        this.json = notNull(mapper, "mapper");
        this.clock = notNull(clock, "clock");
        this.limiter = new SlidingWindowRateLimiter(unit, requestLimit, clock);
    }

    private static <T> T notNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name + "is null");
        }
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
            if (value == null) {
                throw new NullPointerException("value is null");
            }
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



    private static ObjectMapper defaultMapper() {
        ObjectMapper om = new ObjectMapper();
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return om;
    }

    static final class SlidingWindowRateLimiter implements RateLimiter {
        private final Long windowNanos;
        private final int limit;
        private final Deque<Long> stamps = new ArrayDeque<>();
        private final Object monitor = new Object();
        private final Clock clock;

        SlidingWindowRateLimiter(TimeUnit unit, int limit, Clock clock) {
            this.windowNanos = unit.toNanos(1L);
            this.limit = limit;
            this.clock = clock;
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
                        continue;
                    }
                    long waitMillis = Math.max(1L, waitNanos / 1_000_000L);
                    monitor.wait(waitMillis);
                    cleanup(nanoNow());
                    now = nanoNow();
                }
            }
        }

        private long nanoNow() {
            return System.nanoTime();
        }

        private void cleanup(long now) {
            while (!stamps.isEmpty()) {
                long lodest = stamps.peekFirst();
                if (now - lodest >= windowNanos) {
                    stamps.removeFirst();
                } else {
                    break;
                }
            }
        }
    }
}