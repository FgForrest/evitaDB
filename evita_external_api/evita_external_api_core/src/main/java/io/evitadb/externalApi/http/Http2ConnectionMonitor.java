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

import io.evitadb.externalApi.event.Http2GoAwayEvent;
import io.evitadb.externalApi.event.Http2GoAwayEvent.Direction;
import io.evitadb.externalApi.event.Http2RstFloodEvent;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Error;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Makes two otherwise invisible HTTP/2 connection-level situations visible in the server log and in the metrics:
 *
 * 1. **a peer resetting streams en masse** - a client that opens and immediately cancels calls in a tight loop (an
 *    unbounded re-subscription loop without a backoff is the classic one) or whose request timeouts are all firing at
 *    once. Nothing about it shows up in the access log, because the individual requests look perfectly ordinary;
 * 2. **the server tearing a connection down with an erroneous `GOAWAY`** - `PROTOCOL_ERROR` or `FRAME_SIZE_ERROR`
 *    from a malformed peer or a broken intermediary, `COMPRESSION_ERROR` from an HPACK desync, `SETTINGS_TIMEOUT`,
 *    `INADEQUATE_SECURITY`, or `ENHANCE_YOUR_CALM` when the Rapid-Reset defence below is switched on. A `GOAWAY` is
 *    connection-level, so it kills **every** in-flight request on that connection, including ones entirely unrelated
 *    to whatever triggered it - on the client this surfaces as an assortment of transport errors, cancellations and
 *    timeouts against a server that is otherwise perfectly healthy. Graceful `NO_ERROR` shutdowns are ignored.
 *
 * **Detection is deliberately separated from enforcement.** Netty's Rapid-Reset defence (CVE-2023-44487) is
 * *disabled by default* here, unlike in stock Armeria which allows 400 resets per minute. evitaDB is a database that
 * sits behind the application layer and talks to a small number of trusted clients; on that deployment the defence
 * does more harm than good, because a legitimate client emits a `RST_STREAM` for every client-side timeout and every
 * closed server-stream. It therefore fires hardest exactly when the server is already slow - requests time out, the
 * client cancels them, the resets accumulate, the connection is killed, every in-flight request on it dies and the
 * client reconnects and retries. That turns a slow period into a metastable failure. Reporting the flood without
 * killing the connection gives the operator the same information without the amplification.
 *
 * Anyone exposing evitaDB to untrusted peers can switch the defence back on with {@link #PROPERTY_MAX_RST_FRAMES}
 * (and optionally {@link #PROPERTY_RST_FRAME_WINDOW_SECONDS}). These are system properties rather than configuration
 * keys on purpose - they are a last resort for a deployment that has an unusual threat model, not something an
 * ordinary operator should be tuning.
 *
 * Neither Netty nor Armeria expose an observation hook for either situation, so both are recognized directly on the
 * wire. {@link ExternalApiServer} hands {@link #install(ChannelPipeline)} to
 * `ServerBuilder#childChannelPipelineCustomizer`, which slots a per-connection handler in between the TLS handler and
 * the HTTP/2 connection handler. It therefore sees inbound frames before the HTTP/2 codec does, and outbound frames
 * after it produced them - in both cases as plain, already-decrypted HTTP/2 frame bytes.
 *
 * **Invariants a future change must not break:**
 *
 * - every inspected {@link ByteBuf} is read with absolute getters only and is forwarded untouched - a relative read
 *   would consume bytes the HTTP/2 codec (or the socket) still needs;
 * - inbound inspection only starts once the HTTP/2 connection preface has been seen, which is what keeps it from
 *   misreading HTTP/1.1 traffic, a PROXY protocol header or an h2c upgrade handshake as frames. A connection whose
 *   preface never arrives is simply never inspected;
 * - inbound inspection disables itself for the connection if it ever loses the frame boundaries
 *   ({@link #MAX_INBOUND_FRAME_LENGTH}); reporting nothing is always preferable to reporting nonsense;
 * - outbound recognition must remain tolerant of buffer layout: Netty currently writes the fixed part of the
 *   `GOAWAY` frame (header, last stream id, error code) as its own buffer and the debug data as a second one, but
 *   the recognition only requires the fixed part to be contiguous.
 *
 * This object is shared by every connection and holds the reporting throttle; the per-connection parsing state lives
 * in the handler instances it produces.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class Http2ConnectionMonitor {
	/**
	 * Undocumented escape hatch enabling Netty's Rapid-Reset defence - the number of `RST_STREAM` frames a connection
	 * may send within {@link #PROPERTY_RST_FRAME_WINDOW_SECONDS} before the server closes it with
	 * `GOAWAY(ENHANCE_YOUR_CALM)`. Defaults to `0`, which leaves the defence off. See the class documentation for why
	 * it is off and why it is not an ordinary configuration key.
	 */
	public static final String PROPERTY_MAX_RST_FRAMES = "evitadb.http2.maxRstFramesPerWindow";
	/**
	 * Undocumented escape hatch setting the length of the window {@link #PROPERTY_MAX_RST_FRAMES} applies to, in
	 * seconds. It is also the window the reporting threshold is measured over. Defaults to `60`.
	 */
	public static final String PROPERTY_RST_FRAME_WINDOW_SECONDS = "evitadb.http2.rstFrameWindowSeconds";
	/**
	 * Number of `RST_STREAM` frames within one window above which a connection is reported. Matches the threshold
	 * stock Armeria enforces at, so the log line appears exactly where a default Armeria server would have severed
	 * the connection.
	 */
	public static final int RST_FLOOD_REPORTING_THRESHOLD = 400;
	/**
	 * Length of the reporting (and, when enabled, enforcement) window in seconds.
	 */
	public static final int DEFAULT_RST_FRAME_WINDOW_SECONDS = 60;

	/**
	 * The HTTP/2 connection preface every client sends before its first frame (RFC 9113 §3.4). Inbound inspection
	 * starts right behind it.
	 */
	private static final byte[] CONNECTION_PREFACE =
		"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
	/**
	 * Length of the HTTP/2 frame header - 3B payload length, 1B type, 1B flags, 4B stream identifier (RFC 9113 §4.1).
	 */
	private static final int FRAME_HEADER_LENGTH = 9;
	/**
	 * Type of the `RST_STREAM` frame (RFC 9113 §6.4).
	 */
	private static final int FRAME_TYPE_RST_STREAM = 0x03;
	/**
	 * Type of the `GOAWAY` frame (RFC 9113 §6.8).
	 */
	private static final int FRAME_TYPE_GO_AWAY = 0x07;
	/**
	 * Minimal `GOAWAY` payload - 4B last stream identifier, 4B error code, optional debug data (RFC 9113 §6.8).
	 */
	private static final int GO_AWAY_MIN_PAYLOAD_LENGTH = 8;
	/**
	 * Error code of a graceful shutdown - the only `GOAWAY` that is not worth reporting (RFC 9113 §7).
	 */
	private static final long ERROR_CODE_NO_ERROR = 0x00L;
	/**
	 * Error code Netty uses when the `RST_STREAM` rate limit is exceeded (RFC 9113 §7). It is the one reason that has
	 * a configurable threshold and a well-known client-side cause, so it earns a more specific explanation.
	 */
	private static final long ERROR_CODE_ENHANCE_YOUR_CALM = 0x0BL;
	/**
	 * Highest error code defined by RFC 9113 §7 - used only to keep the outbound frame recognition tight, Netty and
	 * Armeria never send anything outside this range.
	 */
	private static final long ERROR_CODE_MAX = 0x0DL;
	/**
	 * Largest inbound frame the walker considers plausible. Armeria advertises a `SETTINGS_MAX_FRAME_SIZE` of 16 KiB,
	 * so a conforming peer stays three orders of magnitude below this; anything larger means the walker lost the
	 * frame boundaries and must stop rather than produce made-up counts.
	 */
	private static final int MAX_INBOUND_FRAME_LENGTH = 1 << 20;
	/**
	 * How long reporting is suppressed for a peer address that has already been reported.
	 */
	private static final long REPORT_SUPPRESSION_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
	/**
	 * Upper bound on the number of tracked peer addresses - reached only when many distinct peers misbehave at once,
	 * in which case the entries that fell out of the suppression window are dropped before a new one is added.
	 */
	private static final int MAX_TRACKED_PEERS = 256;
	/**
	 * Class name prefix Armeria gives to both of its HTTP/2 protocol handlers - the one probing for the connection
	 * preface on a plaintext port and the one negotiating ALPN behind TLS. See {@link #install(ChannelPipeline)}.
	 */
	private static final String HTTP2_HANDLER_NAME_PREFIX = "Http2";

	/**
	 * Number of `RST_STREAM` frames per window Netty is told to enforce - `0` (the default) leaves the Rapid-Reset
	 * defence disabled and is what {@link ExternalApiServer} hands to Armeria.
	 */
	@Getter private final int maxRstFramesPerWindow;
	/**
	 * Length of the reporting and enforcement window, in seconds.
	 */
	@Getter private final int rstFrameWindowSeconds;
	/**
	 * Number of `RST_STREAM` frames within one window above which a connection is reported. Follows
	 * {@link #maxRstFramesPerWindow} when enforcement is on (so the report explains the teardown that is about to
	 * happen) and {@link #RST_FLOOD_REPORTING_THRESHOLD} when it is off.
	 */
	private final int reportingThreshold;
	/**
	 * {@link #rstFrameWindowSeconds} pre-converted, so the hot path doesn't repeat the conversion.
	 */
	private final long windowNanos;
	/**
	 * Throttling state per peer address - see {@link PeerReportState}.
	 */
	private final Map<String, PeerReportState> reportStateByPeer = new ConcurrentHashMap<>(16);
	/**
	 * Guards the "could not be installed" warning so a pipeline this monitor no longer understands reports itself
	 * once instead of on every accepted connection.
	 */
	private final AtomicBoolean installationFailureReported = new AtomicBoolean();

	/**
	 * Creates a monitor configured from the undocumented system properties, falling back to reporting-only operation.
	 *
	 * @return monitor shared by every connection of a single server
	 */
	@Nonnull
	public static Http2ConnectionMonitor fromSystemProperties() {
		return new Http2ConnectionMonitor(
			readNonNegativeProperty(PROPERTY_MAX_RST_FRAMES, 0),
			Math.max(1, readNonNegativeProperty(PROPERTY_RST_FRAME_WINDOW_SECONDS, DEFAULT_RST_FRAME_WINDOW_SECONDS))
		);
	}

	/**
	 * Reads a non-negative integer system property, falling back to the default value when it is absent or malformed.
	 *
	 * @param propertyName name of the system property
	 * @param defaultValue value to use when the property is absent or cannot be parsed
	 * @return the resolved value
	 */
	private static int readNonNegativeProperty(@Nonnull String propertyName, int defaultValue) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			final int parsedValue = Integer.parseInt(value.trim());
			if (parsedValue < 0) {
				log.warn("Ignoring negative value `{}` of system property `{}`.", value, propertyName);
				return defaultValue;
			}
			return parsedValue;
		} catch (NumberFormatException ex) {
			log.warn("Ignoring non-numeric value `{}` of system property `{}`.", value, propertyName);
			return defaultValue;
		}
	}

	/**
	 * Translates the numeric error code into its RFC 9113 name so the log line doesn't force the reader to look it up.
	 *
	 * @param errorCode `GOAWAY` error code
	 * @return name of the error code including its numeric value
	 */
	@Nonnull
	private static String errorCodeName(long errorCode) {
		final Http2Error error = Http2Error.valueOf(errorCode);
		return (error == null ? "UNKNOWN" : error.name()) + "(" + errorCode + ")";
	}

	/**
	 * Resolves the peer identification used both for the log line and for throttling it. The port is deliberately left
	 * out - a misbehaving client reconnects on a new ephemeral port every time, so including it would defeat the
	 * suppression entirely.
	 *
	 * @param remoteAddress remote address of the inspected channel
	 * @return human-readable peer address
	 */
	@Nonnull
	private static String peerAddress(@Nullable SocketAddress remoteAddress) {
		if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
			return inetSocketAddress.getAddress() == null ?
				inetSocketAddress.getHostString() : inetSocketAddress.getAddress().getHostAddress();
		} else {
			return String.valueOf(remoteAddress);
		}
	}

	/**
	 * Returns the error code of the `GOAWAY` frame the passed buffer starts with, or `-1` when the buffer holds
	 * something else or a graceful `NO_ERROR` shutdown.
	 *
	 * The frame type, the connection-level stream identifier, the payload length and the error code range are all
	 * verified, which makes a false positive on an arbitrary payload buffer that happens to pass through effectively
	 * impossible.
	 *
	 * @param buffer buffer to inspect, never modified
	 * @return the `GOAWAY` error code, or `-1` when this is not an erroneous `GOAWAY` frame
	 */
	private static long resolveGoAwayErrorCode(@Nonnull ByteBuf buffer) {
		final int index = buffer.readerIndex();
		if (buffer.readableBytes() < FRAME_HEADER_LENGTH + GO_AWAY_MIN_PAYLOAD_LENGTH) {
			return -1L;
		}
		if (buffer.getUnsignedByte(index + 3) != FRAME_TYPE_GO_AWAY) {
			return -1L;
		}
		if (buffer.getUnsignedMedium(index) < GO_AWAY_MIN_PAYLOAD_LENGTH) {
			return -1L;
		}
		// GOAWAY is a connection-level frame - its stream identifier is always zero (the top bit is reserved)
		if ((buffer.getInt(index + 5) & 0x7FFFFFFF) != 0) {
			return -1L;
		}
		// payload layout: 4B last stream identifier followed by the 4B error code
		final long errorCode = buffer.getUnsignedInt(index + FRAME_HEADER_LENGTH + 4);
		// NO_ERROR accompanies every ordinary connection close and carries no information worth a warning
		return errorCode > ERROR_CODE_NO_ERROR && errorCode <= ERROR_CODE_MAX ? errorCode : -1L;
	}

	public Http2ConnectionMonitor(int maxRstFramesPerWindow, int rstFrameWindowSeconds) {
		this.maxRstFramesPerWindow = maxRstFramesPerWindow;
		// a zero or negative window would restart the count on every single frame, so the counter could never reach
		// the threshold - enforcement asked for would be silently disabled instead of merely misconfigured
		this.rstFrameWindowSeconds = Math.max(1, rstFrameWindowSeconds);
		this.reportingThreshold = maxRstFramesPerWindow > 0 ?
			maxRstFramesPerWindow : RST_FLOOD_REPORTING_THRESHOLD;
		this.windowNanos = TimeUnit.SECONDS.toNanos(this.rstFrameWindowSeconds);
	}

	/**
	 * Installs the per-connection handler into a freshly initialized child channel pipeline. Meant to be passed
	 * directly to `ServerBuilder#childChannelPipelineCustomizer`.
	 *
	 * The handler has to sit **after** the TLS handler - otherwise it would inspect ciphertext - and **before** the
	 * HTTP/2 connection handler - otherwise it would see decoded messages instead of frames. Appending it is
	 * therefore wrong: it would land behind everything Armeria installs. At the time this runs Armeria has already
	 * placed its protocol-detection handler (`Http2PrefaceOrHttpHandler` on a plaintext port, `Http2OrHttpHandler`
	 * behind ALPN) and the HTTP/2 connection handler later takes exactly that slot, while the TLS handler takes the
	 * slot of the accept handler in front of it. Inserting right ahead of the protocol handler is the single
	 * position that satisfies both requirements on every port - see `Http2ConnectionMonitorTest`, which asserts it
	 * against a real server on both a plaintext and a TLS port.
	 *
	 * @param pipeline pipeline of a freshly accepted child channel
	 */
	public void install(@Nonnull ChannelPipeline pipeline) {
		try {
			for (Entry<String, ChannelHandler> entry : pipeline) {
				if (entry.getValue().getClass().getSimpleName().startsWith(HTTP2_HANDLER_NAME_PREFIX)) {
					pipeline.addBefore(entry.getKey(), null, newHandler());
					return;
				}
			}
			reportInstallationFailure("no HTTP/2 protocol handler was found in the pipeline " + pipeline.names(), null);
		} catch (Throwable ex) {
			// this runs while a connection is being accepted - letting anything escape would reject the connection
			// outright, so an unusable monitor must cost nothing more than the monitoring itself
			reportInstallationFailure("the pipeline could not be instrumented", ex);
		}
	}

	/**
	 * Reports, exactly once per server, that monitoring could not be installed. Losing the monitor is not worth
	 * failing a connection over, but it must not happen quietly either - an Armeria upgrade that reshapes the
	 * pipeline would otherwise silently take the diagnostics away.
	 *
	 * @param reason what went wrong
	 * @param cause  the exception behind it, if there was one
	 */
	private void reportInstallationFailure(@Nonnull String reason, @Nullable Throwable cause) {
		if (this.installationFailureReported.compareAndSet(false, true)) {
			log.warn(
				"HTTP/2 connection monitoring could not be installed - {}. RST_STREAM floods and erroneous GOAWAY " +
					"teardowns will not be reported; HTTP traffic itself is unaffected.",
				reason, cause
			);
		}
	}

	/**
	 * Number of `RST_STREAM` frames within one window above which a connection is reported. Follows
	 * {@link #getMaxRstFramesPerWindow()} when enforcement is on - so the report explains the teardown that is about
	 * to happen - and {@link #RST_FLOOD_REPORTING_THRESHOLD} when it is off.
	 *
	 * @return the reporting threshold
	 */
	protected int reportingThreshold() {
		return this.reportingThreshold;
	}

	/**
	 * Creates the per-connection handler. A new instance is required for every channel - it carries the frame parsing
	 * state of that one connection.
	 *
	 * @return handler to be inserted into a child channel pipeline
	 */
	@Nonnull
	ChannelHandler newHandler() {
		return new MonitoringHandler();
	}

	/**
	 * Reports that a peer exceeded the `RST_STREAM` reporting threshold within a single window.
	 *
	 * @param channel     connection the frames arrived on
	 * @param resetFrames number of `RST_STREAM` frames counted within the window
	 */
	protected void reportRstFlood(@Nonnull Channel channel, int resetFrames) {
		final String peerAddress = peerAddress(channel.remoteAddress());

		new Http2RstFloodEvent(peerAddress).commit();

		final int suppressed = suppressedSinceLastReport(peerAddress, System.nanoTime());
		if (suppressed >= 0) {
			if (this.maxRstFramesPerWindow > 0) {
				log.warn(
					"HTTP/2 peer `{}` sent {} RST_STREAM frames within {} s and reached the configured limit ({}, " +
						"system property `{}`): the server is about to close the connection with " +
						"GOAWAY(ENHANCE_YOUR_CALM), so every in-flight request on it - including requests unrelated " +
						"to the flood - will fail. {}",
					peerAddress, resetFrames, this.rstFrameWindowSeconds, this.maxRstFramesPerWindow,
					PROPERTY_MAX_RST_FRAMES, throttleNote(suppressed)
				);
			} else {
				log.warn(
					"HTTP/2 peer `{}` sent {} RST_STREAM frames within {} s. Each of them is a cancelled request, so " +
						"this is either a client opening and cancelling calls in a tight loop (for example a " +
						"re-subscription loop without a backoff) or a client whose request timeouts are firing en " +
						"masse. The connection is left intact and the requests keep being served - evitaDB does not " +
						"rate-limit trusted clients - but the peer is doing work nobody consumes. {}",
					peerAddress, resetFrames, this.rstFrameWindowSeconds, throttleNote(suppressed)
				);
			}
		}
	}

	/**
	 * Reports that the server is closing a connection with an erroneous `GOAWAY` frame.
	 *
	 * @param channel   channel that is being torn down
	 * @param errorCode error code carried by the frame
	 */
	protected void reportGoAwaySent(@Nonnull Channel channel, long errorCode) {
		final String peerAddress = peerAddress(channel.remoteAddress());
		final String errorCodeName = errorCodeName(errorCode);

		new Http2GoAwayEvent(peerAddress, errorCodeName, Direction.SENT).commit();

		// an ENHANCE_YOUR_CALM teardown has already been explained in detail by reportRstFlood, which fires on the
		// very frame that trips the limit (the inbound handler necessarily runs before the codec that throws), so
		// repeating it here would only add noise - and must not consume the peer's throttle either
		if (errorCode == ERROR_CODE_ENHANCE_YOUR_CALM) {
			return;
		}
		final int suppressed = suppressedSinceLastReport(peerAddress, System.nanoTime());
		if (suppressed >= 0) {
			log.warn(
				"HTTP/2 connection with peer `{}` is being closed by the server with GOAWAY({}), so every in-flight " +
					"request on it - including requests unrelated to the failure - will fail. This points at a " +
					"protocol-level problem on the connection (a malformed or misbehaving client, an intermediary " +
					"rewriting the traffic, or an unsupported TLS configuration) rather than at an individual " +
					"request. {}",
				peerAddress, errorCodeName, throttleNote(suppressed)
			);
		}
	}

	/**
	 * Reports that a **peer** closed the connection with an erroneous `GOAWAY` frame. This is the mirror image of
	 * {@link #reportGoAwaySent(Channel, long)} and is the more alarming of the two: the client is stating that
	 * *evitaDB* violated the protocol, so unlike an outbound teardown it may well point at a server-side defect
	 * rather than a misbehaving peer. Graceful `NO_ERROR` shutdowns, which every ordinary client sends when it closes
	 * a connection, are ignored.
	 *
	 * @param channel   channel the frame arrived on
	 * @param errorCode error code carried by the frame
	 */
	protected void reportGoAwayReceived(@Nonnull Channel channel, long errorCode) {
		final String peerAddress = peerAddress(channel.remoteAddress());
		final String errorCodeName = errorCodeName(errorCode);

		new Http2GoAwayEvent(peerAddress, errorCodeName, Direction.RECEIVED).commit();

		final int suppressed = suppressedSinceLastReport(peerAddress, System.nanoTime());
		if (suppressed >= 0) {
			log.warn(
				"HTTP/2 peer `{}` closed the connection with GOAWAY({}) - the client is rejecting something the " +
					"server sent, so every in-flight request on that connection failed. Unlike a teardown initiated " +
					"by the server this accuses evitaDB of the protocol violation and may point at a server-side " +
					"defect (or at an intermediary rewriting the traffic), so it is worth investigating even when " +
					"the client recovers by reconnecting. {}",
				peerAddress, errorCodeName, throttleNote(suppressed)
			);
		}
	}

	/**
	 * Builds the tail of a report, telling the reader how many occurrences the throttle swallowed since the previous
	 * one. Without it a peer producing a teardown every couple of seconds is indistinguishable from a peer that
	 * produced exactly one.
	 *
	 * @param suppressed number of occurrences suppressed since the last report for this peer
	 * @return sentence to append to the log message
	 */
	@Nonnull
	private static String throttleNote(int suppressed) {
		final long seconds = TimeUnit.NANOSECONDS.toSeconds(REPORT_SUPPRESSION_INTERVAL_NANOS);
		return suppressed == 0 ?
			"Further reports for this peer are throttled to one per " + seconds + " s." :
			suppressed + " further occurrence(s) for this peer were suppressed since the last report; reports are " +
				"throttled to one per " + seconds + " s.";
	}

	/**
	 * Decides whether the passed peer may be reported now and, if so, how many occurrences were swallowed by the
	 * throttle since the previous report. Concurrent callers for the same peer are resolved so that exactly one wins.
	 *
	 * @param peerAddress peer to check
	 * @param now         current {@link System#nanoTime()}
	 * @return number of suppressed occurrences since the last report (`0` on the first one), or `-1` when this
	 * occurrence itself must be suppressed
	 */
	private int suppressedSinceLastReport(@Nonnull String peerAddress, long now) {
		PeerReportState state = this.reportStateByPeer.get(peerAddress);
		if (state == null) {
			pruneStaleEntries(now);
			final PeerReportState fresh = new PeerReportState(now);
			state = this.reportStateByPeer.putIfAbsent(peerAddress, fresh);
			if (state == null) {
				return 0;
			}
		}
		final long previous = state.lastReportedNanos().get();
		if (now - previous >= REPORT_SUPPRESSION_INTERVAL_NANOS &&
			state.lastReportedNanos().compareAndSet(previous, now)) {
			return state.suppressedOccurrences().getAndSet(0);
		}
		state.suppressedOccurrences().incrementAndGet();
		return -1;
	}

	/**
	 * Drops the peers whose suppression window has already elapsed once the map grew beyond {@link #MAX_TRACKED_PEERS}.
	 * Called only when a previously unseen peer is about to be recorded, i.e. at most once per reported incident, so
	 * the linear sweep costs nothing in practice.
	 *
	 * @param now current {@link System#nanoTime()}
	 */
	private void pruneStaleEntries(long now) {
		if (this.reportStateByPeer.size() >= MAX_TRACKED_PEERS) {
			this.reportStateByPeer.values()
				.removeIf(state -> now - state.lastReportedNanos().get() >= REPORT_SUPPRESSION_INTERVAL_NANOS);
		}
	}

	/**
	 * Throttling state of a single peer address - when it was last reported, and how many occurrences have been
	 * swallowed since.
	 *
	 * @param lastReportedNanos    time of the last report in {@link System#nanoTime()} units
	 * @param suppressedOccurrences occurrences swallowed by the throttle since that report
	 */
	private record PeerReportState(
		@Nonnull AtomicLong lastReportedNanos,
		@Nonnull AtomicInteger suppressedOccurrences
	) {

		PeerReportState(long now) {
			this(new AtomicLong(now), new AtomicInteger());
		}

	}

	/**
	 * Per-connection half of the monitor - walks the inbound HTTP/2 frame stream counting `RST_STREAM` frames and
	 * recognizes erroneous outbound `GOAWAY` frames. See {@link Http2ConnectionMonitor} for the pipeline position
	 * this relies on and for the invariants this parsing must preserve.
	 */
	private final class MonitoringHandler extends ChannelDuplexHandler {
		/**
		 * Scratch space for a frame header that arrived split across several reads.
		 */
		private final byte[] frameHeader = new byte[FRAME_HEADER_LENGTH];
		/**
		 * Scratch space for the fixed part of a `GOAWAY` payload - 4B last stream identifier, 4B error code.
		 */
		private final byte[] goAwayPayload = new byte[GO_AWAY_MIN_PAYLOAD_LENGTH];
		/**
		 * Set to FALSE once the frame boundaries are lost - inbound inspection then stops for good on this connection.
		 */
		private boolean inspecting = true;
		/**
		 * Set to FALSE if outbound recognition ever throws - like {@link #inspecting}, monitoring is sacrificed
		 * rather than the connection.
		 */
		private boolean recognizingOutbound = true;
		/**
		 * Set to TRUE once the connection preface has been seen and the byte stream is known to be HTTP/2 frames.
		 */
		private boolean framing;
		/**
		 * Number of leading {@link #CONNECTION_PREFACE} bytes matched so far.
		 */
		private int prefaceMatched;
		/**
		 * Number of {@link #frameHeader} bytes filled in so far.
		 */
		private int frameHeaderBytes;
		/**
		 * Number of payload bytes of the current frame that still have to be skipped.
		 */
		private int payloadRemaining;
		/**
		 * Number of bytes of the fixed part of the current `GOAWAY` payload captured so far, or `-1` when no `GOAWAY`
		 * payload is being captured.
		 */
		private int goAwayPayloadBytes = -1;
		/**
		 * Start of the current counting window, in {@link System#nanoTime()} units.
		 */
		private long windowStartNanos = System.nanoTime();
		/**
		 * Number of `RST_STREAM` frames counted within the current window.
		 */
		private int resetFramesInWindow;

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (this.inspecting && msg instanceof ByteBuf buffer) {
				try {
					inspectInbound(ctx.channel(), buffer);
				} catch (Throwable ex) {
					// This is an observability side-channel sitting in the middle of every connection. An exception
					// escaping here would be caught by Netty, turned into an exceptionCaught, and would very likely
					// close the connection - and the buffer below would never be forwarded, stalling the read and
					// leaking it. No diagnostic is worth that, so monitoring switches itself off for this connection
					// instead. (The project's "never silently skip an unexpected state" rule yields here, and the
					// price is paid as a loud warning rather than as silence.)
					this.inspecting = false;
					reportInspectionFailure("inbound", ex);
				}
			}
			// forwarded outside the guard above - the buffer must reach the codec exactly as it arrived, whatever
			// the monitor did or failed to do with it
			ctx.fireChannelRead(msg);
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			if (this.recognizingOutbound && msg instanceof ByteBuf buffer) {
				try {
					final long errorCode = resolveGoAwayErrorCode(buffer);
					if (errorCode > 0) {
						reportGoAwaySent(ctx.channel(), errorCode);
					}
				} catch (Throwable ex) {
					// an exception here would fail the write promise and lose a frame the peer is waiting for
					this.recognizingOutbound = false;
					reportInspectionFailure("outbound", ex);
				}
			}
			// forwarded outside the guard above, for the same reason as in channelRead
			ctx.write(msg, promise);
		}

		/**
		 * Reports that monitoring was switched off for this connection because it threw. Deliberately swallows its
		 * own failure - if the logging subsystem is what broke, saying so must still not reach the traffic.
		 *
		 * @param direction which half of the monitor failed
		 * @param cause     the exception it failed with
		 */
		private static void reportInspectionFailure(@Nonnull String direction, @Nonnull Throwable cause) {
			try {
				log.warn(
					"HTTP/2 {} connection monitoring failed and has been disabled for this connection. The " +
						"connection itself is unaffected and keeps serving requests.",
					direction, cause
				);
			} catch (Throwable ignored) {
				// nothing left to do - reporting the failure to report is not worth risking the connection over
			}
		}

		/**
		 * Walks the inbound bytes, counting `RST_STREAM` frames. Reads absolutely so that the buffer reaches the
		 * HTTP/2 codec untouched, and tolerates frames split across any number of reads.
		 *
		 * @param channel connection the bytes arrived on
		 * @param buffer  bytes to walk, never modified
		 */
		private void inspectInbound(@Nonnull Channel channel, @Nonnull ByteBuf buffer) {
			final int end = buffer.writerIndex();
			int index = buffer.readerIndex();
			while (index < end) {
				if (!this.framing) {
					final byte current = buffer.getByte(index++);
					if (current == CONNECTION_PREFACE[this.prefaceMatched]) {
						if (++this.prefaceMatched == CONNECTION_PREFACE.length) {
							this.framing = true;
						}
					} else {
						// the preface has no self-overlap, so a mismatch can only restart the match from scratch
						this.prefaceMatched = current == CONNECTION_PREFACE[0] ? 1 : 0;
					}
				} else if (this.payloadRemaining > 0) {
					if (this.goAwayPayloadBytes >= 0) {
						// the fixed part of a GOAWAY payload carries the error code and may be split across reads
						while (index < end && this.goAwayPayloadBytes < GO_AWAY_MIN_PAYLOAD_LENGTH) {
							this.goAwayPayload[this.goAwayPayloadBytes++] = buffer.getByte(index++);
							this.payloadRemaining--;
						}
						if (this.goAwayPayloadBytes == GO_AWAY_MIN_PAYLOAD_LENGTH) {
							this.goAwayPayloadBytes = -1;
							recordReceivedGoAway(channel);
						}
					} else {
						final int skipped = Math.min(this.payloadRemaining, end - index);
						this.payloadRemaining -= skipped;
						index += skipped;
					}
				} else {
					this.frameHeader[this.frameHeaderBytes++] = buffer.getByte(index++);
					if (this.frameHeaderBytes == FRAME_HEADER_LENGTH) {
						this.frameHeaderBytes = 0;
						final int length = ((this.frameHeader[0] & 0xFF) << 16)
							| ((this.frameHeader[1] & 0xFF) << 8)
							| (this.frameHeader[2] & 0xFF);
						if (length > MAX_INBOUND_FRAME_LENGTH) {
							// the frame boundaries are lost - stop rather than report made-up counts
							this.inspecting = false;
							return;
						}
						final int frameType = this.frameHeader[3] & 0xFF;
						if (frameType == FRAME_TYPE_RST_STREAM) {
							recordResetFrame(channel);
						} else if (frameType == FRAME_TYPE_GO_AWAY && length >= GO_AWAY_MIN_PAYLOAD_LENGTH) {
							// arm the payload capture below - the error code follows the last stream identifier
							this.goAwayPayloadBytes = 0;
						}
						this.payloadRemaining = length;
					}
				}
			}
		}

		/**
		 * Reports a `GOAWAY` the peer sent us, reading the error code out of the payload the walker captured. The
		 * graceful `NO_ERROR` close that ends every ordinary connection is not news and is passed over.
		 *
		 * @param channel connection the frame arrived on
		 */
		private void recordReceivedGoAway(@Nonnull Channel channel) {
			final long errorCode = ((long) (this.goAwayPayload[4] & 0xFF) << 24)
				| ((long) (this.goAwayPayload[5] & 0xFF) << 16)
				| ((long) (this.goAwayPayload[6] & 0xFF) << 8)
				| (this.goAwayPayload[7] & 0xFF);
			// NO_ERROR is what every ordinary client sends when it closes a connection - only failures are news.
			// Unlike the outbound recognition there is no upper bound on the code here: this walker knows the frame
			// boundaries, so an unknown (extension) code is genuine rather than a possible false positive.
			if (errorCode != ERROR_CODE_NO_ERROR) {
				reportGoAwayReceived(channel, errorCode);
			}
		}

		/**
		 * Counts one `RST_STREAM` frame into the current window and reports the connection the first time the count
		 * passes the threshold. The window is **tumbling**, not sliding: once it elapses the count restarts at one.
		 * That is deliberately the same shape Netty's `Http2MaxRstFrameListener` uses, so when enforcement is turned
		 * on, this counter and Netty's limiter trip on the very same frame instead of drifting apart.
		 *
		 * @param channel connection the frame arrived on
		 */
		private void recordResetFrame(@Nonnull Channel channel) {
			final long now = System.nanoTime();
			if (now - this.windowStartNanos >= Http2ConnectionMonitor.this.windowNanos) {
				this.windowStartNanos = now;
				this.resetFramesInWindow = 1;
			} else if (++this.resetFramesInWindow == reportingThreshold() + 1) {
				reportRstFlood(channel, this.resetFramesInWindow);
			}
		}

	}

}
