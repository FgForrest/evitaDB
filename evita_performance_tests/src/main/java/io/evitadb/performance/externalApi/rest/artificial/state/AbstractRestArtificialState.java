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

package io.evitadb.performance.externalApi.rest.artificial.state;

import io.evitadb.api.query.Query;
import io.evitadb.performance.artificial.AbstractArtificialBenchmarkState;
import io.evitadb.test.client.query.rest.RestQueryConverter;
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import javax.annotation.Nonnull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.test.TestConstants.TEST_CATALOG;

/**
 * Common ancestor for thread-scoped state objects.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@State(Scope.Thread)
public abstract class AbstractRestArtificialState {

	/**
	 * Regex pattern to parse input request into URL and query (request body)
	 */
	private static final Pattern REQUEST_PATTERN = Pattern.compile("([A-Z]+)\\s((/[\\w\\-]+)+)(\\s+([.\\s\\S]+))?");
	@Nonnull private final RestQueryConverter queryConverter = new RestQueryConverter();

	/**
	 * HTTP method to use for executing the request.
	 */
	@Getter private String method;
	/**
	 * Resource url prepared for the measured invocation.
	 */
	@Getter private String resource;
	/**
	 * Request body prepared for the measured invocation.
	 */
	@Getter private String requestBody;

	protected void setRequest(@Nonnull String method, @Nonnull String resource) {
		this.method = method;
		this.resource = resource;
	}

	protected void setRequest(@Nonnull String method, @Nonnull String resource, @Nonnull String requestBody) {
		this.method = method;
		this.resource = resource;
		this.requestBody = requestBody;
	}

	protected void setRequest(@Nonnull AbstractArtificialBenchmarkState<?> benchmarkState, @Nonnull Query query) {
		final String request = this.queryConverter.convert(benchmarkState.getEvita(), TEST_CATALOG, query);

		final Matcher requestMatcher = REQUEST_PATTERN.matcher(request);
		Assert.isPremiseValid(requestMatcher.matches(), "Invalid request format.");
		this.method = requestMatcher.group(1);
		this.resource = requestMatcher.group(2);
		this.requestBody = requestMatcher.group(5);
	}
}
