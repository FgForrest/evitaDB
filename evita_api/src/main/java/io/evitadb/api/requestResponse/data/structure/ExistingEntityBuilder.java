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
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.data.*;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.ParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.Scope;
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
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder.assertReferenceSchemaCompatibility;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link Entity}. Entity is immutable object so there is need for another object
 * that would simplify the process of updating its contents. This is why the builder class exists.
 *
 * This builder is suitable for the situation when there already is some entity at place, and we need to alter it.
 * TODO JNO - cache created references until new mutation is added
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
	 * Collected reference mutations grouped by business {@link ReferenceKey} and disambiguated
	 * by internal primary key. The outer key never contains the internal primary key.
	 */
	@Nullable private Map<ReferenceKey, Map<Integer, List<ReferenceMutation<?>>>> referenceMutations;
	/**
	 * Contains names of all reference names that have at least one insert reference mutation in this instance.
	 * For such references it contains overall number of references with this name combined from base entity
	 * and mutations.
	 */
	@Nullable private Map<String, Integer> referencesDefinedCount;
	/**
	 * Contains reference schemas with locally promoted cardinality or locally resolved.
	 */
	@Nullable private Map<String, ReferenceSchemaContract> localReferenceSchemas;
	/**
	 * Tracks internal primary keys of references removed during this build session to allow
	 * proper merging semantics when a previously removed reference is upserted again.
	 */
	private Set<Integer> removedReferences;
	/**
	 * Internal sequence that is used to generate unique negative reference ids for references that
	 * don't have it assigned yet.
	 */
	private int lastLocallyAssignedReferenceId = 0;
	/**
	 * Pending scope mutation, if any, applied when materializing the builder to a mutation.
	 */
	@Nullable private SetEntityScopeMutation scopeMutation;
	/**
	 * Pending hierarchy parent mutation, if any, applied when materializing the builder to a mutation.
	 */
	@Nullable private ParentMutation hierarchyMutation;

	/**
	 * Ensures that price data were fetched with the required content-mode before any mutation is allowed.
	 *
	 * This method validates that the provided price predicate requires PriceContentMode.ALL. If it does
	 * not, any price update operation would operate on incomplete data and is therefore rejected.
	 *
	 * @param pricePredicate non-null predicate describing fetched price content
	 * @throws IllegalArgumentException when prices were not fetched with ALL content mode
	 */
	private static void assertPricesFetched(@Nonnull PriceContractSerializablePredicate pricePredicate) {
		Assert.isTrue(
			pricePredicate.getPriceContentMode() == PriceContentMode.ALL,
			"Prices were not fetched and cannot be updated. Please enrich the entity first or load it with all the prices."
		);
	}

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
			this.baseEntity.schema, this.baseEntity.attributes, ExistsPredicate.instance());
		this.associatedDataBuilder = new ExistingAssociatedDataBuilder(
			this.baseEntity.schema, this.baseEntity.associatedData, ExistsPredicate.instance());
		this.pricesBuilder = new ExistingPricesBuilder(
			this.baseEntity.schema, this.baseEntity.prices, new PriceContractSerializablePredicate());
		this.referenceMutations = null;
		this.referencesDefinedCount = null;
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
			this.baseEntity.schema, this.baseEntity.attributes, baseEntity.getAttributePredicate());
		this.associatedDataBuilder = new ExistingAssociatedDataBuilder(
			this.baseEntity.schema, this.baseEntity.associatedData, baseEntity.getAssociatedDataPredicate());
		this.pricesBuilder = new ExistingPricesBuilder(
			this.baseEntity.schema, this.baseEntity.prices, baseEntity.getPricePredicate());
		this.referenceMutations = null;
		this.referencesDefinedCount = null;
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
		if (localMutation instanceof SetEntityScopeMutation setScopeMutation) {
			this.scopeMutation = setScopeMutation;
		} else if (localMutation instanceof ParentMutation hierarchicalPlacementMutation) {
			this.hierarchyMutation = hierarchicalPlacementMutation;
		} else if (localMutation instanceof AttributeMutation attributeMutation) {
			this.attributesBuilder.addMutation(attributeMutation);
		} else if (localMutation instanceof AssociatedDataMutation associatedDataMutation) {
			this.associatedDataBuilder.addMutation(associatedDataMutation);
		} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
			getReferenceMutationsForKey(referenceMutation.getReferenceKey())
				.computeIfAbsent(
					referenceMutation.getReferenceKey().internalPrimaryKey(),
					k -> new ArrayList<>(8)
				).add(referenceMutation);
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
		return this.baseEntity.parentAvailable();
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		if (!parentAvailable()) {
			throw ContextMissingException.hierarchyContextMissing();
		}
		return ofNullable(this.hierarchyMutation)
			.map(it ->
				     it.mutateLocal(this.baseEntity.schema, this.baseEntity.getParent())
				       .stream()
				       .mapToObj(
					       pId -> (EntityClassifierWithParent) new EntityReferenceWithParent(getType(), pId, null))
				       .findFirst()
			)
			.orElseGet(
				() -> this.baseEntityDecorator == null ?
					this.baseEntity.getParentEntity() : this.baseEntityDecorator.getParentEntity()
			);
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
	public Collection<ReferenceContract> getReferences() {
		if (!referencesAvailable()) {
			throw ContextMissingException.referenceContextMissing();
		}

		final List<ReferenceContract> result = new ArrayList<>(Math.max(16, this.baseEntity.getReferences().size()));

		for (ReferenceContract baseRef : this.baseEntity.getReferences()) {
			if (!baseRef.exists()) {
				continue;
			}

			ReferenceContract toAdd = baseRef;

			// mutations by internal PK for the business-key (name + PK) of this reference
			final Map<Integer, List<ReferenceMutation<?>>> byIpk = ofNullable(this.referenceMutations)
				.map(it -> it.get(baseRef.getReferenceKey()))
				.orElseGet(Map::of);

			// get all mutations affecting this concrete reference (same as original getReferenceMutationsByReferenceKey call)
			final List<ReferenceMutation<?>> mutations = getReferenceMutationsByReferenceKey(
				baseRef.getReferenceKey(), byIpk, Operation.READ
			);
			if (!mutations.isEmpty()) {
				final ReferenceContract mutated = evaluateReferenceMutations(baseRef, mutations);
				if (mutated != null && mutated.differsFrom(baseRef)) {
					toAdd = mutated;
				}
			}

			if (this.referencePredicate.test(toAdd)) {
				result.add(toAdd);
			}
		}

		// Handle references that exist only in queued mutations (i.e., not present on base entity)
		if (this.referenceMutations != null) {
			for (Map.Entry<ReferenceKey, Map<Integer, List<ReferenceMutation<?>>>> entry : this.referenceMutations.entrySet()) {
				final ReferenceKey refKey = entry.getKey(); // business key (name + PK), internal PK is always 0
				final Map<Integer, List<ReferenceMutation<?>>> byIpk = entry.getValue();

				final Set<String> baseReferenceNames = this.baseEntity.getReferenceNames();
				for (Map.Entry<Integer, List<ReferenceMutation<?>>> ipkEntry : byIpk.entrySet()) {
					final int ipk = ipkEntry.getKey();
					// we derive an exact reference key from the internal primary keys of the internal map
					// that collects mutations for a particular reference
					final ReferenceKey exactKey = new ReferenceKey(refKey.referenceName(), refKey.primaryKey(), ipk);

					// now we check that the base entity doesn't have a reference with such a particular internal primary key
					// because this was already handled in the previous loop
					if (
						baseReferenceNames.contains(exactKey.referenceName()) &&
							this.baseEntity.getReference(exactKey)
							               .filter(Droppable::exists)
							               .isPresent()) {
						continue;
					}

					final List<ReferenceMutation<?>> mutations = ipkEntry.getValue();
					if (mutations == null || mutations.isEmpty()) {
						continue;
					}

					final ReferenceContract created = evaluateReferenceMutations(null, mutations);
					if (created != null && this.referencePredicate.test(created)) {
						result.add(created);
					}
				}
			}
		}

		return result;
	}

	@Nonnull
	@Override
	public Set<String> getReferenceNames() {
		return getReferences()
			.stream()
			.map(ReferenceContract::getReferenceName)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}
		return getReferences()
			.stream()
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		return getReference(new ReferenceKey(referenceName, referencedEntityId));
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		if (!referencesAvailable(referenceKey.referenceName())) {
			throw ContextMissingException.referenceContextMissing(referenceKey.referenceName());
		}
		if (referenceKey.isUnknownReference() &&
			getReferenceSchemaContract(referenceKey.referenceName())
				.map(ReferenceSchemaContract::getCardinality)
				.map(Cardinality::allowsDuplicates)
				.orElse(false)) {
			throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), getSchema(), Operation.READ);
		}
		final Optional<ReferenceContract> reference = this.baseEntity
			.getReference(referenceKey)
			.map(
				it -> ofNullable(this.referenceMutations)
					.map(mutations -> mutations.get(referenceKey))
					.map(mutations -> getReferenceMutationsByReferenceKey(referenceKey, mutations, Operation.READ))
					.map(mutations -> evaluateReferenceMutations(it, mutations))
					.orElseGet(
						() -> this.baseEntityDecorator == null ?
							it : this.baseEntityDecorator.getReference(referenceKey).orElse(it)
					)
			)
			.or(
				() -> ofNullable(this.referenceMutations)
					.map(mutations -> mutations.get(referenceKey))
					.map(mutations -> getReferenceMutationsByReferenceKey(referenceKey, mutations, Operation.READ))
					.map(mutations -> evaluateReferenceMutations(null, mutations))
			);
		return reference.filter(this.referencePredicate);
	}

	@Nonnull
	@Override
	public List<ReferenceContract> getReferences(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		if (!referencesAvailable(referenceKey.referenceName())) {
			throw ContextMissingException.referenceContextMissing(referenceKey.referenceName());
		}

		// collect base references for the business key
		final List<ReferenceContract> baseReferences = this.baseEntity.getReferences(referenceKey);

		// build a set of known internal PKs for fast membership checks (only for known positive internal PKs)
		final int baseSize = baseReferences.size();
		final HashSet<Integer> knownInternalPks = CollectionUtils.createHashSet(baseSize);

		// fetch relevant mutation bulk only once
		final Map<Integer, List<ReferenceMutation<?>>> mutationBulk =
			this.referenceMutations == null ?
				Map.of() : this.referenceMutations.get(referenceKey);

		// estimate result capacity (upper bound: all base + all new-from-mutations)
		final int mutationCount = mutationBulk != null ? mutationBulk.size() : 0;
		final ArrayList<ReferenceContract> result = new ArrayList<>(baseSize + mutationCount);

		// process existing base references first
		for (int i = 0; i < baseSize; i++) {
			final ReferenceContract baseRef = baseReferences.get(i);
			if (!this.referencePredicate.test(baseRef)) {
				continue;
			}
			if (mutationBulk != null) {
				final ReferenceKey baseRefKey = baseRef.getReferenceKey();
				final int ipk = baseRefKey.internalPrimaryKey();
				if (baseRefKey.isKnownInternalPrimaryKey()) {
					knownInternalPks.add(ipk);
				}

				final List<ReferenceMutation<?>> mutationsForRef = mutationBulk.get(ipk);
				if (mutationsForRef != null) {
					result.add(evaluateReferenceMutations(baseRef, mutationsForRef));
					continue;
				}
			}
			// no mutations for this reference – prefer decorated instance if available
			result.add(
				this.baseEntityDecorator == null ?
					baseRef :
					this.baseEntityDecorator.getReference(referenceKey).orElse(baseRef)
			);
		}

		// now add references created solely by mutations (not tied to existing base references)
		if (mutationBulk != null && !mutationBulk.isEmpty()) {
			for (Map.Entry<Integer, List<ReferenceMutation<?>>> e : mutationBulk.entrySet()) {
				final int internalPkFromMutation = e.getKey();
				// skip those already present in base (we processed them above)
				if (internalPkFromMutation > 0 && knownInternalPks.contains(internalPkFromMutation)) {
					continue;
				}
				final List<ReferenceMutation<?>> mutations = e.getValue();
				// create new reference from NULL input and evaluate predicate
				final ReferenceContract created = evaluateReferenceMutations(null, mutations);
				if (created != null && this.referencePredicate.test(created)) {
					result.add(created);
				}
			}
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	@Override
	public DataChunk<ReferenceContract> getReferenceChunk(
		@Nonnull String referenceName
	) throws ContextMissingException {
		return new PlainChunk<>(this.getReferences(referenceName));
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
		if (!parentAvailable()) {
			throw ContextMissingException.hierarchyContextMissing();
		}
		this.hierarchyMutation = !Objects.equals(this.baseEntity.getParent(), OptionalInt.of(parentPrimaryKey)) ?
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

	@Nonnull
	@Override
	public EntityBuilder updateReferences(
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull UnaryOperator<ReferenceBuilder> whichIs
	) {
		final EntitySchemaContract schema = getSchema();
		for (ReferenceContract reference : getReferences()) {
			if (filter.test(reference)) {
				final ExistingReferenceBuilder builder = new ExistingReferenceBuilder(reference, schema);
				addOrReplaceReferenceMutations(whichIs.apply(builder));
			}
		}
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey
	) {
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
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) {
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
		return setUniqueReferenceInternal(
			getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality),
			new ReferenceKey(referenceName, referencedPrimaryKey),
			referencedEntityType,
			cardinality,
			whichIs
		);
	}

	@Nonnull
	@Override
	public EntityBuilder setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull UnaryOperator<ReferenceBuilder> whichIs
	) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(referenceName);
		return setReference(
			referenceName,
			referenceSchema.getReferencedEntityType(),
			referenceSchema.getCardinality(),
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
		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}

		final Optional<ReferenceSchemaContract> referenceSchema = getReferenceSchemaContract(referenceName);
		referenceSchema.ifPresent(
			theRefSchema -> assertReferenceSchemaCompatibility(theRefSchema, referencedEntityType, cardinality)
		);

		Assert.isTrue(
			this.referencePredicate.test(
				new Reference(
					getSchema(),
					referenceSchema.orElseGet(
						() -> getReferenceSchemaOrCreateImplicit(
							referenceName, referencedEntityType, cardinality
						)
					),
					new ReferenceKey(referenceName, referencedPrimaryKey),
					null
				)
			),
			"References were not fetched and cannot be updated. Please enrich the entity first or load it with the references."
		);
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
		final EntitySchemaContract schema = getSchema();
		final Optional<ReferenceContract> existingReference = this.baseEntity.getReferenceWithoutSchemaCheck(
			referenceKey);
		final ReferenceBuilder referenceBuilder;
		if (existingReference.map(filter::test).orElse(false)) {
			referenceBuilder = whichIs.apply(new ExistingReferenceBuilder(existingReference.get(), schema));
		} else {
			referenceBuilder = whichIs.apply(
				new InitialReferenceBuilder(
					schema,
					getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality),
					referenceName, referencedPrimaryKey,
					getNextReferenceInternalId()
				)
			);
		}
		addOrReplaceReferenceMutations(referenceBuilder);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		removeReferenceInternal(new ReferenceKey(referenceName, referencedPrimaryKey), false);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReference(@Nonnull ReferenceKey referenceKey) throws ReferenceNotKnownException {
		removeReferenceInternal(referenceKey, false);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName, int referencedPrimaryKey) {
		removeReferenceInternal(new ReferenceKey(referenceName, referencedPrimaryKey), true);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName) {
		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}

		return removeReferences(ref -> Objects.equals(referenceName, ref.getReferenceName()));
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull String referenceName, @Nonnull Predicate<ReferenceContract> filter) {
		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}

		final Predicate<ReferenceContract> combinedFilter = ref ->
			Objects.equals(referenceName, ref.getReferenceName()) && filter.test(ref);
		return removeReferences(combinedFilter);
	}

	@Nonnull
	@Override
	public EntityBuilder removeReferences(@Nonnull Predicate<ReferenceContract> filter) {
		final Collection<ReferenceContract> currentReferences = getReferences();
		final Set<ReferenceKey> keysToRemove = new HashSet<>(currentReferences.size());
		for (ReferenceContract currentReference : currentReferences) {
			if (filter.test(currentReference)) {
				keysToRemove.add(currentReference.getReferenceKey());
			}
		}

		for (ReferenceKey referenceKey : keysToRemove) {
			// the reference key is complete - it should never throw exception due to duplicate references
			removeReferenceInternal(referenceKey, false);
		}

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
		if (!attributeAvailable(attributeName)) {
			throw ContextMissingException.attributeContextMissing(attributeName);
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(new AttributeKey(attributeName), -1)),
			"Attribute " + attributeName + " was not fetched and cannot be removed. Please enrich the entity first or load it with attributes."
		);
		this.attributesBuilder.removeAttribute(attributeName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nullable T attributeValue) {
		if (!attributeAvailable(attributeName)) {
			throw ContextMissingException.attributeContextMissing(attributeName);
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(new AttributeKey(attributeName), -1)),
			"Attributes were not fetched and cannot be updated. Please enrich the entity first or load it with attributes. Please enrich the entity first or load it with attributes."
		);
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nullable T[] attributeValue) {
		if (!attributeAvailable(attributeName)) {
			throw ContextMissingException.attributeContextMissing(attributeName);
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(new AttributeKey(attributeName), -1)),
			"Attributes were not fetched and cannot be updated. Please enrich the entity first or load it with attributes. Please enrich the entity first or load it with attributes."
		);
		this.attributesBuilder.setAttribute(attributeName, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		if (!attributeAvailable(attributeName)) {
			throw ContextMissingException.attributeContextMissing(attributeName);
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(new AttributeKey(attributeName, locale), -1)),
			"Attribute " + attributeName + " in locale " + locale + " was not fetched and cannot be removed. Please enrich the entity first or load it with attributes."
		);
		this.attributesBuilder.removeAttribute(attributeName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		if (!attributeAvailable(attributeName)) {
			throw ContextMissingException.attributeContextMissing(attributeName);
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(new AttributeKey(attributeName, locale), -1)),
			"Attributes in locale " + locale + " were not fetched and cannot be updated. Please enrich the entity first or load it with attributes."
		);
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAttribute(
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		if (!attributeAvailable(attributeName)) {
			throw ContextMissingException.attributeContextMissing(attributeName);
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(new AttributeKey(attributeName, locale), -1)),
			"Attributes in locale " + locale + " were not fetched and cannot be updated. Please enrich the entity first or load it with attributes."
		);
		this.attributesBuilder.setAttribute(attributeName, locale, attributeValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		if (!attributeAvailable(mutation.getAttributeKey().attributeName())) {
			throw ContextMissingException.attributeContextMissing(mutation.getAttributeKey().attributeName());
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(mutation.getAttributeKey(), -1)),
			"Attribute " + mutation.getAttributeKey() + " was not fetched and cannot be updated. Please enrich the entity first or load it with attributes."
		);
		this.attributesBuilder.mutateAttribute(mutation);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		if (!associatedDataAvailable(associatedDataName)) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataName);
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be removed. Please enrich the entity first or load it with the associated data."
		);
		this.associatedDataBuilder.removeAssociatedData(associatedDataName);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nullable T associatedDataValue) {
		if (!associatedDataAvailable(associatedDataName)) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataName);
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		this.associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		if (!associatedDataAvailable(associatedDataName)) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataName);
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(new AssociatedDataValue(new AssociatedDataKey(associatedDataName), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		this.associatedDataBuilder.setAssociatedData(associatedDataName, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		if (!associatedDataAvailable(associatedDataName)) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataName);
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(
				new AssociatedDataValue(new AssociatedDataKey(associatedDataName, locale), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be removed. Please enrich the entity first or load it with the associated data."
		);
		this.associatedDataBuilder.removeAssociatedData(associatedDataName, locale);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T associatedDataValue) {
		if (!associatedDataAvailable(associatedDataName)) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataName);
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(
				new AssociatedDataValue(new AssociatedDataKey(associatedDataName, locale), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		this.associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public <T extends Serializable> EntityBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		if (!associatedDataAvailable(associatedDataName)) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataName);
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(
				new AssociatedDataValue(new AssociatedDataKey(associatedDataName, locale), -1)),
			"Associated data " + associatedDataName + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		this.associatedDataBuilder.setAssociatedData(associatedDataName, locale, associatedDataValue);
		return this;
	}

	@Nonnull
	@Override
	public EntityBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		if (!associatedDataAvailable(mutation.getAssociatedDataKey().associatedDataName())) {
			throw ContextMissingException.associatedDataContextMissing(
				mutation.getAssociatedDataKey().associatedDataName());
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(new AssociatedDataValue(mutation.getAssociatedDataKey(), -1)),
			"Associated data " + mutation.getAssociatedDataKey() + " was not fetched and cannot be updated. Please enrich the entity first or load it with the associated data."
		);
		this.associatedDataBuilder.mutateAssociatedData(mutation);
		return this;
	}

	@Override
	public int getNextReferenceInternalId() {
		return --this.lastLocallyAssignedReferenceId;
	}

	@Override
	public void addOrReplaceReferenceMutations(@Nonnull ReferenceBuilder referenceBuilder) {
		// Preconditions
		if (!referencesAvailable(referenceBuilder.getReferenceName())) {
			throw ContextMissingException.referenceContextMissing(referenceBuilder.getReferenceName());
		}

		final ReferenceKey referenceKey = referenceBuilder.getReferenceKey();
		final int internalReferenceKey = referenceKey.internalPrimaryKey();
		Assert.isPremiseValid(
			internalReferenceKey != 0,
			"Internal primary key must be known or locally assigned here!"
		);

		final List<ReferenceMutation<?>> changeSet = new ArrayList<>(16);
		referenceBuilder.buildChangeSet().forEach(changeSet::add);

		final Optional<ReferenceContract> existingReferenceOpt =
			this.baseEntity.getReferenceWithoutSchemaCheck(referenceKey);
		if (existingReferenceOpt.isEmpty()) {
			// no existing reference with this key - we can just add the mutation set
			final Map<Integer, List<ReferenceMutation<?>>> referenceMutationIndex =
				getReferenceMutationsForKey(referenceKey);
			// register change set
			referenceMutationIndex.put(internalReferenceKey, changeSet);

			registerCardinalityAndPromoteNewIfNeeded(
				referenceKey,
				getReferenceSchemaOrThrowException(referenceKey.referenceName()),
				referenceMutationIndex
			);
		} else {
			// There is some existing reference in the base entity - we may need to merge or replace
			final Optional<ReferenceContract> refInBaseOpt = this.baseEntity
				.getReference(referenceKey)
				.filter(Droppable::exists);

			final boolean existsAndNotLocallyRemoved = refInBaseOpt
				.map(ref ->
					     ref.exists() &&
						     !(this.removedReferences != null && this.removedReferences.remove(internalReferenceKey))
				)
				.orElse(true);

			if (existsAndNotLocallyRemoved) {
				// Replace mutation set outright
				getReferenceMutationsForKey(referenceKey).put(internalReferenceKey, changeSet);
			} else {
				// Merge required: we need to restore removals for group/attributes that weren't upserted after previous removal
				boolean groupUpserted = false;
				Set<AttributeKey> attributesUpserted = null;

				// Scan changeSet once to gather upsert info
				for (final ReferenceMutation<?> rm : changeSet) {
					if (rm instanceof SetReferenceGroupMutation) {
						groupUpserted = true;
					} else if (rm instanceof ReferenceAttributeMutation ram) {
						final AttributeMutation am = ram.getAttributeMutation();
						if (am instanceof UpsertAttributeMutation) {
							if (attributesUpserted == null) {
								attributesUpserted = new HashSet<>(8);
							}
							attributesUpserted.add(am.getAttributeKey());
						}
					}
				}

				final Set<AttributeKey> finalAttributesUpserted =
					attributesUpserted != null ? attributesUpserted : Collections.emptySet();

				// Build merged mutation list with minimal allocations
				// Heuristic capacity: original + possible removals
				List<ReferenceMutation<?>> merged = new ArrayList<>(changeSet.size() + 8);

				// If group was NOT upserted, add removal of previous group (if any)
				final ReferenceContract refInBase = refInBaseOpt.get();
				if (!groupUpserted) {
					final Optional<? extends Droppable> groupOpt = refInBase.getGroup();
					if (groupOpt.isPresent()) {
						final Droppable group = groupOpt.get();
						if (group.exists()) {
							merged.add(new RemoveReferenceGroupMutation(referenceKey));
						}
					}
				}

				// For attributes not upserted, add their removals if they existed before
				final Collection<AttributeValue> attrs = refInBase.getAttributeValues();
				for (final AttributeValue av : attrs) {
					if (av.exists()) {
						final AttributeKey key = av.key();
						if (!finalAttributesUpserted.contains(key)) {
							merged.add(
								new ReferenceAttributeMutation(
									referenceKey,
									new RemoveAttributeMutation(key)
								)
							);
						}
					}
				}

				// Append filtered original changeSet (skip redundant ops)
				for (final ReferenceMutation<?> rm : changeSet) {
					// Skip InsertReferenceMutation - the reference existed before the removal
					if (rm instanceof InsertReferenceMutation) {
						continue;
					}

					// Skip redundant SetReferenceGroupMutation if group remains the same
					if (rm instanceof SetReferenceGroupMutation srgm) {
						boolean sameAsBefore = false;
						final Optional<GroupEntityReference> groupOpt = refInBase.getGroup();
						if (groupOpt.isPresent()) {
							final GroupEntityReference group = groupOpt.get();
							final Integer existingPk = group.getPrimaryKey();
							final Integer newPk = srgm.getGroupPrimaryKey();
							sameAsBefore = Objects.equals(existingPk, newPk);
						}
						if (sameAsBefore) {
							continue;
						}
					}

					// Skip redundant attribute upserts if value equals previous one
					if (rm instanceof ReferenceAttributeMutation ram) {
						final AttributeMutation am = ram.getAttributeMutation();
						if (am instanceof UpsertAttributeMutation upsert) {
							boolean sameAsBefore = false;
							final AttributeKey aKey = am.getAttributeKey();

							// Check schema existence before reading value, as in original logic
							final boolean schemaPresent = refInBase
								.getAttributeSchema(aKey.attributeName())
								.isPresent();
							if (schemaPresent) {
								final Optional<AttributeValue> avOpt = refInBase.getAttributeValue(aKey);
								if (avOpt.isPresent()) {
									final AttributeValue prev = avOpt.get();
									sameAsBefore = Objects.equals(prev.value(), upsert.getAttributeValue());
								}
							}
							if (sameAsBefore) {
								continue;
							}
						}
					}

					merged.add(rm);
				}

				getReferenceMutationsForKey(referenceKey)
					.put(internalReferenceKey, merged);
			}
		}
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, boolean indexed
	) {
		assertPricesFetched(this.pricePredicate);
		Assert.isTrue(
			this.pricePredicate.test(
				new Price(
					new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
					false
				)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		this.pricesBuilder.setPrice(priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		boolean indexed
	) {
		assertPricesFetched(this.pricePredicate);
		Assert.isTrue(
			this.pricePredicate.test(
				new Price(
					new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
					false
				)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		this.pricesBuilder.setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax, @Nullable DateTimeRange validity, boolean indexed
	) {
		assertPricesFetched(this.pricePredicate);
		Assert.isTrue(
			this.pricePredicate.test(
				new Price(
					new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
					false
				)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		this.pricesBuilder.setPrice(
			priceId, priceList, currency, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		return this;
	}

	@Override
	public EntityBuilder setPrice(
		int priceId, @Nonnull String priceList, @Nonnull Currency currency, @Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax, @Nonnull BigDecimal taxRate, @Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity, boolean indexed
	) {
		assertPricesFetched(this.pricePredicate);
		Assert.isTrue(
			this.pricePredicate.test(
				new Price(
					new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
					false
				)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		this.pricesBuilder.setPrice(
			priceId, priceList, currency, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed);
		return this;
	}

	@Override
	public EntityBuilder removePrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		assertPricesFetched(this.pricePredicate);
		Assert.isTrue(
			this.pricePredicate.test(
				new Price(
					new PriceKey(priceId, priceList, currency), 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
					false
				)),
			"Price " + priceId + ", " + priceList + ", " + currency + " was not fetched and cannot be updated. Please enrich the entity first or load it with the prices."
		);
		this.pricesBuilder.removePrice(priceId, priceList, currency);
		return this;
	}

	@Override
	public EntityBuilder setPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		assertPricesFetched(this.pricePredicate);
		this.pricesBuilder.setPriceInnerRecordHandling(priceInnerRecordHandling);
		return this;
	}

	@Override
	public EntityBuilder removePriceInnerRecordHandling() {
		assertPricesFetched(this.pricePredicate);
		this.pricesBuilder.removePriceInnerRecordHandling();
		return this;
	}

	@Override
	public EntityBuilder removeAllNonTouchedPrices() {
		assertPricesFetched(this.pricePredicate);
		this.pricesBuilder.removeAllNonTouchedPrices();
		return this;
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
		buildReferenceChangeSet().forEach(mutations::add);
		//noinspection unchecked,rawtypes
		Collections.sort((List) mutations);

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
		return toMutation()
			.map(it -> it.mutate(this.baseEntity.getSchema(), this.baseEntity))
			.orElse(this.baseEntity);
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
	 * Sets a unique reference internally while ensuring compliance with the specified reference schema. The method
	 * validates that the reference conforms to the rules defined in the schema, and may promote cardinality if permissible.
	 * It enforces constraints such as avoiding duplicate references and maintaining schema consistency.
	 *
	 * @param referenceSchema      the schema associated with the reference, used to validate its properties and constraints
	 * @param referenceKey         the unique key identifying the referenced entity; this includes the primary key and internal key
	 * @param referencedEntityType the type of the referenced entity; must match the schema-defined type, if provided
	 * @param cardinality          the cardinality of the reference; if provided, must match the schema-defined cardinality
	 * @param whichIs              an optional consumer for setting additional properties of the reference during its building process
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
		final String referenceName = referenceKey.referenceName();
		final int referencedPrimaryKey = referenceKey.primaryKey();

		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}

		Assert.isTrue(
			this.referencePredicate.isReferenceRequested(referenceName),
			"References were not fetched and cannot be updated. Please enrich the entity first or load it with the references."
		);

		assertReferenceSchemaCompatibility(referenceSchema, referencedEntityType, cardinality);

		// this method cannot be used when duplicates are allowed by schema
		final EntitySchemaContract entitySchema = getSchema();
		final Cardinality schemaCardinality = referenceSchema.getCardinality();
		if (schemaCardinality.allowsDuplicates()) {
			throw new ReferenceAllowsDuplicatesException(referenceName, entitySchema, Operation.WRITE);
		}

		// but we allow implicit cardinality widening when needed
		if (this.referenceMutations != null &&
			// reference has already mutations tied to different internal primary key
			ofNullable(this.referenceMutations.get(referenceKey))
				.map(Map::keySet)
				.map(mutations -> !mutations.contains(referenceKey.internalPrimaryKey()))
				.orElse(false) &&
			// and the schema cardinality does not allow it
			schemaCardinality.getMax() <= 1 &&
			!entitySchema.allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)
		) {
			throw new InvalidMutationException(
				"The reference `" + referenceName + "` is already defined to have `" +
					schemaCardinality + "` cardinality, cannot add another reference to it!"
			);
		}

		final Optional<ReferenceContract> existingReference =
			this.baseEntity.getReferenceWithoutSchemaCheck(referenceKey);
		final ReferenceBuilder referenceBuilder = existingReference
			.map(it -> (ReferenceBuilder) new ExistingReferenceBuilder(it, entitySchema))
			.filter(this.referencePredicate)
			.orElseGet(
				() -> new InitialReferenceBuilder(
					entitySchema,
					referenceSchema,
					referenceName, referencedPrimaryKey,
					getNextReferenceInternalId()
				)
			);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceBuilder));
		addOrReplaceReferenceMutations(referenceBuilder);
		return this;
	}

	/**
	 * Registers the cardinality of the reference and promotes the cardinality to a higher level if needed,
	 * while ensuring compliance with the schema constraints. Updates or elevates the cardinality where
	 * necessary in reference mutations.
	 *
	 * @param referenceKey           the unique key identifying the reference
	 * @param referenceSchema        the schema definition of the reference, which includes cardinality constraints
	 * @param referenceMutationIndex the index mapping reference primary keys to their respective mutations
	 */
	private void registerCardinalityAndPromoteNewIfNeeded(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Map<Integer, List<ReferenceMutation<?>>> referenceMutationIndex
	) {
		// but we need to adapt the cardinality if needed
		if (this.referencesDefinedCount == null) {
			this.referencesDefinedCount = new HashMap<>(8);
		}
		final String referenceName = referenceKey.referenceName();
		if (!this.referencesDefinedCount.containsKey(referenceName)) {
			final Collection<ReferenceContract> references = this.baseEntity.getReferences();
			int counter = 0;
			for (ReferenceContract reference : references) {
				if (reference.exists() && reference.getReferenceKey().referenceName().equals(referenceName)) {
					counter++;
				}
			}
			this.referencesDefinedCount.put(referenceName, counter);
		}

		// register a new reference
		final Integer currentReferenceCount = this.referencesDefinedCount.merge(referenceName, 1, Integer::sum);
		final Cardinality schemaCardinality = referenceSchema.getCardinality();
		final boolean lowCardinality = schemaCardinality.getMax() < currentReferenceCount;
		final boolean hasDuplicates = referenceMutationIndex.size() > 1;
		final boolean duplicateMismatch = hasDuplicates && !schemaCardinality.allowsDuplicates();
		if (lowCardinality || duplicateMismatch) {
			if (!this.getSchema().getEvolutionMode().contains(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
				throw new InvalidMutationException(
					"The reference `" + referenceName + "` is already defined to have `" +
						schemaCardinality + "` cardinality, cannot add another reference to it!"
				);
			} else {
				// we need to promote the cardinality in all insert reference mutations
				final Cardinality elevatedCardinality = schemaCardinality.getMin() == 0 ?
					(hasDuplicates ? Cardinality.ZERO_OR_MORE_WITH_DUPLICATES : Cardinality.ZERO_OR_MORE) :
					(hasDuplicates ? Cardinality.ONE_OR_MORE_WITH_DUPLICATES : Cardinality.ONE_OR_MORE) ;
				for (Entry<Integer, List<ReferenceMutation<?>>> entry : referenceMutationIndex.entrySet()) {
					final List<ReferenceMutation<?>> mutations = entry.getValue();
					final List<ReferenceMutation<?>> newMutations = new ArrayList<>(mutations.size());
					for (ReferenceMutation<?> mutation : mutations) {
						if (mutation instanceof InsertReferenceMutation irm) {
							newMutations.add(irm.withCardinality(elevatedCardinality));
							cacheLocalSchema(
								referenceName,
								ReferenceSchema._internalBuild(referenceSchema, elevatedCardinality)
							);
						} else {
							newMutations.add(mutation);
						}
					}
					entry.setValue(newMutations);
				}
			}
		}
	}

	/**
	 * Resolves the list of mutations for a reference identified by the given business {@link ReferenceKey}.
	 *
	 * The top-level mutation index groups mutations by business key, while the inner map disambiguates
	 * duplicates using an internal primary key. This helper selects the correct inner list using the
	 * following rules:
	 * - If the reference key already contains a known internal primary key, the corresponding bucket is returned.
	 * - If the internal primary key is unknown but there is exactly one bucket, that single bucket is returned.
	 * - Otherwise, the reference allows duplicates and the target bucket cannot be uniquely determined, so a
	 * {@link ReferenceAllowsDuplicatesException} is thrown.
	 *
	 * @param referenceKey non-null business reference key (may or may not contain internal primary key)
	 * @param mutations    non-null map of buckets keyed by internal primary key
	 * @return non-null list of mutations for the resolved reference instance
	 * @throws ReferenceAllowsDuplicatesException when multiple candidates exist and no internal primary key
	 *                                            is available to disambiguate
	 */
	@Nonnull
	private List<ReferenceMutation<?>> getReferenceMutationsByReferenceKey(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull Map<Integer, List<ReferenceMutation<?>>> mutations,
		@Nonnull Operation operation
	) {
		if (referenceKey.isUnknownReference()) {
			if (mutations.size() == 1) {
				return mutations.values().iterator().next();
			} else {
				throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), this.getSchema(), operation);
			}
		} else {
			final List<ReferenceMutation<?>> foundChangeSet = mutations.get(referenceKey.internalPrimaryKey());
			return foundChangeSet == null ? Collections.emptyList() : foundChangeSet;
		}
	}

	/**
	 * Builds and returns a stream of {@link ReferenceMutation} objects that represent the change set
	 * of reference-related mutations to be applied to the base entity. The method aggregates,
	 * filters, and processes the reference mutations stored in the current instance, ensuring that only
	 * effective mutations that increase version or represent non-existent references are included.
	 *
	 * During this process:
	 * - Aggregates all mutations grouped by reference key and associated internal IDs.
	 * - Verifies the integrity of each mutation to ensure it targets the correct reference.
	 * - Applies the mutations in the order they were added, maintaining consistency with the schema and
	 * existing state of the entity's references.
	 *
	 * The returned stream can then be used for further processing or persistence.
	 *
	 * @return a {@link Stream} of filtered {@link ReferenceMutation} objects representing the change set.
	 */
	@Nonnull
	private Stream<ReferenceMutation<?>> buildReferenceChangeSet() {
		if (this.referenceMutations == null || this.referenceMutations.isEmpty()) {
			return Stream.empty();
		} else {
			final EntitySchemaContract schema = getSchema();

			// Pre-size the result list by summing sizes of inner mutation lists.
			// This walks only the maps and list sizes (O(number of lists)), not the mutations themselves.
			int totalCapacity = 0;
			for (Map.Entry<ReferenceKey, Map<Integer, List<ReferenceMutation<?>>>> bulkEntry : this.referenceMutations.entrySet()) {
				final Map<Integer, List<ReferenceMutation<?>>> byInternalId = bulkEntry.getValue();
				for (List<ReferenceMutation<?>> bucket : byInternalId.values()) {
					totalCapacity += bucket.size();
				}
			}

			// presize the result list to avoid multiple resizes
			final List<ReferenceMutation<?>> filtered = new ArrayList<>(totalCapacity);

			// Second pass: actual processing
			for (Map.Entry<ReferenceKey, Map<Integer, List<ReferenceMutation<?>>>> bulkEntry : this.referenceMutations.entrySet()) {
				final ReferenceKey referenceKey = bulkEntry.getKey();
				final Map<Integer, List<ReferenceMutation<?>>> mutationsByInternalId = bulkEntry.getValue();

				// handle each reference one by one
				for (Map.Entry<Integer, List<ReferenceMutation<?>>> entry : mutationsByInternalId.entrySet()) {
					final int internalId = entry.getKey();
					// create the complete reference key - the reference key in the map never has internalPrimaryKey set
					final ReferenceKey composedReferenceKey = new ReferenceKey(
						referenceKey.referenceName(),
						referenceKey.primaryKey(),
						internalId
					);

					// retrieve to base reference if it exists to start mutation from it
					ReferenceContract updatedReference = this.baseEntity.references.get(composedReferenceKey);

					// apply all mutations in the order they were added
					final List<ReferenceMutation<?>> mutations = entry.getValue();
					for (final ReferenceMutation<?> mutation : mutations) {
						final ReferenceKey mutationKey = mutation.getReferenceKey();

						// sanity check - all mutations must target the same reference
						Assert.isPremiseValid(
							composedReferenceKey.equals(mutationKey)
								&& composedReferenceKey.internalPrimaryKey() == mutationKey.internalPrimaryKey(),
							"All mutations must target the same reference!"
						);

						// apply mutation
						final ReferenceContract newReference = mutation.mutateLocal(schema, updatedReference);

						// only keep the mutation if it increases version, or initial version was null (non-existent)
						if (updatedReference == null || newReference.version() > updatedReference.version()) {
							filtered.add(mutation);
							updatedReference = newReference;
						}
					}
				}
			}

			return filtered.stream();
		}
	}

	/**
	 * Retrieves or creates a map containing lists of reference mutations associated with the given reference key.
	 * If the reference key contains an internal primary key, a new key without the internal primary key is used.
	 *
	 * @param referenceKey the reference key for which mutations are being fetched or initialized
	 * @return a map containing lists of reference mutations associated with the provided or derived reference key
	 */
	@Nonnull
	private Map<Integer, List<ReferenceMutation<?>>> getReferenceMutationsForKey(@Nonnull ReferenceKey referenceKey) {
		if (this.referenceMutations == null) {
			this.referenceMutations = CollectionUtils.createHashMap(16);
		}
		// the top level index must contain ReferenceKeys without internalPrimaryKey
		return this.referenceMutations.computeIfAbsent(
			referenceKey.isUnknownReference() ?
				referenceKey :
				new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey()),
			k -> CollectionUtils.createHashMap(16)
		);
	}

	/**
	 * Removes a reference from the entity's internal state based on the reference name and primary key.
	 * This method handles validation of the reference existence, schema, and conditions for allowing or disallowing
	 * the removal of duplicates for references that support duplication.
	 *
	 * @param referenceKey               the key of the reference to be removed
	 * @param allowRemovingAllDuplicates specifies whether all duplicate references are allowed to be removed
	 *                                   (when multiple reference mutations exist for the same primary key)
	 *                                   or if such an operation should raise an exception
	 * @throws ContextMissingException            if the reference context is missing
	 * @throws IllegalArgumentException           if the entity's references were not properly fetched or updated
	 * @throws ReferenceAllowsDuplicatesException if the reference supports duplicates and
	 *                                            the removal operation was disallowed to remove all duplicates
	 */
	private void removeReferenceInternal(
		@Nonnull ReferenceKey referenceKey,
		boolean allowRemovingAllDuplicates
	) {
		final String referenceName = referenceKey.referenceName();
		final int referencedPrimaryKey = referenceKey.primaryKey();

		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}

		boolean removed = false;
		// remove possibly added / updated reference mutation
		final Map<Integer, List<ReferenceMutation<?>>> removedMutations = this.referenceMutations == null ?
			Map.of() :
			this.referenceMutations.remove(
				referenceKey.isUnknownReference() ?
					referenceKey :
					new ReferenceKey(referenceName, referencedPrimaryKey)
			);

		if (removedMutations != null) {
			if (!allowRemovingAllDuplicates && removedMutations.size() > 1) {
				this.referenceMutations.put(referenceKey, removedMutations);
				if (referenceKey.isUnknownReference()) {
					throw new ReferenceAllowsDuplicatesException(referenceName, getSchema(), Operation.WRITE);
				} else {
					removedMutations.remove(referenceKey.internalPrimaryKey());
				}
			}
			// adjust the reference count if we tracked it
			for (List<ReferenceMutation<?>> mutations : removedMutations.values()) {
				for (ReferenceMutation<?> mutation : mutations) {
					if (mutation instanceof InsertReferenceMutation) {
						Assert.isPremiseValid(
							this.referencesDefinedCount != null,
							"Reference `" + referenceName + "` count is expected to be tracked in the builder!"
						);
						this.referencesDefinedCount.merge(referenceName, -1, Integer::sum);
					}
				}
			}
			removed = true;
		}

		final Optional<ReferenceContract> theReference = this.baseEntity
			.getReferenceWithoutSchemaCheck(referenceKey)
			.filter(Droppable::exists)
			.filter(this.referencePredicate);

		if (theReference.isPresent()) {
			// if the reference was part of the previous entity version we build upon, remove it as-well
			final ReferenceKey completeReferenceKey = theReference.get().getReferenceKey();
			getReferenceMutationsForKey(referenceKey)
				.put(
					completeReferenceKey.internalPrimaryKey(),
					Collections.singletonList(
						new RemoveReferenceMutation(completeReferenceKey)
					)
				);
			if (this.removedReferences == null) {
				this.removedReferences = new HashSet<>(4);
			}
			this.removedReferences.add(completeReferenceKey.internalPrimaryKey());
			removed = true;
		}

		Assert.isTrue(
			removed,
			"There's no reference of a type `" + referenceName + "` and primary key `" + referencedPrimaryKey + "`!"
		);
	}

	/**
	 * Evaluates and applies a list of reference mutations to a given reference.
	 * If the mutations result in a modified reference that differs from the original,
	 * the method validates the modified reference against the original data/state.
	 * Depending on the validation and presence of a decorator, the reference may be
	 * wrapped and returned as a `ReferenceDecorator`.
	 *
	 * @param reference The original reference to be mutated, can be null.
	 * @param mutations The list of mutations to be applied to the reference, must not be null.
	 * @return The mutated reference, potentially wrapped in a decorator if validation occurs and passes.
	 * May return null if the input reference is null and no valid reference is created.
	 */
	@Nullable
	private ReferenceContract evaluateReferenceMutations(
		@Nullable ReferenceContract reference,
		@Nonnull List<ReferenceMutation<?>> mutations
	) {
		ReferenceContract mutatedReference = reference;
		for (ReferenceMutation<?> mutation : mutations) {
			mutatedReference = mutation.mutateLocal(this.baseEntity.schema, mutatedReference);
		}
		final ReferenceContract theReference = mutatedReference != null && mutatedReference.differsFrom(reference) ?
			mutatedReference :
			reference;
		if (this.baseEntityDecorator != null && theReference != null) {
			final Optional<ReferenceContract> originalReference = this.baseEntityDecorator.getReference(
				theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey()
			);
			final Optional<SealedEntity> originalReferencedEntity = originalReference
				.flatMap(ReferenceContract::getReferencedEntity);
			final Optional<SealedEntity> originalReferencedEntityGroup = originalReference
				.flatMap(ReferenceContract::getGroupEntity);
			final Boolean entityValid = originalReferencedEntity
				.map(EntityContract::getPrimaryKey)
				.map(it -> it == theReference.getReferencedPrimaryKey())
				.orElse(false);
			final Boolean entityGroupValid = originalReferencedEntityGroup
				.map(EntityContract::getPrimaryKey)
				.map(
					it -> theReference.getGroup()
					                  .map(group -> Objects.equals(it, group.getPrimaryKey()))
					                  .orElse(false)
				)
				.orElse(false);
			if (entityValid || entityGroupValid) {
				return new ReferenceDecorator(
					theReference,
					entityValid ? originalReferencedEntity.get() : null,
					entityGroupValid ? originalReferencedEntityGroup.get() : null,
					this.referencePredicate.getAttributePredicate(theReference.getReferenceName())
				);
			}
		}
		return theReference;
	}

	/**
	 * Retrieves the reference schema associated with the specified reference name.
	 * If the schema is not found, an exception is thrown.
	 *
	 * @param referenceName the name of the reference whose schema is to be retrieved
	 * @return the reference schema associated with the given reference name
	 * @throws ReferenceNotKnownException if no reference schema is found for the specified reference name
	 */
	@Nonnull
	private ReferenceSchemaContract getReferenceSchemaOrThrowException(@Nonnull String referenceName) {
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
			.orElseGet(
				() -> {
					if (referencedEntityType == null || cardinality == null) {
						throw new ReferenceNotKnownException(referenceName);
					}
					final ReferenceSchema implicit = Reference.createImplicitSchema(
						referenceName, referencedEntityType, cardinality, null
					);
					cacheLocalSchema(referenceName, implicit);
					return implicit;
				}
			);
	}

	/**
	 * Caches the provided reference schema locally by its reference name.
	 *
	 * @param referenceName the unique name identifying the reference schema; must not be null
	 * @param referenceSchema the reference schema contract to be cached; must not be null
	 */
	private void cacheLocalSchema(
		@Nonnull String referenceName,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		if (this.localReferenceSchemas == null) {
			this.localReferenceSchemas = new HashMap<>(4);
		}
		this.localReferenceSchemas.put(referenceName, referenceSchema);
	}

	/**
	 * Retrieves the reference schema associated with the given reference name.
	 *
	 * @param referenceName The name of the reference for which the schema is requested. Must not be null.
	 * @return An {@link Optional} containing the {@link ReferenceSchemaContract} if found, or an empty {@link Optional} if not found.
	 */
	@Nonnull
	private Optional<ReferenceSchemaContract> getReferenceSchemaContract(@Nonnull String referenceName) {
		return Optional
			.ofNullable(this.localReferenceSchemas)
			.map(it -> it.get(referenceName))
			.or(() -> getSchema().getReference(referenceName))
			.or(
				() -> {
					final Collection<ReferenceContract> references = this.baseEntity.getReferences();
					for (ReferenceContract reference : references) {
						if (reference.exists() && reference.getReferenceName().equals(
							referenceName)) {
							cacheLocalSchema(referenceName, reference.getReferenceSchemaOrThrow());
							return reference.getReferenceSchema();
						}
					}
					return empty();
				}
			);
	}

}
