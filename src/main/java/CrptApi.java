import java.util.List;

public final class CrptApi {

    public interface TokenProvider {
        String getToken();
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
}