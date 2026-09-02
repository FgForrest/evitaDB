/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.driver;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.retry.RetryDecision;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;

import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link EvitaClient#createRetryRule(boolean)} — the boundary between the always-active
 * "unprocessed" retry (a request Armeria can prove never reached the server, safe to replay regardless of
 * the {@code retry} configuration flag) and the broader, potentially-duplicating rule set that stays
 * behind that flag.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EvitaClient retry rule")
@Tag(DRIVER)
@Tag(MANAGEMENT)
class EvitaClientRetryRuleTest {
	private static final ClientRequestContext CONTEXT = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));

	@Nested
	@DisplayName("when retry is disabled (the default)")
	class RetryDisabled {
		private final RetryRule rule = EvitaClient.createRetryRule(false);

		@Test
		@DisplayName("should retry a request Armeria proves was never processed by the server")
		void shouldRetryUnprocessedRequest() {
			assertWillRetry(this.rule, UnprocessedRequestException.of(new IOException("connection refused")));
		}

		@Test
		@DisplayName("should not retry a mid-call transport abort that may have already reached the server")
		void shouldNotRetryAmbiguousTransportAbort() {
			assertWillNotRetry(this.rule, new StatusRuntimeException(Status.CANCELLED));
		}
	}

	@Nested
	@DisplayName("when retry is enabled")
	class RetryEnabled {
		private final RetryRule rule = EvitaClient.createRetryRule(true);

		@Test
		@DisplayName("should still retry a request Armeria proves was never processed by the server")
		void shouldRetryUnprocessedRequest() {
			assertWillRetry(this.rule, UnprocessedRequestException.of(new IOException("connection refused")));
		}
	}

	private static void assertWillRetry(@Nonnull RetryRule rule, @Nonnull Throwable cause) {
		final RetryDecision decision = rule.shouldRetry(CONTEXT, cause).toCompletableFuture().join();
		assertNotSame(RetryDecision.next(), decision, "expected a retry decision for " + cause);
		assertNotSame(RetryDecision.noRetry(), decision, "expected a retry decision for " + cause);
	}

	private static void assertWillNotRetry(@Nonnull RetryRule rule, @Nonnull Throwable cause) {
		final RetryDecision decision = rule.shouldRetry(CONTEXT, cause).toCompletableFuture().join();
		assertSame(RetryDecision.next(), decision, "expected no retry decision for " + cause);
	}
}
