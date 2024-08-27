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

package io.evitadb.externalApi.grpc.requestResponse.schema;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcAssociatedDataSchema;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeElement;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeSchemaType;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogSchema;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchema;
import io.evitadb.externalApi.grpc.generated.GrpcReferenceSchema;
import io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcNameVariant;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.*;
import static java.util.Optional.ofNullable;

/**
 * This class contains methods for converting between {@link GrpcEntitySchema} and {@link EntitySchema}, same for all
 * the necessary partial schemas such as {@link AttributeSchema}, {@link ReferenceSchema}, {@link AssociatedDataSchema}.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 * @author Jan Novotný, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EntitySchemaConverter {

	/**
	 * Creates {@link GrpcEntitySchema} from {@link EntitySchemaContract}.
	 *
	 * @param entitySchema        entity schema to convert
	 * @param includeNameVariants whether to include name variants
	 */
	@Nonnull
	public static GrpcEntitySchema convert(@Nonnull EntitySchemaContract entitySchema, boolean includeNameVariants) {
		final GrpcEntitySchema.Builder builder = GrpcEntitySchema.newBuilder()
			.setName(entitySchema.getName())
			.setWithGeneratedPrimaryKey(entitySchema.isWithGeneratedPrimaryKey())
			.setWithHierarchy(entitySchema.isWithHierarchy())
			.setWithPrice(entitySchema.isWithPrice())
			.setIndexedPricePlaces(entitySchema.getIndexedPricePlaces())
			.addAllLocales(entitySchema.getLocales().stream().map(EvitaDataTypesConverter::toGrpcLocale).toList())
			.addAllCurrencies(entitySchema.getCurrencies().stream().map(EvitaDataTypesConverter::toGrpcCurrency).toList())
			.putAllAttributes(toGrpcAttributeSchemas(entitySchema.getAttributes(), includeNameVariants, attributeName -> false))
			.putAllSortableAttributeCompounds(toGrpcSortableAttributeCompoundSchemas(entitySchema.getSortableAttributeCompounds(), includeNameVariants, attributeName -> false))
			.putAllAssociatedData(toGrpcAssociatedDataSchemas(entitySchema.getAssociatedData(), includeNameVariants))
			.putAllReferences(toGrpcReferenceSchemas(entitySchema.getReferences(), includeNameVariants))
			.addAllEvolutionMode(entitySchema.getEvolutionMode().stream().map(EvitaEnumConverter::toGrpcEvolutionMode).toList())
			.setVersion(entitySchema.version());

		if (entitySchema.getDescription() != null) {
			builder.setDescription(StringValue.newBuilder().setValue(entitySchema.getDescription()).build());
		}
		if (entitySchema.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.newBuilder().setValue(entitySchema.getDeprecationNotice()).build());
		}

		if (includeNameVariants) {
			entitySchema.getNameVariants()
				.forEach(
					(namingConvention, nameVariant) -> builder.addNameVariant(toGrpcNameVariant(namingConvention, nameVariant))
				);
		}

		return builder.build();
	}

	/**
	 * Creates {@link SealedCatalogSchema} from the {@link GrpcCatalogSchema}.
	 */
	@Nonnull
	public static EntitySchema convert(@Nonnull GrpcEntitySchema entitySchema) {
		return EntitySchema._internalBuild(
			entitySchema.getVersion(),
			entitySchema.getName(),
			NamingConvention.generate(entitySchema.getName()),
			entitySchema.hasDescription() ? entitySchema.getDescription().getValue() : null,
			entitySchema.hasDeprecationNotice() ? entitySchema.getDeprecationNotice().getValue() : null,
			entitySchema.getWithGeneratedPrimaryKey(),
			entitySchema.getWithHierarchy(),
			entitySchema.getWithPrice(),
			entitySchema.getIndexedPricePlaces(),
			entitySchema.getLocalesList()
				.stream()
				.map(EvitaDataTypesConverter::toLocale)
				.collect(Collectors.toSet()),
			entitySchema.getCurrenciesList()
				.stream()
				.map(EvitaDataTypesConverter::toCurrency)
				.collect(Collectors.toSet()),
			entitySchema.getAttributesMap()
				.entrySet()
				.stream()
				.collect(Collectors.toMap(
					Entry::getKey,
					it -> toAttributeSchema(it.getValue(), EntityAttributeSchemaContract.class)
				)),
			entitySchema.getAssociatedDataMap()
				.entrySet()
				.stream()
				.collect(Collectors.toMap(
					Entry::getKey,
					it -> toAssociatedDataSchema(it.getValue())
				)),
			entitySchema.getReferencesMap()
				.entrySet()
				.stream()
				.collect(Collectors.toMap(
					Entry::getKey,
					it -> toReferenceSchema(it.getValue())
				)),
			entitySchema.getEvolutionModeList()
				.stream()
				.map(EvitaEnumConverter::toEvolutionMode)
				.collect(Collectors.toSet()),
			entitySchema.getSortableAttributeCompoundsMap()
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> toSortableAttributeCompoundSchema(it.getValue())
					)
				)
		);
	}

	/**
	 * Creates list of {@link GrpcAttributeElement} from the {@link AttributeElement}.
	 */
	@Nonnull
	public static List<GrpcAttributeElement> toGrpcAttributeElement(@Nonnull Collection<AttributeElement> attributeElementsList) {
		return attributeElementsList
			.stream()
			.map(
				it -> GrpcAttributeElement.newBuilder()
					.setAttributeName(it.attributeName())
					.setDirection(toGrpcOrderDirection(it.direction()))
					.setBehaviour(toGrpcOrderBehaviour(it.behaviour()))
					.build()
			)
			.toList();
	}

	/**
	 * Creates list of {@link AttributeElement} from the {@link GrpcAttributeElement}.
	 */
	@Nonnull
	public static List<AttributeElement> toAttributeElement(@Nonnull Collection<GrpcAttributeElement> attributeElementsList) {
		return attributeElementsList
			.stream()
			.map(it -> new AttributeElement(it.getAttributeName(), toOrderDirection(it.getDirection()), toOrderBehaviour(it.getBehaviour())))
			.toList();
	}

	/**
	 * From passed map where keys are representing attribute names and values {@link AttributeSchema} will be built a new map where values are converted
	 * to {@link GrpcAttributeSchema}.
	 *
	 * @param originalAttributeSchemas map of {@link AttributeSchema} to be converted to map of {@link GrpcAttributeSchema}
	 * @param includeNameVariants      if true, name variants will be included in the result
	 * @param inheritedPredicate predicate that matches all attributes that were inherited from the original schema via reflection
	 * @return map where keys are representing attribute names and values are {@link GrpcAttributeSchema}
	 */
	@Nonnull
	private static Map<String, GrpcAttributeSchema> toGrpcAttributeSchemas(
		@Nonnull Map<String, ? extends AttributeSchemaContract> originalAttributeSchemas,
		boolean includeNameVariants,
		@Nonnull Predicate<String> inheritedPredicate
	) {
		final Map<String, GrpcAttributeSchema> attributeSchemas = CollectionUtils.createHashMap(originalAttributeSchemas.size());
		for (Map.Entry<String, ? extends AttributeSchemaContract> entry : originalAttributeSchemas.entrySet()) {
			attributeSchemas.put(
				entry.getKey(),
				toGrpcAttributeSchema(entry.getValue(), includeNameVariants, inheritedPredicate)
			);
		}
		return attributeSchemas;
	}

	/**
	 * Converts single {@link AttributeSchema} to {@link GrpcAttributeSchema}.
	 *
	 * @param attributeSchema     instance of {@link AttributeSchema} to be converted to {@link GrpcAttributeSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @param inheritedPredicate predicate that matches all attributes that were inherited from the original schema via reflection
	 * @return built instance of {@link GrpcAttributeSchema}
	 */
	@Nonnull
	private static GrpcAttributeSchema toGrpcAttributeSchema(
		@Nonnull AttributeSchemaContract attributeSchema,
		boolean includeNameVariants,
		@Nonnull Predicate<String> inheritedPredicate
	) {
		final boolean isGlobal = attributeSchema instanceof GlobalAttributeSchemaContract;
		final boolean isEntity = attributeSchema instanceof EntityAttributeSchemaContract;
		final GrpcAttributeSchema.Builder builder = GrpcAttributeSchema.newBuilder()
			.setName(attributeSchema.getName())
			.setSchemaType(EvitaEnumConverter.toGrpcAttributeSchemaType(attributeSchema.getClass()))
			.setUnique(EvitaEnumConverter.toGrpcAttributeUniquenessType(attributeSchema.getUniquenessType()))
			.setFilterable(attributeSchema.isFilterable())
			.setSortable(attributeSchema.isSortable())
			.setLocalized(attributeSchema.isLocalized())
			.setNullable(attributeSchema.isNullable())
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(attributeSchema.getType()))
			.setIndexedDecimalPlaces(attributeSchema.getIndexedDecimalPlaces())
			.setInherited(inheritedPredicate.test(attributeSchema.getName()));

		ofNullable(attributeSchema.getDefaultValue())
			.ifPresent(it -> builder.setDefaultValue(EvitaDataTypesConverter.toGrpcEvitaValue(it)));
		ofNullable(attributeSchema.getDescription())
			.ifPresent(it -> builder.setDescription(StringValue.newBuilder().setValue(it).build()));
		ofNullable(attributeSchema.getDeprecationNotice())
			.ifPresent(it -> builder.setDeprecationNotice(StringValue.newBuilder().setValue(it).build()));

		if (isEntity) {
			final EntityAttributeSchemaContract globalAttributeSchema = (EntityAttributeSchemaContract) attributeSchema;
			builder.setRepresentative(globalAttributeSchema.isRepresentative());
		}

		if (isGlobal) {
			final GlobalAttributeSchemaContract globalAttributeSchema = (GlobalAttributeSchemaContract) attributeSchema;
			builder.setRepresentative(globalAttributeSchema.isRepresentative());
			builder.setUniqueGlobally(EvitaEnumConverter.toGrpcGlobalAttributeUniquenessType(globalAttributeSchema.getGlobalUniquenessType()));
		}

		if (includeNameVariants) {
			attributeSchema.getNameVariants()
				.forEach(
					(namingConvention, nameVariant) -> builder.addNameVariant(toGrpcNameVariant(namingConvention, nameVariant))
				);
		}

		return builder.build();
	}

	/**
	 * From passed map where keys are representing attribute names and values {@link SortableAttributeCompoundSchema}
	 * will be built a new map where values are converted to {@link GrpcSortableAttributeCompoundSchema}.
	 *
	 * @param originalSortableAttributeCompoundSchemas map of {@link SortableAttributeCompoundSchema} to be converted
	 *                                                 to map of {@link GrpcSortableAttributeCompoundSchema}
	 * @param includeNameVariants                      if true, name variants will be included in the result
	 * @param inheritedPredicate predicate that matches all attributes that were inherited from the original schema via reflection
	 * @return map where keys are representing attribute names and values are {@link GrpcSortableAttributeCompoundSchema}
	 */
	@Nonnull
	private static Map<String, GrpcSortableAttributeCompoundSchema> toGrpcSortableAttributeCompoundSchemas(
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> originalSortableAttributeCompoundSchemas,
		boolean includeNameVariants,
		@Nonnull Predicate<String> inheritedPredicate
	) {
		final Map<String, GrpcSortableAttributeCompoundSchema> attributeSchemas = CollectionUtils.createHashMap(originalSortableAttributeCompoundSchemas.size());
		for (Map.Entry<String, SortableAttributeCompoundSchemaContract> entry : originalSortableAttributeCompoundSchemas.entrySet()) {
			attributeSchemas.put(
				entry.getKey(),
				toGrpcSortableAttributeCompoundSchema(entry.getValue(), includeNameVariants, inheritedPredicate)
			);
		}
		return attributeSchemas;
	}

	/**
	 * Converts single {@link SortableAttributeCompoundSchema} to {@link GrpcSortableAttributeCompoundSchema}.
	 *
	 * @param attributeCompoundSchema instance of {@link SortableAttributeCompoundSchema} to be converted to {@link GrpcSortableAttributeCompoundSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @param inheritedPredicate predicate that matches all attributes that were inherited from the original schema via reflection
	 *
	 * @return built instance of {@link GrpcSortableAttributeCompoundSchema}
	 */
	@Nonnull
	private static GrpcSortableAttributeCompoundSchema toGrpcSortableAttributeCompoundSchema(
		@Nonnull SortableAttributeCompoundSchemaContract attributeCompoundSchema,
		boolean includeNameVariants,
		@Nonnull Predicate<String> inheritedPredicate
	) {
		final GrpcSortableAttributeCompoundSchema.Builder builder = GrpcSortableAttributeCompoundSchema.newBuilder()
			.setName(attributeCompoundSchema.getName())
			.addAllAttributeElements(toGrpcAttributeElement(attributeCompoundSchema.getAttributeElements()))
			.setInherited(inheritedPredicate.test(attributeCompoundSchema.getName()));

		ofNullable(attributeCompoundSchema.getDescription())
			.ifPresent(it -> builder.setDescription(StringValue.newBuilder().setValue(it).build()));
		ofNullable(attributeCompoundSchema.getDeprecationNotice())
			.ifPresent(it -> builder.setDeprecationNotice(StringValue.newBuilder().setValue(it).build()));

		if (includeNameVariants) {
			attributeCompoundSchema.getNameVariants()
				.forEach(
					(namingConvention, nameVariant) -> builder.addNameVariant(toGrpcNameVariant(namingConvention, nameVariant))
				);
		}

		return builder.build();
	}

	/**
	 * From passed map where keys are representing associated data names and values {@link AssociatedDataSchema} will be built a new map where values are converted
	 * to {@link GrpcAssociatedDataSchema}.
	 *
	 * @param originalAssociatedDataSchemas map of {@link AssociatedDataSchema} to be converted to map of {@link GrpcAssociatedDataSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @return map where keys are representing associated data names and values are {@link GrpcAssociatedDataSchema}
	 */
	@Nonnull
	private static Map<String, GrpcAssociatedDataSchema> toGrpcAssociatedDataSchemas(@Nonnull Map<String, AssociatedDataSchemaContract> originalAssociatedDataSchemas, boolean includeNameVariants) {
		final Map<String, GrpcAssociatedDataSchema> associatedDataSchemas = CollectionUtils.createHashMap(originalAssociatedDataSchemas.size());
		for (Map.Entry<String, AssociatedDataSchemaContract> entry : originalAssociatedDataSchemas.entrySet()) {
			associatedDataSchemas.put(entry.getKey(), toGrpcAssociatedDataSchema(entry.getValue(), includeNameVariants));
		}
		return associatedDataSchemas;
	}

	/**
	 * Converts single {@link AssociatedDataSchema} to {@link GrpcAssociatedDataSchema}.
	 *
	 * @param associatedDataSchema instance of {@link AssociatedDataSchema} to be converted to {@link GrpcAssociatedDataSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @return built instance of {@link GrpcAssociatedDataSchema}
	 */
	@Nonnull
	private static GrpcAssociatedDataSchema toGrpcAssociatedDataSchema(@Nonnull AssociatedDataSchemaContract associatedDataSchema, boolean includeNameVariants) {
		final GrpcAssociatedDataSchema.Builder builder = GrpcAssociatedDataSchema.newBuilder()
			.setName(associatedDataSchema.getName())
			.setType(EvitaDataTypesConverter.toGrpcEvitaAssociatedDataDataType(associatedDataSchema.getType()))
			.setLocalized(associatedDataSchema.isLocalized())
			.setNullable(associatedDataSchema.isNullable());

		if (associatedDataSchema.getDescription() != null) {
			builder.setDescription(StringValue.newBuilder().setValue(associatedDataSchema.getDescription()).build());
		}
		if (associatedDataSchema.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.newBuilder().setValue(associatedDataSchema.getDeprecationNotice()).build());
		}

		if (includeNameVariants) {
			associatedDataSchema.getNameVariants()
				.forEach(
					(namingConvention, nameVariant) -> builder.addNameVariant(toGrpcNameVariant(namingConvention, nameVariant))
				);
		}

		return builder.build();
	}

	/**
	 * From passed map where keys are representing reference data names and values {@link ReferenceSchema} will be built a new map where values are converted
	 * to {@link GrpcReferenceSchema}.
	 *
	 * @param originalReferenceSchemas map of {@link ReferenceSchema} to be converted to map of {@link GrpcReferenceSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @return map where keys are representing associated data names and values are {@link GrpcReferenceSchema}
	 */
	@Nonnull
	private static Map<String, GrpcReferenceSchema> toGrpcReferenceSchemas(@Nonnull Map<String, ReferenceSchemaContract> originalReferenceSchemas, boolean includeNameVariants) {
		final Map<String, GrpcReferenceSchema> referenceSchemas = CollectionUtils.createHashMap(originalReferenceSchemas.size());
		for (Map.Entry<String, ReferenceSchemaContract> entry : originalReferenceSchemas.entrySet()) {
			referenceSchemas.put(entry.getKey(), toGrpcReferenceSchema(entry.getValue(), includeNameVariants));
		}
		return referenceSchemas;
	}

	/**
	 * Converts single {@link ReferenceSchema} to {@link GrpcReferenceSchema}.
	 *
	 * @param referenceSchema instance of {@link ReferenceSchema} to be converted to {@link GrpcReferenceSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @return built instance of {@link GrpcReferenceSchema}
	 */
	@Nonnull
	private static GrpcReferenceSchema toGrpcReferenceSchema(@Nonnull ReferenceSchemaContract referenceSchema, boolean includeNameVariants) {
		final GrpcReferenceSchema.Builder builder = GrpcReferenceSchema.newBuilder()
			.setName(referenceSchema.getName())
			.setCardinality(toGrpcCardinality(referenceSchema.getCardinality()))
			.setEntityType(referenceSchema.getReferencedEntityType())
			.setEntityTypeRelatesToEntity(referenceSchema.isReferencedEntityTypeManaged())
			.setReferencedEntityTypeManaged(referenceSchema.isReferencedEntityTypeManaged())
			.setGroupTypeRelatesToEntity(referenceSchema.isReferencedGroupTypeManaged())
			.setReferencedGroupTypeManaged(referenceSchema.isReferencedGroupTypeManaged())
			.setIndexed(referenceSchema.isIndexed())
			.setFaceted(referenceSchema.isFaceted());

		if (referenceSchema.getReferencedGroupType() != null) {
			builder.setGroupType(StringValue.newBuilder().setValue(referenceSchema.getReferencedGroupType()).build());
		}
		if (referenceSchema.getDescription() != null) {
			builder.setDescription(StringValue.newBuilder().setValue(referenceSchema.getDescription()).build());
		}
		if (referenceSchema.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.newBuilder().setValue(referenceSchema.getDeprecationNotice()).build());
		}

		final Predicate<String> inheritedPredicate;
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedSchema) {
			builder.setReflectedReferenceName(StringValue.of(reflectedSchema.getReflectedReferenceName()))
				.setDescriptionInherited(reflectedSchema.isDescriptionInherited())
				.setDeprecationNoticeInherited(reflectedSchema.isDeprecatedInherited())
				.setCardinalityInherited(reflectedSchema.isCardinalityInherited())
				.setIndexedInherited(reflectedSchema.isIndexedInherited())
				.setFacetedInherited(reflectedSchema.isFacetedInherited())
				.setAttributesInherited(reflectedSchema.isAttributesInherited());
			for (String attributeName : reflectedSchema.getAttributesExcludedFromInheritance()) {
				builder.addAttributesExcludedFromInheritance(attributeName);
			}
			final Set<String> declaredAttributes = reflectedSchema.getDeclaredAttributes().keySet();
			inheritedPredicate = attributeName -> !declaredAttributes.contains(attributeName);
		} else {
			inheritedPredicate = attributeName -> false;
		}

		builder
			.putAllAttributes(toGrpcAttributeSchemas(referenceSchema.getAttributes(), includeNameVariants, inheritedPredicate))
			.putAllSortableAttributeCompounds(toGrpcSortableAttributeCompoundSchemas(referenceSchema.getSortableAttributeCompounds(), includeNameVariants, inheritedPredicate));

		if (includeNameVariants) {
			referenceSchema.getNameVariants()
				.forEach(
					(namingConvention, nameVariant) -> builder.addNameVariant(toGrpcNameVariant(namingConvention, nameVariant))
				);
			if (!referenceSchema.isReferencedEntityTypeManaged()) {
				referenceSchema.getEntityTypeNameVariants(
						referencedEntityType -> {
							throw new GenericEvitaInternalError("Should not be called!");
						}
					)
					.forEach(
						(namingConvention, nameVariant) -> builder.addEntityTypeNameVariant(toGrpcNameVariant(namingConvention, nameVariant))
					);
			}
			if (referenceSchema.getReferencedGroupType() != null && !referenceSchema.isReferencedGroupTypeManaged()) {
				referenceSchema.getGroupTypeNameVariants(
						referencedEntityType -> {
							throw new GenericEvitaInternalError("Should not be called!");
						}
					)
					.forEach(
						(namingConvention, nameVariant) -> builder.addGroupTypeNameVariant(toGrpcNameVariant(namingConvention, nameVariant))
					);
			}
		}

		return builder.build();
	}

	/**
	 * Creates {@link AttributeSchema} from the {@link GrpcAttributeSchema}.
	 */
	@Nonnull
	private static <T extends AttributeSchemaContract> T toAttributeSchema(@Nonnull GrpcAttributeSchema attributeSchema, @Nonnull Class<T> expectedType) {
		if (attributeSchema.getSchemaType() == GrpcAttributeSchemaType.GLOBAL) {
			if (expectedType.isAssignableFrom(GlobalAttributeSchema.class)) {
				//noinspection unchecked
				return (T) GlobalAttributeSchema._internalBuild(
					attributeSchema.getName(),
					NamingConvention.generate(attributeSchema.getName()),
					attributeSchema.hasDescription() ? attributeSchema.getDescription().getValue() : null,
					attributeSchema.hasDeprecationNotice() ? attributeSchema.getDeprecationNotice().getValue() : null,
					EvitaEnumConverter.toAttributeUniquenessType(attributeSchema.getUnique()),
					EvitaEnumConverter.toGlobalAttributeUniquenessType(attributeSchema.getUniqueGlobally()),
					attributeSchema.getFilterable(),
					attributeSchema.getSortable(),
					attributeSchema.getLocalized(),
					attributeSchema.getNullable(),
					attributeSchema.getRepresentative(),
					EvitaDataTypesConverter.toEvitaDataType(attributeSchema.getType()),
					attributeSchema.hasDefaultValue() ? EvitaDataTypesConverter.toEvitaValue(attributeSchema.getDefaultValue()) : null,
					attributeSchema.getIndexedDecimalPlaces()
				);
			} else {
				throw new EvitaInvalidUsageException("Expected global attribute, but `" + attributeSchema.getSchemaType() + "` was provided!");
			}
		} else if (attributeSchema.getSchemaType() == GrpcAttributeSchemaType.ENTITY) {
			if (expectedType.isAssignableFrom(EntityAttributeSchemaContract.class)) {
				//noinspection unchecked
				return (T) EntityAttributeSchema._internalBuild(
					attributeSchema.getName(),
					NamingConvention.generate(attributeSchema.getName()),
					attributeSchema.hasDescription() ? attributeSchema.getDescription().getValue() : null,
					attributeSchema.hasDeprecationNotice() ? attributeSchema.getDeprecationNotice().getValue() : null,
					EvitaEnumConverter.toAttributeUniquenessType(attributeSchema.getUnique()),
					attributeSchema.getFilterable(),
					attributeSchema.getSortable(),
					attributeSchema.getLocalized(),
					attributeSchema.getNullable(),
					attributeSchema.getRepresentative(),
					EvitaDataTypesConverter.toEvitaDataType(attributeSchema.getType()),
					attributeSchema.hasDefaultValue() ? EvitaDataTypesConverter.toEvitaValue(attributeSchema.getDefaultValue()) : null,
					attributeSchema.getIndexedDecimalPlaces()
				);
			} else {
				throw new EvitaInvalidUsageException("Expected global attribute, but `" + attributeSchema.getSchemaType() + "` was provided!");
			}
		} else {
			if (expectedType.isAssignableFrom(AttributeSchema.class)) {
				//noinspection unchecked
				return (T) AttributeSchema._internalBuild(
					attributeSchema.getName(),
					NamingConvention.generate(attributeSchema.getName()),
					attributeSchema.hasDescription() ? attributeSchema.getDescription().getValue() : null,
					attributeSchema.hasDeprecationNotice() ? attributeSchema.getDeprecationNotice().getValue() : null,
					EvitaEnumConverter.toAttributeUniquenessType(attributeSchema.getUnique()),
					attributeSchema.getFilterable(),
					attributeSchema.getSortable(),
					attributeSchema.getLocalized(),
					attributeSchema.getNullable(),
					EvitaDataTypesConverter.toEvitaDataType(attributeSchema.getType()),
					attributeSchema.hasDefaultValue() ? EvitaDataTypesConverter.toEvitaValue(attributeSchema.getDefaultValue()) : null,
					attributeSchema.getIndexedDecimalPlaces()
				);
			} else {
				throw new EvitaInvalidUsageException("Expected global attribute, but `" + attributeSchema.getSchemaType() + "` was provided!");
			}
		}
	}

	/**
	 * Creates {@link AssociatedDataSchema} from the {@link GrpcAssociatedDataSchema}.
	 */
	@Nonnull
	private static AssociatedDataSchemaContract toAssociatedDataSchema(@Nonnull GrpcAssociatedDataSchema associatedDataSchema) {
		final Class<? extends Serializable> javaType = EvitaDataTypesConverter.toEvitaDataType(associatedDataSchema.getType());
		return AssociatedDataSchema._internalBuild(
			associatedDataSchema.getName(),
			NamingConvention.generate(associatedDataSchema.getName()),
			associatedDataSchema.hasDescription() ? associatedDataSchema.getDescription().getValue() : null,
			associatedDataSchema.hasDeprecationNotice() ? associatedDataSchema.getDeprecationNotice().getValue() : null,
			javaType,
			associatedDataSchema.getLocalized(),
			associatedDataSchema.getNullable()
		);
	}

	/**
	 * Creates {@link ReferenceSchema} from the {@link GrpcReferenceSchema}.
	 */
	@Nonnull
	private static ReferenceSchemaContract toReferenceSchema(@Nonnull GrpcReferenceSchema referenceSchema) {
		final Cardinality cardinality = toCardinality(referenceSchema.getCardinality());
		if (referenceSchema.hasReflectedReferenceName()) {
			return ReflectedReferenceSchema._internalBuild(
				referenceSchema.getName(),
				NamingConvention.generate(referenceSchema.getName()),
				referenceSchema.hasDescription() ? referenceSchema.getDescription().getValue() : null,
				referenceSchema.hasDeprecationNotice() ? referenceSchema.getDeprecationNotice().getValue() : null,
				referenceSchema.getEntityType(),
				NamingConvention.generate(referenceSchema.getEntityType()),
				referenceSchema.hasGroupType() ? referenceSchema.getGroupType().getValue() : null,
				referenceSchema.getReferencedGroupTypeManaged()
					? Collections.emptyMap()
					: NamingConvention.generate(referenceSchema.getGroupType().getValue()),
				referenceSchema.getReferencedGroupTypeManaged(),
				referenceSchema.getReflectedReferenceName().getValue(),
				cardinality,
				referenceSchema.getIndexed(),
				referenceSchema.getFaceted(),
				referenceSchema.getAttributesMap()
					.entrySet()
					.stream()
					.collect(Collectors.toMap(
						Entry::getKey,
						it -> toAttributeSchema(it.getValue(), AttributeSchemaContract.class)
					)),
				referenceSchema.getSortableAttributeCompoundsMap()
					.entrySet()
					.stream()
					.collect(
						Collectors.toMap(
							Entry::getKey,
							it -> toSortableAttributeCompoundSchema(it.getValue())
						)
					),
				referenceSchema.getDescriptionInherited(),
				referenceSchema.getDeprecationNoticeInherited(),
				referenceSchema.getCardinalityInherited(),
				referenceSchema.getIndexedInherited(),
				referenceSchema.getFacetedInherited(),
				referenceSchema.getAttributesInherited(),
				referenceSchema.getAttributesExcludedFromInheritanceList().toArray(String[]::new),
				// here we create mock original reference - it won't be used in most cases, except for detecting
				// inherited attributes
				ReferenceSchema._internalBuild(
					referenceSchema.getReflectedReferenceName().getValue(),
					NamingConvention.generate(referenceSchema.getReflectedReferenceName().getValue()),
					null,
					null,
					referenceSchema.getEntityType(),
					Map.of(),
					true,
					cardinality,
					referenceSchema.hasGroupType() ? referenceSchema.getGroupType().getValue() : null,
					Map.of(),
					referenceSchema.getReferencedGroupTypeManaged(),
					referenceSchema.getIndexed(),
					referenceSchema.getFaceted(),
					referenceSchema.getAttributesMap()
						.entrySet()
						.stream()
						.filter(it -> it.getValue().getInherited())
						.collect(Collectors.toMap(
							Entry::getKey,
							it -> toAttributeSchema(it.getValue(), AttributeSchemaContract.class)
						)),
					referenceSchema.getSortableAttributeCompoundsMap()
						.entrySet()
						.stream()
						.filter(it -> it.getValue().getInherited())
						.collect(
							Collectors.toMap(
								Entry::getKey,
								it -> toSortableAttributeCompoundSchema(it.getValue())
							)
						)
				)
			);
		} else {
			return ReferenceSchema._internalBuild(
				referenceSchema.getName(),
				NamingConvention.generate(referenceSchema.getName()),
				referenceSchema.hasDescription() ? referenceSchema.getDescription().getValue() : null,
				referenceSchema.hasDeprecationNotice() ? referenceSchema.getDeprecationNotice().getValue() : null,
				referenceSchema.getEntityType(),
				referenceSchema.getReferencedEntityTypeManaged()
					? Collections.emptyMap()
					: NamingConvention.generate(referenceSchema.getEntityType()),
				referenceSchema.getReferencedEntityTypeManaged(),
				cardinality,
				referenceSchema.hasGroupType() ? referenceSchema.getGroupType().getValue() : null,
				referenceSchema.getReferencedGroupTypeManaged()
					? Collections.emptyMap()
					: NamingConvention.generate(referenceSchema.getGroupType().getValue()),
				referenceSchema.getReferencedGroupTypeManaged(),
				referenceSchema.getIndexed(),
				referenceSchema.getFaceted(),
				referenceSchema.getAttributesMap()
					.entrySet()
					.stream()
					.collect(Collectors.toMap(
						Entry::getKey,
						it -> toAttributeSchema(it.getValue(), AttributeSchemaContract.class)
					)),
				referenceSchema.getSortableAttributeCompoundsMap()
					.entrySet()
					.stream()
					.collect(
						Collectors.toMap(
							Entry::getKey,
							it -> toSortableAttributeCompoundSchema(it.getValue())
						)
					)
			);
		}
	}

	/**
	 * Creates {@link SortableAttributeCompoundSchema} from the {@link GrpcSortableAttributeCompoundSchema}.
	 */
	@Nonnull
	private static SortableAttributeCompoundSchemaContract toSortableAttributeCompoundSchema(@Nonnull GrpcSortableAttributeCompoundSchema sortableAttributeCompound) {
		return SortableAttributeCompoundSchema._internalBuild(
			sortableAttributeCompound.getName(),
			NamingConvention.generate(sortableAttributeCompound.getName()),
			sortableAttributeCompound.hasDescription() ? sortableAttributeCompound.getDescription().getValue() : null,
			sortableAttributeCompound.hasDeprecationNotice() ? sortableAttributeCompound.getDeprecationNotice().getValue() : null,
			toAttributeElement(sortableAttributeCompound.getAttributeElementsList())
		);
	}

}
