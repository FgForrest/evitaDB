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

package io.evitadb.test.tester;

import io.evitadb.test.tester.GraphQLTester.Request;
import io.restassured.http.Header;
import io.restassured.response.ValidatableResponse;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import org.hamcrest.Matcher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.externalApi.graphql.io.GraphQLMimeTypes.APPLICATION_GRAPHQL_RESPONSE_JSON;
import static io.evitadb.externalApi.http.MimeTypes.APPLICATION_JSON;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Simple tester utility for easier testing of GraphQL API. It uses REST Assured library as backend but test doesn't have
 * to configure each request with URL, headers, POST method and so on.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GraphQLTester extends JsonExternalApiTester<Request> {

	public GraphQLTester(@Nonnull String baseUrl) {
		super(baseUrl);
	}

	/**
	 * Test single request to GraphQL API.
	 */
	@Override
	@Nonnull
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
				.post(this.baseUrl + "/" + request.getCatalogName() + (request.getUrlPathSuffix() != null ? request.getUrlPathSuffix() : "")).
			then()
				.log()
				.ifError();
	}

	@Getter(AccessLevel.PRIVATE)
	public static class Request {

		private final GraphQLTester tester;
		private final String catalogName;

		@Nullable
		private String urlPathSuffix;
		@Nonnull
		private String document;

		private final Map<String, Object> variables = new HashMap<>();

		private final Map<String, Header> headers = new HashMap<>();

		public Request(@Nonnull GraphQLTester tester, @Nonnull String catalogName) {
			this.tester = tester;
			this.catalogName = catalogName;
		}

		public Request urlPathSuffix(String urlPathSuffix) {
			this.urlPathSuffix = urlPathSuffix;
			return this;
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
			this.headers.put(CONTENT_TYPE_HEADER, new Header(CONTENT_TYPE_HEADER, value));
			return this;
		}

		public Request acceptHeader(@Nonnull String value) {
			this.headers.put(ACCEPT_HEADER, new Header(ACCEPT_HEADER, value));
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
			if (!this.headers.containsKey(CONTENT_TYPE_HEADER)) {
				this.headers.put(CONTENT_TYPE_HEADER, new Header(CONTENT_TYPE_HEADER, APPLICATION_JSON));
			}
			if (!this.headers.containsKey(ACCEPT_HEADER)) {
				this.headers.put(ACCEPT_HEADER, new Header(ACCEPT_HEADER, APPLICATION_GRAPHQL_RESPONSE_JSON));
			}
			return this.tester.executeAndThen(this);
		}

		/**
		 * Executes configured request against GraphQL APi and returns response with validation methods.
		 */
		public ValidatableResponse executeAndThen(int statusCode, @Nonnull Matcher<?> errorsMatcher) {
			return executeAndThen()
				.statusCode(statusCode)
				.body("errors", errorsMatcher);
		}

		/**
		 * Executes configured request against GraphQL API, validates that status code is 200 and no GraphQL errors
		 * came, and returns response with validation methods.
		 */
		public ValidatableResponse executeAndExpectOkAndThen() {
			return executeAndThen(200, nullValue());
		}

		/**
		 * Executes configured request against GraphQL API, validates that status code is 200 and that there are any
		 * GraphQL errors, and returns response with validation methods.
		 */
		public ValidatableResponse executeAndExpectErrorsAndThen() {
			return executeAndThen(200, hasSize(greaterThan(0)));
		}
	}
}
