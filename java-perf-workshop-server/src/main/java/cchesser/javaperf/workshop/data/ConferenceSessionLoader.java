package cchesser.javaperf.workshop.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.*;

/**
 * Loads conference session data. This caches data which is loaded from a remote service instance and
 * executes its data loading in a multi-threaded fashion.
 */
public class ConferenceSessionLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConferenceSessionLoader.class);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, new ThreadFactoryBuilder()
            .setNameFormat("wkshp-conf-loader-%d").setDaemon(true).build());

    // Simple self-loading in-memory cache to load content.
    private static LoadingCache<String, List<ConferenceSession>> CACHE = CacheBuilder.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build(
                    new CacheLoader<String, List<ConferenceSession>>() {
                        public List<ConferenceSession> load(String key)
                                throws ExecutionException, InterruptedException {
                            Future<List<ConferenceSession>> sessions = EXECUTOR
                                    .submit(new SessionLoadCallable(SessionLoadCallable.TARGET_SESSIONS));
                            Future<List<ConferenceSession>> precompilers = EXECUTOR
                                    .submit(new SessionLoadCallable(SessionLoadCallable.TARGET_PRECOMPILERS));
                            final List<ConferenceSession> results = sessions.get();
                            results.addAll(precompilers.get());

                            // Applying a sort by title name. This is not necessary, but applied to provide additional
                            // cost overhead when analyzing the JVM.
                            results.sort((ConferenceSession o1, ConferenceSession o2) -> o1.getTitle()
                                    .compareTo(o2.getTitle()));

                            LOGGER.debug("Loading results from remote service: {}", results.size());
                            return results;
                        }
                    });

    /**
     * @return Indicates if the remote service is available.
     */
    public boolean isRemoteServiceAvailable() {
        try {
            HttpURLConnection.setFollowRedirects(false);
            HttpURLConnection con =
                    (HttpURLConnection) new URL(SessionLoadCallable.BASE_URL).openConnection();
            con.setRequestMethod("HEAD");
            return (con.getResponseCode() == HttpURLConnection.HTTP_OK);
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * @return List of {@link cchesser.javaperf.workshop.data.ConferenceSession}. This content is cached, and is loaded
     * with a default TTL of 30 seconds.
     */
    public List<ConferenceSession> load() {
        try {
            return CACHE.get("KEY");
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Callable to support the operation of loading KCDC conference session data.
     */
    private static class SessionLoadCallable implements Callable<List<ConferenceSession>> {

        static final String BASE_URL = "http://www.kcdc.info/";
        static final String TARGET_SESSIONS = "sessions";
        static final String TARGET_PRECOMPILERS = "precompilers";

        private final String target;
        SessionLoadCallable(String target) {
            this.target = target;
        }

        @Override
        public List<ConferenceSession> call() throws Exception {

            List<ConferenceSession> sessions = null;
            try {
                ObjectMapper mapper = new ObjectMapper();
                sessions = mapper.readValue(new URL(BASE_URL + target).openStream(), new TypeReference<List<ConferenceSession>>(){});
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return sessions;
        }
    }
}