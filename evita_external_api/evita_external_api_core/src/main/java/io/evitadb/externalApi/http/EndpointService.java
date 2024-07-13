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

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import io.evitadb.utils.Assert;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Base implementation of {@link HttpService} for handling endpoints logic with content negotiation, request and response
 * bodies serialization and so on.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@Slf4j
public abstract class EndpointService<C extends EndpointExecutionContext> implements HttpService {
	private static final int STREAM_CHUNK_SIZE = 8192;
	private static final String CONTENT_TYPE_CHARSET = "; charset=UTF-8";

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) {
		final C executionContext = createExecutionContext(req);
		validateRequest(req);

		executionContext.provideRequestBodyContentType(resolveRequestBodyContentType(executionContext).orElse(null));
		executionContext.providePreferredResponseContentType(resolvePreferredResponseContentType(executionContext).orElse(null));

		beforeRequestHandled(executionContext);
		return HttpResponse.of(Objects.requireNonNull(doHandleRequest(executionContext)
			.thenApply(response -> {
				try {
					afterRequestHandled(executionContext, response);

					if (response instanceof NotFoundEndpointResponse) {
						throw new HttpExchangeException(HttpStatus.NOT_FOUND.code(), "Requested resource wasn't found.");
					} else if (response instanceof SuccessEndpointResponse successResponse) {
						final Object result = successResponse.getResult();
						if (result == null) {
							return HttpResponse.builder()
								.status(HttpStatus.NO_CONTENT)
								.build();
						} else {
							final HttpResponseWriter responseWriter = HttpResponse.streaming();
							ctx.addAdditionalResponseHeader(HttpHeaderNames.CONTENT_TYPE, executionContext.preferredResponseContentType() + CONTENT_TYPE_CHARSET);
							responseWriter.write(ResponseHeaders.of(HttpStatus.OK));
							writeResponse(executionContext, responseWriter, result, ctx.eventLoop());
							if (responseWriter.isOpen()) {
								responseWriter.close();
							}
							return responseWriter;
						}
					} else {
						throw createInternalError("Unsupported response `" + response.getClass().getName() + "`.");
					}
				} catch (Exception e) {
					executionContext.notifyError(e);
					throw e;
				}
			}).whenComplete((response, throwable) -> {
				executionContext.close();
			})
		));
	}

	/**
	 * Creates new instance of endpoint execution context for given HTTP request.
	 */
	@Nonnull
	protected abstract C createExecutionContext(@Nonnull HttpRequest httpRequest);

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
	protected abstract CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull C executionContext);

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


	protected void validateRequest(@Nonnull HttpRequest exchange) {
		if (!hasSupportedHttpMethod(exchange)) {
			throw new HttpExchangeException(
				HttpStatus.METHOD_NOT_ALLOWED.code(),
				"Supported methods are " + getSupportedHttpMethods().stream().map(it -> "`" + it + "`").collect(Collectors.joining(", ")) + "."
			);
		}
	}

	private boolean hasSupportedHttpMethod(@Nonnull HttpRequest request) {
		return getSupportedHttpMethods().contains(request.method().toString());
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

		final String bodyContentType = executionContext.httpRequest().headers().contentType().toString();
		Assert.isTrue(
			bodyContentType != null &&
				getSupportedRequestContentTypes().stream().anyMatch(bodyContentType::startsWith),
			() -> new HttpExchangeException(
				HttpStatus.UNSUPPORTED_MEDIA_TYPE.code(),
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
			HttpStatus.NOT_ACCEPTABLE.code(),
			"Supported response body media types are " + getSupportedResponseContentTypes().stream().map(it -> "`" + it + "`").collect(Collectors.joining(", ")) + "."
		);
	}

	@Nullable
	private Set<String> parseAcceptHeaders(@Nonnull C executionContext) {
		final Set<String> acceptHeaders = executionContext.httpRequest().headers().accept()
			.stream()
			.map(MediaType::toString)
			.collect(Collectors.toUnmodifiableSet());
		return acceptHeaders.isEmpty() ? null : acceptHeaders;
	}

	/**
	 * Reads request body into raw string.
	 */
	@Nonnull
	protected CompletableFuture<String> readRawRequestBody(@Nonnull C executionContext) {
		Assert.isPremiseValid(
			!getSupportedRequestContentTypes().isEmpty(),
			() -> createInternalError("Handler doesn't support reading of request body.")
		);

		final String bodyContentType = executionContext.httpRequest().contentType().toString();
		final Charset bodyCharset = Arrays.stream(bodyContentType.split(";"))
			.map(String::trim)
			.filter(part -> part.startsWith("charset"))
			.findFirst()
			.map(charsetPart -> {
				final String[] charsetParts = charsetPart.split("=");
				if (charsetParts.length != 2) {
					throw new HttpExchangeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE.code(), "Charset has invalid format");
				}
				return charsetParts[1].trim();
			})
			.map(charsetName -> {
				try {
					return Charset.forName(charsetName);
				} catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
					throw new HttpExchangeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE.code(), "Unsupported charset.");
				}
			})
			.orElse(StandardCharsets.UTF_8);

		return executionContext.httpRequest()
			.aggregate()
			.thenApply(r -> {
				try (HttpData data = r.content()) {
					try (InputStream inputStream = data.toInputStream()) {
						final byte[] buffer = new byte[EndpointService.STREAM_CHUNK_SIZE];
						final StringBuilder stringBuilder = new StringBuilder(64);
						int bytesRead;
						while ((bytesRead = inputStream.read(buffer)) != -1) {
							stringBuilder.append(new String(buffer, 0, bytesRead, bodyCharset));
						}
						return stringBuilder.toString();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			});
	}

	/**
	 * Tries to parse input request body JSON into data class.
	 */
	@Nonnull
	protected <T> CompletableFuture<T> parseRequestBody(@Nonnull C executionContext, @Nonnull Class<T> dataClass) {
		throw createInternalError("Cannot parse request body because handler doesn't support it.");
	}

	/**
	 * Serializes a result object into the preferred media type.
	 *
	 * @param executionContext      endpoint exchange
	 * @param responseWriter response writer to write the response to
	 * @param result         result data from handler to serialize to the response
	 * @param eventExecutor event executor to schedule response writing
	 */
	protected void writeResponse(@Nonnull C executionContext, @Nonnull HttpResponseWriter responseWriter, @Nonnull Object result, @Nonnull EventLoop eventExecutor) {
		throw createInternalError("Cannot serialize response body because handler doesn't support it.");
	}

}
