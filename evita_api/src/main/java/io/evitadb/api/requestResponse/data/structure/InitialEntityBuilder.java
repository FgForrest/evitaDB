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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException.Operation;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to create the entity.
 * Due to performance reasons (see DirectWriteOrOperationLog microbenchmark) there is special
 * implementation for the situation when entity is newly created. In this case we know everything
 * is new and we don't need to closely monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InitialEntityBuilder implements InternalEntityBuilder {
	@Serial private static final long serialVersionUID = -3674623071115207036L;

	/**
	 * Logical entity type (a.k.a. collection name) this builder creates.
	 * Matches {@link #schema} name and is kept for quick access.
	 */
	private final String type;
	/**
	 * Schema snapshot used to validate attributes, associated data, references and prices.
	 * For new entities we use lightweight {@link EntitySchema#_internalBuild(String)} when only type is known.
	 */
	private final EntitySchemaContract schema;
	/**
	 * Primary key of the entity if it is known upfront. For newly created entities it is usually {@code null}
	 * and will be generated by the persistence layer.
	 */
	private final Integer primaryKey;
	/**
	 * Target {@link Scope} for the entity. Defaults to {@link Scope#DEFAULT_SCOPE}.
	 */
	private Scope scope;
	/**
	 * Lazy-initialized builder for entity attributes.
	 */
	@Nullable private InitialEntityAttributesBuilder attributesBuilder;
	/**
	 * Lazy-initialized builder for associated data.
	 */
	@Nullable private InitialAssociatedDataBuilder associatedDataBuilder;
	/**
	 * Lazy-initialized builder for prices.
	 */
	@Nullable private InitialPricesBuilder pricesBuilder;
	/**
	 * Flat collection of all references added to the builder. It may contain duplicates
	 * when the reference cardinality allows them. The list preserves insertion order.
	 */
	@Nullable private List<ReferenceContract> referenceCollection;
	/**
	 * Index of references by {@link ReferenceKey}. If a key appears more than once, the value stored
	 * is a special sentinel {@link Entity#DUPLICATE_REFERENCE} to signal that duplicates exist and
	 * callers must fall back to scanning {@link #referenceCollection}.
	 */
	@Nullable private Map<ReferenceKey, ReferenceContract> references;
	/**
	 * Contains names of all reference names that have at least one reference in this instance.
	 */
	@Nullable private Map<String, Integer> referencesDefinedCount;
	/**
	 * Optional parent primary key when hierarchy is used, otherwise {@code null}.
	 */
	@Nullable private Integer parent;
	/**
	 * Internal sequence that is used to generate unique negative reference ids for references that
	 * don't have it assigned yet (which is none in the initial entity builder).
	 */
	private int lastLocallyAssignedReferenceId = 0;

	/**
	 * Creates a builder for a new entity using only its type. A lightweight schema snapshot is
	 * created internally via {@link EntitySchema#_internalBuild(String)}.
	 *
	 * @param type logical entity type (collection name)
	 */
	public InitialEntityBuilder(@Nonnull String type) {
		this.type = type;
		this.schema = EntitySchema._internalBuild(type);
		this.primaryKey = null;
		this.scope = Scope.DEFAULT_SCOPE;
		this.referencesDefinedCount = null;
	}

	/**
	 * Creates a builder for a new entity backed by a concrete {@link EntitySchemaContract}.
	 *
	 * @param schema schema to validate mutations against
	 */
	public InitialEntityBuilder(@Nonnull EntitySchemaContract schema) {
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = null;
		this.scope = Scope.DEFAULT_SCOPE;
		this.referencesDefinedCount = null;
	}

	/**
	 * Creates a builder for a new entity with a known primary key and only a type available.
	 *
	 * @param type       logical entity type (collection name)
	 * @param primaryKey fixed primary key to be used for the entity, may be {@code null}
	 */
	public InitialEntityBuilder(@Nonnull String type, @Nullable Integer primaryKey) {
		this.type = type;
		this.primaryKey = primaryKey;
		this.schema = EntitySchema._internalBuild(type);
		this.scope = Scope.DEFAULT_SCOPE;
		this.referencesDefinedCount = null;
	}

	/**
	 * Creates a builder for a new entity backed by a concrete schema with a known primary key.
	 *
	 * @param schema     schema to validate mutations against
	 * @param primaryKey fixed primary key to be used for the entity, may be {@code null}
	 */
	public InitialEntityBuilder(@Nonnull EntitySchemaContract schema, @Nullable Integer primaryKey) {
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = primaryKey;
		this.scope = Scope.DEFAULT_SCOPE;
		this.referencesDefinedCount = null;
	}

	/**
	 * Creates a builder and seeds it with the provided state. This factory is primarily used by
	 * deserializers or higher level APIs that already have all pieces computed.
	 *
	 * - All attributes and associated data are inserted via the corresponding initial builders.
	 * - References are collected into a list and indexed into a map; duplicate keys are marked by
	 * storing {@link Entity#DUPLICATE_REFERENCE} in the map and keeping all entries in the list.
	 * - Price inner record handling is set when provided, then all prices are upserted into the
	 * price builder.
	 * - Scope defaults to {@link Scope#DEFAULT_SCOPE} when {@code null}.
	 *
	 * @param entitySchema             schema that defines the entity
	 * @param primaryKey               optional fixed primary key
	 * @param scope                    initial scope; when {@code null}, {@link Scope#DEFAULT_SCOPE} is used
	 * @param attributeValues          attribute values to seed
	 * @param associatedDataValues     associated data values to seed
	 * @param referenceContracts       references to seed
	 * @param priceInnerRecordHandling optional inner record handling for prices
	 * @param prices                   prices to seed
	 */
	public InitialEntityBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Integer primaryKey,
		@Nullable Scope scope,
		@Nonnull Collection<AttributeValue> attributeValues,
		@Nonnull Collection<AssociatedDataValue> associatedDataValues,
		@Nonnull Collection<ReferenceContract> referenceContracts,
		@Nullable PriceInnerRecordHandling priceInnerRecordHandling,
		@Nonnull Collection<PriceContract> prices
	) {
		this.type = entitySchema.getName();
		this.schema = entitySchema;
		this.primaryKey = primaryKey;
		this.scope = scope == null ? Scope.DEFAULT_SCOPE : scope;
		this.attributesBuilder = attributeValues.isEmpty() ? null : new InitialEntityAttributesBuilder(this.schema);
		for (AttributeValue attributeValue : attributeValues) {
			final AttributeKey attributeKey = attributeValue.key();
			if (attributeKey.localized()) {
				getAttributesBuilder().setAttribute(
					attributeKey.attributeName(),
					attributeKey.localeOrThrowException(),
					attributeValue.value()
				);
			} else {
				getAttributesBuilder().setAttribute(
					attributeKey.attributeName(),
					attributeValue.value()
				);
			}
		}
		this.associatedDataBuilder = associatedDataValues.isEmpty() ? null : new InitialAssociatedDataBuilder(
			this.schema);
		for (AssociatedDataValue associatedDataValue : associatedDataValues) {
			final AssociatedDataKey associatedDataKey = associatedDataValue.key();
			if (associatedDataKey.localized()) {
				getAssociatedDataBuilder().setAssociatedData(
					associatedDataKey.associatedDataName(),
					associatedDataKey.localeOrThrowException(),
					associatedDataValue.value()
				);
			} else {
				getAssociatedDataBuilder().setAssociatedData(
					associatedDataKey.associatedDataName(),
					associatedDataValue.value()
				);
			}
		}
		if (priceInnerRecordHandling == null && prices.isEmpty()) {
			this.pricesBuilder = null;
		} else {
			this.pricesBuilder = new InitialPricesBuilder(this.schema);
			ofNullable(priceInnerRecordHandling)
				.ifPresent(this.pricesBuilder::setPriceInnerRecordHandling);
			for (PriceContract price : prices) {
				getPricesBuilder().setPrice(
					price.priceId(),
					price.priceList(),
					price.currency(),
					price.innerRecordId(),
					price.priceWithoutTax(),
					price.taxRate(),
					price.priceWithTax(),
					price.validity(),
					price.indexed()
				);
			}
		}

		this.referenceCollection = referenceContracts.isEmpty() ?
			null : new ArrayList<>(referenceContracts);
		this.referencesDefinedCount = referenceContracts.isEmpty() ?
			null : CollectionUtils.createLinkedHashMap(entitySchema.getReferences().size());
		this.references = referenceContracts.isEmpty() ?
			null :
			referenceContracts
				.stream()
				.collect(
					Collectors.toMap(
						ref -> {
							this.referencesDefinedCount.compute(
								ref.getReferenceName(),
								(k, v) -> v == null ? 1 : v + 1
							);
							return ref.getReferenceKey();
						},
						Function.identity(),
						(o, o2) -> {
							throw new IllegalStateException("Duplicate key " + o);
						},
						LinkedHashMap::new
					)
				);
	}

	@Override
	public boolean dropped() {
		return false;
	}

	@Override
	public int version() {
		return 1;
	}

	@Override
	@Nonnull
	public String getType() {
		return this.type;
	}

	@Override
	@Nonnull
	public EntitySchemaContract getSchema() {
		return this.schema;
	}

	@Override
	@Nullable
	public Integer getPrimaryKey() {
		return this.primaryKey;
	}

	@Override
	public boolean parentAvailable() {
		return true;
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		return ofNullable(this.parent)
			.map(it -> new EntityReferenceWithParent(this.type, it, null));
	}

	@Override
	public boolean referencesAvailable() {
		return true;
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return true;
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		return Objects.requireNonNullElse(this.referenceCollection, Collections.emptyList());
	}

	@Nonnull
	@Override
	public Set<String> getReferenceNames() {
		if (this.referencesDefinedCount == null) {
			return Collections.emptySet();
		} else {
			return this.referencesDefinedCount.keySet();
		}
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		if (this.referenceCollection == null) {
			return Collections.emptyList();
		} else {
			final List<ReferenceContract> references = new ArrayList<>(
				Math.max(16, this.referenceCollection.size() / 8));
			for (ReferenceContract it : this.referenceCollection) {
				if (Objects.equals(referenceName, it.getReferenceName())) {
					references.add(it);
				}
			}
			return references;
		}
	}

	/**
	 * Returns a reference by name and referenced entity id.
	 *
	 * Throws {@link ReferenceAllowsDuplicatesException} when duplicates are present for the key,
	 * because a single reference cannot be uniquely identified in that case.
	 */
	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		final ReferenceContract reference = this.references == null ?
			null : this.references.get(new ReferenceKey(referenceName, referencedEntityId));
		if (reference == null) {
			return empty();
		} else if (reference == Entity.DUPLICATE_REFERENCE || reference.getReferenceSchemaOrThrow().getCardinality().allowsDuplicates()) {
			throw new ReferenceAllowsDuplicatesException(referenceName, this.schema, Operation.READ);
		} else {
			return of(reference);
		}
	}

	/**
	 * Returns a reference by its {@link ReferenceKey}. When multiple references share the same key
	 * (duplicates are allowed by cardinality), the builder cannot return a unique result and throws
	 * {@link ReferenceAllowsDuplicatesException}. In that case use {@link #getReferences(ReferenceKey)}.
	 */
	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull ReferenceKey referenceKey)
		throws ContextMissingException, ReferenceNotFoundException {
		final ReferenceContract reference = this.references == null ? null : this.references.get(referenceKey);
		if (reference == null) {
			return empty();
		} else if (reference == Entity.DUPLICATE_REFERENCE || reference.getReferenceSchemaOrThrow().getCardinality().allowsDuplicates()) {
			throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), this.schema, Operation.READ);
		} else {
			return of(reference);
		}
	}

	@Nonnull
	@Override
	public List<ReferenceContract> getReferences(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		if (this.references == null || this.referenceCollection == null) {
			return Collections.emptyList();
		} else {
			final ReferenceContract reference = this.references.get(referenceKey);
			if (reference == Entity.DUPLICATE_REFERENCE) {
				// Multiple references share the same key. The map stores a sentinel only, so we must scan
				// the flat list to return all occurrences for the key. This is acceptable here because this
				// builder is optimized for initial write path where duplicates are uncommon.
				final List<ReferenceContract> result = new ArrayList<>(8);
				for (ReferenceContract it : this.referenceCollection) {
					if (it.getReferenceKey().equals(referenceKey)) {
						result.add(it);
					}
				}
				return result;
			} else if (reference == null) {
				return Collections.emptyList();
			} else {
				return Collections.singletonList(reference);
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	@Override
	public DataChunk<ReferenceContract> getReferenceChunk(
		@Nonnull String referenceName
	) throws ContextMissingException {
		return new PlainChunk<>(this.getReferences(referenceName));
	}

	/**
	 * Returns the union of all locales present in attributes and associated data currently staged
	 * in this builder. This is a convenience for constructing the final entity and for validation.
	 */
	@Nonnull
	public Set<Locale> getAllLocales() {
		return Stream.concat(
			             this.attributesBuilder == null ? Stream.empty() : this.attributesBuilder.getAttributeLocales().stream(),
			             this.associatedDataBuilder == null ?
				             Stream.empty() :
				             this.associatedDataBuilder.getAssociatedDataLocales().stream()
		             )
		             .collect(Collectors.toSet());
	}

	/**
	 * Returns the same set as {@link #getAllLocales()}.
	 *
	 * Kept for backwards compatibility and readability at call sites where the context is
	 * clearly about the entity-wide locales.
	 */
	@Nonnull
	public Set<Locale> getLocales() {
		return getAllLocales();
	}

	@Nonnull
	@Override
	public Scope getScope() {
		return this.scope;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName) {
		getAttributesBuilder().removeAttribute(attributeName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nullable T attributeValue) {
		getAttributesBuilder().setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nullable T[] attributeValue) {
		getAttributesBuilder().setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		getAttributesBuilder().removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		getAttributesBuilder().setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		getAttributesBuilder().setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		getAttributesBuilder().mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		getAssociatedDataBuilder().removeAssociatedData(associatedDataName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nullable T associatedDataValue) {
		getAssociatedDataBuilder().setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		getAssociatedDataBuilder().setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		getAssociatedDataBuilder().removeAssociatedData(associatedDataName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T associatedDataValue) {
		getAssociatedDataBuilder().setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		getAssociatedDataBuilder().setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		getAssociatedDataBuilder().mutateAssociatedData(mutation);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setScope(@Nonnull Scope scope) {
		this.scope = scope;
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setParent(int parentPrimaryKey) {
		this.parent = parentPrimaryKey;
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeParent() {
		this.parent = null;
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder updateReferences(
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull UnaryOperator<ReferenceBuilder> whichIs
	) {
		// an existing list of references was found - and we know it's duplicates
		final List<ReferenceExchange> updates = new ArrayList<>(8);
		// we need to traverse all references and filter those matching reference key and predicate
		final List<ReferenceContract> theReferenceCollection = getReferenceCollectionForUpdate();
		for (int i = 0; i < theReferenceCollection.size(); i++) {
			final ReferenceContract referenceContract = theReferenceCollection.get(i);
			if (filter.test(referenceContract)) {
				// if the predicate passes, we need to update the reference
				final ExistingReferenceBuilder refBuilder = new ExistingReferenceBuilder(
					referenceContract, this.schema);
				final ReferenceContract updatedReference = whichIs.apply(refBuilder).build();
				updates.add(new ReferenceExchange(i, updatedReference));
			}
		}
		// otherwise just replace the updated references in the list on found positions
		final Map<ReferenceKey, ReferenceContract> referenceIndex = getReferenceIndexForUpdate();
		for (ReferenceExchange update : updates) {
			theReferenceCollection.set(update.index(), update.updatedReference());
			referenceIndex.put(update.updatedReference().getReferenceKey(), update.updatedReference());
		}
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		return setUniqueReferenceInternal(
			getReferenceSchemaOrThrowException(referenceName),
			new ReferenceKey(referenceName, referencedPrimaryKey),
			null,
			null,
			null
		);
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName, int referencedPrimaryKey, @Nullable Consumer<ReferenceBuilder> whichIs) {
		return setUniqueReferenceInternal(
			getReferenceSchemaOrThrowException(referenceName),
			new ReferenceKey(referenceName, referencedPrimaryKey),
			null,
			null,
			whichIs
		);
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey
	) {
		return setUniqueReferenceInternal(
			getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality),
			new ReferenceKey(referenceName, referencedPrimaryKey),
			referencedEntityType,
			cardinality,
			null
		);
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrCreateImplicit(
			referenceName, referencedEntityType, cardinality
		);
		setUniqueReferenceInternal(
			referenceSchema,
			new ReferenceKey(referenceName, referencedPrimaryKey),
			referencedEntityType,
			cardinality,
			whichIs
		);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull UnaryOperator<ReferenceBuilder> whichIs
	) {
		return setReference(
			referenceName,
			null,
			null,
			referencedPrimaryKey,
			filter,
			whichIs
		);
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull UnaryOperator<ReferenceBuilder> whichIs
	) {
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
		final Map<ReferenceKey, ReferenceContract> theReferenceIndex = getReferenceIndexForUpdate();
		final ReferenceContract theReference = theReferenceIndex.get(referenceKey);
		final Optional<ReferenceSchemaContract> referenceSchema = getReferenceSchemaContract(referenceName);
		referenceSchema.ifPresent(
			theRefSchema -> assertReferenceSchemaCompatibility(theRefSchema, referencedEntityType, cardinality)
		);

		if (theReference == null) {
			// no existing reference was found - create brand new, and we know it's not duplicate
			final InitialReferenceBuilder builder = new InitialReferenceBuilder(
				this.schema,
				referenceSchema
					.orElseGet(() -> getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality)),
				referenceName, referencedPrimaryKey,
				getNextReferenceInternalId()
			);
			addReferenceInternal(whichIs.apply(builder).build());
			return this;
		} else if (theReference == Entity.DUPLICATE_REFERENCE) {
			// existing list of references was found - and we know it's duplicates
			final List<ReferenceExchange> updates = new LinkedList<>();
			// we need to traverse all references and filter those matching reference key and predicate
			final List<ReferenceContract> theReferenceCollection = getReferenceCollectionForUpdate();
			for (int i = 0; i < theReferenceCollection.size(); i++) {
				final ReferenceContract referenceContract = theReferenceCollection.get(i);
				if (referenceKey.equals(referenceContract.getReferenceKey()) && filter.test(referenceContract)) {
					// if the predicate passes, we need to update the reference
					final ExistingReferenceBuilder refBuilder = new ExistingReferenceBuilder(
						referenceContract,
						this.schema
					);
					final ReferenceContract updatedReference = whichIs.apply(refBuilder).build();
					updates.add(new ReferenceExchange(i, updatedReference));
				}
			}
			if (updates.isEmpty()) {
				// if no updates were made - it means that the predicate did not pass for any of the references
				// so we create another duplicate and add it to the list
				final InitialReferenceBuilder refBuilder = new InitialReferenceBuilder(
					this.schema,
					referenceSchema
						.orElseGet(() -> getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality)),
					referenceName,
					referencedPrimaryKey,
					getNextReferenceInternalId()
				);
				theReferenceCollection.add(whichIs.apply(refBuilder).build());
			} else {
				// otherwise just replace the updated references in the list on found positions
				for (ReferenceExchange update : updates) {
					theReferenceCollection.set(update.index(), update.updatedReference());
				}
			}
			return this;
		} else {
			final List<ReferenceContract> theReferenceCollection = getReferenceCollectionForUpdate();
			// we found exactly one reference to this particular reference key
			final ReferenceContract theFinalReference;
			if (filter.test(theReference)) {
				// if the predicate matches, we update the existing reference
				final ExistingReferenceBuilder refBuilder = new ExistingReferenceBuilder(theReference, this.schema);
				theFinalReference = whichIs.apply(refBuilder).build();
				theReferenceIndex.put(referenceKey, theFinalReference);
				theReferenceCollection.removeIf(examinedReference -> examinedReference == theReference);
			} else {
				ReferenceSchemaContract theReferenceSchema = referenceSchema
					.orElseGet(() -> getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality));
				final Cardinality schemaCardinality = theReferenceSchema.getCardinality();
				// we don't allow explicit cardinality change
				if (cardinality != null && cardinality != schemaCardinality) {
					throw new InvalidMutationException(
						"The reference `" + referenceName + "` is already defined to have `" +
							theReferenceSchema.getCardinality() + "` cardinality, cannot change it to `" + cardinality + "` by data update!"
					);
				}
				// but we allow implicit cardinality widening when needed
				if (!schemaCardinality.allowsDuplicates()) {
					if (getSchema().allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
						theReferenceSchema = promoteDuplicateCardinality(theReferenceSchema, referenceKey);
					} else {
						throw new InvalidMutationException(
							"The reference `" + referenceName + "` is defined to have `" +
								theReferenceSchema.getCardinality() + "` cardinality, cannot add duplicate reference to it!"
						);
					}
				}
				// otherwise we create a new reference, which makes existing and new one duplicates
				final InitialReferenceBuilder refBuilder = new InitialReferenceBuilder(
					this.schema,
					theReferenceSchema,
					referenceName,
					referencedPrimaryKey,
					getNextReferenceInternalId()
				);
				Objects.requireNonNull(this.referencesDefinedCount)
				       .computeIfPresent(referenceName, (k, v) -> v + 1);
				theFinalReference = whichIs.apply(refBuilder).build();
				// we're adding a new reference with the same key - mark as duplicate
				theReferenceIndex.put(referenceKey, Entity.DUPLICATE_REFERENCE);
			}
			theReferenceCollection.add(theFinalReference);
			return this;
		}
	}

	@Nonnull
	@Override
	public EntityBuilder removeReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		if (this.references != null) {
			final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
			final ReferenceContract removedReference = this.references.remove(referenceKey);
			if (removedReference == Entity.DUPLICATE_REFERENCE) {
				this.references.put(referenceKey, Entity.DUPLICATE_REFERENCE);
				// removing duplicates this way is not supported - caller must use filter variant
				throw new ReferenceAllowsDuplicatesException(referenceName, this.schema, Operation.WRITE);
			} else if (removedReference != null) {
				getReferenceCollectionForUpdate().remove(removedReference);
			}
			Objects.requireNonNull(this.referencesDefinedCount)
				.computeIfPresent(referenceName, (k, v) -> v > 1 ? v - 1 : null);
		}
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReference(@Nonnull ReferenceKey referenceKey) throws ReferenceNotKnownException {
		if (this.references != null) {
			final String referenceName = referenceKey.referenceName();
			final ReferenceContract removedReference = this.references.remove(referenceKey);
			if (removedReference == Entity.DUPLICATE_REFERENCE) {
				if (referenceKey.isUnknownReference()) {
					this.references.put(referenceKey, Entity.DUPLICATE_REFERENCE);
					// removing duplicates this way is not supported - caller must use filter variant
					throw new ReferenceAllowsDuplicatesException(
						referenceName, this.schema, Operation.WRITE);
				} else {
					int duplicateCounter = 0;
					ReferenceContract remainingOccurence = null;
					final Iterator<ReferenceContract> it = getReferenceCollectionForUpdate().iterator();
					while (it.hasNext()) {
						final ReferenceContract reference = it.next();
						if (referenceKey.equals(reference.getReferenceKey())) {
							if (reference.getReferenceKey().internalPrimaryKey() == referenceKey.internalPrimaryKey()) {
								Objects.requireNonNull(this.referencesDefinedCount)
								       .computeIfPresent(referenceName, (k, v) -> v > 1 ? v - 1 : null);
								it.remove();
							} else if (remainingOccurence == null) {
								remainingOccurence = reference;
								duplicateCounter = 1;
							} else {
								duplicateCounter++;
							}
						}
					}
					if (duplicateCounter == 1) {
						// only one occurrence remains - put it back as the single one
						this.references.put(referenceKey, remainingOccurence);
					} else if (duplicateCounter > 1) {
						// more than one occurrence remains - keep the duplicate marker
						this.references.put(referenceKey, Entity.DUPLICATE_REFERENCE);
					}
				}
			} else if (removedReference != null) {
				getReferenceCollectionForUpdate().remove(removedReference);
				Objects.requireNonNull(this.referencesDefinedCount)
				       .computeIfPresent(referenceName, (k, v) -> v > 1 ? v - 1 : null);
			}
		}
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName, int referencedPrimaryKey) {
		if (this.references != null) {
			// remove all references with this key
			final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
			this.references.remove(referenceKey);
			getReferenceCollectionForUpdate()
				.removeIf(examinedReference -> {
					final boolean remove = examinedReference.getReferenceKey().equals(referenceKey);
					if (remove) {
						Objects.requireNonNull(this.referencesDefinedCount)
						       .computeIfPresent(referenceName, (k, v) -> v > 1 ? v - 1 : null);
					}
					return remove;
				});
		}
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName) {
		if (this.references != null) {
			final Iterator<ReferenceContract> it = getReferenceCollectionForUpdate().iterator();
			while (it.hasNext()) {
				final ReferenceContract reference = it.next();
				if (referenceName.equals(reference.getReferenceName())) {
					it.remove();
					// we can remove here, because even if the reference is duplicated, this method would eventually
					// remove all the duplicates anyway
					this.references.remove(reference.getReferenceKey());
				}
			}
			Objects.requireNonNull(this.referencesDefinedCount)
			       .remove(referenceName);
		}
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName, @Nonnull Predicate<ReferenceContract> filter) {
		final Predicate<ReferenceContract> nameFilter = reference -> referenceName.equals(reference.getReferenceName());
		return this.removeReferences(nameFilter.and(filter));
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull Predicate<ReferenceContract> filter) {
		if (this.references != null) {
			Set<ReferenceKey> affectedKeys = null;
			final List<ReferenceContract> referenceCollection = getReferenceCollectionForUpdate();
			final Iterator<ReferenceContract> it = referenceCollection.iterator();

			while (it.hasNext()) {
				final ReferenceContract reference = it.next();
				if (filter.test(reference)) {
					it.remove();
					Objects.requireNonNull(this.referencesDefinedCount)
					       .computeIfPresent(reference.getReferenceName(), (k, v) -> v > 1 ? v - 1 : null);
					final ReferenceKey key = reference.getReferenceKey();
					final ReferenceContract removed = this.references.remove(key);
					// if it was duplicate, remember the key for later reconciliation
					if (removed == Entity.DUPLICATE_REFERENCE) {
						if (affectedKeys == null) {
							affectedKeys = new HashSet<>(16);
						}
						affectedKeys.add(key);
					}
				}
			}

			// Reconcile only keys that were duplicates
			if (affectedKeys != null && !affectedKeys.isEmpty()) {
				final Map<ReferenceKey, Integer> counts = new HashMap<>(affectedKeys.size() << 1);
				final Map<ReferenceKey, ReferenceContract> singleCandidate = new HashMap<>(affectedKeys.size() << 1);

				// Count remaining occurrences for affected keys and keep the single candidate if count == 1
				for (ReferenceContract ref : referenceCollection) {
					final ReferenceKey key = ref.getReferenceKey();
					if (affectedKeys.contains(key)) {
						final int newCount = counts.getOrDefault(key, 0) + 1;
						counts.put(key, newCount);
						if (newCount == 1) {
							singleCandidate.put(key, ref);
						} else if (newCount == 2) {
							// no longer single, drop candidate to avoid stale reference
							singleCandidate.remove(key);
						}
					}
				}

				// Rebuild reference index for affected keys
				for (ReferenceKey key : affectedKeys) {
					final int c = counts.getOrDefault(key, 0);
					if (c == 1) {
						this.references.put(key, singleCandidate.get(key));
					} else if (c > 1) {
						this.references.put(key, Entity.DUPLICATE_REFERENCE);
					}
					// if c == 0, nothing remains for that key
				}
			}
		}
		return this;
	}

	@Override
	public int getNextReferenceInternalId() {
		return --this.lastLocallyAssignedReferenceId;
	}

	@Override
	public void addOrReplaceReferenceMutations(@Nonnull ReferenceBuilder referenceBuilder) {
		addReferenceInternal(referenceBuilder.build());
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean indexed
	) {
		getPricesBuilder().setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		getPricesBuilder().setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, DateTimeRange validity, boolean indexed
	) {
		getPricesBuilder().setPrice(
			priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity, boolean indexed
	) {
		getPricesBuilder().setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		return this;
	}

	@Override
	public EntityBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		getPricesBuilder().removePrice(priceId, priceList, currency);
		return this;
	}

	@Override
	public EntityBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		getPricesBuilder().setPriceInnerRecordHandling(priceInnerRecordHandling);
		return this;
	}

	@Override
	public EntityBuilder removePriceInnerRecordHandling() {
		getPricesBuilder().removePriceInnerRecordHandling();
		return this;
	}

	@Override
	public EntityBuilder removeAllNonTouchedPrices() {
		getPricesBuilder().removeAllNonTouchedPrices();
		return this;
	}

	/**
	 * Builds a single {@link EntityMutation} representing an upsert of a brand new entity.
	 *
	 * Behavior specifics:
	 * - Uses {@link EntityExistence#MUST_NOT_EXIST} to enforce initial creation semantics.
	 * - Adds {@link SetEntityScopeMutation} only when the scope differs from LIVE (default is kept implicit).
	 * - Adds {@link SetParentMutation} when a parent was set.
	 * - Emits reference insert and their attribute/group mutations. When duplicates exist for a
	 * reference key, the effective cardinality is relaxed via {@link #resolveCardinality(ReferenceKey, Cardinality)}.
	 * - Emits attribute, associated data and price mutations. Price inner record handling is always
	 * set explicitly to guarantee deterministic state.
	 */
	@Nonnull
	@Override
	public Optional<EntityMutation> toMutation() {
		final List<LocalMutation<?, ?>> mutations = new ArrayList<>(16);

		// scope mutation (if not LIVE)
		if (this.scope != Scope.LIVE) {
			mutations.add(new SetEntityScopeMutation(this.scope));
		}

		// parent mutation (if present)
		if (this.parent != null) {
			mutations.add(new SetParentMutation(this.parent));
		}

		// reference mutations
		if (this.referenceCollection != null) {
			for (final ReferenceContract it : this.referenceCollection) {
				// Insert reference
				mutations.add(
					new InsertReferenceMutation(
						it.getReferenceKey(),
						resolveCardinality(it.getReferenceKey(), it.getReferenceCardinality()),
						it.getReferencedEntityType()
					)
				);

				// Optional group
				it.getGroup().ifPresent(group -> mutations.add(
					new SetReferenceGroupMutation(
						it.getReferenceKey(),
						group.getType(),
						group.getPrimaryKey()
					)
				));

				// Reference attributes with non-null values
				for (final AttributeValue attributeValue : it.getAttributeValues()) {
					final Serializable value = attributeValue.value();
					if (value != null) {
						mutations.add(
							new ReferenceAttributeMutation(
								it.getReferenceKey(),
								new UpsertAttributeMutation(attributeValue.key(), value)
							)
						);
					}
				}
			}
		}

		// entity attributes (non-null only)
		if (this.attributesBuilder != null) {
			for (final AttributeValue it : this.attributesBuilder.getAttributeValues()) {
				final Serializable value = it.value();
				if (value != null) {
					mutations.add(new UpsertAttributeMutation(it.key(), value));
				}
			}
		}

		// associated data
		if (this.associatedDataBuilder != null) {
			for (final AssociatedDataValue ad : this.associatedDataBuilder.getAssociatedDataValues()) {
				mutations.add(new UpsertAssociatedDataMutation(ad.key(), ad.valueOrThrowException()));
			}
		}

		// prices
		if (this.pricesBuilder != null) {
			mutations.add(new SetPriceInnerRecordHandlingMutation(getPricesBuilder().getPriceInnerRecordHandling()));
			for (final PriceContract it : this.pricesBuilder.getPrices()) {
				mutations.add(new UpsertPriceMutation(it.priceKey(), it));
			}
		}

		return of(
			new EntityUpsertMutation(
				getType(),
				getPrimaryKey(),
				EntityExistence.MUST_NOT_EXIST,
				mutations
			)
		);
	}

	/**
	 * Materializes the builder into an immutable {@link Entity}.
	 *
	 * Notes:
	 * - Uses empty containers when a particular builder was never touched to minimize footprint.
	 * - Combines schema-declared reference names with those added in this builder.
	 * - The hierarchy flag is set when schema declares hierarchy or a parent was assigned.
	 */
	@Nonnull
	@Override
	public Entity toInstance() {
		final Set<String> allReferenceNames;
		if (this.referenceCollection == null || this.referencesDefinedCount == null) {
			allReferenceNames = this.schema.getReferences().keySet();
		} else {
			allReferenceNames = new HashSet<>(this.getSchema().getReferences().size() + this.referencesDefinedCount.size());
			allReferenceNames.addAll(this.referencesDefinedCount.keySet());
			allReferenceNames.addAll(this.schema.getReferences().keySet());
		}

		final List<ReferenceContract> theReferences;
		if (this.referenceCollection == null) {
			theReferences = Collections.emptyList();
		} else {
			theReferences = new ArrayList<>(this.referenceCollection);
			Collections.sort(theReferences);
		}

		return Entity._internalBuild(
			this.primaryKey,
			version(),
			this.schema,
			this.parent,
			theReferences,
			this.attributesBuilder == null ?
				new EntityAttributes(this.schema) : this.attributesBuilder.build(),
			this.associatedDataBuilder == null ?
				new AssociatedData(this.schema) : this.associatedDataBuilder.build(),
			this.pricesBuilder == null ?
				new Prices(this.schema, PriceInnerRecordHandling.NONE) : this.pricesBuilder.build(),
			getAllLocales(),
			allReferenceNames,
			this.schema.isWithHierarchy() || this.parent != null,
			false
		);
	}

	/**
	 * Sets a unique reference internally while ensuring compliance with the specified reference schema. The method
	 * validates that the reference conforms to the rules defined in the schema, and may promote cardinality if permissible.
	 * It enforces constraints such as avoiding duplicate references and maintaining schema consistency.
	 *
	 * @param referenceSchema the schema associated with the reference, used to validate its properties and constraints
	 * @param referenceKey the unique key identifying the referenced entity; this includes the primary key and internal key
	 * @param referencedEntityType the type of the referenced entity; must match the schema-defined type, if provided
	 * @param cardinality the cardinality of the reference; if provided, must match the schema-defined cardinality
	 * @param whichIs an optional consumer for setting additional properties of the reference during its building process
	 * @return the updated instance of InternalEntityBuilder that includes the newly added or updated reference
	 */
	@Nonnull
	private InternalEntityBuilder setUniqueReferenceInternal(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) {
		final String referenceName = referenceSchema.getName();
		final Cardinality schemaCardinality = assertReferenceSchemaCompatibility(
			referenceSchema, referencedEntityType, cardinality
		);

		// this method cannot be used when duplicates are allowed by schema
		if (schemaCardinality.allowsDuplicates()) {
			throw new ReferenceAllowsDuplicatesException(referenceName, getSchema(), Operation.WRITE);
		}
		// but we allow implicit cardinality widening when needed
		if (this.referencesDefinedCount != null && this.referencesDefinedCount.containsKey(referenceName) && schemaCardinality.getMax() <= 1) {
			// check whether there already is some reference to this key
			// if yes, check whether we can promote cardinality
			if (getSchema().allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
				referenceSchema = promoteUniqueCardinality(referenceSchema, referenceKey);
			} else {
				throw new InvalidMutationException(
					"The reference `" + referenceName + "` is already defined to have `" +
						referenceSchema.getCardinality() + "` cardinality, cannot add another reference to it!"
				);
			}
		}
		// coerce the reference key
		final int referencedEntityPrimaryKey = referenceKey.primaryKey();
		if (this.references != null) {
			final ReferenceContract referenceContract = this.references.get(
				referenceKey.isUnknownReference() ?
					referenceKey :
					new ReferenceKey(referenceName, referencedEntityPrimaryKey)
			);
			if (referenceContract != null) {
				if (referenceContract == Entity.DUPLICATE_REFERENCE) {
					throw new ReferenceAllowsDuplicatesException(referenceName, getSchema(), Operation.WRITE);
				} else {
					Assert.isTrue(
						referenceKey.isUnknownReference() ||
							referenceKey.internalPrimaryKey() == referenceContract.getReferenceKey().internalPrimaryKey(),
						() -> new InvalidMutationException(
							"The reference `" + referenceName + "` with primary key `" +
								referencedEntityPrimaryKey + "` already exists and has internal id " +
								referenceContract.getReferenceKey().internalPrimaryKey() + "!"
						)
					);
					referenceKey = referenceContract.getReferenceKey();
				}
			}
		}
		final InitialReferenceBuilder builder = new InitialReferenceBuilder(
			this.schema,
			referenceSchema,
			referenceName,
			referencedEntityPrimaryKey,
			referenceKey.isUnknownReference() ?
				getNextReferenceInternalId() :
				referenceKey.internalPrimaryKey()
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(builder));
		addReferenceInternal(builder.build());
		return this;
	}

	/**
	 * Asserts the compatibility of the provided reference schema with the specified references entity type and cardinality.
	 * Ensures that the referenced entity type and cardinality provided are either compatible with or match the
	 * already defined values in the reference schema. If they are incompatible, an {@code InvalidMutationException} is thrown.
	 *
	 * @param referenceSchema the reference schema contract to validate against
	 * @param referencedEntityType the referenced entity type to check for compatibility, may be null
	 * @param cardinality the cardinality to check for compatibility, may be null
	 * @return the cardinality defined in the provided reference schema
	 */
	@Nonnull
	static Cardinality assertReferenceSchemaCompatibility(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality
	) {
		final String schemaDefinedReferencedEntityType = referenceSchema.getReferencedEntityType();
		if (referencedEntityType != null) {
			Assert.isTrue(
				Objects.equals(schemaDefinedReferencedEntityType, referencedEntityType),
				() -> new InvalidMutationException(
					"The reference `" + referenceSchema.getName() + "` is already defined to point to `" +
						schemaDefinedReferencedEntityType + "` entity type, cannot change it to `" + referencedEntityType + "`!"
				)
			);
		}

		final Cardinality schemaCardinality = referenceSchema.getCardinality();
		if (cardinality != null) {
			Assert.isTrue(
				Objects.equals(schemaCardinality, cardinality),
				() -> new InvalidMutationException(
					"The reference `" + referenceSchema.getName() + "` is already defined to have `" +
						schemaCardinality + "` cardinality, cannot change it to `" + cardinality + "`!"
				)
			);
		}
		return schemaCardinality;
	}

	/**
	 * Promotes the cardinality from zero/one to one, to zero/one to many. Modifies reference schemas
	 * of all existing references of a particular type (locally) to the elevated cardinality. This is a local change
	 * that does not affect the schema itself - it is used only behave consistently within this builder.
	 *
	 * @param referenceSchema The schema contract of the reference, containing metadata about the reference type and its cardinality.
	 * @param referenceKey    The key of the reference that is being checked or promoted for unique cardinality.
	 */
	private ReferenceSchemaContract promoteUniqueCardinality(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey
	) {
		if (this.referenceCollection != null) {
			final Cardinality schemaCardinality = referenceSchema.getCardinality();
			final Cardinality elevatedCardinality = schemaCardinality.getMin() == 0 ?
				Cardinality.ZERO_OR_MORE :
				Cardinality.ONE_OR_MORE;
			final ReferenceSchemaContract updatedSchema = ReferenceSchema._internalBuild(
				referenceSchema, elevatedCardinality
			);
			final List<ReferenceContract> updatedReferences = new LinkedList<>();
			final Iterator<ReferenceContract> it = this.referenceCollection.iterator();
			while (it.hasNext()) {
				final ReferenceContract reference = it.next();
				if (
					reference.getReferenceName().equals(referenceKey.referenceName()) &&
						!reference.getReferenceKey().equals(referenceKey)
				) {
					updatedReferences.add(
						new Reference(
							updatedSchema,
							reference instanceof Reference ref ?
								ref :
								new Reference(
									getSchema(),
									referenceSchema,
									referenceKey.internalPrimaryKey(),
									reference
								)
						)
					);
					it.remove();
				}
			}
			// we have promoted some references - update the index accordingly
			final Map<ReferenceKey, ReferenceContract> index = getReferenceIndexForUpdate();
			for (ReferenceContract updatedReference : updatedReferences) {
				index.put(updatedReference.getReferenceKey(), updatedReference);
				this.referenceCollection.add(updatedReference);
			}
			return updatedSchema;
		}
		return referenceSchema;
	}

	/**
	 * Promotes the cardinality from zero/one to many, to zero/one to many with duplicates. Modifies reference schemas
	 * of all existing references of particular type (locally) to the elevated cardinality. This is a local change
	 * that does not affect the schema itself - it is used only behave consistently within this builder.
	 *
	 * @param referenceSchema The schema contract of the reference, containing metadata about the reference type and its cardinality.
	 * @param referenceKey    The key of the reference that is being checked or promoted for unique cardinality.
	 */
	private ReferenceSchemaContract promoteDuplicateCardinality(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey
	) {
		if (this.referenceCollection != null) {
			final Cardinality schemaCardinality = referenceSchema.getCardinality();
			final Cardinality elevatedCardinality = schemaCardinality.getMin() == 0 ?
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES :
				Cardinality.ONE_OR_MORE_WITH_DUPLICATES;

			final List<ReferenceContract> updatedReferences = new ArrayList<>();
			final ReferenceSchemaContract updatedSchema = ReferenceSchema._internalBuild(
				referenceSchema, elevatedCardinality
			);
			final Iterator<ReferenceContract> it = this.referenceCollection.iterator();
			while (it.hasNext()) {
				final ReferenceContract reference = it.next();
				if (reference.getReferenceName().equals(referenceKey.referenceName())) {
					updatedReferences.add(
						new Reference(
							updatedSchema,
							reference instanceof Reference ref ?
								ref :
								new Reference(
									getSchema(),
									referenceSchema,
									referenceKey.internalPrimaryKey(),
									reference
								)
						)
					);
					it.remove();
				}
			}
			// we have promoted some references - update the index accordingly
			final Map<ReferenceKey, ReferenceContract> index = getReferenceIndexForUpdate();
			for (ReferenceContract updatedReference : updatedReferences) {
				index.put(updatedReference.getReferenceKey(), updatedReference);
				this.referenceCollection.add(updatedReference);
			}

			return updatedSchema;
		}
		return referenceSchema;
	}

	@Delegate(types = AttributesContract.class)
	@Nonnull
	private InitialEntityAttributesBuilder getAttributesBuilder() {
		if (this.attributesBuilder == null) {
			this.attributesBuilder = new InitialEntityAttributesBuilder(this.schema);
		}
		return this.attributesBuilder;
	}

	@Delegate(types = AssociatedDataContract.class)
	@Nonnull
	private InitialAssociatedDataBuilder getAssociatedDataBuilder() {
		if (this.associatedDataBuilder == null) {
			this.associatedDataBuilder = new InitialAssociatedDataBuilder(this.schema);
		}
		return this.associatedDataBuilder;
	}

	@Delegate(types = PricesContract.class, excludes = Versioned.class)
	@Nonnull
	private InitialPricesBuilder getPricesBuilder() {
		if (this.pricesBuilder == null) {
			this.pricesBuilder = new InitialPricesBuilder(this.schema);
		}
		return this.pricesBuilder;
	}

	@Nonnull
	private List<ReferenceContract> getReferenceCollectionForUpdate() {
		if (this.referenceCollection == null) {
			this.referenceCollection = new ArrayList<>();
			this.references = new LinkedHashMap<>();
		}
		return this.referenceCollection;
	}

	@Nonnull
	private Map<ReferenceKey, ReferenceContract> getReferenceIndexForUpdate() {
		if (this.references == null) {
			this.referenceCollection = new ArrayList<>();
			this.references = new LinkedHashMap<>();
		}
		return this.references;
	}

	/**
	 * Resolves the cardinality for a given reference key based on the proposed cardinality
	 * and whether duplicate references are allowed.
	 *
	 * @param key                 the reference key for which the cardinality is being resolved
	 * @param proposedCardinality the cardinality that is proposed for the given reference key
	 * @return the resolved cardinality, which may be adjusted based on duplicate reference handling
	 */
	@Nonnull
	private Cardinality resolveCardinality(@Nonnull ReferenceKey key, @Nonnull Cardinality proposedCardinality) {
		if (this.references != null && this.references.get(key) == Entity.DUPLICATE_REFERENCE) {
			if (proposedCardinality.allowsDuplicates()) {
				return proposedCardinality;
			} else {
				if (proposedCardinality.getMin() == 0) {
					return Cardinality.ZERO_OR_MORE_WITH_DUPLICATES;
				} else {
					return Cardinality.ONE_OR_MORE_WITH_DUPLICATES;
				}
			}
		} else {
			return proposedCardinality;
		}
	}

	/**
	 * Retrieves the reference schema associated with the given reference name from the entity schema.
	 * If the reference schema for the specified name is not found, a {@code ReferenceNotKnownException} is thrown.
	 *
	 * @param referenceName the name of the reference whose schema is to be retrieved; must not be null
	 * @return the {@code ReferenceSchemaContract} associated with the given reference name; never null
	 * @throws ReferenceNotKnownException if the reference schema associated with the given name is not found
	 */
	@Nonnull
	private ReferenceSchemaContract getReferenceSchemaOrThrowException(@Nonnull String referenceName)
		throws ReferenceNotKnownException {
		return getReferenceSchemaContract(referenceName)
			.orElseThrow(() -> new ReferenceNotKnownException(referenceName));
	}

	/**
	 * Retrieves an existing ReferenceSchemaContract for the specified reference name, or creates
	 * an implicit schema if none exists.
	 *
	 * @param referenceName        the name of the reference to retrieve or create the schema for; must not be null
	 * @param referencedEntityType the type of the entity being referenced; must not be null
	 * @param cardinality          the cardinality that defines the relationship; must not be null
	 * @return the existing ReferenceSchemaContract or a newly created implicit schema
	 * @throws ReferenceNotKnownException if the reference cannot be located and cannot be created implicitly
	 */
	@Nonnull
	private ReferenceSchemaContract getReferenceSchemaOrCreateImplicit(
		@Nonnull String referenceName,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality
	) throws ReferenceNotKnownException {
		return getReferenceSchemaContract(referenceName)
			.orElseGet(() -> {
				if (referencedEntityType == null || cardinality == null) {
					throw new ReferenceNotKnownException(referenceName);
				}
				return Reference.createImplicitSchema(referenceName, referencedEntityType, cardinality, null);
			});
	}

	/**
	 * Retrieves the {@link ReferenceSchemaContract} for the given reference name.
	 *
	 * @param referenceName the name of the reference for which the schema contract is requested, must not be null
	 * @return an {@link Optional} containing the {@link ReferenceSchemaContract} if it exists, or an empty {@link Optional} if not found
	 */
	@Nonnull
	private Optional<ReferenceSchemaContract> getReferenceSchemaContract(@Nonnull String referenceName) {
		return getSchema()
			.getReference(referenceName)
			.or(() -> {
				if (this.referenceCollection != null) {
					for (ReferenceContract existingReference : this.referenceCollection) {
						if (existingReference.getReferenceName().equals(referenceName)) {
							return existingReference.getReferenceSchema();
						}
					}
				}
				return Optional.empty();
			});
	}

	/**
	 * Adds a reference to the internal collection while ensuring uniqueness based on the reference's key.
	 * If the reference is added for the first time, it is stored. In case of a duplicate, the reference is marked
	 * with a special indicator for duplicate references.
	 *
	 * @param reference the reference to be added; must not be null
	 */
	private void addReferenceInternal(@Nonnull ReferenceContract reference) {
		if (this.referenceCollection == null || this.references == null) {
			this.referenceCollection = new ArrayList<>(16);
			this.references = new LinkedHashMap<>(16);
		}
		final ReferenceContract referenceWithAssignedInternalId = getReference(reference);
		this.referenceCollection.add(referenceWithAssignedInternalId);
		this.references.compute(
			referenceWithAssignedInternalId.getReferenceKey(),
			(key, existingValue) ->
				existingValue == null ? referenceWithAssignedInternalId : Entity.DUPLICATE_REFERENCE
		);
		if (this.referencesDefinedCount == null) {
			this.referencesDefinedCount = new HashMap<>(8);
		}
		this.referencesDefinedCount.compute(
			referenceWithAssignedInternalId.getReferenceName(),
			(name, count) -> count == null ? 1 : count + 1
		);
	}

	/**
	 * Processes a given reference and returns the appropriate {@link ReferenceContract}.
	 * If the reference key is unknown, a new reference is created with an updated identifier.
	 *
	 * @param reference the reference to be processed, must not be null
	 * @return a processed reference, either the original or a new instance with an updated identifier
	 */
	@Nonnull
	private ReferenceContract getReference(@Nonnull ReferenceContract reference) {
		if (reference.getReferenceKey().isUnknownReference()) {
			return reference instanceof Reference ref ?
				new Reference(getNextReferenceInternalId(), ref) :
				new Reference(
					this.schema, reference.getReferenceSchemaOrThrow(), getNextReferenceInternalId(), reference
				);
		} else {
			return reference;
		}
	}

	/**
	 * The ReferenceExchange class represents an immutable data structure that holds
	 * a reference update operation. It encapsulates the index of the reference
	 * and the updated reference data.
	 *
	 * This class is a record, providing a concise way to create immutable data objects.
	 *
	 * Fields:
	 * - index: The index of the reference being updated.
	 * - updatedReference: The new reference information encapsulated in a ReferenceContract.
	 *
	 * It is expected that the updatedReference is non-null to ensure the integrity
	 * of reference updates.
	 */
	private record ReferenceExchange(
		int index,
		@Nonnull ReferenceContract updatedReference
	) {

	}
}
