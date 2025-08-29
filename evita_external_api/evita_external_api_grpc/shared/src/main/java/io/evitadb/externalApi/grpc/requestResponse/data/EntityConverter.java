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
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.chunk.PageTransformer;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.DevelopmentConstants;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.*;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.ComplexDataObjectConverter;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.AssociatedDataForm;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcLocalizedAttribute.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VersionUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcScope;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toScope;
import static java.util.Optional.ofNullable;

/**
 * Class used for building suitable form of entity based on {@link SealedEntity}. The main methods here are
 * {@link #toGrpcSealedEntity(SealedEntity, SemVer)} and {@link #toGrpcBinaryEntity(BinaryEntity)}, which are used for building
 * {@link GrpcSealedEntity} and {@link GrpcBinaryEntity} respectively.
 * Decision factor of which is going to be used is whether {@link SessionFlags#BINARY} was passed when creating a session.
 *
 * @author Tomáš Pozler, 2022
 */
public class EntityConverter {

	/**
	 * Default converter that leaves the sealed entity as it is.
	 */
	public static final TypeConverter<SealedEntity> SEALED_ENTITY_TYPE_CONVERTER = (sealedEntityClass, sealedEntity) -> sealedEntity;

	/**
	 * Method converts {@link GrpcSealedEntity} to the {@link SealedEntity} that can be used on the client side.
	 */
	@Nonnull
	public static <T> T toEntity(
		@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull GrpcSealedEntity grpcEntity,
		@Nonnull Class<T> expectedType,
		@Nonnull TypeConverter<T> typeConverter
	) {
		return toEntity(entitySchemaFetcher, evitaRequest, null, grpcEntity, expectedType, typeConverter);
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
		@Nonnull Class<T> expectedType,
		@Nonnull TypeConverter<T> typeConverter
	) {
		final SealedEntitySchema entitySchema = entitySchemaFetcher.apply(grpcEntity);
		final EntityClassifierWithParent parentEntity;
		if (grpcEntity.hasParentEntity()) {
			final HierarchyContent hierarchyContent = evitaRequest.getHierarchyContent();
			final EvitaRequest parentRequest = ofNullable(hierarchyContent)
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
			parentEntity = toEntity(entitySchemaFetcher, parentRequest, grpcEntity.getParentEntity(), SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER);
		} else if (grpcEntity.hasParentReference()) {
			parentEntity = toEntityReferenceWithParent(grpcEntity.getParentReference());
		} else {
			parentEntity = parent;
		}

		if (EntityReference.class.isAssignableFrom(expectedType)) {
			throw new EvitaInvalidUsageException("EntityReference is not expected in this method!");
		} else {
			final List<ReferenceContract> references = grpcEntity.getReferencesList()
				.stream()
				.map(it -> toReference(entitySchema, it))
				.collect(Collectors.toList());

			final PriceContractSerializablePredicate pricePredicate;
			if (grpcEntity.hasPriceForSale()) {
				final AccompanyingPrice[] requestedAccompanyingPrices = evitaRequest.getAccompanyingPrices();
				final Map<String, GrpcPrice> returnedAccompanyingPrices = grpcEntity.getAccompanyingPricesMap();
				final Map<String, Optional<PriceContract>> accompanyingPrices = CollectionUtils.createHashMap(requestedAccompanyingPrices.length);
				for (AccompanyingPrice requestedAccompanyingPrice : requestedAccompanyingPrices) {
					final GrpcPrice grpcPrice = returnedAccompanyingPrices.get(requestedAccompanyingPrice.priceName());
					accompanyingPrices.put(
						requestedAccompanyingPrice.priceName(),
						grpcPrice != null ?
							Optional.of(toPrice(grpcPrice)) :
							Optional.empty()
					);
				}
				pricePredicate = new PriceContractSerializablePredicate(
					evitaRequest,
					new PriceForSaleWithAccompanyingPrices(
						toPrice(grpcEntity.getPriceForSale()),
						accompanyingPrices
					)
				);
			} else {
				pricePredicate = new PriceContractSerializablePredicate(evitaRequest, false);
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
						.collect(Collectors.toSet()),
					toScope(grpcEntity.getScope()),
					evitaRequest::getReferenceChunkTransformer
				),
				entitySchema,
				parentEntity,
				new LocaleSerializablePredicate(evitaRequest),
				new HierarchySerializablePredicate(evitaRequest),
				new AttributeValueSerializablePredicate(evitaRequest),
				new AssociatedDataValueSerializablePredicate(evitaRequest),
				new ReferenceContractSerializablePredicate(evitaRequest),
				pricePredicate,
				evitaRequest.getAlignedNow(),
				new ClientReferenceFetcher(
					parentEntity,
					grpcEntity.getReferencesList(),
					toReferenceOffsetAndLimits(grpcEntity.getReferenceOffsetAndLimitsMap()),
					entitySchemaFetcher,
					evitaRequest
				)
			);

			if (expectedType.isInstance(sealedEntity)) {
				//noinspection unchecked
				return (T) sealedEntity;
			} else {
				return typeConverter.apply(expectedType, sealedEntity);
			}
		}
	}

	/**
	 * Method converts map {@link GrpcOffsetAndLimit} to the {@link OffsetAndLimit} map.
	 */
	@Nonnull
	private static Map<String, OffsetAndLimit> toReferenceOffsetAndLimits(@Nonnull Map<String, GrpcOffsetAndLimit> referenceOffsetAndLimitsMap) {
		return referenceOffsetAndLimitsMap.entrySet()
			.stream()
			.collect(Collectors.toMap(
				Entry::getKey,
				entry -> {
					final GrpcOffsetAndLimit value = entry.getValue();
					return new OffsetAndLimit(
						value.getOffset(),
						value.getLimit(),
						value.getPageNumber(),
						value.getLastPageNumber(),
						value.getTotalRecordCount()
					);
				}
			));
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
			final GrpcEntityReference grpcGroupReference = grpcReference.getGroupReferencedEntityReference();
			group = new GroupEntityReference(
				grpcGroupReference.getEntityType(),
				grpcGroupReference.getPrimaryKey(),
				grpcGroupReference.hasReferenceVersion() ? grpcGroupReference.getReferenceVersion().getValue() : grpcGroupReference.getVersion(),
				false
			);
		} else if (grpcReference.hasGroupReferencedEntity()) {
			final GrpcSealedEntity grpcEntityReference = grpcReference.getGroupReferencedEntity();
			group = new GroupEntityReference(
				grpcEntityReference.getEntityType(),
				grpcEntityReference.getPrimaryKey(),
				grpcEntityReference.getVersion(),
				false
			);
		} else {
			group = null;
		}
		final String referenceName = grpcReference.getReferenceName();
		final String referencedEntityType = grpcReference.hasReferencedEntityReference() ?
			grpcReference.getReferencedEntityReference().getEntityType() :
			grpcReference.getReferencedEntity().getEntityType();
		final Cardinality cardinality = EvitaEnumConverter.toCardinality(grpcReference.getReferenceCardinality())
		                                                  .orElse(Cardinality.ZERO_OR_MORE);
		return new Reference(
			entitySchema,
			entitySchema.getReference(referenceName)
			            .orElseGet(() -> Reference.createImplicitSchema(referenceName, referencedEntityType, cardinality, group)),
			grpcReference.getVersion(),
			new ReferenceKey(
				referenceName,
				grpcReference.hasReferencedEntityReference() ?
					grpcReference.getReferencedEntityReference().getPrimaryKey() :
					grpcReference.getReferencedEntity().getPrimaryKey(),
				grpcReference.getInternalPrimaryKey()
			),
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
	 * @param clientVersion version of the client so that the server can adjust the response
	 * @return new instance of {@link GrpcSealedEntity}
	 */
	@Nonnull
	public static GrpcSealedEntity toGrpcSealedEntity(@Nonnull SealedEntity entity, @Nullable SemVer clientVersion) {
		//noinspection ConstantConditions
		final GrpcSealedEntity.Builder entityBuilder = GrpcSealedEntity.newBuilder()
			.setEntityType(entity.getType())
			.setPrimaryKey(entity.getPrimaryKey())
			.setSchemaVersion(entity.getSchema().version())
			.setVersion(entity.version())
			.setScope(toGrpcScope(entity.getScope()));

		if (entity.parentAvailable()) {
			entity.getParentEntity()
				.ifPresent(parent -> entityBuilder.setParent(Int32Value.of(parent.getPrimaryKeyOrThrowException())));

			entity.getParentEntity()
				.ifPresent(parent -> {
					if (parent instanceof EntityReferenceWithParent entityReference) {
						entityBuilder.setParentReference(
							toGrpcEntityReferenceWithParent(entityReference)
						);
					} else if (parent instanceof SealedEntity sealedEntity) {
						entityBuilder.setParentEntity(
							toGrpcSealedEntity(sealedEntity, clientVersion)
						);
					} else {
						throw new EvitaInvalidUsageException("Unexpected parent type: " + parent.getClass());
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

			final Optional<PriceForSaleWithAccompanyingPrices> priceForSale = entity.getPriceForSaleWithAccompanyingPricesIfAvailable();
			priceForSale.ifPresent(it -> {
				entityBuilder.setPriceForSale(toGrpcPrice(it.priceForSale()));
				for (Entry<String, Optional<PriceContract>> entry : it.accompanyingPrices().entrySet()) {
					if (entry.getValue().isPresent()) {
						entityBuilder.putAccompanyingPrices(
							entry.getKey(),
							toGrpcPrice(entry.getValue().get())
						);
					}
				}
			});
		}

		final boolean referencesRequestedAndFetched;
		final Predicate<String> referenceRequestedPredicate;
		final Entity internalEntity;
		if (entity instanceof Entity theEntity) {
			referencesRequestedAndFetched = true;
			internalEntity = theEntity;
			referenceRequestedPredicate = referenceName -> true;
		} else if (entity instanceof EntityDecorator entityDecorator) {
			internalEntity = entityDecorator.getDelegate();
			referencesRequestedAndFetched = entityDecorator.referencesAvailable();
			referenceRequestedPredicate = entityDecorator::referencesAvailable;
		} else {
			throw new EvitaInvalidUsageException("Unexpected entity type: " + entity.getClass());
		}
		if (referencesRequestedAndFetched) {
			for (ReferenceContract reference : entity.getReferences()) {
				final GrpcReference.Builder grpcReferenceBuilder = GrpcReference.newBuilder()
					.setVersion(reference.version())
					.setReferenceName(reference.getReferenceName())
					.setInternalPrimaryKey(reference.getReferenceKey().internalPrimaryKey())
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
					grpcReferenceBuilder.setReferencedEntity(
						toGrpcSealedEntity(reference.getReferencedEntity().get(), clientVersion)
					);
				} else {
					grpcReferenceBuilder.setReferencedEntityReference(GrpcEntityReference.newBuilder()
						.setEntityType(reference.getReferencedEntityType())
						.setPrimaryKey(reference.getReferencedPrimaryKey())
						.setReferenceVersion(Int32Value.newBuilder().setValue(reference.version()).build())
						.setVersion(reference.version())
						.build()
					);
				}

				if (reference.getGroupEntity().isPresent()) {
					grpcReferenceBuilder.setGroupReferencedEntity(
						toGrpcSealedEntity(reference.getGroupEntity().get(), clientVersion)
					);
				} else if (reference.getGroup().isPresent()) {
					final GroupEntityReference theGroup = reference.getGroup().get();
					grpcReferenceBuilder.setGroupReferencedEntityReference(
						GrpcEntityReference.newBuilder()
							.setEntityType(theGroup.getType())
							.setPrimaryKey(theGroup.getPrimaryKey())
							.setReferenceVersion(Int32Value.newBuilder().setValue(reference.version()).build())
							.setVersion(theGroup.version())
							.build()
					);
				}

				entityBuilder.addReferences(grpcReferenceBuilder);
			}
			internalEntity.getSchema().getReferences().keySet().forEach(
				referenceName -> {
					if (referenceRequestedPredicate.test(referenceName)) {
						final DataChunk<ReferenceContract> referenceChunk = entity.getReferenceChunk(referenceName);
						final GrpcOffsetAndLimit.Builder offsetAndLimit = GrpcOffsetAndLimit.newBuilder();
						if (referenceChunk instanceof PaginatedList<?> paginatedList) {
							offsetAndLimit.setOffset(paginatedList.getFirstPageItemNumber());
							offsetAndLimit.setLimit(paginatedList.getPageSize());
							offsetAndLimit.setPageNumber(paginatedList.getPageNumber());
							offsetAndLimit.setLastPageNumber(paginatedList.getLastPageNumber());
							offsetAndLimit.setTotalRecordCount(paginatedList.getTotalRecordCount());
						} else if (referenceChunk instanceof StripList<?> stripList) {
							offsetAndLimit.setOffset(stripList.getOffset());
							offsetAndLimit.setLimit(stripList.getLimit());
							offsetAndLimit.setTotalRecordCount(stripList.getTotalRecordCount());
						} else {
							offsetAndLimit.setOffset(0);
							offsetAndLimit.setLimit(referenceChunk.getTotalRecordCount());
							offsetAndLimit.setTotalRecordCount(referenceChunk.getTotalRecordCount());
						}
						entityBuilder.putReferenceOffsetAndLimits(
							referenceName,
							offsetAndLimit.build()
						);
					}
				}
			);
		}

		if (entity.associatedDataAvailable() && !entity.getAssociatedDataValues().isEmpty()) {
			final Map<Locale, GrpcLocalizedAssociatedData.Builder> localizedAssociatedData = CollectionUtils.createHashMap(entity.getLocales().size());
			final Map<String, GrpcEvitaAssociatedDataValue> globalAssociatedData = CollectionUtils.createHashMap(entity.getSchema().getAssociatedData().size());
			for (AssociatedDataValue theAssociatedData : entity.getAssociatedDataValues()) {
				final GrpcEvitaAssociatedDataValue associatedDataValue = EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(
					Objects.requireNonNull(theAssociatedData.value()), theAssociatedData.version(),
					VersionUtils.greaterThanEquals(2025, 4, clientVersion) ?
						AssociatedDataForm.STRUCTURED_VALUE : AssociatedDataForm.JSON
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
			.setSellable(price.indexed())
			.setIndexed(price.indexed())
			.setVersion(price.version());
		final Integer innerRecordId = price.innerRecordId();
		if (innerRecordId != null) {
			priceBuilder.setInnerRecordId(Int32Value.newBuilder().setValue(innerRecordId).build());
		}

		final DateTimeRange validity = price.validity();
		if (validity != null) {
			priceBuilder.setValidity(EvitaDataTypesConverter.toGrpcDateTimeRange(validity));
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
		@Nonnull Class<S> expectedType,
		@Nonnull TypeConverter<S> typeConverter
	) {
		return sealedEntitiesList
			.stream()
			.map(
				it -> toEntity(
					entity -> entitySchemaProvider.apply(entity.getEntityType(), entity.getSchemaVersion()),
					evitaRequest,
					it,
					expectedType,
					typeConverter
				)
			)
			.toList();
	}

	@Nonnull
	public static <T> T parseBinaryEntity(@Nonnull GrpcBinaryEntity binaryEntity) {
		/* TOBEDONE JNO https://github.com/FgForrest/evitaDB/issues/13 */
		throw new UnsupportedOperationException(
			"Parsing of binary entities is not yet supported in gRPC API! Please use the sealed entity form instead."
		);
	}

	/**
	 * Builds an entity reference with parent in gRPC entity from {@link EntityReferenceWithParent} instance.
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
		final Serializable value;
		if (associatedDataValue.hasPrimitiveValue()) {
			value = EvitaDataTypesConverter.toEvitaValue(associatedDataValue.getPrimitiveValue());
		} else if (associatedDataValue.hasRoot()) {
			value = EvitaDataTypesConverter.toComplexObject(associatedDataValue.getRoot());
		} else if (associatedDataValue.hasJsonValue()) {
			value = ComplexDataObjectConverter.convertJsonToComplexDataObject(associatedDataValue.getJsonValue());
		} else {
			throw new EvitaInvalidUsageException("Associated data value is missing value.");
		}

		return new AssociatedDataValue(
			associatedDataValue.getVersion().getValue(),
			associatedDataKey,
			value
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
			grpcPrice.getIndexed() || grpcPrice.getSellable()
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
		final Map<Locale, Builder> localizedAttributes = CollectionUtils.createHashMap(maxLocaleCount);
		final Map<String, GrpcEvitaValue> globalAttributes = CollectionUtils.createHashMap(maxAttributeCount);
		for (AttributeValue attribute : entityAttributes) {
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
		private final Map<String, OffsetAndLimit> referencesOffsetAndLimit;
		private final EvitaRequest evitaRequest;

		public ClientReferenceFetcher(
			@Nullable EntityClassifierWithParent parentEntity,
			@Nonnull List<GrpcReference> grpcReference,
			@Nonnull Map<String, OffsetAndLimit> referencesOffsetAndLimit,
			@Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
			@Nonnull EvitaRequest evitaRequest
		) {
			this.parentEntity = parentEntity;
			this.entityIndex = grpcReference.stream()
				.filter(GrpcReference::hasReferencedEntity)
				.map(it -> {
					final RequirementContext fetchCtx = ofNullable(evitaRequest.getReferenceEntityFetch().get(it.getReferenceName()))
						.orElse(evitaRequest.getDefaultReferenceRequirement());
					Assert.isPremiseValid(
						fetchCtx != null && fetchCtx.entityFetch() != null,
						"Server returned referenced entity, but it's not requested in the request?!"
					);
					final GrpcSealedEntity referencedEntity = it.getReferencedEntity();
					final EvitaRequest referenceRequest = evitaRequest.deriveCopyWith(referencedEntity.getEntityType(), fetchCtx.entityFetch());
					return toEntity(entitySchemaFetcher, referenceRequest, referencedEntity, SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER);
				})
				.collect(
					Collectors.toMap(
						it -> new EntityReference(it.getType(), it.getPrimaryKeyOrThrowException()),
						Function.identity()
					)
				);
			this.groupIndex = grpcReference.stream()
				.filter(GrpcReference::hasGroupReferencedEntity)
				.map(it -> {
					final RequirementContext fetchCtx = ofNullable(evitaRequest.getReferenceEntityFetch().get(it.getReferenceName()))
						.orElse(evitaRequest.getDefaultReferenceRequirement());
					Assert.isPremiseValid(
						fetchCtx != null && fetchCtx.entityGroupFetch() != null,
						"Server returned referenced entity, but it's not requested in the request?!"
					);
					final GrpcSealedEntity referencedEntity = it.getGroupReferencedEntity();
					final EvitaRequest referenceRequest = evitaRequest.deriveCopyWith(referencedEntity.getEntityType(), fetchCtx.entityGroupFetch());
					return toEntity(entitySchemaFetcher, referenceRequest, referencedEntity, SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER);
				})
				.collect(
					Collectors.toMap(
						it -> new EntityReference(it.getType(), it.getPrimaryKeyOrThrowException()),
						Function.identity(),
						(sealedEntity, sealedEntity2) -> sealedEntity
					)
				);
			this.referencesOffsetAndLimit = referencesOffsetAndLimit;
			this.evitaRequest = evitaRequest;
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

		@Nonnull
		@Override
		public EvitaRequest getEnvelopingEntityRequest() {
			return this.evitaRequest;
		}

		@Nullable
		@Override
		public Function<Integer, EntityClassifierWithParent> getParentEntityFetcher() {
			return parentId -> this.parentEntity;
		}

		@Nullable
		@Override
		public Function<Integer, SealedEntity> getEntityFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return primaryKey -> this.entityIndex.get(new EntityReference(referenceSchema.getReferencedEntityType(), primaryKey));
		}

		@Nullable
		@Override
		public Function<Integer, SealedEntity> getEntityGroupFetcher(@Nonnull ReferenceSchemaContract referenceSchema) {
			return primaryKey -> this.groupIndex.get(new EntityReference(Objects.requireNonNull(referenceSchema.getReferencedGroupType()), primaryKey));
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

		@Nonnull
		@Override
		public DataChunk<ReferenceContract> createChunk(@Nonnull Entity entity, @Nonnull String referenceName, @Nonnull List<ReferenceContract> references) {
			// client reference fetcher sees only slice of the original reference list (in case of paginated access only requested page)
			// so we need to use alternate method with providing full total count
			final ChunkTransformer chunker = entity.getReferenceChunkTransformer().apply(referenceName);
			if (chunker instanceof PageTransformer pageTransformer) {
				// when page transformer is used, we need to use server side calculated offset and limit
				// because spacing could have been used and this cannot be interpreted on the client side
				return new ClientPageTransformer(
					pageTransformer.getPage().getPageSize(),
					Objects.requireNonNull(this.referencesOffsetAndLimit.get(referenceName))
				).createChunk(references);
			} else {
				return chunker.createChunk(references);
			}
		}

		/**
		 * An implementation of the {@link ChunkTransformer} interface that slices a list of {@link ReferenceContract}
		 * objects into chunks based on pagination parameters defined by a given {@link OffsetAndLimit} instance.
		 *
		 * This class provides functionality to transform a complete list of references into a paged data chunk
		 * aligned with the specified offset, limit, and page number parameters. It internally delegates the
		 * chunk creation process to {@code PageTransformer}.
		 */
		@RequiredArgsConstructor
		private static class ClientPageTransformer implements ChunkTransformer {
			private final int pageSize;
			private final OffsetAndLimit offsetAndLimit;

			@Nonnull
			@Override
			public DataChunk<ReferenceContract> createChunk(@Nonnull List<ReferenceContract> referenceContracts) {
				return PageTransformer.getReferenceContractDataChunk(
					referenceContracts,
					this.offsetAndLimit.pageNumber(),
					this.pageSize,
					this.offsetAndLimit.lastPageNumber(),
					// always zero - server sends only a single page
					0,
					referenceContracts.size(),
					this.offsetAndLimit.totalRecordCount()
				);
			}

		}
	}

	/**
	 * The TypeConverter interface provides a method to convert an instance of {@link SealedEntity} into different types.
	 * This interface extends the {@link BiFunction} interface, using a {@link Class} object representing the expected type
	 * and a {@link SealedEntity} object as input parameters, and returns an object of the expected type.
	 *
	 * @param <T> the type of object into which the {@link SealedEntity} is to be converted.
	 */
	@FunctionalInterface
	public interface TypeConverter<T> extends BiFunction<Class<T>, SealedEntity, T> {

	}

}
