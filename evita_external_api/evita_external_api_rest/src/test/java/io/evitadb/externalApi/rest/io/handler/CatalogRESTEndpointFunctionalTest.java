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

package io.evitadb.externalApi.rest.io.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.dataType.Range;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.testSuite.RESTEndpointFunctionalTest;
import io.evitadb.test.Entities;
import io.evitadb.test.builder.MapBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.text.NumberFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer.separateAttributeKeysByLocale;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.CURRENCY_CZK;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static io.evitadb.test.generator.DataGenerator.PRICE_LIST_BASIC;

/**
 * Ancestor for tests for REST catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
abstract class CatalogRESTEndpointFunctionalTest extends RESTEndpointFunctionalTest {

	final ObjectJsonSerializer jsonSerializer = new ObjectJsonSerializer(new ObjectMapper());

	@Nullable
	protected Object serializeToJsonValue(@Nullable Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Object[] array) {
			final ArrayList<Object> objects = new ArrayList<>(array.length);
			for (Object item : array) {
				objects.add(serializeToJsonValue(item));
			}
			return objects;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Integer integer) {
			return integer;
		}
		if (value instanceof Range<?> range) {
			final List<Object> serializedRange = new ArrayList<>(2);
			if (range.getPreciseFrom() != null) {
				serializedRange.add(serializeToJsonValue(range.getPreciseFrom()));
			} else {
				serializedRange.add(null);
			}
			if (range.getPreciseTo() != null) {
				serializedRange.add(serializeToJsonValue(range.getPreciseTo()));
			} else {
				serializedRange.add(null);
			}
			return serializedRange;
		}
		return jsonSerializer.serializeObject(value).asText();
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName) {
		return getRandomAttributeValue(originalProductEntities, attributeName, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities, @Nonnull String attributeName, int order) {
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
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities,
	                                                                             @Nonnull String attributeName,
	                                                                             @Nonnull Locale locale) {
		return getRandomAttributeValue(originalProductEntities, attributeName, locale, 10);
	}

	/**
	 * Returns value of "random" value in the dataset.
	 */
	protected <T extends Serializable> T getRandomAttributeValue(@Nonnull List<SealedEntity> originalProductEntities,
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
	protected Map<String, Object> createEntityDtoWithFormattedPriceForSale(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICE_FOR_SALE.name(), map()
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax()))
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithFormattedPrice(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICE.name(), map()
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax()))
				.build())
			.build();
	}


	@Nonnull
	protected Map<String, Object> createEntityDtoWithFormattedPrices(@Nonnull SealedEntity entity) {
		final NumberFormat priceFormatter = NumberFormat.getCurrencyInstance(CZECH_LOCALE);
		priceFormatter.setCurrency(CURRENCY_CZK);

		return map()
			.e(EntityDescriptor.PRICES.name(), List.of(
				map()
					.e(PriceDescriptor.PRICE_WITH_TAX.name(), priceFormatter.format(entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax()))
					.build()
			))
			.build();
	}

	@Nonnull
	protected ArrayList<Map<String, Object>> createPricesDto(@Nonnull SealedEntity entity, @Nullable Currency currency, @Nonnull String priceList) {
		final ArrayList<Map<String, Object>> prices = new ArrayList<>();
		entity.getPrices().stream()
			.filter(price -> price.getCurrency().equals(currency) || currency == null)
			.filter(price -> price.getPriceList().equals(priceList))
			.forEach(price -> {
					prices.add(map()
						.e(PriceDescriptor.PRICE_ID.name(), price.getPriceId())
						.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
						.e(PriceDescriptor.CURRENCY.name(), currency != null ? currency.toString() : CURRENCY_CZK)
						.e(PriceDescriptor.INNER_RECORD_ID.name(), price.getInnerRecordId())
						.e(PriceDescriptor.SELLABLE.name(), price.isSellable())
						.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), price.getPriceWithoutTax().toString())
						.e(PriceDescriptor.PRICE_WITH_TAX.name(), price.getPriceWithTax().toString())
						.e(PriceDescriptor.TAX_RATE.name(), price.getTaxRate().toString())
						.e(PriceDescriptor.VALIDITY.name(), convertRangeIntoArrayList(price.getValidity()))
						.build());
				}
			);
		return prices;
	}

	@Nonnull
	protected ArrayList<Map<String, Object>> createPricesDto(@Nonnull SealedEntity entity) {
		final ArrayList<Map<String, Object>> prices = new ArrayList<>();
		entity.getPrices().stream()
			.forEach(price -> {
					prices.add(map()
						.e(PriceDescriptor.PRICE_ID.name(), price.getPriceId())
						.e(PriceDescriptor.PRICE_LIST.name(), price.getPriceList())
						.e(PriceDescriptor.CURRENCY.name(), price.getCurrency().toString())
						.e(PriceDescriptor.INNER_RECORD_ID.name(), price.getInnerRecordId())
						.e(PriceDescriptor.SELLABLE.name(), price.isSellable())
						.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), price.getPriceWithoutTax().toString())
						.e(PriceDescriptor.PRICE_WITH_TAX.name(), price.getPriceWithTax().toString())
						.e(PriceDescriptor.TAX_RATE.name(), price.getTaxRate().toString())
						.e(PriceDescriptor.VALIDITY.name(), convertRangeIntoArrayList(price.getValidity()))
						.build());
				}
			);
		return prices;
	}

	@Nonnull
	protected Map<String, Object> createPriceForSaleDto(@Nonnull SealedEntity entity, @Nonnull Currency currency, @Nonnull String priceList) {
		final PriceContract price = entity.getPriceForSale(currency, null, priceList).orElseThrow();
		return map()
			.e(PriceDescriptor.PRICE_ID.name(), price.getPriceId())
			.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
			.e(PriceDescriptor.CURRENCY.name(), currency.toString())
			.e(PriceDescriptor.INNER_RECORD_ID.name(), price.getInnerRecordId())
			.e(PriceDescriptor.SELLABLE.name(), price.isSellable())
			.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), price.getPriceWithoutTax().toString())
			.e(PriceDescriptor.PRICE_WITH_TAX.name(), price.getPriceWithTax().toString())
			.e(PriceDescriptor.TAX_RATE.name(), price.getTaxRate().toString())
			.e(PriceDescriptor.VALIDITY.name(), convertRangeIntoArrayList(price.getValidity()))
			.build();
	}

	@Nullable
	protected List<String> convertRangeIntoArrayList(@Nullable Range<?> range) {
		if (range == null) {
			return null;
		}

		final ArrayNode serializedRange = (ArrayNode) jsonSerializer.serializeObject(range);
		final ArrayList<String> listOfValues = new ArrayList<>(2);
		listOfValues.add(serializedRange.get(0).textValue());
		listOfValues.add(serializedRange.get(1).textValue());
		return listOfValues;
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithPrice(@Nonnull SealedEntity entity) {
		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.PRICE.name(), map()
				.e(TYPENAME_FIELD, PriceDescriptor.THIS.name())
				.e(PriceDescriptor.CURRENCY.name(), CURRENCY_CZK.toString())
				.e(PriceDescriptor.PRICE_LIST.name(), PRICE_LIST_BASIC)
				.e(PriceDescriptor.PRICE_WITH_TAX.name(), entity.getPrices(CURRENCY_CZK, PRICE_LIST_BASIC).iterator().next().getPriceWithTax().toString())
				.build())
			.build();
	}

	@Nonnull
	protected Map<String, Object> createEntityDtoWithAssociatedData(@Nonnull SealedEntity entity, @Nonnull Locale requestedLocale,
	                                                                @Nonnull boolean distinguishLocalizedData) {
		final ArrayList<Object> entityLocales = new ArrayList<>(1);
		entityLocales.add(entity.getLocales().stream().filter(locale -> locale.equals(requestedLocale)).findFirst().orElseThrow().toLanguageTag());

		final Map<String, Object> associatedData;
		if (distinguishLocalizedData) {
			associatedData = map()
				.e(SectionedAssociatedDataDescriptor.LOCALIZED.name(), map()
					.e(Locale.ENGLISH.toLanguageTag(), map()
						.e(ASSOCIATED_DATA_LABELS, map()
							.e("root", map().build())
							.build())
						.build())
					.build())
				.build();
		} else {
			associatedData = map()
				.e(ASSOCIATED_DATA_LABELS, map()
					.e("root", map().build())
					.build())
				.build();
		}

		return map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), Entities.PRODUCT)
			.e(EntityDescriptor.LOCALES.name(), entityLocales)
			.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().map(Locale::toLanguageTag).collect(Collectors.toCollection(ArrayList::new)))
			.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name())
			.e(EntityDescriptor.ASSOCIATED_DATA.name(), associatedData)
			.build();
	}

	/**
	 * Creates reference DTO - this method can be used when reference {@link io.evitadb.api.requestResponse.schema.Cardinality}
	 * is ZERO_OR_MORE or ONE_OR_MORE
	 */
	@Nonnull
	protected List<Map<String, Object>> createReferencesDto(@Nonnull SealedEntity entity, @Nonnull String entityName, boolean withLocales) {
		final Collection<ReferenceContract> referenceContracts = entity.getReferences(entityName);
		final ArrayList<Map<String, Object>> references = new ArrayList<>();
		referenceContracts.stream()
			.forEach(reference -> {
					references.add(
						serializeSingleReference(reference, withLocales)
					);
				}
			);
		return references;
	}

	/**
	 * Creates single reference DTO - this method can be used when reference {@link io.evitadb.api.requestResponse.schema.Cardinality}
	 * is EXACTLY_ONE or ZERO_OR_ONE
	 */
	@Nonnull
	protected Map<String, Object> createReferenceDto(@Nonnull SealedEntity entity, @Nonnull String entityName, boolean withLocales) {
		final Collection<ReferenceContract> referenceContracts = entity.getReferences(entityName);
		final ReferenceContract reference = referenceContracts.stream().findFirst().orElseThrow();

		return serializeSingleReference(reference, withLocales);
	}

	protected Map<String, ?> createEntityAttributes(@Nonnull EntityContract entity, boolean distinguishLocalized, @Nullable Locale locale) {
		if (!entity.getAttributeKeys().isEmpty()) {
			if (distinguishLocalized) {
				final MapBuilder attributesMap = map();

				final Map<String, List<AttributeKey>> localeSeparatedKeys = separateAttributeKeysByLocale(entity, entity.getAttributeKeys());

				final List<AttributesContract.AttributeKey> globalAttributes = localeSeparatedKeys.remove(SectionedAttributesDescriptor.GLOBAL.name());
				if (!globalAttributes.isEmpty()) {
					attributesMap.e(SectionedAttributesDescriptor.GLOBAL.name(), createAttributesMap(entity, globalAttributes, locale));
				}

				final MapBuilder localizedAttributesBuilder = map();
				for (Entry<String, List<AttributeKey>> entry : localeSeparatedKeys.entrySet()) {
					final Map<String, Object> localized = createAttributesMap(entity, entry.getValue(), locale);
					if (!localized.isEmpty() && (locale == null || locale.toLanguageTag().equals(entry.getKey()))) {
						localizedAttributesBuilder.e(entry.getKey(), localized);
					}
				}
				final Map<String, Object> localizedAttributesMap = localizedAttributesBuilder.build();
				if (!localizedAttributesMap.isEmpty()) {
					attributesMap.e(SectionedAttributesDescriptor.LOCALIZED.name(), localizedAttributesMap);
				}
				return attributesMap.build();
			} else {
				final Set<AttributeKey> attributeKeys = entity.getAttributeKeys();
				return createAttributesMap(entity, attributeKeys, locale);
			}
		} else {
			return map().build();
		}
	}

	protected Map<String, Object> createAttributesMap(@Nonnull EntityContract entity, @Nonnull Collection<AttributesContract.AttributeKey> attributeKeys,
	                                                  @Nonnull Locale locale) {
		final MapBuilder attributesMap = map();
		attributeKeys.forEach(attributeKey -> {
			final Optional<AttributeValue> attributeValue = attributeKey.isLocalized() ?
				entity.getAttributeValue(attributeKey.getAttributeName(), locale) :
				entity.getAttributeValue(attributeKey.getAttributeName());
			if (attributeValue.isPresent()) {
				attributesMap.e(attributeKey.getAttributeName(), serializeToJsonValue(attributeValue.get().getValue()));
			} else {
				attributesMap.e(attributeKey.getAttributeName(), null);
			}
		});
		return attributesMap.build();
	}

	private Map<String, Object> serializeSingleReference(@Nonnull ReferenceContract reference, boolean withLocales) {
		final MapBuilder referenceAttributesBuilder = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), reference.getReferencedPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), reference.getReferencedEntityType());
		if (withLocales) {
			referenceAttributesBuilder
				.e(EntityDescriptor.LOCALES.name(), new ArrayList<>())
				.e(EntityDescriptor.ALL_LOCALES.name(), Arrays.asList(CZECH_LOCALE.toLanguageTag(), Locale.ENGLISH.toLanguageTag()))
				.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.UNKNOWN.name());
		}

		return map().
			e(ReferenceDescriptor.REFERENCED_ENTITY.name(), referenceAttributesBuilder.build())
			.e(ReferenceDescriptor.ATTRIBUTES.name(), convertAttributesIntoMap(reference))
			.build();
	}

	private Map<String, Object> convertAttributesIntoMap(ReferenceContract reference) {
		final MapBuilder attrsMap = map();
		reference.getAttributeValues().forEach(attributeValue -> attrsMap.e(attributeValue.getKey().getAttributeName(), serializeToJsonValue(attributeValue.getValue())));
		return attrsMap.build();
	}
}
