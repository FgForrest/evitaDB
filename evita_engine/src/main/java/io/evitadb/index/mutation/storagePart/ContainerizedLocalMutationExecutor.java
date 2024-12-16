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
import io.evitadb.function.TriConsumer;
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
import java.util.function.Consumer;
import java.util.function.Function;
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
		@Nonnull TriConsumer<Serializable, Boolean, AttributeKey> missingAttributeHandler
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
		final Scope scope = this.entityContainer.getScope();
		if (this.entityContainer.isNew() && !this.entityRemovedEntirely) {
			// we need to check entire entity
			final List<Object> missingMandatedAttributes = new LinkedList<>();
			if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_ATTRIBUTES)) {
				verifyMandatoryAttributes(this.entityContainer, missingMandatedAttributes, true, true, mutationCollector);
			}
			if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFLECTED_REFERENCES)) {
				insertReflectedReferences(scope, this.referencesStorageContainer, missingMandatedAttributes, mutationCollector);
			}
			if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)) {
				verifyReferenceAttributes(scope, this.entityContainer, this.referencesStorageContainer, inputMutations, missingMandatedAttributes, mutationCollector);
			}

			if (!missingMandatedAttributes.isEmpty()) {
				throw new MandatoryAttributesNotProvidedException(this.schemaAccessor.get().getName(), missingMandatedAttributes);
			}
		} else if (this.entityRemovedEntirely) {
			removeReflectedReferences(scope, this.initialReferencesStorageContainer, mutationCollector);
		} else{
			if (this.initialEntityScope != this.entityContainer.getScope()) {
				// we need to drop all reflected references in old scope
				removeReflectedReferences(this.initialEntityScope, this.referencesStorageContainer, mutationCollector);
				// and insert new reflected references in new scope
				insertReflectedReferences(scope, this.referencesStorageContainer, List.of(), mutationCollector);
			}

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
			if (this.referencesStorageContainer != null && this.referencesStorageContainer.isDirty()) {
				if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFLECTED_REFERENCES)) {
					verifyReflectedReferences(scope, inputMutations, mutationCollector);
				}
				if (implicitMutationBehavior.contains(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES)) {
					verifyReferenceAttributes(
						scope,
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

	/*
		PROTECTED METHODS
	 */

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

	/*
		PRIVATE METHODS
	 */

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

		final TriConsumer<Serializable, Boolean, AttributeKey> missingAttributeHandler = (defaultValue, nullable, attributeKey) -> {
			Assert.isPremiseValid(attributeKey != null, "Attribute key must not be null!");
			Assert.isPremiseValid(nullable != null, "Nullable flag must not be null!");
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
	 * @param referencesStorageContainer The container for the references storage part
	 *                                   Can be null if there are no references to insert.
	 * @param missingMandatedAttributes  The list of missing mandated attributes.
	 * @param mutationCollector          The collector for mutations.
	 *                                   Cannot be null.
	 */
	private void insertReflectedReferences(
		@Nonnull Scope scope,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector
	) {
		final EntitySchema entitySchema = schemaAccessor.get();
		final CatalogSchema catalogSchema = catalogSchemaAccessor.get();

		setupReferencesOnEntityCreation(
			scope, catalogSchema, entitySchema,
			missingMandatedAttributes, mutationCollector
		);

		if (referencesStorageContainer != null) {
			propagateReferencesToEntangledEntities(
				scope, catalogSchema, entitySchema, referencesStorageContainer,
				InsertReferenceMutation::new, mutationCollector
			);
		}
	}

	/**
	 * Updates all entities that are entangled with the provided {@link CatalogSchema} and {@link EntitySchema}.
	 *
	 * @param catalogSchema             the catalog schema, must not be null
	 * @param entitySchema              the entity schema, must not be null
	 * @param missingMandatedAttributes The list of missing mandated attributes.
	 * @param mutationCollector         the collector for mutations, must not be null
	 */
	private void setupReferencesOnEntityCreation(
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
			final Optional<ReducedEntityIndex> referenceIndexWithPrimaryReferences;
			final Optional<DataStoreReader> referenceIndexDataStoreReader;
			// find reflected schema definition (if any)
			if (referenceSchema instanceof ReflectedReferenceSchema rrs) {
				// access the data store reader of referenced collection
				referenceIndexDataStoreReader = ofNullable(this.dataStoreReaderAccessor.apply(referencedEntityType));
				referenceIndexWithPrimaryReferences = referenceIndexDataStoreReader
					.map(
						targetDataStoreReader ->
							// we need to access index in target entity collection, because we need to retrieve index with primary references
							targetDataStoreReader.getIndexIfExists(
								new EntityIndexKey(
									EntityIndexType.REFERENCED_ENTITY,
									scope,
									// we need to use this reflected reference name
									new ReferenceKey(rrs.getReflectedReferenceName(), this.entityPrimaryKey)
								),
								entityIndexKey -> null
							)
					);
			} else {
				final Optional<ReflectedReferenceSchema> rrs = catalogSchema.getEntitySchema(referencedEntityType)
					.flatMap(it -> ((EntitySchemaDecorator) it).getDelegate().getReflectedReferenceFor(entitySchema.getName(), referenceSchema.getName()));
				referenceIndexDataStoreReader = of(this.dataStoreReader);
				referenceIndexWithPrimaryReferences = rrs.map(ReferenceSchema::getName)
					// we need to use this schema name and current data store reader, because we need to retrieve index with primary references
					.map(__unused -> new ReferenceKey(entitySchema.getName(), this.entityPrimaryKey))
					.map(referenceKey -> dataStoreReader.getIndexIfExists(
							new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, referenceKey),
							entityIndexKey -> null
						)
					);
			}

			// if the target entity and reference schema exists, and the index contains any indexed data,
			// set-up all reflected references
			referenceIndexWithPrimaryReferences
				.ifPresent(
					it -> createAndRegisterReferencePropagationMutation(
						(ReferenceSchema) referenceSchema,
						it,
						mutationCollector,
						InsertReferenceMutation::new,
						referenceKeys -> {
							final ReferenceBlock referenceBlock = new ReferenceBlock(
								catalogSchema,
								this.entityContainer.getLocales(),
								entitySchema,
								(ReferenceSchema) referenceSchema,
								// create a new attribute value provider for the reflected reference
								new ReferencedEntityAttributeValueProvider(
									catalogVersion,
									this.entityPrimaryKey,
									referenceKeys,
									referenceIndexDataStoreReader
										.orElseThrow(() -> new GenericEvitaInternalError("Target referenced index data store reader should be known here!"))
								)
							);
							Arrays.stream(referenceBlock.getAttributeSupplier().get())
								.forEach(mutationCollector::addLocalMutation);
							// register missing attributes if any
							final Set<Object> missingRequiredAttributes = referenceBlock.getMissingMandatedAttributes();
							if (!missingRequiredAttributes.isEmpty()) {
								missingMandatedAttributes.addAll(
									missingRequiredAttributes
								);
							}
						}
					)
				);
		}
	}

	/**
	 * Verifies the reflected references of the input mutations and collects them using the provided mutation collector.
	 * This method checks each mutation in the input list and determines if it is an InsertReferenceMutation or a RemoveReferenceMutation.
	 * For InsertReferenceMutation, it propagates the reference modification by collecting all similar mutations and
	 * creating a new mutation object. For RemoveReferenceMutation, it follows the same process.
	 *
	 * @param inputMutations    A list of input mutations to verify the reflected references.
	 * @param mutationCollector The mutation collector used to collect the verified reflected references.
	 */
	private void verifyReflectedReferences(
		@Nonnull Scope scope,
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
						scope, catalogSchema, entitySchema, thisReferenceName,
						() -> new ReferenceBlock(
							catalogSchema,
							this.entityContainer.getLocales(),
							entitySchema,
							entitySchema.getReferenceOrThrowException(thisReferenceName),
							new MutationAttributeValueProvider(
								this.entityPrimaryKey,
								irm,
								currentIndex,
								processedMutations,
								inputMutations
							)
						),
						mutationCollector,
						InsertReferenceMutation::new
					);
				} else if (inputMutation instanceof RemoveReferenceMutation rrm) {
					final ReferenceKey referenceKey = rrm.getReferenceKey();
					final int currentIndex = i;
					final String thisReferenceName = referenceKey.referenceName();
					propagateReferenceModification(
						scope, catalogSchema, entitySchema, thisReferenceName,
						() -> new ReferenceBlock(
							catalogSchema,
							this.entityContainer.getLocales(),
							entitySchema,
							entitySchema.getReferenceOrThrowException(thisReferenceName),
							new MutationAttributeValueProvider(
								this.entityPrimaryKey,
								rrm,
								currentIndex,
								processedMutations,
								inputMutations
							)
						),
						mutationCollector,
						RemoveReferenceMutation::new
					);
				}
			}
		}
	}

	/**
	 * Removes references that are reflected in the provided entity and references storage containers.
	 *
	 * @param referencesStorageContainer the storage part containing the references data.
	 * @param mutationCollector          the collector that will gather all mutations applied during the process.
	 */
	private void removeReflectedReferences(
		@Nonnull Scope scope,
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull MutationCollector mutationCollector
	) {
		if (referencesStorageContainer != null) {
			propagateReferencesToEntangledEntities(
				scope,
				catalogSchemaAccessor.get(),
				schemaAccessor.get(),
				referencesStorageContainer,
				RemoveReferenceMutation::new,
				mutationCollector
			);
		}
	}

	/**
	 * Propagates references to entangled entities based on the given schema and storage container.
	 * This method relies on the fact that references are ordered by reference key.
	 *
	 * @param catalogSchema              The catalog schema.
	 * @param entitySchema               The entity schema.
	 * @param referencesStorageContainer The container holding references. Cannot be null.
	 * @param mutationFactory            The factory to create mutations. Cannot be null.
	 * @param mutationCollector          The mutation collector to accumulate mutations. Cannot be null.
	 */
	private void propagateReferencesToEntangledEntities(
		@Nonnull Scope scope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull ReferencesStoragePart referencesStorageContainer,
		@Nonnull Function<ReferenceKey, ReferenceMutation<?>> mutationFactory,
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
					scope,
					catalogSchema,
					entitySchema,
					thisReferenceName,
					() -> {
						final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(thisReferenceName);
						// lets collect all primary keys of the reference with the same name into a bitmap
						if (currentIndex + 1 < references.length) {
							for (int j = currentIndex + 1; j < references.length; j++) {
								if (!thisReferenceName.equals(references[j].getReferenceName())) {
									return new ReferenceBlock(
										catalogSchema, this.entityContainer.getLocales(), entitySchema, referenceSchema,
										new ReferenceAttributeValueProvider(this.entityPrimaryKey, Arrays.copyOfRange(references, currentIndex, j))
									);
								}
							}
							return new ReferenceBlock(
								catalogSchema, this.entityContainer.getLocales(), entitySchema, referenceSchema,
								new ReferenceAttributeValueProvider(this.entityPrimaryKey, Arrays.copyOfRange(references, currentIndex, references.length))
							);
						} else {
							return new ReferenceBlock(
								catalogSchema, this.entityContainer.getLocales(), entitySchema, referenceSchema,
								new ReferenceAttributeValueProvider(this.entityPrimaryKey, reference)
							);
						}
					},
					mutationCollector,
					mutationFactory
				);
			}
		}
	}

	/**
	 * Creates and registers insert reference mutation for currently processed entity into mutation collector.
	 *
	 * @param referenceSchema                     the reference schema, must not be {@code null}
	 * @param referenceIndexWithPrimaryReferences the index containing primary references
	 * @param mutationCollector                   the mutation collector, must not be {@code null}
	 * @param eachReferenceConsumer               consumer that is invoked with each created reference
	 */
	private static void createAndRegisterReferencePropagationMutation(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReducedEntityIndex referenceIndexWithPrimaryReferences,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull Function<ReferenceKey, ReferenceMutation<?>> mutationFactory,
		@Nonnull Consumer<ReferenceKey[]> eachReferenceConsumer
	) {
		// for each such entity create internal (reflected) reference to it
		final Bitmap referencingEntities = referenceIndexWithPrimaryReferences.getAllPrimaryKeys();
		final ReferenceKey[] referenceKeys = new ReferenceKey[referencingEntities.size()];
		int index = -1;
		final OfInt it = referencingEntities.iterator();
		while (it.hasNext()) {
			int epk = it.nextInt();
			final ReferenceKey refKey = new ReferenceKey(referenceSchema.getName(), epk);
			mutationCollector.addLocalMutation(mutationFactory.apply(refKey));
			referenceKeys[++index] = refKey;
		}
		eachReferenceConsumer.accept(referenceKeys);
	}

	/**
	 * Propagates reference mutation to the external entity one or more references based on the provided schema
	 * and reference parameters.
	 *
	 * @param catalogSchema          the catalog schema, must not be null
	 * @param entitySchema           the entity schema, must not be null
	 * @param referenceName          the name of the reference, must not be null
	 * @param referenceBlockSupplier supplier for primary keys, must not be null
	 * @param mutationCollector      collector for mutations, must not be null
	 * @param mutationFactory        function that creates the propagated mutation
	 */
	private void propagateReferenceModification(
		@Nonnull Scope scope,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull String referenceName,
		@Nonnull Supplier<ReferenceBlock> referenceBlockSupplier,
		@Nonnull MutationCollector mutationCollector,
		@Nonnull Function<ReferenceKey, ReferenceMutation<?>> mutationFactory
	) {
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		// access the data store reader of referenced collection
		final DataStoreReader dataStoreReader = this.dataStoreReaderAccessor.apply(referencedEntityType);
		// and if such is found (collection might not yet exist, but we can still reference to it)
		if (dataStoreReader != null) {
			final Optional<String> referencedSchemaRef = catalogSchema.getEntitySchema(referencedEntityType)
				.map(it -> ((EntitySchemaDecorator) it).getDelegate())
				.flatMap(
					it -> referenceSchema instanceof ReflectedReferenceSchema rrs ?
						of(it.getReferenceOrThrowException(rrs.getReflectedReferenceName())) :
						it.getReflectedReferenceFor(entitySchema.getName(), referenceName)
				)
				.filter(theSchema -> theSchema.isIndexedInScope(scope))
				.map(ReferenceSchema::getName);
			// if there is any reflected reference schemas
			if (referencedSchemaRef.isPresent()) {
				// lets collect all primary keys of the reference with the same name into a bitmap
				final ReferenceBlock referenceBlock = referenceBlockSupplier.get();
				final GlobalEntityIndex globalIndex = dataStoreReader.getIndexIfExists(
					new EntityIndexKey(EntityIndexType.GLOBAL, scope),
					entityIndexKey -> null
				);
				// if global index is found there is at least one entity of such type
				final RoaringBitmap referencedPrimaryKeys = referenceBlock.getReferencedPrimaryKeys();
				if (globalIndex != null) {
					// but we need to match only those our entity refers to via this reference
					final RoaringBitmap existingEntityPks = RoaringBitmap.and(
						referencedPrimaryKeys,
						getRoaringBitmap(globalIndex.getAllPrimaryKeys())
					);
					// and for all of those create missing references
					final String reflectedReferenceName = referencedSchemaRef.get();
					final PeekableIntIterator existingPrimaryKeysIterator = existingEntityPks.getIntIterator();
					while (existingPrimaryKeysIterator.hasNext()) {
						int epk = existingPrimaryKeysIterator.next();
						// but we need to match only those our entity refers to via this reference and doesn't exist
						final ReducedEntityIndex referenceIndex = dataStoreReader.getIndexIfExists(
							new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, new ReferenceKey(reflectedReferenceName, epk)),
							entityIndexKey -> null
						);
						if (referenceIndex == null || !referenceIndex.getAllPrimaryKeys().contains(this.entityPrimaryKey)) {
							// if the reference is not present, we need to create it
							final ReferenceMutation<?> baseReferenceMutation = mutationFactory.apply(
								new ReferenceKey(reflectedReferenceName, this.entityPrimaryKey)
							);
							mutationCollector.addExternalMutation(
								new ServerEntityUpsertMutation(
									referencedEntityType, epk, EntityExistence.MUST_EXIST,
									EnumSet.of(ImplicitMutationBehavior.GENERATE_REFERENCE_ATTRIBUTES),
									true,
									true,
									baseReferenceMutation instanceof InsertReferenceMutation ?
										ArrayUtils.mergeArrays(
											new LocalMutation[]{baseReferenceMutation},
											referenceBlock.getAttributeSupplier().get()
										) :
										new LocalMutation[]{baseReferenceMutation}
								)
							);
						}
					}
				} else if (referenceSchema instanceof ReflectedReferenceSchema && !referencedPrimaryKeys.isEmpty()) {
					throw new EntityMissingException(
						referencedEntityType, referencedPrimaryKeys.toArray(),
						"Cannot set up main reference via reflected reference with name `" + referenceName + "`!"
					);
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
					final TriConsumer<Serializable, Boolean, AttributeKey> missingAttributeHandler = (defaultValue, nullable, attributeKey) -> {
						Assert.isPremiseValid(attributeKey != null, "Attribute key must not be null!");
						Assert.isPremiseValid(nullable != null, "Nullable flag must not be null!");
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
									// create a mutation to counterpart reference in the referenced entity
									mutationCollector.addExternalMutation(
										entityMutationFactory.apply(
											new ReferenceAttributeMutation(
												new ReferenceKey(rrsc.getReflectedReferenceName(), this.entityPrimaryKey),
												toInvertedTypeAttributeMutation(ram.getAttributeMutation())
											)
										)
									);
								} else {
									// otherwise check whether there is reflected reference in the referenced entity
									// that relates to our standard reference
									catalogSchema.getEntitySchema(referenceSchema.getReferencedEntityType())
										.map(it -> ((EntitySchemaDecorator) it).getDelegate())
										.flatMap(it -> it.getReflectedReferenceFor(entitySchema.getName(), referenceName))
										.ifPresent(
											// if such is found, create a mutation to counterpart reflected reference
											// in the referenced entity
											rrsc -> mutationCollector.addExternalMutation(
												entityMutationFactory.apply(
													new ReferenceAttributeMutation(
														new ReferenceKey(rrsc.getName(), this.entityPrimaryKey),
														toInvertedTypeAttributeMutation(ram.getAttributeMutation())
													)
												)
											)
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

}
