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

package io.evitadb.externalApi;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.test.Entities;
import org.apache.commons.io.IOUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_CZK;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Common support methods for functional tests of external APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ExternalApiFunctionTestsSupport {

	default String readFromClasspath(String path) throws IOException {
		return IOUtils.toString(
			Objects.requireNonNull(ExternalApiFunctionTestsSupport.class.getClassLoader().getResourceAsStream(path)),
			StandardCharsets.UTF_8
		);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	default AttributeValue getRandomAttributeValueObject(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName) {
		return getRandomAttributeValueObject(originalProductEntities, attributeName, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	default AttributeValue getRandomAttributeValueObject(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName, int order) {
		return originalProductEntities
			.stream()
			.flatMap(it -> it.getAttributeValues(attributeName).stream())
			.filter(Objects::nonNull)
			.skip(order)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	default <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName) {
		return getRandomAttributeValue(originalProductEntities, attributeName, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	default <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName, int order) {
		return originalProductEntities
			.stream()
			.map(it -> (T) it.getAttribute(attributeName))
			.filter(Objects::nonNull)
			.skip(order)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	default <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities,
	                                                             @Nonnull String attributeName,
	                                                             @Nonnull Locale locale) {
		return getRandomAttributeValue(originalProductEntities, attributeName, locale, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	default <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities,
	                                                             @Nonnull String attributeName,
	                                                             @Nonnull Locale locale,
	                                                             int order) {
		return originalProductEntities
			.stream()
			.map(it -> (T) it.getAttribute(attributeName, locale))
			.filter(Objects::nonNull)
			.skip(order)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to localize `" + attributeName + "` attribute!"));
	}

	@Nonnull
	default String resultPath(@Nonnull Object... properties) {
		return Stream.of(properties)
			.map(it -> {
				if (it instanceof String s) {
					return s;
				}
				if (it instanceof PropertyDescriptor propertyDescriptor) {
					return propertyDescriptor.name();
				}
				throw new IllegalArgumentException("Unsupported property type: " + it.getClass());
			})
			.collect(Collectors.joining("."));
	}

	default int findEntityPk(@Nonnull List<SealedEntity> originalProductEntities,
	                           @Nonnull Predicate<SealedEntity> filter) {
		return originalProductEntities.stream()
			.filter(filter)
			.findFirst()
			.map(SealedEntity::getPrimaryKey)
			.orElseThrow(() -> new GenericEvitaInternalError("No entity to test."));
	}

	default int findEntityWithPricePk(@Nonnull List<SealedEntity> originalProductEntities) {
		return findEntityPk(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}

	@Nonnull
	default Integer[] findEntityPks(@Nonnull List<SealedEntity> originalProductEntities,
	                                  @Nonnull Predicate<SealedEntity> filter) {
		final Integer[] pks = originalProductEntities.stream()
			.filter(filter)
			.limit(2)
			.map(EntityContract::getPrimaryKey)
			.toArray(Integer[]::new);
		assertEquals(2, pks.length);
		return pks;
	}

	@Nonnull
	default Integer[] findEntityWithPricePks(List<SealedEntity> originalProductEntities) {
		return findEntityPks(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}

	@Nonnull
	default <T> List<T> getAttributesByPks(@Nonnull Evita evita, @Nonnull Integer[] pks, @Nonnull String attributeName) {
		//noinspection unchecked
		return getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks)
				),
				require(
					entityFetch(
						attributeContent(attributeName)
					)
				)
			),
			SealedEntity.class
		)
			.stream()
			.map(it -> (T) it.getAttribute(attributeName))
			.toList();
	}

	@Nonnull
	default <T> List<T> getAttributesByPks(@Nonnull Evita evita, @Nonnull Integer[] pks, @Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(pks),
					entityLocaleEquals(locale)
				),
				require(
					entityFetch(
						attributeContent(attributeName)
					)
				)
			),
			SealedEntity.class
		)
			.stream()
			.map(it -> (T) it.getAttribute(attributeName, locale))
			.toList();
	}

	@Nonnull
	default EntityClassifier getEntity(@Nonnull Evita evita, @Nonnull Query query) {
		return getEntity(evita, query, EntityClassifier.class);
	}

	@Nonnull
	default <S extends Serializable> S getEntity(@Nonnull Evita evita, @Nonnull Query query, @Nonnull Class<S> expectedType) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOne(query, expectedType)
					.orElseThrow();
			}
		);
	}

	@Nonnull
	default List<EntityClassifier> getEntities(@Nonnull Evita evita, @Nonnull Query query) {
		return getEntities(evita, query, false);
	}

	@Nonnull
	default List<EntityClassifier> getEntities(@Nonnull Evita evita, @Nonnull Query query, @Nonnull Consumer<EntityClassifier> validator) {
		return getEntities(evita, query, validator, false);
	}

	@Nonnull
	default <S extends Serializable> List<S> getEntities(@Nonnull Evita evita, @Nonnull Query query, @Nonnull Class<S> expectedType) {
		return getEntities(evita, query, false, expectedType);
	}

	@Nonnull
	default <S extends Serializable> List<S> getEntities(@Nonnull Evita evita,
	                                                       @Nonnull Query query,
	                                                       @Nonnull Consumer<S> validator,
	                                                       @Nonnull Class<S> expectedType) {
		return getEntities(evita, query, validator, false, expectedType);
	}

	@Nonnull
	default List<EntityClassifier> getEntities(@Nonnull Evita evita, @Nonnull Query query, boolean allowEmpty) {
		return getEntities(evita, query, allowEmpty, EntityClassifier.class);
	}

	@Nonnull
	default List<EntityClassifier> getEntities(@Nonnull Evita evita,
	                                             @Nonnull Query query,
	                                             @Nonnull Consumer<EntityClassifier> validator,
	                                             boolean allowEmpty) {
		return getEntities(evita, query, validator, allowEmpty, EntityClassifier.class);
	}

	@Nonnull
	default <S extends Serializable> List<S> getEntities(@Nonnull Evita evita,
	                                                       @Nonnull Query query,
	                                                       boolean allowEmpty,
	                                                       @Nonnull Class<S> expectedType) {
		return getEntities(evita, query, it -> {}, allowEmpty, expectedType);
	}

	@Nonnull
	default <S extends Serializable> List<S> getEntities(@Nonnull Evita evita,
	                                                       @Nonnull Query query,
	                                                       @Nonnull Consumer<S> validator,
	                                                       boolean allowEmpty,
	                                                       @Nonnull Class<S> expectedType) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<S> entities = session.queryList(query, expectedType);
				if (!allowEmpty) {
					assertFalse(entities.isEmpty());
				}
				return entities.stream()
					.peek(validator::accept)
					.toList();
			}
		);
	}

	@Nonnull
	default EvitaResponse<EntityClassifier> queryEntities(@Nonnull Evita evita,
	                                                        @Nonnull Query query) {
		return queryEntities(evita, query, false);
	}

	@Nonnull
	default EvitaResponse<EntityClassifier> queryEntities(@Nonnull Evita evita,
	                                                        @Nonnull Query query,
	                                                        @Nonnull Consumer<EntityClassifier> validator) {
		return queryEntities(evita, query, validator, false);
	}

	@Nonnull
	default <S extends Serializable> EvitaResponse<S> queryEntities(@Nonnull Evita evita,
	                                                                  @Nonnull Query query,
	                                                                  @Nonnull Class<S> expectedType) {
		return queryEntities(evita, query, false, expectedType);
	}

	@Nonnull
	default <S extends Serializable> EvitaResponse<S> queryEntities(@Nonnull Evita evita,
	                                                                  @Nonnull Query query,
	                                                                  @Nonnull Consumer<S> validator,
	                                                                  @Nonnull Class<S> expectedType) {
		return queryEntities(evita, query, validator, false, expectedType);
	}

	@Nonnull
	default EvitaResponse<EntityClassifier> queryEntities(@Nonnull Evita evita,
	                                                        @Nonnull Query query,
	                                                        boolean allowEmpty) {
		return queryEntities(evita, query, allowEmpty, EntityClassifier.class);
	}

	@Nonnull
	default EvitaResponse<EntityClassifier> queryEntities(@Nonnull Evita evita,
	                                                        @Nonnull Query query,
	                                                        @Nonnull Consumer<EntityClassifier> validator,
	                                                        boolean allowEmpty) {
		return queryEntities(evita, query, validator, allowEmpty, EntityClassifier.class);
	}

	@Nonnull
	default <S extends Serializable> EvitaResponse<S> queryEntities(@Nonnull Evita evita,
	                                                                  @Nonnull Query query,
	                                                                  boolean allowEmpty,
	                                                                  @Nonnull Class<S> expectedType) {
		return queryEntities(evita, query, it -> {}, allowEmpty, expectedType);
	}

	@Nonnull
	default <S extends Serializable> EvitaResponse<S> queryEntities(@Nonnull Evita evita,
	                                                                  @Nonnull Query query,
	                                                                  @Nonnull Consumer<S> validator,
	                                                                  boolean allowEmpty,
	                                                                  @Nonnull Class<S> expectedType) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<S> response = session.query(query, expectedType);
				if (!allowEmpty) {
					assertFalse(response.getRecordData().isEmpty());
				}
				response.getRecordData().forEach(validator);
				return response;
			}
		);
	}


	@Nonnull
	default String serializeStringArrayToQueryString(@Nonnull List<String> items) {
		return Arrays.toString(items.stream().map(it -> "\"" + it + "\"").toArray());
	}

	@Nonnull
	default String serializeStringArrayToQueryString(@Nonnull String[] items) {
		return Arrays.toString(Arrays.stream(items).map(it -> "\"" + it + "\"").toArray());
	}

	@Nonnull
	default String serializeIntArrayToQueryString(@Nonnull List<Integer> items) {
		return Arrays.toString(items.toArray());
	}

	@Nonnull
	default String serializeIntArrayToQueryString(@Nonnull Integer[] items) {
		return Arrays.toString(items);
	}
}
