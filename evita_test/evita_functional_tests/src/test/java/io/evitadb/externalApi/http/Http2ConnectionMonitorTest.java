/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.http;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Http2ConnectionMonitor} - both the on-the-wire frame recognition in isolation and the assumption
 * it stands on, namely that {@link Http2ConnectionMonitor#install(io.netty.channel.ChannelPipeline)} really does place
 * the handler where it sees the HTTP/2 frames of every connection, on a plaintext and on a TLS port alike.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Http2ConnectionMonitor")
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class Http2ConnectionMonitorTest {
	private static final byte[] CONNECTION_PREFACE =
		"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
	/**
	 * Upper bound on every wait in this class. Nothing here polls, so a generous bound costs nothing on a healthy
	 * run and only decides how long a genuinely broken run takes to fail.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 60L;
	/**
	 * How long a request is left hanging before the client cancels it. Only reached after the connection is already
	 * established, so it is not racing connection setup on a loaded machine.
	 */
	private static final long CANCELLATION_DELAY_MILLIS = 2_000L;

	/**
	 * Fails the test unless the passed latch is counted down within {@link #AWAIT_TIMEOUT_SECONDS}.
	 */
	private static void awaitLatch(@Nonnull CountDownLatch latch, @Nonnull String message) throws Exception {
		assertTrue(latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
	}

	/**
	 * Builds a raw HTTP/2 frame - 3B payload length, 1B type, 1B flags, 4B stream identifier, payload.
	 */
	@Nonnull
	private static byte[] frame(int type, int streamId, @Nonnull byte[] payload) {
		final byte[] result = new byte[9 + payload.length];
		result[0] = (byte) (payload.length >>> 16);
		result[1] = (byte) (payload.length >>> 8);
		result[2] = (byte) payload.length;
		result[3] = (byte) type;
		result[4] = 0;
		result[5] = (byte) (streamId >>> 24);
		result[6] = (byte) (streamId >>> 16);
		result[7] = (byte) (streamId >>> 8);
		result[8] = (byte) streamId;
		System.arraycopy(payload, 0, result, 9, payload.length);
		return result;
	}

	/**
	 * Builds a `RST_STREAM` frame (type 0x03) carrying the `CANCEL` error code.
	 */
	@Nonnull
	private static byte[] rstStreamFrame(int streamId) {
		return frame(0x03, streamId, new byte[]{0x00, 0x00, 0x00, 0x08});
	}

	/**
	 * Builds the fixed part of a `GOAWAY` frame (type 0x07) exactly the way Netty writes it - header, last stream
	 * identifier and error code in one buffer, the debug data in a separate one.
	 */
	@Nonnull
	private static byte[] goAwayFrame(int lastStreamId, long errorCode, int debugDataLength) {
		final byte[] payload = new byte[]{
			(byte) (lastStreamId >>> 24), (byte) (lastStreamId >>> 16),
			(byte) (lastStreamId >>> 8), (byte) lastStreamId,
			(byte) (errorCode >>> 24), (byte) (errorCode >>> 16),
			(byte) (errorCode >>> 8), (byte) errorCode
		};
		final byte[] result = frame(0x07, 0, payload);
		// the declared payload length also covers the debug data that arrives in a separate buffer
		final int declaredLength = payload.length + debugDataLength;
		result[0] = (byte) (declaredLength >>> 16);
		result[1] = (byte) (declaredLength >>> 8);
		result[2] = (byte) declaredLength;
		return result;
	}

	@Nonnull
	private static byte[] concat(@Nonnull byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		final byte[] result = new byte[length];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, result, offset, part.length);
			offset += part.length;
		}
		return result;
	}

	/**
	 * Monitor that records what it would have reported instead of logging it.
	 *
	 * The reports arrive on a Netty event loop while the test asserts on its own thread, so the collections are
	 * concurrent and every report also counts down a latch. Tests **await those latches** rather than polling with
	 * sleeps - this suite runs heavily parallelized and a sleep-and-poll loop turns into a flake as soon as the box
	 * is busy.
	 */
	private static class RecordingMonitor extends Http2ConnectionMonitor {
		private final List<Integer> rstFloods = new CopyOnWriteArrayList<>();
		private final List<Long> goAways = new CopyOnWriteArrayList<>();
		private final List<Long> receivedGoAways = new CopyOnWriteArrayList<>();
		private final CountDownLatch rstFloodReported = new CountDownLatch(1);
		private final CountDownLatch goAwaySentReported = new CountDownLatch(1);
		private final Integer reportingThresholdOverride;

		RecordingMonitor(int maxRstFramesPerWindow, int rstFrameWindowSeconds) {
			this(maxRstFramesPerWindow, rstFrameWindowSeconds, null);
		}

		/**
		 * Lowers the reporting threshold without enabling enforcement, so a test can prove the reporting path
		 * without waiting for the production threshold of 400 frames.
		 */
		RecordingMonitor(int maxRstFramesPerWindow, int rstFrameWindowSeconds, Integer reportingThresholdOverride) {
			super(maxRstFramesPerWindow, rstFrameWindowSeconds);
			this.reportingThresholdOverride = reportingThresholdOverride;
		}

		@Override
		protected int reportingThreshold() {
			return this.reportingThresholdOverride == null ?
				super.reportingThreshold() : this.reportingThresholdOverride;
		}

		@Override
		protected void reportRstFlood(@Nonnull Channel channel, int resetFrames) {
			this.rstFloods.add(resetFrames);
			// counted down last, so an awaiting thread always observes the recorded value
			this.rstFloodReported.countDown();
		}

		@Override
		protected void reportGoAwaySent(@Nonnull Channel channel, long errorCode) {
			this.goAways.add(errorCode);
			this.goAwaySentReported.countDown();
		}

		@Override
		protected void reportGoAwayReceived(@Nonnull Channel channel, long errorCode) {
			this.receivedGoAways.add(errorCode);
		}
	}

	@Nested
	@DisplayName("inbound RST_STREAM counting")
	class InboundCounting {

		@Test
		@DisplayName("reports once the peer exceeds the threshold within a window")
		void shouldReportRstFloodOnceThresholdIsExceeded() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				channel.writeInbound(Unpooled.wrappedBuffer(CONNECTION_PREFACE));
				// with enforcement off the threshold is RST_FLOOD_REPORTING_THRESHOLD - the report fires on the next
				for (int i = 0; i < Http2ConnectionMonitor.RST_FLOOD_REPORTING_THRESHOLD; i++) {
					channel.writeInbound(Unpooled.wrappedBuffer(rstStreamFrame(2 * i + 1)));
				}
				assertEquals(List.of(), monitor.rstFloods, "must not report at or below the threshold");

				channel.writeInbound(Unpooled.wrappedBuffer(rstStreamFrame(999)));
				assertEquals(
					List.of(Http2ConnectionMonitor.RST_FLOOD_REPORTING_THRESHOLD + 1), monitor.rstFloods,
					"must report exactly once when the threshold is exceeded"
				);

				channel.writeInbound(Unpooled.wrappedBuffer(rstStreamFrame(1001)));
				assertEquals(1, monitor.rstFloods.size(), "must report at most once per window");
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("counts frames that arrive split across arbitrary reads")
		void shouldCountFramesSplitAcrossReads() {
			final RecordingMonitor monitor = new RecordingMonitor(2, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				final byte[] stream = concat(
					CONNECTION_PREFACE, rstStreamFrame(1), rstStreamFrame(3), rstStreamFrame(5)
				);
				// hand the bytes over one at a time - the walker must not depend on read boundaries
				for (byte singleByte : stream) {
					channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{singleByte}));
				}
				assertEquals(List.of(3), monitor.rstFloods, "a threshold of 2 must be reported on the third frame");
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("skips frame payloads instead of misreading them as frame headers")
		void shouldSkipFramePayloads() {
			final RecordingMonitor monitor = new RecordingMonitor(1, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				// a DATA frame whose payload contains bytes that look exactly like a RST_STREAM frame
				final byte[] trap = rstStreamFrame(7);
				channel.writeInbound(Unpooled.wrappedBuffer(
					concat(CONNECTION_PREFACE, frame(0x00, 1, trap), rstStreamFrame(1))
				));
				assertEquals(
					List.of(), monitor.rstFloods,
					"payload bytes that look like a RST_STREAM frame must not be counted"
				);

				channel.writeInbound(Unpooled.wrappedBuffer(rstStreamFrame(3)));
				assertEquals(List.of(2), monitor.rstFloods, "genuine RST_STREAM frames must still be counted");
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("ignores a connection whose HTTP/2 preface never arrives")
		void shouldIgnoreNonHttp2Connection() {
			final RecordingMonitor monitor = new RecordingMonitor(1, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				// plain HTTP/1.1 traffic must never be walked as if it were HTTP/2 frames
				channel.writeInbound(Unpooled.wrappedBuffer(
					"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
				));
				channel.writeInbound(Unpooled.wrappedBuffer(rstStreamFrame(1)));
				channel.writeInbound(Unpooled.wrappedBuffer(rstStreamFrame(3)));
				assertEquals(List.of(), monitor.rstFloods, "nothing may be counted before the preface is seen");
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("forwards inbound buffers untouched")
		void shouldForwardInboundBuffersUntouched() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				final byte[] payload = concat(CONNECTION_PREFACE, rstStreamFrame(1));
				channel.writeInbound(Unpooled.wrappedBuffer(payload));
				final ByteBuf forwarded = channel.readInbound();
				assertNotNull(forwarded, "the buffer must reach the next handler");
				assertEquals(payload.length, forwarded.readableBytes(), "no byte may be consumed on the way");
				// finishAndReleaseAll() only releases what is still queued - anything read out has to be released here
				forwarded.release();
			} finally {
				channel.finishAndReleaseAll();
			}
		}
	}

	@Nested
	@DisplayName("inbound GOAWAY recognition")
	class InboundGoAway {

		@Test
		@DisplayName("reports a peer that closes the connection with an error")
		void shouldReportErroneousGoAwayFromPeer() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				channel.writeInbound(Unpooled.wrappedBuffer(concat(CONNECTION_PREFACE, goAwayFrame(7, 9L, 0))));
				assertEquals(
					List.of(9L), monitor.receivedGoAways,
					"a client rejecting what the server sent must be reported - it may be our own defect"
				);
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("ignores the graceful NO_ERROR shutdown every client sends")
		void shouldIgnoreGracefulPeerShutdown() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				channel.writeInbound(Unpooled.wrappedBuffer(concat(CONNECTION_PREFACE, goAwayFrame(7, 0L, 0))));
				assertEquals(List.of(), monitor.receivedGoAways, "an ordinary connection close is not news");
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("reads an error code that arrived split across reads, and keeps its place in the stream")
		void shouldReadSplitGoAwayAndResynchronise() {
			final RecordingMonitor monitor = new RecordingMonitor(1, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				final byte[] debugData = "boom".getBytes(StandardCharsets.US_ASCII);
				final byte[] stream = concat(
					CONNECTION_PREFACE, goAwayFrame(7, 1L, debugData.length), debugData,
					rstStreamFrame(1), rstStreamFrame(3)
				);
				for (byte singleByte : stream) {
					channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{singleByte}));
				}
				assertEquals(List.of(1L), monitor.receivedGoAways, "PROTOCOL_ERROR(1) must be read byte by byte");
				assertEquals(
					List.of(2), monitor.rstFloods,
					"the walker must skip the GOAWAY debug data and stay aligned on the following frames"
				);
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("reports an unknown extension error code, which framing makes unambiguous")
		void shouldReportUnknownErrorCode() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				channel.writeInbound(Unpooled.wrappedBuffer(concat(CONNECTION_PREFACE, goAwayFrame(7, 0xFEL, 0))));
				assertEquals(List.of(0xFEL), monitor.receivedGoAways);
			} finally {
				channel.finishAndReleaseAll();
			}
		}
	}

	@Nested
	@DisplayName("outbound GOAWAY recognition")
	class OutboundGoAway {

		@Test
		@DisplayName("reports an erroneous GOAWAY written by the server")
		void shouldReportErroneousGoAway() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				final byte[] debugData = "Maximum number of RST frames reached".getBytes(StandardCharsets.US_ASCII);
				channel.writeOutbound(Unpooled.wrappedBuffer(goAwayFrame(Integer.MAX_VALUE, 11L, debugData.length)));
				channel.writeOutbound(Unpooled.wrappedBuffer(debugData));
				assertEquals(List.of(11L), monitor.goAways, "ENHANCE_YOUR_CALM(11) must be recognized");
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("reports connection-level protocol failures too")
		void shouldReportProtocolErrorGoAway() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				channel.writeOutbound(Unpooled.wrappedBuffer(goAwayFrame(5, 1L, 0)));
				channel.writeOutbound(Unpooled.wrappedBuffer(goAwayFrame(5, 9L, 0)));
				assertEquals(List.of(1L, 9L), monitor.goAways, "PROTOCOL_ERROR and COMPRESSION_ERROR must be seen");
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("ignores a graceful NO_ERROR shutdown and ordinary frames")
		void shouldIgnoreGracefulShutdown() {
			final RecordingMonitor monitor = new RecordingMonitor(0, 60);
			final EmbeddedChannel channel = new EmbeddedChannel(monitor.newHandler());
			try {
				channel.writeOutbound(Unpooled.wrappedBuffer(goAwayFrame(Integer.MAX_VALUE, 0L, 0)));
				channel.writeOutbound(Unpooled.wrappedBuffer(frame(0x00, 1, new byte[64])));
				channel.writeOutbound(Unpooled.wrappedBuffer(rstStreamFrame(1)));
				assertEquals(List.of(), monitor.goAways, "only erroneous GOAWAY frames may be reported");
			} finally {
				channel.finishAndReleaseAll();
			}
		}
	}

	@Nested
	@DisplayName("resilience")
	class Resilience {

		/**
		 * Monitor whose every report blows up - stands in for a broken logging appender, a JFR failure, or simply a
		 * future defect in the reporting path.
		 */
		private static Http2ConnectionMonitor explodingMonitor() {
			return new Http2ConnectionMonitor(1, 60) {
				@Override
				protected void reportRstFlood(@Nonnull Channel channel, int resetFrames) {
					throw new IllegalStateException("boom");
				}

				@Override
				protected void reportGoAwaySent(@Nonnull Channel channel, long errorCode) {
					throw new IllegalStateException("boom");
				}

				@Override
				protected void reportGoAwayReceived(@Nonnull Channel channel, long errorCode) {
					throw new IllegalStateException("boom");
				}
			};
		}

		@Test
		@DisplayName("a failing inbound report neither drops the buffer nor kills the connection")
		void shouldSurviveFailingInboundReport() {
			final EmbeddedChannel channel = new EmbeddedChannel(explodingMonitor().newHandler());
			try {
				final byte[] payload = concat(
					CONNECTION_PREFACE, rstStreamFrame(1), rstStreamFrame(3), rstStreamFrame(5)
				);
				channel.writeInbound(Unpooled.wrappedBuffer(payload));

				assertTrue(channel.isActive(), "the connection must survive a broken monitor");
				assertDoesNotThrow(channel::checkException, "no exception may reach the pipeline");
				final ByteBuf forwarded = channel.readInbound();
				assertNotNull(forwarded, "the buffer must still reach the HTTP/2 codec");
				assertEquals(payload.length, forwarded.readableBytes(), "with every byte intact");
				forwarded.release();

				// monitoring is off for this connection now, but traffic keeps flowing through it
				final byte[] more = rstStreamFrame(7);
				channel.writeInbound(Unpooled.wrappedBuffer(more));
				final ByteBuf second = channel.readInbound();
				assertNotNull(second, "subsequent reads must keep being forwarded");
				assertEquals(more.length, second.readableBytes());
				second.release();
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("a failing outbound report neither loses the frame nor fails the write")
		void shouldSurviveFailingOutboundReport() {
			final EmbeddedChannel channel = new EmbeddedChannel(explodingMonitor().newHandler());
			try {
				final byte[] goAway = goAwayFrame(5, 1L, 0);
				channel.writeOutbound(Unpooled.wrappedBuffer(goAway));

				assertTrue(channel.isActive(), "the connection must survive a broken monitor");
				assertDoesNotThrow(channel::checkException, "no exception may reach the pipeline");
				final ByteBuf written = channel.readOutbound();
				assertNotNull(written, "the frame must still reach the socket");
				assertEquals(goAway.length, written.readableBytes());
				written.release();

				final byte[] more = frame(0x00, 1, new byte[16]);
				channel.writeOutbound(Unpooled.wrappedBuffer(more));
				final ByteBuf second = channel.readOutbound();
				assertNotNull(second, "subsequent writes must keep going out");
				assertEquals(more.length, second.readableBytes());
				second.release();
			} finally {
				channel.finishAndReleaseAll();
			}
		}

		@Test
		@DisplayName("a pipeline it does not understand is left alone rather than rejected")
		void shouldNotRejectUnknownPipeline() {
			final EmbeddedChannel channel = new EmbeddedChannel();
			try {
				// no Http2* handler anywhere - install() must degrade quietly instead of throwing
				new Http2ConnectionMonitor(0, 60).install(channel.pipeline());
				assertTrue(channel.isActive(), "an uninstrumentable pipeline must not cost the connection");

				final byte[] payload = concat(CONNECTION_PREFACE, rstStreamFrame(1));
				channel.writeInbound(Unpooled.wrappedBuffer(payload));
				final ByteBuf forwarded = channel.readInbound();
				assertNotNull(forwarded, "traffic must flow through an unmonitored pipeline unchanged");
				assertEquals(payload.length, forwarded.readableBytes());
				forwarded.release();
			} finally {
				channel.finishAndReleaseAll();
			}
		}
	}

	@Nested
	@DisplayName("configuration")
	// these are the only tests here that touch JVM-global state, and the suite runs test classes concurrently -
	// without the lock they could hand a half-set threshold to any server booting on another thread
	@ResourceLock(Resources.SYSTEM_PROPERTIES)
	class Configuration {

		@AfterEach
		void tearDown() {
			System.clearProperty(Http2ConnectionMonitor.PROPERTY_MAX_RST_FRAMES);
			System.clearProperty(Http2ConnectionMonitor.PROPERTY_RST_FRAME_WINDOW_SECONDS);
		}

		@Test
		@DisplayName("leaves the Rapid-Reset defence disabled when nothing is set")
		void shouldDefaultToReportingOnly() {
			final Http2ConnectionMonitor monitor = Http2ConnectionMonitor.fromSystemProperties();
			assertEquals(
				0, monitor.getMaxRstFramesPerWindow(),
				"enforcement must be off by default - Armeria's 400 per minute is deliberately not inherited"
			);
			assertEquals(
				Http2ConnectionMonitor.DEFAULT_RST_FRAME_WINDOW_SECONDS, monitor.getRstFrameWindowSeconds()
			);
		}

		@Test
		@DisplayName("enables enforcement when the escape hatch is set")
		void shouldEnableEnforcementFromSystemProperties() {
			System.setProperty(Http2ConnectionMonitor.PROPERTY_MAX_RST_FRAMES, "150");
			System.setProperty(Http2ConnectionMonitor.PROPERTY_RST_FRAME_WINDOW_SECONDS, "30");
			final Http2ConnectionMonitor monitor = Http2ConnectionMonitor.fromSystemProperties();
			assertEquals(150, monitor.getMaxRstFramesPerWindow());
			assertEquals(30, monitor.getRstFrameWindowSeconds());
		}

		@Test
		@DisplayName("falls back to the defaults on a malformed or negative value")
		void shouldFallBackOnMalformedValues() {
			System.setProperty(Http2ConnectionMonitor.PROPERTY_MAX_RST_FRAMES, "not-a-number");
			System.setProperty(Http2ConnectionMonitor.PROPERTY_RST_FRAME_WINDOW_SECONDS, "-5");
			final Http2ConnectionMonitor monitor = Http2ConnectionMonitor.fromSystemProperties();
			assertEquals(0, monitor.getMaxRstFramesPerWindow(), "a malformed value must not enable enforcement");
			assertEquals(
				Http2ConnectionMonitor.DEFAULT_RST_FRAME_WINDOW_SECONDS, monitor.getRstFrameWindowSeconds()
			);
		}

		@Test
		@DisplayName("never hands Netty a zero-length window")
		void shouldNeverProduceZeroWindow() {
			System.setProperty(Http2ConnectionMonitor.PROPERTY_RST_FRAME_WINDOW_SECONDS, "0");
			assertTrue(
				Http2ConnectionMonitor.fromSystemProperties().getRstFrameWindowSeconds() > 0,
				"a zero window would silently disable enforcement even when it was asked for"
			);
		}
	}

	@Nested
	@DisplayName("pipeline placement")
	class PipelinePlacement {
		/**
		 * Number of cancellations each placement test drives, and therefore the reporting threshold it needs.
		 */
		private static final int CANCELLED_REQUESTS = 3;

		private Server server;
		private RecordingMonitor monitor;
		private CountDownLatch requestsReceived;

		@AfterEach
		void tearDown() {
			if (this.server != null) {
				this.server.stop().join();
				this.server = null;
			}
		}

		@Test
		@DisplayName("counts the RST_STREAM frames of a real plaintext HTTP/2 connection")
		void shouldSeeInboundFramesOfPlaintextConnection() throws Exception {
			startServer(false, 0);
			cancelRequests(plainClient(), CANCELLED_REQUESTS);

			awaitLatch(this.monitor.rstFloodReported, "the RST_STREAM flood must be reported");
			assertEquals(
				List.of(CANCELLED_REQUESTS), this.monitor.rstFloods,
				"cancelled requests must be recognized as RST_STREAM frames on the wire"
			);
			assertEquals(List.of(), this.monitor.goAways, "nothing may be torn down while enforcement is off");
		}

		@Test
		@DisplayName("counts the RST_STREAM frames of a real TLS HTTP/2 connection")
		void shouldSeeInboundFramesOfTlsConnection() throws Exception {
			startServer(true, 0);
			cancelRequests(tlsClient(), CANCELLED_REQUESTS);

			awaitLatch(this.monitor.rstFloodReported, "the RST_STREAM flood must be reported through TLS too");
			assertEquals(
				List.of(CANCELLED_REQUESTS), this.monitor.rstFloods,
				"cancelled requests must be recognized through the TLS handler too"
			);
		}

		@Test
		@DisplayName("reports the flood before the enforced GOAWAY when the escape hatch is on")
		void shouldReportFloodAndGoAwayWhenEnforcementIsOn() throws Exception {
			// enforcement at CANCELLED_REQUESTS - 1 makes the last cancellation trip Netty's Rapid-Reset defence
			startServer(false, CANCELLED_REQUESTS - 1);
			cancelRequests(plainClient(), CANCELLED_REQUESTS);

			awaitLatch(this.monitor.rstFloodReported, "the flood must be reported");
			assertEquals(
				List.of(CANCELLED_REQUESTS), this.monitor.rstFloods,
				"the flood must be reported on the very frame that trips the limit"
			);

			awaitLatch(this.monitor.goAwaySentReported, "the enforced GOAWAY must be recognized");
			assertEquals(
				List.of(11L), this.monitor.goAways,
				"GOAWAY(ENHANCE_YOUR_CALM) must be recognized on the outbound path of a real server"
			);
		}

		/**
		 * Boots a server whose pipeline carries the monitor, exactly the way {@link ExternalApiServer} installs it.
		 */
		private void startServer(boolean tls, int maxRstFramesPerWindow) {
			// the reporting threshold is lowered so the test needs a handful of cancellations instead of four hundred
			this.monitor = new RecordingMonitor(
				maxRstFramesPerWindow, 60, maxRstFramesPerWindow > 0 ? null : CANCELLED_REQUESTS - 1
			);
			this.requestsReceived = new CountDownLatch(CANCELLED_REQUESTS);
			final ServerBuilder builder = Server.builder()
				.http2MaxResetFramesPerWindow(maxRstFramesPerWindow, 60)
				.childChannelPipelineCustomizer(this.monitor::install)
				.service("/hang", (ctx, req) -> {
					this.requestsReceived.countDown();
					// never completes on its own - the client's response timeout cancels it, sending RST_STREAM
					return HttpResponse.of(new CompletableFuture<HttpResponse>());
				})
				.service("/ping", (ctx, req) -> HttpResponse.of("pong"));
			if (tls) {
				builder.https(0).tlsSelfSigned();
			} else {
				builder.http(0);
			}
			this.server = builder.build();
			this.server.start().join();
		}

		@Nonnull
		private WebClient plainClient() {
			return WebClient.of("h2c://127.0.0.1:" + this.server.activeLocalPort());
		}

		@Nonnull
		private WebClient tlsClient() {
			return WebClient.builder("h2://127.0.0.1:" + this.server.activeLocalPort(SessionProtocol.HTTPS))
				.factory(ClientFactory.builder().tlsNoVerify().build())
				.build();
		}

		/**
		 * Establishes the connection with one ordinary request, then fires the given number of requests that all time
		 * out on the client. Each timeout cancels its stream, which is exactly what a legitimate client does under
		 * load and what reaches the server as a `RST_STREAM` frame - all of them on the one multiplexed connection.
		 *
		 * The response timeout is set **per request**, so the warm-up request is not racing a deadline of its own on
		 * a loaded machine, and it only starts counting once the connection is already established.
		 */
		private void cancelRequests(@Nonnull WebClient client, int count) throws Exception {
			assertEquals("pong", client.get("/ping").aggregate().join().contentUtf8());

			final RequestOptions cancelAfterShortWait = RequestOptions.builder()
				.responseTimeoutMillis(CANCELLATION_DELAY_MILLIS)
				.build();
			final CompletableFuture<?>[] cancelled = new CompletableFuture<?>[count];
			for (int i = 0; i < count; i++) {
				cancelled[i] = client.execute(HttpRequest.of(HttpMethod.GET, "/hang"), cancelAfterShortWait)
					.aggregate()
					.handle((response, throwable) -> null);
			}
			assertTrue(
				this.requestsReceived.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"every request must reach the server, otherwise no stream was ever opened to reset"
			);
			CompletableFuture.allOf(cancelled).get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
	}
}
