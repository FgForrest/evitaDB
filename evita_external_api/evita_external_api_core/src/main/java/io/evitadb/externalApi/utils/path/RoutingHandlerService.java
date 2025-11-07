/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.utils.path;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.externalApi.utils.MethodNotAllowedService;
import io.evitadb.externalApi.utils.NotFoundService;
import io.evitadb.externalApi.utils.NotFoundWebSocketHandler;
import io.evitadb.externalApi.utils.path.routing.CopyOnWriteMap;
import io.evitadb.externalApi.utils.path.routing.PathHandlerDescriptor;
import io.evitadb.externalApi.utils.path.routing.PathTemplate;
import io.evitadb.externalApi.utils.path.routing.PathTemplateMatcher;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.externalApi.http.AdditionalHttpHeaderNames.INTERNAL_HEADER_PREFIX;

/**
 * Adapted class from `io.undertow.server.RoutingHandler` to be used with Armeria.
 *
 * A Handler that handles the common case of pathHandlingMode via path template and method name.
 *
 * @author Stuart Douglas
 */
@Slf4j
public class RoutingHandlerService implements HttpService, WebSocketHandler {

	// Matcher objects grouped by http methods.
	private final Map<HttpMethod, PathTemplateMatcher<PathHandlerDescriptor>> matches = new CopyOnWriteMap<>();
	// Matcher used to find if this instance contains matches for any http method for a path.
	// This matcher is used to report if this instance can match a path for one of the http methods.
	private final PathTemplateMatcher<PathHandlerDescriptor> allMethodsMatcher = new PathTemplateMatcher<>();

	// Service called when no match was found and invalid method handler can't be invoked.
	private volatile HttpService fallbackHttpService = new NotFoundService();
	private volatile WebSocketHandler fallbackWebSocketHandler = new NotFoundWebSocketHandler();
	// Handler called when this instance can not match the http method but can match another http method.
	// For example: For an exchange the POST method is not matched by this instance but at least one http method is
	// matched for the same exchange.
	// If this handler is null the fallbackHandler will be used.
	private volatile HttpService invalidMethodHandler = new MethodNotAllowedService();

	// If this is true then path matches will be added to the query parameters for easy access by later handlers.
	private final boolean rewriteQueryParameters;

	public RoutingHandlerService(boolean rewriteQueryParameters) {
		this.rewriteQueryParameters = rewriteQueryParameters;
	}

	public RoutingHandlerService() {
		this.rewriteQueryParameters = true;
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		PathTemplateMatcher<PathHandlerDescriptor> matcher = this.matches.get(req.method());
		if (matcher == null) {
			return handleNoMatch(ctx, req);
		}
		final String path = URLUtils.normalizeSlashes(req.uri().getPath());
		PathTemplateMatcher.PathMatchResult<PathHandlerDescriptor> match = matcher.match(path);
		if (match == null || !match.getValue().isHttpService()) {
			return handleNoMatch(ctx, req);
		}

		final RequestHeadersBuilder headersBuilder = req.headers().toBuilder();

		if (this.rewriteQueryParameters) {
			QueryParams.fromQueryString(ctx.query()).forEach((key, value) -> headersBuilder.add(INTERNAL_HEADER_PREFIX + key, value));
			match.getParameters().forEach((key, value) -> headersBuilder.add(INTERNAL_HEADER_PREFIX + key, value));
		}

		final HttpRequest newRequest = req.withHeaders(headersBuilder.build());
		if (match.getValue() != null) {
			return match.getValue().asHttpService().serve(ctx, newRequest);
		} else {
			return this.fallbackHttpService.serve(ctx, newRequest);
		}
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		PathTemplateMatcher<PathHandlerDescriptor> matcher;
		if (ctx.sessionProtocol().isExplicitHttp1()) {
			matcher = this.matches.get(HttpMethod.GET);
		} else if (ctx.sessionProtocol().isExplicitHttp2()) {
			matcher = this.matches.get(HttpMethod.CONNECT);
		} else {
			log.debug("WebSocket connection with unsupported protocol: {}", ctx.sessionProtocol());
			return handleNoMatch(ctx, in);
		}
		if (matcher == null) {
			return handleNoMatch(ctx, in);
		}
		final String path = URLUtils.normalizeSlashes(in.path());
		PathTemplateMatcher.PathMatchResult<PathHandlerDescriptor> match = matcher.match(path);
		if (match == null || !match.getValue().isWebSocketHandler()) {
			return handleNoMatch(ctx, in);
		}

		if (match.getValue() != null) {
			return match.getValue().asWebSocketHandler().handle(ctx, in);
		} else {
			return this.fallbackWebSocketHandler.handle(ctx, in);
		}
	}

	/**
	 * Handles the case in with a match was not found for the http method but might exist for another http method.
	 * For example: POST not matched for a path but at least one match exists for same path.
	 *
	 * @param ctx The context of the request.
	 * @param req The request that was not matched.
	 * @throws Exception
	 */
	@Nonnull
	private HttpResponse handleNoMatch(final @Nonnull ServiceRequestContext ctx, final @Nonnull HttpRequest req) throws Exception {
		// if invalidMethodHandler is null we fail fast without matching with allMethodsMatcher
		if (this.invalidMethodHandler != null && this.allMethodsMatcher.match(req.method().toString()) != null) {
			return this.invalidMethodHandler.serve(ctx, req);
		}
		return this.fallbackHttpService.serve(ctx, req);
	}

	/**
	 * Handles the case in with a match was not found for the http method but might exist for another http method.
	 * For example: POST not matched for a path but at least one match exists for same path.
	 *
	 * @param ctx The context of the request.
	 * @param in The request that was not matched.
	 */
	private WebSocket handleNoMatch(final @Nonnull ServiceRequestContext ctx, final @Nonnull RoutableWebSocket in) {
		return this.fallbackWebSocketHandler.handle(ctx, in);
	}

	@Nonnull
	public synchronized RoutingHandlerService add(@Nonnull final String method, @Nonnull final String template, @Nonnull HttpService handler) {
		final HttpMethod parsedMethod = HttpMethod.tryParse(method);
		Assert.isPremiseValid(parsedMethod != null, "Invalid method: " + method);
		return add(parsedMethod, template, handler);
	}

	@Nonnull
	public synchronized RoutingHandlerService add(@Nonnull HttpMethod method, @Nonnull String template, @Nonnull HttpService handler) {
		PathTemplateMatcher<PathHandlerDescriptor> matcher = this.matches.get(method);
		if (matcher == null) {
			this.matches.put(method, matcher = new PathTemplateMatcher<>());
		}
		PathHandlerDescriptor res = matcher.get(template);
		if (res == null) {
			matcher.add(template, new PathHandlerDescriptor(handler));
		}
		if (this.allMethodsMatcher.match(template) == null) {
			this.allMethodsMatcher.add(template, new PathHandlerDescriptor(handler));
		}

		// automatic support for WebSockets
		if (HttpMethod.GET.equals(method) && handler instanceof WebSocketHandler) {
			addWebSocketHandler(HttpMethod.CONNECT, template, (WebSocketHandler) handler);
			// GET method gets registered automatically by the above code because the handler is common for both HTTP and WebSocket
		}

		return this;
	}

	@Nonnull
	public synchronized RoutingHandlerService get(@Nonnull final String template, @Nonnull HttpService handler) {
		return add(HttpMethod.GET, template, handler);
	}

	@Nonnull
	public synchronized RoutingHandlerService post(@Nonnull final String template, @Nonnull HttpService handler) {
		return add(HttpMethod.POST, template, handler);
	}

	@Nonnull
	public synchronized RoutingHandlerService put(@Nonnull final String template, @Nonnull HttpService handler) {
		return add(HttpMethod.PUT, template, handler);
	}

	@Nonnull
	public synchronized RoutingHandlerService delete(@Nonnull final String template, @Nonnull HttpService handler) {
		return add(HttpMethod.DELETE, template, handler);
	}

	@Nonnull
	public synchronized RoutingHandlerService add(@Nonnull final String template, @Nonnull final WebSocketHandler handler) {
		// for connecting WebSockets using HTTP/1
		addWebSocketHandler(HttpMethod.GET, template, handler);
		// for connecting WebSockets using HTTP/2
		addWebSocketHandler(HttpMethod.CONNECT, template, handler);
		return this;
	}

	private synchronized void addWebSocketHandler(@Nonnull HttpMethod method, @Nonnull String template, @Nonnull WebSocketHandler handler) {
		PathTemplateMatcher<PathHandlerDescriptor> matcher = this.matches.get(method);
		if (matcher == null) {
			this.matches.put(method, matcher = new PathTemplateMatcher<>());
		}
		PathHandlerDescriptor res = matcher.get(template);
		if (res == null) {
			matcher.add(template, new PathHandlerDescriptor(handler));
		}
		if (this.allMethodsMatcher.match(template) == null) {
			this.allMethodsMatcher.add(template, new PathHandlerDescriptor(handler));
		}
	}

	@Nonnull
	public synchronized RoutingHandlerService addAll(@Nonnull RoutingHandlerService routingHandler) {
		for (Entry<HttpMethod, PathTemplateMatcher<PathHandlerDescriptor>> entry : routingHandler.getMatches().entrySet()) {
			HttpMethod method = entry.getKey();
			PathTemplateMatcher<PathHandlerDescriptor> matcher = this.matches.get(method);
			if (matcher == null) {
				this.matches.put(method, matcher = new PathTemplateMatcher<>());
			}
			matcher.addAll(entry.getValue());
			// If we use allMethodsMatcher.addAll() we can have duplicate
			// PathTemplates which we want to ignore here so it does not crash.
			for (PathTemplate template : entry.getValue().getPathTemplates()) {
				if (this.allMethodsMatcher.match(template.getTemplateString()) == null) {
					this.allMethodsMatcher.add(template, null);
				}
			}
		}
		return this;
	}

	/**
	 *
	 * Removes the specified route from the handler
	 *
	 * @param method The method to remove
	 * @param path the path tempate to remove
	 * @return this handler
	 */
	@Nonnull
	public RoutingHandlerService remove(@Nonnull HttpMethod method, @Nonnull String path) {
		PathTemplateMatcher<PathHandlerDescriptor> handler = this.matches.get(method);
		if(handler != null) {
			handler.remove(path);
		}
		// remove other WebSocket handlers as well if present
		if (HttpMethod.GET.equals(method)) {
			PathTemplateMatcher<PathHandlerDescriptor> webSocketHandler = this.matches.get(HttpMethod.CONNECT);
			if(webSocketHandler != null && webSocketHandler.get(path).isWebSocketHandler()) {
				webSocketHandler.remove(path);
			}
		}
		return this;
	}


	/**
	 *
	 * Removes the specified route from the handler
	 *
	 * @param path the path tempate to remove
	 * @return this handler
	 */
	public RoutingHandlerService remove(String path) {
		this.allMethodsMatcher.remove(path);
		return this;
	}

	Map<HttpMethod, PathTemplateMatcher<PathHandlerDescriptor>> getMatches() {
		return this.matches;
	}

	/**
	 * @return Handler called when no match was found and invalid method handler can't be invoked.
	 */
	public HttpService getFallbackHttpService() {
		return this.fallbackHttpService;
	}

	/**
	 * @param fallbackHttpService Handler that will be called when no match was found and invalid method handler can't be
	 * invoked.
	 * @return This instance.
	 */
	public RoutingHandlerService setFallbackHttpService(HttpService fallbackHttpService) {
		this.fallbackHttpService = fallbackHttpService;
		return this;
	}

	/**
	 * @return Handler called when this instance can not match the http method but can match another http method.
	 */
	public HttpService getInvalidMethodHandler() {
		return this.invalidMethodHandler;
	}

	/**
	 * Sets the handler called when this instance can not match the http method but can match another http method.
	 * For example: For an exchange the POST method is not matched by this instance but at least one http method matched
	 * for the exchange.
	 * If this handler is null the fallbackHandler will be used.
	 *
	 * @param invalidMethodHandler Handler that will be called when this instance can not match the http method but can
	 * match another http method.
	 * @return This instance.
	 */
	public RoutingHandlerService setInvalidMethodHandler(HttpService invalidMethodHandler) {
		this.invalidMethodHandler = invalidMethodHandler;
		return this;
	}

}
