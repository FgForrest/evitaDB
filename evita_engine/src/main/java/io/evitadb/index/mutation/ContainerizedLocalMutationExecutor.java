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

package io.evitadb.index.mutation;

import com.carrotsearch.hppc.IntHashSet;
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
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutationExecutorWithImplicitMutations;
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
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.transaction.stage.mutation.VerifiedEntityUpsertMutation;
import io.evitadb.dataType.array.CompositeObjectArray;
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
import io.evitadb.store.entity.model.entity.price.MinimalPriceInternalIdContainer;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.AbstractEntityStorageContainerAccessor;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.*;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.buildWriter;
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
	implements LocalMutationExecutorWithImplicitMutations, WritableEntityStorageContainerAccessor {
	public static final String ERROR_SAME_KEY_EXPECTED = "Expected same primary key here!";
	private static final AttributeValue[] EMPTY_ATTRIBUTES = new AttributeValue[0];
	@Nonnull
	private final EntityExistence requiresExisting;
	private final int entityPrimaryKey;
	@Nonnull
	private final String entityType;
	@Nonnull private final Supplier<CatalogSchema> catalogSchemaAccessor;
	@Nonnull private final Supplier<EntitySchema> schemaAccessor;
	@Nonnull private final Function<String, DataStoreReader> dataStoreReaderAccessor;
	private final boolean removeOnly;
	private EntityBodyStoragePart entityContainer;
	private PricesStoragePart pricesContainer;
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
		return Arrays.stream(
				ofNullable(storagePart)
					.map(AttributesStoragePart::getAttributes)
					.orElse(EMPTY_ATTRIBUTES)
			)
			.filter(Droppable::exists)
			.map(AttributeValue::key)
			.collect(Collectors.toSet());
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

	public ContainerizedLocalMutationExecutor(
		@Nonnull DataStoreMemoryBuffer storageContainerBuffer,
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EntityExistence requiresExisting,
		@Nonnull Supplier<CatalogSchema> catalogSchemaAccessor,
		@Nonnull Supplier<EntitySchema> schemaAccessor,
		@Nonnull Function<String, DataStoreReader> dataStoreReaderAccessor,
		boolean removeOnly
	) {
		super(catalogVersion, storageContainerBuffer, schemaAccessor);
		this.catalogSchemaAccessor = catalogSchemaAccessor;
		this.schemaAccessor = schemaAccessor;
		this.dataStoreReaderAccessor = dataStoreReaderAccessor;
		this.entityPrimaryKey = entityPrimaryKey;
		this.entityType = schemaAccessor.get().getName();
		this.entityContainer = getEntityStoragePart(entityType, entityPrimaryKey, requiresExisting);
		this.requiresExisting = requiresExisting;
		this.removeOnly = removeOnly;
		if (this.removeOnly) {
			this.entityContainer.markForRemoval();
		}
	}

	@Override
	public void applyMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		final EntitySchemaContract entitySchema = schemaAccessor.get();
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
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + localMutation.getClass());
		}
	}

	@Override
	public void commit() {
		// now store all dirty containers
		getChangedEntityStorageParts()
			.forEach(part -> {
				if (part.isEmpty()) {
					this.storageContainerBuffer.removeByPrimaryKey(
						catalogVersion,
						part.getStoragePartPK(),
						part.getClass()
					);
				} else {
					Assert.isPremiseValid(
						!removeOnly,
						"Only removal operations are expected to happen!"
					);
					this.storageContainerBuffer.update(catalogVersion, part);
				}
			});
	}

	@Override
	public void rollback() {
		// do nothing all containers will be discarded along with this instance
	}

	@Nonnull
	@Override
	public ImplicitMutations popImplicitMutations(@Nonnull List<? extends LocalMutation<?, ?>> inputMutations) {
		// apply entity consistency checks and returns list of mutations that needs to be also applied in order
		// maintain entity consistent (i.e. init default values).
		final EntityBodyStoragePart entityStorageContainer = this.getEntityStorageContainer();
		final MutationCollector mutationCollector = new MutationCollector();
		if (entityStorageContainer.isNew() && !entityStorageContainer.isValidated() && !entityContainer.isMarkedForRemoval()) {
			// we need to check entire entity
			final List<Object> missingMandatedAttributes = new LinkedList<>();
			verifyMandatoryAttributes(entityStorageContainer, missingMandatedAttributes, true, true, mutationCollector);
			insertReflectedReferences(referencesStorageContainer, mutationCollector);
			verifyReferenceMandatoryAttributes(entityContainer, referencesStorageContainer, missingMandatedAttributes, mutationCollector);

			if (!missingMandatedAttributes.isEmpty()) {
				throw new MandatoryAttributesNotProvidedException(schemaAccessor.get().getName(), missingMandatedAttributes);
			}

			verifyMandatoryAssociatedData(entityStorageContainer);
			verifyReferenceCardinalities(referencesStorageContainer);
			entityStorageContainer.setValidated(true);
		} else if (entityStorageContainer.isMarkedForRemoval()) {
			removeReflectedReferences(entityContainer, referencesStorageContainer, mutationCollector);
		} else {
			final List<Object> missingMandatedAttributes = new LinkedList<>();
			// we need to check only changed parts
			verifyMandatoryAttributes(
				entityStorageContainer,
				missingMandatedAttributes,
				ofNullable(this.globalAttributesStorageContainer).map(AttributesStoragePart::isDirty).orElse(false),
				ofNullable(this.languageSpecificAttributesContainer).map(it -> it.values().stream().anyMatch(AttributesStoragePart::isDirty)).orElse(false),
				mutationCollector
			);
			if (referencesStorageContainer != null && referencesStorageContainer.isDirty()) {
				verifyReflectedReferences(entityContainer, referencesStorageContainer, inputMutations, mutationCollector);
				verifyReferenceMandatoryAttributes(entityContainer, referencesStorageContainer, missingMandatedAttributes, mutationCollector);
			}

			if (!missingMandatedAttributes.isEmpty()) {
				throw new MandatoryAttributesNotProvidedException(schemaAccessor.get().getName(), missingMandatedAttributes);
			}

			verifyRemovedMandatoryAssociatedData();

			ofNullable(this.referencesStorageContainer)
				.filter(ReferencesStoragePart::isDirty)
				.ifPresent(this::verifyReferenceCardinalities);
		}
		return mutationCollector.toImplicitMutations();
	}

	@Override
	public void registerAssignedPriceId(@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey, @Nullable Integer innerRecordId, @Nonnull PriceInternalIdContainer priceId) {
		assertEntityTypeMatches(entityType);
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		if (assignedInternalPriceIdIndex == null) {
			assignedInternalPriceIdIndex = new HashMap<>();
		}
		assignedInternalPriceIdIndex.compute(
			priceKey,
			(thePriceKey, existingInternalPriceId) -> {
				final Integer newPriceId = Objects.requireNonNull(priceId.getInternalPriceId());
				Assert.isPremiseValid(
					existingInternalPriceId == null || Objects.equals(existingInternalPriceId, newPriceId),
					"Attempt to change already assigned price id!"
				);
				return newPriceId;
			}
		);
	}

	@Nonnull
	@Override
	public PriceInternalIdContainer findExistingInternalIds(@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey, @Nullable Integer innerRecordId) {
		assertEntityTypeMatches(entityType);
		Assert.isPremiseValid(entityPrimaryKey == this.entityPrimaryKey, ERROR_SAME_KEY_EXPECTED);
		Integer internalPriceId = assignedInternalPriceIdIndex == null ? null : assignedInternalPriceIdIndex.get(priceKey);
		if (internalPriceId == null) {
			final PricesStoragePart priceStorageContainer = getPriceStoragePart(entityType, entityPrimaryKey);
			return priceStorageContainer.findExistingInternalIds(priceKey);
		} else {
			return new MinimalPriceInternalIdContainer(internalPriceId);
		}
	}

	/*
		PROTECTED METHODS
	 */

	@Nonnull
	@Override
	public Set<Locale> getAddedLocales() {
		return addedLocales == null ? Collections.emptySet() : addedLocales;
	}

	@Nonnull
	@Override
	public Set<Locale> getRemovedLocales() {
		return removedLocales == null ? Collections.emptySet() : removedLocales;
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
		return this.referencesStorageContainer;
	}

	/*
		PRIVATE METHODS
	 */

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
					final EntityBodyStoragePart entityStoragePart = getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);
					if (entityStoragePart.addAttributeLocale(locale)) {
						registerAddedLocale(locale);
					}
				});
		} else if (attributeMutation instanceof RemoveAttributeMutation) {
			ofNullable(affectedAttribute.locale())
				.ifPresent(locale -> {
					final AttributesStoragePart attributeStoragePart = getAttributeStoragePart(entityType, entityPrimaryKey, locale);
					final ReferencesStoragePart referencesStoragePart = getReferencesStoragePart(entityType, entityPrimaryKey);

					if (attributeStoragePart.isEmpty() && !referencesStoragePart.isLocalePresent(locale)) {
						final EntityBodyStoragePart entityStoragePart = getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST);
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
		final EntitySchema entitySchema = schemaAccessor.get();
		final Set<AttributeKey> availableGlobalAttributes = checkGlobal ?
			collectAttributeKeys(this.globalAttributesStorageContainer) : Collections.emptySet();
		final Set<Locale> entityLocales = entityStorageContainer.getLocales();
		final Map<Locale, Set<AttributeKey>> availableLocalizedAttributes = checkLocalized ?
			entityLocales
				.stream()
				.collect(
					Collectors.toMap(
						Function.identity(),
						it -> collectAttributeKeys(getAttributeStoragePart(entityType, entityPrimaryKey, it))
					)
				) : Collections.emptyMap();

		final BiConsumer<Serializable, AttributeKey> missingAttributeHandler = (defaultValue, attributeKey) -> {
			if (defaultValue == null) {
				missingMandatedAttributes.add(attributeKey);
			} else {
				mutationCollector.addLocalMutation(
					new UpsertAttributeMutation(attributeKey, defaultValue)
				);
			}
		};

		entitySchema
			.getNonNullableAttributes()
			.forEach(attribute -> {
				final Serializable defaultValue = attribute.getDefaultValue();
				if (checkLocalized && attribute.isLocalized()) {
					entityLocales.stream()
						.map(locale -> new AttributeKey(attribute.getName(), locale))
						.flatMap(key -> ofNullable(availableLocalizedAttributes.get(key.locale()))
							.map(it -> it.contains(key)).orElse(false) ? Stream.empty() : Stream.of(key))
						.forEach(it -> missingAttributeHandler.accept(defaultValue, it));
				} else if (checkGlobal && !attribute.isLocalized()) {
					final AttributeKey attributeKey = new AttributeKey(attribute.getName());
					if (!availableGlobalAttributes.contains(attributeKey)) {
						missingAttributeHandler.accept(defaultValue, attributeKey);
					}
				}
			});
	}

	/**
	 * Method handles situation when new entity is created and fully set up. It:
	 *
	 * 1. creates new implicit references for each:
	 *    a) entity reflected reference when there is existing referenced entity with reference counterpart
	 *    b) entity reference when there is existing referenced entity with reflected reference counterpart
	 * 2. creates new entity upsert mutations with insert reference for each:
	 *    b) entity reflected reference which targets existing referenced entity
	 *    b) entity reference which targets existing referenced entity with reflected reference to our entity reference
	 *
	 * @param referencesStorageContainer The container for the references storage.
	 *                                   Can be null if there are no references to insert.
	 * @param mutationCollector          The collector for mutations.
	 *                                   Cannot be null.
	 */
	private void insertReflectedReferences(
		@Nullable ReferencesStoragePart referencesStorageContainer,
		@Nonnull MutationCollector mutationCollector
	) {
		final EntitySchema entitySchema = schemaAccessor.get();
		final CatalogSchema catalogSchema = catalogSchemaAccessor.get();

		// first iterate over all reflected reference schemas in this entity and setup reflections for them
		final Collection<ReferenceSchemaContract> referenceSchemas = entitySchema.getReferences().values();
		for (ReferenceSchemaContract referenceSchema : referenceSchemas) {
			final String referencedEntityType = referenceSchema.getReferencedEntityType();
			// access the data store reader of referenced collection
			final DataStoreReader dataStoreReader = this.dataStoreReaderAccessor.apply(referencedEntityType);
			// and if such is found (collection might not yet exists, but we can still reference to it)
			if (dataStoreReader != null) {
				// find reflected schema definition (if any)
				final Optional<String> reflectedReferenceSchema;
				if (referenceSchema instanceof ReflectedReferenceSchema rrs) {
					reflectedReferenceSchema = of(rrs.getReflectedReferenceName());
				} else {
					reflectedReferenceSchema = catalogSchema.getEntitySchema(referencedEntityType)
						.flatMap(it -> ((EntitySchemaDecorator) it).getDelegate().getReflectedReferenceFor(referenceSchema.getName()))
						.map(ReferenceSchema::getName);
				}
				// and create insert reference mutation in this entity for the referencing entities
				reflectedReferenceSchema
					.ifPresent(it -> createAndRegisterInsertReferenceMutation(
							dataStoreReader, referenceSchema, it, mutationCollector
						)
					);
			}
		}

		if (referencesStorageContainer != null) {
			// we can rely on a fact, that references are ordered by reference key
			String referenceName = null;
			final ReferenceContract[] references = referencesStorageContainer.getReferences();
			for (int i = 0; i < references.length; i++) {
				final ReferenceContract reference = references[i];
				final String thisReferenceName = reference.getReferenceName();
				// fast forward to reference with different name
				if (!Objects.equals(thisReferenceName, referenceName)) {
					referenceName = thisReferenceName;
					final int currentIndex = i;
					// setup references
					setupExternalReference(
						catalogSchema, entitySchema,
						thisReferenceName,
						() -> {
							// lets collect all primary keys of the reference with the same name into a bitmap
							final RoaringBitmapWriter<RoaringBitmap> writer = buildWriter();
							writer.add(reference.getReferencedPrimaryKey());
							for (int j = currentIndex + 1; j < references.length; j++) {
								final ReferenceContract nextReference = references[j];
								if (thisReferenceName.equals(nextReference.getReferenceName())) {
									writer.add(nextReference.getReferencedPrimaryKey());
								} else {
									break;
								}
							}
							return writer.get();
						},
						mutationCollector
					);
				}
			}
		}
	}

	/* TODO JNO - document me */
	private void verifyReflectedReferences(
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nullable ReferencesStoragePart referencesStoragePart,
		@Nonnull List<? extends LocalMutation<?, ?>> inputMutations,
		@Nonnull MutationCollector mutationCollector
	) throws MandatoryAttributesNotProvidedException {

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
					setupExternalReference(
						catalogSchema, entitySchema, thisReferenceName,
						() -> {
							// lets collect all primary keys of the insert reference with the same name into a bitmap
							final RoaringBitmapWriter<RoaringBitmap> writer = buildWriter();
							writer.add(irm.getReferenceKey().primaryKey());
							for (int j = currentIndex + 1; j < inputMutations.size(); j++) {
								final LocalMutation<?, ?> nextMutation = inputMutations.get(j);
								if (nextMutation instanceof InsertReferenceMutation nextIrm &&
										thisReferenceName.equals(nextIrm.getReferenceKey().referenceName())) {
									writer.add(nextIrm.getReferenceKey().primaryKey());
									processedMutations.add(j);
								}
							}
							return writer.get();
						},
						mutationCollector
					);
				} else if (inputMutation instanceof RemoveReferenceMutation rrm) {
					/* TODO JNO - Implement with tests */
				}
			}
		}

	}

	/* TODO JNO - document me */
	private void removeReflectedReferences(
		@Nonnull EntityBodyStoragePart entityContainer,
		@Nonnull ReferencesStoragePart referencesStorageContainer,
		@Nonnull MutationCollector mutationCollector
	) {


	}

	/**
	 * Creates and registers insert reference mutation for currently processed entity into mutation collector.
	 *
	 * @param dataStoreReader             the data store reader, must not be {@code null}
	 * @param referenceSchema             the reference schema, must not be {@code null}
	 * @param externalReferenceSchemaName the external reference schema name, can be {@code null}
	 * @param mutationCollector           the mutation collector, must not be {@code null}
	 */
	private void createAndRegisterInsertReferenceMutation(
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable String externalReferenceSchemaName,
		@Nonnull MutationCollector mutationCollector
	) {
		// access its reduced entity index for current entity
		final ReferenceKey referenceKey = new ReferenceKey(externalReferenceSchemaName, this.entityPrimaryKey);
		final ReducedEntityIndex reducedEntityIndex = dataStoreReader.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, referenceKey),
			entityIndexKey -> null
		);
		// and if such is found, there are entities referring to our entity
		if (reducedEntityIndex != null) {
			// for each such entity create internal (reflected) reference to it
			final Bitmap referencingEntities = reducedEntityIndex.getAllPrimaryKeys();
			final OfInt it = referencingEntities.iterator();
			while (it.hasNext()) {
				int epk = it.nextInt();
				mutationCollector.addLocalMutation(
					new InsertReferenceMutation(new ReferenceKey(referenceSchema.getName(), epk))
				);
			}
		}
	}

	/**
	 * Setup external entity one or more references based on the provided schema and reference parameters.
	 *
	 * @param catalogSchema     the catalog schema, must not be null
	 * @param entitySchema      the entity schema, must not be null
	 * @param referenceName     the name of the reference, must not be null
	 * @param primaryKeySupplier supplier for primary keys, must not be null
	 * @param mutationCollector collector for mutations, must not be null
	 */
	private void setupExternalReference(
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull EntitySchema entitySchema,
		@Nonnull String referenceName,
		@Nonnull Supplier<RoaringBitmap> primaryKeySupplier,
		@Nonnull MutationCollector mutationCollector
	) {
		final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		// access the data store reader of referenced collection
		final DataStoreReader dataStoreReader = this.dataStoreReaderAccessor.apply(referencedEntityType);
		// and if such is found (collection might not yet exist, but we can still reference to it)
		if (dataStoreReader != null) {
			final Optional<String> reflectedReferenceSchema = catalogSchema.getEntitySchema(referencedEntityType)
				.map(it -> ((EntitySchemaDecorator) it).getDelegate())
				.flatMap(
					it -> referenceSchema instanceof ReflectedReferenceSchema rrs ?
						of(rrs.getReflectedReferenceName()) :
						it.getReflectedReferenceFor(referenceName).map(ReferenceSchema::getName)
				);
			// if there is any reflected reference schemas
			if (reflectedReferenceSchema.isPresent()) {
				// lets collect all primary keys of the reference with the same name into a bitmap
				final RoaringBitmap referencePrimaryKeys = primaryKeySupplier.get();
				final GlobalEntityIndex globalIndex = dataStoreReader.getIndexIfExists(new EntityIndexKey(EntityIndexType.GLOBAL), entityIndexKey -> null);
				// if global index is found there is at least one entity of such type
				if (globalIndex != null) {
					// but we need to match only those our entity refers to via this reference
					final RoaringBitmap existingEntityPks = RoaringBitmap.and(
						referencePrimaryKeys,
						getRoaringBitmap(globalIndex.getAllPrimaryKeys())
					);
					// and for all of those create missing references
					final String reflectedReferenceName = reflectedReferenceSchema.get();
					for (Integer epk : existingEntityPks) {
						mutationCollector.addExternalMutation(
							new VerifiedEntityUpsertMutation(
								referencedEntityType, epk, EntityExistence.MUST_EXIST, VerifiedEntityUpsertMutation.ImplicitMutations.SKIP,
								new InsertReferenceMutation(
									new ReferenceKey(reflectedReferenceName, this.entityPrimaryKey)
								)
							)
						);
					}
				}
			}
		}
	}

	/**
	 * Method verifies that all non-mandatory attributes are present on entity references.
	 */
	private void verifyReferenceMandatoryAttributes(
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nullable ReferencesStoragePart referencesStoragePart,
		@Nonnull List<Object> missingMandatedAttributes,
		@Nonnull MutationCollector mutationCollector
	) throws MandatoryAttributesNotProvidedException {
		if (referencesStoragePart == null) {
			return;
		}

		final EntitySchema entitySchema = schemaAccessor.get();
		final Set<Locale> entityLocales = entityStorageContainer.getLocales();

		Arrays.stream(referencesStoragePart.getReferences())
			.filter(Droppable::exists)
			.collect(Collectors.groupingBy(ReferenceContract::getReferenceName))
			.forEach((referenceName, references) -> {
				final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
				for (ReferenceContract reference : references) {
					final Set<AttributeKey> availableAttributes = collectAttributeKeys(reference);
					final List<AttributeKey> missingReferenceMandatedAttribute = new LinkedList<>();
					final BiConsumer<Serializable, AttributeKey> missingAttributeHandler = (defaultValue, attributeKey) -> {
						if (defaultValue == null) {
							missingReferenceMandatedAttribute.add(attributeKey);
						} else {
							mutationCollector.addLocalMutation(
								new ReferenceAttributeMutation(
									reference.getReferenceKey(),
									new UpsertAttributeMutation(attributeKey, defaultValue)
								)
							);
						}
					};

					referenceSchema.getNonNullableAttributes()
						.forEach(attribute -> {
							final Serializable defaultValue = attribute.getDefaultValue();
							if (attribute.isLocalized()) {
								entityLocales.stream()
									.map(locale -> new AttributeKey(attribute.getName(), locale))
									.map(key -> availableAttributes.contains(key) ? null : key)
									.filter(Objects::nonNull)
									.forEach(it -> missingAttributeHandler.accept(defaultValue, it));
							} else {
								final AttributeKey attributeKey = new AttributeKey(attribute.getName());
								if (!availableAttributes.contains(attributeKey)) {
									missingAttributeHandler.accept(defaultValue, attributeKey);
								}
							}
						});

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
	 * Method verifies that all non-mandatory associated data are present on entity.
	 */
	private void verifyMandatoryAssociatedData(@Nonnull EntityBodyStoragePart entityStorageContainer) throws MandatoryAssociatedDataNotProvidedException {
		final EntitySchema entitySchema = schemaAccessor.get();
		final Set<AssociatedDataKey> availableAssociatedDataKeys = entityContainer.getAssociatedDataKeys();
		final Set<AssociatedDataKey> availableGlobalAssociatedDataKeys = availableAssociatedDataKeys
			.stream()
			.filter(it -> it.locale() == null)
			.collect(Collectors.toSet());
		final Set<Locale> entityLocales = entityStorageContainer.getLocales();
		final Map<Locale, Set<AssociatedDataKey>> availableLocalizedAssociatedDataKeys = entityLocales
			.stream()
			.collect(
				Collectors.toMap(
					Function.identity(),
					it -> availableAssociatedDataKeys.stream()
						.filter(key -> Objects.equals(it, key.locale()))
						.collect(Collectors.toSet())
				)
			);

		final List<AssociatedDataKey> missingMandatedAssociatedData = entitySchema.getNonNullableAssociatedData()
			.stream()
			.flatMap(associatedData -> {
				if (associatedData.isLocalized()) {
					return entityLocales.stream()
						.map(locale -> new AssociatedDataKey(associatedData.getName(), locale))
						.flatMap(key -> ofNullable(availableLocalizedAssociatedDataKeys.get(key.locale()))
							.map(it -> it.contains(key)).orElse(false) ? Stream.empty() : Stream.of(key));
				} else {
					final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedData.getName());
					return availableGlobalAssociatedDataKeys.contains(associatedDataKey) ? Stream.empty() : Stream.of(associatedDataKey);
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
		final List<CardinalityViolation> violations = entitySchema
			.getReferences()
			.values()
			.stream()
			.flatMap(it -> {
				final int referenceCount = ofNullable(referencesStorageContainer)
					.map(ref -> ref.getReferencedIds(it.getName()).length)
					.orElse(0);
				return switch (it.getCardinality()) {
					case ZERO_OR_MORE -> Stream.empty();
					case ZERO_OR_ONE ->
						referenceCount <= 1 ? Stream.empty() : Stream.of(new CardinalityViolation(it.getName(), it.getCardinality(), referenceCount));
					case ONE_OR_MORE ->
						referenceCount >= 1 ? Stream.empty() : Stream.of(new CardinalityViolation(it.getName(), it.getCardinality(), referenceCount));
					case EXACTLY_ONE ->
						referenceCount == 1 ? Stream.empty() : Stream.of(new CardinalityViolation(it.getName(), it.getCardinality(), referenceCount));
				};
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
		this.entityContainer = this.entityContainer == null ? new EntityBodyStoragePart(entityPrimaryKey) : this.entityContainer;
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
			priceKey -> ofNullable(assignedInternalPriceIdIndex).map(it -> it.get(priceKey)).orElse(null)
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
		final PricesStoragePart pricesStorageContainer = getPriceStoragePart(entityType, entityPrimaryKey);
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
			getEntityStoragePart(entityType, entityPrimaryKey, EntityExistence.MUST_EXIST).setDirty(true);
		}
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
		if (addedLocales == null) {
			addedLocales = new HashSet<>();
		}
		addedLocales.add(locale);
	}

	/**
	 * Registers the removed locale in the set of removed locales.
	 * If the removedLocales set is null, it initializes it as a new HashSet.
	 *
	 * @param locale the locale to be registered as removed
	 */
	private void registerRemovedLocale(@Nonnull Locale locale) {
		if (removedLocales == null) {
			removedLocales = new HashSet<>();
		}
		removedLocales.add(locale);
	}

	/**
	 * Allows collecting mutations in a mutable and lazy fashion.
	 */
	private static class MutationCollector {
		private static final LocalMutation[] NO_LOCAL_MUTATIONS = new LocalMutation[0];
		private static final EntityMutation[] NO_ENTITY_MUTATIONS = new EntityMutation[0];

		@SuppressWarnings("rawtypes")
		private CompositeObjectArray<LocalMutation> localMutations;
		private CompositeObjectArray<EntityMutation> externalMutations;

		/**
		 * Adds a local mutation to the mutation collector.
		 *
		 * @param mutation the local mutation to add
		 */
		public void addLocalMutation(@Nonnull LocalMutation<?, ?> mutation) {
			if (this.localMutations == null) {
				this.localMutations = new CompositeObjectArray<>(LocalMutation.class);
			}
			this.localMutations.add(mutation);
		}

		/**
		 * Adds an external mutation to the mutation collector.
		 *
		 * @param mutation the external mutation to add
		 */
		public void addExternalMutation(@Nonnull EntityMutation mutation) {
			if (this.externalMutations == null) {
				this.externalMutations = new CompositeObjectArray<>(EntityMutation.class);
			}
			this.externalMutations.add(mutation);
		}

		public ImplicitMutations toImplicitMutations() {
			return new ImplicitMutations(
				this.localMutations == null ? NO_LOCAL_MUTATIONS : this.localMutations.toArray(),
				this.externalMutations == null ? NO_ENTITY_MUTATIONS : this.externalMutations.toArray()
			);
		}

	}

}
