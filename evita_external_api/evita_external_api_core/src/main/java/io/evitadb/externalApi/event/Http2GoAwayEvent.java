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

package io.evitadb.externalApi.event;

import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * JFR event fired whenever an HTTP/2 connection is torn down by an erroneous `GOAWAY` frame - in either direction.
 *
 * `SENT` covers the server giving up on a connection: `ENHANCE_YOUR_CALM` from Netty's Rapid-Reset defence
 * (CVE-2023-44487, off by default - see {@link io.evitadb.externalApi.http.Http2ConnectionMonitor}),
 * `PROTOCOL_ERROR` or `FRAME_SIZE_ERROR` from a malformed peer, `COMPRESSION_ERROR` from an HPACK desync, and the
 * other connection-level failures. `RECEIVED` covers the mirror image and is the more alarming one: the *client* is
 * rejecting something the server sent, which may point at a defect in evitaDB rather than in the peer.
 *
 * Either way a `GOAWAY` closes the whole connection, so every in-flight request on it fails - exporting the
 * occurrence as a metric makes the situation alertable instead of something that has to be discovered by debugging
 * the client. Graceful `NO_ERROR` shutdowns, which accompany every ordinary connection close, are not reported.
 *
 * The error code and the direction are exported as metric labels - both are closed, small sets (RFC 9113 §7 defines
 * fourteen codes), and the code is what tells a rate-limited client apart from a malformed one. The peer address is
 * recorded on the JFR event but deliberately **not** exported as a label - it is an unbounded dimension that would
 * blow up the Prometheus cardinality. Use the accompanying log line or the JFR recording to identify the peer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Description(
	"Event that is fired when an HTTP/2 connection is closed by an erroneous GOAWAY frame, either sent by the " +
		"server or received from the peer."
)
@Label("HTTP/2 connection closed with GOAWAY")
@ExportInvocationMetric(label = "HTTP/2 connections closed with an erroneous GOAWAY total")
@Getter
public class Http2GoAwayEvent extends AbstractExternalApiEvent {

	/**
	 * Address of the peer whose connection was closed.
	 */
	@Label("Peer address")
	@Name("peerAddress")
	@Description("The remote address of the peer whose connection was closed.")
	final String peerAddress;

	/**
	 * Name and numeric value of the RFC 9113 §7 error code carried by the `GOAWAY` frame.
	 */
	@Label("GOAWAY error code")
	@Name("errorCode")
	@Description("The RFC 9113 error code carried by the GOAWAY frame, for example ENHANCE_YOUR_CALM(11).")
	@ExportMetricLabel
	final String errorCode;

	/**
	 * Whether the server sent the frame or received it from the peer.
	 */
	@Label("GOAWAY direction")
	@Name("direction")
	@Description("SENT when the server closed the connection, RECEIVED when the peer did.")
	@ExportMetricLabel
	final String direction;

	public Http2GoAwayEvent(@Nonnull String peerAddress, @Nonnull String errorCode, @Nonnull Direction direction) {
		this.peerAddress = peerAddress;
		this.errorCode = errorCode;
		this.direction = direction.name();
	}

	/**
	 * Which side of the connection gave up.
	 */
	public enum Direction {
		/**
		 * The server closed the connection - the peer, or something between it and the server, misbehaved.
		 */
		SENT,
		/**
		 * The peer closed the connection, rejecting something the server sent.
		 */
		RECEIVED
	}

}
