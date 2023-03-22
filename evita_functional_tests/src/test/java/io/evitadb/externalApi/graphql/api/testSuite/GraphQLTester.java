/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.testSuite;

import io.evitadb.externalApi.graphql.io.GraphQLMimeTypes;
import io.evitadb.utils.StringUtils;
import io.restassured.http.Header;
import io.restassured.response.ValidatableResponse;
import io.undertow.util.Headers;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Simple tester utility for easier testing of GraphQL API. It uses REST Assured library as backend but test doesn't have
 * to configure each request with URL, headers, POST method and so on.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class GraphQLTester {

	private final String baseUrl;

	/**
	 * Test single request to GraphQL API.
	 */
	public Request test(@Nonnull String catalogName) {
		return new Request(this, catalogName);
	}

	@SneakyThrows
	private ValidatableResponse executeAndThen(@Nonnull Request request) {
		final Map<String, Object> body = Map.of(
			"query", request.getDocument(),
			"variables", request.getVariables()
		);

		return given()
				.relaxedHTTPSValidation()
				.headers(new io.restassured.http.Headers(new ArrayList<>(request.getHeaders().values())))
				.body(body)
				.log()
				.ifValidationFails().
			when()
				.post(baseUrl + "/" + StringUtils.toKebabCase(request.getCatalogName())).
			then()
				.log()
				.ifError();
	}

	@Getter(AccessLevel.PRIVATE)
	public static class Request {

		private final GraphQLTester tester;
		private final String catalogName;

		@Nonnull
		private String document;

		private final Map<String, Object> variables = new HashMap<>();

		private final Map<String, Header> headers = new HashMap<>();

		public Request(@Nonnull GraphQLTester tester, @Nonnull String catalogName) {
			this.tester = tester;
			this.catalogName = catalogName;
		}

		public Request document(@Nonnull String document, @Nonnull Object... arguments) {
			this.document = String.format(document, arguments);
			return this;
		}

		public Request variable(@Nonnull String name, @Nullable Object value) {
			this.variables.put(name, value);
			return this;
		}

		public Request contentTypeHeader(@Nonnull String value) {
			this.headers.put(Headers.CONTENT_TYPE_STRING, new Header(Headers.CONTENT_TYPE_STRING, value));
			return this;
		}

		public Request acceptHeader(@Nonnull String value) {
			this.headers.put(Headers.ACCEPT_STRING, new Header(Headers.ACCEPT_STRING, value));
			return this;
		}

		public Request header(@Nonnull String name, @Nonnull String value) {
			this.headers.put(name, new Header(name, value));
			return this;
		}

		/**
		 * Executes configured request against GraphQL APi and returns response with validation methods.
		 */
		public ValidatableResponse executeAndThen() {
			if (!this.headers.containsKey(Headers.CONTENT_TYPE_STRING)) {
				this.headers.put(Headers.CONTENT_TYPE_STRING, new Header(Headers.CONTENT_TYPE_STRING, GraphQLMimeTypes.APPLICATION_GRAPHQL_JSON));
			}
			if (!this.headers.containsKey(Headers.ACCEPT_STRING)) {
				this.headers.put(Headers.ACCEPT_STRING, new Header(Headers.ACCEPT_STRING, GraphQLMimeTypes.APPLICATION_GRAPHQL_JSON));
			}
			return tester.executeAndThen(this);
		}
	}
}
