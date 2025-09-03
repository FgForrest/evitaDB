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
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.map.LazyHashMapDelegate;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
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
 * Builder that is used to create new {@link References} instance.
 * Due to performance reasons (see {@link DirectWriteOrOperationLog} microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class InitialReferencesBuilder implements ReferencesBuilder {
	@Serial private static final long serialVersionUID = -3706426701440679539L;
	/**
	 * Contains the entity schema that is used to validate the references against.
	 */
	private final EntitySchemaContract schema;
	/**
	 * Flat collection of all references added to the builder. It may contain duplicates
	 * when the reference cardinality allows them. The list preserves insertion order.
	 */
	@Nullable private List<ReferenceContract> referenceCollection;
	/**
	 * Index of references by {@link ReferenceKey}. If a key appears more than once, the value stored
	 * is a special sentinel {@link References#DUPLICATE_REFERENCE} to signal that duplicates exist and
	 * callers must fall back to scanning {@link #referenceCollection}.
	 */
	@Nullable private Map<ReferenceKey, ReferenceContract> references;
	/**
	 * Contains names of all reference names that have at least one reference in this instance.
	 */
	@Nullable private Map<String, Integer> referencesDefinedCount;
	/**
	 * Internal sequence that is used to generate unique negative reference ids for references that
	 * don't have it assigned yet (which is none in the initial entity builder).
	 */
	private int lastLocallyAssignedReferenceId = 0;
	/**
	 * Map of attribute types for the reference shared for all references of the same type.
	 */
	private final Map<String, AttributeSchemaContract> attributeTypes = new LazyHashMapDelegate<>(4);

	InitialReferencesBuilder(@Nonnull EntitySchemaContract schema) {
		this.schema = schema;
		this.referenceCollection = null;
		this.referencesDefinedCount = null;
		this.references = null;
	}

	InitialReferencesBuilder(
		@Nonnull EntitySchemaContract schema,
		@Nonnull Collection<ReferenceContract> referenceContracts
	) {
		this.schema = schema;
		this.referenceCollection = referenceContracts.isEmpty() ?
			null : new ArrayList<>(referenceContracts);
		this.referencesDefinedCount = referenceContracts.isEmpty() ?
			null : CollectionUtils.createLinkedHashMap(schema.getReferences().size());
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
		} else if (reference == References.DUPLICATE_REFERENCE || reference.getReferenceSchemaOrThrow().getCardinality().allowsDuplicates()) {
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
		} else if (reference == References.DUPLICATE_REFERENCE || reference.getReferenceSchemaOrThrow().getCardinality().allowsDuplicates()) {
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
			if (reference == References.DUPLICATE_REFERENCE) {
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
					referenceContract,
					this.schema,
					this.attributeTypes
				);
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
	public ReferencesBuilder setReference(@Nonnull String referenceName, int referencedPrimaryKey) {
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
	public ReferencesBuilder setReference(
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
	public ReferencesBuilder setReference(
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
	public ReferencesBuilder setReference(
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
				getNextReferenceInternalId(),
				this.attributeTypes
			);
			addReferenceInternal(whichIs.apply(builder).build());
			return this;
		} else if (theReference == References.DUPLICATE_REFERENCE) {
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
						this.schema,
						this.attributeTypes
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
					getNextReferenceInternalId(),
					this.attributeTypes
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
				final ExistingReferenceBuilder refBuilder = new ExistingReferenceBuilder(
					theReference, this.schema, this.attributeTypes
				);
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
					if (this.schema.allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
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
					getNextReferenceInternalId(),
					this.attributeTypes
				);
				Objects.requireNonNull(this.referencesDefinedCount)
				       .computeIfPresent(referenceName, (k, v) -> v + 1);
				theFinalReference = whichIs.apply(refBuilder).build();
				// we're adding a new reference with the same key - mark as duplicate
				theReferenceIndex.put(referenceKey, References.DUPLICATE_REFERENCE);
			}
			theReferenceCollection.add(theFinalReference);
			return this;
		}
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReference(@Nonnull String referenceName, int referencedPrimaryKey) {
		if (this.references != null) {
			final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
			final ReferenceContract removedReference = this.references.remove(referenceKey);
			if (removedReference == References.DUPLICATE_REFERENCE) {
				this.references.put(referenceKey, References.DUPLICATE_REFERENCE);
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
	public ReferencesBuilder removeReference(@Nonnull ReferenceKey referenceKey) throws ReferenceNotKnownException {
		if (this.references != null) {
			final String referenceName = referenceKey.referenceName();
			final ReferenceContract removedReference = this.references.remove(referenceKey);
			if (removedReference == References.DUPLICATE_REFERENCE) {
				if (referenceKey.isUnknownReference()) {
					this.references.put(referenceKey, References.DUPLICATE_REFERENCE);
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
						this.references.put(referenceKey, References.DUPLICATE_REFERENCE);
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
	public ReferencesBuilder removeReferences(@Nonnull String referenceName, int referencedPrimaryKey) {
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
	public ReferencesBuilder removeReferences(@Nonnull String referenceName) {
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
	public ReferencesBuilder removeReferences(@Nonnull String referenceName, @Nonnull Predicate<ReferenceContract> filter) {
		final Predicate<ReferenceContract> nameFilter = reference -> referenceName.equals(reference.getReferenceName());
		return this.removeReferences(nameFilter.and(filter));
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(@Nonnull Predicate<ReferenceContract> filter) {
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
					if (removed == References.DUPLICATE_REFERENCE) {
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
						this.references.put(key, References.DUPLICATE_REFERENCE);
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

	@Nonnull
	@Override
	public Stream<? extends ReferenceMutation<?>> buildChangeSet() {
		if (this.referenceCollection != null) {
			return this.referenceCollection
				.stream()
				.flatMap(it -> {
					final Stream<? extends ReferenceMutation<?>> base = Stream.of(
						new InsertReferenceMutation(
							it.getReferenceKey(),
							resolveCardinality(it.getReferenceKey(), it.getReferenceCardinality()),
							it.getReferencedEntityType()
						)
					);
					final Stream<? extends ReferenceMutation<?>> baseWithGroup = it.getGroup().isPresent() ?
						Stream.concat(
							base,
							Stream.of(
								new SetReferenceGroupMutation(
									it.getReferenceKey(),
									it.getGroup().get().getType(),
									it.getGroup().get().getPrimaryKey()
								)
							)
						)
						: base;
					final Stream<? extends ReferenceMutation<?>> attributeMutations =
						it.getAttributeValues()
						  .stream()
						  .filter(
							  attributeValue -> attributeValue.value() != null)
						  .map(
							  attributeValue -> new ReferenceAttributeMutation(
								  it.getReferenceKey(),
								  new UpsertAttributeMutation(
									  attributeValue.key(),
									  attributeValue.value()
								  )
							  )
						  );
					return Stream.concat(
						baseWithGroup,
						// Reference attributes with non-null values
						attributeMutations
					);
				}
			);
		} else {
			return Stream.empty();
		}
	}

	@Nonnull
	@Override
	public References build() {
		final Set<String> allReferenceNames;
		if (this.referenceCollection == null || this.referencesDefinedCount == null) {
			allReferenceNames = this.schema.getReferences().keySet();
		} else {
			allReferenceNames = new HashSet<>(this.schema.getReferences().size() + this.referencesDefinedCount.size());
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
		return new References(
			this.schema,
			theReferences,
			allReferenceNames,
			References.DEFAULT_CHUNK_TRANSFORMER
		);
	}

	/**
	 * Retrieves the reference collection for updating. If the collection does not yet exist,
	 * it initializes a new collection along with its associated index.
	 *
	 * @return a non-null {@link List} of {@link ReferenceContract} representing the reference collection
	 *         prepared for updates
	 */
	@Nonnull
	private List<ReferenceContract> getReferenceCollectionForUpdate() {
		if (this.referenceCollection == null) {
			this.referenceCollection = new ArrayList<>();
			this.references = new LinkedHashMap<>();
		}
		return this.referenceCollection;
	}

	/**
	 * Retrieves the reference index for updating. If the index does not yet exist,
	 * it initializes a new reference index along with its associated collection.
	 *
	 * @return a non-null map of {@link ReferenceKey} to {@link ReferenceContract} representing
	 *         the reference index prepared for updates
	 */
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
		if (this.references != null && this.references.get(key) == References.DUPLICATE_REFERENCE) {
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
				return ReferencesBuilder.createImplicitSchema(
					this.schema, referenceName, referencedEntityType, cardinality, null
				);
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
		return this.schema
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
				existingValue == null ? referenceWithAssignedInternalId : References.DUPLICATE_REFERENCE
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
	private InitialReferencesBuilder setUniqueReferenceInternal(
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
			throw new ReferenceAllowsDuplicatesException(referenceName, this.schema, Operation.WRITE);
		}
		// but we allow implicit cardinality widening when needed
		if (this.referencesDefinedCount != null && this.referencesDefinedCount.containsKey(referenceName) && schemaCardinality.getMax() <= 1) {
			// check whether there already is some reference to this key
			// if yes, check whether we can promote cardinality
			if (this.schema.allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
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
				if (referenceContract == References.DUPLICATE_REFERENCE) {
					throw new ReferenceAllowsDuplicatesException(referenceName, this.schema, Operation.WRITE);
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
				referenceKey.internalPrimaryKey(),
			this.attributeTypes
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
									this.schema,
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
									this.schema,
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
