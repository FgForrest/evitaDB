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

import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.RestEntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.test.builder.MapBuilder;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer.separateAssociatedDataKeysByLocale;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer.separateAttributeKeysByLocale;
import static io.evitadb.test.builder.MapBuilder.map;

/**
 * Ancestor for tests for REST catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
abstract class CatalogRestDataEndpointFunctionalTest extends RestEndpointFunctionalTest implements ExternalApiFunctionTestsSupport {

	@Nonnull
	protected List<Map<String, Object>> createEntityDtos(@Nonnull List<? extends EntityClassifier> entityClassifiers) {
		return createEntityDtos(entityClassifiers, false);
	}

	@Nonnull
	protected List<Map<String, Object>> createEntityDtos(@Nonnull List<? extends EntityClassifier> entityClassifiers, boolean localized) {
		return entityClassifiers.stream()
			.map(it -> createEntityDto(it, localized))
			.toList();
	}

	@Nullable
	protected Map<String, Object> createEntityDto(@Nullable EntityClassifier entityClassifier) {
		return createEntityDto(entityClassifier, false);
	}

	@Nullable
	protected Map<String, Object> createEntityDto(@Nullable EntityClassifier entityClassifier, boolean localized) {
		if (entityClassifier == null) {
			return null;
		}

		final MapBuilder dto = map()
			.e(EntityDescriptor.PRIMARY_KEY.name(), entityClassifier.getPrimaryKey())
			.e(EntityDescriptor.TYPE.name(), entityClassifier.getType());

		if (entityClassifier instanceof SealedEntity entity) {
			createEntityBodyDto(dto, entity, localized);
			createAttributesDto(dto, entity.getLocales(), entity, localized);
			createAssociatedDataDto(dto, entity.getLocales(), entity, localized);
			createPricesDto(dto, entity);
			createReferencesDto(dto, entity, localized);
		} else if (entityClassifier instanceof EntityClassifierWithParent entity) {
			entity.getParentEntity()
				.ifPresent(parent -> dto.e(RestEntityDescriptor.PARENT_ENTITY.name(), createEntityDto(parent, localized)));
		}

		return dto.build();
	}

	private void createEntityBodyDto(@Nonnull MapBuilder entityDto, @Nonnull SealedEntity entity, boolean localized) {
		entityDto.e(EntityDescriptor.VERSION.name(), entity.version());

		entity.getParent().ifPresent(pk -> entityDto.e(RestEntityDescriptor.PARENT.name(), pk));
		entity.getParentEntity().ifPresent(parent -> entityDto.e(RestEntityDescriptor.PARENT_ENTITY.name(), createEntityDto(parent, localized)));
		if (!entity.getLocales().isEmpty()) {
			entityDto.e(EntityDescriptor.LOCALES.name(), entity.getLocales().stream().map(Locale::toLanguageTag).toList());
		}
		if (!entity.getAllLocales().isEmpty()) {
			entityDto.e(EntityDescriptor.ALL_LOCALES.name(), entity.getAllLocales().stream().map(Locale::toLanguageTag).toList());
		}
		if (entity.getPriceInnerRecordHandling() != PriceInnerRecordHandling.UNKNOWN) {
			entityDto.e(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), entity.getPriceInnerRecordHandling().name());
		}
	}

	private void createAttributesDto(@Nonnull MapBuilder parentDto,
	                                 @Nonnull Set<Locale> locales,
	                                 @Nonnull AttributesContract attributes,
	                                 boolean localized) {
		final Set<AttributeKey> attributeKeys = attributes.getAttributeKeys();
		if (!attributeKeys.isEmpty()) {
			final MapBuilder attributesDto;
			if (localized) {
				Assert.isPremiseValid(
					locales.size() == 1,
					"Localized entity must have exactly one locale."
				);
				attributesDto = createAttributesOfLangDto(attributes, attributeKeys, locales.iterator().next());
			} else {
				attributesDto = map();

				final Map<String, List<AttributeKey>> localeSeparatedKeys = separateAttributeKeysByLocale(locales, attributeKeys);

				final List<AttributesContract.AttributeKey> globalAttributeKeys = localeSeparatedKeys.remove(SectionedAttributesDescriptor.GLOBAL.name());
				if (!globalAttributeKeys.isEmpty()) {
					attributesDto.e(SectionedAttributesDescriptor.GLOBAL.name(), createAttributesOfLangDto(attributes, globalAttributeKeys, null));
				}

				final MapBuilder localizedAttributesBuilder = map();
				for (Entry<String, List<AttributeKey>> entry : localeSeparatedKeys.entrySet()) {
					final MapBuilder attributesForLocale = createAttributesOfLangDto(attributes, entry.getValue(), Locale.forLanguageTag(entry.getKey()));
					if (!attributesForLocale.isEmpty()) {
						localizedAttributesBuilder.e(entry.getKey(), attributesForLocale);
					}
				}
				if (!localizedAttributesBuilder.isEmpty()) {
					attributesDto.e(SectionedAttributesDescriptor.LOCALIZED.name(), localizedAttributesBuilder);
				}
			}

			parentDto.e(EntityDescriptor.ATTRIBUTES.name(), attributesDto);
		}
	}

	private MapBuilder createAttributesOfLangDto(@Nonnull AttributesContract attributes,
	                                             @Nonnull Collection<AttributesContract.AttributeKey> attributeKeys,
	                                             @Nullable Locale locale) {
		final MapBuilder dto = map();
		attributeKeys.forEach(attributeKey -> {
			final Optional<AttributeValue> attributeValue;
			if (attributeKey.localized()) {
				Assert.isPremiseValid(
					locale != null,
					"Locale must be provided for localized attribute key."
				);
				attributeValue = attributes.getAttributeValue(attributeKey.attributeName(), locale);
			} else {
				attributeValue = attributes.getAttributeValue(attributeKey.attributeName());
			}

			if (attributeValue.isPresent()) {
				dto.e(attributeKey.attributeName(), serializeToJsonValue(attributeValue.get().value()));
			} else {
				dto.e(attributeKey.attributeName(), null);
			}
		});
		return dto;
	}

	private void createAssociatedDataDto(@Nonnull MapBuilder parentDto,
	                                     @Nonnull Set<Locale> locales,
	                                     @Nonnull AssociatedDataContract associatedData,
	                                     boolean localized) {
		final Set<AssociatedDataKey> associatedDataKeys = associatedData.getAssociatedDataKeys();
		if (!associatedDataKeys.isEmpty()) {
			final MapBuilder associatedDataDto;
			if (localized) {
				Assert.isPremiseValid(
					locales.size() == 1,
					"Localized entity must have exactly one locale."
				);
				associatedDataDto = createAssociatedDataOfLangDto(associatedData, associatedDataKeys, locales.iterator().next());
			} else {
				associatedDataDto = map();

				final Map<String, List<AssociatedDataKey>> localeSeparatedKeys = separateAssociatedDataKeysByLocale(locales, associatedDataKeys);

				final List<AssociatedDataKey> globalAssociatedData = localeSeparatedKeys.remove(SectionedAssociatedDataDescriptor.GLOBAL.name());
				if (!globalAssociatedData.isEmpty()) {
					associatedDataDto.e(SectionedAssociatedDataDescriptor.GLOBAL.name(), createAssociatedDataOfLangDto(associatedData, globalAssociatedData, null));
				}

				final MapBuilder localizedAssociatedDataBuilder = map();
				for (Entry<String, List<AssociatedDataKey>> entry : localeSeparatedKeys.entrySet()) {
					final MapBuilder associatedDataForLocale = createAssociatedDataOfLangDto(associatedData, entry.getValue(), Locale.forLanguageTag(entry.getKey()));
					if (!associatedDataForLocale.isEmpty()) {
						localizedAssociatedDataBuilder.e(entry.getKey(), associatedDataForLocale);
					}
				}
				if (!localizedAssociatedDataBuilder.isEmpty()) {
					associatedDataDto.e(SectionedAssociatedDataDescriptor.LOCALIZED.name(), localizedAssociatedDataBuilder);
				}
			}

			parentDto.e(EntityDescriptor.ASSOCIATED_DATA.name(), associatedDataDto);
		}
	}

	private MapBuilder createAssociatedDataOfLangDto(@Nonnull AssociatedDataContract associatedData,
	                                                 @Nonnull Collection<AssociatedDataKey> associatedDataKeys,
	                                                 @Nullable Locale locale) {
		final MapBuilder dto = map();
		associatedDataKeys.forEach(associatedDataKey -> {
			final Optional<AssociatedDataValue> associatedDataValue;
			if (associatedDataKey.localized()) {
				Assert.isPremiseValid(
					locale != null,
					"Locale must be provided for localized associated data key."
				);
				associatedDataValue = associatedData.getAssociatedDataValue(associatedDataKey.associatedDataName(), locale);
			} else {
				associatedDataValue = associatedData.getAssociatedDataValue(associatedDataKey.associatedDataName());
			}

			if (associatedDataValue.isPresent()) {
				dto.e(associatedDataKey.associatedDataName(), serializeToJsonValue(associatedDataValue.get().value()));
			} else {
				dto.e(associatedDataKey.associatedDataName(), null);
			}
		});
		return dto;
	}

	private void createPricesDto(@Nonnull MapBuilder entityDto,
	                             @Nonnull EntityContract entity) {
		final Collection<PriceContract> prices = entity.getPrices();
		if (!prices.isEmpty()) {
			entityDto.e(
				EntityDescriptor.PRICES.name(),
				entity.getPrices()
					.stream()
					.map(price -> createEntityPriceDto(price).build())
					.toList()
			);
		}

		entity.getPriceForSaleIfAvailable()
			.ifPresent(price -> entityDto.e(EntityDescriptor.PRICE_FOR_SALE.name(), createEntityPriceDto(price)));
	}

	@Nonnull
	private MapBuilder createEntityPriceDto(@Nonnull PriceContract price) {
		return map()
			.e(PriceDescriptor.PRICE_ID.name(), price.priceId())
			.e(PriceDescriptor.PRICE_LIST.name(), price.priceList())
			.e(PriceDescriptor.CURRENCY.name(), price.currencyCode())
			.e(PriceDescriptor.INNER_RECORD_ID.name(), price.innerRecordId())
			.e(PriceDescriptor.SELLABLE.name(), price.sellable())
			.e(PriceDescriptor.PRICE_WITHOUT_TAX.name(), price.priceWithoutTax().toString())
			.e(PriceDescriptor.PRICE_WITH_TAX.name(), price.priceWithTax().toString())
			.e(PriceDescriptor.TAX_RATE.name(), price.taxRate().toString())
			.e(PriceDescriptor.VALIDITY.name(), Optional.ofNullable(price.validity())
				.map(this::serializeToJsonValue)
				.orElse(null));
	}

	private void createReferencesDto(@Nonnull MapBuilder entityDto,
	                                 @Nonnull SealedEntity entity,
	                                 boolean localized) {
		final Collection<ReferenceContract> references = entity.getReferences();
		if (!references.isEmpty()) {
			references.stream()
				.map(ReferenceContract::getReferenceName)
				.collect(Collectors.toCollection(TreeSet::new))
				.forEach(it -> createReferencesOfNameDto(entityDto, entity, it, localized));
		}
	}

	private void createReferencesOfNameDto(@Nonnull MapBuilder entityDto,
	                                       @Nonnull SealedEntity entity,
	                                       @Nonnull String referenceName,
	                                       boolean localized) {
		final Collection<ReferenceContract> groupedReferences = entity.getReferences(referenceName);
		final Optional<ReferenceContract> anyReferenceFound = groupedReferences.stream().findFirst();
		if (anyReferenceFound.isPresent()) {
			final ReferenceContract firstReference = anyReferenceFound.get();
			final String nodeReferenceName = firstReference.getReferenceSchema()
				.map(it -> it.getNameVariant(NamingConvention.CAMEL_CASE))
				.orElse(referenceName);

			if (firstReference.getReferenceCardinality() == Cardinality.EXACTLY_ONE ||
				firstReference.getReferenceCardinality() == Cardinality.ZERO_OR_ONE) {
				Assert.isPremiseValid(groupedReferences.size() == 1, "Reference cardinality is: " +
					firstReference.getReferenceCardinality() + " but found " + groupedReferences.size() +
					" references with same name: " + referenceName);

				entityDto.e(nodeReferenceName, createReferenceDto(entity.getLocales(), firstReference, localized));
			} else {
				entityDto.e(
					nodeReferenceName,
					groupedReferences.stream()
						.map(it -> createReferenceDto(entity.getLocales(), it, localized).build())
						.toList()
				);
			}
		}
	}

	@Nonnull
	private MapBuilder createReferenceDto(@Nonnull Set<Locale> locales, @Nonnull ReferenceContract reference, boolean localized) {
		final MapBuilder dto = map()
			.e(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), reference.getReferencedPrimaryKey());

		reference.getReferencedEntity()
			.ifPresent(entity -> dto.e(ReferenceDescriptor.REFERENCED_ENTITY.name(), createEntityDto(entity, localized)));

		reference.getGroupEntity()
			.map(it -> (EntityClassifier) it)
			.or(reference::getGroup)
			.ifPresent(groupEntity -> dto.e(ReferenceDescriptor.GROUP_ENTITY.name(), createEntityDto(groupEntity, localized)));

		createAttributesDto(dto, locales, reference, localized);

		return dto;
	}
}
