/**
 * Copyright 2016-2019 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.sse.internal.stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.agrona.BitUtil.SIZE_OF_BYTE;
import static org.agrona.LangUtil.rethrowUnchecked;
import static org.reaktivity.nukleus.buffer.BufferPool.NO_SLOT;
import static org.reaktivity.nukleus.sse.internal.util.Flags.FIN;
import static org.reaktivity.nukleus.sse.internal.util.Flags.INIT;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.sse.internal.SseConfiguration;
import org.reaktivity.nukleus.sse.internal.SseNukleus;
import org.reaktivity.nukleus.sse.internal.types.Flyweight;
import org.reaktivity.nukleus.sse.internal.types.HttpHeaderFW;
import org.reaktivity.nukleus.sse.internal.types.ListFW;
import org.reaktivity.nukleus.sse.internal.types.OctetsFW;
import org.reaktivity.nukleus.sse.internal.types.String16FW;
import org.reaktivity.nukleus.sse.internal.types.StringFW;
import org.reaktivity.nukleus.sse.internal.types.codec.SseEventFW;
import org.reaktivity.nukleus.sse.internal.types.control.Capability;
import org.reaktivity.nukleus.sse.internal.types.control.RouteFW;
import org.reaktivity.nukleus.sse.internal.types.control.SseRouteExFW;
import org.reaktivity.nukleus.sse.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.sse.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.sse.internal.types.stream.ChallengeFW;
import org.reaktivity.nukleus.sse.internal.types.stream.DataFW;
import org.reaktivity.nukleus.sse.internal.types.stream.EndFW;
import org.reaktivity.nukleus.sse.internal.types.stream.HttpBeginExFW;
import org.reaktivity.nukleus.sse.internal.types.stream.HttpChallengeExFW;
import org.reaktivity.nukleus.sse.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.sse.internal.types.stream.SseBeginExFW;
import org.reaktivity.nukleus.sse.internal.types.stream.SseDataExFW;
import org.reaktivity.nukleus.sse.internal.types.stream.SseEndExFW;
import org.reaktivity.nukleus.sse.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.sse.internal.util.Flags;
import org.reaktivity.nukleus.stream.StreamFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public final class SseServerFactory implements StreamFactory
{
    private static final String HTTP_TYPE_NAME = "http";

    private static final StringFW HEADER_NAME_METHOD = new StringFW(":method");
    private static final StringFW HEADER_NAME_STATUS = new StringFW(":status");
    private static final StringFW HEADER_NAME_ACCESS_CONTROL_ALLOW_METHODS = new StringFW("access-control-allow-methods");
    private static final StringFW HEADER_NAME_ACCESS_CONTROL_REQUEST_METHOD = new StringFW("access-control-request-method");
    private static final StringFW HEADER_NAME_ACCESS_CONTROL_REQUEST_HEADERS = new StringFW("access-control-request-headers");

    private static final String16FW HEADER_VALUE_STATUS_204 = new String16FW("204");
    private static final String16FW HEADER_VALUE_STATUS_405 = new String16FW("405");
    private static final String16FW HEADER_VALUE_METHOD_GET = new String16FW("GET");
    private static final String16FW HEADER_VALUE_METHOD_OPTIONS = new String16FW("OPTIONS");

    private static final String16FW CORS_PREFLIGHT_METHOD = HEADER_VALUE_METHOD_OPTIONS;
    private static final String16FW CORS_ALLOWED_METHODS = HEADER_VALUE_METHOD_GET;

    private static final Pattern QUERY_PARAMS_PATTERN = Pattern.compile("(?<path>[^?]*)(?<query>[\\?].*)");
    private static final Pattern LAST_EVENT_ID_PATTERN = Pattern.compile("(\\?|&)lastEventId=(?<lastEventId>[^&]*)(&|$)");

    private static final byte ASCII_COLON = 0x3a;
    private static final String METHOD_PROPERTY = "method";
    private static final String HEADERS_PROPERTY = "headers";

    public static final int MAXIMUM_HEADER_SIZE =
            5 +         // data:
            3 +         // id:
            255 +       // id string
            6 +         // event:
            16 +        // event string
            3;          // \n for data:, id:, event

    private static final int CHALLENGE_CAPABILITIES_MASK = 1 << Capability.CHALLENGE.ordinal();

    private final RouteFW routeRO = new RouteFW();
    private final SseRouteExFW sseRouteExRO = new SseRouteExFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();

    private final ChallengeFW challengeRO = new ChallengeFW();
    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();

    private final SseBeginExFW.Builder sseBeginExRW = new SseBeginExFW.Builder();

    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final HttpBeginExFW httpBeginExRO = new HttpBeginExFW();
    private final HttpBeginExFW.Builder httpBeginExRW = new HttpBeginExFW.Builder();

    private final HttpChallengeExFW httpChallengeExRO = new HttpChallengeExFW();

    private final SseDataExFW sseDataExRO = new SseDataExFW();
    private final SseEndExFW sseEndExRO = new SseEndExFW();

    private final SseEventFW.Builder sseEventRW = new SseEventFW.Builder();

    private final StringFW challengeEventType;

    private final RouteManager router;
    private final MutableDirectBuffer writeBuffer;
    private final MutableDirectBuffer challengeBuffer;
    private final BufferPool bufferPool;
    private final LongUnaryOperator supplyInitialId;
    private final LongUnaryOperator supplyReplyId;
    private final LongSupplier supplyTraceId;
    private final DirectBuffer initialComment;
    private final int httpTypeId;
    private final int sseTypeId;

    private final Gson gson = new Gson();

    private final Long2ObjectHashMap<SseServerReply> correlations;
    private final MessageFunction<RouteFW> wrapRoute;
    private final Consumer<ListFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW>> setHttpResponseHeaders;
    private final Consumer<ListFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW>> setHttpResponseHeadersWithTimestampExt;

    public SseServerFactory(
        SseConfiguration config,
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        LongUnaryOperator supplyInitialId,
        LongUnaryOperator supplyReplyId,
        LongSupplier supplyTraceId,
        ToIntFunction<String> supplyTypeId)
    {
        this.router = requireNonNull(router);
        this.writeBuffer = requireNonNull(writeBuffer);
        this.challengeBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.bufferPool = requireNonNull(bufferPool);
        this.supplyInitialId = requireNonNull(supplyInitialId);
        this.supplyReplyId = requireNonNull(supplyReplyId);
        this.supplyTraceId = requireNonNull(supplyTraceId);
        this.correlations = new Long2ObjectHashMap<>();
        this.wrapRoute = this::wrapRoute;
        this.initialComment = config.initialComment();
        this.httpTypeId = supplyTypeId.applyAsInt(HTTP_TYPE_NAME);
        this.sseTypeId = supplyTypeId.applyAsInt(SseNukleus.NAME);
        this.setHttpResponseHeaders = this::setHttpResponseHeaders;
        this.setHttpResponseHeadersWithTimestampExt = this::setHttpResponseHeadersWithTimestampExt;
        this.challengeEventType = new StringFW(config.getChallengeEventType());
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer throttle)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long streamId = begin.streamId();

        MessageConsumer newStream;

        if ((streamId & 0x0000_0000_0000_0001L) != 0L)
        {
            newStream = newInitialStream(begin, throttle);
        }
        else
        {
            newStream = newReplyStream(begin, throttle);
        }

        return newStream;
    }

    private MessageConsumer newInitialStream(
        final BeginFW begin,
        final MessageConsumer acceptReply)
    {
        final OctetsFW extension = begin.extension();
        final HttpBeginExFW httpBeginEx = extension.get(httpBeginExRO::tryWrap);

        MessageConsumer newStream = null;

        if (isCorsPreflightRequest(httpBeginEx))
        {
            final long acceptRouteId = begin.routeId();
            final long acceptInitialId = begin.streamId();
            final long acceptReplyId = supplyReplyId.applyAsLong(acceptInitialId);
            final long newTraceId = supplyTraceId.getAsLong();

            doWindow(acceptReply, acceptRouteId, acceptInitialId, newTraceId, 0L, 0, 0, 0, 0);
            doHttpBegin(acceptReply, acceptRouteId, acceptReplyId, newTraceId, 0L,
                    SseServerFactory::setCorsPreflightResponse);
            doHttpEnd(acceptReply, acceptRouteId, acceptReplyId, newTraceId, 0L);

            newStream = (t, b, i, l) -> {};
        }
        else if (!isSseRequestMethod(httpBeginEx))
        {
            final long acceptRouteId = begin.routeId();
            final long acceptInitialId = begin.streamId();
            final long acceptReplyId = supplyReplyId.applyAsLong(acceptInitialId);
            final long newTraceId = supplyTraceId.getAsLong();

            doWindow(acceptReply, acceptRouteId, acceptInitialId, newTraceId, 0L, 0, 0, 0, 0);
            doHttpBegin(acceptReply, acceptRouteId, acceptReplyId, newTraceId, 0L,
                hs -> hs.item(h -> h.name(HEADER_NAME_STATUS).value(HEADER_VALUE_STATUS_405)));
            doHttpEnd(acceptReply, acceptRouteId, acceptReplyId, newTraceId, 0L);

            newStream = (t, b, i, l) -> {};
        }
        else
        {
            newStream = newInitialSseStream(begin, acceptReply, httpBeginEx);
        }

        return newStream;
    }

    public MessageConsumer newInitialSseStream(
        final BeginFW begin,
        final MessageConsumer acceptReply,
        final HttpBeginExFW httpBeginEx)
    {
        final long acceptRouteId = begin.routeId();
        final long acceptInitialId = begin.streamId();
        final long traceId = begin.trace();
        final long authorization = begin.authorization();

        // TODO: need lightweight approach (start)
        final Map<String, String> headers = new LinkedHashMap<>();
        httpBeginEx.headers().forEach(header ->
        {
            final String name = header.name().asString();
            final String value = header.value().asString();
            headers.merge(name, value, (v1, v2) -> String.format("%s, %s", v1, v2));
        });

        String pathInfo = headers.get(":path"); // TODO: ":pathinfo" ?
        String lastEventId = headers.get("last-event-id");

        // extract lastEventId query parameter from pathInfo
        // use query parameter value as default for missing Last-Event-ID header
        if (pathInfo != null)
        {
            Matcher matcher = QUERY_PARAMS_PATTERN.matcher(pathInfo);
            if (matcher.matches())
            {
                String path = matcher.group("path");
                String query = matcher.group("query");

                matcher = LAST_EVENT_ID_PATTERN.matcher(query);
                StringBuffer builder = new StringBuffer(path);
                while (matcher.find())
                {
                    if (lastEventId == null)
                    {
                        lastEventId = decodeLastEventId(matcher.group("lastEventId"));
                    }

                    String replacement = matcher.group(3).isEmpty() ? "$3" : "$1";
                    matcher.appendReplacement(builder, replacement);
                }
                matcher.appendTail(builder);
                pathInfo = builder.toString();
            }
        }

        // TODO: need lightweight approach (end)

        final MessagePredicate filter = (t, b, o, l) ->
        {
            final RouteFW route = routeRO.wrap(b, o, o + l);
            final SseRouteExFW routeEx = route.extension().get(sseRouteExRO::tryWrap);
            final String routePathInfo = routeEx != null ? routeEx.pathInfo().asString() : null;

            // TODO: process pathInfo matching
            //       && pathInfo.startsWith(routePathInfo);
            return true;
        };

        MessageConsumer newStream = null;

        final RouteFW route = router.resolve(acceptRouteId, authorization, filter, wrapRoute);
        if (route != null)
        {
            final SseRouteExFW sseRouteEx = route.extension().get(sseRouteExRO::tryWrap);

            final long connectRouteId = route.correlationId();
            final long connectInitialId = supplyInitialId.applyAsLong(connectRouteId);
            final long connectReplyId = supplyReplyId.applyAsLong(connectInitialId);
            final MessageConsumer connectInitial = router.supplyReceiver(connectInitialId);

            final long acceptReplyId = supplyReplyId.applyAsLong(acceptInitialId);

            final boolean timestampRequested = httpBeginEx.headers().anyMatch(header ->
                "accept".equals(header.name().asString()) && header.value().asString().contains("ext=timestamp"));

            final SseServerInitial initialStream = new SseServerInitial(
                    acceptReply,
                    acceptRouteId,
                    acceptInitialId,
                    acceptReplyId,
                    connectInitial,
                    connectRouteId,
                    connectInitialId);

            final SseServerReply replyStream = new SseServerReply(
                    connectInitial,
                    connectRouteId,
                    connectReplyId,
                    acceptReply,
                    acceptRouteId,
                    acceptReplyId,
                    timestampRequested);

            correlations.put(connectReplyId, replyStream);

            router.setThrottle(connectInitialId, initialStream::handleThrottle);
            router.setThrottle(acceptReplyId, replyStream::handleThrottle);

            doSseBegin(connectInitial, connectRouteId, connectInitialId, traceId, authorization,
                    pathInfo, lastEventId);

            newStream = initialStream::handleStream;
        }

        return newStream;
    }

    private MessageConsumer newReplyStream(
        final BeginFW begin,
        final MessageConsumer applicationReplyThrottle)
    {
        final long connectReplyId = begin.streamId();

        final SseServerReply replyStream = correlations.remove(connectReplyId);

        MessageConsumer newStream = null;

        if (replyStream != null)
        {
            newStream = replyStream::handleStream;
        }

        return newStream;
    }

    private RouteFW wrapRoute(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        return routeRO.wrap(buffer, index, index + length);
    }

    private final class SseServerInitial
    {
        private final MessageConsumer acceptReply;
        private final long acceptRouteId;
        private final long acceptInitialId;
        private final long acceptReplyId;
        private final MessageConsumer connectInitial;
        private final long connectRouteId;
        private final long connectInitialId;

        private SseServerInitial(
            MessageConsumer acceptReply,
            long acceptRouteId,
            long acceptInitialId,
            long acceptReplyId,
            MessageConsumer connectInitial,
            long connectRouteId,
            long connectInitialId)
        {
            this.acceptReply = acceptReply;
            this.acceptRouteId = acceptRouteId;
            this.acceptInitialId = acceptInitialId;
            this.acceptReplyId = acceptReplyId;
            this.connectInitial = connectInitial;
            this.connectRouteId = connectRouteId;
            this.connectInitialId = connectInitialId;
        }

        private void handleStream(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                handleBegin(begin);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                handleEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                handleAbort(abort);
                break;
            default:
                doReset(acceptReply, acceptRouteId, acceptInitialId);
                break;
            }
        }

        private void handleBegin(
            BeginFW begin)
        {
        }

        private void handleEnd(
            EndFW end)
        {
            final long traceId = end.trace();
            final long authorization = end.authorization();
            doSseEnd(connectInitial, connectRouteId, connectInitialId, traceId, authorization);
        }

        private void handleAbort(
            AbortFW abort)
        {
            final long traceId = abort.trace();
            final long authorization = abort.authorization();

            // TODO: SseAbortEx
            doSseAbort(connectInitial, connectRouteId, connectInitialId, traceId, authorization);
            cleanupCorrelationIfNecessary();
        }

        private void handleThrottle(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                handleReset(reset);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                handleWindow(window);
                break;
            default:
                // ignore
                break;
            }
        }

        private void handleReset(
            ResetFW reset)
        {
            final long traceId = reset.trace();
            doReset(acceptReply, acceptRouteId, acceptInitialId, traceId);
        }

        private void handleWindow(
            WindowFW window)
        {
            final long authorization = window.authorization();
            final long traceId = window.trace();
            final int credit = window.credit();
            final int padding = window.padding();
            final long groupId = window.groupId();
            final int capabilities = window.capabilities() | CHALLENGE_CAPABILITIES_MASK;

            doWindow(acceptReply, acceptRouteId, acceptInitialId, traceId, authorization, credit, padding, groupId, capabilities);
        }

        private boolean cleanupCorrelationIfNecessary()
        {
            final SseServerReply correlated = correlations.remove(acceptReplyId);
            if (correlated != null)
            {
                router.clearThrottle(acceptReplyId);
            }

            return correlated != null;
        }
    }

    final class SseServerReply
    {
        private final MessageConsumer applicationReplyThrottle;
        private final long applicationRouteId;
        private final long applicationReplyId;

        private final MessageConsumer networkReply;
        private final long networkRouteId;
        private final long networkReplyId;

        private final boolean timestampRequested;

        private int networkSlot = NO_SLOT;
        int networkSlotOffset;
        boolean deferredEnd;

        private MessageConsumer streamState;

        private int minimumNetworkReplyBudget = -1;
        private int networkReplyBudget;
        private int networkReplyPadding;

        private int applicationReplyBudget;

        private SseServerReply(
            MessageConsumer applicationReplyThrottle,
            long applicationRouteId,
            long applicationReplyId,
            MessageConsumer networkReply,
            long networkRouteId,
            long networkReplyId,
            boolean timestampRequested)
        {
            this.applicationReplyThrottle = applicationReplyThrottle;
            this.applicationRouteId = applicationRouteId;
            this.applicationReplyId = applicationReplyId;
            this.networkReply = networkReply;
            this.networkRouteId = networkRouteId;
            this.networkReplyId = networkReplyId;
            this.timestampRequested = timestampRequested;
            this.streamState = this::beforeBegin;
        }

        private void handleStream(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            streamState.accept(msgTypeId, buffer, index, length);
        }

        private void beforeBegin(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            if (msgTypeId == BeginFW.TYPE_ID)
            {
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                handleBegin(begin);
            }
            else
            {
                doReset(applicationReplyThrottle, applicationRouteId, applicationReplyId);
            }
        }

        private void afterBeginOrData(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                handleData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                handleEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                handleAbort(abort);
                break;
            default:
                doReset(applicationReplyThrottle, applicationRouteId, applicationReplyId);
                break;
            }
        }

        private void handleBegin(
            BeginFW begin)
        {
            final long applicationReplyTraceId = begin.trace();
            final long applicationReplyAuthorization = begin.authorization();

            if (timestampRequested)
            {
                doHttpBegin(
                    networkReply,
                    networkRouteId,
                    networkReplyId,
                    applicationReplyTraceId,
                    applicationReplyAuthorization,
                    setHttpResponseHeadersWithTimestampExt);
            }
            else
            {
                doHttpBegin(
                    networkReply,
                    networkRouteId,
                    networkReplyId,
                    applicationReplyTraceId,
                    applicationReplyAuthorization,
                    setHttpResponseHeaders);
            }

            this.streamState = this::afterBeginOrData;
        }

        private void handleData(
            DataFW data)
        {
            final long traceId = data.trace();
            final long authorization = data.authorization();
            final int dataLength = Math.max(data.length(), 0);

            applicationReplyBudget -= dataLength + data.padding();

            if (applicationReplyBudget < 0)
            {
                doReset(applicationReplyThrottle, applicationRouteId, applicationReplyId);
                doSseAbort(networkReply, networkRouteId, networkReplyId, supplyTraceId.getAsLong(), authorization);
            }
            else
            {
                final int flags = data.flags();
                final OctetsFW payload = data.payload();
                final OctetsFW extension = data.extension();

                DirectBuffer id = null;
                DirectBuffer type = null;
                long timestamp = 0L;
                if (extension.sizeof() > 0)
                {
                    final SseDataExFW sseDataEx = extension.get(sseDataExRO::wrap);
                    id = sseDataEx.id().value();
                    type = sseDataEx.type().value();

                    if (timestampRequested)
                    {
                        timestamp = sseDataEx.timestamp();
                    }
                }

                final int bytesWritten = doHttpData(
                    networkReply,
                    networkRouteId,
                    networkReplyId,
                    traceId,
                    authorization,
                    flags,
                    networkReplyPadding,
                    payload,
                    id,
                    type,
                    timestamp,
                    null);

                networkReplyBudget -= bytesWritten + networkReplyPadding;
            }
        }

        private void handleEnd(
            EndFW end)
        {
            final long traceId = end.trace();
            final long authorization = end.authorization();
            final OctetsFW extension = end.extension();

            if (extension.sizeof() > 0)
            {
                final SseEndExFW sseEndEx = extension.get(sseEndExRO::wrap);
                final DirectBuffer id = sseEndEx.id().value();

                int flags = FIN | INIT;

                final SseEventFW sseEvent = sseEventRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                        .flags(flags)
                        .id(id)
                        .build();

                final DataFW frame = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                    .routeId(networkRouteId)
                    .streamId(networkReplyId)
                    .trace(traceId)
                    .authorization(authorization)
                    .flags(flags)
                    .groupId(0)
                    .padding(networkReplyPadding)
                    .payload(sseEvent.buffer(), sseEvent.offset(), sseEvent.sizeof())
                    .build();

                if (networkReplyBudget >= frame.sizeof() + networkReplyPadding)
                {
                    networkReply.accept(frame.typeId(), frame.buffer(), frame.offset(), frame.sizeof());
                    doHttpEnd(networkReply, networkRouteId, networkReplyId, traceId, authorization);
                }
                else
                {
                    // Rare condition where there is insufficient window to write id: last_event_id\n\n
                    networkSlot = bufferPool.acquire(networkReplyId);
                    MutableDirectBuffer buffer = bufferPool.buffer(networkSlot);
                    buffer.putBytes(0, frame.buffer(), frame.offset(), frame.sizeof());
                    networkSlotOffset = frame.sizeof();
                    deferredEnd = true;
                }
            }
            else
            {
                doHttpEnd(networkReply, networkRouteId, networkReplyId, traceId, authorization);
            }
        }

        private void handleAbort(
            AbortFW abort)
        {
            final long traceId = abort.trace();
            final long authorization = abort.authorization();
            doHttpAbort(networkReply, networkRouteId, networkReplyId, traceId, authorization);
        }

        private void handleThrottle(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                handleWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                handleReset(reset);
                break;
            case ChallengeFW.TYPE_ID:
                final ChallengeFW challenge = challengeRO.wrap(buffer, index, index + length);
                handleChallenge(challenge);
                break;
            default:
                // ignore
                break;
            }
        }

        private void handleWindow(
            WindowFW window)
        {
            networkReplyBudget += window.credit();
            networkReplyPadding = window.padding();

            if (minimumNetworkReplyBudget == -1)
            {
                minimumNetworkReplyBudget = window.credit();

                if (initialComment != null)
                {
                    final int bytesWritten = doHttpData(
                            networkReply,
                            networkRouteId,
                            networkReplyId,
                            supplyTraceId.getAsLong(),
                            0L,
                            FIN | INIT,
                            networkReplyPadding,
                            null,
                            null,
                            null,
                            0L,
                            initialComment);

                    networkReplyBudget -= bytesWritten + networkReplyPadding;
                    assert networkReplyBudget >= 0;
                }
            }

            if (networkReplyBudget < minimumNetworkReplyBudget)
            {
                // Not sending WINDOW to application side as group budget expects full initial window first time
                // Wait until it builds up to full initial window
                return;
            }
            minimumNetworkReplyBudget = 0;

            if (networkSlot != NO_SLOT)
            {
                final MutableDirectBuffer buffer = bufferPool.buffer(networkSlot);
                final DataFW data = dataRO.wrap(buffer,  0,  networkSlotOffset);
                final int networkReplyDebit = data.payload().sizeof() + data.padding();

                if (networkReplyBudget >= networkReplyDebit)
                {
                    networkReply.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
                    networkReplyBudget -= networkReplyDebit;
                    networkSlotOffset -= data.sizeof();
                    assert networkSlotOffset == 0;
                    bufferPool.release(networkSlot);
                    networkSlot = NO_SLOT;

                    if (deferredEnd)
                    {
                        doHttpEnd(networkReply, networkRouteId, networkReplyId, data.trace(), data.authorization());
                        deferredEnd = false;
                    }
                }
            }

            int applicationReplyPadding = networkReplyPadding + MAXIMUM_HEADER_SIZE;
            final int applicationReplyCredit = networkReplyBudget - applicationReplyBudget;
            if (applicationReplyCredit > 0)
            {
                final long traceId = window.trace();
                final long authorization = window.authorization();
                doWindow(applicationReplyThrottle, applicationRouteId, applicationReplyId, traceId, authorization,
                         applicationReplyCredit, applicationReplyPadding, window.groupId(), 0);
                applicationReplyBudget += applicationReplyCredit;
            }
        }

        private void handleReset(
            ResetFW reset)
        {
            final long traceId = reset.trace();
            doReset(applicationReplyThrottle, applicationRouteId, applicationReplyId, traceId);
        }

        private void handleChallenge(
            ChallengeFW challenge)
        {
            final HttpChallengeExFW httpChallengeEx = challenge.extension().get(httpChallengeExRO::tryWrap);
            if (httpChallengeEx != null)
            {
                final JsonObject challengeObject = new JsonObject();
                final JsonObject challengeHeaders = new JsonObject();
                final ListFW<HttpHeaderFW> httpHeaders = httpChallengeEx.headers();

                httpHeaders.forEach(header ->
                {
                    final StringFW name = header.name();
                    final String16FW value = header.value();
                    if (name != null)
                    {
                        if (name.sizeof() > SIZE_OF_BYTE &&
                            name.buffer().getByte(name.offset() + SIZE_OF_BYTE) != ASCII_COLON)
                        {
                            final String propertyName = name.asString();
                            final String propertyValue = value.asString();
                            challengeHeaders.addProperty(propertyName, propertyValue);
                        }
                        else if (name.equals(HEADER_NAME_METHOD))
                        {
                            final String propertyValue = value.asString();
                            challengeObject.addProperty(METHOD_PROPERTY, propertyValue);
                        }
                    }
                });
                challengeObject.add(HEADERS_PROPERTY, challengeHeaders);

                final String challengeJson = gson.toJson(challengeObject);
                final int challengeBytes = challengeBuffer.putStringWithoutLengthUtf8(0, challengeJson);

                final SseEventFW sseEvent = sseEventRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                        .flags(Flags.INIT | Flags.FIN)
                        .type(challengeEventType.value())
                        .data(challengeBuffer, 0, challengeBytes)
                        .build();

                final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                        .routeId(networkRouteId)
                        .streamId(networkReplyId)
                        .trace(challenge.trace())
                        .authorization(0)
                        .groupId(0)
                        .padding(networkReplyPadding)
                        .payload(sseEvent.buffer(), sseEvent.offset(), sseEvent.sizeof())
                        .build();

                final int networkReplyDebit = sseEvent.sizeof() + networkReplyPadding;
                if (networkReplyBudget > networkReplyDebit)
                {
                    networkReplyBudget -= networkReplyDebit;

                    networkReply.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
                }
                else
                {
                    if (networkSlot == NO_SLOT)
                    {
                        networkSlot = bufferPool.acquire(networkReplyId);
                    }

                    if (networkSlot != NO_SLOT)
                    {
                        MutableDirectBuffer buffer = bufferPool.buffer(networkSlot);
                        buffer.putBytes(networkSlotOffset, data.buffer(), data.offset(), data.sizeof());
                        networkSlotOffset += data.sizeof();
                    }
                }
            }
        }
    }

    private void setHttpResponseHeaders(
        ListFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW> headers)
    {
        headers.item(h -> h.name(":status").value("200"));
        headers.item(h -> h.name("content-type").value("text/event-stream"));
    }

    private void setHttpResponseHeadersWithTimestampExt(
        ListFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW> headers)
    {
        headers.item(h -> h.name(":status").value("200"));
        headers.item(h -> h.name("content-type").value("text/event-stream;ext=timestamp"));
    }

    private void doHttpBegin(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        Consumer<ListFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW>> mutator)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .extension(e -> e.set(visitHttpBeginEx(mutator)))
                .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private Flyweight.Builder.Visitor visitHttpBeginEx(
        Consumer<ListFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW>> headers)
    {
        return (buffer, offset, limit) ->
            httpBeginExRW.wrap(buffer, offset, limit)
                         .typeId(httpTypeId)
                         .headers(headers)
                         .build()
                         .sizeof();
    }

    private int doHttpData(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        int flags,
        int padding,
        OctetsFW data,
        DirectBuffer id,
        DirectBuffer type,
        long timestamp,
        DirectBuffer comment)
    {
        final SseEventFW sseEvent = sseEventRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                .flags(flags)
                .timestamp(timestamp)
                .id(id)
                .type(type)
                .data(data)
                .comment(comment)
                .build();

        final DataFW frame = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .flags(flags)
                .groupId(0)
                .padding(padding)
                .payload(sseEvent.buffer(), sseEvent.offset(), sseEvent.sizeof())
                .build();

        receiver.accept(frame.typeId(), frame.buffer(), frame.offset(), frame.sizeof());

        return frame.length();
    }

    private void doHttpEnd(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doHttpAbort(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doSseBegin(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        String pathInfo,
        String lastEventId)
    {
        final SseBeginExFW sseBegin = sseBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(sseTypeId)
                .pathInfo(pathInfo)
                .lastEventId(lastEventId)
                .build();

        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .extension(sseBegin.buffer(), sseBegin.offset(), sseBegin.sizeof())
                .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private void doSseAbort(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        // TODO: SseAbortEx
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doSseEnd(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doWindow(
        final MessageConsumer sender,
        final long routeId,
        final long streamId,
        final long traceId,
        final long authorization,
        final int credit,
        final int padding,
        final long groupId,
        final int capabilities)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .trace(traceId)
                .authorization(authorization)
                .credit(credit)
                .padding(padding)
                .groupId(groupId)
                .capabilities(capabilities)
                .build();

        sender.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doReset(
        final MessageConsumer sender,
        final long routeId,
        final long streamId,
        final long traceId)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
               .routeId(routeId)
               .streamId(streamId)
               .trace(traceId)
               .build();

        sender.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    private void doReset(
        final MessageConsumer sender,
        final long routeId,
        final long streamId)
    {
        doReset(sender, routeId, streamId, supplyTraceId.getAsLong());
    }

    private static String decodeLastEventId(
        String lastEventId)
    {
        if (lastEventId != null && lastEventId.indexOf('%') != -1)
        {
            try
            {
                lastEventId = URLDecoder.decode(lastEventId, UTF_8.toString());
            }
            catch (UnsupportedEncodingException ex)
            {
                // unexpected, UTF-8 is a supported character set
                rethrowUnchecked(ex);
            }
        }

        return lastEventId;
    }

    private static boolean isCorsPreflightRequest(
        HttpBeginExFW httpBeginEx)
    {
        return httpBeginEx != null &&
               httpBeginEx.headers().anyMatch(h -> HEADER_NAME_METHOD.equals(h.name()) &&
                                                   CORS_PREFLIGHT_METHOD.equals(h.value())) &&
               httpBeginEx.headers().anyMatch(h -> HEADER_NAME_ACCESS_CONTROL_REQUEST_METHOD.equals(h.name()) ||
                                                   HEADER_NAME_ACCESS_CONTROL_REQUEST_HEADERS.equals(h.name()));
    }

    private static void setCorsPreflightResponse(
        ListFW.Builder<HttpHeaderFW.Builder, HttpHeaderFW> headers)
    {
        headers.item(h -> h.name(HEADER_NAME_STATUS).value(HEADER_VALUE_STATUS_204))
               .item(h -> h.name(HEADER_NAME_ACCESS_CONTROL_ALLOW_METHODS).value(CORS_ALLOWED_METHODS));
    }

    private static boolean isSseRequestMethod(
        HttpBeginExFW httpBeginEx)
    {
        return httpBeginEx != null &&
               httpBeginEx.headers().anyMatch(h -> HEADER_NAME_METHOD.equals(h.name()) &&
                                                   HEADER_VALUE_METHOD_GET.equals(h.value()));
    }
}
