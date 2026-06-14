package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Open Graph / title metadata from a URL asynchronously.
 * Results are cached in memory so each URL is only fetched once per session.
 * All callbacks are delivered on the main thread.
 */
public class LinkPreviewFetcher {

    public static class Preview {
        public final String url;
        public final String title;
        public final String domain;
        public final String imageUrl;

        public Preview(String url, String title, String domain, String imageUrl) {
            this.url      = url;
            this.title    = title;
            this.domain   = domain;
            this.imageUrl = imageUrl;
        }
    }

    public interface Callback {
        void onResult(Preview preview);
    }

    private static final Map<String, Preview>  cache       = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService        executor    = Executors.newFixedThreadPool(3);
    private static final Handler                mainHandler = new Handler(Looper.getMainLooper());

    private static final Pattern OG_TITLE   = Pattern.compile(
        "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']{1,200})[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE2  = Pattern.compile(
        "<meta[^>]+content=[\"']([^\"']{1,200})[\"'][^>]+property=[\"']og:title[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TITLE = Pattern.compile(
        "<title[^>]*>([^<]{1,200})</title>",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE   = Pattern.compile(
        "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']{4,500})[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE2  = Pattern.compile(
        "<meta[^>]+content=[\"']([^\"']{4,500})[\"'][^>]+property=[\"']og:image[\"']",
        Pattern.CASE_INSENSITIVE);

    /**
     * Fetch preview for a URL. Calls back immediately with cached value if available,
     * otherwise fetches in background. Callback always runs on the main thread.
     */
    public static void fetch(String url, Callback callback) {
        if (cache.containsKey(url)) {
            callback.onResult(cache.get(url));
            return;
        }
        executor.execute(() -> {
            Preview preview = fetchSync(url);
            // Store even null so we don't re-fetch failed URLs this session
            cache.put(url, preview);
            final Preview result = preview;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    private static Preview fetchSync(String urlStr) {
        try {
            URL url  = new URL(urlStr);
            String domain = url.getHost();
            if (domain.startsWith("www.")) domain = domain.substring(4);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (compatible; DuoShield/1.0)");

            int status = conn.getResponseCode();
            if (status < 200 || status >= 400) {
                conn.disconnect();
                return new Preview(urlStr, null, domain, null);
            }

            String ct = conn.getContentType();
            if (ct == null || !ct.toLowerCase().contains("text/html")) {
                conn.disconnect();
                return new Preview(urlStr, null, domain, null);
            }

            // Read only the first ~25 KB — enough to get <head> metadata
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                int chars = 0;
                while ((line = br.readLine()) != null && chars < 25_000) {
                    sb.append(line).append('\n');
                    chars += line.length();
                }
            }
            conn.disconnect();

            String html  = sb.toString();
            String title = null;
            String img   = null;

            // og:title (two attribute orders)
            Matcher m = OG_TITLE.matcher(html);
            if (m.find()) {
                title = m.group(1).trim();
            } else {
                m = OG_TITLE2.matcher(html);
                if (m.find()) title = m.group(1).trim();
            }
            // Fallback to <title>
            if (title == null || title.isEmpty()) {
                m = HTML_TITLE.matcher(html);
                if (m.find()) title = m.group(1).trim();
            }

            // og:image
            m = OG_IMAGE.matcher(html);
            if (m.find()) {
                img = m.group(1).trim();
            } else {
                m = OG_IMAGE2.matcher(html);
                if (m.find()) img = m.group(1).trim();
            }

            if (title != null) title = title.replaceAll("\\s+", " ");

            return new Preview(urlStr, title, domain, img);

        } catch (Exception e) {
            return null;
        }
    }

    /** Call on app wipe or sign-out to clear cached previews. */
    public static void clearCache() {
        cache.clear();
    }
}
