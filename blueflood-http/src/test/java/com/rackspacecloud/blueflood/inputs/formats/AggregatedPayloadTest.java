package com.rackspacecloud.blueflood.inputs.formats;

import com.rackspacecloud.blueflood.service.Configuration;
import com.rackspacecloud.blueflood.service.CoreConfig;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static com.rackspacecloud.blueflood.TestUtils.FUTURE_COLLECTION_TIME_REGEX;
import static com.rackspacecloud.blueflood.TestUtils.PAST_COLLECTION_TIME_REGEX;
import static com.rackspacecloud.blueflood.TestUtils.getJsonFromFile;
import static junit.framework.Assert.assertTrue;

/**
 * Unit tests for the {@link AggregatedPayload} class
 */
public class AggregatedPayloadTest {

    private static final long TIME_DIFF_MS = 2000;
    private static final String POSTFIX = ".post";

    private AggregatedPayload payload;

    @Test
    public void testTimestampInTheFuture() throws IOException {

        long timestamp = System.currentTimeMillis() + TIME_DIFF_MS
                + Configuration.getInstance().getLongProperty( CoreConfig.AFTER_CURRENT_COLLECTIONTIME_MS );

        String json = getJsonFromFile("sample_payload.json", timestamp, POSTFIX);
        payload = AggregatedPayload.create(json);

        List<String> errors = payload.getValidationErrors();
        assertTrue( "'" + errors.get(0) + "' does not match pattern " + FUTURE_COLLECTION_TIME_REGEX, Pattern.matches(FUTURE_COLLECTION_TIME_REGEX, errors.get(0)) );
    }

    @Test
    public void testTimestampInThePast() throws IOException {

        long timestamp = System.currentTimeMillis() - TIME_DIFF_MS
                - Configuration.getInstance().getLongProperty( CoreConfig.BEFORE_CURRENT_COLLECTIONTIME_MS );

        String json = getJsonFromFile( "sample_payload.json", timestamp, POSTFIX);
        payload = AggregatedPayload.create(json);

        List<String> errors = payload.getValidationErrors();
        assertTrue( "'" + errors.get(0) + "' does not match pattern " + PAST_COLLECTION_TIME_REGEX, Pattern.matches( PAST_COLLECTION_TIME_REGEX, errors.get(0) ) );
    }

    @Test
    public void testDelayMetrics() throws IOException {
        long timeNow = System.currentTimeMillis();
        long trackerDelayMs = Configuration.getInstance().getLongProperty(CoreConfig.TRACKER_DELAYED_METRICS_MILLIS);
        long shortDelay = Configuration.getInstance().getLongProperty(CoreConfig.SHORT_DELAY_METRICS_ROLLUP_DELAY_MILLIS);
        long collectionTime = timeNow - trackerDelayMs - shortDelay - TIME_DIFF_MS;

        String json = getJsonFromFile( "sample_single_aggregated_payload.json", collectionTime, POSTFIX);
        payload = AggregatedPayload.create(json);

        assertTrue("payload has delayed metrics", payload.hasDelayedMetrics(timeNow));
    }
}
