/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException.CardinalityViolation;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditMode;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.data.structure.InitialReferencesBuilder.assertReferenceSchemaCompatibility;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Builder that is used to alter existing {@link References}. References is immutable object so there is need for
 * another object that would simplify the process of updating its contents. This is why the builder class exists.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ExistingReferencesBuilder implements ReferencesBuilder {
	@Serial private static final long serialVersionUID = -3943068011659070539L;
	/**
	 * Entity schema if available.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Initial set of references that is going to be modified by this builder.
	 */
	private final References baseReferences;
	/**
	 * This predicate filters out references that were not fetched in query.
	 */
	@Getter private final ReferenceContractSerializablePredicate referencePredicate;
	/**
	 * Function, that allows accessing rich form of references from an {@link EntityDecorator} if available.
	 */
	private final Function<ReferenceKey, Optional<ReferenceContract>> richReferenceFetcher;
	/**
	 * Helper associated object tracking duplicates.
	 */
	@Nullable private Map<String, BuilderReferenceBundle> referenceBundles;
	/**
	 * Collected reference mutations grouped by business {@link ReferenceKey} and disambiguated
	 * by internal primary key. The outer key never contains the internal primary key.
	 */
	@Nullable private Map<ReferenceKey, Map<Integer, List<ReferenceMutation<?>>>> referenceMutations;
	/**
	 * Contains reference schemas with locally promoted cardinality or locally resolved.
	 */
	@Nullable private Map<String, ReferenceSchemaContract> localReferenceSchemas;
	/**
	 * Contains memoized result of {@link #getReferences()} to avoid multiple evaluations of the same
	 * potentially expensive operation.
	 */
	@Nullable private Collection<ReferenceContract> memoizedReferences;
	/**
	 * Tracks internal primary keys of references removed during this build session to allow
	 * proper merging semantics when a previously removed reference is upserted again.
	 */
	private Set<FullyComparableReferenceKey> removedReferences;
	/**
	 * Internal sequence that is used to generate unique negative reference ids for references that
	 * don't have it assigned yet.
	 */
	private int lastLocallyAssignedReferenceId = 0;

	/**
	 * This method upserts a new mutation into an existing list of reference mutations.
	 * The logic ensures that the new mutation either replaces or coexists with compatible
	 * mutations based on specific rules. If necessary, certain mutations in the existing
	 * list are removed to maintain coherence with the new mutation.
	 *
	 * @param existingChangeSet A list of {@link ReferenceMutation} objects representing
	 *                          the current state of mutations. This list may be modified
	 *                          by the method, with elements added, replaced, or removed
	 *                          based on the logic provided.
	 * @param newMutation       A {@link ReferenceMutation} object representing the new mutation
	 *                          to be added to the existing change set. The new mutation
	 *                          is evaluated relative to the existing list to determine
	 *                          if it should replace or coexist with other mutations.
	 */
	private static void upsertModification(
		@Nonnull List<ReferenceMutation<?>> existingChangeSet,
		@Nonnull ReferenceMutation<?> newMutation
	) {
		boolean addNewMutation = true;
		final Iterator<ReferenceMutation<?>> it = existingChangeSet.iterator();
		while (it.hasNext()) {
			final ReferenceMutation<?> existingMutation = it.next();
			if (existingMutation instanceof InsertReferenceMutation) {
				Assert.isPremiseValid(
					!(newMutation instanceof RemoveReferenceMutation),
					"Unexpected new mutation!"
				);
			} else if (existingMutation instanceof SetReferenceGroupMutation) {
				if (newMutation instanceof SetReferenceGroupMutation) {
					it.remove();
				} else if (newMutation instanceof RemoveReferenceGroupMutation) {
					it.remove();
					addNewMutation = false;
				}
			} else if (existingMutation instanceof RemoveReferenceGroupMutation) {
				if (newMutation instanceof SetReferenceGroupMutation || newMutation instanceof RemoveReferenceGroupMutation) {
					it.remove();
				}
			} else if (existingMutation instanceof ReferenceAttributeMutation existingRam) {
				if (newMutation instanceof ReferenceAttributeMutation newRam) {
					final AttributeKey existingKey = existingRam.getAttributeMutation().getAttributeKey();
					final AttributeKey newKey = newRam.getAttributeMutation().getAttributeKey();
					if (existingKey.equals(newKey)) {
						it.remove();
					}
				}
			} else if (existingMutation instanceof RemoveReferenceMutation) {
				it.remove();
			} else {
				throw new GenericEvitaInternalError("Unexpected mutation type: " + existingMutation.getClass());
			}
		}
		// when we are reinserting or removing the reference - all previous mutations are irrelevant
		if (newMutation instanceof InsertReferenceMutation || newMutation instanceof RemoveReferenceMutation) {
			existingChangeSet.clear();
		}
		if (addNewMutation) {
			existingChangeSet.add(newMutation);
		}
	}

	/**
	 * Merges the given reference mutation change set with the current state of the reference, ensuring that
	 * the resulting mutations reflect any removals and redundant operations are skipped. This method is designed
	 * to restore removals for groups or attributes that were not re-upserted after a prior removal, ensuring the
	 * minimal and correct set of mutations is generated.
	 *
	 * @param changeSet    a list of reference mutations to be merged and filtered
	 * @param refInBase    the base reference state, which is used to compare the existing state with the incoming mutations
	 * @param referenceKey the key that identifies the reference being mutated
	 * @return a list of merged and filtered reference mutations, including any necessary removal mutations and excluding redundant operations
	 */
	@Nonnull
	private static List<ReferenceMutation<?>> collectMergedReferenceMutations(
		@Nonnull List<ReferenceMutation<?>> changeSet,
		@Nonnull ReferenceContract refInBase,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		// Merge required: we need to restore removals for group/attributes that weren't upserted after previous removal
		boolean groupUpserted = false;
		Set<AttributeKey> attributesUpsertedOrRemoved = null;

		// Scan changeSet once to gather upsert info
		for (final ReferenceMutation<?> rm : changeSet) {
			if (rm instanceof SetReferenceGroupMutation) {
				groupUpserted = true;
			} else if (rm instanceof ReferenceAttributeMutation ram) {
				final AttributeMutation am = ram.getAttributeMutation();
				if (am instanceof UpsertAttributeMutation || am instanceof RemoveAttributeMutation) {
					if (attributesUpsertedOrRemoved == null) {
						attributesUpsertedOrRemoved = new HashSet<>(8);
					}
					attributesUpsertedOrRemoved.add(am.getAttributeKey());
				}
			}
		}

		final Set<AttributeKey> finalAttributesUpsertedOrRemoved =
			attributesUpsertedOrRemoved != null ? attributesUpsertedOrRemoved : Collections.emptySet();

		// Build merged mutation list with minimal allocations
		// Heuristic capacity: original + possible removals
		List<ReferenceMutation<?>> merged = new ArrayList<>(changeSet.size() + 8);

		// If group was NOT upserted, add removal of previous group (if any)
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
				if (!finalAttributesUpsertedOrRemoved.contains(key)) {
					final Serializable defaultValue = referenceSchema
						.getAttribute(key.attributeName())
						.map(AttributeSchemaContract::getDefaultValue)
						.orElse(null);
					if (defaultValue == null) {
						merged.add(
							new ReferenceAttributeMutation(referenceKey, new RemoveAttributeMutation(key))
						);
					} else {
						merged.add(
							new ReferenceAttributeMutation(
								referenceKey,
								new UpsertAttributeMutation(key, defaultValue)
							)
						);
					}
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
		return merged;
	}

	ExistingReferencesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull References baseReferences,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull Function<ReferenceKey, Optional<ReferenceContract>> richReferenceFetcher
	) {
		this.entitySchema = entitySchema;
		this.baseReferences = baseReferences;
		this.referencePredicate = referencePredicate;
		this.richReferenceFetcher = richReferenceFetcher;
		this.referenceBundles = null;
	}

	@Override
	public boolean referencesAvailable() {
		return this.baseReferences.referencesAvailable();
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return this.baseReferences.referencesAvailable(referenceName);
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		if (this.memoizedReferences == null) {
			if (!referencesAvailable()) {
				throw ContextMissingException.referenceContextMissing();
			}

			final List<ReferenceContract> result = new ArrayList<>(
				Math.max(16, this.baseReferences.getReferences().size()));

			for (ReferenceContract baseRef : this.baseReferences.getReferences()) {
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
					result.add(
						retainRichDataIfPossible(toAdd)
					);
				}
			}

			// Handle references that exist only in queued mutations (i.e., not present on base entity)
			if (this.referenceMutations != null) {
				for (Map.Entry<ReferenceKey, Map<Integer, List<ReferenceMutation<?>>>> entry : this.referenceMutations.entrySet()) {
					final ReferenceKey refKey = entry.getKey(); // business key (name + PK), internal PK is always 0
					final Map<Integer, List<ReferenceMutation<?>>> byIpk = entry.getValue();

					final Set<String> baseReferenceNames = this.baseReferences.getReferenceNames();
					for (Map.Entry<Integer, List<ReferenceMutation<?>>> ipkEntry : byIpk.entrySet()) {
						final int ipk = ipkEntry.getKey();
						// we derive an exact reference key from the internal primary keys of the internal map
						// that collects mutations for a particular reference
						final ReferenceKey exactKey = new ReferenceKey(
							refKey.referenceName(), refKey.primaryKey(), ipk);

						// now we check that the base entity doesn't have a reference with such a particular internal primary key
						// because this was already handled in the previous loop
						if (
							baseReferenceNames.contains(exactKey.referenceName()) &&
								this.baseReferences.getReference(exactKey)
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

			this.memoizedReferences = result;
			return result;
		}
		return this.memoizedReferences;
	}

	/**
	 * Attempts to enrich the given {@link ReferenceContract} with additional data if possible, such as the referenced entity
	 * or the entity group, ensuring data consistency based on primary key validation. If the referenced entity or group
	 * does not match the provided reference's primary key or group, the original reference is returned unmodified.
	 *
	 * @param reference the reference that needs to be checked and potentially enriched with additional rich data
	 * @return a possibly enhanced {@link ReferenceContract} enriched with additional data if validation is successful,
	 * or the original reference if enrichment is not possible
	 */
	@Nonnull
	private ReferenceDecorator retainRichDataIfPossible(@Nonnull ReferenceContract reference) {
		final Optional<ReferenceContract> originalReference =
			reference.getReferenceKey().isKnownInternalPrimaryKey() ?
				this.richReferenceFetcher.apply(reference.getReferenceKey()) : empty();
		final Optional<SealedEntity> originalReferencedEntity = originalReference
			.flatMap(ReferenceContract::getReferencedEntity);
		final Optional<SealedEntity> originalReferencedEntityGroup = originalReference
			.flatMap(ReferenceContract::getGroupEntity);
		final Boolean entityValid = originalReferencedEntity
			.map(EntityContract::getPrimaryKey)
			.map(it -> it == reference.getReferencedPrimaryKey())
			.orElse(false);
		final Boolean entityGroupValid = originalReferencedEntityGroup
			.map(EntityContract::getPrimaryKey)
			.map(
				it -> reference.getGroup()
				                  .map(group -> Objects.equals(it, group.getPrimaryKey()))
				                  .orElse(false)
			)
			.orElse(false);
		if (entityValid || entityGroupValid) {
			return new ReferenceDecorator(
				reference,
				entityValid ? originalReferencedEntity.get() : null,
				entityGroupValid ? originalReferencedEntityGroup.get() : null,
				reference.getReferenceKey().isKnownInternalPrimaryKey() ?
					this.referencePredicate.getAttributePredicate(reference.getReferenceName()) :
					this.referencePredicate.getAllAttributePredicate()
			);
		} else if (reference instanceof ReferenceDecorator referenceDecorator) {
			return referenceDecorator;
		} else {
			return new ReferenceDecorator(
				reference, null, null,
				reference.getReferenceKey().isKnownInternalPrimaryKey() ?
					this.referencePredicate.getAttributePredicate(reference.getReferenceName()) :
					this.referencePredicate.getAllAttributePredicate()
			);
		}
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
		final String referenceName = referenceKey.referenceName();
		assertReferenceAvailableAndMatchPredicate(referenceName);

		if (referenceKey.isUnknownReference() &&
			getReferenceSchemaContract(referenceName)
				.map(ReferenceSchemaContract::getCardinality)
				.map(Cardinality::allowsDuplicates)
				.orElse(false)) {
			throw new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.READ);
		}
		final Optional<ReferenceContract> reference = this.baseReferences
			.getReferenceWithoutSchemaCheck(referenceKey)
			.map(
				it -> ofNullable(this.referenceMutations)
					.map(mutations -> mutations.get(referenceKey))
					.map(mutations -> getReferenceMutationsByReferenceKey(referenceKey, mutations, Operation.READ))
					.map(mutations -> {
						final ReferenceContract mutatedReference = evaluateReferenceMutations(it, mutations);
						if (mutatedReference == null) {
							return null;
						} else {
							return (ReferenceContract) retainRichDataIfPossible(mutatedReference);
						}
					})
					.orElseGet(() -> this.richReferenceFetcher.apply(referenceKey).orElse(it))
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
		@Nonnull String referenceName,
		int referencedEntityId
	) throws ContextMissingException, ReferenceNotFoundException {
		return getReferences(new ReferenceKey(referenceName, referencedEntityId));
	}

	@Nonnull
	@Override
	public List<ReferenceContract> getReferences(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		return getReferencesInternal(referenceKey, true);
	}

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> getReferenceChunk(
		@Nonnull String referenceName
	) throws ContextMissingException {
		return new PlainChunk<>(this.getReferences(referenceName));
	}

	@Nonnull
	@Override
	public ReferencesBuilder updateReferences(
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		if (!referencesAvailable()) {
			throw ContextMissingException.referenceContextMissing();
		}
		for (ReferenceContract reference : getReferences()) {
			if (filter.test(reference)) {
				final ExistingReferenceBuilder builder = new ExistingReferenceBuilder(
					reference,
					this.entitySchema,
					getReferenceBundleForUpdate(reference.getReferenceName(), 8)
						.getAttributeTypes()
				);
				whichIs.accept(builder);
				addOrReplaceReferenceMutations(builder, true);
			}
		}
		return this;
	}

	@Nonnull
	@Override
	public ReferencesBuilder setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey
	) {
		return setUniqueReferenceInternal(
			getReferenceSchemaOrThrowException(referenceName),
			new ReferenceKey(referenceName, referencedPrimaryKey),
			null,
			null,
			ReferenceEditMode.RESET,
			null
		);
	}

	@Nonnull
	@Override
	public ReferencesBuilder setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) {
		return setUniqueReferenceInternal(
			getReferenceSchemaOrThrowException(referenceName),
			new ReferenceKey(referenceName, referencedPrimaryKey),
			null,
			null,
			ReferenceEditMode.RESET,
			whichIs
		);
	}

	@Nonnull
	@Override
	public ReferencesBuilder updateReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) throws ReferenceNotKnownException {
		return setUniqueReferenceInternal(
			getReferenceSchemaOrThrowException(referenceName),
			new ReferenceKey(referenceName, referencedPrimaryKey),
			null,
			null,
			ReferenceEditMode.UPDATE_ONLY,
			whichIs
		);
	}

	@Nonnull
	@Override
	public ReferencesBuilder setOrUpdateReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(referenceName);
		return setOrUpdateReference(
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
	public ReferencesBuilder setReference(
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
			ReferenceEditMode.RESET,
			null
		);
	}

	@Nonnull
	@Override
	public ReferencesBuilder setReference(
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
			ReferenceEditMode.RESET,
			whichIs
		);
	}

	@Nonnull
	@Override
	public ReferencesBuilder updateReference(
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
			ReferenceEditMode.UPDATE_ONLY,
			whichIs
		);
	}

	@Nonnull
	@Override
	public ReferencesBuilder setOrUpdateReference(
		@Nonnull String referenceName,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		assertReferenceAvailableAndMatchPredicate(referenceName);

		final Optional<ReferenceSchemaContract> referenceSchema = getReferenceSchemaContract(referenceName);
		referenceSchema.ifPresent(
			theRefSchema -> assertReferenceSchemaCompatibility(theRefSchema, referencedEntityType, cardinality)
		);

		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
		final EntitySchemaContract schema = this.entitySchema;
		final List<ReferenceContract> existingReferences = getReferencesInternal(referenceKey, false);
		ReferenceContract selectedReference = null;
		for (ReferenceContract existingReference : existingReferences) {
			if (filter.test(existingReference)) {
				Assert.isTrue(
					selectedReference == null,
					() -> new ReferenceAllowsDuplicatesException(referenceName, schema, Operation.WRITE_MULTIPLE_MATCHES)
				);
				selectedReference = existingReference;
			}
		}
		final ReferenceBuilder referenceBuilder;
		if (selectedReference == null) {
			referenceBuilder = new InitialReferenceBuilder(
				schema,
				getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality),
				referenceName, referencedPrimaryKey,
				getNextReferenceInternalId(),
				getReferenceBundleForUpdate(referenceName, 8)
					.getAttributeTypes()
			);
		} else {
			referenceBuilder = new ExistingReferenceBuilder(
				selectedReference,
				schema,
				getReferenceBundleForUpdate(referenceName, 8)
					.getAttributeTypes()
			);
		}
		whichIs.accept(referenceBuilder);
		addOrReplaceReferenceMutations(referenceBuilder, true);
		return this;
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		removeReferenceInternal(new ReferenceKey(referenceName, referencedPrimaryKey), false);
		return this;
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReference(@Nonnull ReferenceKey referenceKey) throws ReferenceNotKnownException {
		removeReferenceInternal(referenceKey, false);
		return this;
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(@Nonnull String referenceName, int referencedPrimaryKey) {
		removeReferenceInternal(new ReferenceKey(referenceName, referencedPrimaryKey), true);
		return this;
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(@Nonnull String referenceName) {
		assertReferenceAvailableAndMatchPredicate(referenceName);
		return removeReferences(ref -> Objects.equals(referenceName, ref.getReferenceName()));
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(
		@Nonnull String referenceName,
		@Nonnull Predicate<ReferenceContract> filter
	) {
		assertReferenceAvailableAndMatchPredicate(referenceName);
		final Predicate<ReferenceContract> combinedFilter = ref ->
			Objects.equals(referenceName, ref.getReferenceName()) && filter.test(ref);
		return removeReferences(combinedFilter);
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(@Nonnull Predicate<ReferenceContract> filter) {
		if (!referencesAvailable()) {
			throw ContextMissingException.referenceContextMissing();
		}
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

	/**
	 * Adds a new mutation to the collection of reference mutations.
	 *
	 * @param referenceMutation the mutation to be added, which defines changes to a specific reference.
	 *                          The mutation includes details such as the reference key and any required
	 *                          updates or additions to the reference schema.
	 */
	public void addMutation(@Nonnull ReferenceMutation<?> referenceMutation) {
		if (referenceMutation instanceof InsertReferenceMutation irs) {
			// we need to resolve the referenced entity type and cardinality from schema
			if (irs.getReferencedEntityType() == null || irs.getReferenceCardinality() == null) {
				final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(
					referenceMutation.getReferenceKey().referenceName()
				);
				referenceMutation = irs.withReferenceTo(
					referenceSchema.getReferencedEntityType(),
					referenceSchema.getCardinality()
				);
			} else {
				// check we have access to the reference
				getReferenceSchemaOrCreateImplicit(
					referenceMutation.getReferenceKey().referenceName(),
					irs.getReferencedEntityType(),
					irs.getReferenceCardinality()
				);
			}
		}
		getReferenceMutationsForKey(referenceMutation.getReferenceKey())
			.computeIfAbsent(
				referenceMutation.getReferenceKey().internalPrimaryKey(),
				k -> new ArrayList<>(8)
			).add(referenceMutation);
		this.memoizedReferences = null;
	}

	@Nonnull
	@Override
	public Stream<? extends ReferenceMutation<?>> buildChangeSet() {
		if (this.referenceMutations == null || this.referenceMutations.isEmpty()) {
			return Stream.empty();
		} else {
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
					ReferenceContract updatedReference = this.baseReferences
						.getReferenceWithoutSchemaCheck(composedReferenceKey)
						.orElse(null);

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
						final ReferenceContract newReference = mutation.mutateLocal(
							this.entitySchema,
							updatedReference,
							getReferenceBundleForUpdate(mutationKey.referenceName(), 8)
								.getAttributeTypes()
						);

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

	@Override
	public int getNextReferenceInternalId() {
		if (this.lastLocallyAssignedReferenceId == 0) {
			// take lowest negative internal PK from existing references
			this.lastLocallyAssignedReferenceId = Math.min(
				0,
				getReferences()
					.stream()
					.mapToInt(it -> it.getReferenceKey().internalPrimaryKey())
					.min()
					.orElse(0)
			);
		}
		return --this.lastLocallyAssignedReferenceId;
	}

	@Nonnull
	@Override
	public ReferenceKey createReference(@Nonnull String referenceName, int referencedEntityPrimaryKey) {
		final InitialReferenceBuilder referenceBuilder = new InitialReferenceBuilder(
			this.entitySchema,
			getReferenceSchemaOrThrowException(referenceName),
			referenceName, referencedEntityPrimaryKey,
			getNextReferenceInternalId(),
			getReferenceBundleForUpdate(referenceName, 8)
				.getAttributeTypes()
		);
		addOrReplaceReferenceMutations(referenceBuilder, true);
		return referenceBuilder.getReferenceKey();
	}

	@Override
	public void addOrReplaceReferenceMutations(
		@Nonnull ReferenceBuilder referenceBuilder,
		boolean methodAllowsDuplicates
	) {
		// Preconditions
		final String referenceName = referenceBuilder.getReferenceName();
		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}

		// if the reference is new - we need to adapt its internal key to the one assigned here
		if (!methodAllowsDuplicates && referenceBuilder instanceof InitialReferenceBuilder irb) {
			irb.remapInternalKeyUsing(referenceKey -> {
				// try to find new reference with the same business key in this builder
				final Map<Integer, List<ReferenceMutation<?>>> existingMutations = this.referenceMutations == null ?
					null : this.referenceMutations.get(referenceKey);
				if (existingMutations != null && existingMutations.size() == 1) {
					return new ReferenceKey(
						referenceKey.referenceName(),
						referenceKey.primaryKey(),
						existingMutations.keySet().iterator().next()
					);
				} else {
					return null;
				}
			});
		}

		final ReferenceKey referenceKey = referenceBuilder.getReferenceKey();
		final int internalReferenceKey = referenceKey.internalPrimaryKey();
		Assert.isPremiseValid(
			internalReferenceKey != 0 ||
				// TOBEDONE #538 - due to backward compatibility with 2025.6 we need to relax this condition
				// until all reflected references gets reassigned to proper internal PKs gradually
				getReferenceSchemaOrThrowException(referenceName) instanceof ReflectedReferenceSchemaContract,
			"Internal primary key must be known or locally assigned here!"
		);

		final Optional<ReferenceContract> existingReferenceOpt =
			this.baseReferences.getReferenceWithoutSchemaCheck(referenceKey);

		final Map<Integer, List<ReferenceMutation<?>>> referenceMutationsIndex = getReferenceMutationsForKey(
			referenceKey
		);
		final List<ReferenceMutation<?>> changeSet = ofNullable(referenceMutationsIndex)
			.map(it -> it.get(referenceKey.internalPrimaryKey()))
			.map(ArrayList::new)
			.orElseGet(() -> new ArrayList<>(16));
		referenceBuilder
			.buildChangeSet()
			.forEach(newMutation -> upsertModification(changeSet, newMutation));

		final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(
			referenceKey.referenceName(), 8
		);

		if (existingReferenceOpt.isEmpty()) {
			// no existing reference with this key - we can just add the mutation set
			final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(referenceKey.referenceName());
			replaceChangeSet(
				internalReferenceKey,
				referenceBuilder,
				referenceBundle,
				referenceMutationsIndex,
				registerCardinalityAndPromoteNewIfNeeded(
					referenceKey,
					referenceSchema,
					referenceMutationsIndex,
					changeSet
				)
			);
		} else {
			// There is some existing reference in the base entity - we may need to merge or replace
			final Optional<ReferenceContract> refInBaseOpt = this.baseReferences
				.getReference(referenceKey)
				.filter(Droppable::exists);

			final boolean existsAndNotLocallyRemoved = refInBaseOpt
				.map(ref ->
					     ref.exists() &&
						     !(
							     this.removedReferences != null &&
								     this.removedReferences.remove(new FullyComparableReferenceKey(referenceKey))
						     )
				)
				.orElse(true);

			if (existsAndNotLocallyRemoved) {
				replaceChangeSet(
					internalReferenceKey, referenceBuilder, referenceBundle, referenceMutationsIndex, changeSet);
			} else {
				final ReferenceSchemaContract referenceSchema = getReferenceSchemaOrThrowException(referenceKey.referenceName());
				final List<ReferenceMutation<?>> merged = collectMergedReferenceMutations(
					changeSet, refInBaseOpt.get(), referenceKey, referenceSchema
				);

				replaceChangeSet(
					internalReferenceKey, referenceBuilder, referenceBundle, referenceMutationsIndex, merged);
			}
		}

		this.memoizedReferences = null;
	}

	@Nonnull
	@Override
	public References build() {
		if (isThereAnyChangeInMutations()) {
			return new References(
				this.entitySchema,
				getReferences(),
				getReferenceNames(),
				this.baseReferences.getReferenceChunkTransformer()
			);
		} else {
			return this.baseReferences;
		}
	}

	/**
	 * Checks if there are any changes in the mutations by determining
	 * whether the reference mutations list is non-null and not empty.
	 *
	 * @return true if the reference mutations list is not null and not empty;
	 * false otherwise.
	 */
	public boolean isThereAnyChangeInMutations() {
		return this.referenceMutations != null && !this.referenceMutations.isEmpty();
	}

	/**
	 * Replaces the current set of mutations (change set) associated with the given internal reference key
	 * in the reference mutations index. The method also updates or inserts the reference into the specified
	 * reference bundle, handling duplicate references if necessary.
	 *
	 * @param internalReferenceKey    The unique identifier (key) of the internal reference whose change set
	 *                                is to be replaced in the reference mutations index.
	 * @param reference               The {@link ReferenceContract} object representing the reference to be updated or inserted.
	 *                                Must not be null.
	 * @param referenceBundle         The {@link BuilderReferenceBundle} object representing the bundle where the reference
	 *                                should be updated or inserted. Must not be null.
	 * @param referenceMutationsIndex A map that maintains associations between internal reference keys and
	 *                                their corresponding lists of reference mutations. Must not be null.
	 * @param changeSet               A list of {@link ReferenceMutation} objects representing the new change set to be associated
	 *                                with the specified internal reference key in the reference mutations index. Must not be null.
	 */
	private void replaceChangeSet(
		int internalReferenceKey,
		@Nonnull ReferenceContract reference,
		@Nonnull BuilderReferenceBundle referenceBundle,
		@Nonnull Map<Integer, List<ReferenceMutation<?>>> referenceMutationsIndex,
		@Nonnull List<ReferenceMutation<?>> changeSet
	) {
		referenceBundle.upsertWithDuplicateReferenceConversion(
			reference,
			rk -> getReference(rk)
				.orElseThrow(() -> new GenericEvitaInternalError("Reference disappeared!"))
		);

		referenceMutationsIndex.put(internalReferenceKey, changeSet);
	}

	/**
	 * Retrieves a list of references associated with the specified `ReferenceKey`,
	 * applying optional schema validation and filtering based on a predicate.
	 * The method processes both base references and those created or altered through mutations.
	 *
	 * @param referenceKey the key that identifies the references to retrieve.
	 * @param schemaCheck  a flag indicating whether schema validation should be performed.
	 *                     If true, base references will be validated against the schema.
	 * @return an ArrayList of `ReferenceContract` objects, representing the references
	 * associated with the specified key. May include references created solely
	 * by mutations, provided they pass the predicate filter.
	 * @throws ContextMissingException if the reference context is unavailable for the given reference name.
	 */
	@Nonnull
	private ArrayList<ReferenceContract> getReferencesInternal(
		@Nonnull ReferenceKey referenceKey,
		boolean schemaCheck
	) {
		final String referenceName = referenceKey.referenceName();
		assertReferenceAvailableAndMatchPredicate(referenceName);

		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}

		// collect base references for the business key
		final List<ReferenceContract> baseReferences = schemaCheck ?
			this.baseReferences.getReferences(referenceKey) :
			this.baseReferences.getReferencesWithoutSchemaCheck(referenceKey);

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
			if (mutationBulk != null) {
				final ReferenceKey baseRefKey = baseRef.getReferenceKey();
				final int ipk = baseRefKey.internalPrimaryKey();
				if (!baseRefKey.isUnknownReference()) {
					knownInternalPks.add(ipk);
				}

				final List<ReferenceMutation<?>> mutationsForRef = mutationBulk.get(ipk);
				if (mutationsForRef != null) {
					final ReferenceContract mutatedReference = evaluateReferenceMutations(baseRef, mutationsForRef);
					if (mutatedReference != null && this.referencePredicate.test(mutatedReference)) {
						result.add(retainRichDataIfPossible(mutatedReference));
					}
					continue;
				}
			}
			// no mutations for this reference – prefer decorated instance if available
			if (this.referencePredicate.test(baseRef)) {
				result.add(this.richReferenceFetcher.apply(baseRef.getReferenceKey()).orElse(baseRef));
			}
		}

		// now add references created solely by mutations (not tied to existing base references)
		if (mutationBulk != null && !mutationBulk.isEmpty()) {
			for (Entry<Integer, List<ReferenceMutation<?>>> e : mutationBulk.entrySet()) {
				final int internalPkFromMutation = e.getKey();
				// skip those already present in base (we processed them above)
				if (knownInternalPks.contains(internalPkFromMutation)) {
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

	/**
	 * Sets a unique reference internally while ensuring compliance with the specified reference schema. The method
	 * validates that the reference conforms to the rules defined in the schema, and may promote cardinality if permissible.
	 * It enforces constraints such as avoiding duplicate references and maintaining schema consistency.
	 *
	 * @param referenceSchema      the schema associated with the reference, used to validate its properties and constraints
	 * @param referenceKey         the unique key identifying the referenced entity; this includes the primary key and internal key
	 * @param referencedEntityType the type of the referenced entity; must match the schema-defined type, if provided
	 * @param cardinality          the cardinality of the reference; if provided, must match the schema-defined cardinality
	 * @param editMode             the mode of reference editing; UPDATE_ONLY or UPDATE_OR_CREATE
	 * @param whichIs              an optional consumer for setting additional properties of the reference during its building process
	 * @return the updated instance of InternalEntityBuilder that includes the newly added or updated reference
	 */
	@Nonnull
	private ReferencesBuilder setUniqueReferenceInternal(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		@Nonnull ReferenceEditMode editMode,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) {
		final String referenceName = referenceKey.referenceName();
		final int referencedPrimaryKey = referenceKey.primaryKey();

		assertReferenceAvailableAndMatchPredicate(referenceName);
		assertReferenceSchemaCompatibility(referenceSchema, referencedEntityType, cardinality);

		// this method cannot be used when duplicates are allowed by schema
		final EntitySchemaContract entitySchema = this.entitySchema;
		final Cardinality schemaCardinality = referenceSchema.getCardinality();
		if (schemaCardinality.allowsDuplicates()) {
			throw new ReferenceAllowsDuplicatesException(referenceName, entitySchema, Operation.WRITE);
		}

		final List<ReferenceMutation<?>> existingMutations;
		final int existingInternalPkCount;
		if (this.referenceMutations == null) {
			existingMutations = List.of();
			existingInternalPkCount = 0;
		} else {
			final Map<Integer, List<ReferenceMutation<?>>> mutations = this.referenceMutations.get(referenceKey);
			if (mutations == null || mutations.isEmpty()) {
				existingInternalPkCount = 0;
				existingMutations = List.of();
			} else {
				existingInternalPkCount = mutations.size();
				existingMutations = getReferenceMutationsByReferenceKey(referenceKey, mutations, Operation.WRITE);
			}
		}

		final Optional<ReferenceContract> existingReference =
			this.baseReferences.getReferenceWithoutSchemaCheck(referenceKey);

		if (editMode == ReferenceEditMode.UPDATE_ONLY && existingReference.isEmpty()) {
			// no action happens - reference does not exist
			return this;
		}

		final ReferenceBuilder referenceBuilder;
		final ReferenceContract ref = existingReference
			.filter(reference -> isNotReferenceLocallyRemoved(reference.getReferenceKey()))
			.filter(this.referencePredicate)
			.orElse(null);

		if (editMode == ReferenceEditMode.UPDATE_ONLY && ref != null && isNotReferenceLocallyRemoved(ref.getReferenceKey()) && this.referencePredicate.test(ref)) {
			referenceBuilder = new ExistingReferenceBuilder(
				ref, entitySchema, existingMutations,
				getReferenceBundleForUpdate(ref.getReferenceName(), 8)
					.getAttributeTypes()
			);
		} else {
			referenceBuilder = createBrandNewInitialReferenceBuilder(
				entitySchema, referenceSchema, referenceName, referencedPrimaryKey,
				existingReference.map(it -> it.getReferenceKey().internalPrimaryKey())
					.orElseGet(
						() -> existingMutations.stream()
							.filter(InsertReferenceMutation.class::isInstance)
							.map(it -> it.getReferenceKey().internalPrimaryKey())
							.findFirst()
							.orElseGet(this::getNextReferenceInternalId)
					),
				existingInternalPkCount
			);
			existingReference
				.map(ReferenceContract::getReferenceKey)
				.ifPresent(this::addReferenceToRemoved);
		}

		ofNullable(whichIs).ifPresent(it -> it.accept(referenceBuilder));
		addOrReplaceReferenceMutations(referenceBuilder, false);
		return this;
	}

	/**
	 * Creates an instance of InitialReferenceBuilder using the provided entity schema,
	 * reference schema, existing mutations, reference name, and referenced primary key.
	 * The method ensures the first mutation in the list is an InsertReferenceMutation
	 * and initializes the builder accordingly. It also applies any attribute mutations
	 * from the provided list of mutations to the builder.
	 *
	 * @param entitySchema         the schema of the entity containing the reference
	 * @param referenceSchema      the schema definition of the reference
	 * @param existingMutations    a list of existing reference mutations to be applied
	 * @param referenceName        the name of the reference
	 * @param referencedPrimaryKey the primary key of the referenced entity
	 * @return an instance of InitialReferenceBuilder initialized with the provided data
	 * @throws GenericEvitaInternalError if the first mutation in the list is not an InsertReferenceMutation
	 */
	@Nonnull
	private InitialReferenceBuilder createInitialReferenceBuilderWithExistingMutations(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<ReferenceMutation<?>> existingMutations,
		@Nonnull String referenceName,
		int referencedPrimaryKey
	) {
		final InitialReferenceBuilder refBuilder;
		if (existingMutations.get(0) instanceof InsertReferenceMutation irm) {
			final ReferenceKey irmRefKey = irm.getReferenceKey();
			Assert.isPremiseValid(
				!irmRefKey.isUnknownReference(),
				"Expected known or locally assigned internal primary key in InsertReferenceMutation!"
			);
			refBuilder = new InitialReferenceBuilder(
				entitySchema,
				referenceSchema,
				referenceName,
				referencedPrimaryKey,
				irmRefKey.internalPrimaryKey(),
				getReferenceBundleForUpdate(referenceName, 8)
					.getAttributeTypes()
			);
		} else {
			throw new GenericEvitaInternalError(
				"Expected first mutation to be InsertReferenceMutation!"
			);
		}
		for (ReferenceMutation<?> existingMutation : existingMutations) {
			if (existingMutation instanceof ReferenceAttributeMutation ram) {
				refBuilder.mutateAttribute(ram.getAttributeMutation());
			}
		}
		return refBuilder;
	}

	/**
	 * Creates a new instance of {@link InitialReferenceBuilder} for the provided reference.
	 *
	 * @param entitySchema            the schema of the entity to which the reference belongs; must not be null
	 * @param referenceSchema         the schema of the reference being created; must not be null
	 * @param referenceName           the name of the reference being created; must not be null
	 * @param referencedPrimaryKey    the primary key of the referenced entity
	 * @param existingInternalPkCount the number of existing internal primary keys for the reference
	 * @return a newly created instance of {@link InitialReferenceBuilder}
	 * @throws InvalidMutationException if the reference's cardinality cannot be widened and adding additional references
	 *                                  violates the defined cardinality
	 */
	@Nonnull
	private InitialReferenceBuilder createBrandNewInitialReferenceBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		int internalPrimaryKey,
		int existingInternalPkCount
	) {
		final InitialReferenceBuilder refBuilder;
		// if the PK is negative is locally newly assigned and will represent a new reference
		// if the PK is positive, it represents locally removed reference that is being reinserted
		// so the count of existing references does not change
		final int newCount = existingInternalPkCount + (internalPrimaryKey < 0 ? 1 : 0);
		// but we allow implicit cardinality widening when needed
		if (
			newCount > 1 &&
				referenceSchema.getCardinality().getMax() <= 1 &&
				!entitySchema.allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)
		) {
			throw new ReferenceCardinalityViolatedException(
				entitySchema.getName(),
				List.of(
					new CardinalityViolation(
						referenceName, referenceSchema.getCardinality(),
						newCount, false
					)
				)
			);
		} else {
			refBuilder = new InitialReferenceBuilder(
				entitySchema,
				referenceSchema,
				referenceName,
				referencedPrimaryKey,
				internalPrimaryKey,
				getReferenceBundleForUpdate(referenceName, 8)
					.getAttributeTypes()
			);
		}
		return refBuilder;
	}

	/**
	 * Checks if a given reference is not locally removed.
	 *
	 * @param referenceKey the key of the reference to check
	 * @return true if the reference does not exist in the set of locally removed references, or if the set of removed references is null; false otherwise
	 */
	private boolean isNotReferenceLocallyRemoved(@Nonnull ReferenceKey referenceKey) {
		return this.removedReferences == null ||
			!this.removedReferences.contains(new FullyComparableReferenceKey(referenceKey));
	}

	/**
	 * Registers the cardinality of the reference and promotes the cardinality to a higher level if needed,
	 * while ensuring compliance with the schema constraints. Updates or elevates the cardinality where
	 * necessary in reference mutations.
	 *
	 * @param referenceKey           the unique key identifying the reference
	 * @param referenceSchema        the schema definition of the reference, which includes cardinality constraints
	 * @param referenceMutationIndex the index mapping reference primary keys to their respective mutations
	 * @return promotedChangeSet the list ofreference mutations with updated cardinality if promotion was necessary
	 */
	@Nonnull
	private List<ReferenceMutation<?>> registerCardinalityAndPromoteNewIfNeeded(
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Map<Integer, List<ReferenceMutation<?>>> referenceMutationIndex,
		@Nonnull List<ReferenceMutation<?>> promotedChangeSet
	) {
		// but we need to adapt the cardinality if needed
		final String referenceName = referenceKey.referenceName();
		final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
		referenceBundle.initializeBundleIfNecessary(
			theBundle -> {
				initReferenceBundle(referenceName, theBundle);
				if (this.removedReferences != null) {
					for (FullyComparableReferenceKey removedReference : this.removedReferences) {
						if (referenceName.equals(removedReference.referenceName())) {
							this.baseReferences.getReferenceWithoutSchemaCheck(
								new ReferenceKey(
									removedReference.referenceName(),
									removedReference.primaryKey(),
									removedReference.internalPrimaryKey()
								)
							).ifPresentOrElse(
								theBundle::removeReference,
								() -> {
									// this should never happen - the removed reference must be present in base references
									throw new GenericEvitaInternalError(
										"Removed reference is missing in base references!"
									);
								}
							);
						}
					}
				}
			}
		);

		// register a new reference
		final Cardinality schemaCardinality = referenceSchema.getCardinality();
		final int currentReferenceCount = referenceBundle.count() + 1;
		final boolean lowCardinality = schemaCardinality.getMax() < currentReferenceCount;
		final boolean hasDuplicates = referenceMutationIndex.size() > 1;
		final boolean duplicateMismatch = hasDuplicates && !schemaCardinality.allowsDuplicates();
		if (lowCardinality || duplicateMismatch) {
			if (!this.entitySchema.getEvolutionMode().contains(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
				throw new ReferenceCardinalityViolatedException(
					this.entitySchema.getName(),
					List.of(new CardinalityViolation(referenceName, schemaCardinality, currentReferenceCount, duplicateMismatch))
				);
			} else {
				// we need to promote the cardinality in all insert reference mutations
				final Cardinality elevatedCardinality = schemaCardinality.getMin() == 0 ?
					(hasDuplicates ? Cardinality.ZERO_OR_MORE_WITH_DUPLICATES : Cardinality.ZERO_OR_MORE) :
					(hasDuplicates ? Cardinality.ONE_OR_MORE_WITH_DUPLICATES : Cardinality.ONE_OR_MORE);
				for (Entry<Integer, List<ReferenceMutation<?>>> entry : referenceMutationIndex.entrySet()) {
					final List<ReferenceMutation<?>> mutations = entry.getValue();
					entry.setValue(
						elevateReferenceMutations(
							referenceSchema, mutations, elevatedCardinality, referenceName
						)
					);
				}
				return elevateReferenceMutations(
					referenceSchema, promotedChangeSet, elevatedCardinality, referenceName
				);
			}
		}
		return promotedChangeSet;
	}

	@Nonnull
	private List<ReferenceMutation<?>> elevateReferenceMutations(
		@Nonnull ReferenceSchemaContract referenceSchema,
		List<ReferenceMutation<?>> mutations, Cardinality elevatedCardinality, String referenceName
	) {
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
		return newMutations;
	}

	/**
	 * Initializes a reference bundle by processing both duplicated and non-duplicated references
	 * associated with a specific reference name.
	 *
	 * @param referenceName   The name of the reference to be processed. Must not be null.
	 * @param referenceBundle The builder reference bundle where the references are upserted
	 *                        based on their duplicated or non-duplicated status. Must not be null.
	 */
	private void initReferenceBundle(
		@Nonnull String referenceName,
		@Nonnull BuilderReferenceBundle referenceBundle
	) {
		final Iterator<List<ReferenceContract>> it = this.baseReferences
			.getDuplicatedReferences(referenceName)
			.iterator();
		while (it.hasNext()) {
			final List<ReferenceContract> references = it.next();
			ReferenceContract firstReference = null;
			boolean converted = false;
			for (ReferenceContract reference : references) {
				if (this.referencePredicate.test(reference)) {
					if (converted) {
						referenceBundle.upsertDuplicateReference(reference);
					} else if (firstReference == null) {
						firstReference = reference;
						referenceBundle.upsertNonDuplicateReference(reference);
					} else {
						// we have more than one reference - we need to convert the first one as well
						referenceBundle.convertToDuplicateReference(reference, firstReference);
						converted = true;
					}
				}
			}
		}
		this.baseReferences.getNonDuplicatedReferences(referenceName)
		                   .forEach(referenceBundle::upsertNonDuplicateReference);
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
				throw new ReferenceAllowsDuplicatesException(
					referenceKey.referenceName(), this.entitySchema, operation);
			}
		} else {
			final List<ReferenceMutation<?>> foundChangeSet = mutations.get(referenceKey.internalPrimaryKey());
			return foundChangeSet == null ? Collections.emptyList() : foundChangeSet;
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

		assertReferenceAvailableAndMatchPredicate(referenceName);

		final ReferenceKey genericReferenceKey = referenceKey.isUnknownReference() ?
			referenceKey :
			new ReferenceKey(referenceName, referencedPrimaryKey);

		// remove possibly added / updated reference mutation
		final Map<Integer, List<ReferenceMutation<?>>> removedMutations;
		if (this.referenceMutations == null) {
			removedMutations = Map.of();
		} else {
			removedMutations = this.referenceMutations.remove(genericReferenceKey);
		}

		// if the schema allows duplicates, we cannot remove the reference without knowing
		// the exact internal primary key to remove - unless explicitly allowed
		if (
			!allowRemovingAllDuplicates &&
				referenceKey.isUnknownReference() &&
				getReferenceSchemaContract(referenceName)
					.map(ReferenceSchemaContract::getCardinality)
					.map(Cardinality::allowsDuplicates)
					.orElse(false)
		) {
			throw new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.WRITE);
		}

		boolean removed = removedMutations != null && !removedMutations.isEmpty();
		List<ReferenceMutation<?>> discardedMutations = null;
		if (removed) {
			if (referenceKey.isUnknownReference()) {
				Assert.isTrue(
					removedMutations.size() == 1,
					() -> new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.WRITE)
				);
				discardedMutations = removedMutations.values().iterator().next();
			} else {
				this.referenceMutations.put(genericReferenceKey, removedMutations);
				discardedMutations = removedMutations.remove(referenceKey.internalPrimaryKey());
				removed = discardedMutations != null && !discardedMutations.isEmpty();
			}
		}

		ReferenceContract formerReference = null;
		for (ReferenceContract theReference : this.baseReferences.getReferencesWithoutSchemaCheck(referenceKey)) {
			if (
				theReference.exists() &&
					this.referencePredicate.test(theReference) &&
					(
						referenceKey.isUnknownReference() ||
							referenceKey.internalPrimaryKey() == theReference.getReferenceKey().internalPrimaryKey()
					)
			) {
				formerReference = theReference;
				// if the reference was part of the previous entity version we build upon, remove it as-well
				final ReferenceKey completeReferenceKey = theReference.getReferenceKey();
				getReferenceMutationsForKey(referenceKey)
					.put(
						completeReferenceKey.internalPrimaryKey(),
						Collections.singletonList(
							new RemoveReferenceMutation(completeReferenceKey)
						)
					);
				addReferenceToRemoved(completeReferenceKey);
				removed = true;
			}
		}

		// we need to update reference bundle keys as-well
		if (removed) {
			this.memoizedReferences = null;
			final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
			if (referenceBundle.isInitialized()) {
				if (discardedMutations == null) {
					Assert.isPremiseValid(
						formerReference != null,
						"We always expect former reference to be non-null if no mutations were discarded!"
					);
					if (referenceBundle.isDuplicate(referenceKey)) {
						referenceBundle.removeDuplicateReference(formerReference);
					} else {
						referenceBundle.removeNonDuplicateReference(formerReference);
					}
				} else {
					ReferenceContract updatedReference = formerReference;
					for (ReferenceMutation<?> discardedMutation : discardedMutations) {
						updatedReference = discardedMutation.mutateLocal(
							this.entitySchema, updatedReference, referenceBundle.getAttributeTypes()
						);
					}
					Assert.isPremiseValid(
						updatedReference != null,
						"We always expect updated reference to be non-null after applying discarded mutations!"
					);
					if (referenceBundle.isDuplicate(updatedReference.getReferenceKey())) {
						referenceBundle.removeDuplicateReference(updatedReference);
					} else {
						referenceBundle.removeNonDuplicateReference(updatedReference);
					}
				}
			}
		} else {
			throw new InvalidMutationException(
				"There's no reference of a type `" + referenceName + "` and primary key `" + referencedPrimaryKey + "`!"
			);
		}
	}

	/**
	 * Adds a given reference key to the collection of removed references.
	 *
	 * @param referenceKey the reference key to be added to the removed references collection; must not be null
	 */
	private void addReferenceToRemoved(@Nonnull ReferenceKey referenceKey) {
		if (this.removedReferences == null) {
			this.removedReferences = new HashSet<>(4);
		}
		this.removedReferences.add(
			new FullyComparableReferenceKey(referenceKey)
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
		BuilderReferenceBundle referenceBundle = null;
		String lastReferenceName = null;
		for (ReferenceMutation<?> mutation : mutations) {
			final String mutationReferenceName = mutation.getReferenceKey().referenceName();
			referenceBundle = referenceBundle == null || !Objects.equals(lastReferenceName, mutationReferenceName) ?
				getReferenceBundleForUpdate(mutationReferenceName, 8) :
				referenceBundle;
			lastReferenceName = mutationReferenceName;
			// apply mutation
			mutatedReference = mutation.mutateLocal(
				this.entitySchema, mutatedReference, referenceBundle.getAttributeTypes());
		}
		return mutatedReference != null && mutatedReference.differsFrom(reference) ?
			mutatedReference :
			reference;
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
					final ReferenceSchema implicit = ReferencesBuilder.createImplicitSchema(
						this.entitySchema, referenceName, referencedEntityType, cardinality, null
					);
					cacheLocalSchema(referenceName, implicit);
					return implicit;
				}
			);
	}

	/**
	 * Caches the provided reference schema locally by its reference name.
	 *
	 * @param referenceName   the unique name identifying the reference schema; must not be null
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
	 * Retrieves or initializes an {@link BuilderReferenceBundle} for a given reference name.
	 * If the reference bundle for the specified reference name does not already exist, it creates
	 * a new one with the provided expected count and stores it in the map.
	 *
	 * @param referenceName the name of the reference to retrieve or initialize the bundle for
	 * @param expectedCount the initial count expected for the reference bundle, used if creating a new bundle
	 * @return the {@link BuilderReferenceBundle} associated with the specified reference name
	 */
	@Nonnull
	private BuilderReferenceBundle getReferenceBundleForUpdate(@Nonnull String referenceName, int expectedCount) {
		if (this.referenceBundles == null) {
			this.referenceBundles = CollectionUtils.createHashMap(
				Math.max(8, this.entitySchema.getReferences().size()));
		}
		return this.referenceBundles.computeIfAbsent(
			referenceName,
			k -> new BuilderReferenceBundle(expectedCount)
		);
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
			.or(() -> this.entitySchema.getReference(referenceName))
			.or(
				() -> {
					final Collection<ReferenceContract> references = this.baseReferences.getReferences();
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

	/**
	 * Ensures that the specified reference is available and satisfies the predicate condition.
	 * Throws an exception if the reference is not available or does not match the expected criteria.
	 *
	 * @param referenceName the name of the reference to be validated; must not be null
	 */
	private void assertReferenceAvailableAndMatchPredicate(@Nonnull String referenceName) {
		if (!referencesAvailable(referenceName)) {
			throw ContextMissingException.referenceContextMissing(referenceName);
		}
		Assert.isTrue(
			this.referencePredicate.isReferenceRequested(referenceName),
			"References were not fetched and cannot be updated. Please enrich the entity first or load it with the references."
		);
	}

	/**
	 * Represents alternative to {@link ReferenceKey} that implements full comparison including internalPrimaryKey.
	 *
	 * @param referenceName      the name of the reference to be validated; must not be null
	 * @param primaryKey         the primary key of the referenced entity; must not be negative
	 * @param internalPrimaryKey the internal primary key of the referenced entity; must not be negative
	 */
	private record FullyComparableReferenceKey(
		@Nonnull String referenceName,
		int primaryKey,
		int internalPrimaryKey
	) {

		private FullyComparableReferenceKey(@Nonnull ReferenceKey referenceKey) {
			this(referenceKey.referenceName(), referenceKey.primaryKey(), referenceKey.internalPrimaryKey());
			Assert.isPremiseValid(!referenceKey.isUnknownReference(), "The primary key must be known!");
		}

	}

}
