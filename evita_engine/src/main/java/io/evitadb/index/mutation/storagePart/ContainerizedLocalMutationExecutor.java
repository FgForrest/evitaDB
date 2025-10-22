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

package io.evitadb.index.mutation.storagePart;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import com.carrotsearch.hppc.cursors.ObjectIntCursor;
import io.evitadb.api.exception.EntityMissingException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.MandatoryAssociatedDataNotProvidedException;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException.MissingReferenceAttribute;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException.CardinalityViolation;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.ConsistencyCheckingLocalMutationExecutor;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.ParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ComparableReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.IntObjBiFunction;
import io.evitadb.function.IntObjPredicate;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.AbstractEntityStorageContainerAccessor;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.Setter;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.getRoaringBitmap;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * evitaDB organizes entity data in storage in following way:
 *
 * - basic entity data, set of locales, set of associated data keys is stored in {@link EntityBodyStoragePart}
 * - attributes are divided into several groups and stored in multiple {@link AttributesStoragePart}
 * - global attributes (i.e. language agnostic)
 * - localized attributes (i.e. tied to specific {@link Locale})
 * - associated data are stored one by one using {@link AssociatedDataStoragePart}
 * - prices are all stored in single container {@link PricesStoragePart} - even if they can be requested
 * price list by price list
 * - references are all stored in single container {@link ReferencesStoragePart} - even if they can be requested
 * by different entity types
 *
 * Containers are modified separately and updated only when really changes. When reading containers - only minimal set
 * of containers that fulfills the requested query is really read from the storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public final class ContainerizedLocalMutationExecutor extends AbstractEntityStorageContainerAccessor
	implements ConsistencyCheckingLocalMutationExecutor, WritableEntityStorageContainerAccessor {
	public static final String ERROR_SAME_KEY_EXPECTED = "Expected same primary key here!";
	@Nonnull
	private final EntityExistence requiresExisting;
	private final int entityPrimaryKey;
	@Nonnull
	private final String entityType;
	@Nonnull private final Supplier<CatalogSchema> catalogSchemaAccessor;
	@Nonnull private final Supplier<EntitySchema> schemaAccessor;
	@Nonnull private final Function<String, DataStoreReader> dataStoreReaderAccessor;
	@Nonnull private final IntSupplier priceInternalIdSupplier;
	@Getter private final boolean entityRemovedEntirely;
	private final DataStoreMemoryBuffer dataStoreUpdater;
	@Getter @Setter private boolean trapChanges;
	private Scope initialEntityScope;
	private EntityBodyStoragePart entityContainer;
	private PricesStoragePart pricesContainer;
	private ReferencesStoragePart initialReferencesStorageContainer;
	private ReferencesStoragePart referencesStorageContainer;
	private AttributesStoragePart globalAttributesStorageContainer;
	private Map<Locale, AttributesStoragePart> languageSpecificAttributesContainer;
	private Map<AssociatedDataKey, AssociatedDataStoragePart> associatedDataContainers;
	private Map<PriceKey, Integer> assignedInternalPriceIdIndex;
	private Map<ComparableReferenceKey, ReferenceKey> assignedPrimaryKeys;
	private Set<Locale> addedLocales;
	private Set<Locale> removedLocales;

	/**
	 * Retrieves all attribute keys specified in the passed {@link AttributesStoragePart}.
	 */
	@Nonnull
	private static Set<AttributeKey> collectAttributeKeys(@Nullable AttributesStoragePart storagePart) {
		return ofNullable(storagePart)
			.map(AttributesStoragePart::getAttributes)
			.map(
				it -> Arrays.stream(it)
					.filter(Droppable::exists)
					.map(AttributeValue::key)
					.collect(Collectors.toSet())
			)
			.orElse(Collections.emptySet());
	}

	/**
	 * Retrieves all attribute keys specified in the passed {@link ReferenceContract}.
	 */
	@Nonnull
	private static Set<AttributeKey> collectAttributeKeys(@Nonnull ReferenceContract referenceContract) {
		return referenceContract.getAttributeValues()
			.stream()
			.filter(Droppable::exists)
			.map(AttributeValue::key)
			.collect(Collectors.toSet());
	}

	/**
	 * Processes reference attributes with default values.
	 *
	 * @param referenceSchema         The reference schema, expected to be non-null.
	 * @param entityLocales           The set of locales applicable to the entity, expected to be non-null.
	 * @param availableAttributes     The set of available attribute keys, expected to be non-null.
	 * @param missingAttributeHandler The handler for missing attributes, can be null if no handling is required.
	 */
	private static void processReferenceAttributesWithDefaultValue(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull Set<Locale> entityLocales,
		@Nonnull Set<AttributeKey> availableAttributes,
		@Nonnull MissingAttributeHandler missingAttributeHandler
	) {
		referenceSchema.getNonNullableOrDefaultValueAttributes()
			.values()
			.forEach(attributeSchema -> {
				final Serializable defaultValue = attributeSchema.getDefaultValue();
				if (attributeSchema.isLocalized()) {
					entityLocales.stream()
						.map(locale -> new AttributeKey(attributeSchema.getName(), locale))
						.map(key -> availableAttributes.contains(key) ? null : key)
						.filter(Objects::nonNull)
						.forEach(it -> missingAttributeHandler.accept(defaultValue, attributeSchema.isNullable(), it));
				} else {
					final AttributeKey attributeKey = new AttributeKey(attributeSchema.getName());
					if (!availableAttributes.contains(attributeKey)) {
						missingAttributeHandler.accept(defaultValue, attributeSchema.isNullable(), attributeKey);
					}
				}
			});
	}

	/**
	 * Converts the given {@link AttributeMutation} to its inverted type if applicable.
	 *
	 * @param attributeMutation the attribute mutation to be inverted, should not be null
	 * @return a new {@link AttributeMutation} with inverted type if applicable, otherwise returns the original mutation,
	 * never null
	 */
	@Nonnull
	private static AttributeMutation toInvertedTypeAttributeMutation(@Nonnull AttributeMutation attributeMutation) {
		if (attributeMutation instanceof UpsertAttributeMutation upsertAttributeMutation) {
			final Serializable attributeValue = upsertAttributeMutation.getAttributeValue();
			if (attributeValue instanceof Predecessor predecessor) {
				return new UpsertAttributeMutation(upsertAttributeMutation.getAttributeKey(), new ReferencedEntityPredecessor(predecessor.predecessorPk()));
			} else if (attributeValue instanceof ReferencedEntityPredecessor predecessor) {
				return new UpsertAttributeMutation(upsertAttributeMutation.getAttributeKey(), new Predecessor(predecessor.predecessorPk()));
			} else {
				return attributeMutation;
			}
		} else {
			return attributeMutation;
		}
	}

	/**
	 * Creates and registers insert reference mutation for all references in external entities that are primary
	 * references to the entity being created and that do not yet have reflected reference to this entity.
	 * This can be particularly demanding, because we need to fetch external entities reference storage container
	 * content to read internal primary keys of those entities.
	 *
	 * @param entityPrimaryKey           the primary key of the entity to which the references should point
	 * @param scope                      the scope in which the reference index should be looked up
	 * @param referenceSchema            the reference schema, must not be {@code null}
	 * @param referencesStorageContainer the references storage container if exists
	 * @param mutationCollector          the mutation collector, must not be {@code null}
	 * @param eachReferenceConsumer      consumer that is invoked with each created reference
	 */
	private void createAndRegisterReferencePropagationMutation(
		int entityPrimaryKey,
		@Nonnull Scope scope,
		@Nonnull ReflectedReferenceSchema referenceSchema,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull BiConsumer<List<ReferenceKey>, DataStoreReader> eachReferenceConsumer
	) {
		// we need to access index in target entity collection, because we need to retrieve index with primary references
		final List<ReducedEntityIndex> indexes = getAllReducedIndexes(
			dataStoreReader, scope,
			referenceSchema.getReflectedReferenceName(),
			this.entityPrimaryKey,
			referenceSchema.getCardinality().allowsDuplicates()
		);

		for (ReducedEntityIndex referenceIndexWithPrimaryReferences : indexes) {
			// for each such entity create internal (reflected) reference to it (if it doesn't exist)
			final Bitmap referencingEntities = referenceIndexWithPrimaryReferences.getAllPrimaryKeys();
			final DataStoreReader targetDataStoreReader = ofNullable(
				this.dataStoreReaderAccessor.apply(referenceSchema.getReferencedEntityType()))
				.orElseThrow(
					() -> new GenericEvitaInternalError(
						"Data store reader for entity `" + referenceSchema.getReferencedEntityType() +
							"` unexpectedly not found!"
					)
				);
			final RepresentativeReferenceKey rrk = Objects.requireNonNull(
				(RepresentativeReferenceKey) referenceIndexWithPrimaryReferences
					.getIndexKey()
					.discriminator()
			);
			final List<ReferenceKey> referenceKeys = new ArrayList<>(referencingEntities.size());
			final OfInt it = referencingEntities.iterator();
			while (it.hasNext()) {
				int epk = it.nextInt();
				final ReferenceKey primaryRefKeyWithInternalPk = findPrimaryReferenceKeyOrThrow(
					referenceSchema,
					targetDataStoreReader,
					epk,
					new ReferenceKey(
						referenceSchema.getReflectedReferenceName(),
						entityPrimaryKey
					),
					rrk.representativeAttributeValues()
				);
				final ReferenceKey insertedRefKey = new ReferenceKey(
					referenceSchema.getName(), epk, primaryRefKeyWithInternalPk.internalPrimaryKey()
				);
				if (referencesStorageContainer == null || !referencesStorageContainer.contains(insertedRefKey)) {
					mutationCollector.addLocalMutation(new InsertReferenceMutation(insertedRefKey));
					referenceKeys.add(insertedRefKey);
				}
			}

			if (!referenceKeys.isEmpty()) {
				eachReferenceConsumer.accept(referenceKeys, dataStoreReader);
			}
		}
	}

	/**
	 * Retrieves a list of all reduced entity indexes based on the provided parameters.
	 *
	 * @param dataStoreReader the reader used to access the data store and retrieve indexes
	 * @param scope the scope within which indexes should be retrieved
	 * @param referenceName the name of the reference associated with the indexes
	 * @param entityPrimaryKey the primary key of the entity for which indexes are retrieved
	 * @param allowsDuplicates specifies whether duplicate entries are allowed in the retrieved indexes
	 * @return a list of reduced entity indexes that match the specified parameters
	 */
	@Nonnull
	private static List<ReducedEntityIndex> getAllReducedIndexes(
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int entityPrimaryKey,
		boolean allowsDuplicates
	) {
		return getAllReducedIndexes(
			eik -> dataStoreReader.getIndexIfExists(eik, __ -> null),
			eik -> dataStoreReader.getIndexIfExists(eik, __ -> null),
			epk -> dataStoreReader.getIndexIfExists(epk, __ -> null),
			scope, referenceName, entityPrimaryKey, allowsDuplicates
		);
	}

	/**
	 * Retrieves all reduced entity indexes related to the specified reference schema within the given scope.
	 * This method returns a list of indexes that are filtered based on the cardinality and attributes
	 * of the reference schema, ensuring that duplicate references are correctly handled.
	 *
	 * @param referencedTypeIndexProvider    function that provides the index based on the given {@link EntityIndexKey}
	 * @param reducedIndexProvider    function that provides the index based on the given {@link EntityIndexKey}
	 * @param reducedIndexByPkProvider    function that provides the index based on the given index primary key
	 * @param scope            the scope in which the indexes are searched
	 * @param referenceName    the name of the reference schema for which the indexes are retrieved
	 * @param theEntityPrimaryKey the primary key of the entity to which the reference belongs
	 * @param allowsDuplicates flag indicating whether the reference schema allows duplicate references
	 * @return a list of reduced entity indexes corresponding to the given reference schema within the scope
	 */
	@Nonnull
	public static List<ReducedEntityIndex> getAllReducedIndexes(
		@Nonnull Function<EntityIndexKey, ReferencedTypeEntityIndex> referencedTypeIndexProvider,
		@Nonnull Function<EntityIndexKey, ReducedEntityIndex> reducedIndexProvider,
		@Nonnull IntFunction<ReducedEntityIndex> reducedIndexByPkProvider,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int theEntityPrimaryKey,
		boolean allowsDuplicates
	) {
		final List<ReducedEntityIndex> indexes;
		if (allowsDuplicates) {
			// we need to collect all indexes that contain primary references to this entity
			// with all combinations of representative attribute values
			final ReferencedTypeEntityIndex typeIndex = referencedTypeIndexProvider.apply(
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE,
					scope,
					referenceName
				)
			);
			indexes = typeIndex == null ?
				Collections.emptyList() :
				Arrays.stream(typeIndex.getAllReferenceIndexes(theEntityPrimaryKey))
				      .mapToObj(reducedIndexByPkProvider)
				      .filter(Objects::nonNull)
				      .toList();
		} else {
			final ReducedEntityIndex targetIndex = reducedIndexProvider.apply(
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY,
					scope,
					// we need to use this reflected reference name
					new RepresentativeReferenceKey(
						new ReferenceKey(
							referenceName,
							theEntityPrimaryKey
						),
						// and we don't need any representative attribute values,
						// since reference cannot contain duplicates
						ArrayUtils.EMPTY_SERIALIZABLE_ARRAY
					)
				)
			);
			indexes = targetIndex == null ? Collections.emptyList() : List.of(targetIndex);
		}
		return indexes;
	}

	/**
	 * Finds the primary reference key associated with the given {@code representativeReferenceKey}.
	 * Validates that the reference has a known internal primary key and is present in the reference storage.
	 *
	 * @param referenceSchema The schema defining the structure of the reference.
	 * @param dataStoreReader The data store reader instance to fetch reference storage part data.
	 * @param entityPrimaryKey The primary key of the entity to which the reference belongs.
	 * @param referenceKey The generic reference key whose associated primary reference key is to be found.
	 * @param representativeAttributeValues The representative attribute values used to identifythe reference uniquely.
	 * @return The primary reference key with a known internal primary key.
	 * @throws EntityMissingException If the reference storage part is not found for the given entity primary key.
	 * @throws AssertionError If the primary reference key does not have a known internal primary key.
	 */
	@Nonnull
	private ReferenceKey findPrimaryReferenceKeyOrThrow(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull DataStoreReader dataStoreReader,
		int entityPrimaryKey,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Serializable[] representativeAttributeValues
	) {
		final ReferencesStoragePart otherReferenceStoragePart = dataStoreReader.fetch(this.catalogVersion, entityPrimaryKey, ReferencesStoragePart.class);
		Assert.notNull(
			otherReferenceStoragePart,
			() -> new EntityMissingException(
				referenceSchema.getReferencedEntityType(),
				new int[] {entityPrimaryKey},
				"The reference storage part isn't found, but there is a reference in the index!"
			)
		);
		final ReferenceKey primaryRefKeyWithInternalPk = otherReferenceStoragePart
			.findReferenceOrThrowException(
				referenceSchema,
				referenceKey,
				representativeAttributeValues
			)
			.getReferenceKey();

		Assert.isPremiseValid(
			primaryRefKeyWithInternalPk.isKnownInternalPrimaryKey(),
			"The primary reference must have known an internal primary key!"
		);

		return primaryRefKeyWithInternalPk;
	}

	/**
	 * Finds the primary reference key associated with the given {@code genericReferenceKey}.
	 * Validates that the reference has a known internal primary key and is present in the reference storage.
	 *
	 * @param dataStoreReader The data store reader instance to fetch reference storage part data.
	 * @param entityPrimaryKey The primary key of the entity to which the reference belongs.
	 * @param genericReferenceKey The generic reference key whose associated primary reference key is to be found.
	 * @param defaultValueProvider The function that provides a default value if the reference or reference storage part
	 *                             is not found - must never return null value.
	 * @return The primary reference key with a known internal primary key or empty value
	 */
	@Nonnull
	private ReferenceKey findPrimaryReferenceKey(
		@Nonnull DataStoreReader dataStoreReader,
		int entityPrimaryKey,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey genericReferenceKey,
		@Nonnull Serializable[] representativeAttributeValues,
		@Nonnull Function<ReferencesStoragePart, ReferenceKey> defaultValueProvider
	) {
		final Optional<ReferencesStoragePart> otherReferenceStoragePart = ofNullable(
			dataStoreReader.fetch(
				this.catalogVersion,
				entityPrimaryKey,
				ReferencesStoragePart.class
			)
		);
		final ReferenceKey primaryRefKeyWithInternalPk = otherReferenceStoragePart
			.flatMap(
				it -> it.findReference(
					referenceSchema, genericReferenceKey, representativeAttributeValues
				)
			)
			.map(ReferenceContract::getReferenceKey)
			.orElseGet(
				() -> otherReferenceStoragePart
					.map(defaultValueProvider)
					.orElseGet(() -> defaultValueProvider.apply(null))
			);

		Assert.isPremiseValid(
			primaryRefKeyWithInternalPk.isKnownInternalPrimaryKey(),
			"The primary reference must have known an internal primary key!"
		);

		return primaryRefKeyWithInternalPk;
	}

	/**
	 * Generates missing references for entities by verifying the existence of referenced entities
	 * and creating the necessary references if they are absent. This method ensures that the
	 * entities conform to the expected reference structure within the schema.
	 *
	 * @param entityPrimaryKey           The primary key of the entity for which the missing references need to be generated.
	 * @param mutationCollector          Collector used to store the generated mutations for processing.
	 * @param dataStoreReader            Resolved data reader for the referenced entity type.
	 * @param existingEntityPks          Bitmap of existing entity primary keys used for iterating and identifying existing entities.
	 * @param referenceSchema			 The reference schema used to generate the missing references.
	 * @param referencedSchemaName       The name of the schema in which the references are being created.
	 * @param referencedEntityType       The type of the referenced entity that should be checked and potentially created.
	 * @param reducedIndexesProvider 	 Function that provides a list of reduced entity indexes based on the entity primary key.
	 * @param referenceAttributeSupplier Supplier providing an array of reference attribute mutations to be applied to the missing references.
	 * @param entityPrimaryKeyPredicate  A predicate that determines if the entity primary key matches the criteria for insertion.
	 */
	private void generateMissing(
		int entityPrimaryKey,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull RoaringBitmap existingEntityPks,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull String referencedSchemaName,
		@Nonnull String referencedEntityType,
		@Nonnull IntFunction<List<ReducedEntityIndex>> reducedIndexesProvider,
		@Nonnull Function<ReferenceKey, Stream<ReferenceAttributeMutation>> referenceAttributeSupplier,
		@Nonnull IntObjPredicate<Serializable[]> entityPrimaryKeyPredicate
	) {
		final PeekableIntIterator existingPrimaryKeysIterator = existingEntityPks.getIntIterator();
		final AtomicInteger nonExistentRefCounter = new AtomicInteger(0);
		while (existingPrimaryKeysIterator.hasNext()) {
			// this is the primary key of the referenced entity - the owner of a newly created reference
			final int ownerEntityPrimaryKey = existingPrimaryKeysIterator.next();
			//noinspection rawtypes
			final List<LocalMutation> localMutations = new ArrayList<>(32);
			// but we need to match only those, our entity refers to via this reference and doesn't exist
			final List<ReducedEntityIndex> reducedEntityIndexes = reducedIndexesProvider.apply(ownerEntityPrimaryKey);
			if (referenceSchema instanceof ReflectedReferenceSchema rrs) {
				// if we work with the reflected reference schema
				// we need to look up for reference internal PKs in external referenceStoragePart (fetch)
				final ReferenceKey reflectedReferenceKey = new ReferenceKey(rrs.getName(), ownerEntityPrimaryKey);
				final ArrayList<PrimaryReferenceKeyWithRepresentativeAttributes> primaryReferenceKeys = new ArrayList<>(reducedEntityIndexes.size());
				for (final ReducedEntityIndex reducedEntityIndex : reducedEntityIndexes) {
					final Serializable[] representativeAttributeValues = reducedEntityIndex
						.getRepresentativeReferenceKey()
						.representativeAttributeValues();
					if (entityPrimaryKeyPredicate.test(ownerEntityPrimaryKey, representativeAttributeValues)) {
						primaryReferenceKeys.add(
							new PrimaryReferenceKeyWithRepresentativeAttributes(
								findPrimaryReferenceKey(
									dataStoreReader,
									ownerEntityPrimaryKey,
									rrs,
									new ReferenceKey(rrs.getReflectedReferenceName(), entityPrimaryKey),
									representativeAttributeValues,
									referencesStoragePart -> {
										final int lastUsedPrimaryKey = referencesStoragePart == null ?
											0 : referencesStoragePart.getLastUsedPrimaryKey();
										// make up new internal primary key from the known last used primary key
										// target container will adapt the key we assign here
										return new ReferenceKey(
											rrs.getReflectedReferenceName(),
											entityPrimaryKey,
											// if the reference storage part is not present, we will start from 1
											// (created reference will be the first one)
											lastUsedPrimaryKey + nonExistentRefCounter.incrementAndGet()
										);
									}
								),
								representativeAttributeValues
							)
						);
					}
				}

				// now we need to replace assigned reflected reference internal PKs according to the found ones
				// in the primary container, entire operation needs to be performed in a single procedure
				final int replacedReferencesCount = this.referencesStorageContainer.replaceReferences(
					rrs, reflectedReferenceKey,
					examinedRepresentativeAttributeValues -> {
						for (int i = 0; i < primaryReferenceKeys.size(); i++) {
							if (
								Arrays.equals(
									examinedRepresentativeAttributeValues,
									primaryReferenceKeys.get(i).representativeAttributeValues()
								)
							) {
								return i;
							}
						}
						return -1;
					},
					(index, reference) -> new Reference(
						primaryReferenceKeys.get(index).referenceKey().internalPrimaryKey(), reference)
				);
				Assert.isPremiseValid(
					primaryReferenceKeys.size() == replacedReferencesCount,
					"All references must be replaced!"
				);
				// after that we can generate attribute mutations - the reference key is now stable
				for (PrimaryReferenceKeyWithRepresentativeAttributes primaryReferenceKey : primaryReferenceKeys) {
					addMutations(
						localMutations,
						entityPrimaryKey, ownerEntityPrimaryKey, primaryReferenceKey.referenceKey(),
						referencedSchemaName, referenceSchema.getName(), referenceAttributeSupplier
					);
				}
			} else {
				// if we work with the standard reference schema,
				// we will look up for reference internal PKs in the current referenceStoragePart
				for (ReducedEntityIndex reducedEntityIndex : reducedEntityIndexes) {
					final Serializable[] representativeAttributeValues = reducedEntityIndex
						.getRepresentativeReferenceKey()
						.representativeAttributeValues();
					if (entityPrimaryKeyPredicate.test(ownerEntityPrimaryKey, representativeAttributeValues)) {
						this.referencesStorageContainer.findReference(
							referenceSchema, new ReferenceKey(referenceSchema.getName(), ownerEntityPrimaryKey),
							representativeAttributeValues
						).ifPresent(
							reference -> addMutations(
								localMutations,
								entityPrimaryKey, ownerEntityPrimaryKey, reference.getReferenceKey(),
								referencedSchemaName, referenceSchema.getName(), referenceAttributeSupplier
							)
						);
					}
				}
			}
			// finally create enclosing entity mutation that will create the reference
			if (!localMutations.isEmpty()) {
				// if the reference is not present, we need to create it
				mutationCollector.addExternalMutation(
					new ServerEntityUpsertMutation(
						referencedEntityType, ownerEntityPrimaryKey, EntityExistence.MUST_EXIST,
						EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES),
						true,
						true,
						localMutations.toArray(LocalMutation[]::new)
					)
				);
			}
		}
	}

	/**
	 * Adds mutations to the provided list of local mutations based on the given parameters.
	 * This method is used to insert a new reference mutation while applying derived attributes
	 * through a supplier function.
	 *
	 * @param localMutations               the list of local mutations to which the new mutation will be added
	 * @param entityPrimaryKey             the primary key of the entity for which the mutation is being created
	 * @param ownerEntityPrimaryKey        the primary key of the owner entity
	 * @param primaryReferenceKey          the primary reference key associated with the mutation
	 * @param referencedSchemaName         the schema name of the entity being referenced
	 * @param referenceSchemaName          the schema name of the reference
	 * @param referenceAttributeSupplier   a supplier function to derive reference attribute mutations
	 *                                      based on the provided reference key
	 */
	private static void addMutations(
		@SuppressWarnings("rawtypes") @Nonnull List<LocalMutation> localMutations,
		int entityPrimaryKey,
		int ownerEntityPrimaryKey,
		@Nonnull ReferenceKey primaryReferenceKey,
		@Nonnull String referencedSchemaName,
		@Nonnull String referenceSchemaName,
		@Nonnull Function<ReferenceKey, Stream<ReferenceAttributeMutation>> referenceAttributeSupplier
	) {
		localMutations.add(
			new InsertReferenceMutation(
				// reflected references copy internal PK of the reference they reflect
				new ReferenceKey(
					referencedSchemaName,
					entityPrimaryKey,
					primaryReferenceKey.internalPrimaryKey()
				)
			)
		);
		referenceAttributeSupplier.apply(
			new ReferenceKey(
				referenceSchemaName,
				ownerEntityPrimaryKey,
				primaryReferenceKey.internalPrimaryKey()
			)
		).forEach(localMutations::add);
	}

	/**
	 * Removes all existing references for the specified primary key entity.
	 * Iterates through the provided existing entity primary keys and identifies references that match the criteria
	 * to then remove them using the mutation collector.
	 *
	 * @param entityPrimaryKey          The primary key of the entity for which references are to be removed.
	 * @param mutationCollector         The collector used for adding mutations to modify entity references.
	 * @param existingEntityPks         A bitmap containing the primary keys of existing entities to process.
	 * @param referencedSchemaName      The name of the schema for the referenced entities.
	 * @param referencedEntityType      The type of the referenced entities.
	 * @param entityPrimaryKeyPredicate A predicate that determines if the entity primary key matches the criteria for removal.
	 */
	private void removeExistingEntityPks(
		int entityPrimaryKey,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull RoaringBitmap existingEntityPks,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull String referencedSchemaName,
		@Nonnull String referencedEntityType,
		@Nonnull IntObjPredicate<Serializable[]> entityPrimaryKeyPredicate,
		@Nonnull Predicate<ReferenceContract> referencePredicate
	) {
		// and for all of those create missing references
		final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
		final PeekableIntIterator existingPrimaryKeysIterator = existingEntityPks.getIntIterator();
		while (existingPrimaryKeysIterator.hasNext()) {
			final int epk = existingPrimaryKeysIterator.next();
			final List<ReferenceContract> referencesToRemove = this.referencesStorageContainer.findAllReferences(
				new ReferenceKey(referenceSchema.getName(), epk), referencePredicate
			);
			if (!referencesToRemove.isEmpty()) {
				final List<LocalMutation<?, ?>> localMutations = new ArrayList<>(referencesToRemove.size());
				for (ReferenceContract removedReference : referencesToRemove) {
					if (entityPrimaryKeyPredicate.test(epk, rad.getRepresentativeValues(removedReference))) {
						localMutations.add(
							new RemoveReferenceMutation(
								new ReferenceKey(
									referencedSchemaName,
									entityPrimaryKey,
									removedReference.getReferenceKey().internalPrimaryKey()
								)
							)
						);
					}
				}
				// if the dropped reference is present, we need to remove it
				mutationCollector.addExternalMutation(
					new ServerEntityUpsertMutation(
						referencedEntityType, epk, EntityExistence.MUST_EXIST,
						EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES),
						true,
						true,
						localMutations.toArray(LocalMutation[]::new)
					)
				);
			}
		}
	}

	public ContainerizedLocalMutationExecutor(
		@Nonnull DataStoreMemoryBuffer dataStoreUpdater,
		@Nonnull DataStoreReader dataStoreReader,
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EntityExistence requiresExisting,
		@Nonnull Supplier<CatalogSchema> catalogSchemaAccessor,
		@Nonnull Supplier<EntitySchema> schemaAccessor,
		@Nonnull Function<String, DataStoreReader> dataStoreReaderAccessor,
		@Nonnull IntSupplier priceInternalIdSupplier,
		boolean entityRemovedEntirely
	) {
		super(catalogVersion, dataStoreReader);
		this.dataStoreUpdater = dataStoreUpdater;
		this.catalogSchemaAccessor = catalogSchemaAccessor;
		this.schemaAccessor = schemaAccessor;
		this.dataStoreReaderAccessor = dataStoreReaderAccessor;
		this.priceInternalIdSupplier = priceInternalIdSupplier;
		this.entityPrimaryKey = entityPrimaryKey;
		this.entityType = schemaAccessor.get().getName();
		this.entityContainer = getEntityStoragePart(this.entityType, entityPrimaryKey, requiresExisting);
		this.initialEntityScope = this.entityContainer.getScope();
		this.requiresExisting = requiresExisting;
		this.entityRemovedEntirely = entityRemovedEntirely;
	}

	@Override
	public void applyMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		final EntitySchema entitySchema = this.schemaAccessor.get();
		if (localMutation instanceof SetPriceInnerRecordHandlingMutation setPriceInnerRecordHandlingMutation) {
			updatePrices(entitySchema, setPriceInnerRecordHandlingMutation);
		} else if (localMutation instanceof PriceMutation priceMutation) {
			updatePriceIndex(entitySchema, priceMutation);
		} else if (localMutation instanceof ParentMutation parentMutation) {
			updateParent(entitySchema, parentMutation);
		} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
			updateReferences(entitySchema, referenceMutation);
		} else if (localMutation instanceof AttributeMutation attributeMutation) {
			updateAttributes(entitySchema, attributeMutation);
		} else if (localMutation instanceof AssociatedDataMutation associatedDataMutation) {
			updateAssociatedData(entitySchema, associatedDataMutation);
		} else if (localMutation instanceof SetEntityScopeMutation setEntityScopeMutation) {
			updateEntityScope(entitySchema, setEntityScopeMutation);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + localMutation.getClass());
		}
	}

	/**
	 * Completes the local mutation execution phase by ensuring all necessary IDs are assigned and
	 * the references storage container is sorted, if applicable.
	 *
	 * This method primarily checks whether the `referencesStorageContainer` is initialized. If it is,
	 * the method delegates the tasks of assigning missing IDs and sorting to the container. This step
	 * ensures the integrity and order of the data within the storage container after mutations have
	 * been executed.
	 */
	public void finishLocalMutationExecutionPhase() {
		if (this.referencesStorageContainer != null) {
			this.assignedPrimaryKeys = this.referencesStorageContainer.assignMissingIdsAndSort();
		}
	}

	@Override
	public void commit() {
		if (this.entityRemovedEntirely) {
			this.entityContainer.markForRemoval();
		}

		final BiConsumer<Long, StoragePart> remover = this.trapChanges ?
			(catalogVersion, part) -> this.dataStoreUpdater.trapRemoveByPrimaryKey(
				catalogVersion,
				part.getStoragePartPKOrElseThrowException(),
				part.getClass()
			)
			: (catalogVersion, part) -> this.dataStoreUpdater.removeByPrimaryKey(
			catalogVersion,
			part.getStoragePartPKOrElseThrowException(),
			part.getClass()
		);
		final BiConsumer<Long, StoragePart> updater = this.trapChanges ?
			this.dataStoreUpdater::trapUpdate : this.dataStoreUpdater::update;
		// now store all dirty containers
		getChangedEntityStorageParts()
			.forEach(part -> {
				if (part.isEmpty()) {
					remover.accept(this.catalogVersion, part);
				} else {
					Assert.isPremiseValid(
						!this.entityRemovedEntirely,
						"Only removal operations are expected to happen!"
					);
					updater.accept(this.catalogVersion, part);
				}
			});
	}

	@Override
	public void rollback() {
		// do nothing all containers will be discarded along with this instance
	}

	@Override
	public void verifyConsistency() {
		if (!this.entityRemovedEntirely) {
			if (this.entityContainer.isNew()) {
				verifyMandatoryAssociatedData(this.entityContainer);
				verifyReferenceCardinalities(this.referencesStorageContainer);
			} else {
				verifyRemovedMandatoryAssociatedData();
				ofNullable(this.referencesStorageContainer)
					.filter(ReferencesStoragePart::isDirty)
					.ifPresent(this::verifyReferenceCardinalities);
			}
		}
	}

	@Nonnull
	@Override
	public ImplicitMutations popImplicitMutations(
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutationBehavior
	) {
		// apply entity consistency checks and returns list of mutations that needs to be also applied in order
		// maintain entity consistent (i.e. init default values).
		final MutationCollector mutationCollector = new MutationCollector();
		final Scope targetEntityScope = this.entityContainer.getScope();
		if (this.entityContainer.isNew() && !this.entityRemovedEntirely) {
			// we need to check entire entity
			final List<Object> missingMandatedAttributes = new LinkedList<>();
			if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_ATTRIBUTES)) {
				verifyMandatoryAttributes(this.entityContainer, missingMandatedAttributes, true, true, mutationCollector);
			}
			if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFLECTED_REFERENCES)) {
				insertReflectedReferences(
					this.entityPrimaryKey, targetEntityScope, targetEntityScope,
					this.referencesStorageContainer, missingMandatedAttributes, mutationCollector
				);
			}
			if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)) {
				verifyReferenceAttributes(
					targetEntityScope, this.entityContainer, this.referencesStorageContainer,
					inputMutations, missingMandatedAttributes, mutationCollector
				);
			}

			if (!missingMandatedAttributes.isEmpty()) {
				throw new MandatoryAttributesNotProvidedException(this.schemaAccessor.get().getName(), missingMandatedAttributes);
			}
		} else {
			if (this.entityRemovedEntirely) {
				if (this.initialReferencesStorageContainer != null) {
					propagateReferencesToEntangledEntities(
						this.entityPrimaryKey,
						this.initialEntityScope,
						targetEntityScope,
						this.catalogSchemaAccessor.get(),
						this.schemaAccessor.get(),
						this.initialReferencesStorageContainer,
						CreateMode.REMOVE_ALL_EXISTING,
						mutationCollector
					);
				}
			} else {
				// check mandatory attributes
				final List<Object> missingMandatedAttributes = new LinkedList<>();
				// we need to check only changed parts
				if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_ATTRIBUTES)) {
					verifyMandatoryAttributes(
						this.entityContainer,
						missingMandatedAttributes,
						ofNullable(this.globalAttributesStorageContainer).map(AttributesStoragePart::isDirty).orElse(false),
						ofNullable(this.languageSpecificAttributesContainer).map(it -> it.values().stream().anyMatch(AttributesStoragePart::isDirty)).orElse(false),
						mutationCollector
					);
				}

				if (this.initialEntityScope != targetEntityScope) {
					// we need to drop all reflected references in old scope
					final ReferencesStoragePart theReferenceStorageContainer = getCachedReferenceStorageContainer(this.entityPrimaryKey);
					if (theReferenceStorageContainer != null) {
						propagateReferencesToEntangledEntities(
							this.entityPrimaryKey,
							this.initialEntityScope,
							targetEntityScope,
							this.catalogSchemaAccessor.get(),
							this.schemaAccessor.get(),
							theReferenceStorageContainer,
							CreateMode.REMOVE_NON_INDEXED,
							mutationCollector
						);
					}
					// and insert new reflected references in new scope
					insertReflectedReferences(
						this.entityPrimaryKey, this.initialEntityScope, targetEntityScope,
						this.referencesStorageContainer, List.of(), mutationCollector
					);
				} else if (this.referencesStorageContainer != null && this.referencesStorageContainer.isDirty()) {
					if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFLECTED_REFERENCES)) {
						verifyReflectedReferences(
							this.entityPrimaryKey, this.initialEntityScope, targetEntityScope,
							inputMutations, mutationCollector
						);
					}
					if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)) {
						verifyReferenceAttributes(
							targetEntityScope,
							this.entityContainer,
							this.referencesStorageContainer,
							inputMutations,
							missingMandatedAttributes,
							mutationCollector
						);
					}

				}

				if (!missingMandatedAttributes.isEmpty()) {
					throw new MandatoryAttributesNotProvidedException(this.schemaAccessor.get().getName(), missingMandatedAttributes);
				}
			}
		}
		return mutationCollector.toImplicitMutations();
	}

	@Override
	public void registerAssignedPriceId(int entityPrimaryKey, @Nonnull PriceKey priceKey, int internalPriceId) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		if (this.assignedInternalPriceIdIndex == null) {
			this.assignedInternalPriceIdIndex = CollectionUtils.createHashMap(16);
		}
		this.assignedInternalPriceIdIndex.compute(
			priceKey,
			(thePriceKey, existingInternalPriceId) -> {
				Assert.isPremiseValid(
					existingInternalPriceId == null || existingInternalPriceId == internalPriceId,
					"Attempt to change already assigned price id!"
				);
				return internalPriceId;
			}
		);
	}

	@Nonnull
	@Override
	public OptionalInt findExistingInternalId(@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		Integer internalPriceId = this.assignedInternalPriceIdIndex == null ? null : this.assignedInternalPriceIdIndex.get(priceKey);
		if (internalPriceId == null) {
			final PricesStoragePart priceStorageContainer = getPriceStoragePart(entityType, entityPrimaryKey);
			return priceStorageContainer.findExistingInternalIds(priceKey);
		} else {
			return OptionalInt.of(internalPriceId);
		}
	}

	@Nonnull
	@Override
	public Set<Locale> getAddedLocales() {
		return this.addedLocales == null ? Collections.emptySet() : this.addedLocales;
	}

	@Nonnull
	@Override
	public Set<Locale> getRemovedLocales() {
		return this.removedLocales == null ? Collections.emptySet() : this.removedLocales;
	}

	/**
	 * Retrieves all entity storage parts from various containers and assemblages them into an array.
	 * Method is used when the caller needs to return updated entity as its result. In such situation, we collect all
	 * already fetched / updated parts and enrich them with the missing ones and reconstructs the body of the updated
	 * entity.
	 *
	 * @return an array of {@link EntityStoragePart} consisting of the entity container,
	 * global attributes storage container, language-specific attributes container,
	 * prices container, references storage container, and associated data containers.
	 */
	@Nonnull
	public EntityStoragePart[] getEntityStorageParts() {
		return Stream.of(
				Stream.of(this.entityContainer),
				Stream.of(this.globalAttributesStorageContainer),
				this.languageSpecificAttributesContainer == null ?
					Stream.<AttributesStoragePart>empty() : this.languageSpecificAttributesContainer.values().stream(),
				Stream.of(this.pricesContainer),
				Stream.of(this.referencesStorageContainer),
				this.associatedDataContainers == null ?
					Stream.<AssociatedDataStoragePart>empty() : this.associatedDataContainers.values().stream()
			)
			.flatMap(Function.identity())
			.filter(Objects::nonNull)
			.toArray(EntityStoragePart[]::new);
	}

	/**
	 * Returns all entity storage parts that have been changed during the mutation process and fetches all missing
	 * storage parts from persistent storage so that full entity body can be reconstructed.
	 *
	 * @return an array of {@link EntityStoragePart} consisting of the entity container,
	 */
	@Nonnull
	public EntityStoragePart[] getAllEntityStorageParts() {
		final EntityBodyStoragePart entityStorageContainer = getEntityStorageContainer();
		return Stream.of(
				Stream.of(entityStorageContainer),
				Stream.of(
					ofNullable(this.globalAttributesStorageContainer)
						.orElseGet(() -> getAttributeStoragePart(this.entityType, this.entityPrimaryKey))
				),
				entityStorageContainer.getAttributeLocales()
					.stream()
					.map(
						locale -> ofNullable(this.languageSpecificAttributesContainer)
							.map(it -> it.get(locale))
							.orElseGet(() -> getAttributeStoragePart(this.entityType, this.entityPrimaryKey, locale))
					),
				getEntitySchema().isWithPrice() ?
					Stream.of(
						ofNullable(this.pricesContainer)
							.orElseGet(() -> getPriceStoragePart(this.entityType, this.entityPrimaryKey))
					) :
					Stream.<PricesStoragePart>empty(),
				Stream.of(
					ofNullable(this.referencesStorageContainer)
						.orElseGet(() -> getReferencesStoragePart(this.entityType, this.entityPrimaryKey))
				),
				entityStorageContainer.getAssociatedDataKeys()
					.stream()
					.map(
						associatedDataKey -> ofNullable(this.associatedDataContainers)
							.map(it -> it.get(associatedDataKey))
							.orElseGet(() -> getAssociatedDataStoragePart(this.entityType, this.entityPrimaryKey, associatedDataKey))
					)
			)
			.flatMap(Function.identity())
			.filter(Objects::nonNull)
			.toArray(EntityStoragePart[]::new);
	}

	/**
	 * Returns entity primary key of the updated container.
	 *
	 * @return entity primary key
	 */
	public int getEntityPrimaryKey() {
		return this.entityPrimaryKey;
	}

	/**
	 * Retrieves the list of primary keys that have been assigned.
	 *
	 * @return a map of old {@link ReferenceKey} which has been replaced with new {@link ReferenceKey} with
	 *         assigned internal primary key.
	 */
	@Nonnull
	public Map<ComparableReferenceKey, ReferenceKey> getAssignedPrimaryKeys() {
		return this.assignedPrimaryKeys == null ?
			Collections.emptyMap() : this.assignedPrimaryKeys;
	}

	/**
	 * Retrieves the type of the entity.
	 *
	 * @return the entity type as a non-null string.
	 */
	@Nonnull
	public String getEntityType() {
		return this.entityType;
	}

	/**
	 * Retrieves the entity schema using the schema accessor.
	 *
	 * @return EntitySchema object representing the schema.
	 */
	@Nonnull
	public EntitySchema getEntitySchema() {
		return this.schemaAccessor.get();
	}

	@Nullable
	@Override
	protected EntityBodyStoragePart getCachedEntityStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.entityContainer;
	}

	@Nonnull
	@Override
	protected EntityBodyStoragePart cacheEntityStorageContainer(int entityPrimaryKey, @Nonnull EntityBodyStoragePart entityStorageContainer) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		this.entityContainer = entityStorageContainer;
		this.initialEntityScope = entityStorageContainer.getScope();
		return this.entityContainer;
	}

	@Nullable
	@Override
	protected AttributesStoragePart getCachedAttributeStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.globalAttributesStorageContainer;
	}

	@Nonnull
	@Override
	protected AttributesStoragePart cacheAttributeStorageContainer(int entityPrimaryKey, @Nonnull AttributesStoragePart attributesStorageContainer) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		this.globalAttributesStorageContainer = attributesStorageContainer;
		return this.globalAttributesStorageContainer;
	}

	@Nonnull
	@Override
	protected Map<Locale, AttributesStoragePart> getOrCreateCachedLocalizedAttributesStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return ofNullable(this.languageSpecificAttributesContainer)
			.orElseGet(() -> {
				// when not available lazily instantiate it
				this.languageSpecificAttributesContainer = new HashMap<>();
				return this.languageSpecificAttributesContainer;
			});
	}

	@Nonnull
	@Override
	protected Map<AssociatedDataKey, AssociatedDataStoragePart> getOrCreateCachedAssociatedDataStorageContainer(int entityPrimaryKey, @Nonnull AssociatedDataKey key) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return ofNullable(this.associatedDataContainers)
			.orElseGet(() -> {
				// when not available lazily instantiate it
				this.associatedDataContainers = new LinkedHashMap<>();
				return this.associatedDataContainers;
			});
	}

	@Nullable
	@Override
	protected ReferencesStoragePart getCachedReferenceStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.referencesStorageContainer;
	}

	@Nonnull
	@Override
	protected ReferencesStoragePart cacheReferencesStorageContainer(int entityPrimaryKey, @Nonnull ReferencesStoragePart referencesStorageContainer) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		this.referencesStorageContainer = referencesStorageContainer;
		if (this.entityRemovedEntirely) {
			// when the entity is being removed we need to keep the initial state of references
			// in order to correctly propagate reflected references removal
			this.initialReferencesStorageContainer = new ReferencesStoragePart(
				referencesStorageContainer.getEntityPrimaryKey(),
				referencesStorageContainer.getLastUsedPrimaryKey(),
				Arrays.copyOf(referencesStorageContainer.getReferences(), referencesStorageContainer.getReferences().length),
				referencesStorageContainer.sizeInBytes().orElse(-1)
			);
		}
		return this.referencesStorageContainer;
	}

	@Nullable
	@Override
	protected PricesStoragePart getCachedPricesStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.pricesContainer;
	}

	@Nonnull
	@Override
	protected PricesStoragePart cachePricesStorageContainer(int entityPrimaryKey, @Nonnull PricesStoragePart pricesStorageContainer) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		this.pricesContainer = pricesStorageContainer;
		return this.pricesContainer;
	}

	/**
	 * Method processes all mutations that targets entity attributes - e.g. {@link AttributeMutation} and updates
	 * information about entity language accordingly.
	 */
	void recomputeLanguageOnAttributeUpdate(@Nonnull AttributeMutation attributeMutation) {
		final AttributeKey affectedAttribute = attributeMutation.getAttributeKey();

		if (attributeMutation instanceof UpsertAttributeMutation) {
			ofNullable(affectedAttribute.locale())
				.ifPresent(locale -> {
					final EntityBodyStoragePart entityStoragePart = getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST);
					if (entityStoragePart.addAttributeLocale(locale)) {
						registerAddedLocale(locale);
					}
				});
		} else if (attributeMutation instanceof RemoveAttributeMutation) {
			ofNullable(affectedAttribute.locale())
				.ifPresent(locale -> {
					final AttributesStoragePart attributeStoragePart = getAttributeStoragePart(this.entityType, this.entityPrimaryKey, locale);
					final ReferencesStoragePart referencesStoragePart = getReferencesStoragePart(this.entityType, this.entityPrimaryKey);

					if (attributeStoragePart.isEmpty() && !referencesStoragePart.isLocalePresent(locale)) {
						final EntityBodyStoragePart entityStoragePart = getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST);
						if (entityStoragePart.removeAttributeLocale(locale)) {
							registerRemovedLocale(locale);
						}
					}
				});
		} else if (attributeMutation instanceof ApplyDeltaAttributeMutation) {
			// DO NOTHING
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + attributeMutation.getClass());
		}
	}

	/**
	 * Method verifies that all non-mandatory attributes are present on entity.
	 */
	private void verifyMandatoryAttributes(
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nonnull List<Object> missingMandatedAttributes,
		boolean checkGlobal,
		boolean checkLocalized,
		@Nonnull MutationCollector mutationCollector
	) throws MandatoryAttributesNotProvidedException {
		if (!checkGlobal && !checkLocalized) {
			return;
		}
		final EntitySchema entitySchema = this.schemaAccessor.get();
		final Collection<EntityAttributeSchemaContract> nonNullableOrDefaultValueAttributes =
			entitySchema.getNonNullableOrDefaultValueAttributes();
		if (nonNullableOrDefaultValueAttributes.isEmpty()) {
			return;
		}

		final Set<AttributeKey> availableGlobalAttributes = checkGlobal ?
			collectAttributeKeys(this.globalAttributesStorageContainer) : Collections.emptySet();
		final Set<Locale> entityLocales = entityStorageContainer.getLocales();
		final Map<Locale, Set<AttributeKey>> availableLocalizedAttributes = checkLocalized ?
			entityLocales
				.stream()
				.collect(
					Collectors.toMap(
						Function.identity(),
						it -> collectAttributeKeys(getAttributeStoragePart(this.entityType, this.entityPrimaryKey, it))
					)
				) : Collections.emptyMap();

		final MissingAttributeHandler missingAttributeHandler = (defaultValue, nullable, attributeKey) -> {
			Assert.isPremiseValid(attributeKey != null, "Attribute key must not be null!");
			if (defaultValue == null) {
				if (!nullable) {
					missingMandatedAttributes.add(attributeKey);
				}
			} else {
				mutationCollector.addLocalMutation(
					new UpsertAttributeMutation(attributeKey, defaultValue)
				);
			}
		};

		nonNullableOrDefaultValueAttributes
			.forEach(attribute -> {
				final Serializable defaultValue = attribute.getDefaultValue();
				if (checkLocalized && attribute.isLocalized()) {
					entityLocales.stream()
						.map(locale -> new AttributeKey(attribute.getName(), locale))
						.flatMap(key -> ofNullable(availableLocalizedAttributes.get(key.locale()))
							.map(it -> it.contains(key)).orElse(false) ? Stream.empty() : Stream.of(key))
						.forEach(it -> missingAttributeHandler.accept(defaultValue, attribute.isNullable(), it));
				} else if (checkGlobal && !attribute.isLocalized()) {
					final AttributeKey attributeKey = new AttributeKey(attribute.getName());
					if (!availableGlobalAttributes.contains(attributeKey)) {
						missingAttributeHandler.accept(defaultValue, attribute.isNullable(), attributeKey);
					}
				}
			});
	}

	/**
	 * Method handles situation when new entity is created and fully set up. It:
	 *
	 * 1. creates new implicit references for each:
	 * a) entity reflected reference when there is existing referenced entity with reference counterpart
	 * b) entity reference when there is existing referenced entity with reflected reference counterpart
	 * 2. creates new entity upsert mutations with insert reference for each:
	 * b) entity reflected reference which targets existing referenced entity
	 * b) entity reference which targets existing referenced entity with reflected reference to our entity reference
	 *
	 * @param entityPrimaryKey           the primary key of the entity for which the reflected references need to be generated
	 * @param sourceEntityScope          the source scope of the entity, must not be null
	 * @param targetEntityScope          the scope of the target entity, must not be null
	 * @param referencesStorageContainer The container for the references storage part
	 *                                   Can be null if there are no references to insert.
	 * @param missingMandatedAttributes  The list of missing mandated attributes.
	 * @param mutationCollector          The collector for mutations.
	 *                                   Cannot be null.
	 */
	private void insertReflectedReferences(
		int entityPrimaryKey,
		@Nonnull Scope sourceEntityScope,
		@Nonnull Scope targetEntityScope,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector
	) {
		final EntitySchema entitySchema = this.schemaAccessor.get();
		final CatalogSchema catalogSchema = this.catalogSchemaAccessor.get();

		setupReferencesOnEntityCreation(
			entityPrimaryKey,
			referencesStorageContainer,
			targetEntityScope,
			catalogSchema,
			entitySchema,
			missingMandatedAttributes,
			mutationCollector
		);

		if (referencesStorageContainer != null) {
			propagateReferencesToEntangledEntities(
				entityPrimaryKey, sourceEntityScope, targetEntityScope,
				catalogSchema, entitySchema, referencesStorageContainer,
				CreateMode.INSERT_MISSING,
				mutationCollector
			);
		}
	}

	/**
	 * Updates all entities that are entangled with the provided {@link CatalogSchema} and {@link EntitySchema}.
	 *
	 * @param entityPrimaryKey           the primary key of the entity for which the reflected references need to be generated
	 * @param referencesStorageContainer container with existing references if exists
	 * @param catalogSchema              the catalog schema, must not be null
	 * @param entitySchema               the entity schema, must not be null
	 * @param missingMandatedAttributes  The list of missing mandated attributes.
	 * @param mutationCollector          the collector for mutations, must not be null
	 */
	private void setupReferencesOnEntityCreation(
		int entityPrimaryKey,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull Scope scope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector
	) {
		// first iterate over all reference schemas in this entity and setup reflections for them
		final Collection<ReferenceSchemaContract> referenceSchemas = entitySchema.getReferences().values();
		for (ReferenceSchemaContract referenceSchema : referenceSchemas) {
			// if the schema is reflected reference, we need to find the target entity and its index
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				final String referencedEntityType = referenceSchema.getReferencedEntityType();
				// if the target entity and reference schema exists
				ofNullable(this.dataStoreReaderAccessor.apply(referencedEntityType))
					.ifPresent(
						dataStoreReader -> createAndRegisterReferencePropagationMutation(
							entityPrimaryKey,
							scope,
							reflectedReferenceSchema,
							referencesStorageContainer,
							dataStoreReader,
							mutationCollector,
							(referenceKeys, dsr) -> setupMandatoryAttributes(
								catalogSchema,
								entitySchema,
								missingMandatedAttributes,
								mutationCollector,
								reflectedReferenceSchema,
								referenceKeys,
								dsr
							)
						));
			}
		}
	}

	/**
	 * Configures and processes mandatory attributes for a referenced entity and collects any missing mandated attributes.
	 *
	 * @param catalogSchema             The schema of the catalog to which the entity belongs, providing context for the reference.
	 * @param entitySchema              The schema of the entity, defining attributes and structure for the given entity.
	 * @param missingMandatedAttributes A list to collect attributes that are identified as missing based on schema constraints.
	 * @param mutationCollector         A collector to store mutations that need to be applied for processing attributes.
	 * @param referenceSchema           The schema of
	 */
	private void setupMandatoryAttributes(
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull List<ReferenceKey> referenceKeys,
		@Nonnull DataStoreReader referenceIndexDataStoreReader
	) {
		final ReferenceBlock<ReferenceKey> referenceBlock = new ReferenceBlock<>(
			catalogSchema,
			this.entityContainer.getLocales(),
			entitySchema,
			referenceSchema,
			// create a new attribute value provider for the reflected reference
			new ReferencedEntityAttributeValueProvider(
				this.catalogVersion,
				this.entityPrimaryKey,
				referenceKeys,
				referenceIndexDataStoreReader
			)
		);
		referenceKeys.stream()
			.flatMap(referenceKey -> referenceBlock.getAttributeSupplier().apply(referenceKey))
			.forEach(mutationCollector::addLocalMutation);
		// register missing attributes if any
		final Set<AttributeKey> missingRequiredAttributes = referenceBlock.getMissingMandatedAttributes();
		if (!missingRequiredAttributes.isEmpty()) {
			missingMandatedAttributes.addAll(missingRequiredAttributes);
		}
	}

	/**
	 * Verifies the reflected references of the input mutations and collects them using the provided mutation collector.
	 * This method checks each mutation in the input list and determines if it is an InsertReferenceMutation or a RemoveReferenceMutation.
	 * For InsertReferenceMutation, it propagates the reference modification by collecting all similar mutations and
	 * creating a new mutation object. For RemoveReferenceMutation, it follows the same process.
	 *
	 * @param entityPrimaryKey  The primary key of the entity to verify the reflected references.
	 * @param sourceEntityScope the source scope of the entity, must not be null
	 * @param targetEntityScope the scope of the target entity, must not be null
	 * @param inputMutations    A list of input mutations to verify the reflected references.
	 * @param mutationCollector The mutation collector used to collect the verified reflected references.
	 */
	private void verifyReflectedReferences(
		int entityPrimaryKey,
		@Nonnull Scope sourceEntityScope,
		@Nonnull Scope targetEntityScope,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull MutationCollector mutationCollector
	) {
		final CatalogSchema catalogSchema = this.catalogSchemaAccessor.get();
		final EntitySchema entitySchema = this.schemaAccessor.get();
		final IntHashSet processedMutations = new IntHashSet(inputMutations.size());
		for (int i = 0; i < inputMutations.size(); i++) {
			if (!processedMutations.contains(i)) {
				final LocalMutation<?, ?> inputMutation = inputMutations.get(i);
				if (inputMutation instanceof InsertReferenceMutation irm) {
					final ReferenceKey referenceKey = irm.getReferenceKey();
					final int currentIndex = i;
					final String thisReferenceName = referenceKey.referenceName();
					propagateReferenceModification(
						entityPrimaryKey, sourceEntityScope, targetEntityScope,
						catalogSchema, entitySchema, thisReferenceName,
						() -> new ReferenceBlock<>(
							catalogSchema,
							this.entityContainer.getLocales(),
							entitySchema,
							entitySchema.getReferenceOrThrowException(thisReferenceName),
							new MutationAttributeValueProvider(
								this.entityPrimaryKey,
								this.getEntitySchema(),
								irm,
								currentIndex,
								processedMutations,
								inputMutations
							)
						),
						mutationCollector,
						CreateMode.INSERT_MISSING
					);
				} else if (inputMutation instanceof RemoveReferenceMutation rrm) {
					final ReferenceKey referenceKey = rrm.getReferenceKey();
					final int currentIndex = i;
					final String thisReferenceName = referenceKey.referenceName();
					propagateReferenceModification(
						entityPrimaryKey, sourceEntityScope, targetEntityScope,
						catalogSchema, entitySchema, thisReferenceName,
						() -> new ReferenceBlock<>(
							catalogSchema,
							this.entityContainer.getLocales(),
							entitySchema,
							entitySchema.getReferenceOrThrowException(thisReferenceName),
							new MutationAttributeValueProvider(
								this.entityPrimaryKey,
								this.getEntitySchema(),
								rrm,
								currentIndex,
								processedMutations,
								inputMutations
							)
						),
						mutationCollector,
						CreateMode.REMOVE_ALL_EXISTING
					);
				}
			}
		}
	}

	/**
	 * Propagates references to entangled entities based on the given schema and storage container.
	 * This method relies on the fact that references are ordered by reference key.
	 *
	 * @param entityPrimaryKey           the primary key of the entity, must not be null
	 * @param sourceEntityScope          the source scope of the entity, must not be null
	 * @param targetEntityScope          the scope of the target entity, must not be null
	 * @param catalogSchema              The catalog schema.
	 * @param entitySchema               The entity schema.
	 * @param referencesStorageContainer The container holding references
	 * @param createMode                 the mode of this propagate function - it can either insert mising references or remove all existing
	 * @param mutationCollector          The mutation collector to accumulate mutations
	 */
	private void propagateReferencesToEntangledEntities(
		int entityPrimaryKey,
		@Nonnull Scope sourceEntityScope,
		@Nonnull Scope targetEntityScope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferencesStoragePart referencesStorageContainer,
		@Nonnull CreateMode createMode,
		@Nonnull MutationCollector mutationCollector
	) {
		// we can rely on a fact, that references are ordered by reference key
		String referenceName = null;
		// we need to filter out already discarded references
		final ReferenceContract[] references = referencesStorageContainer.getReferences();

		for (int i = 0; i < references.length; i++) {
			final ReferenceContract reference = references[i];
			// skip dropped references
			if (reference.dropped()) {
				continue;
			}
			final String thisReferenceName = reference.getReferenceName();
			// fast forward to reference with different name
			if (!Objects.equals(thisReferenceName, referenceName)) {
				referenceName = thisReferenceName;
				// setup references
				final int currentIndex = i;
				propagateReferenceModification(
					entityPrimaryKey,
					sourceEntityScope,
					targetEntityScope,
					catalogSchema,
					entitySchema,
					thisReferenceName,
					() -> new ReferenceBlock<>(
						catalogSchema, this.entityContainer.getLocales(), entitySchema,
						entitySchema.getReferenceOrThrowException(thisReferenceName),
						new ReferenceAttributeValueProvider(
							this.entityPrimaryKey, currentIndex, references, thisReferenceName
						)
					),
					mutationCollector,
					createMode
				);
			}
		}
	}

	/**
	 * Propagates reference mutation to the external entity one or more references based on the provided schema
	 * and reference parameters.
	 *
	 * @param entityPrimaryKey       the primary key of the entity, must not be null
	 * @param sourceEntityScope      the source scope of the entity, must not be null
	 * @param targetEntityScope      the target scope of the entity, must not be null
	 * @param catalogSchema          the catalog schema, must not be null
	 * @param entitySchema           the entity schema, must not be null
	 * @param referenceName          the name of the reference, must not be null
	 * @param referenceBlockSupplier supplier for primary keys, must not be null
	 * @param mutationCollector      collector for mutations, must not be null
	 * @param createMode             the mode of this propagate function - it can either insert missing references or remove all existing
	 */
	private <T> void propagateReferenceModification(
		int entityPrimaryKey,
		@Nonnull Scope sourceEntityScope,
		@Nonnull Scope targetEntityScope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull String referenceName,
		@Nonnull Supplier<ReferenceBlock<T>> referenceBlockSupplier,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull CreateMode createMode
	) {
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		// access the data store reader of referenced collection
		final DataStoreReader dataStoreReader = this.dataStoreReaderAccessor.apply(referencedEntityType);
		// and if such is found (collection might not yet exist, but we can still reference to it)
		if (dataStoreReader != null) {
			final Optional<ReferenceSchema> referencedSchemaRef = catalogSchema.getEntitySchema(referencedEntityType)
				.map(it -> ((EntitySchemaDecorator) it).getDelegate())
				.flatMap(
					it -> referenceSchema instanceof ReflectedReferenceSchema rrs ?
						of(it.getReferenceOrThrowException(rrs.getReflectedReferenceName())) :
						it.getReflectedReferenceFor(entitySchema.getName(), referenceName)
				);
			// if there is any reflected reference schemas
			if (referencedSchemaRef.isPresent()) {
				final BiPredicate<ReferenceSchemaContract, ReferenceSchemaContract> propagationPredicate;
				switch (createMode) {
					case INSERT_MISSING -> propagationPredicate = (rs1, rs2) ->
						rs1.isIndexedInScope(targetEntityScope) && rs2.isIndexedInScope(targetEntityScope);
					case REMOVE_NON_INDEXED -> propagationPredicate = (rs1, rs2) ->
						!rs1.isIndexedInScope(targetEntityScope) || !rs2.isIndexedInScope(targetEntityScope);
					case REMOVE_ALL_EXISTING -> propagationPredicate = (rs1, rs2) ->
						true;
					default -> throw new GenericEvitaInternalError("Unknown create mode: " + createMode);
				}
				// test the predicate
				final ReferenceSchema referencedSchema = referencedSchemaRef.get();
				if (propagationPredicate.test(referenceSchema, referencedSchema)) {
					final String referencedSchemaName = referencedSchema.getName();
					final boolean allowsDuplicates = referencedSchema.getCardinality().allowsDuplicates();
					// lets collect all primary keys of the reference with the same name into a bitmap
					final ReferenceBlock<T> referenceBlock = referenceBlockSupplier.get();
					final GlobalEntityIndex globalIndex = dataStoreReader.getIndexIfExists(
						new EntityIndexKey(EntityIndexType.GLOBAL, targetEntityScope),
						entityIndexKey -> null
					);
					// if global index is found there is at least one entity of such type
					final RoaringBitmap referencedPrimaryKeys = referenceBlock.getReferencedPrimaryKeys();
					// but we need to match only those our entity refers to via this reference
					final RoaringBitmap existingEntityPks = globalIndex == null ?
						null :
						RoaringBitmap.and(
							referencedPrimaryKeys,
							getRoaringBitmap(globalIndex.getAllPrimaryKeys())
						);

					// when the reflected reference is used, we can reuse shared reduced entity index
					final boolean hardReference = referenceName.equals(referencedSchemaName);
					final Scope usedScope = createMode == CreateMode.INSERT_MISSING ? targetEntityScope : sourceEntityScope;
					final IntObjPredicate<Serializable[]> primaryKeyPredicate;
					if (hardReference) {
						// provide reduced entity index related to provided entity primary key
						primaryKeyPredicate = (epk, representativeAttributeValues) -> {
							final ReducedEntityIndex targetReducedIndex = dataStoreReader.getIndexIfExists(
								new EntityIndexKey(
									EntityIndexType.REFERENCED_ENTITY,
									usedScope,
									new RepresentativeReferenceKey(
										new ReferenceKey(referencedSchemaName, epk),
										representativeAttributeValues
									)
								),
								__ -> null
							);
							// always check this entity primary key
							return targetReducedIndex != null && targetReducedIndex.getAllPrimaryKeys().contains(entityPrimaryKey);
						};
					} else {
						// always provide the same reference index
						primaryKeyPredicate = (epk, representativeAttributeValues) -> {
							final ReducedEntityIndex targetReducedIndex = dataStoreReader.getIndexIfExists(
								new EntityIndexKey(
									EntityIndexType.REFERENCED_ENTITY,
									usedScope,
									new RepresentativeReferenceKey(
										new ReferenceKey(referencedSchemaName, entityPrimaryKey),
										representativeAttributeValues
									)
								),
								__ -> null
							);
							return targetReducedIndex != null && targetReducedIndex.getAllPrimaryKeys().contains(epk);
						};
					}
					switch (createMode) {
						case INSERT_MISSING -> {
							if (existingEntityPks == null && referenceSchema instanceof ReflectedReferenceSchema && !referencedPrimaryKeys.isEmpty()) {
								throw new EntityMissingException(
									referencedEntityType, referencedPrimaryKeys.toArray(),
									"Cannot set up main reference via reflected reference with name `" + referenceName + "`!"
								);
							} else if (existingEntityPks != null) {
								generateMissing(
									entityPrimaryKey,
									mutationCollector,
									dataStoreReader,
									existingEntityPks,
									referenceSchema,
									referencedSchemaName,
									referencedEntityType,
									epk ->
										getAllReducedIndexes(
											this.dataStoreReader,
											usedScope,
											referenceName,
											epk,
											allowsDuplicates
										),
									referenceBlock.getAttributeSupplier(),
									// we need to negate the predicate, because we want to insert missing references
									primaryKeyPredicate.negate()
								);
							}
						}
						case REMOVE_ALL_EXISTING -> removeExistingEntityPks(
							entityPrimaryKey, mutationCollector,
							existingEntityPks == null ? referencedPrimaryKeys : existingEntityPks,
							referenceSchema,
							referencedSchemaName,
							referencedEntityType,
							primaryKeyPredicate,
							Droppable::dropped
						);
						case REMOVE_NON_INDEXED -> removeExistingEntityPks(
							entityPrimaryKey, mutationCollector,
							existingEntityPks == null ? referencedPrimaryKeys : existingEntityPks,
							referenceSchema,
							referencedSchemaName,
							referencedEntityType,
							primaryKeyPredicate,
							Droppable::exists
						);
					}
				}
			}
		}
	}

	/**
	 * Method verifies that all non-mandatory attributes are present on entity references.
	 */
	private void verifyReferenceAttributes(
		@Nonnull Scope scope,
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nullable ReferencesStoragePart referencesStoragePart,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector
	) throws MandatoryAttributesNotProvidedException {
		if (referencesStoragePart == null) {
			return;
		}

		final CatalogSchema catalogSchema = this.catalogSchemaAccessor.get();
		final EntitySchema entitySchema = this.schemaAccessor.get();
		propagateOrphanedReferenceAttributeMutations(
			scope, catalogSchema, entitySchema, inputMutations, mutationCollector
		);

		final Set<Locale> entityLocales = entityStorageContainer.getLocales();
		Arrays.stream(referencesStoragePart.getReferences())
			.filter(Droppable::exists)
			.collect(Collectors.groupingBy(ReferenceContract::getReferenceName))
			.forEach((referenceName, references) -> {
				final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
				for (ReferenceContract reference : references) {
					final Set<AttributeKey> availableAttributes = collectAttributeKeys(reference);
					final List<AttributeKey> missingReferenceMandatedAttribute = new LinkedList<>();
					final MissingAttributeHandler missingAttributeHandler = (defaultValue, nullable, attributeKey) -> {
						Assert.isPremiseValid(attributeKey != null, "Attribute key must not be null!");
						if (defaultValue == null) {
							if (!nullable) {
								missingReferenceMandatedAttribute.add(attributeKey);
							}
						} else {
							mutationCollector.addLocalMutation(
								new ReferenceAttributeMutation(
									reference.getReferenceKey(),
									new UpsertAttributeMutation(attributeKey, defaultValue)
								)
							);
						}
					};

					processReferenceAttributesWithDefaultValue(
						referenceSchema, entityLocales, availableAttributes, missingAttributeHandler
					);

					if (!missingReferenceMandatedAttribute.isEmpty()) {
						missingMandatedAttributes.add(
							new MissingReferenceAttribute(
								referenceName,
								missingReferenceMandatedAttribute
							)
						);
					}
				}
			});
	}

	/**
	 * Propagates mutations related to reference attributes that are not related to any reference insertion.
	 * Method generates mutations that replay attribute mutations in reflected references or in reference attributes in
	 * the target entity collection, so that consistency is maintained.
	 *
	 * @param catalogSchema     The schema of the catalog, containing metadata about the entire data catalog.
	 * @param entitySchema      The schema of the entity, containing metadata about the specific entity type.
	 * @param inputMutations    The list of local mutations to be processed, potentially containing reference attribute mutations.
	 * @param mutationCollector The collector used to gather and store mutations that need to be externally applied.
	 */
	private void propagateOrphanedReferenceAttributeMutations(
		@Nonnull Scope scope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull MutationCollector mutationCollector
	) {
		// we need to avoid propagating reference attribute mutations related to created references
		// those are handled within the reference creation process
		// but we will build them in a lazy way
		final Supplier<Set<ReferenceKey>> createdReferencesSupplier = () -> inputMutations.stream()
			.filter(InsertReferenceMutation.class::isInstance)
			.map(InsertReferenceMutation.class::cast)
			.map(InsertReferenceMutation::getReferenceKey)
			.collect(Collectors.toSet());
		Set<ReferenceKey> createdReferences = null;
		// we need to avoid propagating reference attribute mutations related to removed references
		// those are handled within the reference removal process
		// but we will build them in a lazy way
		final IntObjBiFunction<String, Set<ReferenceKey>> removedReferencesSupplier =
			(epk, entityName) -> mutationCollector.getExternalEntityLocalMutations(
				entityName,
				epk,
				eup -> eup.getLocalMutations()
				          .stream()
				          .filter(RemoveReferenceMutation.class::isInstance)
				          .map(RemoveReferenceMutation.class::cast)
				          .map(ReferenceMutation::getReferenceKey)
			);
		Set<ReferenceKey> removedReferences = null;

		// go through all input mutations
		final Map<EntityReference, List<ReferenceAttributeMutation>> referenceAttributeMutationsByEntityReference = new LazyHashMap<>(inputMutations.size());
		for (LocalMutation<?, ?> inputMutation : inputMutations) {
			// and check if there are any reference attribute mutation
			if (inputMutation instanceof ReferenceAttributeMutation ram) {
				final ReferenceKey referenceKey = ram.getReferenceKey();
				// find the reference schema
				final String referenceName = referenceKey.referenceName();
				final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
				// lazy init created / removed references on first attribute mutation
				createdReferences = createdReferences == null ? createdReferencesSupplier.get() : createdReferences;
				// if the mutation relate to reference which hasn't been created in the same entity update
				if (!createdReferences.contains(referenceKey)) {
					// access the data store reader of referenced collection
					final DataStoreReader dataStoreReader = this.dataStoreReaderAccessor.apply(referenceSchema.getReferencedEntityType());
					// and if such is found (collection might not yet exist, but we can still reference to it)
					if (dataStoreReader != null) {
						// check whether global index exists (there is at least one entity of such type)
						final GlobalEntityIndex globalIndex = dataStoreReader.getIndexIfExists(
							new EntityIndexKey(EntityIndexType.GLOBAL, scope),
							entityIndexKey -> null
						);
						if (globalIndex != null) {
							// and whether it contains the entity we are referencing to
							if (globalIndex.contains(referenceKey.primaryKey())) {
								// if the current reference is a reflected reference
								if (referenceSchema instanceof ReflectedReferenceSchemaContract rrsc) {
									final boolean attributeVisibleFromOtherSide = catalogSchema
										.getEntitySchema(rrsc.getReferencedEntityType())
										.flatMap(it -> it.getReference(rrsc.getReflectedReferenceName()))
										.flatMap(it -> it.getAttribute(ram.getAttributeKey().attributeName()))
										.isPresent();
									// create a mutation to counterpart reference in the referenced entity
									// but only if the attribute is visible from that point of view
									if (attributeVisibleFromOtherSide) {
										removedReferences = removedReferences == null ?
											removedReferencesSupplier.apply(
												referenceKey.primaryKey(),
												referenceSchema.getReferencedEntityType()
											) :
											removedReferences;
										final ReferenceKey referenceKeyToUpdate = new ReferenceKey(
											rrsc.getReflectedReferenceName(), this.entityPrimaryKey,
											ram.getReferenceKey().internalPrimaryKey()
										);
										if (!removedReferences.contains(referenceKeyToUpdate)) {
											// only if the counterpart reference wasn't removed in the same update
											registerPropagatedReferenceAttributeMutation(
												referenceSchema, ram, referenceAttributeMutationsByEntityReference,
												referenceKey, referenceKeyToUpdate
											);
										}
									}
								} else {
									// otherwise check whether there is reflected reference in the referenced entity
									// that relates to our standard reference
									final Optional<ReflectedReferenceSchema> reflectedReferenceSchema =
										catalogSchema.getEntitySchema(referenceSchema.getReferencedEntityType())
										             .map(it -> ((EntitySchemaDecorator) it).getDelegate())
										             .flatMap(it -> it.getReflectedReferenceFor(
											                      entitySchema.getName(),
											                      referenceName
										                      )
										             );
									if (reflectedReferenceSchema.isPresent()) {
										final ReflectedReferenceSchema rrsc = reflectedReferenceSchema.get();
										// if such is found, create a mutation to counterpart reflected reference
										// in the referenced entity
										final boolean attributeVisibleFromOtherSide = rrsc.getAttribute(
											ram.getAttributeKey().attributeName()
										).isPresent();
										// create a mutation to counterpart reference in the referenced entity
										// but only if the attribute is visible from that point of view
										if (attributeVisibleFromOtherSide) {
											removedReferences = removedReferences == null ?
												removedReferencesSupplier.apply(
													referenceKey.primaryKey(),
													referenceSchema.getReferencedEntityType()
												) :
												removedReferences;
											final ReferenceKey referenceKeyToUpdate = new ReferenceKey(
												rrsc.getName(), this.entityPrimaryKey,
												ram.getReferenceKey().internalPrimaryKey()
											);
											if (!removedReferences.contains(referenceKeyToUpdate)) {
												// only if the counterpart reference wasn't removed in the same update
												// only if the counterpart reference wasn't removed in the same update
												registerPropagatedReferenceAttributeMutation(
													referenceSchema, ram, referenceAttributeMutationsByEntityReference,
													referenceKey, referenceKeyToUpdate
												);
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
		// finally register upsert mutations for all collected reference attribute mutations
		for (Entry<EntityReference, List<ReferenceAttributeMutation>> entry : referenceAttributeMutationsByEntityReference.entrySet()) {
			mutationCollector.addExternalMutation(
				new ServerEntityUpsertMutation(
					entry.getKey().getType(),
					entry.getKey().primaryKey(),
					EntityExistence.MUST_EXIST,
					EnumSet.noneOf(ImplicitMutationBehavior.class),
					true,
					true,
					entry.getValue().toArray(ReferenceAttributeMutation[]::new)
				)
			);
		}
	}

	/**
	 * Registers a propagated reference attribute mutation by associating it with a specific entity reference
	 * and grouping it within a map of reference attribute mutations by entity reference.
	 *
	 * @param referenceSchema The schema of the reference that defines the metadata and constraints for the reference.
	 * @param ram The reference attribute mutation to be propagated and registered.
	 * @param referenceAttributeMutationsByEntityReference A map that organizes reference attribute mutations
	 *        by the corresponding entity reference.
	 * @param referenceKey The key of the reference for which the mutation is being registered.
	 * @param referenceKeyToUpdate The key indicating the specific reference attribute mutation to be updated.
	 */
	private static void registerPropagatedReferenceAttributeMutation(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceAttributeMutation ram,
		@Nonnull Map<EntityReference, List<ReferenceAttributeMutation>> referenceAttributeMutationsByEntityReference,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceKey referenceKeyToUpdate
	) {
		referenceAttributeMutationsByEntityReference.computeIfAbsent(
			new EntityReference(
				referenceSchema.getReferencedEntityType(),
				referenceKey.primaryKey()
			),
			// we don't expect many mutations per entity reference
			it -> new LinkedList<>()
		).add(
			new ReferenceAttributeMutation(
				referenceKeyToUpdate,
				toInvertedTypeAttributeMutation(ram.getAttributeMutation())
			)
		);
	}

	/**
	 * Method verifies that all non-mandatory associated data are present on entity.
	 */
	private void verifyMandatoryAssociatedData(@Nonnull EntityBodyStoragePart entityStorageContainer) throws MandatoryAssociatedDataNotProvidedException {
		final EntitySchema entitySchema = this.schemaAccessor.get();
		final Collection<AssociatedDataSchema> nonNullableAssociatedData = entitySchema.getNonNullableAssociatedData();
		if (nonNullableAssociatedData.isEmpty()) {
			return;
		}

		final Set<AssociatedDataKey> availableAssociatedDataKeys = this.entityContainer.getAssociatedDataKeys();
		final Set<Locale> entityLocales = entityStorageContainer.getLocales();

		final List<AssociatedDataKey> missingMandatedAssociatedData = nonNullableAssociatedData
			.stream()
			.flatMap(associatedData -> {
				if (associatedData.isLocalized()) {
					return entityLocales.stream()
						.map(locale -> new AssociatedDataKey(associatedData.getName(), locale))
						.flatMap(key -> availableAssociatedDataKeys.contains(key) ? Stream.empty() : Stream.of(key));
				} else {
					final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedData.getName());
					return availableAssociatedDataKeys.contains(associatedDataKey) ? Stream.empty() : Stream.of(associatedDataKey);
				}
			})
			.toList();

		if (!missingMandatedAssociatedData.isEmpty()) {
			throw new MandatoryAssociatedDataNotProvidedException(entitySchema.getName(), missingMandatedAssociatedData);
		}
	}

	/**
	 * Method verifies whether none of the mandatory associated data were removed.
	 */
	private void verifyRemovedMandatoryAssociatedData() {
		final AtomicReference<Set<String>> mandatoryAssociatedData = new AtomicReference<>();
		final List<AssociatedDataKey> missingMandatedAssociatedData = ofNullable(this.associatedDataContainers)
			.map(Map::values)
			.orElse(Collections.emptyList())
			.stream()
			.filter(AssociatedDataStoragePart::isDirty)
			.filter(AssociatedDataStoragePart::isEmpty)
			.filter(it -> {
				if (mandatoryAssociatedData.get() == null) {
					final EntitySchema entitySchema = this.schemaAccessor.get();
					mandatoryAssociatedData.set(
						entitySchema.getNonNullableAssociatedData()
							.stream()
							.map(AssociatedDataSchema::getName)
							.collect(Collectors.toSet())
					);
				}
				return mandatoryAssociatedData.get().contains(it.getValue().key().associatedDataName());
			})
			.map(it -> it.getValue().key())
			.toList();

		if (!missingMandatedAssociatedData.isEmpty()) {
			throw new MandatoryAssociatedDataNotProvidedException(this.entityType, missingMandatedAssociatedData);
		}
	}

	/**
	 * Method verifies that all references keep the specified cardinality.
	 */
	private void verifyReferenceCardinalities(
		@Nullable ReferencesStoragePart referencesStorageContainer
	) throws ReferenceCardinalityViolatedException {
		final EntitySchemaContract entitySchema = this.schemaAccessor.get();
		final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
		if (references.isEmpty()) {
			return;
		}
		ObjectIntMap<String> referencesFound = new ObjectIntHashMap<>(references.size());
		ObjectIntMap<ReferenceKey> duplicatedReferenceFound = null;
		if (referencesStorageContainer != null) {
			ReferenceSchemaContract referenceSchema = null;
			final Reference[] cntReferences = referencesStorageContainer.getReferences();
			for (int i = 0; i < cntReferences.length; i++) {
				final Reference reference = cntReferences[i];
				if (reference.exists()) {
					if (referenceSchema == null || !referenceSchema.getName().equals(reference.getReferenceName())) {
						referenceSchema = entitySchema.getReferenceOrThrowException(reference.getReferenceName());
					}
					// skip reflected references that are not available
					if (referenceSchema instanceof final ReflectedReferenceSchemaContract rrsc) {
						if (!rrsc.isReflectedReferenceAvailable()) {
							while (cntReferences.length > i + 1 && cntReferences[i + 1].getReferenceName().equals(
								rrsc.getName())) {
								i++;
							}
							continue;
						}
					}
					referencesFound.putOrAdd(reference.getReferenceName(), 1, 1);
					if (!referenceSchema.getCardinality().allowsDuplicates()) {
						if (duplicatedReferenceFound == null) {
							duplicatedReferenceFound = new ObjectIntHashMap<>();
						}
						duplicatedReferenceFound.putOrAdd(reference.getReferenceKey(), 1, 1);
					}
				}
			}
		}
		List<CardinalityViolation> violations = null; // lazy to minimize allocations
		for (ReferenceSchemaContract examinedSchema : entitySchema.getReferences().values()) {
			final Cardinality cardinality = examinedSchema.getCardinality();
			final String referenceName = examinedSchema.getName();
			if (cardinality.getMin() > 0 && !referencesFound.containsKey(referenceName)) {
				if (violations == null) {
					violations = new LinkedList<>();
				}
				violations.add(
					new CardinalityViolation(referenceName, cardinality, 0, false)
				);
			} else if (cardinality.getMax() <= 0 && referencesFound.get(referenceName) > 1) {
				if (violations == null) {
					violations = new LinkedList<>();
				}
				violations.add(
					new CardinalityViolation(referenceName, cardinality, referencesFound.get(referenceName), false)
				);
			}
		}
		if (duplicatedReferenceFound != null) {
			for (ObjectIntCursor<ReferenceKey> cursor : duplicatedReferenceFound) {
				if (cursor.value > 1) {
					if (violations == null) {
						violations = new LinkedList<>();
					}
					violations.add(
						new CardinalityViolation(
							cursor.key.referenceName(),
							entitySchema.getReferenceOrThrowException(cursor.key.referenceName()).getCardinality(),
							cursor.value,
							true
						)
					);
				}
			}
		}
		if (violations != null && !violations.isEmpty()) {
			throw new ReferenceCardinalityViolatedException(entitySchema.getName(), violations);
		}
	}

	/**
	 * Returns stream of containers that were touched and modified by applying mutations. Existing containers are
	 * automatically fetched from the underlying storage and modified, new containers are created on the fly.
	 */
	private Stream<? extends EntityStoragePart> getChangedEntityStorageParts() {
		// now return all affected containers
		return Stream.of(
				Stream.of(
					this.getEntityStorageContainer(),
					this.pricesContainer,
					this.globalAttributesStorageContainer,
					this.referencesStorageContainer
				),
				ofNullable(this.languageSpecificAttributesContainer).stream().flatMap(it -> it.values().stream()),
				ofNullable(this.associatedDataContainers).stream().flatMap(it -> it.values().stream())
			)
			.flatMap(it -> it)
			.filter(Objects::nonNull)
			/* return only parts that have been changed */
			.filter(EntityStoragePart::isDirty);
	}

	/**
	 * Returns either existing {@link EntityStoragePart} or creates brand new container.
	 */
	@Nonnull
	private EntityBodyStoragePart getEntityStorageContainer() {
		// if entity represents first version we need to forcefully create entity container object
		this.entityContainer = this.entityContainer == null ? new EntityBodyStoragePart(this.entityPrimaryKey) : this.entityContainer;
		return this.entityContainer;
	}

	/**
	 * Method processes attribute related mutations by applying create/update/delete operations in
	 * {@link AttributesStoragePart#getAttributes()}. The appropriate storage part is located by information about
	 * locale in passed `localMutation` argument.
	 */
	private void updateAttributes(@Nonnull EntitySchemaContract entitySchema, @Nonnull AttributeMutation localMutation) {
		final AttributeKey attributeKey = localMutation.getAttributeKey();
		final AttributeSchemaContract attributeDefinition = entitySchema.getAttribute(attributeKey.attributeName())
			.orElseThrow(() -> new EvitaInvalidUsageException("Attribute `" + attributeKey.attributeName() + "` is not known for entity `" + entitySchema.getName() + "`."));
		final AttributesStoragePart attributesStorageContainer = ofNullable(attributeKey.locale())
			// get or create locale specific attributes container
			.map(it -> getAttributeStoragePart(this.entityType, this.entityPrimaryKey, it))
			// get or create locale agnostic container (global one)
			.orElseGet(() -> getAttributeStoragePart(this.entityType, this.entityPrimaryKey));

		// now replace the attribute contents in the container
		attributesStorageContainer.upsertAttribute(
			attributeKey, attributeDefinition, attributeValue -> localMutation.mutateLocal(entitySchema, attributeValue)
		);

		// change in entity parts also change the entity itself (we need to update the version)
		if (attributesStorageContainer.isDirty()) {
			getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
		}

		recomputeLanguageOnAttributeUpdate(localMutation);
	}

	/**
	 * Method processes associated data related mutations by applying create/update/delete operations with particular
	 * {@link AssociatedDataStoragePart} located by the {@link AssociatedDataKey}.
	 */
	private void updateAssociatedData(@Nonnull EntitySchemaContract entitySchema, @Nonnull AssociatedDataMutation localMutation) {
		final AssociatedDataKey associatedDataKey = localMutation.getAssociatedDataKey();
		// get or create associated data container
		final AssociatedDataStoragePart associatedDataStorageContainer = getAssociatedDataStoragePart(this.entityType, this.entityPrimaryKey, associatedDataKey);
		final AssociatedDataValue mutatedValue = localMutation.mutateLocal(entitySchema, associatedDataStorageContainer.getValue());
		// add associated data key to entity set to allow lazy fetching by the key
		final boolean localesChanged;
		if (mutatedValue.exists()) {
			localesChanged = this.entityContainer.addAssociatedDataKey(associatedDataKey);
		} else {
			localesChanged = this.entityContainer.removeAssociatedDataKey(associatedDataKey);
		}
		// now replace the associated data in the container
		associatedDataStorageContainer.replaceAssociatedData(mutatedValue);

		// change in entity parts also change the entity itself (we need to update the version)
		if (associatedDataStorageContainer.isDirty()) {
			getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
		}

		// recompute entity languages (this affect the related index)
		if (localesChanged) {
			if (mutatedValue.exists()) {
				Assert.isPremiseValid(associatedDataKey.locale() != null, "Locale must not be null!");
				registerAddedLocale(associatedDataKey.locale());
			} else {
				Assert.isPremiseValid(associatedDataKey.locale() != null, "Locale must not be null!");
				registerRemovedLocale(associatedDataKey.locale());
			}
		}
	}

	/**
	 * Method processes reference related mutations by applying create/update/delete operations in
	 * {@link ReferencesStoragePart#getReferences()}.
	 */
	private void updateReferences(@Nonnull EntitySchemaContract entitySchema, @Nonnull ReferenceMutation<?> localMutation) {
		// get or create references container
		final ReferencesStoragePart referencesStorageCnt = getReferencesStoragePart(this.entityType, this.entityPrimaryKey);
		// replace or add the mutated reference in the container
		final ReferenceKey referenceKey = localMutation.getReferenceKey();
		final ReferenceContract updatedReference;
		if (referenceKey.isKnownInternalPrimaryKey() || referenceKey.isNewReference()) {
			updatedReference = referencesStorageCnt.replaceOrAddReference(
				referenceKey,
				referenceContract -> localMutation.mutateLocal(entitySchema, referenceContract)
			);
		} else if (
			entitySchema.getReferenceOrThrowException(referenceKey.referenceName())
			            .getCardinality()
			            .allowsDuplicates()
		) {
			throw new InvalidMutationException(
				"Reference `" + referenceKey.referenceName() + "` in entity `" + entitySchema.getName() + "` allows duplicates. " +
					"It's not possible to modify it without providing identification using reference key with internal id!"
			);
		} else {
			updatedReference = referencesStorageCnt.replaceOrAddReference(
				referenceKey, referenceContract -> localMutation.mutateLocal(entitySchema, referenceContract)
			);
		}
		// change in entity parts also change the entity itself (we need to update the version)
		if (referencesStorageCnt.isDirty()) {
			getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
		}
		// recompute languages
		if (localMutation instanceof ReferenceAttributeMutation referenceAttributesUpdateMutation) {
			recomputeLanguageOnAttributeUpdate(referenceAttributesUpdateMutation.getAttributeMutation());
		} else if (localMutation instanceof RemoveReferenceMutation) {
			removeEntireReference(updatedReference);
		}
	}

	/**
	 * Method processes mutation where {@link EntityBodyStoragePart#getParent()} is modified. It replaces
	 * original container preserving all data but changing the hierarchy placement in it.
	 */
	private void updateParent(@Nonnull EntitySchemaContract entitySchema, @Nonnull ParentMutation localMutation) {
		// get entity container
		final EntityBodyStoragePart entityStorageContainer = getEntityStoragePart(this.entityType, this.entityPrimaryKey, this.requiresExisting);
		// update hierarchical placement there
		entityStorageContainer.setParent(
			localMutation.mutateLocal(
					entitySchema,
					ofNullable(entityStorageContainer.getParent())
						.map(OptionalInt::of)
						.orElseGet(OptionalInt::empty)
				)
				.stream().boxed().findAny()
				.orElse(null)
		);
	}

	/**
	 * Method processes price related mutations by applying create/update/delete operations in
	 * {@link PricesStoragePart#getPrices()}.
	 */
	private void updatePriceIndex(@Nonnull EntitySchemaContract entitySchema, @Nonnull PriceMutation localMutation) {
		// get or create prices container
		final PricesStoragePart pricesStorageContainer = getPriceStoragePart(this.entityType, this.entityPrimaryKey);
		// add or replace price in the container
		pricesStorageContainer.replaceOrAddPrice(
			localMutation.getPriceKey(),
			priceContract -> localMutation.mutateLocal(entitySchema, priceContract),
			priceKey -> ofNullable(this.assignedInternalPriceIdIndex)
				.map(it -> it.get(priceKey))
				.orElseGet(this.priceInternalIdSupplier::getAsInt)
		);
		// change in entity parts also change the entity itself (we need to update the version)
		if (pricesStorageContainer.isDirty()) {
			getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
		}
	}

	/**
	 * Method processes mutation where {@link PricesStoragePart#getPriceInnerRecordHandling()} is modified. It replaces
	 * original container preserving all prices but changing the price handling mode in it.
	 */
	private void updatePrices(@Nonnull EntitySchemaContract entitySchema, @Nonnull SetPriceInnerRecordHandlingMutation localMutation) {
		// get or create prices container
		final PricesStoragePart pricesStorageContainer = getPriceStoragePart(this.entityType, this.entityPrimaryKey);
		// update price inner record handling in it - we have to mock the Prices virtual container for this operation
		final PricesContract mutatedPrices = localMutation.mutateLocal(
			entitySchema,
			new Prices(
				entitySchema,
				pricesStorageContainer.getVersion(),
				Collections.emptyList(),
				pricesStorageContainer.getPriceInnerRecordHandling()
			)
		);
		pricesStorageContainer.setPriceInnerRecordHandling(mutatedPrices.getPriceInnerRecordHandling());
		// change in entity parts also change the entity itself (we need to update the version)
		if (pricesStorageContainer.isDirty()) {
			getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
		}
	}

	/**
	 * Updates the scope of the given entity schema based on the provided mutation.
	 *
	 * @param entitySchema           The entity schema contract representing the entity whose scope is to be updated.
	 * @param setEntityScopeMutation The mutation object containing the logic to update the entity's scope.
	 */
	private void updateEntityScope(@Nonnull EntitySchemaContract entitySchema, @Nonnull SetEntityScopeMutation setEntityScopeMutation) {
		final EntityBodyStoragePart entityStorageContainer = getEntityStorageContainer();
		final Scope newScope = setEntityScopeMutation.mutateLocal(entitySchema, entityStorageContainer.getScope());
		entityStorageContainer.setScope(newScope);
	}

	/**
	 * Method processes mutation that removes entire reference - we must then recompute attribute related locales for
	 * the entity. The relation removal might affect the set of locales the entity posses.
	 *
	 * @param updatedReference body of the removed reference
	 */
	private void removeEntireReference(@Nonnull ReferenceContract updatedReference) {
		final ReferencesStoragePart referencesStoragePart = getReferencesStoragePart(this.entityType, this.entityPrimaryKey);
		updatedReference.getAttributeLocales().forEach(locale -> {
			final AttributesStoragePart attributeStoragePart = getAttributeStoragePart(this.entityType, this.entityPrimaryKey, locale);
			if (attributeStoragePart.isEmpty() && !referencesStoragePart.isLocalePresent(locale)) {
				final EntityBodyStoragePart entityStoragePart = getEntityStoragePart(this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST);
				if (entityStoragePart.removeAttributeLocale(locale)) {
					registerRemovedLocale(locale);
				}
			}
		});
	}

	/**
	 * Registers the added locale in the set of added locales.
	 * If the addedLocales set is null, it initializes it as a new HashSet.
	 *
	 * @param locale the locale to be registered as added
	 */
	private void registerAddedLocale(@Nonnull Locale locale) {
		if (this.addedLocales == null) {
			this.addedLocales = new HashSet<>();
		}
		this.addedLocales.add(locale);
	}

	/**
	 * Registers the removed locale in the set of removed locales.
	 * If the removedLocales set is null, it initializes it as a new HashSet.
	 *
	 * @param locale the locale to be registered as removed
	 */
	private void registerRemovedLocale(@Nonnull Locale locale) {
		if (this.removedLocales == null) {
			this.removedLocales = new HashSet<>();
		}
		this.removedLocales.add(locale);
	}

	/**
	 * The CreateMode enum represents the various modes of implicit reference mutations instantiation regarding
	 * the reflected reference schemas.
	 */
	private enum CreateMode {
		/**
		 * When source reference and reflected reference schemas are both indexed in respective scopes, and one
		 * reference is missing, new {@link InsertReferenceMutation} is created.
		 */
		INSERT_MISSING,
		/**
		 * When either source reference or reflected reference schemas is not indexed in respective scopes, and
		 * reflected reference exists, new {@link RemoveReferenceMutation} is created.
		 */
		REMOVE_NON_INDEXED,
		/**
		 * All existing reflected references trigger respective {@link RemoveReferenceMutation} creation.
		 */
		REMOVE_ALL_EXISTING
	}

	/**
	 * Local interface for handling missing attributes.
	 */
	private interface MissingAttributeHandler {

		/**
		 * Accepts the missing attribute information and processes it accordingly.
		 *
		 * @param defaultValue set by attribute schema
		 * @param nullable     true if attribute can be NULL according to attribute schema
		 * @param attributeKey the key of the missing attribute
		 */
		void accept(
			@Nullable Serializable defaultValue,
			boolean nullable,
			@Nonnull AttributeKey attributeKey
		);

	}

	/**
	 * Represents a combination of a primary reference key and its associated representative attribute values.
	 *
	 * This record is used to encapsulate a unique reference to an entity identified by a reference key,
	 * along with a set of serialized representative attribute values that further describe or qualify the entity.
	 *
	 * @param referenceKey A non-null reference key uniquely identifying the entity.
	 * @param representativeAttributeValues A non-null array of serializable values that represent
	 *                                      additional attribute information associated with the entity.
	 */
	private record PrimaryReferenceKeyWithRepresentativeAttributes(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Serializable[] representativeAttributeValues
	) {}

}
