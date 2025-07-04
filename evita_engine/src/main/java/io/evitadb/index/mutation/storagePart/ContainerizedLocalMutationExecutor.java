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
import io.evitadb.api.exception.EntityMissingException;
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
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
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
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
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
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
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
	 * Creates and registers insert reference mutation for currently processed entity into mutation collector.
	 *
	 * @param referenceSchema                     the reference schema, must not be {@code null}
	 * @param referenceIndexWithPrimaryReferences the index containing primary references
	 * @param referencesStorageContainer          the references storage container if exists
	 * @param mutationCollector                   the mutation collector, must not be {@code null}
	 * @param eachReferenceConsumer               consumer that is invoked with each created reference
	 */
	private static void createAndRegisterReferencePropagationMutation(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReducedEntityIndex referenceIndexWithPrimaryReferences,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull Consumer<List<ReferenceKey>> eachReferenceConsumer
	) {
		// for each such entity create internal (reflected) reference to it (if it doesn't exist)
		final Bitmap referencingEntities = referenceIndexWithPrimaryReferences.getAllPrimaryKeys();
		final List<ReferenceKey> referenceKeys = new ArrayList<>(referencingEntities.size());
		final OfInt it = referencingEntities.iterator();
		while (it.hasNext()) {
			int epk = it.nextInt();
			final ReferenceKey refKey = new ReferenceKey(referenceSchema.getName(), epk);
			if (referencesStorageContainer == null || !referencesStorageContainer.contains(refKey)) {
				mutationCollector.addLocalMutation(new InsertReferenceMutation(refKey));
				referenceKeys.add(refKey);
			}
		}

		if (!referenceKeys.isEmpty()) {
			eachReferenceConsumer.accept(referenceKeys);
		}
	}

	/**
	 * Generates missing references for entities by verifying the existence of referenced entities
	 * and creating the necessary references if they are absent. This method ensures that the
	 * entities conform to the expected reference structure within the schema.
	 *
	 * @param entityPrimaryKey           The primary key of the entity for which the missing references need to be generated.
	 * @param mutationCollector          Collector used to store the generated mutations for processing.
	 * @param existingEntityPks          Bitmap of existing entity primary keys used for iterating and identifying existing entities.
	 * @param referenceSchema			 The reference schema used to generate the missing references.
	 * @param referencedSchemaName       The name of the schema in which the references are being created.
	 * @param referencedEntityType       The type of the referenced entity that should be checked and potentially created.
	 * @param referenceAttributeSupplier Supplier providing an array of reference attribute mutations to be applied to the missing references.
	 * @param entityPrimaryKeyPredicate  A predicate that determines if the entity primary key matches the criteria for insertion.
	 */
	private static void generateMissing(
		int entityPrimaryKey,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull RoaringBitmap existingEntityPks,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referencedSchemaName,
		@Nonnull String referencedEntityType,
		@Nonnull Function<ReferenceKey, ReferenceAttributeMutation[]> referenceAttributeSupplier,
		@Nonnull IntPredicate entityPrimaryKeyPredicate
	) {
		// and for all of those create missing references
		final PeekableIntIterator existingPrimaryKeysIterator = existingEntityPks.getIntIterator();
		while (existingPrimaryKeysIterator.hasNext()) {
			final int ownerEntityPrimaryKey = existingPrimaryKeysIterator.next();
			// but we need to match only those, our entity refers to via this reference and doesn't exist
			if (entityPrimaryKeyPredicate.test(ownerEntityPrimaryKey)) {
				// if the reference is not present, we need to create it
				mutationCollector.addExternalMutation(
					new ServerEntityUpsertMutation(
						referencedEntityType, ownerEntityPrimaryKey, EntityExistence.MUST_EXIST,
						EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES),
						true,
						true,
						ArrayUtils.mergeArrays(
							new LocalMutation[]{
								new InsertReferenceMutation(
									new ReferenceKey(referencedSchemaName, entityPrimaryKey)
								)
							},
							referenceAttributeSupplier.apply(
								new ReferenceKey(referenceSchema.getName(), ownerEntityPrimaryKey)
							)
						)
					)
				);
			}
		}
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
	private static void removeExistingEntityPks(
		int entityPrimaryKey,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull RoaringBitmap existingEntityPks,
		@Nonnull String referencedSchemaName,
		@Nonnull String referencedEntityType,
		@Nonnull IntPredicate entityPrimaryKeyPredicate
	) {
		// and for all of those create missing references
		final PeekableIntIterator existingPrimaryKeysIterator = existingEntityPks.getIntIterator();
		while (existingPrimaryKeysIterator.hasNext()) {
			final int epk = existingPrimaryKeysIterator.next();
			// but we need to match only those, our entity refers to via this reference and exist
			if (entityPrimaryKeyPredicate.test(epk)) {
				// if the reference is present, we need to remove it
				mutationCollector.addExternalMutation(
					new ServerEntityUpsertMutation(
						referencedEntityType, epk, EntityExistence.MUST_EXIST,
						EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES),
						true,
						true,
						new RemoveReferenceMutation(
							new ReferenceKey(referencedSchemaName, entityPrimaryKey)
						)
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
		final EntitySchema entitySchema = schemaAccessor.get();
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
					remover.accept(catalogVersion, part);
				} else {
					Assert.isPremiseValid(
						!entityRemovedEntirely,
						"Only removal operations are expected to happen!"
					);
					updater.accept(catalogVersion, part);
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
		final EntitySchema entitySchema = schemaAccessor.get();
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
		final EntitySchema entitySchema = schemaAccessor.get();
		final CatalogSchema catalogSchema = catalogSchemaAccessor.get();

		setupReferencesOnEntityCreation(
			referencesStorageContainer,
			targetEntityScope, catalogSchema, entitySchema,
			missingMandatedAttributes, mutationCollector
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
	 * @param referencesStorageContainer container with existing references if exists
	 * @param catalogSchema              the catalog schema, must not be null
	 * @param entitySchema               the entity schema, must not be null
	 * @param missingMandatedAttributes  The list of missing mandated attributes.
	 * @param mutationCollector          the collector for mutations, must not be null
	 */
	private void setupReferencesOnEntityCreation(
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
			final String referencedEntityType = referenceSchema.getReferencedEntityType();
			// find reflected schema definition (if any)
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				// access the data store reader of referenced collection
				final Optional<DataStoreReader> referenceIndexDataStoreReader = ofNullable(this.dataStoreReaderAccessor.apply(referencedEntityType));
				final Optional<ReducedEntityIndex> referenceIndexWithPrimaryReferences = referenceIndexDataStoreReader
					.map(
						targetDataStoreReader ->
							// we need to access index in target entity collection, because we need to retrieve index with primary references
							targetDataStoreReader.getIndexIfExists(
								new EntityIndexKey(
									EntityIndexType.REFERENCED_ENTITY,
									scope,
									// we need to use this reflected reference name
									new ReferenceKey(reflectedReferenceSchema.getReflectedReferenceName(), this.entityPrimaryKey)
								),
								entityIndexKey -> null
							)
					);

				// if the target entity and reference schema exists, and the index contains any indexed data,
				// set-up all reflected references
				referenceIndexWithPrimaryReferences
					.ifPresent(
						referenceIndex -> createAndRegisterReferencePropagationMutation(
							reflectedReferenceSchema,
							referenceIndex,
							referencesStorageContainer,
							mutationCollector,
							referenceKeys -> setupMandatoryAttributes(
								catalogSchema,
								entitySchema,
								missingMandatedAttributes,
								mutationCollector,
								reflectedReferenceSchema,
								referenceKeys,
								// index data store reader must be logically present here
								referenceIndexDataStoreReader.orElseThrow()
							)
						)
					);
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
			.flatMap(referenceKey -> Arrays.stream(referenceBlock.getAttributeSupplier().apply(referenceKey)))
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
		final CatalogSchema catalogSchema = catalogSchemaAccessor.get();
		final EntitySchema entitySchema = schemaAccessor.get();
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
		final ReferenceContract[] references = Arrays.stream(referencesStorageContainer.getReferences())
			.filter(Droppable::exists)
			.toArray(ReferenceContract[]::new);

		for (int i = 0; i < references.length; i++) {
			final ReferenceContract reference = references[i];
			final String thisReferenceName = reference.getReferenceName();
			// fast forward to reference with different name
			if (!Objects.equals(thisReferenceName, referenceName)) {
				referenceName = thisReferenceName;
				final int currentIndex = i;
				// setup references
				propagateReferenceModification(
					entityPrimaryKey,
					sourceEntityScope,
					targetEntityScope,
					catalogSchema,
					entitySchema,
					thisReferenceName,
					() -> {
						final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(thisReferenceName);
						// lets collect all primary keys of the reference with the same name into a bitmap
						if (currentIndex + 1 < references.length) {
							for (int j = currentIndex + 1; j < references.length; j++) {
								if (!thisReferenceName.equals(references[j].getReferenceName())) {
									return new ReferenceBlock<>(
										catalogSchema, this.entityContainer.getLocales(), entitySchema, referenceSchema,
										new ReferenceAttributeValueProvider(this.entityPrimaryKey, Arrays.copyOfRange(references, currentIndex, j))
									);
								}
							}
							return new ReferenceBlock<>(
								catalogSchema, this.entityContainer.getLocales(), entitySchema, referenceSchema,
								new ReferenceAttributeValueProvider(this.entityPrimaryKey, Arrays.copyOfRange(references, currentIndex, references.length))
							);
						} else {
							return new ReferenceBlock<>(
								catalogSchema, this.entityContainer.getLocales(), entitySchema, referenceSchema,
								new ReferenceAttributeValueProvider(this.entityPrimaryKey, reference)
							);
						}
					},
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
				if (propagationPredicate.test(referenceSchema, referencedSchemaRef.get())) {
					final String referencedSchemaName = referencedSchemaRef.get().getName();
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
					final IntPredicate primaryKeyPredicate;
					if (hardReference) {
						// provide reduced entity index related to provided entity primary key
						primaryKeyPredicate = (epk) -> {
							final ReducedEntityIndex referenceIndex = dataStoreReader.getIndexIfExists(
								new EntityIndexKey(
									EntityIndexType.REFERENCED_ENTITY,
									createMode == CreateMode.INSERT_MISSING ? targetEntityScope : sourceEntityScope,
									new ReferenceKey(referencedSchemaName, epk)
								),
								entityIndexKey -> null
							);
							// always check this entity primary key
							return referenceIndex != null && referenceIndex.getAllPrimaryKeys().contains(entityPrimaryKey);
						};
					} else {
						// locate shared reference index
						final ReducedEntityIndex sharedReferenceIndex = dataStoreReader.getIndexIfExists(
							new EntityIndexKey(
								EntityIndexType.REFERENCED_ENTITY,
								createMode == CreateMode.INSERT_MISSING ? targetEntityScope : sourceEntityScope,
								new ReferenceKey(referencedSchemaName, entityPrimaryKey)
							),
							entityIndexKey -> null
						);
						// always provide the same reference index
						primaryKeyPredicate = (epk) -> sharedReferenceIndex != null && sharedReferenceIndex.getAllPrimaryKeys().contains(epk);
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
									entityPrimaryKey, mutationCollector,
									existingEntityPks,
									referenceSchema,
									referencedSchemaName, referencedEntityType,
									referenceBlock.getAttributeSupplier(),
									// we need to negate the predicate, because we want to insert missing references
									primaryKeyPredicate.negate()
								);
							}
						}
						case REMOVE_ALL_EXISTING, REMOVE_NON_INDEXED -> removeExistingEntityPks(
							entityPrimaryKey, mutationCollector,
							existingEntityPks == null ? referencedPrimaryKeys : existingEntityPks,
							referencedSchemaName, referencedEntityType,
							primaryKeyPredicate
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

		final CatalogSchema catalogSchema = catalogSchemaAccessor.get();
		final EntitySchema entitySchema = schemaAccessor.get();
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

		// go through all input mutations
		for (LocalMutation<?, ?> inputMutation : inputMutations) {
			// and check if there are any reference attribute mutation
			if (inputMutation instanceof ReferenceAttributeMutation ram) {
				final ReferenceKey referenceKey = ram.getReferenceKey();
				// lazy init created references on first attribute mutation
				createdReferences = createdReferences == null ? createdReferencesSupplier.get() : createdReferences;
				// if the mutation relate to reference which hasn't been created in the same entity update
				if (!createdReferences.contains(referenceKey)) {

					final String referenceName = referenceKey.referenceName();
					final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);

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
								// create shared factory for creating entity mutations (shared logic)
								final Function<ReferenceAttributeMutation, ServerEntityUpsertMutation> entityMutationFactory =
									referenceAttributeMutation -> new ServerEntityUpsertMutation(
										referenceSchema.getReferencedEntityType(),
										referenceKey.primaryKey(),
										EntityExistence.MUST_EXIST,
										EnumSet.noneOf(ImplicitMutationBehavior.class),
										true,
										true,
										referenceAttributeMutation
									);

								// if the current reference is a reflected reference
								if (referenceSchema instanceof ReflectedReferenceSchemaContract rrsc) {
									final boolean attributeVisibleFromOtherSide = catalogSchema.getEntitySchema(rrsc.getReferencedEntityType())
										.flatMap(it -> it.getReference(rrsc.getReflectedReferenceName()))
										.map(it -> it.getAttribute(ram.getAttributeKey().attributeName()))
										.isPresent();
									// create a mutation to counterpart reference in the referenced entity
									// but only if the attribute is visible from that point of view
									if (attributeVisibleFromOtherSide) {
										mutationCollector.addExternalMutation(
											entityMutationFactory.apply(
												new ReferenceAttributeMutation(
													new ReferenceKey(rrsc.getReflectedReferenceName(), this.entityPrimaryKey),
													toInvertedTypeAttributeMutation(ram.getAttributeMutation())
												)
											)
										);
									}
								} else {
									// otherwise check whether there is reflected reference in the referenced entity
									// that relates to our standard reference
									catalogSchema.getEntitySchema(referenceSchema.getReferencedEntityType())
										.map(it -> ((EntitySchemaDecorator) it).getDelegate())
										.flatMap(it -> it.getReflectedReferenceFor(entitySchema.getName(), referenceName))
										.ifPresent(
											// if such is found, create a mutation to counterpart reflected reference
											// in the referenced entity
											rrsc -> {
												final boolean attributeVisibleFromOtherSide = rrsc.getAttribute(
													ram.getAttributeKey().attributeName()
												).isPresent();
												// create a mutation to counterpart reference in the referenced entity
												// but only if the attribute is visible from that point of view
												if (attributeVisibleFromOtherSide) {
													mutationCollector.addExternalMutation(
														entityMutationFactory.apply(
															new ReferenceAttributeMutation(
																new ReferenceKey(rrsc.getName(), this.entityPrimaryKey),
																toInvertedTypeAttributeMutation(ram.getAttributeMutation())
															)
														)
													);
												}
											}
										);
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Method verifies that all non-mandatory associated data are present on entity.
	 */
	private void verifyMandatoryAssociatedData(@Nonnull EntityBodyStoragePart entityStorageContainer) throws MandatoryAssociatedDataNotProvidedException {
		final EntitySchema entitySchema = schemaAccessor.get();
		final Collection<AssociatedDataSchema> nonNullableAssociatedData = entitySchema.getNonNullableAssociatedData();
		if (nonNullableAssociatedData.isEmpty()) {
			return;
		}

		final Set<AssociatedDataKey> availableAssociatedDataKeys = entityContainer.getAssociatedDataKeys();
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
					final EntitySchema entitySchema = schemaAccessor.get();
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
			throw new MandatoryAssociatedDataNotProvidedException(entityType, missingMandatedAssociatedData);
		}
	}

	/**
	 * Method verifies that all references keep the specified cardinality.
	 */
	private void verifyReferenceCardinalities(@Nullable ReferencesStoragePart referencesStorageContainer) throws ReferenceCardinalityViolatedException {
		final EntitySchemaContract entitySchema = schemaAccessor.get();
		final Map<String, ReferenceSchemaContract> references = entitySchema.getReferences();
		if (references.isEmpty()) {
			return;
		}
		final List<CardinalityViolation> violations = references
			.values()
			.stream()
			.flatMap(it -> {
				final int referenceCount = ofNullable(referencesStorageContainer)
					.map(ref -> ref.getReferencedIds(it.getName()).length)
					.orElse(0);
				if (!(it instanceof ReflectedReferenceSchemaContract rrsc) || rrsc.isReflectedReferenceAvailable()) {
					return switch (it.getCardinality()) {
						case ZERO_OR_MORE -> Stream.empty();
						case ZERO_OR_ONE -> referenceCount <= 1 ?
							Stream.empty() :
							Stream.of(new CardinalityViolation(it.getName(), it.getCardinality(), referenceCount));
						case ONE_OR_MORE -> referenceCount >= 1 ?
							Stream.empty() :
							Stream.of(new CardinalityViolation(it.getName(), it.getCardinality(), referenceCount));
						case EXACTLY_ONE -> referenceCount == 1 ?
							Stream.empty() :
							Stream.of(new CardinalityViolation(it.getName(), it.getCardinality(), referenceCount));
					};
				} else {
					return Stream.empty();
				}
			})
			.toList();
		if (!violations.isEmpty()) {
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
			.map(it -> getAttributeStoragePart(entityType, entityPrimaryKey, it))
			// get or create locale agnostic container (global one)
			.orElseGet(() -> getAttributeStoragePart(entityType, entityPrimaryKey));

		// now replace the attribute contents in the container
		attributesStorageContainer.upsertAttribute(
			attributeKey, attributeDefinition, attributeValue -> localMutation.mutateLocal(entitySchema, attributeValue)
		);

		// change in entity parts also change the entity itself (we need to update the version)
		if (attributesStorageContainer.isDirty()) {
			getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
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
		final AssociatedDataStoragePart associatedDataStorageContainer = getAssociatedDataStoragePart(entityType, entityPrimaryKey, associatedDataKey);
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
			getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
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
		final ReferencesStoragePart referencesStorageCnt = getReferencesStoragePart(entityType, entityPrimaryKey);
		// replace or add the mutated reference in the container
		final ReferenceContract updatedReference = referencesStorageCnt.replaceOrAddReference(
			localMutation.getReferenceKey(),
			referenceContract -> localMutation.mutateLocal(entitySchema, referenceContract)
		);
		// change in entity parts also change the entity itself (we need to update the version)
		if (referencesStorageCnt.isDirty()) {
			getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
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
		final EntityBodyStoragePart entityStorageContainer = getEntityStoragePart(entityType, entityPrimaryKey, requiresExisting);
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
		final PricesStoragePart pricesStorageContainer = getPriceStoragePart(entityType, entityPrimaryKey);
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
			getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
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
		final ReferencesStoragePart referencesStoragePart = getReferencesStoragePart(entityType, entityPrimaryKey);
		updatedReference.getAttributeLocales().forEach(locale -> {
			final AttributesStoragePart attributeStoragePart = getAttributeStoragePart(entityType, entityPrimaryKey, locale);
			if (attributeStoragePart.isEmpty() && !referencesStoragePart.isLocalePresent(locale)) {
				final EntityBodyStoragePart entityStoragePart = getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);
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

}
