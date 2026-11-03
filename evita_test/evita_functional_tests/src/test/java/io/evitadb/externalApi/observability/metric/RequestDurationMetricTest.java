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

package io.evitadb.externalApi.observability.metric;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import io.evitadb.externalApi.event.RequestEvent;
import io.evitadb.externalApi.http.HttpMetricDecorator;
import jdk.jfr.EventType;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the request duration exported from JFR covers asynchronous HTTP request processing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HTTP request duration metric")
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class RequestDurationMetricTest {

	/**
	 * Exercises the decorator on a real server and reads the same JFR duration used by MetricHandler.
	 */
	@Nested
	@DisplayName("Completed requests")
	class CompletedRequests {

		/**
		 * A delayed response makes the request lifetime measurable without blocking the event loop.
		 */
		@Test
		@DisplayName("Includes time spent waiting for the handler response")
		void shouldRecordNonZeroDurationWhenResponseIsDelayed(@TempDir @Nonnull final Path directory) throws Exception {
			final ServedRequest served = serve(
				(ctx, req) -> HttpResponse.delayed(HttpResponse.of("measured response"), Duration.ofMillis(25)),
				directory
			);

			assertEquals(HttpStatus.OK, served.response().status());
			assertEquals("measured response", served.response().contentUtf8());
			assertEquals(1, served.events().size(), "Exactly one completed request event must be recorded");
			final RecordedEvent request = served.events().getFirst();
			assertTrue(
				request.getDuration().toNanos() >= Duration.ofMillis(25).toNanos(),
				"Request JFR duration must include the delayed handler response"
			);
			assertEquals("200", request.getString("httpStatusCode"));
			assertEquals("SUCCESS", request.getString("requestResult"));
			assertEquals("GET", request.getString("methodName"));
			assertEquals("h1c", request.getString("sessionProtocol"));
			final double totalDuration = request.getDouble("totalDurationMilliseconds");
			assertTrue(
				totalDuration >= request.getDouble("responseDurationMilliseconds"),
				"Sending the response is part of the total duration"
			);
			assertTrue(
				totalDuration >= request.getDouble("requestDurationMilliseconds"),
				"Receiving the request is part of the total duration"
			);
			assertTrue(totalDuration >= 25.0);
			assertEquals(0L, request.getLong("requestLengthBytes"));
			assertEquals("measured response".length(), request.getLong("responseLengthBytes"));
		}

		/**
		 * A handler failing before it returns a response must still close the event begun for its request.
		 */
		@Test
		@DisplayName("Records one error event when the handler throws")
		void shouldRecordSingleErrorEventWhenHandlerThrows(@TempDir @Nonnull final Path directory) throws Exception {
			final ServedRequest served = serve(
				(ctx, req) -> {
					throw new IllegalStateException("Handler failure");
				},
				directory
			);

			assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, served.response().status());
			assertEquals(1, served.events().size(), "Exactly one request event must be recorded for a failed request");
			final RecordedEvent request = served.events().getFirst();
			assertTrue(request.getDuration().toNanos() > 0, "Request JFR duration must span the failed request");
			assertEquals("500", request.getString("httpStatusCode"));
			assertEquals("ERROR", request.getString("requestResult"));
		}
	}

	/**
	 * Serves one HTTP/1 request through the decorator on a real server while a recording captures request events.
	 *
	 * @param delegate  the service wrapped by the decorator
	 * @param directory the directory the recording is dumped to
	 * @return the response together with the request events carrying the API label unique to this call
	 */
	@Nonnull
	private static ServedRequest serve(@Nonnull HttpService delegate, @Nonnull Path directory) throws Exception {
		final String apiCode = "duration-test-" + UUID.randomUUID();
		final String eventName = EventType.getEventType(RequestEvent.class).getName();
		final Path dump = directory.resolve("request.jfr");
		final AggregatedHttpResponse response;
		try (final Recording recording = new Recording()) {
			recording.enable(RequestEvent.class).withThreshold(Duration.ZERO);
			recording.start();
			final Server server = Server.builder()
				.http(0)
				.service("/duration", new HttpMetricDecorator(delegate, apiCode))
				.build();
			try {
				server.start().join();
				response = WebClient.of("h1c://127.0.0.1:" + server.activeLocalPort())
					.get("/duration")
					.aggregate()
					.get(30, SECONDS);
			} finally {
				server.stop().join();
			}
			recording.stop();
			recording.dump(dump);
		}
		final List<RecordedEvent> events = RecordingFile.readAllEvents(dump).stream()
			.filter(event -> eventName.equals(event.getEventType().getName()))
			.filter(event -> apiCode.equals(event.getString("api")))
			.toList();
		return new ServedRequest(response, events);
	}

	/**
	 * The client's view of a served request together with the request events recorded for it.
	 *
	 * @param response the aggregated response received by the client
	 * @param events   the request events recorded with the API label of the call
	 */
	private record ServedRequest(
		@Nonnull AggregatedHttpResponse response,
		@Nonnull List<RecordedEvent> events
	) {
	}
}
