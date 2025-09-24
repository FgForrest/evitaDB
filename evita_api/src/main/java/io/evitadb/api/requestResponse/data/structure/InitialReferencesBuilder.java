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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.set.LazyHashSet;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
	private final EntitySchemaContract entitySchema;
	/**
	 * Flat collection of all references added to the builder. It may contain duplicates
	 * when the reference cardinality allows them. The list preserves insertion order.
	 */
	@Nullable private List<ReferenceContract> referenceCollection;
	/**
	 * Helper associated object tracking duplicates.
	 */
	@Nullable private Map<String, BuilderReferenceBundle> referenceBundles;
	/**
	 * Index of references by {@link ReferenceKey}. If a key appears more than once, the value stored
	 * is a special sentinel {@link References#DUPLICATE_REFERENCE} to signal that duplicates exist and
	 * callers must fall back to scanning {@link #referenceCollection}.
	 */
	@Nullable private Map<ReferenceKey, ReferenceContract> references;
	/**
	 * Internal sequence that is used to generate unique negative reference ids for references that
	 * don't have it assigned yet (which is none in the initial entity builder).
	 */
	private int lastLocallyAssignedReferenceId = 0;

	/**
	 * Asserts the compatibility of the provided reference schema with the specified references entity type and cardinality.
	 * Ensures that the referenced entity type and cardinality provided are either compatible with or match the
	 * already defined values in the reference schema. If they are incompatible, an {@code InvalidMutationException} is thrown.
	 *
	 * @param referenceSchema      the reference schema contract to validate against
	 * @param referencedEntityType the referenced entity type to check for compatibility, may be null
	 * @param cardinality          the cardinality to check for compatibility, may be null
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

	InitialReferencesBuilder(@Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.referenceCollection = null;
		this.referenceBundles = null;
		this.references = null;
	}

	InitialReferencesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<ReferenceContract> referenceContracts
	) {
		this.entitySchema = entitySchema;
		if (referenceContracts.isEmpty()) {
			this.referenceCollection = null;
			this.references = null;
		} else {
			this.referenceCollection = new ArrayList<>(referenceContracts);
			this.referenceBundles = CollectionUtils.createHashMap(entitySchema.getReferences().size());
			this.references = CollectionUtils.createHashMap(referenceContracts.size());
			final int averageRefCount = entitySchema.getReferences().isEmpty() ?
				0 : referenceContracts.size() / entitySchema.getReferences().size();
			ReferenceKey previousKey = null;
			for (final ReferenceContract currentReference : referenceContracts) {
				final ReferenceKey currentRefKey = currentReference.getReferenceKey();
				final BuilderReferenceBundle bundle = getReferenceBundleForUpdate(
					currentRefKey.referenceName(), averageRefCount
				);
				if (previousKey != null && previousKey.equalsInGeneral(currentRefKey)) {
					final ReferenceContract previousReference = this.references.put(
						new ReferenceKey(currentRefKey.referenceName(), currentRefKey.primaryKey()),
						References.DUPLICATE_REFERENCE
					);
					Assert.isPremiseValid(
						previousReference != null,
						"Reference " + currentRefKey + " was expected to be already present!"
					);
					if (previousReference != References.DUPLICATE_REFERENCE) {
						// we are encountering the first duplicate - move the previous one to duplicates as well
						bundle.convertToDuplicateReference(currentReference, previousReference);
					} else {
						bundle.upsertDuplicateReference(currentReference);
					}
				} else {
					this.references.put(currentRefKey, currentReference);
					bundle.upsertNonDuplicateReference(currentReference);
				}
				previousKey = currentRefKey;
			}
		}
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
		if (this.referenceBundles == null) {
			return Collections.emptySet();
		} else {
			return this.referenceBundles.keySet();
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
		} else if (reference == References.DUPLICATE_REFERENCE || reference.getReferenceSchemaOrThrow()
		                                                                   .getCardinality()
		                                                                   .allowsDuplicates()) {
			throw new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.READ);
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
		} else if (reference == References.DUPLICATE_REFERENCE || reference.getReferenceSchemaOrThrow()
		                                                                   .getCardinality()
		                                                                   .allowsDuplicates()) {
			throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), this.entitySchema, Operation.READ);
		} else {
			return of(reference);
		}
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
		@Nonnull Consumer<ReferenceBuilder> whichIs
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
					this.entitySchema,
					getReferenceBundleForUpdate(
						referenceContract.getReferenceKey().referenceName(), 8).getAttributeTypes()
				);
				whichIs.accept(refBuilder);
				final ReferenceContract updatedReference = refBuilder.build();
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
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		setReference(
			referenceName,
			null,
			null,
			referencedPrimaryKey,
			filter,
			whichIs
		);
		return this;
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
		@Nullable String referencedEntityType,
		@Nullable Cardinality cardinality,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	) {
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedPrimaryKey);
		final Map<ReferenceKey, ReferenceContract> theReferenceIndex = getReferenceIndexForUpdate();
		final ReferenceContract existingReference = theReferenceIndex.get(referenceKey);
		final Optional<ReferenceSchemaContract> referenceSchema = getReferenceSchemaContract(referenceName);
		referenceSchema.ifPresent(
			theRefSchema -> assertReferenceSchemaCompatibility(theRefSchema, referencedEntityType, cardinality)
		);

		if (existingReference == null) {
			// no existing reference was found - create brand new, and we know it's not duplicate
			final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
			final InitialReferenceBuilder refBuilder = new InitialReferenceBuilder(
				this.entitySchema,
				referenceSchema
					.orElseGet(
						() -> getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality)),
				referenceName, referencedPrimaryKey,
				getNextReferenceInternalId(),
				referenceBundle.getAttributeTypes()
			);
			whichIs.accept(refBuilder);
			final Reference newReference = refBuilder.build();
			addOrReplaceReferenceInternal(newReference);
			referenceBundle.upsertNonDuplicateReference(newReference);
			return this;
		} else if (existingReference == References.DUPLICATE_REFERENCE) {
			// existing list of references was found - and we know it's duplicates
			final List<ReferenceExchange> updates = new LinkedList<>();
			// we need to traverse all references and filter those matching reference key and predicate
			final List<ReferenceContract> theReferenceCollection = getReferenceCollectionForUpdate();
			for (int i = 0; i < theReferenceCollection.size(); i++) {
				final ReferenceContract referenceContract = theReferenceCollection.get(i);
				if (referenceKey.equals(referenceContract.getReferenceKey()) && filter.test(referenceContract)) {
					final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
					// if the predicate passes, we need to update the reference
					final ExistingReferenceBuilder refBuilder = new ExistingReferenceBuilder(
						referenceContract,
						this.entitySchema,
						referenceBundle.getAttributeTypes()
					);
					whichIs.accept(refBuilder);
					final ReferenceContract updatedReference = refBuilder.build();
					referenceBundle.upsertDuplicateReference(updatedReference);
					updates.add(new ReferenceExchange(i, updatedReference));
				}
			}
			if (updates.isEmpty()) {
				// if no updates were made - it means that the predicate did not pass for any of the references
				// so we create another duplicate and add it to the list
				final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
				final InitialReferenceBuilder refBuilder = new InitialReferenceBuilder(
					this.entitySchema,
					referenceSchema
						.orElseGet(
							() -> getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality)),
					referenceName,
					referencedPrimaryKey,
					getNextReferenceInternalId(),
					referenceBundle.getAttributeTypes()
				);
				whichIs.accept(refBuilder);
				final Reference newReference = refBuilder.build();
				referenceBundle.upsertDuplicateReference(newReference);
				theReferenceCollection.add(newReference);
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
			if (filter.test(existingReference)) {
				// if the predicate matches, we update the existing reference
				final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
				final ExistingReferenceBuilder refBuilder = new ExistingReferenceBuilder(
					existingReference, this.entitySchema, referenceBundle.getAttributeTypes()
				);
				whichIs.accept(refBuilder);
				theFinalReference = refBuilder.build();
				replaceReference(theFinalReference, theReferenceCollection);
				referenceBundle.upsertNonDuplicateReference(theFinalReference);
				theReferenceIndex.put(referenceKey, theFinalReference);
			} else {
				final ReferenceSchemaContract theReferenceSchema = referenceSchema
					.orElseGet(
						() -> getReferenceSchemaOrCreateImplicit(referenceName, referencedEntityType, cardinality));
				final Cardinality schemaCardinality = theReferenceSchema.getCardinality();
				// we don't allow explicit cardinality change
				if (cardinality != null && cardinality != schemaCardinality) {
					throw new InvalidMutationException(
						"The reference `" + referenceName + "` is already defined to have `" +
							theReferenceSchema.getCardinality() + "` cardinality, cannot change it to `" + cardinality + "` by data update!"
					);
				}
				// otherwise we create a new reference, which makes existing and new one duplicates
				final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
				final InitialReferenceBuilder refBuilder = new InitialReferenceBuilder(
					this.entitySchema,
					theReferenceSchema,
					referenceName,
					referencedPrimaryKey,
					getNextReferenceInternalId(),
					referenceBundle.getAttributeTypes()
				);
				whichIs.accept(refBuilder);
				theFinalReference = refBuilder.build();
				referenceBundle.convertToDuplicateReference(theFinalReference, existingReference);
				theReferenceCollection.add(theFinalReference);
				// we're adding a new reference with the same key - mark as duplicate
				theReferenceIndex.put(referenceKey, References.DUPLICATE_REFERENCE);

				// but we allow implicit cardinality widening when needed
				if (!schemaCardinality.allowsDuplicates()) {
					if (this.entitySchema.allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
						promoteDuplicateCardinality(theReferenceSchema, referenceKey);
					} else {
						throw new InvalidMutationException(
							"The reference `" + referenceName + "` is defined to have `" +
								theReferenceSchema.getCardinality() + "` cardinality, cannot add duplicate reference to it!"
						);
					}
				}
			}
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
				throw new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.WRITE);
			} else if (removedReference != null) {
				getReferenceBundleForUpdate(referenceName, 8).removeNonDuplicateReference(removedReference);
				getReferenceCollectionForUpdate().remove(removedReference);
			}
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
					throw new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.WRITE);
				} else {
					int duplicateCounter = 0;
					ReferenceContract removedReferenceOccurence = null;
					ReferenceContract remainingOccurence = null;
					final Iterator<ReferenceContract> it = getReferenceCollectionForUpdate().iterator();
					while (it.hasNext()) {
						final ReferenceContract reference = it.next();
						if (referenceKey.equals(reference.getReferenceKey())) {
							if (reference.getReferenceKey().internalPrimaryKey() == referenceKey.internalPrimaryKey()) {
								it.remove();
								removedReferenceOccurence = reference;
							} else if (remainingOccurence == null) {
								remainingOccurence = reference;
								duplicateCounter = 1;
							} else {
								duplicateCounter++;
							}
						}
					}

					Assert.isTrue(
						removedReferenceOccurence != null,
						() -> new InvalidMutationException(
							"Reference with key `" + referenceKey + "` was not found!"
						)
					);
					final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
					referenceBundle.removeDuplicateReference(removedReferenceOccurence);
					if (duplicateCounter == 1) {
						// only one occurrence remains - put it back as the single one
						this.references.put(referenceKey, remainingOccurence);
						referenceBundle.discardDuplicates(remainingOccurence.getReferenceKey());
					} else if (duplicateCounter > 1) {
						// more than one occurrence remains - keep the duplicate marker
						this.references.put(referenceKey, References.DUPLICATE_REFERENCE);
					}
				}
			} else if (removedReference != null) {
				getReferenceCollectionForUpdate().remove(removedReference);
				getReferenceBundleForUpdate(referenceName, 8)
					.removeNonDuplicateReference(removedReference);
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
			final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
			final ReferenceContract removedContract = this.references.remove(referenceKey);
			if (removedContract != null) {
				final boolean duplicate = removedContract == References.DUPLICATE_REFERENCE;
				getReferenceCollectionForUpdate()
					.removeIf(examinedReference -> {
						final boolean remove = examinedReference.getReferenceKey().equals(referenceKey);
						if (remove) {
							if (duplicate) {
								referenceBundle.removeDuplicateReference(examinedReference);
							} else {
								referenceBundle.removeNonDuplicateReference(examinedReference);
							}
						}
						return remove;
					});
			}
		}
		return this;
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(@Nonnull String referenceName) {
		if (this.references != null) {
			final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
			final Iterator<ReferenceContract> it = getReferenceCollectionForUpdate().iterator();
			final Set<ReferenceKey> duplicateReferenceKeys = new LazyHashSet<>(8);
			while (it.hasNext()) {
				final ReferenceContract reference = it.next();
				if (referenceName.equals(reference.getReferenceName())) {
					it.remove();
					// we can remove here, because even if the reference is duplicated, this method would eventually
					// remove all the duplicates anyway
					final ReferenceKey referenceKey = reference.getReferenceKey();
					final ReferenceContract removedReference = this.references.remove(referenceKey);
					if (removedReference == References.DUPLICATE_REFERENCE) {
						duplicateReferenceKeys.add(
							new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey())
						);
						referenceBundle.removeDuplicateReference(reference);
					} else if (duplicateReferenceKeys.contains(referenceKey)) {
						referenceBundle.removeDuplicateReference(reference);
					} else {
						referenceBundle.removeNonDuplicateReference(reference);
					}
				}
			}
		}
		return this;
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(
		@Nonnull String referenceName, @Nonnull Predicate<ReferenceContract> filter) {
		final Predicate<ReferenceContract> nameFilter = reference -> referenceName.equals(reference.getReferenceName());
		return this.removeReferences(nameFilter.and(filter));
	}

	@Nonnull
	@Override
	public ReferencesBuilder removeReferences(@Nonnull Predicate<ReferenceContract> filter) {
		if (this.references != null) {
			Set<ReferenceKey> duplicateReferenceKeys = null;
			final List<ReferenceContract> referenceCollection = getReferenceCollectionForUpdate();
			final Iterator<ReferenceContract> it = referenceCollection.iterator();

			while (it.hasNext()) {
				final ReferenceContract reference = it.next();
				if (filter.test(reference)) {
					it.remove();
					final ReferenceKey key = reference.getReferenceKey();
					final ReferenceContract removed = this.references.remove(key);
					// if it was duplicate, remember the key for later reconciliation
					if (removed == References.DUPLICATE_REFERENCE) {
						if (duplicateReferenceKeys == null) {
							duplicateReferenceKeys = new HashSet<>(16);
						}
						duplicateReferenceKeys.add(key);
						getReferenceBundleForUpdate(reference.getReferenceName(), 8)
							.removeDuplicateReference(reference);
					} else {
						getReferenceBundleForUpdate(reference.getReferenceName(), 8)
							.removeNonDuplicateReference(reference);
					}
				}
			}

			// Reconcile only keys that were duplicates
			if (duplicateReferenceKeys != null && !duplicateReferenceKeys.isEmpty()) {
				final Map<ReferenceKey, Integer> counts = new HashMap<>(duplicateReferenceKeys.size() << 1);
				final Map<ReferenceKey, ReferenceContract> singleCandidate = new HashMap<>(duplicateReferenceKeys.size() << 1);

				// Count remaining occurrences for affected keys and keep the single candidate if count == 1
				for (ReferenceContract ref : referenceCollection) {
					final ReferenceKey key = ref.getReferenceKey();
					if (duplicateReferenceKeys.contains(key)) {
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
				for (ReferenceKey key : duplicateReferenceKeys) {
					final int c = counts.getOrDefault(key, 0);
					if (c == 1) {
						final ReferenceContract remainingReference = singleCandidate.get(key);
						this.references.put(key, remainingReference);
						getReferenceBundleForUpdate(key.referenceName(), 8)
							.discardDuplicates(remainingReference.getReferenceKey());
					} else if (c > 1) {
						this.references.put(key, References.DUPLICATE_REFERENCE);
					}
					// if c == 0, nothing remains for that key
				}
			}
		}
		return this;
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

	@Override
	public int getNextReferenceInternalId() {
		return --this.lastLocallyAssignedReferenceId;
	}

	@Override
	public void addOrReplaceReferenceMutations(
		@Nonnull ReferenceBuilder referenceBuilder,
		boolean methodAllowsDuplicates
	) {
		// if the reference is new - we need to adapt its internal key to the one assigned here
		/* TODO JNO - tohle bude nutné ještě upravit kvůli duplicitám v proxies */
		if (!methodAllowsDuplicates && referenceBuilder instanceof InitialReferenceBuilder irb) {
			irb.remapInternalKeyUsing(referenceKey -> {
				// try to find new reference with the same business key in this builder
				final ReferenceContract existingContract = this.references == null ?
					null : this.references.get(referenceKey);
				if (existingContract != null && existingContract != References.DUPLICATE_REFERENCE) {
					return new ReferenceKey(
						referenceKey.referenceName(),
						referenceKey.primaryKey(),
						existingContract.getReferenceKey().internalPrimaryKey()
					);
				} else {
					return null;
				}
			});
		}
		addOrReplaceReferenceInternal(referenceBuilder.build());
	}

	@Nonnull
	@Override
	public References build() {
		final Set<String> allReferenceNames;
		if (this.referenceBundles == null) {
			allReferenceNames = this.entitySchema.getReferences().keySet();
		} else {
			allReferenceNames = new HashSet<>(this.entitySchema.getReferences().size() + this.referenceBundles.size());
			allReferenceNames.addAll(this.referenceBundles.keySet());
			allReferenceNames.addAll(this.entitySchema.getReferences().keySet());
		}

		final List<ReferenceContract> theReferences;
		if (this.referenceCollection == null) {
			theReferences = Collections.emptyList();
		} else {
			theReferences = new ArrayList<>(this.referenceCollection);
			theReferences.sort(ReferenceContract.FULL_COMPARATOR);
		}
		return new References(
			this.entitySchema,
			theReferences,
			allReferenceNames,
			References.DEFAULT_CHUNK_TRANSFORMER
		);
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
			this.referenceBundles = CollectionUtils.createHashMap(Math.max(8, this.entitySchema.getReferences().size()));
		}
		return this.referenceBundles.computeIfAbsent(
			referenceName,
			k -> new BuilderReferenceBundle(expectedCount)
		);
	}

	/**
	 * Retrieves the reference collection for updating. If the collection does not yet exist,
	 * it initializes a new collection along with its associated index.
	 *
	 * @return a non-null {@link List} of {@link ReferenceContract} representing the reference collection
	 * prepared for updates
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
	 * the reference index prepared for updates
	 */
	@Nonnull
	private Map<ReferenceKey, ReferenceContract> getReferenceIndexForUpdate() {
		if (this.references == null) {
			this.referenceCollection = new ArrayList<>(16);
			this.references = new LinkedHashMap<>(16);
			this.referenceBundles = new HashMap<>(8);
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
					this.entitySchema, referenceName, referencedEntityType, cardinality, null
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
		return this.entitySchema
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
	private void addOrReplaceReferenceInternal(@Nonnull ReferenceContract reference) {
		if (this.referenceCollection == null || this.references == null) {
			this.referenceCollection = new ArrayList<>(16);
			this.references = new LinkedHashMap<>(16);
		}
		final ReferenceContract referenceWithAssignedInternalId = getReference(reference);
		this.references.compute(
			referenceWithAssignedInternalId.getReferenceKey(),
			(key, existingValue) -> {
				if (existingValue == null) {
					this.referenceCollection.add(referenceWithAssignedInternalId);
					return referenceWithAssignedInternalId;
				} else {
					final int existingPk = existingValue.getReferenceKey().internalPrimaryKey();
					final int newPk = referenceWithAssignedInternalId.getReferenceKey().internalPrimaryKey();
					if (existingPk == newPk) {
						replaceReference(referenceWithAssignedInternalId, this.referenceCollection);
						// replacing existing reference with the same internal id
						return referenceWithAssignedInternalId;
					} else {
						this.referenceCollection.add(referenceWithAssignedInternalId);
						// marking as duplicate
						return References.DUPLICATE_REFERENCE;
					}
				}
			}
		);
	}

	/**
	 * Replaces an existing reference in the provided collection with the given reference.
	 * The method removes the reference identified by its internal ID and adds the given
	 * reference to the collection.
	 *
	 * @param reference              the reference object with an assigned internal ID
	 *                               that will replace an existing reference in the collection
	 * @param theReferenceCollection the collection of references in which the replacement
	 *                               occurs
	 */
	@SuppressWarnings("MethodMayBeStatic")
	private void replaceReference(
		@Nonnull ReferenceContract reference,
		@Nonnull List<ReferenceContract> theReferenceCollection
	) {
		Assert.isPremiseValid(
			theReferenceCollection.removeIf(
				examinedRef -> examinedRef.getReferenceKey().equals(reference.getReferenceKey()) &&
					examinedRef.getReferenceKey().internalPrimaryKey() == reference.getReferenceKey()
					                                                               .internalPrimaryKey()
			),
			"The reference to replace must exist in the collection!"
		);
		theReferenceCollection.add(reference);
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
					this.entitySchema, reference.getReferenceSchemaOrThrow(), getNextReferenceInternalId(), reference
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
	 * @param referenceSchema      the schema associated with the reference, used to validate its properties and constraints
	 * @param referenceKey         the unique key identifying the referenced entity; this includes the primary key and internal key
	 * @param referencedEntityType the type of the referenced entity; must match the schema-defined type, if provided
	 * @param cardinality          the cardinality of the reference; if provided, must match the schema-defined cardinality
	 * @param whichIs              an optional consumer for setting additional properties of the reference during its building process
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
			throw new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.WRITE);
		}
		// but we allow implicit cardinality widening when needed
		final BuilderReferenceBundle referenceBundle = getReferenceBundleForUpdate(referenceName, 8);
		if (schemaCardinality.getMax() <= 1) {
			final int existingRefCount = referenceBundle.count();
			if (existingRefCount >= 1) {
				// check whether there already is some reference to this key
				// if yes, check whether we can promote cardinality
				if (this.entitySchema.allows(EvolutionMode.UPDATING_REFERENCE_CARDINALITY)) {
					referenceSchema = promoteUniqueCardinality(referenceSchema, referenceKey);
				} else {
					throw new ReferenceCardinalityViolatedException(
						this.entitySchema.getName(),
						List.of(
							new CardinalityViolation(referenceName, schemaCardinality, existingRefCount + 1)
						)
					);
				}
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
					throw new ReferenceAllowsDuplicatesException(referenceName, this.entitySchema, Operation.WRITE);
				} else {
					Assert.isTrue(
						referenceKey.isUnknownReference() ||
							referenceKey.internalPrimaryKey() == referenceContract.getReferenceKey()
							                                                      .internalPrimaryKey(),
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
			this.entitySchema,
			referenceSchema,
			referenceName,
			referencedEntityPrimaryKey,
			referenceKey.isUnknownReference() ?
				getNextReferenceInternalId() :
				referenceKey.internalPrimaryKey(),
			referenceBundle.getAttributeTypes()
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(builder));
		final Reference newReference = builder.build();
		referenceBundle.upsertNonDuplicateReference(newReference);
		addOrReplaceReferenceInternal(newReference);
		return this;
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
									this.entitySchema,
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
									this.entitySchema,
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
				index.compute(
					updatedReference.getReferenceKey(),
					(key, existing) -> existing == References.DUPLICATE_REFERENCE ?
						existing : updatedReference
				);
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
