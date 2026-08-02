/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.test.tester;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import io.evitadb.test.tester.GraphQLTester.Request;
import io.restassured.http.Header;
import io.restassured.response.ValidatableResponse;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matcher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.evitadb.externalApi.graphql.io.GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON;
import static io.evitadb.externalApi.http.MimeTypes.APPLICATION_JSON;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Simple tester utility for easier testing of GraphQL API. It uses REST Assured library as backend but test doesn't have
 * to configure each request with URL, headers, POST method and so on.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GraphQLTester extends JsonExternalApiTester<Request> {

	/**
	 * Ceiling for the unconditional websocket waits ({@link #testWebSocket} and
	 * {@link WebSocketContext#awaitEvents(int)}).
	 *
	 * This is a hang detector, not a correctness bound. Every CDC stream these waits sit on is
	 * ring-buffer plus WAL backed, so a subscriber that registers late still reads the committed
	 * history back from disk (`ChangeCatalogCaptureSharedPublisher#fillBuffer` falls back to
	 * `readWal` whenever the ring buffer cannot serve the requested WAL pointer) - delivery is
	 * guaranteed, only its timing is not. Raising the ceiling therefore cannot mask a lost event:
	 * a genuinely undelivered event still fails the test, just later. What it does buy is immunity
	 * to a full-reactor run where dozens of test classes each drive an embedded evitaDB instance
	 * concurrently and a normally sub-second delivery gets starved past 30 seconds.
	 *
	 * The bounded, non-throwing {@link WebSocketContext#tryAwaitEvents} variants deliberately do
	 * NOT use this value - their callers pass their own, much shorter budget because they poll to
	 * detect a genuinely lost (non-replayable) host event and must fail fast to retry.
	 */
	private static final int WEBSOCKET_EVENT_TIMEOUT_SECONDS = 120;

	public GraphQLTester(@Nonnull String baseUrl) {
		super(baseUrl);
	}

	/**
	 * Test single request to GraphQL API.
	 */
	@Override
	@Nonnull
	public Request test(@Nonnull String catalogName) {
		return new Request(this, catalogName);
	}

	/**
	 * Connects to a websocket and provides tools to test the subprotocol.
	 *
	 * @param catalogName where the GraphQL API is located
	 * @param writer receives a {@link WebSocketContext} that can write outbound frames and
	 *               synchronise mid-flow on received inbound frames
	 * @param waitForEvents specifies how many events should be received before the validator is called
	 * @param validator accepts a list of received events and validates them
	 */
	public void testWebSocket(
		@Nonnull String catalogName,
		@Nonnull Consumer<WebSocketContext> writer,
		int waitForEvents,
		@Nonnull Consumer<List<String>> validator
	) {
		testWebSocket(catalogName, null, writer, waitForEvents, validator);
	}

	/**
	 * Connects to a websocket and provides tools to test the subprotocol.
	 *
	 * @param catalogName where the GraphQL API is located
	 * @param urlPathSuffix specifies GraphQL API path suffix
	 * @param writer receives a {@link WebSocketContext} that can write outbound frames and
	 *               synchronise mid-flow on received inbound frames
	 * @param waitForEvents specifies how many events should be received before the validator is called
	 * @param validator accepts a list of received events and validates them
	 */
	public void testWebSocket(
		@Nonnull String catalogName,
		@Nullable String urlPathSuffix,
		@Nonnull Consumer<WebSocketContext> writer,
		int waitForEvents,
		@Nonnull Consumer<List<String>> validator
	) {
		final WebSocketClient client = WebSocketClient.builder(this.baseUrl)
			.factory(ClientFactory.insecure())
			.subprotocols("graphql-transport-ws")
			.build();
		final WebSocketSession session = client.connect("/" + catalogName + (urlPathSuffix != null ? urlPathSuffix : "")).join();
		final WebSocketWriter outbound = session.outbound();

		// thread-safe: `onNext` appends from the Armeria event-loop thread while the test thread
		// polls `size()`/iterates in the awaitility waits and the validator below
		final List<String> receivedEventsHolder = new CopyOnWriteArrayList<>();
		session.inbound().subscribe(new WebSocketSubscriber(receivedEventsHolder));
		writer.accept(new WebSocketContextImpl(catalogName, outbound, receivedEventsHolder));

		try {
			await().atMost(WEBSOCKET_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> receivedEventsHolder.size() >= waitForEvents);
		} catch (RuntimeException ex) {
			log.error(
				"WebSocket test failed for catalog {} - only {} events received within timeout: {}",
				catalogName,
				receivedEventsHolder.size(),
				receivedEventsHolder,
				ex
			);
			throw ex;
		}

		validator.accept(receivedEventsHolder);

		outbound.close();
	}

	/**
	 * Test-side context exposing both the outbound {@link WebSocketWriter} and a barrier method
	 * that blocks until at least a given number of inbound text frames has been received. Used
	 * by subscription tests to wait for `connection_ack` (or other early control frames) before
	 * triggering data-emitting operations, which closes the race between a server-side
	 * `subscribe` registration and the data change firing on the same thread.
	 */
	public interface WebSocketContext {

		/**
		 * Outbound writer for sending frames to the server.
		 */
		@Nonnull
		WebSocketWriter writer();

		/**
		 * Block until at least {@code count} text frames have been received from the server,
		 * or fail after {@code WEBSOCKET_EVENT_TIMEOUT_SECONDS}.
		 */
		void awaitEvents(int count);

		/**
		 * Non-throwing, bounded variant of {@link #awaitEvents(int)}: polls until at least
		 * {@code count} text frames have been received or {@code timeout} elapses, and reports
		 * the outcome instead of failing. Intended for retry loops that trigger a live-tail
		 * (backfill-less) event and need to re-fire the trigger when the server-side subscription
		 * registration lost the race, without burning the whole {@code WEBSOCKET_EVENT_TIMEOUT_SECONDS}
		 * budget on a single try.
		 *
		 * @param count   minimum number of received frames to wait for
		 * @param timeout maximum time to wait before giving up
		 * @return {@code true} if at least {@code count} frames were received within {@code timeout}
		 */
		boolean tryAwaitEvents(int count, @Nonnull Duration timeout);

		/**
		 * Predicate-driven variant of {@link #tryAwaitEvents(int, Duration)}: polls until the
		 * supplied {@code condition} accepts the current snapshot of received frames or
		 * {@code timeout} elapses. Use this instead of the frame-count variant whenever a mere
		 * count is ambiguous — e.g. a subscription opted into BOTH the (WAL-backed, replayable)
		 * `ENGINE` area and the (live-tail, non-replayable) `HOST` area, where engine envelopes
		 * arrive regardless of whether the sought host event was live-tailed. A count-based wait
		 * would then go true on the engine envelopes alone and mask a lost host event; a predicate
		 * scanning for the specific host envelope does not.
		 *
		 * @param condition predicate evaluated against the received-frames list on each poll
		 * @param timeout   maximum time to wait before giving up
		 * @return {@code true} if {@code condition} accepted within {@code timeout}
		 */
		boolean tryAwaitEvents(@Nonnull Predicate<List<String>> condition, @Nonnull Duration timeout);
	}

	@RequiredArgsConstructor
	private static class WebSocketContextImpl implements WebSocketContext {

		/** Poll cadence for {@link #tryAwaitEvents(int, Duration)}. */
		private static final long TRY_AWAIT_POLL_INTERVAL_MS = 25L;

		@Nonnull private final String catalogName;
		@Nonnull private final WebSocketWriter writer;
		@Nonnull private final List<String> receivedEvents;

		@Nonnull
		@Override
		public WebSocketWriter writer() {
			return this.writer;
		}

		@Override
		public void awaitEvents(int count) {
			try {
				await().atMost(WEBSOCKET_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> this.receivedEvents.size() >= count);
			} catch (RuntimeException ex) {
				log.error(
					"WebSocket awaitEvents failed for catalog {} - only {} of {} events received within timeout: {}",
					this.catalogName,
					this.receivedEvents.size(),
					count,
					this.receivedEvents,
					ex
				);
				throw ex;
			}
		}

		@Override
		public boolean tryAwaitEvents(int count, @Nonnull Duration timeout) {
			return tryAwaitEvents(events -> events.size() >= count, timeout);
		}

		@Override
		public boolean tryAwaitEvents(@Nonnull Predicate<List<String>> condition, @Nonnull Duration timeout) {
			// manual bounded poll (not awaitility) so a timeout is a plain `false` rather than a
			// thrown condition — exceptions must not drive the caller's retry control flow
			final long deadlineNanos = System.nanoTime() + timeout.toNanos();
			while (!condition.test(this.receivedEvents)) {
				if (System.nanoTime() >= deadlineNanos) {
					return false;
				}
				try {
					Thread.sleep(TRY_AWAIT_POLL_INTERVAL_MS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return condition.test(this.receivedEvents);
				}
			}
			return true;
		}
	}

	@SneakyThrows
	private ValidatableResponse executeAndThen(@Nonnull Request request) {
		final Map<String, Object> body = Map.of(
			"query", Objects.requireNonNull(request.getDocument()),
			"variables", request.getVariables()
		);

		return given()
				.relaxedHTTPSValidation()
				.headers(new io.restassured.http.Headers(new ArrayList<>(request.getHeaders().values())))
				.body(body)
				.log()
				.ifValidationFails().
			when()
				.post(this.baseUrl + "/" + request.getCatalogName() + (request.getUrlPathSuffix() != null ? request.getUrlPathSuffix() : "")).
			then()
				.log()
				.ifError();
	}

	@Getter(AccessLevel.PRIVATE)
	public static class Request {

		private final GraphQLTester tester;
		private final String catalogName;

		@Nullable
		private String urlPathSuffix;
		@Nullable
		private String document;

		private final Map<String, Object> variables = new HashMap<>();

		private final Map<String, Header> headers = new HashMap<>();

		public Request(@Nonnull GraphQLTester tester, @Nonnull String catalogName) {
			this.tester = tester;
			this.catalogName = catalogName;
		}

		public Request urlPathSuffix(String urlPathSuffix) {
			this.urlPathSuffix = urlPathSuffix;
			return this;
		}

		public Request document(@Nonnull String document, @Nonnull Object... arguments) {
			this.document = String.format(document, arguments);
			return this;
		}

		public Request variable(@Nonnull String name, @Nullable Object value) {
			this.variables.put(name, value);
			return this;
		}

		public Request contentTypeHeader(@Nonnull String value) {
			this.headers.put(CONTENT_TYPE_HEADER, new Header(CONTENT_TYPE_HEADER, value));
			return this;
		}

		public Request acceptHeader(@Nonnull String value) {
			this.headers.put(ACCEPT_HEADER, new Header(ACCEPT_HEADER, value));
			return this;
		}

		public Request header(@Nonnull String name, @Nonnull String value) {
			this.headers.put(name, new Header(name, value));
			return this;
		}

		/**
		 * Executes configured request against GraphQL APi and returns response with validation methods.
		 */
		public ValidatableResponse executeAndThen() {
			if (!this.headers.containsKey(CONTENT_TYPE_HEADER)) {
				this.headers.put(CONTENT_TYPE_HEADER, new Header(CONTENT_TYPE_HEADER, APPLICATION_JSON));
			}
			if (!this.headers.containsKey(ACCEPT_HEADER)) {
				this.headers.put(ACCEPT_HEADER, new Header(ACCEPT_HEADER, APPLICATION_GRAPHQL_RESPONSE_JSON));
			}
			return this.tester.executeAndThen(this);
		}

		/**
		 * Executes configured request against GraphQL APi and returns response with validation methods.
		 */
		public ValidatableResponse executeAndThen(int statusCode, @Nonnull Matcher<?> errorsMatcher) {
			return executeAndThen()
				.statusCode(statusCode)
				.body("errors", errorsMatcher);
		}

		/**
		 * Executes configured request against GraphQL API, validates that status code is 200 and no GraphQL errors
		 * came, and returns response with validation methods.
		 */
		public ValidatableResponse executeAndExpectOkAndThen() {
			return executeAndThen(200, nullValue());
		}

		/**
		 * Executes configured request against GraphQL API, validates that status code is 200 and that there are any
		 * GraphQL errors, and returns response with validation methods.
		 */
		public ValidatableResponse executeAndExpectErrorsAndThen() {
			return executeAndThen(200, hasSize(greaterThan(0)));
		}
	}

	@RequiredArgsConstructor
	@Slf4j
	private static class WebSocketSubscriber implements Subscriber<WebSocketFrame> {

		@Nonnull
		private final List<String> receivedEvents;

		@Override
		public void onSubscribe(Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(WebSocketFrame webSocketFrame) {
			if (webSocketFrame.type() == WebSocketFrameType.TEXT) {
				this.receivedEvents.add(webSocketFrame.text());
			} else {
				log.warn("Non-text frame type: {}", webSocketFrame.type());
			}
		}

		@Override
		public void onError(Throwable throwable) {
			log.error("WebSocket subscriber error", throwable);
		}

		@Override
		public void onComplete() {
			log.info("WebSocket subscriber completed");
		}
	}
}
