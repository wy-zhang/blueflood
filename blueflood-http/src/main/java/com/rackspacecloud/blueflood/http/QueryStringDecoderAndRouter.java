/*
 * Copyright 2013 Rackspace
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

package com.rackspacecloud.blueflood.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is our Netty's HTTP inbound handler. It is responsible to
 * process incoming HttpRequest and route it to the right class.
 *
 * This class is used by both POST (Ingest) and GET (Query) servers.
 */
public class QueryStringDecoderAndRouter extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(QueryStringDecoderAndRouter.class);
    private final RouteMatcher router;

    public QueryStringDecoderAndRouter(RouteMatcher router) {
        super(true);
        this.router = router;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        router.route(ctx, HttpRequestWithDecodedQueryParams.create(msg));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable thr) {
        if (thr.getCause() instanceof TooLongFrameException) {
            // todo: meter these so we observe DOS conditions.
            log.warn(String.format("Long frame from %s", ctx.channel().remoteAddress()));
            HttpResponder.respond(ctx, null, HttpResponseStatus.BAD_REQUEST);
        } else {
            log.warn(String.format("Exception event received from %s: ", ctx.channel().remoteAddress()), thr);
        }
    }
}
