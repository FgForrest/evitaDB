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

package io.evitadb.externalApi.grpc.requestResponse.data;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.*;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceAttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.externalApi.grpc.dataType.ComplexDataObjectConverter;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcLocalizedAttribute.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Class used for building suitable form of entity based on {@link SealedEntity}. The main methods here are
 * {@link #toGrpcSealedEntity(SealedEntity)} and {@link #toGrpcBinaryEntity(BinaryEntity)}, which are used for building
 * {@link GrpcSealedEntity} and {@link GrpcBinaryEntity} respectively.
 * Decision factor of which is going to be used is whether {@link SessionFlags#BINARY} was passed when creating a session.
 *
 * @author Tomáš Pozler, 2022
 */
public class EntityConverter {

	/**
	 * Method converts {@link GrpcSealedEntity} to the {@link SealedEntity} that can be used on the client side.
	 */
	@Nonnull
	public static SealedEntity toSealedEntity(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcSealedEntity grpcEntity
		) {
		final SealedEntitySchema entitySchema = entitySchemaFetcher.apply(grpcEntity);
		return new EntityDecorator(
			Entity._internalBuild(
				grpcEntity.getPrimaryKey(),
				grpcEntity.getVersion(),
				entitySchema,
				grpcEntity.hasHierarchicalPlacement() ? toHierarchicalPlacement(grpcEntity.getHierarchicalPlacement()) : null,
				grpcEntity.getReferencesList()
					.stream()
					.map(it -> toReference(entitySchema, entitySchemaFetcher, evitaRequest, it))
					.collect(Collectors.toList()),
				new Attributes(
					entitySchema,
					toAttributeValues(
						grpcEntity.getGlobalAttributesMap(),
						grpcEntity.getLocalizedAttributesMap()
					)
				),
				new AssociatedData(
					entitySchema,
					toAssociatedDataValues(
						grpcEntity.getGlobalAssociatedDataMap(),
						grpcEntity.getLocalizedAssociatedDataMap()
					)
				),
				new Prices(
					grpcEntity.getVersion(),
					grpcEntity.getPricesList().stream().map(EntityConverter::toPrice).collect(Collectors.toList()),
					EvitaEnumConverter.toPriceInnerRecordHandling(grpcEntity.getPriceInnerRecordHandling())
				),
				grpcEntity.getLocalesList()
					.stream()
					.map(EvitaDataTypesConverter::toLocale)
					.collect(Collectors.toSet())
			),
			entitySchema,
			evitaRequest
		);
	}

	/**
	 * Method converts {@link GrpcHierarchicalPlacement} to the {@link HierarchicalPlacement} that can be used on the client side.
	 */
	@Nonnull
	public static HierarchicalPlacement toHierarchicalPlacement(
		@Nonnull GrpcHierarchicalPlacement hierarchicalPlacement
	) {
		return new HierarchicalPlacement(
			hierarchicalPlacement.getVersion(),
			hierarchicalPlacement.hasParentPrimaryKey() ? hierarchicalPlacement.getParentPrimaryKey().getValue() : null,
			hierarchicalPlacement.getOrderAmongSiblings()
		);
	}

	/**
	 * Method converts {@link GrpcReference} to the {@link ReferenceContract} that can be used on the client side.
	 */
	@Nonnull
	public static ReferenceContract toReference(
		@Nonnull SealedEntitySchema entitySchema,
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcReference grpcReference
	) {
		return new ReferenceDecorator(
			new Reference(
				entitySchema,
				grpcReference.getVersion(),
				grpcReference.getReferenceName(),
				grpcReference.getReferencedEntityReference().getPrimaryKey(),
				grpcReference.getReferencedEntityReference().getEntityType(),
				EvitaEnumConverter.toCardinality(grpcReference.getReferenceCardinality()),
				grpcReference.hasGroupReferencedEntity() ?
					new GroupEntityReference(
						grpcReference.getGroupReferencedEntityReference().getEntityType(),
						grpcReference.getGroupReferencedEntityReference().getPrimaryKey(),
						grpcReference.getGroupReferencedEntityReference().getVersion(),
						false
					) :
					null,
				toAttributeValues(
					grpcReference.getGlobalAttributesMap(),
					grpcReference.getLocalizedAttributesMap()
				),
				false
			),
			grpcReference.hasReferencedEntity() ?
				toSealedEntity(entitySchemaFetcher, evitaRequest, grpcReference.getReferencedEntity()) :
				null,
			grpcReference.hasGroupReferencedEntity() ?
				toSealedEntity(entitySchemaFetcher, evitaRequest,grpcReference.getGroupReferencedEntity()) :
				null,
			new ReferenceAttributeValueSerializablePredicate(evitaRequest)
		);
	}

	/**
	 * From provided {@link SealedEntity} builds all essential parts of {@link GrpcSealedEntity}, such as
	 * collections of {@link GrpcEvitaDataTypes} representing {@link AttributeValue}, {@link GrpcEvitaAssociatedDataValue},
	 * {@link GrpcReference} and {@link GrpcPrice} and from those builds new instance of {@link GrpcSealedEntity}.
	 *
	 * @param entity {@link SealedEntity} to convert
	 * @return new instance of {@link GrpcSealedEntity}
	 */
	@Nonnull
	public static GrpcSealedEntity toGrpcSealedEntity(@Nonnull SealedEntity entity) {
		//noinspection ConstantConditions
		final GrpcSealedEntity.Builder entityBuilder = GrpcSealedEntity.newBuilder()
			.setEntityType(entity.getType())
			.setPrimaryKey(entity.getPrimaryKey())
			.setSchemaVersion(entity.getSchema().getVersion())
			.setVersion(entity.getVersion());
		final int entityLocaleCount = entity.getAttributeLocales().size();
		if (!entity.getAttributeValues().isEmpty()) {
			final AttributesHolder attributes = buildAttributes(
				entityLocaleCount,
				entity.getSchema().getAttributes().size(),
				entity.getAttributeValues()
			);
			entityBuilder
				.putAllLocalizedAttributes(attributes.localizedAttributesMap)
				.putAllGlobalAttributes(attributes.globalAttributesMap);
		}

		entityBuilder.setPriceInnerRecordHandling(
			EvitaEnumConverter.toGrpcPriceInnerRecordHandling(entity.getPriceInnerRecordHandling())
		);

		if (!entity.getPrices().isEmpty()) {
			entityBuilder.addAllPrices(entity.getPrices().stream().map(EntityConverter::toGrpcPrice).toList());

			final Optional<PriceContract> priceForSale = entity.getPriceForSaleIfAvailable();
			priceForSale.ifPresent(it -> entityBuilder.setPriceForSale(toGrpcPrice(it)));
		}

		if (!entity.getReferences().isEmpty()) {
			for (ReferenceContract reference : entity.getReferences()) {
				final Collection<AttributeValue> attributeValues = reference.getAttributeValues();
				final AttributesHolder attributes = buildAttributes(
					entityLocaleCount,
					attributeValues.size(),
					attributeValues
				);

				final GrpcReference.Builder grpcReferenceBuilder = GrpcReference.newBuilder()
					.setVersion(reference.getVersion())
					.setReferenceName(reference.getReferenceName())
					.setReferenceCardinality(EvitaEnumConverter.toGrpcCardinality(reference.getReferenceCardinality()))
					.setVersion(reference.getVersion());
				if (reference.getReferencedEntity().isPresent()) {
					grpcReferenceBuilder.setReferencedEntity(toGrpcSealedEntity(reference.getReferencedEntity().get()));
				} else {
					grpcReferenceBuilder.setReferencedEntityReference(GrpcEntityReference.newBuilder()
						.setEntityType(reference.getReferencedEntityType())
						.setPrimaryKey(reference.getReferencedPrimaryKey()).build());
				}

				if (reference.getGroupEntity().isPresent()) {
					grpcReferenceBuilder.setGroupReferencedEntity(toGrpcSealedEntity(reference.getGroupEntity().get()));
				} else if (reference.getGroup().isPresent()) {
					final GroupEntityReference theGroup = reference.getGroup().get();
					grpcReferenceBuilder.setGroupReferencedEntityReference(GrpcEntityReference.newBuilder()
						.setEntityType(theGroup.getType())
						.setPrimaryKey(theGroup.getPrimaryKey())
						.setVersion(theGroup.getVersion())
						.build()
					);
				}

				grpcReferenceBuilder
					.putAllLocalizedAttributes(attributes.localizedAttributesMap)
					.putAllGlobalAttributes(attributes.globalAttributesMap);

				entityBuilder.addReferences(grpcReferenceBuilder);
			}
		}

		if (!entity.getAssociatedDataValues().isEmpty()) {
			final Map<Locale, GrpcLocalizedAssociatedData.Builder> localizedAssociatedData = CollectionUtils.createHashMap(entity.getLocales().size());
			final Map<String, GrpcEvitaAssociatedDataValue> globalAssociatedData = CollectionUtils.createHashMap(entity.getSchema().getAssociatedData().size());
			for (AssociatedDataContract.AssociatedDataValue theAssociatedData : entity.getAssociatedDataValues()) {
				final GrpcEvitaAssociatedDataValue associatedDataValue = EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(
					theAssociatedData.getValue(), theAssociatedData.getVersion()
				);

				if (theAssociatedData.getKey().isLocalized()) {
					localizedAssociatedData.computeIfAbsent(
							theAssociatedData.getKey().getLocale(),
							associatedDataName -> GrpcLocalizedAssociatedData.newBuilder()
						)
						.putAssociatedData(theAssociatedData.getKey().getAssociatedDataName(), associatedDataValue);
				} else {
					globalAssociatedData.put(theAssociatedData.getKey().getAssociatedDataName(), associatedDataValue);
				}
			}

			entityBuilder
				.putAllLocalizedAssociatedData(
					localizedAssociatedData.entrySet()
						.stream()
						.collect(
							Collectors.toMap(
								it -> it.getKey().toLanguageTag(),
								it -> it.getValue().build()
							)
						)
				)
				.putAllGlobalAssociatedData(globalAssociatedData);
		}

		entity.getHierarchicalPlacement()
			.ifPresent(hierarchicalPlacement -> {
				final GrpcHierarchicalPlacement.Builder builder = GrpcHierarchicalPlacement.newBuilder()
					.setOrderAmongSiblings(hierarchicalPlacement.getOrderAmongSiblings());

				ofNullable(hierarchicalPlacement.getParentPrimaryKey())
					.ifPresent(it -> builder.setParentPrimaryKey(Int32Value.newBuilder().setValue(it).build()));

				entityBuilder.setHierarchicalPlacement(
					builder.build()
				);
			});

		entityBuilder.addAllLocales(
			entity.getAllLocales()
				.stream()
				.map(EvitaDataTypesConverter::toGrpcLocale)
				.toList()
		);

		return entityBuilder.build();
	}

	/**
	 * This method converts {@link BinaryEntity} to {@link GrpcBinaryEntity}.
	 *
	 * @param binaryEntity Evita's entity in with binary storage parts
	 * @return built {@link GrpcBinaryEntity}
	 */
	@Nonnull
	public static GrpcBinaryEntity toGrpcBinaryEntity(@Nonnull BinaryEntity binaryEntity) {
		int attributeSize = binaryEntity.getAttributeStorageParts().length;
		final List<ByteString> attributes = new ArrayList<>(attributeSize);
		for (int i = 0; i < attributeSize; i++) {
			attributes.add(ByteString.copyFrom(binaryEntity.getAttributeStorageParts()[i]));
		}
		int associatedDataSize = binaryEntity.getAssociatedDataStorageParts().length;
		final List<ByteString> associatedData = new ArrayList<>(associatedDataSize);
		for (int i = 0; i < associatedDataSize; i++) {
			associatedData.add(ByteString.copyFrom(binaryEntity.getAssociatedDataStorageParts()[i]));
		}
		final GrpcBinaryEntity.Builder binaryEntityBuilder = GrpcBinaryEntity.newBuilder()
			.setEntityType(binaryEntity.getType())
			.setPrimaryKey(binaryEntity.getPrimaryKey())
			.setSchemaVersion(binaryEntity.getSchema().getVersion())
			.setEntityStoragePart(ByteString.copyFrom(binaryEntity.getEntityStoragePart()))
			.addAllAttributeStorageParts(attributes)
			.addAllAssociatedDataStorageParts(associatedData);

		if (binaryEntity.getPriceStoragePart() != null) {
			binaryEntityBuilder.setPriceStoragePart(ByteString.copyFrom(binaryEntity.getPriceStoragePart()));
		}
		if (binaryEntity.getReferenceStoragePart() != null) {
			binaryEntityBuilder.setReferenceStoragePart(ByteString.copyFrom(binaryEntity.getReferenceStoragePart()));
		}

		return binaryEntityBuilder.build();
	}

	/**
	 * Converts {@link PriceContract} to {@link GrpcPrice}.
	 *
	 * @param price {@link PriceContract} to convert
	 * @return new instance of {@link GrpcPrice}
	 */
	@Nonnull
	public static GrpcPrice toGrpcPrice(@Nonnull PriceContract price) {
		final GrpcPrice.Builder priceBuilder = GrpcPrice.newBuilder()
			.setPriceId(price.getPriceId())
			.setPriceList(price.getPriceList())
			.setCurrency(GrpcCurrency.newBuilder().setCode(price.getCurrency().getCurrencyCode()).build())
			.setPriceWithoutTax(GrpcBigDecimal.newBuilder().
				setValueString(price.getPriceWithoutTax().toString())
				.setValue(ByteString.copyFrom(price.getPriceWithoutTax().unscaledValue().toByteArray()))
				.setScale(price.getPriceWithoutTax().scale())
				.setPrecision(price.getPriceWithoutTax().precision()).build())
			.setPriceWithTax(GrpcBigDecimal.newBuilder()
				.setValueString(price.getPriceWithTax().toString())
				.setValue(ByteString.copyFrom(price.getPriceWithTax().unscaledValue().toByteArray()))
				.setScale(price.getPriceWithTax().scale())
				.setPrecision(price.getPriceWithTax().precision()).build())
			.setTaxRate(GrpcBigDecimal.newBuilder()
				.setValueString(price.getTaxRate().toString())
				.setValue(ByteString.copyFrom(price.getTaxRate().unscaledValue().toByteArray()))
				.setScale(price.getTaxRate().scale())
				.setPrecision(price.getTaxRate().precision()).build())
			.setSellable(price.isSellable())
			.setVersion(price.getVersion());
		if (price.getInnerRecordId() != null) {
			priceBuilder.setInnerRecordId(Int32Value.newBuilder().setValue(price.getInnerRecordId()).build());
		}

		if (price.getValidity() != null) {
			priceBuilder.setValidity(EvitaDataTypesConverter.toGrpcDateTimeRange(price.getValidity()));
		}

		return priceBuilder.build();
	}

	/**
	 * Converts list of {@link GrpcEntityReference} to the list of {@link EntityReference} that should be used
	 * in the Java client.
	 */
	@Nonnull
	public static List<EntityReference> toEntityReferences(@Nonnull List<GrpcEntityReference> entityReferencesList) {
		return entityReferencesList
			.stream()
			.map(
				entityReference -> new EntityReference(
					entityReference.getEntityType(),
					entityReference.getPrimaryKey()
				)
			).toList();
	}

	/**
	 * Converts list of {@link GrpcSealedEntity} to the list of {@link SealedEntity} that should be used in the Java
	 * client.
	 */
	@Nonnull
	public static List<SealedEntity> toSealedEntities(
		@Nonnull List<GrpcSealedEntity> sealedEntitiesList,
		@Nonnull Query query,
		@Nonnull BiFunction<String, Integer, SealedEntitySchema> entitySchemaProvider
	) {
		return sealedEntitiesList
			.stream()
			.map(
				it -> toSealedEntity(
					entity -> entitySchemaProvider.apply(entity.getEntityType(), entity.getSchemaVersion()),
					new EvitaRequest(query, OffsetDateTime.now()),
					it
				)
			)
			.toList();
	}

	/**
	 * Method converts {@link GrpcEvitaValue} (global attributes) and {@link GrpcLocalizedAttribute}
	 * to the collection of {@link AttributeValue} that can be used on the client side.
	 */
	@Nonnull
	private static Collection<AttributeValue> toAttributeValues(
		@Nonnull Map<String, GrpcEvitaValue> globalAttributesMap,
		@Nonnull Map<String, GrpcLocalizedAttribute> localizedAttributesMap
	) {
		final List<AttributeValue> result = new ArrayList<>(globalAttributesMap.size() + localizedAttributesMap.size());
		for (Entry<String, GrpcEvitaValue> entry : globalAttributesMap.entrySet()) {
			final String attributeName = entry.getKey();
			result.add(
				toAttributeValue(new AttributeKey(attributeName), entry.getValue())
			);
		}
		for (Entry<String, GrpcLocalizedAttribute> entry : localizedAttributesMap.entrySet()) {
			final Locale locale = Locale.forLanguageTag(entry.getKey());
			final GrpcLocalizedAttribute localizedAttributeSet = entry.getValue();
			for (Entry<String, GrpcEvitaValue> attributeEntry : localizedAttributeSet.getAttributesMap().entrySet()) {
				result.add(
					toAttributeValue(
						new AttributeKey(attributeEntry.getKey(), locale),
						attributeEntry.getValue()
					)
				);
			}
		}
		return result;
	}

	/**
	 * Method converts {@link GrpcEvitaValue} to the {@link AttributeValue} that can be used
	 * on the client side.
	 */
	@Nonnull
	private static AttributeValue toAttributeValue(@Nonnull AttributeKey attributeKey, @Nonnull GrpcEvitaValue attributeValue) {
		Assert.isTrue(attributeValue.hasVersion(), "Missing attribute value version.");
		return new AttributeValue(
			attributeValue.getVersion().getValue(),
			attributeKey,
			EvitaDataTypesConverter.toEvitaValue(attributeValue)
		);
	}

	/**
	 * Method converts {@link GrpcEvitaAssociatedDataValue} and {@link GrpcLocalizedAssociatedData} to the collection of
	 * {@link AssociatedDataValue} that can be used on the client side.
	 */
	@Nonnull
	private static Collection<AssociatedDataValue> toAssociatedDataValues(
		@Nonnull Map<String, GrpcEvitaAssociatedDataValue> globalAssociatedDataMap,
		@Nonnull Map<String, GrpcLocalizedAssociatedData> localizedAssociatedDataMap
	) {
		final List<AssociatedDataValue> result = new ArrayList<>(globalAssociatedDataMap.size() + localizedAssociatedDataMap.size());
		for (Entry<String, GrpcEvitaAssociatedDataValue> entry : globalAssociatedDataMap.entrySet()) {
			final String associatedDataName = entry.getKey();
			result.add(
				toAssociatedDataValue(
					new AssociatedDataKey(associatedDataName),
					entry.getValue()
				)
			);
		}
		for (Entry<String, GrpcLocalizedAssociatedData> entry : localizedAssociatedDataMap.entrySet()) {
			final Locale locale = Locale.forLanguageTag(entry.getKey());
			final GrpcLocalizedAssociatedData localizedAssociatedDataSet = entry.getValue();
			for (Entry<String, GrpcEvitaAssociatedDataValue> associatedDataEntry : localizedAssociatedDataSet.getAssociatedDataMap().entrySet()) {
				result.add(
					toAssociatedDataValue(
						new AssociatedDataKey(associatedDataEntry.getKey(), locale),
						associatedDataEntry.getValue()
					)
				);
			}
		}
		return result;
	}

	/**
	 * Method converts {@link GrpcEvitaAssociatedDataValue} to the {@link AssociatedDataValue} that can be used on the client side.
	 */
	@Nonnull
	private static AssociatedDataValue toAssociatedDataValue(
		@Nonnull AssociatedDataKey associatedDataKey,
		@Nonnull GrpcEvitaAssociatedDataValue associatedDataValue
	) {
		Assert.isTrue(associatedDataValue.hasVersion(), "Missing attribute value version.");
		return new AssociatedDataValue(
			associatedDataValue.getVersion().getValue(),
			associatedDataKey,
			associatedDataValue.hasPrimitiveValue() ?
				EvitaDataTypesConverter.toEvitaValue(associatedDataValue.getPrimitiveValue()) :
				ComplexDataObjectConverter.convertJsonToComplexDataObject(associatedDataValue.getJsonValue())
		);
	}

	/**
	 * Method converts {@link GrpcPrice} to the {@link PriceContract} that can be used on the client side.
	 */
	@Nonnull
	private static PriceContract toPrice(@Nonnull GrpcPrice grpcPrice) {
		return new Price(
			grpcPrice.getVersion(),
			new PriceKey(
				grpcPrice.getPriceId(),
				grpcPrice.getPriceList(),
				EvitaDataTypesConverter.toCurrency(grpcPrice.getCurrency())
			),
			grpcPrice.hasInnerRecordId() ? grpcPrice.getInnerRecordId().getValue() : null,
			EvitaDataTypesConverter.toBigDecimal(grpcPrice.getPriceWithoutTax()),
			EvitaDataTypesConverter.toBigDecimal(grpcPrice.getTaxRate()),
			EvitaDataTypesConverter.toBigDecimal(grpcPrice.getPriceWithTax()),
			grpcPrice.hasValidity() ? EvitaDataTypesConverter.toDateTimeRange(grpcPrice.getValidity()) : null,
			grpcPrice.getSellable()
		);
	}

	/**
	 * Converts list of {@link AttributeValue} into to {@link GrpcEvitaValue} with use of set of {@link Locale}.
	 *
	 * @param maxLocaleCount    maximum number of attribute locales that may be present
	 * @param maxAttributeCount maximum number of attributes that may be present
	 * @param entityAttributes  list of {@link AttributeValue} to convert
	 * @return new instance of {@link AttributesHolder} containing language segmented {@link GrpcEvitaValue}
	 */
	@Nonnull
	private static AttributesHolder buildAttributes(
		int maxLocaleCount,
		int maxAttributeCount,
		@Nonnull Collection<AttributeValue> entityAttributes
	) {
		final Map<Locale, GrpcLocalizedAttribute.Builder> localizedAttributes = CollectionUtils.createHashMap(maxLocaleCount);
		final Map<String, GrpcEvitaValue> globalAttributes = CollectionUtils.createHashMap(maxAttributeCount);
		for (AttributesContract.AttributeValue attribute : entityAttributes) {
			if (attribute.getKey().isLocalized()) {
				final Builder localizedAttributesBuilder = localizedAttributes.computeIfAbsent(
					attribute.getKey().getLocale(),
					locale -> GrpcLocalizedAttribute.newBuilder()
				);
				localizedAttributesBuilder.putAttributes(
					attribute.getKey().getAttributeName(),
					EvitaDataTypesConverter.toGrpcEvitaValue(attribute.getValue(), attribute.getVersion())
				);
			} else {
				globalAttributes.put(
					attribute.getKey().getAttributeName(),
					EvitaDataTypesConverter.toGrpcEvitaValue(attribute.getValue(), attribute.getVersion())
				);
			}
		}

		return new AttributesHolder(
			localizedAttributes.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						it -> it.getKey().toLanguageTag(),
						it -> it.getValue().build()
					)
				),
			globalAttributes
		);
	}

	@Nonnull
	public static SealedEntity parseBinaryEntity(@Nonnull GrpcBinaryEntity binaryEntity) {
		/* TOBEDONE JNO https://github.com/FgForrest/evitaDB/issues/13 */
		return null;
	}

	/**
	 * Holder for maps of {@link GrpcEvitaValue} and {@link GrpcLocalizedAttribute}.
	 */
	private record AttributesHolder(
		@Nonnull Map<String, GrpcLocalizedAttribute> localizedAttributesMap,
		@Nonnull Map<String, GrpcEvitaValue> globalAttributesMap
	) { }
}
