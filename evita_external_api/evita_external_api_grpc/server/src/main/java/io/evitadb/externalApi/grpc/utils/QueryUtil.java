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

package io.evitadb.externalApi.grpc.utils;

import com.google.protobuf.GeneratedMessageV3;
import com.google.rpc.Code;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryParser;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.require.DataInLocales;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam;
import io.evitadb.externalApi.grpc.query.QueryConverter;
import io.grpc.stub.StreamObserver;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

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
	 * Get a set of {@link Locale}s from {@link Query}.
	 *
	 * @param query to get locales from
	 * @return set of locales
	 */
	@Nonnull
	public static Set<Locale> getRequiredLocales(@Nonnull Query query) {
		final DataInLocales dataRequirement = QueryUtils.findRequire(query, DataInLocales.class);
		if (dataRequirement != null) {
			return Arrays.stream(dataRequirement.getLocales())
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		} else {
			final Locale theLocale = ofNullable(QueryUtils.findFilter(query, EntityLocaleEquals.class))
				.map(EntityLocaleEquals::getLocale)
				.orElse(null);
			if (theLocale != null) {
				return Set.of(theLocale);
			}
		}
		return new HashSet<>(0);
	}

	/**
	 * Get an array of {@link EntityContentRequire}s from {@link Query}.
	 *
	 * @param query to get locales from
	 * @return array of entity constraints
	 */
	@Nullable
	public static EntityFetch getEntityFetchRequirement(@Nonnull Query query) {
		if (query.getRequire() == null) {
			return null;
		} else {
			return FinderVisitor.findConstraint(
				query.getRequire(),
				EntityFetch.class::isInstance,
				SeparateEntityContentRequireContainer.class::isInstance
			);
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
		try {
			return parser.parseRequireConstraintList(
				requireConstraints,
				QueryConverter.convertQueryParamsMap(namedQueryParams),
				QueryConverter.convertQueryParamsList(queryParams)
			)
				.stream()
				.map(c -> {
					if (!(c instanceof EntityContentRequire)) {
						throw new EvitaInvalidUsageException("Only content require constraints are supported.");
					}
					return (EntityContentRequire) c;
				})
				.toArray(EntityContentRequire[]::new);
		} catch (EvitaInvalidUsageException ex) {
			if (responseObserver != null) {
				responseObserver.onError(ExceptionStatusProvider.getStatus(ex, Code.INVALID_ARGUMENT, "Query parsing error"));
			}
			throw ex;
		}
	}

	/**
	 * Parse query with provided list of positional parameters.
	 *
	 * @param queryString      to be parsed
	 * @param queryParams      to be used for parsing
	 * @param responseObserver response observer for error handling
	 * @param <T>              type of response message to be able to pass generic {@link StreamObserver}
	 * @return parsed {@link Query}
	 */
	@Nullable
	public static <T extends GeneratedMessageV3> Query parseQuery(
		@Nonnull String queryString,
		@Nonnull List<GrpcQueryParam> queryParams,
		@Nullable StreamObserver<T> responseObserver
	) {
		try {
			return parser.parseQuery(queryString, QueryConverter.convertQueryParamsList(queryParams));
		} catch (Exception ex) {
			if (responseObserver != null) {
				responseObserver.onError(ExceptionStatusProvider.getStatus(ex, Code.INVALID_ARGUMENT, "Query parsing error"));
			}
			return null;
		}
	}

	/**
	 * Parse query with provided map of named parameters.
	 *
	 * @param queryString      to be parsed
	 * @param queryParams      to be used for parsing
	 * @param responseObserver response observer for error handling
	 * @param <T>              type of response message to be able to pass generic {@link StreamObserver}
	 * @return parsed {@link Query}
	 */
	@Nullable
	public static <T extends GeneratedMessageV3> Query parseQuery(@Nonnull String queryString, @Nonnull Map<String, GrpcQueryParam> queryParams, @Nullable StreamObserver<T> responseObserver) {
		try {
			return parser.parseQuery(queryString, QueryConverter.convertQueryParamsMap(queryParams));
		} catch (Exception ex) {
			if (responseObserver != null) {
				responseObserver.onError(ExceptionStatusProvider.getStatus(ex, Code.INVALID_ARGUMENT, "Query parsing error"));
			}
			return null;
		}
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
	public static <T extends GeneratedMessageV3> Query parseQuery(@Nonnull String queryString, @Nonnull List<GrpcQueryParam> queryParamsList, @Nonnull Map<String, GrpcQueryParam> queryParamsMap, @Nullable StreamObserver<T> responseObserver) {
		try {
			if (queryParamsList.isEmpty() && queryParamsMap.isEmpty()) {
				return parser.parseQuery(queryString);
			}
			if (queryParamsList.isEmpty()) {
				return parseQuery(queryString, queryParamsMap, responseObserver);
			}
			if (queryParamsMap.isEmpty()) {
				return parseQuery(queryString, queryParamsList, responseObserver);
			}
			return parser.parseQuery(
				queryString,
				QueryConverter.convertQueryParamsMap(queryParamsMap),
				QueryConverter.convertQueryParamsList(queryParamsList)
			);
		} catch (Exception ex) {
			if (responseObserver != null) {
				responseObserver.onError(ExceptionStatusProvider.getStatus(ex, Code.INVALID_ARGUMENT, "Query parsing error"));
			}
			return null;
		}
	}

	/**
	 * Get an array of priceList ({@link String}) from {@link Query}.
	 *
	 * @param query to get priceLists from
	 * @return array of priceLists
	 */
	@Nonnull
	public static String[] getPriceLists(@Nonnull Query query) {
		final PriceInPriceLists pricesInPriceList = QueryUtils.findFilter(query, PriceInPriceLists.class);
		return ofNullable(pricesInPriceList)
			.map(PriceInPriceLists::getPriceLists)
			.orElse(new String[0]);
	}

	/**
	 * Get a ({@link Currency}) from {@link Query}.
	 *
	 * @param query to get currency from
	 * @return currency if found, null otherwise
	 */
	@Nullable
	public static Currency getCurrency(@Nonnull Query query) {
		return ofNullable(QueryUtils.findFilter(query, PriceInCurrency.class))
			.map(PriceInCurrency::getCurrency)
			.orElse(null);
	}

	/**
	 * Get a ({@link OffsetDateTime}) from {@link Query}.
	 *
	 * @param query to get priceValidIn from
	 * @return priceValidIn if found, null otherwise
	 */
	@Nullable
	public static OffsetDateTime getPriceValidIn(@Nonnull Query query, @Nonnull Supplier<OffsetDateTime> currentDateAndTime) {
		return ofNullable(QueryUtils.findFilter(query, PriceValidIn.class))
			.map(it -> it.getTheMoment(currentDateAndTime))
			.orElse(null);
	}
}
