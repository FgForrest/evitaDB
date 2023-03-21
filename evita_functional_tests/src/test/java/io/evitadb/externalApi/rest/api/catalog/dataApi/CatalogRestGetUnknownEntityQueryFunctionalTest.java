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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for REST catalog unknown single entity query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestGetUnknownEntityQueryFunctionalTest extends CatalogRestDataEndpointFunctionalTest {

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return unknown entity by globally unique attribute")
	void shouldReturnUnknownEntityByGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final String codeAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_CODE);
		final SealedEntity entityWithCode = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_CODE), codeAttribute)
		);

		testRestCall()
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, codeAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.name(), false)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithCode.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity by localized globally unique attribute")
	void shouldReturnRichUnknownEntityByLocalizedGloballyUniqueAttribute(Evita evita, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entityWithUrl = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);

		testRestCall()
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, urlAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.e(EntityDescriptor.ATTRIBUTES.name(), createEntityAttributes(entityWithUrl, true, Locale.ENGLISH))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return rich unknown entity by localized globally unique attribute with locale in URL")
	void shouldReturnRichUnknownEntityByLocalizedGloballyUniqueAttributeWithLocaleInUrl(Evita evita, List<SealedEntity> originalProductEntities) {
		final String urlAttribute = getRandomAttributeValue(originalProductEntities, ATTRIBUTE_URL, Locale.ENGLISH);
		final SealedEntity entityWithUrl = findEntity(
			originalProductEntities,
			it -> Objects.equals(it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH), urlAttribute)
		);

		testRestCall()
			.urlPathSuffix("/" + Locale.ENGLISH.toLanguageTag() + "/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_URL, urlAttribute)
				.e(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), entityWithUrl.getPrimaryKey())
						.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
						.e(EntityDescriptor.LOCALES.name(), List.of(Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.ALL_LOCALES.name(), List.of(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
						.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
						.e(EntityDescriptor.ATTRIBUTES.name(), createEntityAttributes(entityWithUrl, false, Locale.ENGLISH))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error when request contains no parameter")
	void shouldReturnErrorWhenRequestContainsNoParameter(Evita evita) {
		testRestCall()
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(404)
			.body("message", equalTo("Requested resource wasn't found."));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return price for single entity")
	void shouldReturnPriceForSingleEntity(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntityWithPrice(originalProductEntities);

		testRestCall()
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.name(), PriceContentMode.RESPECTING_FILTER.name())
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("prices", equalTo(createPricesDto(entity)));
	}


	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return associated data with custom locale for single entity")
	void shouldReturnAssociatedDataWithCustomLocaleForSingleEntity(Evita evita, List<SealedEntity> originalProductEntities) {
		final SealedEntity entity = findEntity(
			originalProductEntities,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		testRestCall()
			.urlPathSuffix("/entity/get")
			.httpMethod(Request.METHOD_GET)
			.requestParams(map()
				.e(ATTRIBUTE_CODE, entity.getAttribute(ATTRIBUTE_CODE))
				.e(FetchEntityEndpointHeaderDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
				.e(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.TRUE)
				.build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createEntityDtoWithAssociatedData(entity, Locale.ENGLISH, true)));
	}

	@Override
	@Nonnull
	protected Map<String, Object> createEntityDtoWithAssociatedData(@Nonnull SealedEntity entity, @Nonnull Locale requestedLocale, boolean distinguishLocalizedData) {
		final ArrayList<Object> entityLocales = new ArrayList<>(1);
		entityLocales.add(entity.getLocales().stream().filter(locale -> locale.equals(requestedLocale)).findFirst().orElseThrow().toLanguageTag());

		final Map<String, Object> associatedData;
		if (distinguishLocalizedData) {
			associatedData = map()
				.e(SectionedAssociatedDataDescriptor.GLOBAL.name(), map()
					.e("referencedFiles", map()
						.build()
					)
					.e("localization", map()
						.build()
					)
					.build())
				.e(SectionedAssociatedDataDescriptor.LOCALIZED.name(), map()
					.e(Locale.ENGLISH.toLanguageTag(), map()
						.e(ASSOCIATED_DATA_LABELS, map()
							.build())
						.build())
					.build())
				.build();
		} else {
			associatedData = map()
				.e("referencedFiles", map()
					.build()
				)
				.e("localization", map()
					.build()
				)
				.e(ASSOCIATED_DATA_LABELS, map()
					.build())
				.build();
		}

		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), entityLocales)
			.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().map(Locale::toLanguageTag).toList())
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.e(EntityDescriptor.ASSOCIATED_DATA.name(), associatedData)
			.build();
	}

	@Nonnull
	private SealedEntity findEntity(@Nonnull List<SealedEntity> originalProductEntities,
	                                @Nonnull Predicate<SealedEntity> filter) {
		return originalProductEntities.stream()
			.filter(filter)
			.findFirst()
			.orElseThrow(() -> new EvitaInternalError("No entity to test."));
	}

	@Nonnull
	private SealedEntity findEntityWithPrice(List<SealedEntity> originalProductEntities) {
		return findEntity(
			originalProductEntities,
			it -> it.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).size() == 1
		);
	}
}
