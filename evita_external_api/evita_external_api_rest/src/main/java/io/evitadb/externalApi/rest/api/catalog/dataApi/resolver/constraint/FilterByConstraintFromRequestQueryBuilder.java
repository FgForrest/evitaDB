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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.QueryHeaderFilterArgumentsJoinType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ScopeAwareEndpointHeaderDescriptor;
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
		if (parameters.containsKey(FetchEntityEndpointHeaderDescriptor.LOCALE.name())) {
			filterConstraints.add(QueryConstraints.entityLocaleEquals((Locale) parameters.get(
				FetchEntityEndpointHeaderDescriptor.LOCALE.name())));
		}

		final Scope[] requestedScopes = (Scope[]) parameters.get(ScopeAwareEndpointHeaderDescriptor.SCOPE.name());
		if (requestedScopes != null) {
			filterConstraints.add(scope(requestedScopes));
		}

		final Scope[] comparableScopes = Optional.ofNullable(requestedScopes).orElse(Scope.DEFAULT_SCOPES);
		entitySchema.getAttributes()
			.values()
			.stream()
			.filter(attribute -> Arrays.stream(comparableScopes).anyMatch(attribute::isUniqueInScope))
			.map(attributeSchema -> attributeSchema.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
			.forEach(name -> {
				if (parameters.containsKey(name)) {
					filterConstraints.add(QueryConstraints.attributeEquals(name, (A) parameters.get(name)));
				}
			});

		if (filterConstraints.isEmpty()) {
			return null;
		}

		return filterBy(filterConstraints.toArray(FilterConstraint[]::new));
	}

	/**
	 * Creates filter by constraints from request parameters when requesting single unknown entity.
	 */
	@Nullable
	public static FilterBy buildFilterByForUnknownEntity(@Nonnull Map<String, Object> requestParameters,
	                                                     @Nonnull CatalogSchemaContract catalogSchema) {
		// extract parameters

		final Map<String, Object> parameters = new HashMap<>(requestParameters);

		final Locale locale = (Locale) parameters.remove(FetchEntityEndpointHeaderDescriptor.LOCALE.name());
		final QueryHeaderFilterArgumentsJoinType filterJoin = Optional.ofNullable(
			(QueryHeaderFilterArgumentsJoinType) parameters.remove(UnknownEntityEndpointHeaderDescriptor.FILTER_JOIN.name())
		)
			.orElse(QueryHeaderFilterArgumentsJoinType.AND);
		final Scope[] requestedScopes = (Scope[]) parameters.remove(ScopeAwareEndpointHeaderDescriptor.SCOPE.name());
		final Scope[] comparableScopes = Optional.ofNullable(requestedScopes).orElse(Scope.DEFAULT_SCOPES);

		final Map<GlobalAttributeSchemaContract, Object> uniqueAttributes = getGloballyUniqueAttributesFromParameters(comparableScopes, parameters, catalogSchema);

		if (locale == null &&
			Arrays.stream(comparableScopes)
			    .anyMatch(scope ->
				    uniqueAttributes.keySet()
					    .stream()
					    .anyMatch(attribute ->
					        attribute.isUniqueGloballyWithinLocaleInScope(scope)))) {
			throw new RestInvalidArgumentException("Globally unique within locale attribute used but no locale was passed.");
		}

		// build filter

		final List<FilterConstraint> filterConstraints = new LinkedList<>();

		if (locale != null) {
			filterConstraints.add(entityLocaleEquals(locale));
		}
		filterConstraints.add(buildAttributeFilterContainerForSingleEntity(uniqueAttributes, filterJoin));

		if (requestedScopes != null) {
			filterConstraints.add(scope(requestedScopes));
		}

		return filterBy(filterConstraints.toArray(FilterConstraint[]::new));
	}

	@Nonnull
	private static <A extends Serializable & Comparable<A>> FilterConstraint buildAttributeFilterContainerForSingleEntity(
		@Nonnull Map<GlobalAttributeSchemaContract, Object> uniqueAttributes,
		@Nonnull QueryHeaderFilterArgumentsJoinType filterJoin
	) {
		final List<FilterConstraint> attributeConstraints = new LinkedList<>();
		uniqueAttributes.forEach((attributeSchema, attributeValue) -> {
			final String name = attributeSchema.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION);
			//noinspection unchecked
			attributeConstraints.add(QueryConstraints.attributeEquals(name, (A) attributeValue));
		});

		final FilterConstraint composition;
		if (filterJoin == QueryHeaderFilterArgumentsJoinType.AND) {
			composition = and(attributeConstraints.toArray(FilterConstraint[]::new));
		} else if (filterJoin == QueryHeaderFilterArgumentsJoinType.OR) {
			composition = or(attributeConstraints.toArray(FilterConstraint[]::new));
		} else {
			throw new RestInternalError("Unsupported join type `" + filterJoin + "`.");
		}
		return composition;
	}

	/**
	 * Creates filter by constraints from request parameters when requesting unknown entity list.
	 */
	@Nullable
	public static FilterBy buildFilterByForUnknownEntityList(@Nonnull Map<String, Object> requestParameters,
	                                                         @Nonnull CatalogSchemaContract catalogSchema) {
		// extract parameters

		final Map<String, Object> parameters = new HashMap<>(requestParameters);

		final Locale locale = (Locale) parameters.remove(FetchEntityEndpointHeaderDescriptor.LOCALE.name());
		final QueryHeaderFilterArgumentsJoinType filterJoin = Optional.ofNullable(
			(QueryHeaderFilterArgumentsJoinType) parameters.remove(UnknownEntityEndpointHeaderDescriptor.FILTER_JOIN.name())
		)
			.orElse(QueryHeaderFilterArgumentsJoinType.AND);
		final Scope[] requestedScopes = (Scope[]) parameters.remove(ScopeAwareEndpointHeaderDescriptor.SCOPE.name());
		final Scope[] comparableScopes = Optional.ofNullable(requestedScopes).orElse(Scope.DEFAULT_SCOPES);

		final Map<GlobalAttributeSchemaContract, Object> uniqueAttributes = getGloballyUniqueAttributesFromParameters(comparableScopes, parameters, catalogSchema);

		if (locale == null &&
		    Arrays.stream(comparableScopes)
			    .anyMatch(scope ->
				    uniqueAttributes.keySet()
					    .stream()
					    .anyMatch(attribute ->
						    attribute.isUniqueGloballyWithinLocaleInScope(scope)))) {
			throw new RestInvalidArgumentException("Globally unique within locale attribute used but no locale was passed.");
		}

		// build filter

		final List<FilterConstraint> filterConstraints = new LinkedList<>();

		if (locale != null) {
			filterConstraints.add(entityLocaleEquals(locale));
		}
		filterConstraints.add(buildAttributeFilterContainerForEntityList(uniqueAttributes, filterJoin));
		if (requestedScopes != null) {
			filterConstraints.add(scope(requestedScopes));
		}

		return filterBy(filterConstraints.toArray(FilterConstraint[]::new));
	}

	@Nonnull
	private static <A extends Serializable & Comparable<A>> FilterConstraint buildAttributeFilterContainerForEntityList(
		@Nonnull Map<GlobalAttributeSchemaContract, Object> uniqueAttributes,
		@Nonnull QueryHeaderFilterArgumentsJoinType filterJoin
	) {
		final List<FilterConstraint> attributeConstraints = new LinkedList<>();
		uniqueAttributes.forEach((attributeSchema, attributeValue) -> {
			final String name = attributeSchema.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION);
			if(attributeValue instanceof Object[] array) {
				attributeConstraints.add(QueryConstraints.attributeInSet(name, convertObjectArrayToSpecificArray(attributeSchema.getType(), array)));
			} else {
				//noinspection unchecked
				attributeConstraints.add(QueryConstraints.attributeEquals(name, (A) attributeValue));
			}
		});

		final FilterConstraint composition;
		if (filterJoin == QueryHeaderFilterArgumentsJoinType.AND) {
			composition = and(attributeConstraints.toArray(FilterConstraint[]::new));
		} else if (filterJoin == QueryHeaderFilterArgumentsJoinType.OR) {
			composition = or(attributeConstraints.toArray(FilterConstraint[]::new));
		} else {
			throw new RestInternalError("Unsupported join type `" + filterJoin + "`.");
		}
		return composition;
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
				Arrays.stream(requestedScopes).anyMatch(attributeSchema::isUniqueGloballyInScope),
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
