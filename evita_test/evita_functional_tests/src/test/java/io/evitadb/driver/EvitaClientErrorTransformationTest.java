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

import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.exception.EvitaError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EvitaClient#transformStatusRuntimeException(StatusRuntimeException, Runnable)} — the point where
 * a gRPC status is turned back into an evitaDB exception on the client.
 *
 * The server encodes the error code into the status description as `errorCode + ": " + publicMessage`
 * (`GlobalExceptionHandlerInterceptor#createErrorStatus`). The driver used to prepend the status name before running
 * the recovery pattern over it, which made the pattern unmatchable and silently discarded every code that arrived —
 * the case {@link CodedDescription} pins down.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EvitaClient error transformation")
@Tag(DRIVER)
@Tag(OBSERVABILITY)
class EvitaClientErrorTransformationTest {
	/**
	 * A code in the `hash:hash:line` shape the server actually emits. Deliberately unlike anything
	 * {@link io.evitadb.exception.ErrorCodeResolver} would derive from a line of this test, so an assertion on it
	 * cannot pass by coincidence.
	 */
	private static final String SERVER_ERROR_CODE = "deadbeef:cafebabe:412";
	private static final String PUBLIC_MESSAGE = "Entity `Product` with primary key 1 not found.";

	@Nested
	@DisplayName("when the description carries a server error code")
	class CodedDescription {

		@Test
		@DisplayName("should recover the server's code from an INTERNAL status")
		void shouldRecoverCodeFromInternalStatus() {
			final RuntimeException result = transform(Code.INTERNAL, SERVER_ERROR_CODE + ": " + PUBLIC_MESSAGE);

			final GenericEvitaInternalError error = assertInstanceOf(GenericEvitaInternalError.class, result);
			assertEquals(SERVER_ERROR_CODE, error.getErrorCode());
		}

		@Test
		@DisplayName("should recover the server's code from an INVALID_ARGUMENT status")
		void shouldRecoverCodeFromInvalidArgumentStatus() {
			final RuntimeException result = transform(Code.INVALID_ARGUMENT, SERVER_ERROR_CODE + ": " + PUBLIC_MESSAGE);

			final EvitaInvalidUsageException error = assertInstanceOf(EvitaInvalidUsageException.class, result);
			assertEquals(SERVER_ERROR_CODE, error.getErrorCode());
		}

		@Test
		@DisplayName("should recover the server's code from a PERMISSION_DENIED status")
		void shouldRecoverCodeFromPermissionDeniedStatus() {
			final RuntimeException result = transform(Code.PERMISSION_DENIED, SERVER_ERROR_CODE + ": Access denied.");

			final EvitaInvalidUsageException error = assertInstanceOf(EvitaInvalidUsageException.class, result);
			assertEquals(SERVER_ERROR_CODE, error.getErrorCode());
		}

		@Test
		@DisplayName("should keep the server's public message without decorating it")
		void shouldKeepPublicMessageUndecorated() {
			final RuntimeException result = transform(Code.INTERNAL, SERVER_ERROR_CODE + ": " + PUBLIC_MESSAGE);

			final EvitaError error = assertInstanceOf(EvitaError.class, result);
			assertEquals(PUBLIC_MESSAGE, error.getPublicMessage());
			// neither the status name nor the raw code belongs in text shown to a human - both used to leak in,
			// because the whole description was handed to the exception constructor verbatim
			assertFalse(result.getMessage().contains(Code.INTERNAL.name()));
			assertFalse(result.getMessage().contains(SERVER_ERROR_CODE));
		}
	}

	@Nested
	@DisplayName("when the description carries no server error code")
	class UncodedDescription {

		@Test
		@DisplayName("should prefix the status name, which is then the only classification available")
		void shouldPrefixStatusName() {
			final RuntimeException result = transform(Code.INTERNAL, "connection reset by peer");

			assertInstanceOf(GenericEvitaInternalError.class, result);
			assertTrue(
				result.getMessage().contains("INTERNAL: connection reset by peer"),
				"Unexpected message: " + result.getMessage()
			);
		}

		@Test
		@DisplayName("should fall back to the bare status name when there is no description at all")
		void shouldFallBackToStatusName() {
			final RuntimeException result = transform(Code.UNKNOWN, null);

			final EvitaError error = assertInstanceOf(EvitaError.class, result);
			assertEquals(Code.UNKNOWN.name(), error.getPublicMessage());
		}

		@Test
		@DisplayName("should not mistake a two-segment prefix for an error code")
		void shouldNotMistakeTwoSegmentPrefixForCode() {
			// the pattern needs three colon-separated word runs; a message that merely happens to contain colons
			// must go down the uncoded path rather than have its text shredded into a bogus code
			final RuntimeException result = transform(Code.INTERNAL, "connect: timed out");

			assertTrue(
				result.getMessage().contains("INTERNAL: connect: timed out"),
				"Unexpected message: " + result.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("when the session is no longer authenticated")
	class Unauthenticated {

		@Test
		@DisplayName("should terminate the instance and run the callback")
		void shouldTerminateInstance() {
			final AtomicBoolean callbackRan = new AtomicBoolean();
			final RuntimeException result = EvitaClient.transformStatusRuntimeException(
				new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription(SERVER_ERROR_CODE + ": nope")),
				() -> callbackRan.set(true)
			);

			assertInstanceOf(InstanceTerminatedException.class, result);
			assertTrue(callbackRan.get(), "the unauthenticated callback must run before the exception is raised");
		}
	}

	/**
	 * Runs the transformation for a status built from the given code and description, with a callback that must not
	 * fire — every status other than `UNAUTHENTICATED` has to leave the session alone.
	 *
	 * @param statusCode  the gRPC status code the server closed the call with
	 * @param description the status description, may be `null` to model a status carrying none
	 * @return the exception the driver would raise towards the caller
	 */
	@Nonnull
	private static RuntimeException transform(@Nonnull Code statusCode, @Nullable String description) {
		final Status status = description == null ?
			statusCode.toStatus() : statusCode.toStatus().withDescription(description);
		return EvitaClient.transformStatusRuntimeException(
			new StatusRuntimeException(status),
			() -> {
				throw new AssertionError("the unauthenticated callback must not run for " + statusCode);
			}
		);
	}
}
