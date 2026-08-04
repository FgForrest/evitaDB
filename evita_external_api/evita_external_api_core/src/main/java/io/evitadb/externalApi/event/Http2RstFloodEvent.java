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
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * JFR event fired when a single HTTP/2 connection sends more `RST_STREAM` frames within one window than the reporting
 * threshold allows. Every such frame is a cancelled request, so a sustained flood means a client is either opening
 * and cancelling calls in a tight loop or having all of its request timeouts fire at once - neither shows up in the
 * access log, because the individual requests look perfectly ordinary.
 *
 * The event is emitted regardless of whether the Rapid-Reset defence is enforcing (it is disabled by default, see
 * {@link io.evitadb.externalApi.http.Http2ConnectionMonitor}), which is what makes the situation alertable without
 * having to sever the connection to find out about it.
 *
 * The offending peer address is recorded on the JFR event but deliberately **not** exported as a metric label - it is
 * an unbounded dimension that would blow up the Prometheus cardinality. Use the accompanying log line or the JFR
 * recording to identify the peer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Description(
	"Event that is fired when a single HTTP/2 connection sends more RST_STREAM (cancelled request) frames within " +
		"one window than the reporting threshold allows."
)
@Label("HTTP/2 RST_STREAM flood")
@ExportInvocationMetric(label = "HTTP/2 RST_STREAM floods detected total")
@Getter
public class Http2RstFloodEvent extends AbstractExternalApiEvent {

	/**
	 * Address of the peer that sent the frames.
	 */
	@Label("Peer address")
	@Name("peerAddress")
	@Description("The remote address of the peer that flooded the server with RST_STREAM frames.")
	final String peerAddress;

	public Http2RstFloodEvent(@Nonnull String peerAddress) {
		this.peerAddress = peerAddress;
	}

}
