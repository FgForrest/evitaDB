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

package io.evitadb.externalApi.rest.testSuite;

import io.evitadb.externalApi.http.MimeTypes;
import io.restassured.http.Header;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
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
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class RESTTester {

	private final String url;

	/**
	 * Test single request to GraphQL API.
	 */
	public Request test() {
		return new Request(this);
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

		if(request.httpMethod.equals(Request.METHOD_GET)) {
			return requestSpecification.
				when()
					.get(url + (request.getUrlPathSuffix() != null?request.getUrlPathSuffix():"")).
				then()
					.log()
					.ifError();
		} else if(request.httpMethod.equals(Request.METHOD_PUT)) {
			return requestSpecification.
				when()
					.put(url + (request.getUrlPathSuffix() != null?request.getUrlPathSuffix():"")).
				then()
					.log()
					.ifError();
		} else if(request.httpMethod.equals(Request.METHOD_DELETE)) {
			return requestSpecification.
				when()
					.delete(url + (request.getUrlPathSuffix() != null?request.getUrlPathSuffix():"")).
				then()
					.log()
					.ifError();
		} else {
			return requestSpecification.
				when()
					.post(url + (request.getUrlPathSuffix() != null ? request.getUrlPathSuffix() : "")).
				then()
					.log()
					.ifError();
		}
	}

	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	@Getter(AccessLevel.PRIVATE)
	public static class Request {
		public static final String METHOD_POST = "post";
		public static final String METHOD_DELETE = "delete";
		public static final String METHOD_PUT = "put";
		public static final String METHOD_GET = "get";

		private final RESTTester tester;

		@Getter(AccessLevel.PUBLIC) private String httpMethod;

		@Nullable
		private String requestBody;
		@Nullable
		private Map<String,?> requestParams;
		@Nullable
		private String urlPathSuffix;

		private final Map<String, Header> headers = new HashMap<>();

		public Request requestBody(@Nonnull String requestBody, @Nonnull Object... arguments) {
			this.requestBody = String.format(requestBody, arguments);
			return this;
		}

		public Request requestParams(Map<String,?> requestParams) {
			this.requestParams = requestParams;
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
				this.headers.put(Headers.CONTENT_TYPE_STRING, new Header(Headers.CONTENT_TYPE_STRING, MimeTypes.APPLICATION_JSON));
			}
			if (!this.headers.containsKey(Headers.ACCEPT_STRING)) {
				this.headers.put(Headers.ACCEPT_STRING, new Header(Headers.ACCEPT_STRING, MimeTypes.APPLICATION_JSON));
			}
			return tester.executeAndThen(this);
		}
	}
}
