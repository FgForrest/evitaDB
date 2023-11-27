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
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.DevelopmentConstants;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.*;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.grpc.dataType.ComplexDataObjectConverter;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcLocalizedAttribute.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

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
	public static <T> T toEntity(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcSealedEntity grpcEntity,
		@Nonnull Class<T> expectedType
	) {
		return toEntity(entitySchemaFetcher, evitaRequest, null, grpcEntity, expectedType);
	}

	/**
	 * Method converts {@link GrpcSealedEntity} to the {@link SealedEntity} that can be used on the client side.
	 */
	@Nonnull
	public static <T> T toEntity(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable SealedEntity parent,
		@Nonnull GrpcSealedEntity grpcEntity,
		@Nonnull Class<T> expectedType
	) {
		final SealedEntitySchema entitySchema = entitySchemaFetcher.apply(grpcEntity);
		final EntityClassifierWithParent parentEntity;
		if (grpcEntity.hasParentEntity()) {
			final HierarchyContent hierarchyContent = evitaRequest.getHierarchyContent();
			final EvitaRequest parentRequest = Optional.ofNullable(hierarchyContent)
				.flatMap(HierarchyContent::getEntityFetch)
				.map(
					it -> evitaRequest.deriveCopyWith(
						entitySchema.getName(),
						QueryConstraints.entityFetch(
							ArrayUtils.mergeArrays(
								it.getRequirements(),
								new EntityContentRequire[]{QueryConstraints.hierarchyContent()}
							)
						)
					)
				)
				.orElse(evitaRequest);
			parentEntity = toEntity(entitySchemaFetcher, parentRequest, grpcEntity.getParentEntity(), SealedEntity.class);
		} else if (grpcEntity.hasParentReference()) {
			parentEntity = toEntityReferenceWithParent(grpcEntity.getParentReference());
		} else {
			parentEntity = parent;
		}

		if (EntityReference.class.isAssignableFrom(expectedType)) {
			throw new EvitaInternalError("EntityReference is not expected in this method!");
		} else {
			final List<ReferenceContract> references = grpcEntity.getReferencesList()
				.stream()
				.map(it -> toReference(entitySchema, it))
				.collect(Collectors.toList());

			if (DevelopmentConstants.isTestRun()) {
				// for test purposes we need to have the references data sorted by their keys to make tests reproducible
				references.sort(Comparator.comparing(ReferenceContract::getReferenceKey));
			}

			final EntityDecorator sealedEntity = new EntityDecorator(
				Entity._internalBuild(
					grpcEntity.getPrimaryKey(),
					grpcEntity.getVersion(),
					entitySchema,
					grpcEntity.hasParent() ? grpcEntity.getParent().getValue() : null,
					references,
					new EntityAttributes(
						entitySchema,
						toAttributeValues(
							grpcEntity.getGlobalAttributesMap(),
							grpcEntity.getLocalizedAttributesMap()
						),
						entitySchema.getAttributes()
					),
					new AssociatedData(
						entitySchema,
						toAssociatedDataValues(
							grpcEntity.getGlobalAssociatedDataMap(),
							grpcEntity.getLocalizedAssociatedDataMap()
						)
					),
					new Prices(
						entitySchema,
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
				parentEntity,
				new LocaleSerializablePredicate(evitaRequest),
				new HierarchySerializablePredicate(evitaRequest),
				new AttributeValueSerializablePredicate(evitaRequest),
				new AssociatedDataValueSerializablePredicate(evitaRequest),
				new ReferenceContractSerializablePredicate(evitaRequest),
				new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
				evitaRequest.getAlignedNow(),
				new ClientReferenceFetcher(
					parentEntity,
					grpcEntity.getReferencesList(),
					entitySchemaFetcher,
					evitaRequest
				)
			);

			if (expectedType.isInstance(sealedEntity)) {
				//noinspection unchecked
				return (T) sealedEntity;
			} else {
				//noinspection unchecked
				return (T) evitaRequest.getConverter().apply(expectedType, sealedEntity);
			}
		}
	}

	/**
	 * Method converts {@link GrpcReference} to the {@link ReferenceContract} that can be used on the client side.
	 */
	@Nonnull
	public static ReferenceContract toReference(
		@Nonnull SealedEntitySchema entitySchema,
		@Nonnull GrpcReference grpcReference
	) {
		final GroupEntityReference group;
		if (grpcReference.hasGroupReferencedEntityReference()) {
			group = new GroupEntityReference(
				grpcReference.getGroupReferencedEntityReference().getEntityType(),
				grpcReference.getGroupReferencedEntityReference().getPrimaryKey(),
				grpcReference.getGroupReferencedEntityReference().getVersion(),
				false
			);
		} else if (grpcReference.hasGroupReferencedEntity()) {
			group = new GroupEntityReference(
				grpcReference.getGroupReferencedEntity().getEntityType(),
				grpcReference.getGroupReferencedEntity().getPrimaryKey(),
				grpcReference.getGroupReferencedEntity().getVersion(),
				false
			);
		} else {
			group = null;
		}
		return new Reference(
			entitySchema,
			grpcReference.getVersion(),
			grpcReference.getReferenceName(),
			grpcReference.hasReferencedEntityReference() ?
				grpcReference.getReferencedEntityReference().getPrimaryKey() :
				grpcReference.getReferencedEntity().getPrimaryKey(),
			grpcReference.hasReferencedEntityReference() ?
				grpcReference.getReferencedEntityReference().getEntityType() :
				grpcReference.getReferencedEntity().getEntityType(),
			EvitaEnumConverter.toCardinality(grpcReference.getReferenceCardinality()),
			group,
			toAttributeValues(
				grpcReference.getGlobalAttributesMap(),
				grpcReference.getLocalizedAttributesMap()
			),
			false
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
			.setSchemaVersion(entity.getSchema().version())
			.setVersion(entity.version());

		if (entity.parentAvailable()) {
			entity.getParentEntity()
				.ifPresent(parent -> entityBuilder.setParent(Int32Value.of(parent.getPrimaryKey())));

			entity.getParentEntity()
				.ifPresent(parent -> {
					if (parent instanceof EntityReferenceWithParent entityReference) {
						entityBuilder.setParentReference(
							toGrpcEntityReferenceWithParent(entityReference)
						);
					} else if (parent instanceof SealedEntity sealedEntity) {
						entityBuilder.setParentEntity(
							toGrpcSealedEntity(sealedEntity)
						);
					} else {
						throw new IllegalStateException("Unexpected parent type: " + parent.getClass());
					}
				});
		}

		final int entityLocaleCount = entity.getAttributeLocales().size();
		if (entity.attributesAvailable() && !entity.getAttributeValues().isEmpty()) {
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

		if (entity.pricesAvailable() && !entity.getPrices().isEmpty()) {
			entityBuilder.addAllPrices(entity.getPrices().stream().map(EntityConverter::toGrpcPrice).toList());

			final Optional<PriceContract> priceForSale = entity.getPriceForSaleIfAvailable();
			priceForSale.ifPresent(it -> entityBuilder.setPriceForSale(toGrpcPrice(it)));
		}

		if (entity.referencesAvailable() && !entity.getReferences().isEmpty()) {
			for (ReferenceContract reference : entity.getReferences()) {
				final GrpcReference.Builder grpcReferenceBuilder = GrpcReference.newBuilder()
					.setVersion(reference.version())
					.setReferenceName(reference.getReferenceName())
					.setReferenceCardinality(EvitaEnumConverter.toGrpcCardinality(reference.getReferenceCardinality()))
					.setVersion(reference.version());

				if (reference.attributesAvailable()) {
					final Collection<AttributeValue> attributeValues = reference.getAttributeValues();
					final AttributesHolder attributes = buildAttributes(
						entityLocaleCount,
						attributeValues.size(),
						attributeValues
					);
					grpcReferenceBuilder
						.putAllLocalizedAttributes(attributes.localizedAttributesMap)
						.putAllGlobalAttributes(attributes.globalAttributesMap);
				}

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
						.setVersion(theGroup.version())
						.build()
					);
				}

				entityBuilder.addReferences(grpcReferenceBuilder);
			}
		}

		if (entity.associatedDataAvailable() && !entity.getAssociatedDataValues().isEmpty()) {
			final Map<Locale, GrpcLocalizedAssociatedData.Builder> localizedAssociatedData = CollectionUtils.createHashMap(entity.getLocales().size());
			final Map<String, GrpcEvitaAssociatedDataValue> globalAssociatedData = CollectionUtils.createHashMap(entity.getSchema().getAssociatedData().size());
			for (AssociatedDataContract.AssociatedDataValue theAssociatedData : entity.getAssociatedDataValues()) {
				final GrpcEvitaAssociatedDataValue associatedDataValue = EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(
					theAssociatedData.value(), theAssociatedData.version()
				);

				if (theAssociatedData.key().localized()) {
					localizedAssociatedData.computeIfAbsent(
							theAssociatedData.key().locale(),
							associatedDataName -> GrpcLocalizedAssociatedData.newBuilder()
						)
						.putAssociatedData(theAssociatedData.key().associatedDataName(), associatedDataValue);
				} else {
					globalAssociatedData.put(theAssociatedData.key().associatedDataName(), associatedDataValue);
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
			.setSchemaVersion(binaryEntity.getSchema().version())
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
			.setPriceId(price.priceId())
			.setPriceList(price.priceList())
			.setCurrency(GrpcCurrency.newBuilder().setCode(price.currency().getCurrencyCode()).build())
			.setPriceWithoutTax(EvitaDataTypesConverter.toGrpcBigDecimal(price.priceWithoutTax()))
			.setPriceWithTax(EvitaDataTypesConverter.toGrpcBigDecimal(price.priceWithTax()))
			.setTaxRate(EvitaDataTypesConverter.toGrpcBigDecimal(price.taxRate()))
			.setSellable(price.sellable())
			.setVersion(price.version());
		if (price.innerRecordId() != null) {
			priceBuilder.setInnerRecordId(Int32Value.newBuilder().setValue(price.innerRecordId()).build());
		}

		if (price.validity() != null) {
			priceBuilder.setValidity(EvitaDataTypesConverter.toGrpcDateTimeRange(price.validity()));
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
			.map(EntityConverter::toEntityReference).toList();
	}

	/**
	 * Converts {@link GrpcEntityReference} to the {@link EntityReference} that should be used
	 * in the Java client.
	 */
	@Nonnull
	public static EntityReference toEntityReference(@Nonnull GrpcEntityReference entityReference) {
		return new EntityReference(
			entityReference.getEntityType(),
			entityReference.getPrimaryKey()
		);
	}

	/**
	 * Converts {@link GrpcEntityReference} to the {@link EntityReference} that should be used
	 * in the Java client.
	 */
	@Nonnull
	public static EntityReferenceWithParent toEntityReferenceWithParent(@Nonnull GrpcEntityReferenceWithParent entityReferenceWithParent) {
		return new EntityReferenceWithParent(
			entityReferenceWithParent.getEntityType(), entityReferenceWithParent.getPrimaryKey(),
			entityReferenceWithParent.hasParent() ?
				toEntityReferenceWithParent(entityReferenceWithParent.getParent()) : null
		);
	}

	/**
	 * Converts list of {@link GrpcSealedEntity} to the list of {@link SealedEntity} that should be used in the Java
	 * client.
	 */
	@Nonnull
	public static <S> List<S> toEntities(
		@Nonnull List<GrpcSealedEntity> sealedEntitiesList,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull BiFunction<String, Integer, SealedEntitySchema> entitySchemaProvider,
		@Nonnull Class<S> expectedType
	) {
		return sealedEntitiesList
			.stream()
			.map(
				it -> toEntity(
					entity -> entitySchemaProvider.apply(entity.getEntityType(), entity.getSchemaVersion()),
					evitaRequest,
					it,
					expectedType
				)
			)
			.toList();
	}

	@Nonnull
	public static <T> T parseBinaryEntity(@Nonnull GrpcBinaryEntity binaryEntity) {
		/* TOBEDONE JNO https://github.com/FgForrest/evitaDB/issues/13 */
		return null;
	}

	/**
	 * Builds a entity reference with parent in gRPC entity from {@link EntityReferenceWithParent} instance.
	 */
	private static GrpcEntityReferenceWithParent toGrpcEntityReferenceWithParent(
		@Nonnull EntityReferenceWithParent entityReference
	) {
		final GrpcEntityReferenceWithParent.Builder builder = GrpcEntityReferenceWithParent.newBuilder()
			.setEntityType(entityReference.getType())
			.setPrimaryKey(entityReference.getPrimaryKey());

		entityReference.getParentEntity()
			.ifPresent(
				it -> builder.setParent(
					toGrpcEntityReferenceWithParent((EntityReferenceWithParent) it)
				)
			);

		return builder.build();
	}

	/**
	 * Method converts {@link GrpcEvitaValue} (global attributes) and {@link GrpcLocalizedAttribute}
	 * to the collection of {@link AttributeValue} that can be used on the client side.
	 */
	@Nonnull
	private static Map<AttributeKey, AttributeValue> toAttributeValues(
		@Nonnull Map<String, GrpcEvitaValue> globalAttributesMap,
		@Nonnull Map<String, GrpcLocalizedAttribute> localizedAttributesMap
	) {
		final AttributeValue[] attributeValueTuples = new AttributeValue[
			globalAttributesMap.size() +
				localizedAttributesMap.values().stream().mapToInt(it -> it.getAttributesMap().size()).sum()
		];
		int index = 0;
		for (Entry<String, GrpcLocalizedAttribute> entry : localizedAttributesMap.entrySet()) {
			final Locale locale = Locale.forLanguageTag(entry.getKey());
			final GrpcLocalizedAttribute localizedAttributeSet = entry.getValue();
			for (Entry<String, GrpcEvitaValue> attributeEntry : localizedAttributeSet.getAttributesMap().entrySet()) {
				attributeValueTuples[index++] = toAttributeValue(
					new AttributeKey(attributeEntry.getKey(), locale),
					attributeEntry.getValue()
				);
			}
		}
		for (Entry<String, GrpcEvitaValue> entry : globalAttributesMap.entrySet()) {
			final String attributeName = entry.getKey();
			attributeValueTuples[index++] = toAttributeValue(new AttributeKey(attributeName), entry.getValue());
		}

		if (DevelopmentConstants.isTestRun()) {
			// for test purposes we need to have the associated data sorted by their keys to make tests reproducible
			Arrays.sort(attributeValueTuples, Comparator.comparing(AttributeValue::key));
		}

		final LinkedHashMap<AttributeKey, AttributeValue> result = CollectionUtils.createLinkedHashMap(
			globalAttributesMap.size() + localizedAttributesMap.size()
		);
		for (AttributeValue attributeValue : attributeValueTuples) {
			result.put(attributeValue.key(), attributeValue);
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
	private static LinkedHashMap<AssociatedDataKey, AssociatedDataValue> toAssociatedDataValues(
		@Nonnull Map<String, GrpcEvitaAssociatedDataValue> globalAssociatedDataMap,
		@Nonnull Map<String, GrpcLocalizedAssociatedData> localizedAssociatedDataMap
	) {
		final AssociatedDataValue[] associatedDataTuples = new AssociatedDataValue[
			globalAssociatedDataMap.size() +
				localizedAssociatedDataMap.values().stream().mapToInt(it -> it.getAssociatedDataMap().size()).sum()
		];
		int index = 0;
		for (Entry<String, GrpcLocalizedAssociatedData> entry : localizedAssociatedDataMap.entrySet()) {
			final Locale locale = Locale.forLanguageTag(entry.getKey());
			final GrpcLocalizedAssociatedData localizedAssociatedDataSet = entry.getValue();
			for (Entry<String, GrpcEvitaAssociatedDataValue> associatedDataEntry : localizedAssociatedDataSet.getAssociatedDataMap().entrySet()) {
				associatedDataTuples[index++] = toAssociatedDataValue(
					new AssociatedDataKey(associatedDataEntry.getKey(), locale),
					associatedDataEntry.getValue()
				);
			}
		}
		for (Entry<String, GrpcEvitaAssociatedDataValue> entry : globalAssociatedDataMap.entrySet()) {
			final String associatedDataName = entry.getKey();
			associatedDataTuples[index++] =
				toAssociatedDataValue(
					new AssociatedDataKey(associatedDataName),
					entry.getValue()
				);
		}

		if (DevelopmentConstants.isTestRun()) {
			// for test purposes we need to have the associated data sorted by their keys to make tests reproducible
			Arrays.sort(associatedDataTuples, Comparator.comparing(AssociatedDataValue::key));
		}

		final LinkedHashMap<AssociatedDataKey, AssociatedDataValue> result = CollectionUtils.createLinkedHashMap(
			globalAssociatedDataMap.size() + localizedAssociatedDataMap.size()
		);
		for (AssociatedDataValue associatedDataTuple : associatedDataTuples) {
			result.put(associatedDataTuple.key(), associatedDataTuple);
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
			if (attribute.key().localized()) {
				final Builder localizedAttributesBuilder = localizedAttributes.computeIfAbsent(
					attribute.key().locale(),
					locale -> GrpcLocalizedAttribute.newBuilder()
				);
				localizedAttributesBuilder.putAttributes(
					attribute.key().attributeName(),
					EvitaDataTypesConverter.toGrpcEvitaValue(attribute.value(), attribute.version())
				);
			} else {
				globalAttributes.put(
					attribute.key().attributeName(),
					EvitaDataTypesConverter.toGrpcEvitaValue(attribute.value(), attribute.version())
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

	/**
	 * Holder for maps of {@link GrpcEvitaValue} and {@link GrpcLocalizedAttribute}.
	 */
	private record AttributesHolder(
		@Nonnull Map<String, GrpcLocalizedAttribute> localizedAttributesMap,
		@Nonnull Map<String, GrpcEvitaValue> globalAttributesMap
	) {
	}

	private static class ClientReferenceFetcher implements ReferenceFetcher {
		private final EntityClassifierWithParent parentEntity;
		private final Map<EntityReference, SealedEntity> entityIndex;
		private final Map<EntityReference, SealedEntity> groupIndex;

		public ClientReferenceFetcher(
			@Nullable EntityClassifierWithParent parentEntity,
			@Nonnull List<GrpcReference> grpcReference,
			@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
			@Nonnull EvitaRequest evitaRequest
		) {
			this.parentEntity = parentEntity;
			this.entityIndex = grpcReference.stream()
				.filter(GrpcReference::hasReferencedEntity)
				.map(it -> {
					final RequirementContext fetchCtx = Optional.ofNullable(evitaRequest.getReferenceEntityFetch().get(it.getReferenceName()))
						.orElse(evitaRequest.getDefaultReferenceRequirement());
					final GrpcSealedEntity referencedEntity = it.getReferencedEntity();
					final EvitaRequest referenceRequest = evitaRequest.deriveCopyWith(referencedEntity.getEntityType(), fetchCtx.entityFetch());
					return toEntity(entitySchemaFetcher, referenceRequest, referencedEntity, SealedEntity.class);
				})
				.collect(
					Collectors.toMap(
						it -> new EntityReference(it.getType(), it.getPrimaryKey()),
						Function.identity()
					)
				);
			this.groupIndex = grpcReference.stream()
				.filter(GrpcReference::hasGroupReferencedEntity)
				.map(it -> {
					final RequirementContext fetchCtx = Optional.ofNullable(evitaRequest.getReferenceEntityFetch().get(it.getReferenceName()))
						.orElse(evitaRequest.getDefaultReferenceRequirement());
					final GrpcSealedEntity referencedEntity = it.getGroupReferencedEntity();
					final EvitaRequest referenceRequest = evitaRequest.deriveCopyWith(referencedEntity.getEntityType(), fetchCtx.entityGroupFetch());
					return toEntity(entitySchemaFetcher, referenceRequest, referencedEntity, SealedEntity.class);
				})
				.collect(
					Collectors.toMap(
						it -> new EntityReference(it.getType(), it.getPrimaryKey()),
						Function.identity(),
						(sealedEntity, sealedEntity2) -> sealedEntity
					)
				);
		}

		@Nonnull
		@Override
		public <T extends SealedEntity> T initReferenceIndex(@Nonnull T entity, @Nonnull EntityCollectionContract entityCollection) {
			throw new UnsupportedOperationException("Unexpected call!");
		}

		@Nonnull
		@Override
		public <T extends SealedEntity> List<T> initReferenceIndex(@Nonnull List<T> entities, @Nonnull EntityCollectionContract entityCollection) {
			throw new UnsupportedOperationException("Unexpected call!");
		}

		@Nullable
		@Override
		public Function<Integer, EntityClassifierWithParent> getParentEntityFetcher() {
			return parentId -> parentEntity;
		}

		@Nullable
		@Override
		public Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return primaryKey -> entityIndex.get(new EntityReference(referenceSchema.getReferencedEntityType(), primaryKey));
		}

		@Nullable
		@Override
		public Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return primaryKey -> groupIndex.get(new EntityReference(referenceSchema.getReferencedGroupType(), primaryKey));
		}

		@Nullable
		@Override
		public ReferenceComparator getEntityComparator(@Nonnull ReferenceSchemaContract referenceSchema) {
			return null;
		}

		@Nullable
		@Override
		public BiPredicate<Integer, ReferenceDecorator> getEntityFilter(@Nonnull ReferenceSchemaContract referenceSchema) {
			return (entityId, referenceDecorator) -> true;
		}
	}
}
