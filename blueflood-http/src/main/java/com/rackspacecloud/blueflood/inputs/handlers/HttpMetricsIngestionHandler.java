/*
 * Copyright 2013-2015 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.inputs.handlers;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import com.google.common.util.concurrent.ListenableFuture;
import com.rackspacecloud.blueflood.cache.ConfigTtlProvider;
import com.rackspacecloud.blueflood.exceptions.InvalidDataException;
import com.rackspacecloud.blueflood.http.DefaultHandler;
import com.rackspacecloud.blueflood.http.HttpRequestHandler;
import com.rackspacecloud.blueflood.inputs.formats.JSONMetric;
import com.rackspacecloud.blueflood.inputs.formats.JSONMetricsContainer;
import com.rackspacecloud.blueflood.io.Constants;
import com.rackspacecloud.blueflood.tracker.Tracker;
import com.rackspacecloud.blueflood.types.IMetric;
import com.rackspacecloud.blueflood.types.Metric;
import com.rackspacecloud.blueflood.types.MetricsCollection;
import com.rackspacecloud.blueflood.utils.Metrics;
import com.rackspacecloud.blueflood.utils.TimeValue;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.type.TypeFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class HttpMetricsIngestionHandler implements HttpRequestHandler {

    public static final String ERROR_HEADER = "The following errors have been encountered:";

    private static final Logger log = LoggerFactory.getLogger(HttpMetricsIngestionHandler.class);
    private static final Counter requestCount = Metrics.counter(HttpMetricsIngestionHandler.class, "HTTP Request Count");


    protected final ObjectMapper mapper;
    protected final TypeFactory typeFactory;
    private final HttpMetricsIngestionServer.Processor processor;
    private final TimeValue timeout;

    // Metrics
    private static final Timer jsonTimer = Metrics.timer(HttpMetricsIngestionHandler.class, "HTTP Ingestion json processing timer");
    private static final Timer persistingTimer = Metrics.timer(HttpMetricsIngestionHandler.class, "HTTP Ingestion persisting timer");

    public static String getResponseBody( List<String> errors ) {
        StringBuilder sb = new StringBuilder();
        sb.append( ERROR_HEADER + System.lineSeparator() );

        for( String error : errors ) {

            sb.append( error + System.lineSeparator() );
        }
        return sb.toString();
    }


    public HttpMetricsIngestionHandler(HttpMetricsIngestionServer.Processor processor, TimeValue timeout) {
        this.mapper = new ObjectMapper();
        this.typeFactory = TypeFactory.defaultInstance();
        this.timeout = timeout;
        this.processor = processor;
    }

    protected JSONMetricsContainer createContainer(String body, String tenantId) throws JsonParseException, JsonMappingException, IOException {
        List<JSONMetric> jsonMetrics =
                mapper.readValue(
                        body,
                        typeFactory.constructCollectionType(List.class, JSONMetric.class)
                );
        return new JSONMetricsContainer(tenantId, jsonMetrics);
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {

            Tracker.getInstance().track(request);

            requestCount.inc();
            final String tenantId = request.headers().get("tenantId");
            JSONMetricsContainer jsonMetricsContainer;
            List<Metric> metrics;

            final Timer.Context jsonTimerContext = jsonTimer.time();

            final String body = request.content().toString(Constants.DEFAULT_CHARSET);
            try {
                jsonMetricsContainer = createContainer(body, tenantId);

                if (jsonMetricsContainer == null) {
                    log.warn(ctx.channel().remoteAddress() + " Failed to create jsonMetricsContainer.");
                    DefaultHandler.sendResponse(ctx, request, "No valid metrics", HttpResponseStatus.BAD_REQUEST);
                    return;
                } else if (jsonMetricsContainer.getJsonMetrics().isEmpty()) {
                    log.warn(ctx.channel().remoteAddress() + " Json metrics is empty, no valid json metrics to parse.");
                    DefaultHandler.sendResponse(ctx, request, "Error converting JSON payload to metric objects", HttpResponseStatus.BAD_REQUEST);
                    return;
                }

                if (jsonMetricsContainer.areDelayedMetricsPresent()) {
                    Tracker.getInstance().trackDelayedMetricsTenant(tenantId, jsonMetricsContainer.getDelayedMetrics());
                }

                metrics = jsonMetricsContainer.getValidMetrics();
                forceTTLsIfConfigured(metrics);

            } catch (JsonParseException e) {
                log.warn("Exception parsing content", e);
                DefaultHandler.sendResponse(ctx, request, "Cannot parse content", HttpResponseStatus.BAD_REQUEST);
                return;
            } catch (JsonMappingException e) {
                log.warn("Exception parsing content", e);
                DefaultHandler.sendResponse(ctx, request, "Cannot parse content", HttpResponseStatus.BAD_REQUEST);
                return;
            } catch (InvalidDataException ex) {
                // todo: we should measure these. if they spike, we track down the bad client.
                // this is strictly a client problem. Someting wasn't right (data out of range, etc.)
                log.warn(ctx.channel().remoteAddress() + " " + ex.getMessage());
                DefaultHandler.sendResponse(ctx, request, "Invalid data " + ex.getMessage(), HttpResponseStatus.BAD_REQUEST);
                return;
            } catch (IOException e) {
                log.warn("IO Exception parsing content", e);
                DefaultHandler.sendResponse(ctx, request, "Cannot parse content", HttpResponseStatus.BAD_REQUEST);
                return;
            } catch (Exception e) {
                log.warn("Other exception while trying to parse content", e);
                DefaultHandler.sendResponse(ctx, request, "Failed parsing content", HttpResponseStatus.INTERNAL_SERVER_ERROR);
                return;
            } finally {
                jsonTimerContext.stop();
            }

            // verify metrics after parsing json and converting to metrics
            List<String> errors = jsonMetricsContainer.getValidationErrors();
            if (metrics == null || metrics.isEmpty()) {
                // empty container
                log.warn(ctx.channel().remoteAddress() + " No valid metrics");
                DefaultHandler.sendResponse(ctx, request, getResponseBody( errors ), HttpResponseStatus.BAD_REQUEST );
                return;
            }

            final MetricsCollection collection = new MetricsCollection();
            collection.add(new ArrayList<IMetric>(metrics));
            final Timer.Context persistingTimerContext = persistingTimer.time();
            try {
                ListenableFuture<List<Boolean>> futures = processor.apply(collection);
                List<Boolean> persisteds = futures.get(timeout.getValue(), timeout.getUnit());
                for (Boolean persisted : persisteds) {
                    if (!persisted) {
                        log.warn("Trouble persisting metrics:");
                        log.warn(String.format("%s", Arrays.toString(metrics.toArray())));
                        DefaultHandler.sendResponse(ctx, request, "Persisted failed for metrics", HttpResponseStatus.INTERNAL_SERVER_ERROR);
                        return;
                    }
                }

                // after processing metrics, return either OK or MULTI_STATUS depending on number of valid metrics
                if( !errors.isEmpty() ) {
                    // has some validation errors, return MULTI_STATUS
                    DefaultHandler.sendResponse(ctx, request, null, HttpResponseStatus.MULTI_STATUS);
                    return;
                }
                else {
                    // no validation error, return OK
                    DefaultHandler.sendResponse(ctx, request, null, HttpResponseStatus.OK);
                    return;
                }

            } catch (TimeoutException e) {
                DefaultHandler.sendResponse(ctx, request, "Timed out persisting metrics", HttpResponseStatus.ACCEPTED);
            } catch (Exception e) {
                log.error("Exception persisting metrics", e);
                DefaultHandler.sendResponse(ctx, request, "Error persisting metrics", HttpResponseStatus.INTERNAL_SERVER_ERROR);
            } finally {
                persistingTimerContext.stop();
            }
        } finally {
            requestCount.dec();
        }
    }

    private void forceTTLsIfConfigured(List<Metric> containerMetrics) {
        ConfigTtlProvider configTtlProvider = ConfigTtlProvider.getInstance();

        if(configTtlProvider.areTTLsForced()) {
            for(Metric m : containerMetrics) {
                m.setTtl(configTtlProvider.getConfigTTLForIngestion());
            }
        }
    }
}
