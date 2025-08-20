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

package io.evitadb.externalApi.graphql;

import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.utils.NetworkUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.function.Predicate;

import static io.evitadb.externalApi.graphql.io.GraphQLRouter.SYSTEM_PREFIX;

/**
 * Descriptor of external API provider that provides GraphQL API.
 *
 * @see GraphQLProviderRegistrar
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GraphQLProvider implements ExternalApiProvider<GraphQLOptions> {

    public static final String CODE = "graphQL";


	@Nonnull
    @Getter
    private final GraphQLOptions configuration;

	@Nonnull
    private final GraphQLManager graphQLManager;

	/**
	 * Timeout taken from {@link ApiOptions#requestTimeoutInMillis()} that will be used in {@link #isReady()}
	 * method.
	 */
	private final long requestTimeout;

    /**
     * Contains url that was at least once found reachable.
     */
    private String reachableUrl;

	public GraphQLProvider(
		@Nonnull GraphQLOptions configuration,
		@Nonnull GraphQLManager graphQLManager,
		long requestTimeoutInMillis
	) {
		this.configuration = configuration;
		this.graphQLManager = graphQLManager;
		this.requestTimeout = requestTimeoutInMillis;
	}

	@Nonnull
    @Override
    public String getCode() {
        return CODE;
    }

	@Nonnull
	@Override
	public HttpServiceDefinition[] getHttpServiceDefinitions() {
		return new HttpServiceDefinition[] {
			new HttpServiceDefinition(this.graphQLManager.getGraphQLRouter(), PathHandlingMode.DYNAMIC_PATH_HANDLING)
		};
	}

	@Override
	public void afterAllInitialized() {
		this.graphQLManager.emitObservabilityEvents();
	}

	@Override
    public boolean isReady() {
        final Predicate<String> isReady = url -> {
	        final ReadinessEvent readinessEvent = new ReadinessEvent(CODE, Prospective.CLIENT);
			final Optional<String> post = NetworkUtils.fetchContent(
				url,
				"POST",
				"application/json",
				"{\"query\":\"{liveness}\"}",
				this.requestTimeout,
				error -> {
					log.error("Error while checking readiness of GraphQL API: {}", error);
					readinessEvent.finish(Result.ERROR);
				},
				timeouted -> {
					log.error("{}", timeouted);
					readinessEvent.finish(Result.TIMEOUT);
				}
			);
			final Boolean result = post.map(content -> content.contains("true")).orElse(false);
			if (result) {
				readinessEvent.finish(Result.READY);
			}
			return result;
        };
        final String[] baseUrls = this.configuration.getBaseUrls();
        if (this.reachableUrl == null) {
	        for (String baseUrl : baseUrls) {
		        final String url = baseUrl + SYSTEM_PREFIX;
		        if (isReady.test(url)) {
			        this.reachableUrl = url;
			        return true;
		        }
	        }
            return false;
        } else {
            return isReady.test(this.reachableUrl);
        }
    }

}
