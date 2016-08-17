/*
 * Copyright 2014-2015 Rackspace
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
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.rackspacecloud.blueflood.http.DefaultHandler;
import com.rackspacecloud.blueflood.http.HttpRequestHandler;
import com.rackspacecloud.blueflood.inputs.formats.AggregatedPayload;
import com.rackspacecloud.blueflood.io.Constants;
import com.rackspacecloud.blueflood.tracker.Tracker;
import com.rackspacecloud.blueflood.types.MetricsCollection;
import com.rackspacecloud.blueflood.utils.Metrics;
import com.rackspacecloud.blueflood.utils.TimeValue;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class HttpAggregatedIngestionHandler implements HttpRequestHandler {
    
    private static final Logger log = LoggerFactory.getLogger(HttpAggregatedIngestionHandler.class);
    
    private static final Timer handlerTimer = Metrics.timer(HttpAggregatedIngestionHandler.class, "HTTP statsd metrics ingestion timer");
    private static final Counter requestCount = Metrics.counter(HttpAggregatedIngestionHandler.class, "HTTP Request Count");
    
    private final HttpMetricsIngestionServer.Processor processor;
    private final TimeValue timeout;
    
    public HttpAggregatedIngestionHandler(HttpMetricsIngestionServer.Processor processor, TimeValue timeout) {
        this.processor = processor;
        this.timeout = timeout;
    }
    
    // our own stuff.
    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request) {

        Tracker.getInstance().track(request);

        final Timer.Context timerContext = handlerTimer.time();

        // this is all JSON.
        final String body = request.content().toString(Constants.DEFAULT_CHARSET);
        try {
            // block until things get ingested.
            requestCount.inc();
            MetricsCollection collection = new MetricsCollection();

            AggregatedPayload payload = createPayload( body );

            List<String> errors = payload.getValidationErrors();
            if ( errors.isEmpty() ) {
                // no validation errors, process bundle
                collection.add( PreaggregateConversions.buildMetricsCollection( payload ) );
                ListenableFuture<List<Boolean>> futures = processor.apply( collection );
                List<Boolean> persisteds = futures.get( timeout.getValue(), timeout.getUnit() );
                for ( Boolean persisted : persisteds ) {
                    if ( !persisted ) {
                        DefaultHandler.sendResponse( ctx, request, null, HttpResponseStatus.INTERNAL_SERVER_ERROR );
                        return;
                    }
                }
                DefaultHandler.sendResponse( ctx, request, null, HttpResponseStatus.OK );
            }
            else {
                // has validation errors for the single metric, return BAD_REQUEST
                DefaultHandler.sendResponse( ctx,
                        request,
                        HttpMetricsIngestionHandler.getResponseBody( errors ),
                        HttpResponseStatus.BAD_REQUEST );
            }

        } catch (JsonParseException ex) {
            log.debug(String.format("BAD JSON: %s", body));
            log.error(ex.getMessage(), ex);
            DefaultHandler.sendResponse(ctx, request, ex.getMessage(), HttpResponseStatus.BAD_REQUEST);
        } catch (TimeoutException ex) {
            DefaultHandler.sendResponse(ctx, request, "Timed out persisting metrics", HttpResponseStatus.ACCEPTED);
        } catch (Exception ex) {
            log.debug(String.format("JSON request payload: %s", body));
            log.error("Error saving data", ex);
            DefaultHandler.sendResponse(ctx, request, "Internal error saving data", HttpResponseStatus.INTERNAL_SERVER_ERROR);
        } finally {
            requestCount.dec();
            timerContext.stop();
        }
    }
    
    public static AggregatedPayload createPayload(String json) {
        AggregatedPayload payload = new Gson().fromJson(json, AggregatedPayload.class);
        return payload;
    }
}
