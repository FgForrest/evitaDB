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
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.*;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.ParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate.ExistsPredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link Entity}. Entity is immutable object so there is need for another object
 * that would simplify the process of updating its contents. This is why the builder class exists.
 *
 * This builder is suitable for the situation when there already is some entity at place, and we need to alter it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExistingEntityBuilder implements InternalEntityBuilder {
	/**
	 * Explicit serialization id to keep binary compatibility of the builder across versions.
	 */
	@Serial private static final long serialVersionUID = -1422927537304173188L;

	/**
	 * This predicate filters out non-fetched locales.
	 */
	@Getter private final LocaleSerializablePredicate localePredicate;
	/**
	 * This predicate filters out access to the hierarchy parent that were not fetched in query.
	 */
	@Getter private final HierarchySerializablePredicate hierarchyPredicate;
	/**
	 * This predicate filters out attributes that were not fetched in query.
	 */
	@Getter private final AttributeValueSerializablePredicate attributePredicate;
	/**
	 * This predicate filters out associated data that were not fetched in query.
	 */
	@Getter private final AssociatedDataValueSerializablePredicate associatedDataPredicate;
	/**
	 * This predicate filters out references that were not fetched in query.
	 */
	@Getter private final ReferenceContractSerializablePredicate referencePredicate;
	/**
	 * This predicate filters out prices that were not fetched in query.
	 */
	@Getter private final PriceContractSerializablePredicate pricePredicate;
	/**
	 * Immutable snapshot of the original entity the builder mutates upon.
	 */
	private final Entity baseEntity;
	/**
	 * Optional decorator providing access predicates and decorated access to the base entity.
	 * When present, it is consulted for context-aware reads.
	 */
	private final EntityDecorator baseEntityDecorator;
	/**
	 * Builder accumulating attribute-related mutations on top of base entity state.
	 */
	@Delegate(types = AttributesContract.class, excludes = AttributesAvailabilityChecker.class)
	private final ExistingEntityAttributesBuilder attributesBuilder;
	/**
	 * Builder accumulating associated-data mutations on top of base entity state.
	 */
	@Delegate(types = AssociatedDataContract.class, excludes = AssociatedDataAvailabilityChecker.class)
	private final ExistingAssociatedDataBuilder associatedDataBuilder;
	/**
	 * Builder accumulating price mutations on top of base entity state.
	 */
	@Delegate(types = PricesContract.class, excludes = Versioned.class)
	private final ExistingPricesBuilder pricesBuilder;
	/**
	 * Builder accumulating reference mutations on top of base entity state.
	 */
	@Delegate(types = ReferencesContract.class, excludes = ReferenceAvailabilityChecker.class)
	private final ExistingReferencesBuilder referencesBuilder;
	/**
	 * Pending scope mutation, if any, applied when materializing the builder to a mutation.
	 */
	@Nullable private SetEntityScopeMutation scopeMutation;
	/**
	 * Pending hierarchy parent mutation, if any, applied when materializing the builder to a mutation.
	 */
	@Nullable private ParentMutation hierarchyMutation;

	/**
	 * Creates a builder for a plain base entity (no decorator) and optionally enqueues local mutations.
	 *
	 * Predicates default to permissive DEFAULT_INSTANCE variants to reflect no fetch filtering context.
	 *
	 * @param baseEntity     non-null base entity to build upon
	 * @param localMutations non-null collection of local mutations to enqueue (may be empty)
	 */
	public ExistingEntityBuilder(
		@Nonnull Entity baseEntity,
		@Nonnull Collection<LocalMutation<?, ?>> localMutations
	) {
		this.baseEntity = baseEntity;
		this.baseEntityDecorator = null;
		this.attributesBuilder = new ExistingEntityAttributesBuilder(
			this.baseEntity.schema, this.baseEntity.attributes,
			ExistsPredicate.instance(), new LazyHashMap<>(4)
		);
		this.associatedDataBuilder = new ExistingAssociatedDataBuilder(
			this.baseEntity.schema, this.baseEntity.associatedData, ExistsPredicate.instance()
		);
		this.pricesBuilder = new ExistingPricesBuilder(
			this.baseEntity.schema, this.baseEntity.prices, new PriceContractSerializablePredicate()
		);
		this.referencesBuilder = new ExistingReferencesBuilder(
			this.baseEntity.schema, this.baseEntity.references,
			ReferenceContractSerializablePredicate.DEFAULT_INSTANCE,
			referenceKey -> Optional.empty()
		);
		this.localePredicate = LocaleSerializablePredicate.DEFAULT_INSTANCE;
		this.hierarchyPredicate = HierarchySerializablePredicate.DEFAULT_INSTANCE;
		this.attributePredicate = AttributeValueSerializablePredicate.DEFAULT_INSTANCE;
		this.associatedDataPredicate = AssociatedDataValueSerializablePredicate.DEFAULT_INSTANCE;
		this.pricePredicate = PriceContractSerializablePredicate.DEFAULT_INSTANCE;
		this.referencePredicate = ReferenceContractSerializablePredicate.DEFAULT_INSTANCE;
		for (LocalMutation<?, ?> localMutation : localMutations) {
			addMutation(localMutation);
		}
	}

	/**
	 * Creates a builder for an existing entity that may already be decorated and optionally pre-filled
	 * with local mutations to apply on top of the base entity state.
	 *
	 * - Copies predicates from the provided decorator so that access rules match the fetched content.
	 * - Initializes internal builders for attributes, associated data and prices.
	 * - Queues provided local mutations via {@link #addMutation(LocalMutation)} in the given order.
	 *
	 * @param baseEntity     non-null decorator containing the base entity and fetch predicates
	 * @param localMutations non-null collection of local mutations to enqueue (may be empty)
	 */
	public ExistingEntityBuilder(
		@Nonnull EntityDecorator baseEntity,
		@Nonnull Collection<LocalMutation<?, ?>> localMutations
	) {
		this.baseEntity = baseEntity.getDelegate();
		this.baseEntityDecorator = baseEntity;
		this.attributesBuilder = new ExistingEntityAttributesBuilder(
			this.baseEntity.schema, this.baseEntity.attributes,
			baseEntity.getAttributePredicate(), new LazyHashMap<>(4)
		);
		this.associatedDataBuilder = new ExistingAssociatedDataBuilder(
			this.baseEntity.schema, this.baseEntity.associatedData, baseEntity.getAssociatedDataPredicate()
		);
		this.pricesBuilder = new ExistingPricesBuilder(
			this.baseEntity.schema, this.baseEntity.prices, baseEntity.getPricePredicate()
		);
		this.referencesBuilder = new ExistingReferencesBuilder(
			this.baseEntity.schema, this.baseEntity.references,
			baseEntity.getReferencePredicate(),
			baseEntity::getReference
		);
		this.localePredicate = baseEntity.getLocalePredicate();
		this.hierarchyPredicate = baseEntity.getHierarchyPredicate();
		this.attributePredicate = baseEntity.getAttributePredicate();
		this.associatedDataPredicate = baseEntity.getAssociatedDataPredicate();
		this.pricePredicate = baseEntity.getPricePredicate();
		this.referencePredicate = baseEntity.getReferencePredicate();
		for (LocalMutation<?, ?> localMutation : localMutations) {
			addMutation(localMutation);
		}
	}

	/**
	 * Convenience constructor that creates a builder for a decorated entity without any initial mutations.
	 *
	 * @param baseEntity non-null decorated entity serving as the source state and predicates
	 */
	public ExistingEntityBuilder(@Nonnull EntityDecorator baseEntity) {
		this(baseEntity, Collections.emptyList());
	}

	/**
	 * Convenience constructor that creates a builder for a plain base entity without any initial mutations.
	 *
	 * @param baseEntity non-null base entity to build upon
	 */
	public ExistingEntityBuilder(@Nonnull Entity baseEntity) {
		this(baseEntity, Collections.emptyList());
	}

	/**
	 * Enqueues a single local mutation to this builder, dispatching it to the appropriate sub-builder
	 * or internal accumulator based on its concrete type.
	 *
	 * Supported mutations include:
	 * - Scope and hierarchy mutations (applied directly on the builder)
	 * - Attribute and associated data mutations (delegated to respective builders)
	 * - Reference mutations (stored and coalesced per reference key and internal ID)
	 * - Price mutations and inner record handling (delegated to price builder)
	 *
	 * @param localMutation non-null local mutation to apply
	 * @throws GenericEvitaInternalError when an unknown mutation type is encountered
	 */
	public void addMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		localMutation = localMutation.withDecisiveTimestamp(System.nanoTime());
		if (localMutation instanceof SetEntityScopeMutation setScopeMutation) {
			this.scopeMutation = setScopeMutation;
		} else if (localMutation instanceof ParentMutation hierarchicalPlacementMutation) {
			this.hierarchyMutation = hierarchicalPlacementMutation;
		} else if (localMutation instanceof AttributeMutation attributeMutation) {
			this.attributesBuilder.addMutation(attributeMutation);
		} else if (localMutation instanceof AssociatedDataMutation associatedDataMutation) {
			this.associatedDataBuilder.addMutation(associatedDataMutation);
		} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
			this.referencesBuilder.addMutation(referenceMutation);
		} else if (localMutation instanceof PriceMutation priceMutation) {
			this.pricesBuilder.addMutation(priceMutation);
		} else if (localMutation instanceof SetPriceInnerRecordHandlingMutation innerRecordHandlingMutation) {
			this.pricesBuilder.addMutation(innerRecordHandlingMutation);
		} else {
			// SHOULD NOT EVER HAPPEN
			throw new GenericEvitaInternalError("Unknown mutation: " + localMutation.getClass());
		}
	}

	@Override
	public boolean dropped() {
		return false;
	}

	@Override
	public int version() {
		return this.baseEntity.version() + 1;
	}

	@Override
	@Nonnull
	public String getType() {
		return this.baseEntity.getType();
	}

	@Override
	@Nonnull
	public EntitySchemaContract getSchema() {
		return this.baseEntity.getSchema();
	}

	@Override
	@Nullable
	public Integer getPrimaryKey() {
		return this.baseEntity.getPrimaryKey();
	}

	@Override
	public boolean parentAvailable() {
		return this.hierarchyMutation != null || this.baseEntity.parentAvailable();
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		if (!parentAvailable()) {
			throw ContextMissingException.hierarchyContextMissing();
		}
		return getParentEntityWithoutSchemaCheck();
	}

	@Nonnull
	@Override
	public Set<Locale> getAllLocales() {
		final Set<Locale> attributeLocales = this.attributesBuilder.getAttributeLocales();
		final Set<Locale> associatedDataLocales = this.associatedDataBuilder.getAssociatedDataLocales();

		final int expectedSize = attributeLocales.size() + associatedDataLocales.size();
		final Set<Locale> result = CollectionUtils.createHashSet(Math.max(16, expectedSize));
		result.addAll(attributeLocales);
		result.addAll(associatedDataLocales);
		return result;
	}

	/**
	 * Returns the set of locales that are currently visible according to the locale predicate
	 * derived from the fetched content. In contrast to {@link #getAllLocales()}, this method filters
	 * out locales that were not fetched or are otherwise hidden by the predicate.
	 *
	 * @return non-null set of visible locales respecting fetch constraints
	 */
	@Nonnull
	public Set<Locale> getLocales() {
		final Set<Locale> attributeLocales = this.attributesBuilder.getAttributeLocales();
		final Set<Locale> associatedDataLocales = this.associatedDataBuilder.getAssociatedDataLocales();

		final int expectedSize = attributeLocales.size() + associatedDataLocales.size();
		final Set<Locale> result = CollectionUtils.createHashSet(Math.max(16, expectedSize));
		for (Locale attributeLocale : attributeLocales) {
			if (this.localePredicate.test(attributeLocale)) {
				result.add(attributeLocale);
			}
		}
		for (Locale associatedDataLocale : associatedDataLocales) {
			if (this.localePredicate.test(associatedDataLocale)) {
				result.add(associatedDataLocale);
			}
		}
		return result;
	}

	@Nonnull
	@Override
	public Scope getScope() {
		return ofNullable(this.scopeMutation)
			.map(SetEntityScopeMutation::getScope)
			.orElseGet(this.baseEntity::getScope);
	}

	@Nonnull
	@Override
	public EntityBuilder setScope(@Nonnull Scope scope) {
		this.scopeMutation = Objects.equals(this.baseEntity.getScope(), scope) ?
			null : new SetEntityScopeMutation(scope);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setParent(int parentPrimaryKey) {
		final EntitySchemaContract schema = getSchema();
		if (!schema.isWithHierarchy() && !schema.allows(EvolutionMode.ADDING_HIERARCHY)) {
			throw new InvalidMutationException(
				"Entity `" + getType() + "` is not hierarchical and its schema doesn't allow to become hierarchical on first hierarchy mutation!"
			);
		}
		this.hierarchyMutation = !Objects.equals(
			this.baseEntity.getParentWithoutSchemaCheck(), OptionalInt.of(parentPrimaryKey)) ?
			new SetParentMutation(parentPrimaryKey) : null;
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeParent() {
		if (!parentAvailable()) {
			throw ContextMissingException.hierarchyContextMissing();
		}
		Assert.notNull(this.baseEntity.getParent(), "Cannot remove parent that is not present!");
		this.hierarchyMutation = this.baseEntity.getParent().isPresent() ? new RemoveParentMutation() : null;
		return this;
	}

	@Override
	public boolean attributesAvailable() {
		return this.baseEntityDecorator == null ?
			this.baseEntity.attributesAvailable() : this.baseEntityDecorator.attributesAvailable();
	}

	@Override
	public boolean attributesAvailable(@Nonnull Locale locale) {
		return this.baseEntityDecorator == null ?
			this.baseEntity.attributesAvailable(locale) : this.baseEntityDecorator.attributesAvailable(locale);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName) {
		return this.baseEntityDecorator == null ?
			this.baseEntity.attributeAvailable(attributeName) : this.baseEntityDecorator.attributeAvailable(
			attributeName);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName, @Nonnull Locale locale) {
		return this.baseEntityDecorator == null ?
			this.baseEntity.attributeAvailable(attributeName, locale) : this.baseEntityDecorator.attributeAvailable(
			attributeName, locale);
	}

	@Override
	public boolean associatedDataAvailable() {
		return this.baseEntityDecorator == null ?
			this.baseEntity.associatedDataAvailable() : this.baseEntityDecorator.associatedDataAvailable();
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull Locale locale) {
		return this.baseEntityDecorator == null ?
			this.baseEntity.associatedDataAvailable(locale) : this.baseEntityDecorator.associatedDataAvailable(locale);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName) {
		return this.baseEntityDecorator == null ?
			this.baseEntity.associatedDataAvailable(associatedDataName) :
			this.baseEntityDecorator.associatedDataAvailable(associatedDataName);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return this.baseEntityDecorator == null ?
			this.baseEntity.associatedDataAvailable(associatedDataName, locale) :
			this.baseEntityDecorator.associatedDataAvailable(associatedDataName, locale);
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName) {
		this.attributesBuilder.removeAttribute(attributeName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName,
		@Nullable T attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName,
		@Nullable T[] attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(
		@Nonnull String attributeName,
		@Nonnull Locale locale
	) {
		this.attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName,
		@Nonnull Locale locale,
		@Nullable T attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName,
		@Nonnull Locale locale,
		@Nullable T[] attributeValue
	) {
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		this.attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		this.associatedDataBuilder.removeAssociatedData(associatedDataName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName,
		@Nullable T associatedDataValue
	) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull T[] associatedDataValue
	) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull Locale locale
	) {
		this.associatedDataBuilder.removeAssociatedData(associatedDataName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull Locale locale,
		@Nullable T associatedDataValue
	) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull Locale locale,
		@Nullable T[] associatedDataValue
	) {
		this.associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		this.associatedDataBuilder.mutateAssociatedData(mutation);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		this.pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		this.pricesBuilder.setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, indexed
		);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed
	) {
		this.pricesBuilder.setPrice(
			priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, validity, indexed
		);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed
	) {
		this.pricesBuilder.setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed
		);
		return this;
	}

	@Override
	public EntityBuilder removePrice(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency
	) {
		this.pricesBuilder.removePrice(priceId, priceList, currency);
		return this;
	}

	@Override
	public EntityBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		this.pricesBuilder.setPriceInnerRecordHandling(priceInnerRecordHandling);
		return this;
	}

	@Override
	public EntityBuilder removePriceInnerRecordHandling() {
		this.pricesBuilder.removePriceInnerRecordHandling();
		return this;
	}

	@Override
	public EntityBuilder removeAllNonTouchedPrices() {
		this.pricesBuilder.removeAllNonTouchedPrices();
		return this;
	}

	@Override
	public boolean referencesAvailable() {
		return this.baseEntityDecorator == null ?
			this.baseEntity.referencesAvailable() : this.baseEntityDecorator.referencesAvailable();
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return this.baseEntityDecorator == null ?
			this.baseEntity.referencesAvailable(referenceName) :
			this.baseEntityDecorator.referencesAvailable(referenceName);
	}

	@Nonnull
	@Override
	public EntityBuilder updateReferences(
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		this.referencesBuilder.updateReferences(filter, whichIs);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey
	) throws ReferenceNotKnownException {
		this.referencesBuilder.setReference(referenceName, referencedPrimaryKey);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) throws ReferenceNotKnownException {
		this.referencesBuilder.setReference(referenceName, referencedPrimaryKey, whichIs);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setOrUpdateReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		this.referencesBuilder.setOrUpdateReference(referenceName, referencedPrimaryKey, filter, whichIs);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey
	) {
		this.referencesBuilder.setReference(
			referenceName, referencedEntityType, cardinality, referencedPrimaryKey
		);
		return this;
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
		this.referencesBuilder.setReference(
			referenceName, referencedEntityType, cardinality, referencedPrimaryKey, whichIs
		);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setOrUpdateReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		this.referencesBuilder.setOrUpdateReference(
			referenceName, referencedEntityType, cardinality, referencedPrimaryKey, filter, whichIs
		);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey
	) throws ReferenceNotKnownException {
		this.referencesBuilder.removeReference(referenceName, referencedPrimaryKey);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReference(@Nonnull ReferenceKey referenceKey) throws ReferenceNotKnownException {
		this.referencesBuilder.removeReference(referenceKey);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName, int referencedPrimaryKey) {
		this.referencesBuilder.removeReferences(referenceName, referencedPrimaryKey);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName) {
		this.referencesBuilder.removeReferences(referenceName);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName, @Nonnull Predicate<ReferenceContract> filter) {
		this.referencesBuilder.removeReferences(referenceName, filter);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull Predicate<ReferenceContract> filter) {
		this.referencesBuilder.removeReferences(filter);
		return this;
	}

	@Override
	public int getNextReferenceInternalId() {
		return this.referencesBuilder.getNextReferenceInternalId();
	}

	@Override
	public void addOrReplaceReferenceMutations(
		@Nonnull ReferenceBuilder referenceBuilder,
		boolean methodAllowsDuplicates
	) {
		this.referencesBuilder.addOrReplaceReferenceMutations(referenceBuilder, methodAllowsDuplicates);
	}

	@Nonnull
	@Override
	public ReferenceKey createReference(@Nonnull String referenceName, int referencedEntityPrimaryKey) {
		return this.referencesBuilder.createReference(referenceName, referencedEntityPrimaryKey);
	}

	@Nonnull
	@Override
	public Optional<EntityMutation> toMutation() {
		final List<LocalMutation<?, ? extends Comparable<?>>> mutations = new ArrayList<>(16);
		if (this.scopeMutation != null) {
			mutations.add(this.scopeMutation);
		}
		if (this.hierarchyMutation != null) {
			mutations.add(this.hierarchyMutation);
		}
		this.attributesBuilder.buildChangeSet().forEach(mutations::add);
		this.associatedDataBuilder.buildChangeSet().forEach(mutations::add);
		this.pricesBuilder.buildChangeSet().forEach(mutations::add);
		this.referencesBuilder.buildChangeSet().forEach(mutations::add);

		//noinspection unchecked
		mutations.sort(
			Comparator.comparing(LocalMutation.class::cast)
		);

		if (mutations.isEmpty()) {
			return Optional.empty();
		} else {
			return of(
				new EntityUpsertMutation(
					this.baseEntity.getType(),
					Objects.requireNonNull(this.baseEntity.getPrimaryKey()),
					EntityExistence.MUST_EXIST,
					mutations
				)
			);
		}
	}

	@Nonnull
	@Override
	public Entity toInstance() {
		final boolean modified = this.scopeMutation != null ||
			this.hierarchyMutation != null ||
			this.attributesBuilder.isThereAnyChangeInMutations() ||
			this.associatedDataBuilder.isThereAnyChangeInMutations() ||
			this.pricesBuilder.isThereAnyChangeInMutations() ||
			this.referencesBuilder.isThereAnyChangeInMutations();
		if (modified) {
			return Entity._internalBuild(
				this.getPrimaryKey(),
				this.version(),
				this.getSchema(),
				this.getParentEntityWithoutSchemaCheck().map(EntityClassifier::getPrimaryKey).orElse(null),
				this.referencesBuilder.build(),
				this.attributesBuilder.build(),
				this.associatedDataBuilder.build(),
				this.pricesBuilder.build(),
				this.getLocales(),
				this.getScope()
			);
		} else {
			return this.baseEntity;
		}
	}

	/**
	 * Checks if the given reference is present in the base entity.
	 *
	 * @param reference the reference to check
	 * @return true if the reference is present in the base entity, false otherwise
	 */
	public boolean isPresentInBaseEntity(@Nonnull ReferenceContract reference) {
		return this.baseEntity.getReference(reference.getReferenceKey())
		                      .map(Droppable::exists)
		                      .orElse(false);
	}

	/**
	 * Retrieves the parent entity without performing a schema check. This method utilizes the current state of
	 * the hierarchy mutation, schema, base entity, and optional base entity decorators to compute and return
	 * the parent entity, if present. If hierarchy-related functionality is not enabled in the schema, the method
	 * will derive the parent entity from the base entity directly.
	 *
	 * @return an Optional containing the parent entity as an {@link EntityClassifierWithParent} if present;
	 * otherwise, an empty Optional is returned.
	 */
	@Nonnull
	private Optional<EntityClassifierWithParent> getParentEntityWithoutSchemaCheck() {
		return ofNullable(this.hierarchyMutation)
			.map(it ->
			     {
				     final EntitySchemaContract schema = getSchema();
				     return it.mutateLocal(
					              schema,
					              this.baseEntity.parent == null ?
						              OptionalInt.empty() : OptionalInt.of(this.baseEntity.parent)
				              )
				              .stream()
				              .mapToObj(
					              pId -> (EntityClassifierWithParent) new EntityReferenceWithParent(
						              getType(), pId, null
					              )
				              )
				              .findFirst();
			     }
			)
			.orElseGet(
				() -> this.baseEntityDecorator == null || !this.getSchema().isWithHierarchy() ?
					this.baseEntity.getParentEntityWithoutSchemaCheck() : this.baseEntityDecorator.getParentEntity()
			);
	}

}
