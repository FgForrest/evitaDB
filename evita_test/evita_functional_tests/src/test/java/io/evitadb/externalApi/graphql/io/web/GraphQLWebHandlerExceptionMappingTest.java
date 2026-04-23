/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.graphql.io.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import graphql.GraphQLException;
import graphql.execution.InputMapDefinesTooManyFieldsException;
import graphql.execution.NonNullableValueCoercedAsNullException;
import graphql.execution.UnknownOperationException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidUsageException;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@code mapToGraphQLException} method in {@link GraphQLWebHandler}. Uses reflection to access
 * the private method and verifies correct exception mapping for timeouts, user errors, internal errors, and
 * passthrough.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("GraphQLWebHandler - exception mapping")
class GraphQLWebHandlerExceptionMappingTest {

	private GraphQLWebHandler handler;

	@BeforeEach
	void setUp() {
		final Evita evita = mock(Evita.class);
		final ObjectMapper objectMapper = new ObjectMapper();
		final AtomicReference<GraphQL> graphQL = new AtomicReference<>(mock(GraphQL.class));
		final HeaderOptions headers = HeaderOptions.builder().build();

		// ExternalApiTracingContextProvider uses ServiceLoader; mock it to return a noop context
		try (
			MockedStatic<ExternalApiTracingContextProvider> provider =
				mockStatic(ExternalApiTracingContextProvider.class)
		) {
			@SuppressWarnings("unchecked")
			final ExternalApiTracingContext<Object> noopContext = mock(ExternalApiTracingContext.class);
			provider.when(
				() -> ExternalApiTracingContextProvider.getContext(any(), any())
			).thenReturn(noopContext);

			this.handler = new GraphQLWebHandler(evita, headers, objectMapper, GraphQLInstanceType.DATA, graphQL);
		}
	}

	@Nested
	@DisplayName("Timeout exception mapping")
	class TimeoutExceptionMapping {

		@Test
		@DisplayName("maps TimeoutException to 504 Gateway Timeout")
		void shouldMapTimeoutToGatewayTimeout() throws Exception {
			final RuntimeException result = invokeMapToGraphQLException(new TimeoutException("timed out"));

			assertInstanceOf(HttpExchangeException.class, result);
			final HttpExchangeException httpEx = (HttpExchangeException) result;
			assertEquals(504, httpEx.getStatusCode());
		}
	}

	@Nested
	@DisplayName("CompletionException unwrapping")
	class CompletionExceptionUnwrapping {

		@Test
		@DisplayName("unwraps CompletionException around Timeout")
		void shouldUnwrapCompletionException() throws Exception {
			final CompletionException wrapped = new CompletionException(new TimeoutException("wrapped timeout"));

			final RuntimeException result = invokeMapToGraphQLException(wrapped);

			assertInstanceOf(HttpExchangeException.class, result);
			final HttpExchangeException httpEx = (HttpExchangeException) result;
			assertEquals(504, httpEx.getStatusCode());
		}

		@Test
		@DisplayName("unwraps CompletionException for user errors")
		void shouldUnwrapCompletionExceptionForUserErrors() throws Exception {
			final CompletionException wrapped = new CompletionException(new CoercingSerializeException("bad"));

			final RuntimeException result = invokeMapToGraphQLException(wrapped);

			assertInstanceOf(GraphQLInvalidUsageException.class, result);
		}
	}

	@Nested
	@DisplayName("User error mapping")
	class UserErrorMapping {

		@Test
		@DisplayName("maps CoercingSerializeException to usage error")
		void shouldMapCoercingSerializeException() throws Exception {
			final RuntimeException result = invokeMapToGraphQLException(new CoercingSerializeException("bad ser"));

			assertInstanceOf(GraphQLInvalidUsageException.class, result);
		}

		@Test
		@DisplayName("maps CoercingParseValueException to usage error")
		void shouldMapCoercingParseValueException() throws Exception {
			final RuntimeException result = invokeMapToGraphQLException(new CoercingParseValueException("bad parse"));

			assertInstanceOf(GraphQLInvalidUsageException.class, result);
		}

		@Test
		@DisplayName("maps NonNullableValueCoercedAsNull to usage")
		void shouldMapNonNullableValueCoercedAsNull() throws Exception {
			// requires a GraphQLType argument
			final GraphQLScalarType dummyType = GraphQLScalarType.newScalar()
				.name("Dummy")
				.coercing(mock(graphql.schema.Coercing.class))
				.build();

			final RuntimeException result = invokeMapToGraphQLException(
				new NonNullableValueCoercedAsNullException(dummyType)
			);

			assertInstanceOf(GraphQLInvalidUsageException.class, result);
		}

		@Test
		@DisplayName("maps InputMapDefinesTooManyFields to usage")
		void shouldMapInputMapDefinesTooManyFields() throws Exception {
			// requires (GraphQLType, String) constructor
			final GraphQLScalarType dummyType = GraphQLScalarType.newScalar()
				.name("Dummy")
				.coercing(mock(graphql.schema.Coercing.class))
				.build();

			final RuntimeException result = invokeMapToGraphQLException(
				new InputMapDefinesTooManyFieldsException(dummyType, "too many")
			);

			assertInstanceOf(GraphQLInvalidUsageException.class, result);
		}

		@Test
		@DisplayName("maps UnknownOperationException to usage")
		void shouldMapUnknownOperationException() throws Exception {
			final RuntimeException result = invokeMapToGraphQLException(new UnknownOperationException("unknown op"));

			assertInstanceOf(GraphQLInvalidUsageException.class, result);
		}
	}

	@Nested
	@DisplayName("Subclass handling")
	class SubclassHandling {

		@Test
		@DisplayName("maps subclass of user error to invalid usage")
		void shouldMapSubclassOfUserErrorToInvalidUsage() throws Exception {
			// anonymous subclass of CoercingSerializeException
			final CoercingSerializeException subclass = new CoercingSerializeException("sub") {};

			final RuntimeException result = invokeMapToGraphQLException(subclass);

			assertInstanceOf(
				GraphQLInvalidUsageException.class, result,
				"Subclass of a user error exception should map to GraphQLInvalidUsageException"
			);
		}
	}

	@Nested
	@DisplayName("Generic GraphQL error mapping")
	class GenericGraphQLErrorMapping {

		@Test
		@DisplayName("maps GraphQLException to internal error")
		void shouldMapGraphQLExceptionToInternalError() throws Exception {
			final RuntimeException result = invokeMapToGraphQLException(new GraphQLException("generic gql error"));

			assertInstanceOf(GraphQLInternalError.class, result);
		}
	}

	@Nested
	@DisplayName("RuntimeException passthrough")
	class RuntimeExceptionPassthrough {

		@Test
		@DisplayName("passes through RuntimeException as-is")
		void shouldPassthroughRuntimeException() throws Exception {
			final IllegalArgumentException original = new IllegalArgumentException("bad arg");

			final RuntimeException result = invokeMapToGraphQLException(original);

			assertSame(original, result);
		}

		@Test
		@DisplayName("wraps checked exception in CompletionException")
		void shouldWrapCheckedException() throws Exception {
			final IOException checked = new IOException("io error");

			final RuntimeException result = invokeMapToGraphQLException(checked);

			assertInstanceOf(CompletionException.class, result);
			assertSame(checked, result.getCause());
		}
	}

	/**
	 * Invokes the private {@code mapToGraphQLException} method on the handler via reflection.
	 *
	 * @param cause the throwable to map
	 * @return the mapped RuntimeException
	 * @throws Exception if reflection fails
	 */
	@Nonnull
	private RuntimeException invokeMapToGraphQLException(@Nonnull Throwable cause) throws Exception {
		final Method method = GraphQLWebHandler.class.getDeclaredMethod("mapToGraphQLException", Throwable.class);
		method.setAccessible(true);
		return (RuntimeException) method.invoke(this.handler, cause);
	}
}
