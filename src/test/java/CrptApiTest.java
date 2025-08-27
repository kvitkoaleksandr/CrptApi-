import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

class CrptApiTest {
    private HttpServer server;

    private HttpServer startServer(int status, String body) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(0), 0);
            s.createContext("/lk/documents/create", new FixedResponder(status, body));
            s.start();
            this.server = s;
            return s;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private URI baseUri(HttpServer s) {
        return URI.create("http://localhost:" + s.getAddress().getPort());
    }

    static class FixedResponder implements HttpHandler {
        final int status;
        final String body;

        FixedResponder(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            respond(ex, status, body);
        }
    }

    private static CrptApi.Document minimalDoc() {
        CrptApi.Document d = new CrptApi.Document();
        d.participant_inn = "1234567890";
        d.producer_inn = "1234567890";
        d.production_date = "2025-08-25";
        d.production_type = "OWN_PRODUCTION";
        CrptApi.Document.Product p = new CrptApi.Document.Product();
        p.owner_inn = "1234567890";
        p.producer_inn = "1234567890";
        p.production_date = "2025-08-25";
        p.tnved_code = "00000000";
        p.uit_code = "00000000000000000000000000000000000000000000000000";
        d.products = List.of(p);
        return d;
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void successReturnsDocumentIdTest() {
        HttpServer s = startServer(200, "{\"value\":\"11111111-2222-3333-4444-555555555555\"}");
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 10, baseUri(s), () -> "token");

        CrptApi.DocumentId id = api.createIntroduceGoods(minimalDoc(), "SIG", "milk");

        assertEquals("11111111-2222-3333-4444-555555555555", id.value);
    }
}