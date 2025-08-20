/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.evitadb.externalApi.utils.path;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.utils.MethodNotAllowedService;
import io.evitadb.externalApi.utils.NotFoundService;
import io.evitadb.externalApi.utils.path.routing.CopyOnWriteMap;
import io.evitadb.externalApi.utils.path.routing.PathTemplate;
import io.evitadb.externalApi.utils.path.routing.PathTemplateMatcher;

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
public class RoutingHandlerService implements HttpService {

	// Matcher objects grouped by http methods.
	private final Map<HttpMethod, PathTemplateMatcher<HttpService>> matches = new CopyOnWriteMap<>();
	// Matcher used to find if this instance contains matches for any http method for a path.
	// This matcher is used to report if this instance can match a path for one of the http methods.
	private final PathTemplateMatcher<HttpService> allMethodsMatcher = new PathTemplateMatcher<>();

	// Handler called when no match was found and invalid method handler can't be invoked.
	private volatile HttpService fallbackHandler = new NotFoundService();
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
		PathTemplateMatcher<HttpService> matcher = this.matches.get(req.method());
		if (matcher == null) {
			return handleNoMatch(ctx, req);
		}
		PathTemplateMatcher.PathMatchResult<HttpService> match = matcher.match(req.uri().getPath());
		if (match == null) {
			return handleNoMatch(ctx, req);
		}

		final RequestHeadersBuilder headersBuilder = req.headers().toBuilder();

		if (this.rewriteQueryParameters) {
			QueryParams.fromQueryString(ctx.query()).forEach((key, value) -> headersBuilder.add(INTERNAL_HEADER_PREFIX + key, value));
			match.getParameters().forEach((key, value) -> headersBuilder.add(INTERNAL_HEADER_PREFIX + key, value));
		}

		final HttpRequest newRequest = req.withHeaders(headersBuilder.build());
		if (match.getValue() != null) {
			return match.getValue().serve(ctx, newRequest);
		} else {
			return this.fallbackHandler.serve(ctx, newRequest);
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
	private HttpResponse handleNoMatch(final @Nonnull ServiceRequestContext ctx, final @Nonnull HttpRequest req) throws Exception {
		// if invalidMethodHandler is null we fail fast without matching with allMethodsMatcher
		if (this.invalidMethodHandler != null && this.allMethodsMatcher.match(req.method().toString()) != null) {
			return this.invalidMethodHandler.serve(ctx, req);
		}
		return this.fallbackHandler.serve(ctx, req);
	}

	public synchronized RoutingHandlerService add(final String method, final String template, HttpService handler) {
		return add(HttpMethod.tryParse(method), template, handler);
	}

	public synchronized RoutingHandlerService add(HttpMethod method, String template, HttpService handler) {
		PathTemplateMatcher<HttpService> matcher = this.matches.get(method);
		if (matcher == null) {
			this.matches.put(method, matcher = new PathTemplateMatcher<>());
		}
		HttpService res = matcher.get(template);
		if (res == null) {
			matcher.add(template, handler);
		}
		if (this.allMethodsMatcher.match(template) == null) {
			this.allMethodsMatcher.add(template, handler);
		}
		return this;
	}

	public synchronized RoutingHandlerService get(final String template, HttpService handler) {
		return add(HttpMethod.GET, template, handler);
	}

	public synchronized RoutingHandlerService post(final String template, HttpService handler) {
		return add(HttpMethod.POST, template, handler);
	}

	public synchronized RoutingHandlerService put(final String template, HttpService handler) {
		return add(HttpMethod.PUT, template, handler);
	}

	public synchronized RoutingHandlerService delete(final String template, HttpService handler) {
		return add(HttpMethod.DELETE, template, handler);
	}

	public synchronized RoutingHandlerService addAll(RoutingHandlerService routingHandler) {
		for (Entry<HttpMethod, PathTemplateMatcher<HttpService>> entry : routingHandler.getMatches().entrySet()) {
			HttpMethod method = entry.getKey();
			PathTemplateMatcher<HttpService> matcher = this.matches.get(method);
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
	public RoutingHandlerService remove(HttpMethod method, String path) {
		PathTemplateMatcher<HttpService> handler = this.matches.get(method);
		if(handler != null) {
			handler.remove(path);
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

	Map<HttpMethod, PathTemplateMatcher<HttpService>> getMatches() {
		return this.matches;
	}

	/**
	 * @return Handler called when no match was found and invalid method handler can't be invoked.
	 */
	public HttpService getFallbackHandler() {
		return this.fallbackHandler;
	}

	/**
	 * @param fallbackHandler Handler that will be called when no match was found and invalid method handler can't be
	 * invoked.
	 * @return This instance.
	 */
	public RoutingHandlerService setFallbackHandler(HttpService fallbackHandler) {
		this.fallbackHandler = fallbackHandler;
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
