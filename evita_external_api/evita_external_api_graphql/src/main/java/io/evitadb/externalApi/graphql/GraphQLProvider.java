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

package io.evitadb.externalApi.graphql;

import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.utils.NetworkUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Predicate;

import static io.evitadb.externalApi.graphql.io.GraphQLRouter.SYSTEM_PREFIX;

/**
 * Descriptor of external API provider that provides GraphQL API.
 *
 * @see GraphQLProviderRegistrar
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class GraphQLProvider implements ExternalApiProvider<GraphQLConfig> {

    public static final String CODE = "graphQL";

    @Nonnull
    @Getter
    private final GraphQLConfig configuration;
    @Nonnull
    private final GraphQLManager graphQLManager;

    /**
     * Contains url that was at least once found reachable.
     */
    private String reachableUrl;

    @Nonnull
    @Override
    public String getCode() {
        return CODE;
    }

	@Nullable
	@Override
	public HttpHandler getApiHandler() {
		return graphQLManager.getGraphQLRouter();
	}

	@Override
	public void afterAllInitialized() {
		graphQLManager.emitObservabilityEvents();
	}

	@Override
    public boolean isReady() {
        final Predicate<String> isReady = url -> {
            final Optional<String> post = NetworkUtils.fetchContent(url, "POST", "application/json", "{\"query\":\"{liveness}\"}");
            return post.map(content -> content.contains("true")).orElse(false);
        };
        final String[] baseUrls = this.configuration.getBaseUrls(configuration.getExposedHost());
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
