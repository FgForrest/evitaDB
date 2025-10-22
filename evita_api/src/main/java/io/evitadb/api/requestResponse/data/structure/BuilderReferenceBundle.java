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


import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reference bundle used during initial entity build to track and validate references before they are
 * materialized in indexes.
 *
 * The bundle keeps a mapping of {@link RepresentativeReferenceKey} to an internal reference primary key
 * and supports two operational modes:
 *
 * - Non‑duplicate mode: only a single reference for the given {@link io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey}
 *   may exist. Keys are stored without representative attributes. Methods: {@link #upsertNonDuplicateReference(ReferenceContract)}
 *   and {@link #removeNonDuplicateReference(ReferenceContract)}.
 * - Duplicate mode: multiple references with the same reference name and referenced primary key may coexist,
 *   but they must be distinguishable by a set of representative attributes derived from the reference schema.
 *   Keys are then stored with representative attribute values. Methods: {@link #upsertDuplicateReference(ReferenceContract)}
 *   and {@link #removeDuplicateReference(ReferenceContract)}.
 *
 * Transition from non‑duplicate to duplicate mode is handled by {@link #convertToDuplicateReference(ReferenceContract, ReferenceContract)}.
 * It lazily initializes {@link #representativeAttributeDefinition} from the reference schema and rewrites the
 * previously inserted non‑duplicate item to its representative form. Duplicates can be discarded via
 * {@link #discardDuplicates(io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey)} which resets the
 * bundle back to non‑duplicate mode.
 *
 * The class is intentionally minimal and allocation‑aware; it uses a specialized {@link io.evitadb.dataType.map.LazyHashMap}
 * for small maps and postpones computation of the representative attribute definition until absolutely necessary.
 *
 * This helper is package‑private API for builders; it performs strict validations and never uses exceptions
 * as control‑flow. When invariants are broken, it throws {@link io.evitadb.exception.GenericEvitaInternalError} or
 * {@link io.evitadb.api.exception.InvalidMutationException} with descriptive messages.
 *
 * Thread‑safety: this class is not thread‑safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
class BuilderReferenceBundle {
	/**
	 * Map of primary keys for the references identified by {@link RepresentativeReferenceKey}.
	 */
	private final Map<RepresentativeReferenceKey, Integer> repRefKeysToInternalPk;
	/**
	 * Reverse map of representative reference keys for the internal primary keys.
	 */
	private final Map<Integer, RepresentativeReferenceKey> internalPkToRepRefKeys;
	/**
	 * Cached representative attribute definition for quick access.
	 */
	@Nullable private RepresentativeAttributeDefinition representativeAttributeDefinition;
	/**
	 * Map of attribute types for the reference shared for all references of the same type.
	 */
	@Getter private final Map<String, AttributeSchemaContract> attributeTypes = new LazyHashMap<>(4);
	/**
	 * Flag indicating whether the bundle has been initialized with any references.
	 */
	private boolean initialized;

	public BuilderReferenceBundle(int expectedCount) {
		this.repRefKeysToInternalPk = CollectionUtils.createHashMap(expectedCount);
		this.internalPkToRepRefKeys = CollectionUtils.createHashMap(expectedCount);
	}

	/**
	 * Checks whether the bundle has been successfully initialized.
	 *
	 * @return {@code true} if the bundle is initialized; {@code false} otherwise.
	 */
	public boolean isInitialized() {
		return this.initialized || !this.internalPkToRepRefKeys.isEmpty();
	}

	/**
	 * Initializes the bundle using the provided initializer function. The initialization
	 * process is performed only if the bundle has not been previously initialized.
	 *
	 * @param initializer a {@link Consumer} function that takes this {@code BuilderReferenceBundle}
	 *                    instance as a parameter. This function is used to perform the
	 *                    initialization logic for the bundle. It must not be null.
	 */
	public void initializeBundleIfNecessary(@Nonnull Consumer<BuilderReferenceBundle> initializer) {
		if (!this.initialized) {
			initializer.accept(this);
			this.initialized = true;
		}
	}

	/**
	 * Adds the provided reference to the internal structure if it is not already a duplicate.
	 * This method checks whether the reference's internal primary key is already associated
	 * with the representative reference key. If the reference is detected as a duplicate, an
	 * exception is thrown indicating unexpected duplication.
	 *
	 * @param reference the reference object being added, must not be null
	 *        and should uniquely identify itself with an internal primary key.
	 *        The method will ensure that the reference is not a duplicate in the
	 *        internal structure.
	 */
	public void upsertNonDuplicateReference(@Nonnull ReferenceContract reference) {
		final ReferenceKey referenceKey = reference.getReferenceKey();
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final int internalPk = referenceKey.internalPrimaryKey();
		final RepresentativeReferenceKey genericRRK = new RepresentativeReferenceKey(referenceKey);
		if (internalPk != this.repRefKeysToInternalPk.computeIfAbsent(genericRRK, key -> internalPk)) {
			throw new GenericEvitaInternalError(
				"Reference " + referenceKey + " is not expected to be duplicate!"
			);
		} else {
			this.internalPkToRepRefKeys.put(internalPk, genericRRK);
		}
	}

	/**
	 * Inserts or updates a reference, ensuring that duplicate references are appropriately converted
	 * and handled based on the internal structure and attributes. This method verifies the state
	 * of the reference and determines whether it should be treated as a duplicate, converted into
	 * a duplicate, or added as a non-duplicate.
	 *
	 * @param upsertedReference the reference to be upserted. Must not be null and should include
	 *                          a valid reference key. The reference key should not be marked as unknown.
	 * @param previousReferenceFetcher a function that retrieves an existing reference associated
	 *                                 with the given reference key. Must not be null and should return
	 *                                 a valid reference or null if no matching reference exists.
	 */
	public void upsertWithDuplicateReferenceConversion(
		@Nonnull ReferenceContract upsertedReference,
		@Nonnull Function<ReferenceKey, ReferenceContract> previousReferenceFetcher
	) {
		final ReferenceKey referenceKey = upsertedReference.getReferenceKey();
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final RepresentativeReferenceKey rrk = this.internalPkToRepRefKeys.get(referenceKey.internalPrimaryKey());
		if (rrk != null && rrk.representativeAttributeValues().length > 0) {
			upsertDuplicateReference(upsertedReference);
		} else {
			final Integer genericInternalPk = this.repRefKeysToInternalPk.get(
				new RepresentativeReferenceKey(
					new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey())
				)
			);
			// if no generic primary key is present, we are safe to add non-duplicate reference
			if (genericInternalPk == null) {
				upsertNonDuplicateReference(upsertedReference);
			} else if (genericInternalPk == 0) {
				// zero in the map means that there are duplicates for this generic key
				upsertDuplicateReference(upsertedReference);
			} else if (!genericInternalPk.equals(referenceKey.internalPrimaryKey())) {
				convertToDuplicateReference(
					upsertedReference,
					previousReferenceFetcher.apply(
						new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey(), genericInternalPk)
					)
				);
				upsertDuplicateReference(upsertedReference);
			}
		}
	}

	/**
	 * Adds the provided reference to the internal structure as a duplicate.
	 * This method checks whether the reference's internal primary key is already
	 * associated with the representative reference key. If a duplicate reference is detected
	 * with differing internal primary keys, the method will roll back the change and throw
	 * an exception to signal the duplication issue.
	 *
	 * @param reference the reference object being added, must not be null.
	 *                  This reference should uniquely identify itself with an internal
	 *                  primary key and representative attributes. The method ensures proper
	 *                  handling of duplicates within the internal structure.
	 */
	public void upsertDuplicateReference(
		@Nonnull ReferenceContract reference
	) {
		Assert.isPremiseValid(
			this.representativeAttributeDefinition != null,
			() -> "The reference `" + reference.getReferenceName() + "` is not marked as duplicate!"
		);
		final ReferenceKey referenceKey = reference.getReferenceKey();
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final int internalPk = referenceKey.internalPrimaryKey();
		// continue with duplicate check using representative attributes
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			referenceKey,
			this.representativeAttributeDefinition.getRepresentativeValues(reference)
		);
		final Integer previousValue = this.repRefKeysToInternalPk.put(rrk, internalPk);
		if (previousValue != null && !previousValue.equals(internalPk)) {
			// rollback the change
			this.repRefKeysToInternalPk.put(rrk, previousValue);
			// this is a problem - we have two different references with the same representative keys
			throw new InvalidMutationException(
				"Cannot add duplicate reference `" + reference.getReferenceName() +
					"` with the same representative attributes " + Arrays.toString(rrk.representativeAttributeValues()) +
					" as it would be indistinguishable from existing reference with internal id " +
					internalPk + "!"
			);
		} else {
			final RepresentativeReferenceKey previousRRK = this.internalPkToRepRefKeys.put(internalPk, rrk);
			final Integer removedPk = this.repRefKeysToInternalPk.remove(previousRRK);
			Assert.isPremiseValid(
				removedPk == null || removedPk == internalPk,
				() -> "Inconsistent internal structure!"
			);
		}
	}

	/**
	 * Converts the given reference into a duplicate reference and manages its integration into the
	 * internal reference structure. This method ensures that the reference is correctly associated
	 * within the internal structure while verifying its validity as a duplicate.
	 *
	 * @param reference the new reference that is being added as a duplicate, must not be null
	 * @param previousReference the reference that already exists and is used as a baseline for duplication validation, must not be null
	 */
	public void convertToDuplicateReference(
		@Nonnull ReferenceContract reference,
		@Nonnull ReferenceContract previousReference
	) {
		final ReferenceKey referenceKey = reference.getReferenceKey();
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final int internalPk = referenceKey.internalPrimaryKey();
		final RepresentativeReferenceKey genericRRK = new RepresentativeReferenceKey(referenceKey);
		final Integer genericInternalPk = this.repRefKeysToInternalPk.remove(genericRRK);
		Assert.isPremiseValid(
			genericInternalPk != null,
			() -> "The reference `" + reference.getReferenceName() + "` is not present in the structure!"
		);
		final int previousRefInternalPk = previousReference.getReferenceKey().internalPrimaryKey();
		if (internalPk != previousRefInternalPk) {
			this.representativeAttributeDefinition = reference
				.getReferenceSchema()
				.map(schema -> {
					if (schema instanceof ReferenceSchema referenceSchema) {
						return referenceSchema.getRepresentativeAttributeDefinition();
					} else {
						return new RepresentativeAttributeDefinition(
							schema.getAttributes()
							      .entrySet()
							      .stream()
							      .filter(it -> it.getValue().isRepresentative())
							      .collect(
								      Collectors.toMap(
									      Entry::getKey,
									      it -> (AttributeSchema) it.getValue()
								      )
							      )
						);
					}
				})
				.orElseGet(() -> new RepresentativeAttributeDefinition(Collections.emptyMap()));

			// we need to lazy register proper reference attribute key for previous reference
			Assert.isPremiseValid(
				previousReference != null && previousRefInternalPk != internalPk,
				() -> "Previous reference must be present and must have different primary key!"
			);
			final RepresentativeReferenceKey newRRK = new RepresentativeReferenceKey(
				previousReference.getReferenceKey(),
				this.representativeAttributeDefinition.getRepresentativeValues(previousReference)
			);
			// zero in the map means that there are duplicates for this generic key
			this.repRefKeysToInternalPk.put(genericRRK, 0);
			this.repRefKeysToInternalPk.put(newRRK, previousRefInternalPk);
			this.internalPkToRepRefKeys.remove(genericInternalPk);
			this.internalPkToRepRefKeys.put(previousRefInternalPk, newRRK);
			// now we can add the new duplicate reference
			upsertDuplicateReference(reference);
		} else {
			throw new GenericEvitaInternalError(
				"Insertion should produce duplicate reference, but it did not!"
			);
		}
	}

	/**
	 * Removes the specified reference from the internal structure.
	 * Ensures the reference key is present and valid before removing it,
	 * and handles duplicate and non-duplicate references accordingly.
	 *
	 * @param reference the reference object to be removed. Must not be null.
	 */
	public void removeReference(@Nonnull ReferenceContract reference) {
		final RepresentativeReferenceKey rrk = this.internalPkToRepRefKeys.get(
			reference.getReferenceKey().internalPrimaryKey()
		);
		Assert.isPremiseValid(
			rrk != null,
			() -> "Reference " + reference.getReferenceKey() + " is not present in the structure!"
		);
		final Integer reverseInternalPk = this.repRefKeysToInternalPk.get(rrk);
		if (reverseInternalPk == 0 || rrk.representativeAttributeValues().length > 0) {
			removeDuplicateReference(reference);
		} else {
			removeNonDuplicateReference(reference);
		}
	}

	/**
	 * Removes a reference from the internal structure if it is not classified as a duplicate.
	 * This method verifies that the provided reference exists in the internal structure
	 * and matches the associated internal primary key. If the reference is not found
	 * or does not match the expected primary key, an exception is thrown to indicate
	 * inconsistency in the structure.
	 *
	 * @param reference the reference object to be removed, must not be null.
	 *                  The reference should uniquely identify itself with an internal
	 *                  primary key and a representative reference key. The method ensures
	 *                  that only references that exist in the structure are removed.
	 */
	public void removeNonDuplicateReference(@Nonnull ReferenceContract reference) {
		final ReferenceKey referenceKey = reference.getReferenceKey();
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final int internalPk = referenceKey.internalPrimaryKey();
		final RepresentativeReferenceKey genericRRK = new RepresentativeReferenceKey(referenceKey);
		final Integer removedPk = this.repRefKeysToInternalPk.remove(genericRRK);
		if (removedPk == null || removedPk != internalPk) {
			throw new GenericEvitaInternalError(
				"Reference " + referenceKey + " is not present in the structure!"
			);
		} else {
			this.internalPkToRepRefKeys.remove(internalPk);
		}
	}

	/**
	 * Removes a duplicate reference from the internal structure.
	 * This method ensures that the provided reference is recognized as a duplicate
	 * based on its representative reference key. If the reference is not found
	 * or the internal primary key does not match the expected value, an exception is thrown
	 * indicating an inconsistency in the reference structure.
	 *
	 * @param reference the reference object to be removed, must not be null.
	 *                  The reference should uniquely identify itself with an internal
	 *                  primary key and representative attributes, and must be marked
	 *                  as a duplicate in the system.
	 */
	public void removeDuplicateReference(@Nonnull ReferenceContract reference) {
		Assert.isPremiseValid(
			this.representativeAttributeDefinition != null,
			() -> "The reference `" + reference.getReferenceName() + "` is not marked as duplicate!"
		);
		final ReferenceKey referenceKey = reference.getReferenceKey();
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final int internalPk = referenceKey.internalPrimaryKey();
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			referenceKey,
			this.representativeAttributeDefinition.getRepresentativeValues(reference)
		);
		final Integer removedPk = this.repRefKeysToInternalPk.remove(rrk);
		if (removedPk == null || removedPk != internalPk) {
			throw new GenericEvitaInternalError(
				"Reference " + referenceKey + " is not present in the structure!"
			);
		} else {
			this.internalPkToRepRefKeys.remove(internalPk);
		}
	}

	/**
	 * Determines if the given reference key is associated with a duplicate in the internal structure.
	 * This method checks if the reference key corresponds to a representative reference key that
	 * contains at least one representative attribute value, indicating the presence of duplicates.
	 *
	 * @param referenceKey the reference key to check for duplicates, must not be null.
	 *                     The reference key should uniquely identify itself with an internal
	 *                     primary key and should not be an unknown reference.
	 * @return {@code true} if the reference key is associated with a duplicate;
	 *         {@code false} otherwise.
	 */
	public boolean isDuplicate(@Nonnull ReferenceKey referenceKey) {
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final RepresentativeReferenceKey rrk = this.internalPkToRepRefKeys.get(referenceKey.internalPrimaryKey());
		if (rrk != null && rrk.representativeAttributeValues().length > 0) {
			return true;
		} else {
			final Integer genericInternalPk = this.repRefKeysToInternalPk.get(
				new RepresentativeReferenceKey(
					new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey())
				)
			);
			return genericInternalPk == null || !genericInternalPk.equals(referenceKey.internalPrimaryKey());
		}
	}

	/**
	 * Removes all duplicate references associated with the specified reference key
	 * from the internal structure. This method ensures that there is only a single
	 * reference present in the structure and that it is not marked as a duplicate.
	 * It clears any previously set representative attribute definitions and
	 * updates the internal structure to reflect the absence of duplicates.
	 *
	 * @param referenceKey the key of the reference whose duplicates are to
	 *                     be discarded. This must not be null and should
	 *                     uniquely identify the associated reference in the
	 *                     internal structure.
	 */
	public void discardDuplicates(@Nonnull ReferenceKey referenceKey) {
		Assert.isPremiseValid(
			this.representativeAttributeDefinition != null,
			() -> "The reference `" + referenceKey.referenceName() + "` is not marked as duplicate!"
		);
		Assert.isPremiseValid(
			!referenceKey.isUnknownReference(),
			() -> "Method requires known reference key!"
		);
		final RepresentativeReferenceKey genericRRK = new RepresentativeReferenceKey(referenceKey);
		final int internalPk = referenceKey.internalPrimaryKey();
		final RepresentativeReferenceKey previousRRK = this.internalPkToRepRefKeys.put(internalPk, genericRRK);
		Assert.isPremiseValid(
			internalPk == this.repRefKeysToInternalPk.remove(previousRRK),
			() -> "Inconsistent internal structure!"
		);
		this.repRefKeysToInternalPk.put(genericRRK, internalPk);
	}

	/**
	 * Returns the total number of internal primary key references maintained
	 * in the internal structure. This count represents all unique references
	 * stored in the system.
	 *
	 * @return the number of unique primary keys tracked in the internal structure.
	 */
	public int count() {
		return this.internalPkToRepRefKeys.size();
	}

	/**
	 * Determines if the specified ReferenceKey is present in the current collection or system.
	 *
	 * @param referenceKey a non-null ReferenceKey object to be checked for existence
	 * @return true if the specified ReferenceKey is found, false otherwise
	 */
	public boolean containsReferenceKey(@Nonnull ReferenceKey referenceKey) {
		return this.repRefKeysToInternalPk.keySet()
			.stream()
			.anyMatch(it -> ReferenceKey.GENERIC_COMPARATOR.compare(it.referenceKey(), referenceKey) == 0);
	}
}
