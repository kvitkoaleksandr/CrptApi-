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
}