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

package io.evitadb.test.tester;

import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.test.tester.RestTester.Request;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.restassured.RestAssured.given;

/**
 * Simple tester utility for easier testing of REST API. It uses REST Assured library as backend but test doesn't have
 * to configure each request with URL, headers, POST method and so on.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public class RestTester extends JsonExternalApiTester<Request> {

	public RestTester(@Nonnull String baseUrl) {
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
		final RequestSpecification requestSpecification = given()
			.relaxedHTTPSValidation()
			.headers(new io.restassured.http.Headers(new ArrayList<>(request.getHeaders().values())))
			.log()
			.ifValidationFails();

		if(request.getRequestBody() != null) {
			requestSpecification.body(request.getRequestBody());
		}

		if(request.getRequestParams() != null) {
			requestSpecification.params(request.getRequestParams());
		}

		final String fullUrl = baseUrl + "/" + request.getCatalogName() + (request.getUrlPathSuffix() != null ? request.getUrlPathSuffix() : "");
		final Response response = switch (request.httpMethod) {
			case Request.METHOD_GET -> requestSpecification.when().get(fullUrl);
			case Request.METHOD_PUT -> requestSpecification.when().put(fullUrl);
			case Request.METHOD_DELETE -> requestSpecification.when().delete(fullUrl);
			case Request.METHOD_PATCH -> requestSpecification.when().patch(fullUrl);
			default -> requestSpecification.when().post(fullUrl);
		};

		return response
			.then()
				.log()
				.ifError();
	}

	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	@Getter(AccessLevel.PRIVATE)
	public static class Request {
		public static final String METHOD_POST = "post";
		public static final String METHOD_DELETE = "delete";
		public static final String METHOD_PUT = "put";
		public static final String METHOD_PATCH = "patch";
		public static final String METHOD_GET = "get";

		private final RestTester tester;
		private final String catalogName;

		@Getter(AccessLevel.PUBLIC) private String httpMethod;

		@Nullable
		private String requestBody;
		@Nullable
		private Map<String,Object> requestParams;
		@Nullable
		private String urlPathSuffix;

		private final Map<String, Header> headers = new HashMap<>();

		public Request requestBody(@Nonnull String requestBody, @Nonnull Object... arguments) {
			this.requestBody = String.format(requestBody, arguments);
			return this;
		}

		public Request requestParams(Map<String,Object> requestParams) {
			this.requestParams = requestParams;
			return this;
		}

		public Request requestParam(@Nonnull String name, @Nonnull Object value) {
			if (this.requestParams == null) {
				this.requestParams = createHashMap(5);
			}
			this.requestParams.put(name, value);
			return this;
		}

		public Request urlPathSuffix(String urlPathSuffix) {
			this.urlPathSuffix = urlPathSuffix;
			return this;
		}

		public Request httpMethod(@Nonnull String httpMethod) {
			this.httpMethod = httpMethod;
			return this;
		}

		public Request post(String urlPathSuffix) {
			this.urlPathSuffix(urlPathSuffix);
			this.httpMethod(METHOD_POST);
			return this;
		}

		public Request get(String urlPathSuffix) {
			this.urlPathSuffix(urlPathSuffix);
			this.httpMethod(METHOD_GET);
			return this;
		}

		public Request delete(String urlPathSuffix) {
			this.urlPathSuffix(urlPathSuffix);
			this.httpMethod(METHOD_DELETE);
			return this;
		}

		public Request put(String urlPathSuffix) {
			this.urlPathSuffix(urlPathSuffix);
			this.httpMethod(METHOD_PUT);
			return this;
		}

		public Request patch(String urlPathSuffix) {
			this.urlPathSuffix(urlPathSuffix);
			this.httpMethod(METHOD_PATCH);
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
		 * Executes configured request against REST API and returns response with validation methods.
		 */
		public ValidatableResponse executeAndThen() {
			if (!this.headers.containsKey(CONTENT_TYPE_HEADER)) {
				this.headers.put(CONTENT_TYPE_HEADER, new Header(CONTENT_TYPE_HEADER, MimeTypes.APPLICATION_JSON));
			}
			if (!this.headers.containsKey(ACCEPT_HEADER)) {
				this.headers.put(ACCEPT_HEADER, new Header(ACCEPT_HEADER, MimeTypes.APPLICATION_JSON));
			}
			return tester.executeAndThen(this);
		}

		/**
		 * Executes configured request against REST APi and returns response with validation methods.
		 */
		public ValidatableResponse executeAndThen(int statusCode) {
			return executeAndThen()
				.statusCode(statusCode);
		}

		/**
		 * Executes configured request against REST API, validates that status code is 200 and returns response with
		 * validation methods.
		 */
		public ValidatableResponse executeAndExpectOkAndThen() {
			return executeAndThen(200);
		}

		/**
		 * Executes configured request against REST API, validates that status code is 204 and returns response with
		 * validation methods.
		 */
		public ValidatableResponse executeAndExpectOkWithoutBodyAndThen() {
			return executeAndThen(204);
		}

		/**
		 * Executes configured request against REST API, validates that status code is 400 and returns response with
		 * validation methods.
		 */
		public ValidatableResponse executeAndExpectBadRequestAndThen() {
			return executeAndThen(400);
		}

		/**
		 * Executes configured request against REST API, validates that status code is 500 and returns response with
		 * validation methods.
		 */
		public ValidatableResponse executeAndExpectServerErrorAndThen() {
			return executeAndThen(500);
		}

		/**
		 * Executes configured request against REST API, validates that status code is 404 and returns response with
		 * validation methods.
		 */
		public ValidatableResponse executeAndExpectNotFoundAndThen() {
			return executeAndThen(404);
		}
	}
}
