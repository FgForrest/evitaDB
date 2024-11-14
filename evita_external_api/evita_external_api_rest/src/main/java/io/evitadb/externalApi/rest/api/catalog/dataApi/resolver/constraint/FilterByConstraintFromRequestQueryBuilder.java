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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ListUnknownEntitiesEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.QueryHeaderFilterArgumentsJoinType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.UnknownEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Creates {@link io.evitadb.api.query.filter.FilterBy} constraint for Evita query from request parameters.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FilterByConstraintFromRequestQueryBuilder {

	/**
	 * Creates filter by constraints from request parameters when requesting single known entity.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <A extends Serializable & Comparable<A>> FilterBy buildFilterByForSingleEntity(@Nonnull Map<String, Object> parameters,
	                                                                                             @Nonnull EntitySchemaContract entitySchema) {
		final List<FilterConstraint> filterConstraints = new LinkedList<>();

		if (parameters.containsKey(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name())) {
			filterConstraints.add(entityPrimaryKeyInSet((Integer) parameters.get(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.name())));
		}
		if (parameters.containsKey(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name())) {
			filterConstraints.add(QueryConstraints.priceInPriceLists((String[]) parameters.get(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.name())));
		}
		if (parameters.containsKey(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name())) {
			filterConstraints.add(QueryConstraints.priceInCurrency((Currency) parameters.get(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY.name())));
		}
		if (parameters.containsKey(GetEntityEndpointHeaderDescriptor.PRICE_VALID_IN.name())) {
			filterConstraints.add(QueryConstraints.priceValidIn((OffsetDateTime) parameters.get(GetEntityEndpointHeaderDescriptor.PRICE_VALID_IN.name())));
		}
		if (Boolean.TRUE.equals(parameters.get(GetEntityEndpointHeaderDescriptor.PRICE_VALID_NOW.name()))) {
			filterConstraints.add(QueryConstraints.priceValidInNow());
		}
		if (parameters.containsKey(GetEntityEndpointHeaderDescriptor.LOCALE.name())) {
			filterConstraints.add(QueryConstraints.entityLocaleEquals((Locale) parameters.get(GetEntityEndpointHeaderDescriptor.LOCALE.name())));
		}

		final Scope[] requestedScopes = Optional.ofNullable((List<Scope>) parameters.get(GetEntityEndpointHeaderDescriptor.SCOPE.name()))
			.map(it -> it.toArray(Scope[]::new))
			.orElse(Scope.DEFAULT);

		entitySchema.getAttributes()
			.values()
			.stream()
			.filter(attribute -> Arrays.stream(requestedScopes).anyMatch(attribute::isUnique))
			.map(attributeSchema -> attributeSchema.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
			.forEach(name -> {
				if (parameters.containsKey(name)) {
					filterConstraints.add(QueryConstraints.attributeEquals(name, (A) parameters.get(name)));
				}
			});

		if (filterConstraints.isEmpty()) {
			return null;
		}

		return filterBy(
			and(
				filterConstraints.toArray(FilterConstraint[]::new)
			)
		);
	}

	/**
	 * Creates filter by constraints from request parameters when requesting single unknown entity.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <A extends Serializable & Comparable<A>> FilterBy buildFilterByForUnknownEntity(@Nonnull Map<String, Object> requestParameters,
	                                                                                              @Nonnull CatalogSchemaContract catalogSchema) {
		// extract parameters

		final Map<String, Object> parameters = new HashMap<>(requestParameters);

		final Locale locale = (Locale) parameters.remove(FetchEntityEndpointHeaderDescriptor.LOCALE.name());
		final QueryHeaderFilterArgumentsJoinType filterJoin = Optional.ofNullable(
			(QueryHeaderFilterArgumentsJoinType) parameters.remove(UnknownEntityEndpointHeaderDescriptor.FILTER_JOIN.name())
		)
			.orElse(QueryHeaderFilterArgumentsJoinType.AND);
		final Scope[] requestedScopes = Optional.ofNullable((List<Scope>) parameters.remove(GetEntityEndpointHeaderDescriptor.SCOPE.name()))
			.map(it -> it.toArray(Scope[]::new))
			.orElse(Scope.DEFAULT);

		final Map<GlobalAttributeSchemaContract, Object> uniqueAttributes = getGloballyUniqueAttributesFromParameters(requestedScopes, parameters, catalogSchema);

		if (locale == null &&
			Arrays.stream(requestedScopes)
			    .anyMatch(scope ->
				    uniqueAttributes.keySet()
					    .stream()
					    .anyMatch(attribute ->
					        attribute.isUniqueGloballyWithinLocale(scope)))) {
			throw new RestInvalidArgumentException("Globally unique within locale attribute used but no locale was passed.");
		}

		// build filter

		final List<FilterConstraint> filterConstraints = new LinkedList<>();

		Optional.ofNullable(locale).ifPresent(it -> filterConstraints.add(entityLocaleEquals(it)));

		uniqueAttributes.forEach((attributeSchema, attributeValue) -> {
			final String name = attributeSchema.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION);
			filterConstraints.add(QueryConstraints.attributeEquals(name, (A) attributeValue));
		});

		if (filterConstraints.isEmpty()) {
			return null;
		}

		if (filterJoin == QueryHeaderFilterArgumentsJoinType.AND) {
			return filterBy(and(filterConstraints.toArray(FilterConstraint[]::new)));
		} else if (filterJoin == QueryHeaderFilterArgumentsJoinType.OR) {
			return filterBy(or(filterConstraints.toArray(FilterConstraint[]::new)));
		} else {
			throw new RestInternalError("Unsupported filter join type `" + filterJoin + "`.");
		}
	}

	/**
	 * Creates filter by constraints from request parameters when requesting unknown entity list.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <A extends Serializable & Comparable<A>> FilterBy buildFilterByForUnknownEntityList(@Nonnull Map<String, Object> requestParameters,
	                                                                                                  @Nonnull CatalogSchemaContract catalogSchema) {
		// extract parameters

		final Map<String, Object> parameters = new HashMap<>(requestParameters);

		final Locale locale = (Locale) parameters.remove(FetchEntityEndpointHeaderDescriptor.LOCALE.name());
		final QueryHeaderFilterArgumentsJoinType filterJoin = Optional.ofNullable(
			(QueryHeaderFilterArgumentsJoinType) parameters.remove(ListUnknownEntitiesEndpointHeaderDescriptor.FILTER_JOIN.name())
		)
			.orElse(QueryHeaderFilterArgumentsJoinType.AND);
		final Scope[] requestedScopes = Optional.ofNullable((List<Scope>) parameters.remove(GetEntityEndpointHeaderDescriptor.SCOPE.name()))
			.map(it -> it.toArray(Scope[]::new))
			.orElse(Scope.DEFAULT);

		final Map<GlobalAttributeSchemaContract, Object> uniqueAttributes = getGloballyUniqueAttributesFromParameters(requestedScopes, parameters, catalogSchema);

		if (locale == null &&
		    Arrays.stream(requestedScopes)
			    .anyMatch(scope ->
				    uniqueAttributes.keySet()
					    .stream()
					    .anyMatch(attribute ->
						    attribute.isUniqueGloballyWithinLocale(scope)))) {
			throw new RestInvalidArgumentException("Globally unique within locale attribute used but no locale was passed.");
		}

		// build filter

		final List<FilterConstraint> filterConstraints = new LinkedList<>();

		Optional.ofNullable(locale).ifPresent(it -> filterConstraints.add(entityLocaleEquals(it)));

		uniqueAttributes.forEach((attributeSchema, attributeValue) -> {
			final String name = attributeSchema.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION);
			if(attributeValue instanceof Object[] array) {
				filterConstraints.add(QueryConstraints.attributeInSet(name, convertObjectArrayToSpecificArray(attributeSchema.getType(), array)));
			} else {
				filterConstraints.add(QueryConstraints.attributeEquals(name, (A) attributeValue));
			}
		});

		if (filterConstraints.isEmpty()) {
			return null;
		}

		if (filterJoin == QueryHeaderFilterArgumentsJoinType.AND) {
			return filterBy(and(filterConstraints.toArray(FilterConstraint[]::new)));
		} else if (filterJoin == QueryHeaderFilterArgumentsJoinType.OR) {
			return filterBy(or(filterConstraints.toArray(FilterConstraint[]::new)));
		} else {
			throw new RestInternalError("Unsupported filter join type `" + filterJoin + "`.");
		}
	}

	@Nonnull
	private static Map<GlobalAttributeSchemaContract, Object> getGloballyUniqueAttributesFromParameters(
		@Nonnull Scope[] requestedScopes,
		@Nonnull Map<String, Object> remainingParameters,
	    @Nonnull CatalogSchemaContract catalogSchema
	) {
		final Map<GlobalAttributeSchemaContract, Object> uniqueAttributes = createHashMap(remainingParameters.size());

		for (Entry<String, Object> parameter : remainingParameters.entrySet()) {
			final String attributeName = parameter.getKey();
			final GlobalAttributeSchemaContract attributeSchema = catalogSchema
				.getAttributeByName(attributeName, ARGUMENT_NAME_NAMING_CONVENTION)
				.orElse(null);
			if (attributeSchema == null) {
				// not a attribute argument
				continue;
			}
			Assert.isPremiseValid(
				Arrays.stream(requestedScopes).anyMatch(attributeSchema::isUniqueGlobally),
				() -> new RestQueryResolvingInternalError(
					"Cannot find entity by non-unique attribute `" + attributeName + "`."
				)
			);

			final Object attributeValue = parameter.getValue();
			if (attributeValue == null) {
				// ignore empty argument attributes
				continue;
			}

			uniqueAttributes.put(attributeSchema, attributeValue);
		}

		return uniqueAttributes;
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	private static  <V extends Serializable & Comparable<?>> V[] convertObjectArrayToSpecificArray(@Nonnull Class<? extends Serializable> targetComponentType,
	                                                                                       @Nonnull Object[] restList) {
		try {
			final Object array = Array.newInstance(targetComponentType, restList.length);
			for (int i = 0; i < restList.length; i++) {
				Array.set(array, i, restList[i]);
			}
			return (V[]) array;
		} catch (ClassCastException e) {
			throw new RestInternalError("Could not cast REST list to array of type `" + targetComponentType.getName() + "`");
		}
	}
}
