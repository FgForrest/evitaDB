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
import java.util.stream.Collectors;

/**
 * Reference bundle used during initial entity build to track and validate references before they are
 * materialized in indexes.
 *
 * The bundle keeps a mapping of {@link RepresentativeReferenceKey} to an internal reference primary key
 * and supports two operational modes:
 *
 * - Non‑duplicate mode: only a single reference for the given {@link io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey}
 *   may exist. Keys are stored without representative attributes. Methods: {@link #addNonDuplicateReference(ReferenceContract)}
 *   and {@link #removeNonDuplicateReference(ReferenceContract)}.
 * - Duplicate mode: multiple references with the same reference name and referenced primary key may coexist,
 *   but they must be distinguishable by a set of representative attributes derived from the reference schema.
 *   Keys are then stored with representative attribute values. Methods: {@link #addDuplicateReference(ReferenceContract)}
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
public class InitialReferenceBundle {
	/**
	 * Map of primary keys for the references identified by {@link RepresentativeReferenceKey}.
	 */
	private final Map<RepresentativeReferenceKey, Integer> referencePrimaryKeys;
	/**
	 * Cached representative attribute definition for quick access.
	 */
	@Nullable private RepresentativeAttributeDefinition representativeAttributeDefinition;
	/**
	 * Map of attribute types for the reference shared for all references of the same type.
	 */
	@Getter private final Map<String, AttributeSchemaContract> attributeTypes = new LazyHashMap<>(4);

	public InitialReferenceBundle(int expectedCount) {
		this.referencePrimaryKeys = CollectionUtils.createHashMap(expectedCount);
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
	public void addNonDuplicateReference(@Nonnull ReferenceContract reference) {
		final int internalPk = reference.getReferenceKey().internalPrimaryKey();
		final RepresentativeReferenceKey genericRRK = new RepresentativeReferenceKey(reference.getReferenceKey());
		if (internalPk != this.referencePrimaryKeys.computeIfAbsent(genericRRK, key -> internalPk)) {
			throw new GenericEvitaInternalError(
				"Reference " + reference.getReferenceKey() + " is not expected to be duplicate!"
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
		final int internalPk = reference.getReferenceKey().internalPrimaryKey();
		final RepresentativeReferenceKey genericRRK = new RepresentativeReferenceKey(reference.getReferenceKey());
		final Integer genericInternalPk = this.referencePrimaryKeys.remove(genericRRK);
		Assert.isPremiseValid(
			genericInternalPk != null,
			() -> "The reference `" + reference.getReferenceName() + "` is not present in the structure!"
		);
		if (internalPk != previousReference.getReferenceKey().internalPrimaryKey()) {
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
				previousReference != null && previousReference.getReferenceKey().internalPrimaryKey() != internalPk,
				() -> "Previous reference must be present and must have different primary key!"
			);
			this.referencePrimaryKeys.put(
				new RepresentativeReferenceKey(previousReference.getReferenceKey(), this.representativeAttributeDefinition.getRepresentativeValues(previousReference)),
				previousReference.getReferenceKey().internalPrimaryKey()
			);
			// now we can add the new duplicate reference
			addDuplicateReference(reference);
		} else {
			throw new GenericEvitaInternalError(
				"Insertion should produce duplicate reference, but it did not!"
			);
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
	public void addDuplicateReference(
		@Nonnull ReferenceContract reference
	) {
		Assert.isPremiseValid(
			this.representativeAttributeDefinition != null,
			() -> "The reference `" + reference.getReferenceName() + "` is not marked as duplicate!"
		);
		final int internalPk = reference.getReferenceKey().internalPrimaryKey();
		// continue with duplicate check using representative attributes
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			reference.getReferenceKey(),
			this.representativeAttributeDefinition.getRepresentativeValues(reference)
		);
		final Integer previousValue = this.referencePrimaryKeys.put(
			rrk,
			internalPk
		);
		if (previousValue != null && !previousValue.equals(internalPk)) {
			// rollback the change
			this.referencePrimaryKeys.put(
				rrk,
				previousValue
			);
			// this is a problem - we have two different references with the same representative keys
			throw new InvalidMutationException(
				"Cannot add duplicate reference `" + reference.getReferenceName() +
					"` with the same representative attributes " + Arrays.toString(rrk.representativeAttributeValues()) +
					" as it would be indistinguishable from existing reference with internal id " +
					internalPk + "!"
			);
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
		Assert.isPremiseValid(
			this.representativeAttributeDefinition == null,
			() -> "The reference `" + reference.getReferenceName() + "` is marked as duplicate!"
		);
		final int internalPk = reference.getReferenceKey().internalPrimaryKey();
		final RepresentativeReferenceKey genericRRK = new RepresentativeReferenceKey(reference.getReferenceKey());
		final Integer removedPk = this.referencePrimaryKeys.remove(genericRRK);
		if (removedPk == null || removedPk != internalPk) {
			throw new GenericEvitaInternalError(
				"Reference " + reference.getReferenceKey() + " is not present in the structure!"
			);
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
		final int internalPk = reference.getReferenceKey().internalPrimaryKey();
		final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
			reference.getReferenceKey(),
			this.representativeAttributeDefinition.getRepresentativeValues(reference)
		);
		final Integer removedPk = this.referencePrimaryKeys.remove(rrk);
		if (removedPk == null || removedPk != internalPk) {
			throw new GenericEvitaInternalError(
				"Reference " + reference.getReferenceKey() + " is not present in the structure!"
			);
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
			this.referencePrimaryKeys.size() <= 1,
			() -> "Cannot discard duplicates when there are multiple references present!"
		);
		this.representativeAttributeDefinition = null;
		this.referencePrimaryKeys.clear();
		this.referencePrimaryKeys.put(new RepresentativeReferenceKey(referenceKey), referenceKey.internalPrimaryKey());
	}

	/**
	 * Returns the total number of internal primary key references maintained
	 * in the internal structure. This count represents all unique references
	 * stored in the system.
	 *
	 * @return the number of unique primary keys tracked in the internal structure.
	 */
	public int count() {
		return this.referencePrimaryKeys.size();
	}

}
