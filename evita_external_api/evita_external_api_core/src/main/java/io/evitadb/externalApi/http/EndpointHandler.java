/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base implementation of {@link HttpHandler} for handling endpoints logic with content negotiation, request and response
 * bodies serialization and so on.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class EndpointHandler<C extends EndpointExecutionContext> implements HttpHandler {

	private static final String CONTENT_TYPE_CHARSET = "; charset=UTF-8";

	@Override
	public void handleRequest(HttpServerExchange serverExchange) {
		final C executionContext = createExecutionContext(serverExchange);
		try {
			validateRequest(serverExchange);

			executionContext.provideRequestBodyContentType(resolveRequestBodyContentType(executionContext).orElse(null));
			executionContext.providePreferredResponseContentType(resolvePreferredResponseContentType(executionContext).orElse(null));

			beforeRequestHandled(executionContext);
			final EndpointResponse response = doHandleRequest(executionContext);
			afterRequestHandled(executionContext, response);

			if (response instanceof NotFoundEndpointResponse) {
				throw new HttpExchangeException(StatusCodes.NOT_FOUND, "Requested resource wasn't found.");
			} else if (response instanceof SuccessEndpointResponse successResponse) {
				final Object result = successResponse.getResult();
				if (result == null) {
					sendEmptySuccessResponse(executionContext);
				} else {
					sendSuccessResponseWithBody(executionContext, result);
				}
			} else {
				throw createInternalError("Unsupported response `" + response.getClass().getName() + "`.");
			}
		} catch (Exception e) {
			executionContext.notifyError(e);
			throw e;
		} finally {
			executionContext.close();
		}
	}

	/**
	 * Creates new instance of endpoint execution context for given HTTP server exchange.
	 */
	@Nonnull
	protected abstract C createExecutionContext(@Nonnull HttpServerExchange serverExchange);

	/**
	 * Hook method called before actual endpoint handling logic is executed. Default implementation does nothing.
	 */
	protected void beforeRequestHandled(@Nonnull C executionContext) {
		// default implementation does nothing
	}

	/**
	 * Hook method called after actual endpoint handling logic is executed. Default implementation does nothing.
	 */
	protected void afterRequestHandled(@Nonnull C executionContext, @Nonnull EndpointResponse response) {
		// default implementation does nothing
	}

	/**
	 * Actual endpoint logic.
	 */
	@Nonnull
	protected abstract EndpointResponse doHandleRequest(@Nonnull C executionContext);

	@Nonnull
	protected abstract <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message);

	@Nonnull
	protected abstract <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message, @Nonnull Throwable cause);

	@Nonnull
	protected abstract <T extends ExternalApiInvalidUsageException> T createInvalidUsageException(@Nonnull String message);

	/**
	 * Defines which HTTP methods can this particular endpoint process.
	 */
	@Nonnull
	public abstract Set<String> getSupportedHttpMethods();

	/**
	 * Defines which mime types are supported for request body.
	 * By default, no mime types are supported.
	 */
	@Nonnull
	public Set<String> getSupportedRequestContentTypes() {
		return Set.of();
	}

	/**
	 * Defines which mime types are supported for response body.
	 * By default, no mime types are supported.
	 * Note: order of mime types is important, it defines priority of mime types by which preferred response mime type is selected.
	 */
	@Nonnull
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return new LinkedHashSet<>(0);
	}


	protected void validateRequest(@Nonnull HttpServerExchange exchange) {
		if (!hasSupportedHttpMethod(exchange)) {
			throw new HttpExchangeException(
				StatusCodes.METHOD_NOT_ALLOWED,
				"Supported methods are " + getSupportedHttpMethods().stream().map(it -> "`" + it + "`").collect(Collectors.joining(", ")) + "."
			);
		}
	}

	private boolean hasSupportedHttpMethod(@Nonnull HttpServerExchange exchange) {
		return getSupportedHttpMethods().contains(exchange.getRequestMethod().toString());
	}

	/**
	 * Validates that request body (if any) has supported media type and if so, returns it.
	 */
	@Nonnull
	private Optional<String> resolveRequestBodyContentType(@Nonnull C executionContext) {
		if (getSupportedRequestContentTypes().isEmpty()) {
			// we can ignore all content type headers because we don't accept any request body
			return Optional.empty();
		}

		final String bodyContentType = executionContext.serverExchange().getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
		Assert.isTrue(
			bodyContentType != null &&
				getSupportedRequestContentTypes().stream().anyMatch(bodyContentType::startsWith),
			() -> new HttpExchangeException(
				StatusCodes.UNSUPPORTED_MEDIA_TYPE,
				"Supported request body media types are " + getSupportedRequestContentTypes().stream().map(it -> "`" + it + "`").collect(Collectors.joining(", ")) + "."
			)
		);

		return Optional.of(bodyContentType);
	}

	/**
	 * Validates that the request accepts at least one of the supported response media types and if so, picks the most
	 * preferred one by order of {@link #getSupportedResponseContentTypes().
	 */
	@Nonnull
	private Optional<String> resolvePreferredResponseContentType(@Nonnull C executionContext) {
		if (getSupportedResponseContentTypes().isEmpty()) {
			// we can ignore any accept headers because we will not return any body
			return Optional.empty();
		}

		final Set<String> acceptHeaders = parseAcceptHeaders(executionContext);
		if (acceptHeaders == null) {
			// missing accept header means that client supports all media types
			return Optional.of(getSupportedResponseContentTypes().iterator().next());
		}

		for (String supportedMediaType : getSupportedResponseContentTypes()) {
			if (acceptHeaders.stream().anyMatch(it -> it.contains(supportedMediaType))) {
				return Optional.of(supportedMediaType);
			}
		}

		if (acceptHeaders.stream().anyMatch(it -> it.contains(MimeTypes.ALL))) {
			// no exact preferred media type found, but we can use this fallback at least
			return Optional.of(getSupportedResponseContentTypes().iterator().next());
		}

		throw new HttpExchangeException(
			StatusCodes.NOT_ACCEPTABLE,
			"Supported response body media types are " + getSupportedResponseContentTypes().stream().map(it -> "`" + it + "`").collect(Collectors.joining(", ")) + "."
		);
	}

	@Nullable
	private Set<String> parseAcceptHeaders(@Nonnull C executionContext) {
		final HeaderValues acceptHeaders = executionContext.serverExchange().getRequestHeaders().get(Headers.ACCEPT);
		if (acceptHeaders == null) {
			return null;
		}
		return acceptHeaders.stream()
			.flatMap(hv -> Arrays.stream(hv.split(",")))
			.map(String::strip)
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Reads request body into raw string.
	 */
	@Nonnull
	protected String readRawRequestBody(@Nonnull C executionContext) {
		Assert.isPremiseValid(
			!getSupportedRequestContentTypes().isEmpty(),
			() -> createInternalError("Handler doesn't support reading of request body.")
		);

		final String bodyContentType = executionContext.requestBodyContentType();
		final Charset bodyCharset = Arrays.stream(bodyContentType.split(";"))
			.map(String::trim)
			.filter(part -> part.startsWith("charset"))
			.findFirst()
			.map(charsetPart -> {
				final String[] charsetParts = charsetPart.split("=");
				if (charsetParts.length != 2) {
					throw new HttpExchangeException(StatusCodes.UNSUPPORTED_MEDIA_TYPE, "Charset has invalid format");
				}
				return charsetParts[1].trim();
			})
			.map(charsetName -> {
				try {
					return Charset.forName(charsetName);
				} catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
					throw new HttpExchangeException(StatusCodes.UNSUPPORTED_MEDIA_TYPE, "Unsupported charset.");
				}
			})
			.orElse(StandardCharsets.UTF_8);

		final String body;
		try (final InputStream is = executionContext.serverExchange().getInputStream();
		     final InputStreamReader isr = new InputStreamReader(is, bodyCharset);
		     final BufferedReader bf = new BufferedReader(isr)) {
			body = bf.lines().collect(Collectors.joining("\n"));
		} catch (IOException e) {
			throw createInternalError("Could not read request body: ", e);
		}

		Assert.isTrue(
			!body.trim().isEmpty(),
			() -> createInvalidUsageException("Request's body contains no data.")
		);
		return body;
	}

	/**
	 * Tries to parse input request body JSON into data class.
	 */
	@Nonnull
	protected <T> T parseRequestBody(@Nonnull C executionContext, @Nonnull Class<T> dataClass) {
		throw createInternalError("Cannot parse request body because handler doesn't support it.");
	}

	/**
	 * Serializes a result object into the preferred media type.
	 *
	 * @param executionContext      endpoint exchange
	 * @param outputStream  output stream to write serialized data to
	 * @param result        result data from handler to write to response
	 */
	protected void writeResult(@Nonnull C executionContext, @Nonnull OutputStream outputStream, @Nonnull Object result) {
		throw createInternalError("Cannot serialize response body because handler doesn't support it.");
	}

	private void sendEmptySuccessResponse(@Nonnull C executionContext) {
		executionContext.serverExchange().setStatusCode(StatusCodes.NO_CONTENT);
		executionContext.serverExchange().endExchange();
	}

	private void sendSuccessResponseWithBody(@Nonnull C executionContext, @Nonnull Object result) {
		executionContext.serverExchange().setStatusCode(StatusCodes.OK);
		executionContext.serverExchange().getResponseHeaders().put(Headers.CONTENT_TYPE, executionContext.preferredResponseContentType() + CONTENT_TYPE_CHARSET);
		writeResult(executionContext, executionContext.serverExchange().getOutputStream(), result);
		executionContext.serverExchange().endExchange();
	}

}
