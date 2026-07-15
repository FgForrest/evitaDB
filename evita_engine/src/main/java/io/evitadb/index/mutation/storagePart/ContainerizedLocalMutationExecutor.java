/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor;
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
import io.evitadb.function.IntObjPredicate;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart.EntityAssociatedDataKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart.EntityAttributesSetKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart.LocaleModificationResult;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart.MissingReferenceBehavior;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicInteger;
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
public final class ContainerizedLocalMutationExecutor
	implements ConsistencyCheckingLocalMutationExecutor,
	WritableEntityStorageContainerAccessor, EntityStoragePartAccessor {
	private static final String ERROR_SAME_KEY_EXPECTED = "Expected same primary key here!";
	private static final LocaleWithScope[] EMPTY_LOCALE_WITH_SCOPES = new LocaleWithScope[0];
	/**
	 * Represents the catalog version the storage container accessor is related to.
	 */
	private final long catalogVersion;
	/**
	 * Contains CURRENT storage buffer that traps transactional and intermediate volatile data.
	 */
	@Nonnull private final DataStoreReader dataStoreReader;
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
	private ReferenceKeyManager referenceKeyManager;
	@Nullable private Map<Locale, EnumSet<LocaleScope>> addedLocales;
	@Nullable private Map<Locale, EnumSet<LocaleScope>> removedLocales;
	@Getter private int localesIdentityHash;

	/**
	 * Lazily instantiates and returns the reference key manager. This avoids allocating the manager
	 * when there are no reference-related mutations.
	 *
	 * @return the reference key manager instance
	 */
	@Nonnull
	private ReferenceKeyManager getReferenceKeyManager() {
		if (this.referenceKeyManager == null) {
			this.referenceKeyManager = new ReferenceKeyManager();
		}
		return this.referenceKeyManager;
	}

	/**
	 * Retrieves all attribute keys specified in the passed {@link AttributesStoragePart}.
	 */
	@Nonnull
	private static Set<AttributeKey> collectAttributeKeys(@Nullable AttributesStoragePart storagePart) {
		if (storagePart == null) {
			return Collections.emptySet();
		}
		final AttributeValue[] attributes = storagePart.getAttributes();
		if (attributes.length == 0) {
			return Collections.emptySet();
		}
		// allocation-optimized loop (avoids Optional + stream pipeline on this hot mutation path);
		// pre-sized for the worst case where every attribute exists
		final Set<AttributeKey> result = CollectionUtils.createHashSet(attributes.length);
		for (final AttributeValue attribute : attributes) {
			if (attribute.exists()) {
				result.add(attribute.key());
			}
		}
		return result;
	}

	/**
	 * Processes reference attributes with default values. For every mandatory / default-valued attribute declared by
	 * the reference schema this checks - via an allocation-free membership probe on the reference itself
	 * ({@link Reference#isAttributeValuePresentAndExists(AttributeKey)}) - whether the corresponding value is already
	 * present; if not, the handler is invoked to either register the missing mandatory attribute or emit the
	 * default-value mutation. Probing the (usually few) schema attributes directly avoids materializing the full key
	 * set of the reference (a HashSet per reference) on this hot mutation path.
	 *
	 * @param referenceSchema         The reference schema, expected to be non-null.
	 * @param entityLocales           The set of locales applicable to the entity, expected to be non-null.
	 * @param reference               The reference whose existing attribute values are probed, expected to be non-null.
	 * @param missingAttributeHandler The handler for missing attributes, expected to be non-null.
	 */
	private static void processReferenceAttributesWithDefaultValue(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull Set<Locale> entityLocales,
		@Nonnull Reference reference,
		@Nonnull MissingAttributeHandler missingAttributeHandler
	) {
		for (final AttributeSchemaContract attributeSchema : referenceSchema.getNonNullableOrDefaultValueAttributes().values()) {
			final Serializable defaultValue = attributeSchema.getDefaultValue();
			final boolean nullable = attributeSchema.isNullable();
			if (attributeSchema.isLocalized()) {
				for (final Locale locale : entityLocales) {
					final AttributeKey attributeKey = new AttributeKey(attributeSchema.getName(), locale);
					if (!reference.isAttributeValuePresentAndExists(attributeKey)) {
						missingAttributeHandler.accept(defaultValue, nullable, attributeKey);
					}
				}
			} else {
				final AttributeKey attributeKey = new AttributeKey(attributeSchema.getName());
				if (!reference.isAttributeValuePresentAndExists(attributeKey)) {
					missingAttributeHandler.accept(defaultValue, nullable, attributeKey);
				}
			}
		}
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
				return new UpsertAttributeMutation(
					upsertAttributeMutation.getAttributeKey(),
					new ReferencedEntityPredecessor(predecessor.predecessorPk())
				);
			} else if (attributeValue instanceof ReferencedEntityPredecessor predecessor) {
				return new UpsertAttributeMutation(
					upsertAttributeMutation.getAttributeKey(),
					new Predecessor(predecessor.predecessorPk())
				);
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
		final List<ReducedEntityIndex> indexes = getAllReducedReferencedEntityIndexes(
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
	private static List<ReducedEntityIndex> getAllReducedReferencedEntityIndexes(
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
			scope, referenceName, entityPrimaryKey, allowsDuplicates,
			EntityIndexType.REFERENCED_ENTITY_TYPE,
			EntityIndexType.REFERENCED_ENTITY
		);
	}

	/**
	 * Retrieves all reduced entity indexes matching the given reference and entity primary key, parameterized
	 * by the type index type and reduced index type. This allows the same logic to be reused for both
	 * entity-level and group-level reference indexes.
	 *
	 * @param referencedTypeIndexProvider function that provides the type-level index
	 * @param reducedIndexProvider        function that provides the reduced index by key
	 * @param reducedIndexByPkProvider    function that provides the reduced index by internal PK
	 * @param scope                       the scope in which the indexes are searched
	 * @param referenceName               the name of the reference schema
	 * @param theEntityPrimaryKey         the primary key (entity PK or group PK) to look up
	 * @param allowsDuplicates            whether the reference schema allows duplicate references
	 * @param typeIndexType               the {@link EntityIndexType} for the type-level index
	 * @param reducedIndexType            the {@link EntityIndexType} for the reduced index
	 * @return a list of reduced entity indexes corresponding to the given parameters
	 */
	@Nonnull
	public static <T extends AbstractReducedEntityIndex> List<T> getAllReducedIndexes(
		@Nonnull Function<EntityIndexKey, ReferencedTypeEntityIndex> referencedTypeIndexProvider,
		@Nonnull Function<EntityIndexKey, T> reducedIndexProvider,
		@Nonnull IntFunction<T> reducedIndexByPkProvider,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int theEntityPrimaryKey,
		boolean allowsDuplicates,
		@Nonnull EntityIndexType typeIndexType,
		@Nonnull EntityIndexType reducedIndexType
	) {
		final List<T> indexes;
		if (allowsDuplicates) {
			// we need to collect all indexes that contain primary references to this entity
			// with all combinations of representative attribute values
			final ReferencedTypeEntityIndex typeIndex = referencedTypeIndexProvider.apply(
				new EntityIndexKey(
					typeIndexType,
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
			final T targetIndex = reducedIndexProvider.apply(
				new EntityIndexKey(
					reducedIndexType,
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
		final ReferencesStoragePart otherReferenceStoragePart = dataStoreReader.fetch(
			this.catalogVersion, entityPrimaryKey, ReferencesStoragePart.class
		);
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
	 * @param entityPrimaryKey           The primary key of the entity for which the missing
	 *                                    references need to be generated.
	 * @param mutationCollector          Collector used to store the generated mutations.
	 * @param dataStoreReader            Resolved data reader for the referenced entity type.
	 * @param existingEntityPks          Bitmap of existing entity primary keys used for
	 *                                    iterating and identifying existing entities.
	 * @param referenceSchema            The reference schema used to generate the missing
	 *                                    references.
	 * @param referencedSchemaName       The name of the schema in which the references are
	 *                                    being created.
	 * @param referencedEntityType       The type of the referenced entity that should be
	 *                                    checked and potentially created.
	 * @param reducedIndexesProvider     Function that provides a list of reduced entity
	 *                                    indexes based on the entity primary key.
	 * @param referenceAttributeSupplier Supplier providing an array of reference attribute
	 *                                    mutations to be applied to the missing references.
	 * @param entityPrimaryKeyPredicate  A predicate that determines if the entity primary
	 *                                    key matches the criteria for insertion.
	 * @param implicitMutations          A set of implicit mutation behaviors to be used for
	 *                                    generated external entity mutation.
	 */
	private void generateMissing(
		int entityPrimaryKey,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull PersistentRoaringBitmap existingEntityPks,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull String referencedSchemaName,
		@Nonnull String referencedEntityType,
		@Nonnull IntFunction<List<ReducedEntityIndex>> reducedIndexesProvider,
		@Nonnull Function<ReferenceKey, Stream<ReferenceAttributeMutation>> referenceAttributeSupplier,
		@Nonnull IntObjPredicate<Serializable[]> entityPrimaryKeyPredicate,
		@Nonnull Predicate<RepresentativeReferenceKey> removedRepresentativeReferenceKeyPredicate,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations
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
				final ArrayList<PrimaryReferenceKeyWithRepresentativeAttributes> primaryReferenceKeys =
					new ArrayList<>(reducedEntityIndexes.size());
				for (final ReducedEntityIndex reducedEntityIndex : reducedEntityIndexes) {
					final Serializable[] representativeAttributeValues = reducedEntityIndex
						.getRepresentativeReferenceKey()
						.representativeAttributeValues();
					final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
						new ReferenceKey(referenceSchema.getName(), ownerEntityPrimaryKey),
						representativeAttributeValues
					);
					if (
						entityPrimaryKeyPredicate.test(ownerEntityPrimaryKey, representativeAttributeValues) ||
							removedRepresentativeReferenceKeyPredicate.test(rrk)
					) {
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
					(index, reference) -> {
						final Reference newReference = new Reference(
							primaryReferenceKeys.get(index).referenceKey().internalPrimaryKey(),
							reference
						);
						getReferenceKeyManager().reassignReferenceKey(reference.getReferenceKey(), newReference.getReferenceKey());
						return newReference;
					}
				);
				Assert.isPremiseValid(
					primaryReferenceKeys.size() == replacedReferencesCount,
					"All references must be replaced!"
				);
				// after that we can generate attribute mutations - the reference key is now stable
				for (PrimaryReferenceKeyWithRepresentativeAttributes primaryReferenceKey : primaryReferenceKeys) {
					final ReferenceKey refKey = primaryReferenceKey.referenceKey();
					Assert.isPremiseValid(
						refKey.isKnownInternalPrimaryKey(),
						"Expected known internal primary key here!"
					);
					addMutations(
						localMutations,
						entityPrimaryKey, ownerEntityPrimaryKey,
						getReferenceKeyManager().isReferenceKeyCreated(
							new ReferenceKey(rrs.getName(), ownerEntityPrimaryKey, refKey.internalPrimaryKey())
						),
						refKey,
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
					final ReferenceKey referenceKey = new ReferenceKey(referenceSchema.getName(), ownerEntityPrimaryKey);
					final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(referenceKey, representativeAttributeValues);
					if (
						entityPrimaryKeyPredicate.test(ownerEntityPrimaryKey, representativeAttributeValues) ||
							removedRepresentativeReferenceKeyPredicate.test(rrk)
					) {
						this.referencesStorageContainer.findReference(
							referenceSchema, new ReferenceKey(referenceSchema.getName(), ownerEntityPrimaryKey),
							representativeAttributeValues
						).ifPresent(
							reference -> {
								final ReferenceKey refKey = reference.getReferenceKey();
								Assert.isPremiseValid(
									refKey.isKnownInternalPrimaryKey(),
									"Expected known internal primary key here!"
								);
								addMutations(
									localMutations,
									entityPrimaryKey,
									ownerEntityPrimaryKey,
									getReferenceKeyManager().isReferenceKeyCreated(refKey),
									refKey,
									referencedSchemaName, referenceSchema.getName(), referenceAttributeSupplier
								);
							}
						);
					}
				}
			}
			// finally, create enclosing entity mutation that will create the reference
			if (!localMutations.isEmpty()) {
				mutationCollector.addExternalMutation(
					new ServerEntityUpsertMutation(
						referencedEntityType, ownerEntityPrimaryKey, EntityExistence.MUST_EXIST,
						implicitMutations,
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
		boolean createReference,
		@Nonnull ReferenceKey primaryReferenceKey,
		@Nonnull String referencedSchemaName,
		@Nonnull String referenceSchemaName,
		@Nonnull Function<ReferenceKey, Stream<ReferenceAttributeMutation>> referenceAttributeSupplier
	) {
		if (createReference) {
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
		}
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
	 * @param entityPrimaryKeyPredicate A predicate that determines if the entity primary
	 *                                 key matches the criteria for removal.
	 */
	private void removeExistingEntityPks(
		int entityPrimaryKey,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull PersistentRoaringBitmap existingEntityPks,
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
		this.catalogVersion = catalogVersion;
		this.dataStoreReader = dataStoreReader;
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

	/**
	 * Retrieves the body storage part of the entity by its type and primary key. This method either
	 * returns a cached entity storage part or fetches it from the data store if it's not cached.
	 * Additionally, it validates the existence of the entity based on the expectation provided.
	 *
	 * When the requested `entityType` differs from the entity type being mutated by this executor,
	 * the method delegates to the cross-entity data store reader without caching. This enables
	 * expression evaluation proxies to transparently read storage parts from referenced or group
	 * entity collections.
	 *
	 * @param entityType the type of the entity being retrieved
	 * @param entityPrimaryKey the primary key of the entity to retrieve
	 * @param expects the expected existence state of the entity, which could be MUST_EXIST or MUST_NOT_EXIST
	 * @return the {@link EntityBodyStoragePart} for the given entity type and primary key
	 * @throws InvalidMutationException if the actual existence state of the entity doesn't match the expectation
	 */
	@Nonnull
	public EntityBodyStoragePart getEntityStoragePart(
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nonnull EntityExistence expects
	) {
		if (this.entityType.equals(entityType) && this.entityPrimaryKey == entityPrimaryKey) {
			// same entity — use cached approach
			return ofNullable(getCachedEntityStorageContainer(entityPrimaryKey))
				.orElseGet(() -> cacheEntityStorageContainer(
					entityPrimaryKey,
					ofNullable(this.dataStoreReader.fetch(this.catalogVersion, entityPrimaryKey, EntityBodyStoragePart.class))
						.map(it -> {
							if (expects == EntityExistence.MUST_NOT_EXIST && !it.isMarkedForRemoval()) {
								throw new InvalidMutationException(
									"There is already entity " + entityType + " with primary key "
										+ entityPrimaryKey + " present! Please fetch this entity and perform"
										+ " update operation on top of it."
								);
							} else if (expects == EntityExistence.MUST_EXIST && it.isMarkedForRemoval()) {
								throw new InvalidMutationException(
									"There is no entity " + entityType + " with primary key "
										+ entityPrimaryKey + " present! This means, that you're probably"
										+ " trying to update entity that has been already removed!"
								);
							}
							return it;
						})
						.orElseGet(() -> {
							if (expects == EntityExistence.MUST_EXIST) {
								throw new InvalidMutationException(
									"There is no entity " + entityType + " with primary key "
										+ entityPrimaryKey + " present! This means, that you're probably"
										+ " trying to update entity that has been already removed!"
								);
							} else {
								return new EntityBodyStoragePart(entityPrimaryKey);
							}
						})
				));
		} else {
			// different entity (cross-entity or same-type parent) — non-cached read
			final DataStoreReader reader = this.entityType.equals(entityType)
				? this.dataStoreReader
				: getCrossEntityDataStoreReader(entityType);
			return ofNullable(reader.fetch(this.catalogVersion, entityPrimaryKey, EntityBodyStoragePart.class))
				.orElseGet(() -> new EntityBodyStoragePart(entityPrimaryKey));
		}
	}

	/**
	 * Resolves the {@link DataStoreReader} for a different entity type using the cross-entity reader accessor.
	 * Used for cross-entity expression evaluation where storage parts (attributes, references, etc.) of
	 * a referenced or group entity need to be read from their own collection.
	 *
	 * The returned reader is the target collection's data store buffer, which provides access to both
	 * persistent storage and uncommitted changes within the current session.
	 *
	 * @param entityType the entity type to resolve the reader for
	 * @return a non-null data store reader for the specified entity type
	 * @throws GenericEvitaInternalError if no collection exists for the specified entity type
	 */
	@Nonnull
	private DataStoreReader getCrossEntityDataStoreReader(@Nonnull String entityType) {
		final DataStoreReader reader = this.dataStoreReaderAccessor.apply(entityType);
		if (reader == null) {
			throw new GenericEvitaInternalError(
				"Data store reader for entity type `" + entityType + "` not found. " +
					"This is required for cross-entity expression evaluation."
			);
		}
		return reader;
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (this.entityType.equals(entityType) && this.entityPrimaryKey == entityPrimaryKey) {
			// same entity — use cached approach
			return ofNullable(getCachedAttributeStorageContainer(entityPrimaryKey))
				.orElseGet(() -> {
					final EntityAttributesSetKey key = new EntityAttributesSetKey(entityPrimaryKey, null);
					return cacheAttributeStorageContainer(
						entityPrimaryKey,
						ofNullable(this.dataStoreReader.fetch(
							this.catalogVersion, key,
							AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId
						)).orElseGet(() -> new AttributesStoragePart(entityPrimaryKey))
					);
				});
		} else {
			// different entity (cross-entity or same-type parent) — non-cached read
			final DataStoreReader reader = this.entityType.equals(entityType)
				? this.dataStoreReader
				: getCrossEntityDataStoreReader(entityType);
			final EntityAttributesSetKey key = new EntityAttributesSetKey(entityPrimaryKey, null);
			return ofNullable(reader.fetch(
				this.catalogVersion, key, AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId
			)).orElseGet(() -> new AttributesStoragePart(entityPrimaryKey));
		}
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(
		@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale
	) {
		if (this.entityType.equals(entityType) && this.entityPrimaryKey == entityPrimaryKey) {
			// same entity — use cached approach
			final Map<Locale, AttributesStoragePart> attributesContainer =
				getOrCreateCachedLocalizedAttributesStorageContainer(entityPrimaryKey);
			return attributesContainer.computeIfAbsent(
				locale,
				language -> {
					final EntityAttributesSetKey key =
						new EntityAttributesSetKey(entityPrimaryKey, language);
					return ofNullable(this.dataStoreReader.fetch(
						this.catalogVersion, key,
						AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId
					)).orElseGet(() -> new AttributesStoragePart(entityPrimaryKey, locale));
				}
			);
		} else {
			// different entity (cross-entity or same-type parent) — non-cached read
			final DataStoreReader reader = this.entityType.equals(entityType)
				? this.dataStoreReader
				: getCrossEntityDataStoreReader(entityType);
			final EntityAttributesSetKey key = new EntityAttributesSetKey(entityPrimaryKey, locale);
			return ofNullable(reader.fetch(
				this.catalogVersion, key, AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId
			)).orElseGet(() -> new AttributesStoragePart(entityPrimaryKey, locale));
		}
	}

	/**
	 * Reads an attribute value from a referenced entity's storage part. When `locale` is non-null, reads from
	 * the locale-specific attribute storage part; otherwise reads from the global (locale-agnostic) part.
	 * Used by local histogram triggers when the value source is
	 * {@link io.evitadb.core.expression.trigger.HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE}.
	 *
	 * @param entityType    the type of the referenced entity
	 * @param entityPK      the primary key of the referenced entity
	 * @param attributeName the name of the attribute to read
	 * @param locale        the locale for localized attributes, or `null` for non-localized
	 * @param scope         the expected scope — returns `null` when the referenced entity is in a different scope
	 * @return the attribute value, or null if the attribute doesn't exist, is dropped, or scope mismatch
	 */
	@Nullable
	public Serializable readReferencedEntityAttribute(
		@Nonnull String entityType,
		int entityPK,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull Scope scope
	) {
		// scope check: referenced entity must be in the same scope
		final EntityBodyStoragePart bodyPart = getEntityStoragePart(entityType, entityPK, EntityExistence.MUST_EXIST);
		if (bodyPart.getScope() != scope) {
			return null;
		}
		final AttributesStoragePart attributesPart;
		if (locale != null) {
			attributesPart = getAttributeStoragePart(entityType, entityPK, locale);
		} else {
			attributesPart = getAttributeStoragePart(entityType, entityPK);
		}
		final AttributeKey key = locale != null
			? new AttributeKey(attributeName, locale)
			: new AttributeKey(attributeName);
		final AttributeValue attributeValue = attributesPart.findAttribute(key);
		if (attributeValue == null || attributeValue.value() == null || attributeValue.dropped()) {
			return null;
		}
		return NumberUtils.normalizeIfBigDecimal(attributeValue.value());
	}

	/**
	 * Returns the set of attribute locales for a referenced entity. Used by localized histogram triggers to
	 * discover all locales for which the referenced entity has localized attribute values.
	 *
	 * @param entityType the type of the referenced entity
	 * @param entityPK   the primary key of the referenced entity
	 * @return set of locales for which the entity has localized attribute values
	 */
	@Nonnull
	public Set<Locale> getReferencedEntityAttributeLocales(
		@Nonnull String entityType,
		int entityPK
	) {
		final EntityBodyStoragePart entityBody = getEntityStoragePart(entityType, entityPK, EntityExistence.MUST_EXIST);
		return entityBody.getAttributeLocales();
	}

	@Nonnull
	@Override
	public AssociatedDataStoragePart getAssociatedDataStoragePart(
		@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key
	) {
		if (this.entityType.equals(entityType) && this.entityPrimaryKey == entityPrimaryKey) {
			// same entity — use cached approach
			final Map<AssociatedDataKey, AssociatedDataStoragePart> associatedDataContainer =
				getOrCreateCachedAssociatedDataStorageContainer(entityPrimaryKey);
			return associatedDataContainer.computeIfAbsent(
				key,
				associatedDataKey -> {
					final EntityAssociatedDataKey entityKey = new EntityAssociatedDataKey(
						entityPrimaryKey, key.associatedDataName(), key.locale()
					);
					return ofNullable(this.dataStoreReader.fetch(
						this.catalogVersion, entityKey,
						AssociatedDataStoragePart.class,
						AssociatedDataStoragePart::computeUniquePartId
					)).orElseGet(
						() -> new AssociatedDataStoragePart(entityPrimaryKey, associatedDataKey)
					);
				}
			);
		} else {
			// different entity (cross-entity or same-type parent) — non-cached read
			final DataStoreReader reader = this.entityType.equals(entityType)
				? this.dataStoreReader
				: getCrossEntityDataStoreReader(entityType);
			final EntityAssociatedDataKey entityKey = new EntityAssociatedDataKey(
				entityPrimaryKey, key.associatedDataName(), key.locale()
			);
			return ofNullable(reader.fetch(
				this.catalogVersion, entityKey,
				AssociatedDataStoragePart.class, AssociatedDataStoragePart::computeUniquePartId
			)).orElseGet(() -> new AssociatedDataStoragePart(entityPrimaryKey, key));
		}
	}

	@Nonnull
	@Override
	public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (this.entityType.equals(entityType) && this.entityPrimaryKey == entityPrimaryKey) {
			// same entity — use cached approach
			return ofNullable(getCachedReferenceStorageContainer(entityPrimaryKey))
				.orElseGet(() -> cacheReferencesStorageContainer(
					entityPrimaryKey,
					ofNullable(this.dataStoreReader.fetch(
						this.catalogVersion, entityPrimaryKey, ReferencesStoragePart.class
					)).orElseGet(() -> new ReferencesStoragePart(entityPrimaryKey))
				));
		} else {
			// different entity (cross-entity or same-type parent) — non-cached read
			final DataStoreReader reader = this.entityType.equals(entityType)
				? this.dataStoreReader
				: getCrossEntityDataStoreReader(entityType);
			return ofNullable(reader.fetch(
				this.catalogVersion, entityPrimaryKey, ReferencesStoragePart.class
			)).orElseGet(() -> new ReferencesStoragePart(entityPrimaryKey));
		}
	}

	@Nonnull
	@Override
	public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (this.entityType.equals(entityType) && this.entityPrimaryKey == entityPrimaryKey) {
			// same entity — use cached approach
			return ofNullable(getCachedPricesStorageContainer(entityPrimaryKey))
				.orElseGet(() -> cachePricesStorageContainer(
					entityPrimaryKey,
					ofNullable(this.dataStoreReader.fetch(
						this.catalogVersion, entityPrimaryKey, PricesStoragePart.class
					)).orElseGet(() -> new PricesStoragePart(entityPrimaryKey))
				));
		} else {
			// different entity (cross-entity or same-type parent) — non-cached read
			final DataStoreReader reader = this.entityType.equals(entityType)
				? this.dataStoreReader
				: getCrossEntityDataStoreReader(entityType);
			return ofNullable(reader.fetch(
				this.catalogVersion, entityPrimaryKey, PricesStoragePart.class
			)).orElseGet(() -> new PricesStoragePart(entityPrimaryKey));
		}
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
	@Override
	public void finishLocalMutationExecutionPhase() {
		if (this.referencesStorageContainer != null) {
			getReferenceKeyManager().processAssignedPrimaryKeys(this.referencesStorageContainer.assignMissingIdsAndSort());
		}
		// when scope changes
		if (this.initialEntityScope != this.entityContainer.getScope()) {
			// all references are considered as new for the sake of reflected references propagation
			final ReferencesStoragePart rsp = getReferencesStoragePart(this.entityType, this.entityPrimaryKey);
			getReferenceKeyManager().markAllReferencesAsCreated(rsp.getReferences());
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
		for (final EntityStoragePart part : getChangedEntityStorageParts()) {
			if (part.isEmpty()) {
				remover.accept(this.catalogVersion, part);
			} else {
				Assert.isPremiseValid(
					!this.entityRemovedEntirely,
					"Only removal operations are expected to happen!"
				);
				updater.accept(this.catalogVersion, part);
			}
		}
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
					this.referencesStorageContainer, missingMandatedAttributes, mutationCollector,
					EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)
				);
			}
			if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)) {
				verifyReferenceAttributes(
					targetEntityScope, this.entityContainer, this.referencesStorageContainer,
					inputMutations, missingMandatedAttributes, mutationCollector,
					implicitMutationBehavior
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
						mutationCollector,
						EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)
					);
				}
			} else {
				// check mandatory attributes
				final List<Object> missingMandatedAttributes = new LinkedList<>();
				// we need to check only changed parts
				if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_ATTRIBUTES)) {
					// null-safe dirty checks (avoid Optional + a per-entity stream().anyMatch)
					final boolean globalAttributesDirty = this.globalAttributesStorageContainer != null
						&& this.globalAttributesStorageContainer.isDirty();
					boolean localizedAttributesDirty = false;
					if (this.languageSpecificAttributesContainer != null) {
						for (final AttributesStoragePart part : this.languageSpecificAttributesContainer.values()) {
							if (part.isDirty()) {
								localizedAttributesDirty = true;
								break;
							}
						}
					}
					verifyMandatoryAttributes(
						this.entityContainer,
						missingMandatedAttributes,
						globalAttributesDirty,
						localizedAttributesDirty,
						mutationCollector
					);
				}

				if (this.initialEntityScope != targetEntityScope) {
					// we need to drop all reflected references in old scope
					final ReferencesStoragePart theReferenceStorageContainer =
						getCachedReferenceStorageContainer(this.entityPrimaryKey);
					if (theReferenceStorageContainer != null) {
						propagateReferencesToEntangledEntities(
							this.entityPrimaryKey,
							this.initialEntityScope,
							targetEntityScope,
							this.catalogSchemaAccessor.get(),
							this.schemaAccessor.get(),
							theReferenceStorageContainer,
							CreateMode.REMOVE_NON_INDEXED,
							mutationCollector,
							EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)
						);
					}
					// and insert new reflected references in new scope
					insertReflectedReferences(
						this.entityPrimaryKey, this.initialEntityScope, targetEntityScope,
						this.referencesStorageContainer, List.of(), mutationCollector,
						EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)
					);
				} else if (this.referencesStorageContainer != null && this.referencesStorageContainer.isDirty()) {
					if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFLECTED_REFERENCES)) {
						verifyReflectedReferences(
							this.entityPrimaryKey, this.initialEntityScope, targetEntityScope,
							inputMutations, mutationCollector,
							EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)
						);
					}
					if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)) {
						verifyReferenceAttributes(
							targetEntityScope,
							this.entityContainer,
							this.referencesStorageContainer,
							inputMutations,
							missingMandatedAttributes,
							mutationCollector,
							implicitMutationBehavior
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
	public OptionalInt findExistingInternalId(
		@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey
	) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		Integer internalPriceId = this.assignedInternalPriceIdIndex == null
			? null : this.assignedInternalPriceIdIndex.get(priceKey);
		if (internalPriceId == null) {
			final PricesStoragePart priceStorageContainer = getPriceStoragePart(entityType, entityPrimaryKey);
			return priceStorageContainer.findExistingInternalIds(priceKey);
		} else {
			return OptionalInt.of(internalPriceId);
		}
	}

	@Nonnull
	public LocaleWithScope[] getAddedLocales() {
		if (this.addedLocales == null) {
			return EMPTY_LOCALE_WITH_SCOPES;
		} else {
			final LocaleWithScope[] localeWithScopes = new LocaleWithScope[this.addedLocales.size()];
			int i = 0;
			for (Entry<Locale, EnumSet<LocaleScope>> entry : this.addedLocales.entrySet()) {
				localeWithScopes[i++] = new LocaleWithScope(entry.getKey(), entry.getValue());
			}
			return localeWithScopes;
		}
	}

	@Nonnull
	public LocaleWithScope[] getRemovedLocales() {
		if (this.removedLocales == null) {
			return EMPTY_LOCALE_WITH_SCOPES;
		} else {
			final LocaleWithScope[] localeWithScopes = new LocaleWithScope[this.removedLocales.size()];
			int i = 0;
			for (Entry<Locale, EnumSet<LocaleScope>> entry : this.removedLocales.entrySet()) {
				localeWithScopes[i++] = new LocaleWithScope(entry.getKey(), entry.getValue());
			}
			return localeWithScopes;
		}
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
		// allocation-optimized assembly (avoids the nested Stream.of/flatMap pipeline)
		final List<EntityStoragePart> parts = new ArrayList<>(8);
		addIfNonNull(parts, this.entityContainer);
		addIfNonNull(parts, this.globalAttributesStorageContainer);
		if (this.languageSpecificAttributesContainer != null) {
			for (final AttributesStoragePart part : this.languageSpecificAttributesContainer.values()) {
				addIfNonNull(parts, part);
			}
		}
		addIfNonNull(parts, this.pricesContainer);
		addIfNonNull(parts, this.referencesStorageContainer);
		if (this.associatedDataContainers != null) {
			for (final AssociatedDataStoragePart part : this.associatedDataContainers.values()) {
				addIfNonNull(parts, part);
			}
		}
		return parts.toArray(EntityStoragePart[]::new);
	}

	/**
	 * Adds the given storage part to the target list when it is not {@code null}.
	 *
	 * @param target the list to add the part to
	 * @param part   the storage part to add, may be {@code null}
	 */
	private static void addIfNonNull(@Nonnull List<EntityStoragePart> target, @Nullable EntityStoragePart part) {
		if (part != null) {
			target.add(part);
		}
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
		// allocation-optimized assembly (avoids the nested Stream.of/flatMap pipeline); each branch
		// resolves to a non-null part (cached value or freshly fetched/created), so no null filtering
		final List<EntityStoragePart> parts = new ArrayList<>(16);
		parts.add(entityStorageContainer);
		parts.add(
			this.globalAttributesStorageContainer != null
				? this.globalAttributesStorageContainer
				: getAttributeStoragePart(this.entityType, this.entityPrimaryKey)
		);
		for (final Locale locale : entityStorageContainer.getAttributeLocales()) {
			final AttributesStoragePart cached = this.languageSpecificAttributesContainer == null
				? null : this.languageSpecificAttributesContainer.get(locale);
			parts.add(cached != null ? cached : getAttributeStoragePart(this.entityType, this.entityPrimaryKey, locale));
		}
		if (getEntitySchema().isWithPrice()) {
			parts.add(
				this.pricesContainer != null
					? this.pricesContainer
					: getPriceStoragePart(this.entityType, this.entityPrimaryKey)
			);
		}
		parts.add(
			this.referencesStorageContainer != null
				? this.referencesStorageContainer
				: getReferencesStoragePart(this.entityType, this.entityPrimaryKey)
		);
		for (final AssociatedDataKey associatedDataKey : entityStorageContainer.getAssociatedDataKeys()) {
			final AssociatedDataStoragePart cached = this.associatedDataContainers == null
				? null : this.associatedDataContainers.get(associatedDataKey);
			parts.add(
				cached != null
					? cached
					: getAssociatedDataStoragePart(this.entityType, this.entityPrimaryKey, associatedDataKey)
			);
		}
		return parts.toArray(EntityStoragePart[]::new);
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
		return this.referenceKeyManager == null ?
			Collections.emptyMap() : this.referenceKeyManager.getAssignedPrimaryKeys();
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

	/**
	 * Retrieves the cached entity storage container associated with the specified entity primary key.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the storage container is being retrieved.
	 *                         Must match the primary key of the current instance.
	 * @return the cached entity storage container if it exists, or null if no container is found.
	 */
	@Nullable
	private EntityBodyStoragePart getCachedEntityStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.entityContainer;
	}

	/**
	 * Caches the provided entity storage container for the specified entity primary key.
	 * The method validates that the given primary key matches the expected key
	 * before caching the provided entity storage part.
	 *
	 * @param entityPrimaryKey the primary key of the entity that must match the expected key
	 * @param entityStorageContainer the storage container object to be cached
	 * @return the cached entity storage container
	 */
	@Nonnull
	private EntityBodyStoragePart cacheEntityStorageContainer(
		int entityPrimaryKey, @Nonnull EntityBodyStoragePart entityStorageContainer
	) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		this.entityContainer = entityStorageContainer;
		this.initialEntityScope = entityStorageContainer.getScope();
		return this.entityContainer;
	}

	/**
	 * Retrieves the cached attribute storage container for the specified entity primary key.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the cached attribute storage container is requested
	 * @return the cached attribute storage container, or null if it is not available
	 */
	@Nullable
	private AttributesStoragePart getCachedAttributeStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.globalAttributesStorageContainer;
	}

	/**
	 * Caches the provided AttributesStoragePart for the specified entity primary key and updates
	 * the global attributes storage container.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the attributes
	 *                         storage container is being cached; must match the current
	 *                         entity primary key
	 * @param attributesStorageContainer the AttributesStoragePart to be cached; must not be null
	 * @return the updated global attributes storage container
	 */
	@Nonnull
	private AttributesStoragePart cacheAttributeStorageContainer(
		int entityPrimaryKey,
		@Nonnull AttributesStoragePart attributesStorageContainer
	) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		this.globalAttributesStorageContainer = attributesStorageContainer;
		return this.globalAttributesStorageContainer;
	}

	/**
	 * Retrieves the cached container for storing localized attributes mapped by their respective locales,
	 * or creates it if it does not already exist. Ensures that the operation is performed for the correct entity
	 * identified by the provided primary key.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the localized
	 *                         attributes storage container is required.
	 *                         It must match the primary key of the current object, otherwise, an assertion will fail.
	 * @return a map where the key is the locale and the value is the corresponding {@link AttributesStoragePart}
	 *         for storing the localized attributes.
	 */
	@Nonnull
	private Map<Locale, AttributesStoragePart> getOrCreateCachedLocalizedAttributesStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return ofNullable(this.languageSpecificAttributesContainer)
			.orElseGet(() -> {
				// when not available lazily instantiate it
				this.languageSpecificAttributesContainer =
					CollectionUtils.createHashMap(this.getEntitySchema().getLocales().size());
				return this.languageSpecificAttributesContainer;
			});
	}

	/**
	 * Retrieves the cached container mapping associated data keys to their corresponding storage parts
	 * for the specified entity primary key. If the container does not already exist, it will be created
	 * and cached internally.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the associated data storage container
	 *                         is being retrieved or created; must match the expected entity primary key
	 * @return a map linking {@link AssociatedDataKey} to {@link AssociatedDataStoragePart}, representing the
	 *         cached storage container for associated data
	 * @throws IllegalArgumentException if the provided entity primary key does not match the expected value
	 */
	@Nonnull
	private Map<AssociatedDataKey, AssociatedDataStoragePart>
		getOrCreateCachedAssociatedDataStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return ofNullable(this.associatedDataContainers)
			.orElseGet(() -> {
				// when not available lazily instantiate it
				this.associatedDataContainers = new LinkedHashMap<>(this.getEntitySchema().getAssociatedData().size());
				return this.associatedDataContainers;
			});
	}

	/**
	 * Retrieves the cached reference storage container for the specified entity primary key.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the cached reference storage container is retrieved
	 * @return the cached ReferencesStoragePart instance if available; otherwise, null
	 */
	@Nullable
	private ReferencesStoragePart getCachedReferenceStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.referencesStorageContainer;
	}

	/**
	 * Caches the provided ReferencesStoragePart container for the given entity primary key.
	 * If the entity has been marked as removed entirely, this method ensures that the initial
	 * state of the references storage container is preserved by creating a copy of the current state.
	 *
	 * @param entityPrimaryKey the primary key of the entity, which must match the expected primary key.
	 * @param referencesStorageContainer the ReferencesStoragePart to be cached for the entity.
	 * @return the cached ReferencesStoragePart container.
	 * @throws IllegalArgumentException if the provided entityPrimaryKey does not match
	 *         the expected entityPrimaryKey.
	 */
	@Nonnull
	private ReferencesStoragePart cacheReferencesStorageContainer(
		int entityPrimaryKey,
		@Nonnull ReferencesStoragePart referencesStorageContainer
	) {
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

	/**
	 * Retrieves the cached prices storage container for the specified entity primary key.
	 *
	 * @param entityPrimaryKey the primary key of the entity for which the prices storage container is being accessed
	 * @return the cached {@code PricesStoragePart} corresponding to the specified entity primary key,
	 *         or {@code null} if none exists
	 */
	@Nullable
	private PricesStoragePart getCachedPricesStorageContainer(int entityPrimaryKey) {
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		return this.pricesContainer;
	}

	/**
	 * Caches the given PricesStoragePart associated with the provided entity primary key.
	 *
	 * @param entityPrimaryKey the primary key of the entity, must match the existing primary key of this object
	 * @param pricesStorageContainer the PricesStoragePart to be cached, must not be null
	 * @return the cached PricesStoragePart
	 */
	@Nonnull
	private PricesStoragePart cachePricesStorageContainer(
		int entityPrimaryKey, @Nonnull PricesStoragePart pricesStorageContainer
	) {
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
			final Locale locale = affectedAttribute.locale();
			if (locale != null) {
				final EntityBodyStoragePart ebsp = getEntityStoragePart(
					this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST
				);
				final LocaleModificationResult localeModificationResult = ebsp.addAttributeLocale(locale);
				if (localeModificationResult.anyChangeOccurred()) {
					this.localesIdentityHash++;
					final EnumSet<LocaleScope> scopesAffected = EnumSet.noneOf(LocaleScope.class);
					if (localeModificationResult.attributeLocalesChanged()) {
						scopesAffected.add(LocaleScope.ATTRIBUTE);
					}
					if (localeModificationResult.entityLocalesChanged()) {
						scopesAffected.add(LocaleScope.ENTITY);
					}
					registerAddedLocale(locale, scopesAffected);
				}
			}
		} else if (attributeMutation instanceof RemoveAttributeMutation) {
			final Locale locale = affectedAttribute.locale();
			if (locale != null) {
				final AttributesStoragePart attributeStoragePart = getAttributeStoragePart(
					this.entityType, this.entityPrimaryKey, locale
				);
				final ReferencesStoragePart referencesStoragePart =
					getReferencesStoragePart(this.entityType, this.entityPrimaryKey);

				if (attributeStoragePart.isEmpty() && !referencesStoragePart.isLocalePresent(locale)) {
					final EntityBodyStoragePart ebsp = getEntityStoragePart(
						this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST
					);
					final LocaleModificationResult localeModificationResult = ebsp.removeAttributeLocale(locale);
					if (localeModificationResult.anyChangeOccurred()) {
						this.localesIdentityHash++;
						final EnumSet<LocaleScope> scopesAffected = EnumSet.noneOf(LocaleScope.class);
						if (localeModificationResult.attributeLocalesChanged()) {
							scopesAffected.add(LocaleScope.ATTRIBUTE);
						}
						if (localeModificationResult.entityLocalesChanged()) {
							scopesAffected.add(LocaleScope.ENTITY);
						}
						registerRemovedLocale(locale, scopesAffected);
					}
				}
			}
		} else if (attributeMutation instanceof ApplyDeltaAttributeMutation) {
			// DO NOTHING
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + attributeMutation.getClass());
		}
	}

	/**
	 * Verifies the presence of mandatory attributes in an entity and applies default values if missing and allowed.
	 * If mandatory attributes are missing and default values cannot be applied, they are added
	 * to the list of missing attributes.
	 *
	 * @param entityStorageContainer the container holding the entity's attribute storage and metadata
	 * @param missingMandatedAttributes a list to which any missing mandatory attributes will be added
	 * @param checkGlobal if true, global attributes will be checked for mandatory requirements
	 * @param checkLocalized if true, localized attributes will be checked for mandatory requirements
	 * @param mutationCollector a collector used to gather attribute mutation operations when default values are applied
	 * @throws MandatoryAttributesNotProvidedException if mandatory attributes are missing and cannot be resolved
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
		final Map<Locale, Set<AttributeKey>> availableLocalizedAttributes;
		if (checkLocalized && !entityLocales.isEmpty()) {
			availableLocalizedAttributes = CollectionUtils.createHashMap(entityLocales.size());
			for (final Locale locale : entityLocales) {
				availableLocalizedAttributes.put(
					locale,
					collectAttributeKeys(getAttributeStoragePart(this.entityType, this.entityPrimaryKey, locale))
				);
			}
		} else {
			availableLocalizedAttributes = Collections.emptyMap();
		}

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

		for (final EntityAttributeSchemaContract attribute : nonNullableOrDefaultValueAttributes) {
			final Serializable defaultValue = attribute.getDefaultValue();
			final boolean nullable = attribute.isNullable();
			if (checkLocalized && attribute.isLocalized()) {
				for (final Locale locale : entityLocales) {
					final AttributeKey attributeKey = new AttributeKey(attribute.getName(), locale);
					final Set<AttributeKey> localeAttributes = availableLocalizedAttributes.get(locale);
					if (localeAttributes == null || !localeAttributes.contains(attributeKey)) {
						missingAttributeHandler.accept(defaultValue, nullable, attributeKey);
					}
				}
			} else if (checkGlobal && !attribute.isLocalized()) {
				final AttributeKey attributeKey = new AttributeKey(attribute.getName());
				if (!availableGlobalAttributes.contains(attributeKey)) {
					missingAttributeHandler.accept(defaultValue, nullable, attributeKey);
				}
			}
		}

		// verify that entity retains at least one locale if non-nullable localized attributes exist
		if (checkLocalized && entityLocales.isEmpty()) {
			final List<String> nonNullableLocalizedAttributes = nonNullableOrDefaultValueAttributes.stream()
				.filter(attr -> attr.isLocalized() && !attr.isNullable() && attr.getDefaultValue() == null)
				.map(EntityAttributeSchemaContract::getName)
				.sorted()
				.toList();
			if (!nonNullableLocalizedAttributes.isEmpty()) {
				throw new MandatoryAttributesNotProvidedException(
					"Entity `" + entitySchema.getName() + "` requires at least one locale because it has " +
						"non-nullable localized attributes: " +
						nonNullableLocalizedAttributes.stream()
							.map(name -> "`" + name + "`")
							.collect(Collectors.joining(", ")) + "."
				);
			}
		}
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
	 * @param entityPrimaryKey           the primary key of the entity for which the reflected
	 *                                    references need to be generated
	 * @param sourceEntityScope          the source scope of the entity, must not be null
	 * @param targetEntityScope          the scope of the target entity, must not be null
	 * @param referencesStorageContainer The container for the references storage part
	 *                                   Can be null if there are no references to insert.
	 * @param missingMandatedAttributes  The list of missing mandated attributes.
	 * @param mutationCollector          The collector for mutations.
	 *                                   Cannot be null.
	 * @param implicitMutations          A set of implicit mutation behaviors to be used for
	 *                                    generated external entity mutation.
	 */
	private void insertReflectedReferences(
		int entityPrimaryKey,
		@Nonnull Scope sourceEntityScope,
		@Nonnull Scope targetEntityScope,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations
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
				mutationCollector,
				implicitMutations
			);
		}
	}

	/**
	 * Updates all entities that are entangled with the provided {@link CatalogSchema} and {@link EntitySchema}.
	 *
	 * @param entityPrimaryKey           the primary key of the entity for which the reflected
	 *                                    references need to be generated
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
	 * @param catalogSchema             The schema of the catalog to which the entity belongs,
	 *                                   providing context for the reference.
	 * @param entitySchema              The schema of the entity, defining attributes and structure for the given entity.
	 * @param missingMandatedAttributes A list to collect attributes that are identified as
	 *                                  missing based on schema constraints.
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
	 * This method checks each mutation in the input list and determines if it is an
	 * InsertReferenceMutation or a RemoveReferenceMutation.
	 * For InsertReferenceMutation, it propagates the reference modification by collecting all similar mutations and
	 * creating a new mutation object. For RemoveReferenceMutation, it follows the same process.
	 *
	 * @param entityPrimaryKey  The primary key of the entity to verify the reflected references.
	 * @param sourceEntityScope the source scope of the entity, must not be null
	 * @param targetEntityScope the scope of the target entity, must not be null
	 * @param inputMutations    A list of input mutations to verify the reflected references.
	 * @param mutationCollector The mutation collector used to collect the verified reflected references.
	 * @param implicitMutations A set of implicit mutation behaviors to be used for generated external entity mutation.
	 */
	private void verifyReflectedReferences(
		int entityPrimaryKey,
		@Nonnull Scope sourceEntityScope,
		@Nonnull Scope targetEntityScope,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations
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
								inputMutations,
								this::translateToAssignedReferenceKey
							)
						),
						mutationCollector,
						CreateMode.INSERT_MISSING,
						implicitMutations
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
								inputMutations,
								this::translateToAssignedReferenceKey
							)
						),
						mutationCollector,
						CreateMode.REMOVE_ALL_EXISTING,
						implicitMutations
					);
				}
			}
		}
	}

	/**
	 * Translates the provided comparable reference key into its assigned reference key.
	 * If no assigned reference key exists for the provided key, it validates that
	 * the comparable reference key contains a known internal primary key and
	 * returns the original key's reference key.
	 *
	 * @param crk the comparable reference key that is to be translated into an assigned reference key
	 * @return the assigned reference key corresponding to the provided comparable reference key,
	 *         or the original reference key if no assigned key exists
	 */
	@Nonnull
	private ReferenceKey translateToAssignedReferenceKey(@Nonnull ComparableReferenceKey crk) {
		return getReferenceKeyManager().translateToAssignedReferenceKey(crk);
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
	 * @param createMode                 the mode of this propagate function - it can either
	 *                                    insert missing references or remove all existing
	 * @param mutationCollector          The mutation collector to accumulate mutations
	 * @param implicitMutations          A set of implicit mutation behaviors to be used for
	 *                                    generated external entity mutation.
	 */
	private void propagateReferencesToEntangledEntities(
		int entityPrimaryKey,
		@Nonnull Scope sourceEntityScope,
		@Nonnull Scope targetEntityScope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferencesStoragePart referencesStorageContainer,
		@Nonnull CreateMode createMode,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations
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
					createMode,
					implicitMutations
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
	 * @param createMode             the mode of this propagate function - it can either
	 *                               insert missing references or remove all existing
	 * @param implicitMutations      A set of implicit mutation behaviors to be used for
	 *                               generated external entity mutation.
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
		@Nonnull CreateMode createMode,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations
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
					final PersistentRoaringBitmap referencedPrimaryKeys = referenceBlock.getReferencedPrimaryKeys();
					// but we need to match only those our entity refers to via this reference
					final PersistentRoaringBitmap existingEntityPks = globalIndex == null ?
						null :
						PersistentRoaringBitmap.and(
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
							if (existingEntityPks == null
								&& referenceSchema instanceof ReflectedReferenceSchema
								&& !referencedPrimaryKeys.isEmpty()) {
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
										getAllReducedReferencedEntityIndexes(
											this.dataStoreReader,
											usedScope,
											referenceName,
											epk,
											allowsDuplicates
										),
									referenceBlock.getAttributeSupplier(),
									// we need to negate the predicate, because we want to insert missing references
									primaryKeyPredicate.negate(),
									// we need to take into account references removed in this turn
									getReferenceKeyManager()::isRepresentativeReferenceKeyRemoved,
									implicitMutations
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
	 * Verifies the presence and correctness of reference attributes based on the provided schemas
	 * and ensures all mandatory attributes are accounted for. It checks for missing attributes,
	 * processes attributes with default values, and updates the mutation collector if necessary.
	 *
	 * @param scope The scope of the current operation.
	 * @param entityStorageContainer The container representing the entity's body storage.
	 * @param referencesStoragePart The container holding the entity's references storage, which may be null.
	 * @param inputMutations A list of mutations providing input for the verification.
	 * @param missingMandatedAttributes A list to collect information about missing mandated attributes.
	 * @param mutationCollector The collector for recording attribute mutations.
	 * @param implicitMutations The set of implicit mutation behaviors to consider during the verification process.
	 * @throws MandatoryAttributesNotProvidedException If mandatory attributes are missing and cannot be resolved.
	 */
	private void verifyReferenceAttributes(
		@Nonnull Scope scope,
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nullable ReferencesStoragePart referencesStoragePart,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull EnumSet<ImplicitMutationBehavior> implicitMutations
	) throws MandatoryAttributesNotProvidedException {
		if (referencesStoragePart == null) {
			return;
		}

		final CatalogSchema catalogSchema = this.catalogSchemaAccessor.get();
		final EntitySchema entitySchema = this.schemaAccessor.get();

		if (implicitMutations.contains(ImplicitMutationBehavior.GENERATE_REFLECTED_REFERENCES)) {
			propagateOrphanedReferenceAttributeMutations(
				scope, catalogSchema, entitySchema, inputMutations, mutationCollector
			);
		}

		final Set<Locale> entityLocales = entityStorageContainer.getLocales();
		final List<AttributeKey> missingReferenceMandatedAttribute = new LinkedList<>();
		// the only per-reference state the handler needs is the reference key; carrying it through a single-element
		// holder lets the handler (and its captured context) be allocated once per call instead of once per reference
		final ReferenceKey[] currentReferenceKey = new ReferenceKey[1];
		final MissingAttributeHandler missingAttributeHandler = (defaultValue, nullable, attributeKey) -> {
			Assert.isPremiseValid(attributeKey != null, "Attribute key must not be null!");
			if (defaultValue == null) {
				if (!nullable) {
					missingReferenceMandatedAttribute.add(attributeKey);
				}
			} else {
				mutationCollector.addLocalMutation(
					new ReferenceAttributeMutation(
						currentReferenceKey[0],
						new UpsertAttributeMutation(attributeKey, defaultValue)
					)
				);
			}
		};
		ReferenceSchema referenceSchema = null;
		for (Reference reference : referencesStoragePart.getReferences()) {
			if (reference.exists()) {
				if (referenceSchema == null ||
					!referenceSchema.getName().equals(reference.getReferenceName())) {
					referenceSchema = entitySchema.getReferenceOrThrowException(reference.getReferenceName());
				}
				// skip references whose schema declares no mandatory / default-valued attributes -
				// there is nothing to verify or default here, so we avoid probing the reference attributes
				// for the common case
				if (referenceSchema.getNonNullableOrDefaultValueAttributes().isEmpty()) {
					continue;
				}
				missingReferenceMandatedAttribute.clear();
				// point the shared handler at the reference currently being verified
				currentReferenceKey[0] = reference.getReferenceKey();

				// probe the reference directly for each mandatory / default-valued schema attribute instead of
				// materializing the full key set of the reference (a HashSet per reference on the hot path)
				processReferenceAttributesWithDefaultValue(
					referenceSchema, entityLocales, reference, missingAttributeHandler
				);

				if (!missingReferenceMandatedAttribute.isEmpty()) {
					missingMandatedAttributes.add(
						new MissingReferenceAttribute(
							referenceSchema.getName(),
							new ArrayList<>(missingReferenceMandatedAttribute)
						)
					);
				}
			}
		}
	}

	/**
	 * Propagates mutations related to reference attributes that are not related to any reference insertion.
	 * Method generates mutations that replay attribute mutations in reflected references or in reference attributes in
	 * the target entity collection, so that consistency is maintained.
	 *
	 * @param catalogSchema     The schema of the catalog, containing metadata about the entire data catalog.
	 * @param entitySchema      The schema of the entity, containing metadata about the specific entity type.
	 * @param inputMutations    The list of local mutations to be processed, potentially
	 *                          containing reference attribute mutations.
	 * @param mutationCollector The collector used to gather and store mutations that need to be externally applied.
	 */
	private void propagateOrphanedReferenceAttributeMutations(
		@Nonnull Scope scope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull MutationCollector mutationCollector
	) {
		// go through all input mutations
		final Map<EntityReference, List<ReferenceAttributeMutation>>
			referenceAttributeMutationsByEntityReference = new LazyHashMap<>(inputMutations.size());
		for (LocalMutation<?, ?> inputMutation : inputMutations) {
			// and check if there are any reference attribute mutation
			if (inputMutation instanceof ReferenceAttributeMutation ram) {
				final ReferenceKey referenceKey = getReferenceKeyManager().getAssignedReferenceKey(ram.getReferenceKey());
				// find the reference schema
				final String referenceName = referenceKey.referenceName();
				final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
				// if the mutation relate to reference which hasn't been created in the same entity update
				if (!getReferenceKeyManager().isReferenceKeyCreated(referenceKey)) {
					// access the data store reader of referenced collection
					final DataStoreReader dataStoreReader =
						this.dataStoreReaderAccessor.apply(referenceSchema.getReferencedEntityType());
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
										final ReferenceKey referenceKeyToUpdate = new ReferenceKey(
											rrsc.getReflectedReferenceName(), this.entityPrimaryKey,
											ram.getReferenceKey().internalPrimaryKey()
										);
										if (getReferenceKeyManager().isReferenceKeyNotRemoved(referenceKeyToUpdate)) {
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
											final ReferenceKey referenceKeyToUpdate = new ReferenceKey(
												rrsc.getName(), this.entityPrimaryKey,
												ram.getReferenceKey().internalPrimaryKey()
											);
											if (getReferenceKeyManager().isReferenceKeyNotRemoved(referenceKeyToUpdate)) {
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
		for (Entry<EntityReference, List<ReferenceAttributeMutation>> entry :
			referenceAttributeMutationsByEntityReference.entrySet()) {
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
	 * Verifies that all mandatory (non-nullable) associated data are present on the entity.
	 *
	 * @param entityStorageContainer the entity body storage part to validate
	 * @throws MandatoryAssociatedDataNotProvidedException if any mandatory associated data is missing
	 *                                                     or if the entity has no locales but non-nullable
	 *                                                     localized associated data are defined in the schema
	 */
	private void verifyMandatoryAssociatedData(
		@Nonnull EntityBodyStoragePart entityStorageContainer
	) throws MandatoryAssociatedDataNotProvidedException {
		final EntitySchema entitySchema = this.schemaAccessor.get();
		final Collection<AssociatedDataSchema> nonNullableAssociatedData = entitySchema.getNonNullableAssociatedData();
		if (nonNullableAssociatedData.isEmpty()) {
			return;
		}

		final Set<AssociatedDataKey> availableAssociatedDataKeys = this.entityContainer.getAssociatedDataKeys();
		final Set<Locale> entityLocales = entityStorageContainer.getLocales();

		List<AssociatedDataKey> missingMandatedAssociatedData = null;
		for (final AssociatedDataSchema associatedData : nonNullableAssociatedData) {
			if (associatedData.isLocalized()) {
				for (final Locale locale : entityLocales) {
					final AssociatedDataKey key = new AssociatedDataKey(associatedData.getName(), locale);
					if (!availableAssociatedDataKeys.contains(key)) {
						if (missingMandatedAssociatedData == null) {
							missingMandatedAssociatedData = new LinkedList<>();
						}
						missingMandatedAssociatedData.add(key);
					}
				}
			} else {
				final AssociatedDataKey key = new AssociatedDataKey(associatedData.getName());
				if (!availableAssociatedDataKeys.contains(key)) {
					if (missingMandatedAssociatedData == null) {
						missingMandatedAssociatedData = new LinkedList<>();
					}
					missingMandatedAssociatedData.add(key);
				}
			}
		}

		if (missingMandatedAssociatedData != null && !missingMandatedAssociatedData.isEmpty()) {
			throw new MandatoryAssociatedDataNotProvidedException(entitySchema.getName(), missingMandatedAssociatedData);
		}

		// verify that entity has at least one locale if non-nullable localized associated data exist
		if (entityLocales.isEmpty()) {
			final List<String> nonNullableLocalizedAssociatedData = nonNullableAssociatedData
				.stream()
				.filter(AssociatedDataSchema::isLocalized)
				.map(AssociatedDataSchema::getName)
				.sorted()
				.toList();
			if (!nonNullableLocalizedAssociatedData.isEmpty()) {
				throw new MandatoryAssociatedDataNotProvidedException(
					"Entity `" + entitySchema.getName() + "` requires at least one locale because it has " +
						"non-nullable localized associated data: " +
						nonNullableLocalizedAssociatedData.stream()
							.map(it -> "`" + it + "`")
							.collect(Collectors.joining(", ")) + "."
				);
			}
		}
	}

	/**
	 * Verifies the removal of mandatory associated data and ensures that all required associated data
	 * are provided. If any mandatory associated data is removed and left unpopulated, this method
	 * throws a {@link MandatoryAssociatedDataNotProvidedException}.
	 *
	 * This method processes only the associated data marked as "dirty" and "empty", identifying the
	 * ones that are mandatory according to the provided entity schema. The verification involves:
	 * - Collecting the mandatory associated data names from the entity schema.
	 * - Filtering out the associated data that is dirty, empty, and mandatory.
	 * - Skipping localized associated data whose locale has been dropped from the entity (removing
	 *   all localized data for a locale is allowed as long as at least one locale remains).
	 * - Checking if there are any missing mandatory associated data.
	 *
	 * If any mandatory associated data is missing, the method raises an exception containing the details
	 * of the missing data and the entity type to ensure that all necessary data is provided before proceeding.
	 *
	 * @throws MandatoryAssociatedDataNotProvidedException if any mandatory associated data is missing
	 *                                                     or if the entity has no locales but non-nullable
	 *                                                     localized associated data are defined in the schema
	 */
	private void verifyRemovedMandatoryAssociatedData() {
		final Set<Locale> entityLocales = this.entityContainer.getLocales();
		List<AssociatedDataKey> missingMandatedAssociatedData = null;
		Set<String> mandatoryAssociatedData = null;
		if (this.associatedDataContainers != null) {
			for (final AssociatedDataStoragePart part : this.associatedDataContainers.values()) {
				// only dirty containers that have been emptied are candidates for a missing mandatory value
				if (!part.isDirty() || !part.isEmpty()) {
					continue;
				}
				// lazily resolve the set of mandatory associated data names on the first candidate
				if (mandatoryAssociatedData == null) {
					final Collection<AssociatedDataSchema> nonNullableAssociatedData =
						this.schemaAccessor.get().getNonNullableAssociatedData();
					mandatoryAssociatedData = CollectionUtils.createHashSet(nonNullableAssociatedData.size());
					for (final AssociatedDataSchema associatedData : nonNullableAssociatedData) {
						mandatoryAssociatedData.add(associatedData.getName());
					}
				}
				final AssociatedDataValue value = part.getValue();
				if (value == null || !mandatoryAssociatedData.contains(value.key().associatedDataName())) {
					continue;
				}
				final AssociatedDataKey key = value.key();
				// skip localized associated data whose locale has been dropped from the entity
				if (key.locale() != null && !entityLocales.contains(key.locale())) {
					continue;
				}
				if (missingMandatedAssociatedData == null) {
					missingMandatedAssociatedData = new LinkedList<>();
				}
				missingMandatedAssociatedData.add(key);
			}
		}

		if (missingMandatedAssociatedData != null && !missingMandatedAssociatedData.isEmpty()) {
			throw new MandatoryAssociatedDataNotProvidedException(this.entityType, missingMandatedAssociatedData);
		}

		// verify that entity retains at least one locale if non-nullable localized associated data exist
		if (entityLocales.isEmpty()) {
			final EntitySchema entitySchema = this.schemaAccessor.get();
			final List<String> nonNullableLocalizedAssociatedData = entitySchema.getNonNullableAssociatedData()
				.stream()
				.filter(AssociatedDataSchema::isLocalized)
				.map(AssociatedDataSchema::getName)
				.sorted()
				.toList();
			if (!nonNullableLocalizedAssociatedData.isEmpty()) {
				throw new MandatoryAssociatedDataNotProvidedException(
					"Entity `" + this.entityType + "` requires at least one locale because it has " +
						"non-nullable localized associated data: " +
						nonNullableLocalizedAssociatedData.stream()
							.map(it -> "`" + it + "`")
							.collect(Collectors.joining(", ")) + "."
				);
			}
		}
	}

	/**
	 * Verifies the cardinality constraints of references as defined in the entity schema.
	 * Ensures that each reference complies with its respective cardinality requirements. If any violations are found,
	 * a {@link ReferenceCardinalityViolatedException} is thrown with details of the violations.
	 *
	 * @param referencesStorageContainer An optional container holding the current state of references associated
	 *                                   with an entity. Can be null if there are no references to validate.
	 * @throws ReferenceCardinalityViolatedException If any reference violates its defined cardinality
	 *                                               as specified in the entity schema.
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
	@Nonnull
	private List<EntityStoragePart> getChangedEntityStorageParts() {
		// allocation-optimized collection of the changed containers (avoids the nested Stream pipeline)
		final List<EntityStoragePart> dirtyParts = new ArrayList<>(8);
		addIfDirty(dirtyParts, this.getEntityStorageContainer());
		addIfDirty(dirtyParts, this.pricesContainer);
		addIfDirty(dirtyParts, this.globalAttributesStorageContainer);
		addIfDirty(dirtyParts, this.referencesStorageContainer);
		if (this.languageSpecificAttributesContainer != null) {
			for (final AttributesStoragePart part : this.languageSpecificAttributesContainer.values()) {
				addIfDirty(dirtyParts, part);
			}
		}
		if (this.associatedDataContainers != null) {
			for (final AssociatedDataStoragePart part : this.associatedDataContainers.values()) {
				addIfDirty(dirtyParts, part);
			}
		}
		return dirtyParts;
	}

	/**
	 * Adds the given storage part to the target list when it is not {@code null} and has been changed.
	 *
	 * @param target the list to add the part to
	 * @param part   the storage part to add, may be {@code null}
	 */
	private static void addIfDirty(@Nonnull List<EntityStoragePart> target, @Nullable EntityStoragePart part) {
		if (part != null && part.isDirty()) {
			target.add(part);
		}
	}

	/**
	 * Returns either existing {@link EntityStoragePart} or creates brand new container.
	 */
	@Nonnull
	private EntityBodyStoragePart getEntityStorageContainer() {
		// if entity represents first version we need to forcefully create entity container object
		this.entityContainer = this.entityContainer == null
			? new EntityBodyStoragePart(this.entityPrimaryKey) : this.entityContainer;
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
			.orElseThrow(() -> new EvitaInvalidUsageException(
				"Attribute `" + attributeKey.attributeName() +
					"` is not known for entity `" + entitySchema.getName() + "`."
			));
		// get or create the locale-specific attributes container, or the locale-agnostic (global) one
		final Locale attributeLocale = attributeKey.locale();
		final AttributesStoragePart attributesStorageContainer = attributeLocale != null
			? getAttributeStoragePart(this.entityType, this.entityPrimaryKey, attributeLocale)
			: getAttributeStoragePart(this.entityType, this.entityPrimaryKey);

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
	private void updateAssociatedData(
		@Nonnull EntitySchemaContract entitySchema, @Nonnull AssociatedDataMutation localMutation
	) {
		final AssociatedDataKey associatedDataKey = localMutation.getAssociatedDataKey();
		// get or create associated data container
		final AssociatedDataStoragePart associatedDataStorageContainer = getAssociatedDataStoragePart(
			this.entityType, this.entityPrimaryKey, associatedDataKey
		);
		final AssociatedDataValue mutatedValue =
			localMutation.mutateLocal(entitySchema, associatedDataStorageContainer.getValue());
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
				registerAddedLocale(associatedDataKey.locale(), EnumSet.of(LocaleScope.ENTITY));
			} else {
				Assert.isPremiseValid(associatedDataKey.locale() != null, "Locale must not be null!");
				registerRemovedLocale(associatedDataKey.locale(), EnumSet.of(LocaleScope.ENTITY));
			}
		}
	}

	/**
	 * Method processes reference related mutations by applying create/update/delete operations in
	 * {@link ReferencesStoragePart#getReferences()}.
	 */
	private void updateReferences(@Nonnull EntitySchema entitySchema, @Nonnull ReferenceMutation<?> localMutation) {
		// get or create references container
		final ReferencesStoragePart referencesStorageCnt = getReferencesStoragePart(this.entityType, this.entityPrimaryKey);
		// replace or add the mutated reference in the container
		final ReferenceKey referenceKey = localMutation.getReferenceKey();
		final ReferenceContract updatedReference;
		if (referenceKey.isKnownInternalPrimaryKey() || referenceKey.isNewReference()) {
			updatedReference = referencesStorageCnt.replaceOrAddReference(
				referenceKey,
				referenceContract -> localMutation.mutateLocal(entitySchema, referenceContract),
				() -> entitySchema.getReferenceOrThrowException(
					referenceKey.referenceName()
				) instanceof ReflectedReferenceSchemaContract ?
					MissingReferenceBehavior.ACCEPT_INTERNAL_KEY : MissingReferenceBehavior.GENERATE_NEW_INTERNAL_KEY
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
				referenceKey,
				referenceContract -> localMutation.mutateLocal(entitySchema, referenceContract),
				() -> entitySchema.getReferenceOrThrowException(
					referenceKey.referenceName()
				) instanceof ReflectedReferenceSchemaContract ?
					MissingReferenceBehavior.ACCEPT_INTERNAL_KEY : MissingReferenceBehavior.GENERATE_NEW_INTERNAL_KEY
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
			removeEntireReference(
				updatedReference,
				entitySchema.getReferenceOrThrowException(referenceKey.referenceName())
			);
		}
	}

	/**
	 * Method processes mutation where {@link EntityBodyStoragePart#getParent()} is modified. It replaces
	 * original container preserving all data but changing the hierarchy placement in it.
	 */
	private void updateParent(@Nonnull EntitySchemaContract entitySchema, @Nonnull ParentMutation localMutation) {
		// get entity container
		final EntityBodyStoragePart entityStorageContainer = getEntityStoragePart(
			this.entityType, this.entityPrimaryKey, this.requiresExisting
		);
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
			priceKey -> {
				final Integer assignedPriceId = this.assignedInternalPriceIdIndex == null
					? null : this.assignedInternalPriceIdIndex.get(priceKey);
				return assignedPriceId == null ? this.priceInternalIdSupplier.getAsInt() : assignedPriceId;
			}
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
	private void updatePrices(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull SetPriceInnerRecordHandlingMutation localMutation
	) {
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
	private void updateEntityScope(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull SetEntityScopeMutation setEntityScopeMutation
	) {
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
	private void removeEntireReference(
		@Nonnull ReferenceContract updatedReference,
		@Nonnull ReferenceSchema referenceSchema
	) {
		final ReferencesStoragePart referencesStoragePart = getReferencesStoragePart(this.entityType, this.entityPrimaryKey);

		// register removed reference key and its representative attribute values
		getReferenceKeyManager().registerRemovedReference(
			updatedReference.getReferenceKey(),
			referenceSchema.getRepresentativeAttributeDefinition().getRepresentativeValues(updatedReference)
		);

		updatedReference.getAttributeLocales().forEach(locale -> {
			final AttributesStoragePart attributeStoragePart = getAttributeStoragePart(
				this.entityType, this.entityPrimaryKey, locale
			);
			if (attributeStoragePart.isEmpty() && !referencesStoragePart.isLocalePresent(locale)) {
				final EntityBodyStoragePart ebsp = getEntityStoragePart(
					this.entityType, this.entityPrimaryKey, EntityExistence.MUST_EXIST
				);
				final LocaleModificationResult localeModificationResult = ebsp.removeAttributeLocale(locale);
				if (localeModificationResult.anyChangeOccurred()) {
					this.localesIdentityHash++;
					final EnumSet<LocaleScope> scopesAffected = EnumSet.noneOf(LocaleScope.class);
					if (localeModificationResult.attributeLocalesChanged()) {
						scopesAffected.add(LocaleScope.ATTRIBUTE);
					}
					if (localeModificationResult.entityLocalesChanged()) {
						scopesAffected.add(LocaleScope.ENTITY);
					}
					registerRemovedLocale(locale, scopesAffected);
				}
			}
		});
	}

	/**
	 * Registers a new locale with the specified scopes. If the locale already exists in the
	 * removed locales with overlapping scopes, those scopes are removed from both the added
	 * and removed locales. Updates the internal data structures for added and removed locales
	 * accordingly.
	 *
	 * @param locale the locale to be registered; must not be null
	 * @param scopes the set of locale scopes associated with the locale; must not be null
	 */
	private void registerAddedLocale(@Nonnull Locale locale, @Nonnull EnumSet<LocaleScope> scopes) {
		if (this.addedLocales == null) {
			this.addedLocales = new HashMap<>(16);
		}
		final EnumSet<LocaleScope> addedScopes = EnumSet.copyOf(scopes);
		if (this.removedLocales != null) {
			final EnumSet<LocaleScope> removedScopes = this.removedLocales.get(locale);
			if (removedScopes != null) {
				// overlapping scopes - remove them from both sets
				addedScopes.removeAll(removedScopes);
				removedScopes.removeAll(scopes);
				if (removedScopes.isEmpty()) {
					this.removedLocales.remove(locale);
				}
				if (this.removedLocales.isEmpty()) {
					this.removedLocales = null;
				}
			}
		}
		if (!addedScopes.isEmpty()) {
			this.addedLocales.compute(
				locale,
				(key, existingScopes) -> {
					if (existingScopes == null) {
						return addedScopes;
					} else {
						existingScopes.addAll(addedScopes);
						return existingScopes;
					}
				}
			);
		}
	}

	/**
	 * Registers a locale as removed by associating it with a specific set of scopes.
	 * Handles the internal adjustment of added and removed locales to ensure consistency.
	 *
	 * @param locale the locale to be marked as removed, must not be null
	 * @param scopes the set of scopes associated with the removed locale, must not be null
	 */
	private void registerRemovedLocale(@Nonnull Locale locale, @Nonnull EnumSet<LocaleScope> scopes) {
		if (this.removedLocales == null) {
			this.removedLocales = new HashMap<>(16);
		}
		final EnumSet<LocaleScope> removedScopes = EnumSet.copyOf(scopes);
		if (this.addedLocales != null) {
			final EnumSet<LocaleScope> addedScopes = this.addedLocales.get(locale);
			if (addedScopes != null) {
				// overlapping scopes - remove them from both sets
				removedScopes.removeAll(addedScopes);
				addedScopes.removeAll(scopes);
				if (addedScopes.isEmpty()) {
					this.addedLocales.remove(locale);
				}
				if (this.addedLocales.isEmpty()) {
					this.addedLocales = null;
				}
			}
		}
		if (!removedScopes.isEmpty()) {
			this.removedLocales.compute(
				locale,
				(key, existingScopes) -> {
					if (existingScopes == null) {
						return removedScopes;
					} else {
						existingScopes.addAll(removedScopes);
						return existingScopes;
					}
				}
			);
		}
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

	/**
	 * Internal class that manages reference keys and their lifecycle during entity mutation operations.
	 * This class encapsulates the state and operations related to:
	 *
	 * - Assigned primary keys - mapping from original to newly assigned reference keys</li>
	 * - Created reference keys - tracking which references were created in the current mutation</li>
	 * - Removed reference keys - tracking which references were removed in the current mutation</li>
	 * - Removed representable reference keys - tracking removed references with their representative attributes</li>
	 *
	 * All data structures are allocated lazily to minimize memory footprint when not needed.
	 */
	private static class ReferenceKeyManager {
		/**
		 * Maps comparable reference keys (without internal PK) to their assigned reference keys (with internal PK).
		 * Allocated lazily when reference storage container assigns internal PKs.
		 */
		private Map<ComparableReferenceKey, ReferenceKey> assignedPrimaryKeys;

		/**
		 * Maps created reference keys to their original keys before internal PK assignment.
		 * This always contains reference keys with known internal primary keys.
		 * Allocated lazily when references with assigned PKs are present.
		 */
		private Map<ReferenceKey, ReferenceKey> createdReferenceKeys;

		/**
		 * Contains reference keys that were removed in the current mutation.
		 * Allocated lazily when first reference is removed.
		 */
		private Set<ReferenceKey> removedReferenceKeys;

		/**
		 * Contains representative reference keys (reference key + representative attributes) that were removed.
		 * Used for tracking removed references with their distinguishing attribute values.
		 * Allocated lazily when first reference is removed.
		 */
		private Set<RepresentativeReferenceKey> removedRepresentableReferenceKeys;

		/**
		 * Processes the assigned primary keys returned from reference storage container after sorting and ID assignment.
		 * Creates reverse mapping from newly assigned keys to original keys for lookup during mutation propagation.
		 *
		 * @param assignedKeys map of comparable keys to their assigned reference keys with internal PKs
		 */
		void processAssignedPrimaryKeys(@Nonnull Map<ComparableReferenceKey, ReferenceKey> assignedKeys) {
			if (assignedKeys.isEmpty()) {
				this.createdReferenceKeys = this.createdReferenceKeys == null ?
					Collections.emptyMap() : this.createdReferenceKeys;
				this.assignedPrimaryKeys = this.assignedPrimaryKeys == null ?
					Collections.emptyMap() : this.assignedPrimaryKeys;
			} else {
				this.createdReferenceKeys = this.createdReferenceKeys == null || this.createdReferenceKeys.isEmpty() ?
					CollectionUtils.createHashMap(assignedKeys.size()) : this.createdReferenceKeys;
				if (this.assignedPrimaryKeys == null || this.assignedPrimaryKeys.isEmpty()) {
					this.assignedPrimaryKeys = new HashMap<>(assignedKeys);
				} else {
					this.assignedPrimaryKeys.putAll(assignedKeys);
				}
				for (Entry<ComparableReferenceKey, ReferenceKey> entry : assignedKeys.entrySet()) {
					final ReferenceKey key = entry.getValue();
					final ReferenceKey assignedKey = entry.getKey().referenceKey();
					Assert.isPremiseValid(
						key.isKnownInternalPrimaryKey(),
						"Assigned reference key " + key + " must have known internal primary key!"
					);
					this.createdReferenceKeys.put(key, assignedKey);
				}
			}
		}

		/**
		 * Marks all existing references in the provided array as created. This is used when entity scope changes
		 * and all references need to be considered as newly created for reflected reference propagation purposes.
		 *
		 * @param references array of references to mark as created
		 */
		void markAllReferencesAsCreated(@Nonnull Reference[] references) {
			this.createdReferenceKeys = CollectionUtils.createHashMap(references.length);
			for (Reference reference : references) {
				if (reference.exists()) {
					final ReferenceKey refKey = reference.getReferenceKey();
					Assert.isPremiseValid(
						refKey.isKnownInternalPrimaryKey(),
						"Reference key " + refKey + " must have known internal primary key!"
					);
					this.createdReferenceKeys.put(refKey, refKey);
				}
			}
		}

		/**
		 * Handles reference key reassignment during reflected reference internal PK replacement.
		 * Updates both created and assigned key mappings to reflect the new internal PK.
		 *
		 * @param oldKey the old reference key before reassignment
		 * @param newKey the new reference key with updated internal PK
		 */
		void reassignReferenceKey(@Nonnull ReferenceKey oldKey, @Nonnull ReferenceKey newKey) {
			if (this.createdReferenceKeys != null && !this.createdReferenceKeys.isEmpty()) {
				final ReferenceKey originalValue = this.createdReferenceKeys.remove(oldKey);
				if (originalValue != null) {
					Assert.isPremiseValid(
						newKey.isKnownInternalPrimaryKey(),
						"Reassigned reference key " + newKey + " must have known internal primary key!"
					);
					this.createdReferenceKeys.put(newKey, originalValue);
					if (this.assignedPrimaryKeys != null) {
						this.assignedPrimaryKeys.put(new ComparableReferenceKey(originalValue), newKey);
					}
				}
			}
		}

		/**
		 * Checks if the given reference key was created in the current mutation operation.
		 *
		 * @param referenceKey the reference key to check
		 * @return true if the reference was created in this mutation, false otherwise
		 */
		boolean isReferenceKeyCreated(@Nonnull ReferenceKey referenceKey) {
			return this.createdReferenceKeys != null && this.createdReferenceKeys.containsKey(referenceKey);
		}

		/**
		 * Checks if the given reference key was removed in the current mutation operation.
		 *
		 * @param referenceKey the reference key to check
		 * @return true if the reference was removed in this mutation, false otherwise
		 */
		boolean isReferenceKeyNotRemoved(@Nonnull ReferenceKey referenceKey) {
			return this.removedReferenceKeys == null || !this.removedReferenceKeys.contains(referenceKey);
		}

		/**
		 * Checks if the given representative reference key (with representative attributes) was removed.
		 *
		 * @param representativeReferenceKey the representative reference key to check
		 * @return true if the representative reference was removed, false otherwise
		 */
		boolean isRepresentativeReferenceKeyRemoved(@Nonnull RepresentativeReferenceKey representativeReferenceKey) {
			return this.removedRepresentableReferenceKeys != null &&
			       this.removedRepresentableReferenceKeys.contains(representativeReferenceKey);
		}

		/**
		 * Registers a reference as removed, storing both its key and representative attribute values.
		 * Lazily allocates removal tracking sets if not already present.
		 *
		 * @param referenceKey the key of the removed reference
		 * @param representativeAttributeValues the representative attribute values of the removed reference
		 */
		void registerRemovedReference(
			@Nonnull ReferenceKey referenceKey,
			@Nonnull Serializable[] representativeAttributeValues
		) {
			if (this.removedRepresentableReferenceKeys == null) {
				this.removedRepresentableReferenceKeys = CollectionUtils.createHashSet(8);
			}
			this.removedRepresentableReferenceKeys.add(
				new RepresentativeReferenceKey(referenceKey, representativeAttributeValues)
			);

			if (this.removedReferenceKeys == null) {
				this.removedReferenceKeys = CollectionUtils.createHashSet(8);
			}
			Assert.isPremiseValid(
				referenceKey.isKnownInternalPrimaryKey(),
				"Removed reference key " + referenceKey + " must have known internal primary key!"
			);
			this.removedReferenceKeys.add(referenceKey);
		}

		/**
		 * Translates a comparable reference key to its assigned reference key with internal PK.
		 * If no assignment exists, validates that the original key has a known internal PK.
		 *
		 * @param comparableKey the comparable reference key to translate
		 * @return the assigned reference key, or the original key if no assignment exists
		 * @throws AssertionError if the key has no assignment and no known internal PK
		 */
		@Nonnull
		ReferenceKey translateToAssignedReferenceKey(@Nonnull ComparableReferenceKey comparableKey) {
			if (this.assignedPrimaryKeys == null) {
				Assert.isPremiseValid(
					comparableKey.referenceKey().isKnownInternalPrimaryKey(),
					"Reference key " + comparableKey + " has not been assigned a primary key!"
				);
				return comparableKey.referenceKey();
			}

			final ReferenceKey reassignedKey = this.assignedPrimaryKeys.get(comparableKey);
			if (reassignedKey == null) {
				Assert.isPremiseValid(
					comparableKey.referenceKey().isKnownInternalPrimaryKey(),
					"Reference key " + comparableKey + " has not been assigned a primary key!"
				);
				return comparableKey.referenceKey();
			}
			return reassignedKey;
		}

		/**
		 * Gets the assigned reference key for a given reference key, looking up via comparable key wrapper.
		 * Returns the input key if no assignment is found.
		 *
		 * @param referenceKey the reference key to look up
		 * @return the assigned reference key, or the input key if no assignment exists
		 */
		@Nonnull
		ReferenceKey getAssignedReferenceKey(@Nonnull ReferenceKey referenceKey) {
			if (this.assignedPrimaryKeys == null) {
				return referenceKey;
			}
			return ofNullable(this.assignedPrimaryKeys.get(new ComparableReferenceKey(referenceKey)))
				.orElse(referenceKey);
		}

		/**
		 * Returns the map of assigned primary keys.
		 *
		 * @return unmodifiable map of assigned keys, or empty map if no assignments exist
		 */
		@Nonnull
		Map<ComparableReferenceKey, ReferenceKey> getAssignedPrimaryKeys() {
			return this.assignedPrimaryKeys == null ?
				Collections.emptyMap() : this.assignedPrimaryKeys;
		}
	}

}