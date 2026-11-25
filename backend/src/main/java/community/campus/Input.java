package community.campus;

import java.net.URI;

final class Input {
    private Input() {}

    static String search(String query) {
        return "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static String media(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // Return the same validation error for malformed and unsupported URLs.
        }
        throw ApiErrors.invalid("Enter a valid HTTPS media URL");
    }

    static int offset(int page) { return Math.clamp(page, 0, 1000) * 20; }
}
