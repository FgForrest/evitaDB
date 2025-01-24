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

package io.evitadb.externalApi.grpc.utils;

import com.google.protobuf.GeneratedMessageV3;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryParser;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam;
import io.evitadb.externalApi.grpc.query.QueryConverter;
import io.grpc.stub.StreamObserver;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor.sendErrorToClient;

/**
 * Class containing utility methods targeting on query, such as its parsing or getting specific parts of it.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QueryUtil {
	/**
	 * Instance of {@link QueryParser} used for parsing queries.
	 */
	private static final QueryParser parser = DefaultQueryParser.getInstance();

	/**
	 * Parse list of {@link RequireConstraint} from {@link String}.
	 *
	 * @param requireConstraints to be parsed
	 * @return list of {@link EntityContentRequire}s
	 */
	@Nonnull
	public static <T extends GeneratedMessageV3> List<RequireConstraint> parseRequireContents(
		@Nonnull String requireConstraints,
		@Nonnull List<GrpcQueryParam> queryParams,
		@Nonnull Map<String, GrpcQueryParam> namedQueryParams,
		@Nullable StreamObserver<T> responseObserver
	) {
		try {
			return parser.parseRequireConstraintList(
					requireConstraints,
					QueryConverter.convertQueryParamsMap(namedQueryParams),
					QueryConverter.convertQueryParamsList(queryParams)
				);
		} catch (EvitaInvalidUsageException ex) {
			if (responseObserver != null) {
				sendErrorToClient(ex, responseObserver);
			}
			throw ex;
		}
	}

	/**
	 * Parse array of {@link EntityContentRequire} from {@link String}.
	 *
	 * @param requireConstraints to be parsed
	 * @return array of {@link EntityContentRequire}s
	 */
	@Nonnull
	public static <T extends GeneratedMessageV3> EntityContentRequire[] parseEntityRequiredContents(
		@Nonnull String requireConstraints,
		@Nonnull List<GrpcQueryParam> queryParams,
		@Nonnull Map<String, GrpcQueryParam> namedQueryParams,
		@Nullable StreamObserver<T> responseObserver
	) {
		return parseRequireContents(requireConstraints, queryParams, namedQueryParams, responseObserver)
			.stream()
			.map(c -> {
				if (!(c instanceof EntityContentRequire)) {
					throw new EvitaInvalidUsageException("Only content require constraints are supported.");
				}
				return (EntityContentRequire) c;
			})
			.toArray(EntityContentRequire[]::new);
	}

	/**
	 * Parse query with provided list of positional parameters.
	 *
	 * @param queryString      to be parsed
	 * @param queryParamsList  to be used for parsing query with substitutions placeholder characters for this list values
	 * @param queryParamsMap   to be used for parsing query with substitutions named placeholders for this map values (key is name of placeholder)
	 * @param responseObserver response observer for error handling
	 * @param <T>              type of response message to be able to pass generic {@link StreamObserver}
	 * @return parsed {@link Query}
	 */
	@Nullable
	public static <T extends GeneratedMessageV3> QueryWithParameters parseQuery(
		@Nonnull String queryString,
		@Nonnull List<GrpcQueryParam> queryParamsList,
		@Nonnull Map<String, GrpcQueryParam> queryParamsMap,
		@Nullable StreamObserver<T> responseObserver
	) {
		try {
			if (queryParamsList.isEmpty() && queryParamsMap.isEmpty()) {
				return new QueryWithParameters(
					parser.parseQuery(queryString),
					Collections.emptyList(),
					Collections.emptyMap()
				);
			} else if (queryParamsList.isEmpty()) {
				final Map<String, Object> namedArguments = QueryConverter.convertQueryParamsMap(queryParamsMap);
				return new QueryWithParameters(
					parser.parseQuery(queryString, namedArguments, Collections.emptyList()),
					Collections.emptyList(),
					namedArguments
				);
			} else if (queryParamsMap.isEmpty()) {
				final List<Object> positionalArguments = QueryConverter.convertQueryParamsList(queryParamsList);
				return new QueryWithParameters(
					parser.parseQuery(queryString, Collections.emptyMap(), positionalArguments),
					positionalArguments,
					Collections.emptyMap()
				);
			} else {
				final Map<String, Object> namedArguments = QueryConverter.convertQueryParamsMap(queryParamsMap);
				final List<Object> positionalArguments = QueryConverter.convertQueryParamsList(queryParamsList);
				return new QueryWithParameters(
					parser.parseQuery(
						queryString,
						namedArguments,
						positionalArguments
					),
					positionalArguments,
					namedArguments
				);
			}
		} catch (Exception ex) {
			if (responseObserver != null) {
				sendErrorToClient(ex, responseObserver);
			}
			return null;
		}
	}

	/**
	 * Parse query with provided list of positional parameters accepting parameters directly in the input query.
	 * This method is unsafe and should be used only when the query is known to be from the trustworthy source.
	 *
	 * @param queryString      to be parsed
	 * @param queryParamsList  to be used for parsing query with substitutions placeholder characters for this list values
	 * @param queryParamsMap   to be used for parsing query with substitutions named placeholders for this map values (key is name of placeholder)
	 * @param responseObserver response observer for error handling
	 * @param <T>              type of response message to be able to pass generic {@link StreamObserver}
	 * @return parsed {@link Query}
	 */
	@Nullable
	public static <T extends GeneratedMessageV3> QueryWithParameters parseQueryUnsafe(
		@Nonnull String queryString,
		@Nonnull List<GrpcQueryParam> queryParamsList,
		@Nonnull Map<String, GrpcQueryParam> queryParamsMap,
		@Nullable StreamObserver<T> responseObserver
	) {
		try {
			if (queryParamsList.isEmpty() && queryParamsMap.isEmpty()) {
				return new QueryWithParameters(
					parser.parseQueryUnsafe(queryString),
					Collections.emptyList(),
					Collections.emptyMap()
				);
			} else if (queryParamsList.isEmpty()) {
				final Map<String, Object> namedArguments = QueryConverter.convertQueryParamsMap(queryParamsMap);
				return new QueryWithParameters(
					parser.parseQueryUnsafe(queryString, namedArguments, Collections.emptyList()),
					Collections.emptyList(),
					namedArguments
				);
			} else if (queryParamsMap.isEmpty()) {
				final List<Object> positionalArguments = QueryConverter.convertQueryParamsList(queryParamsList);
				return new QueryWithParameters(
					parser.parseQueryUnsafe(queryString, Collections.emptyMap(), positionalArguments),
					positionalArguments,
					Collections.emptyMap()
				);
			} else {
				final Map<String, Object> namedArguments = QueryConverter.convertQueryParamsMap(queryParamsMap);
				final List<Object> positionalArguments = QueryConverter.convertQueryParamsList(queryParamsList);
				return new QueryWithParameters(
					parser.parseQueryUnsafe(
						queryString,
						namedArguments,
						positionalArguments
					),
					positionalArguments,
					namedArguments
				);
			}
		} catch (Exception ex) {
			if (responseObserver != null) {
				sendErrorToClient(ex, responseObserver);
			}
			return null;
		}
	}

}
