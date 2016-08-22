/*
 * Copyright 2014 Rackspace
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

import com.google.gson.internal.LazilyParsedNumber;
import com.netflix.astyanax.serializers.AbstractSerializer;
import com.rackspacecloud.blueflood.inputs.formats.AggregatedPayload;
import com.rackspacecloud.blueflood.io.serializers.Serializers;
import com.rackspacecloud.blueflood.types.PreaggregatedMetric;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.util.Collection;

import static junit.framework.Assert.*;
import static com.rackspacecloud.blueflood.TestUtils.*;


public class HttpAggregatedIngestionTest {

    private AggregatedPayload payload;

    private final String postfix = ".post";

    @Before
    public void buildPayload() throws IOException {

        String json = getJsonFromFile("sample_payload.json", postfix);
        payload = AggregatedPayload.create(json);
    }
    
    @Test(expected = NumberFormatException.class)
    public void testExpectedGsonConversionFailure() {
        new LazilyParsedNumber("2.321").longValue();
    }
    
    @Test
    public void testGsonNumberConversions() {

        Number doubleNum = new LazilyParsedNumber("2.321");
        assertEquals( Double.parseDouble( "2.321" ), PreaggregateConversions.resolveNumber( doubleNum ) );
        
        Number longNum = new LazilyParsedNumber("12345");
        assertEquals( Long.parseLong( "12345" ), PreaggregateConversions.resolveNumber( longNum ) );
    }
    
    @Test
    public void testCounters() {
        Collection<PreaggregatedMetric> counters = PreaggregateConversions.convertCounters("1", 1, 15000, payload.getCounters());
        assertEquals( 6, counters.size() );
        ensureSerializability(counters);
    }
    
    @Test
    public void testGauges() {
        Collection<PreaggregatedMetric> gauges = PreaggregateConversions.convertGauges("1", 1, payload.getGauges());
        assertEquals( 4, gauges.size() );
        ensureSerializability(gauges);
    }
     
    @Test
    public void testSets() {
        Collection<PreaggregatedMetric> sets = PreaggregateConversions.convertSets("1", 1, payload.getSets());
        assertEquals( 2, sets.size() );
        ensureSerializability(sets);
    }
    
    @Test
    public void testTimers() {
        Collection<PreaggregatedMetric> timers = PreaggregateConversions.convertTimers("1", 1, payload.getTimers());
        assertEquals( 4, timers.size() );
        ensureSerializability(timers);
    }

    @Test
    public void testEnums() {
        Collection<PreaggregatedMetric> enums = PreaggregateConversions.convertEnums("1", 1, payload.getEnums());
        assertEquals( 1, enums.size() );
        ensureSerializability(enums);
    }

    // ok. while we're out it, let's test serialization. Just for fun. The reasoning is that these metrics
    // follow a different creation path that what we currently have in tests.
    private static void ensureSerializability(Collection<PreaggregatedMetric> metrics) {
        for (PreaggregatedMetric metric : metrics) {
            AbstractSerializer serializer = Serializers.serializerFor(metric.getMetricValue().getClass());
            serializer.toByteBuffer(metric.getMetricValue());
        }
    }
}
