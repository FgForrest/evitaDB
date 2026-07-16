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

package io.evitadb.api.requestResponse.mutation.conflict;


import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.dataType.NumberRange;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Reference attribute-level conflict key for serializing concurrent mutations of an attribute delta change.
 *
 * @see ConflictKey
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ReferenceAttributeDeltaConflictKey(
	@Nonnull String entityType,
	@Nullable Integer entityPrimaryKey,
	@Nonnull ReferenceKey referenceKey,
	@Nonnull AttributeKey attributeKey,
	@Nonnull Number deltaValue,
	@Nullable NumberRange<?> allowedRange,
	boolean sharedSurface
) implements CommutativeConflictKey<Number> {

	/**
	 * A range-constrained reference-attribute delta is contained by the write it can conflict with. When the
	 * entity primary key is not yet assigned (delta applied during entity creation) no entity-level key can be
	 * formed, so the finest derivable containing scope is the collection. Otherwise the parent depends on
	 * whether the reference attribute was carved out of the entity's shared surface at the time this key was
	 * emitted: a carved-out reference attribute ({@link #sharedSurface} false) reaches the absolute
	 * {@link ReferenceAttributeConflictKey} it can conflict with — the owning key is name-only, so the delta's
	 * locale-bearing {@link AttributeKey} is projected to its name — while a delta on the shared surface
	 * ({@link #sharedSurface} true) reaches the {@link EntityResidualConflictKey} a coarse writer of that
	 * surface would emit instead.
	 *
	 * @return a {@link ReferenceAttributeConflictKey} for the same reference attribute, an
	 *         {@link EntityResidualConflictKey} for the owning entity's shared surface, or a
	 *         {@link CollectionConflictKey} when the primary key is not yet known
	 */
	@Nonnull
	@Override
	public ConflictKey parentConflictKey() {
		if (this.entityPrimaryKey == null) {
			return new CollectionConflictKey(this.entityType);
		}
		return this.sharedSurface ?
			new EntityResidualConflictKey(this.entityType, this.entityPrimaryKey) :
			new ReferenceAttributeConflictKey(
				this.entityType,
				this.entityPrimaryKey,
				this.referenceKey.referenceName(),
				this.referenceKey.primaryKey(),
				this.attributeKey.attributeName()
			);
	}

	@Nonnull
	@Override
	public Number aggregate(@Nonnull Number one, @Nonnull Number two) {
		return NumberUtils.sum(one, two);
	}

	@Override
	public boolean isConstrainedToRange() {
		return this.allowedRange != null;
	}

	@Override
	public void assertInAllowedRange(
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull Number accumulatedValue
	) {
		if (this.allowedRange != null && !this.allowedRange.isWithin(accumulatedValue)) {
			throw new ConflictingCatalogCommutativeMutationException(
				catalogName,
				this,
				catalogVersion,
				"The accumulated value `" + accumulatedValue + "` for " + this +
					" is outside the allowed range `" + this.allowedRange + "`."
			);
		}
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (!(o instanceof ReferenceAttributeDeltaConflictKey that)) return false;

		return this.entityType.equals(that.entityType) &&
			Objects.equals(this.referenceKey, that.referenceKey) &&
			Objects.equals(this.deltaValue, that.deltaValue) &&
			Objects.equals(this.entityPrimaryKey, that.entityPrimaryKey) &&
			this.attributeKey.equals(that.attributeKey) &&
			Objects.equals(this.allowedRange, that.allowedRange) &&
			this.sharedSurface == that.sharedSurface;
	}

	/**
	 * Hashes the full identity, consistent with {@link #equals(Object)} (including the delta value, allowed
	 * range and the shared-surface flag). Accumulation grouping is handled separately by
	 * {@link #aggregationKey()}, so the hash no longer needs to collapse keys that differ only in their delta.
	 */
	@Override
	public int hashCode() {
		int result = this.entityType.hashCode();
		result = 31 * result + Objects.hashCode(this.referenceKey);
		result = 31 * result + Objects.hashCode(this.entityPrimaryKey);
		result = 31 * result + this.attributeKey.hashCode();
		result = 31 * result + this.deltaValue.hashCode();
		result = 31 * result + Objects.hashCode(this.allowedRange);
		result = 31 * result + Boolean.hashCode(this.sharedSurface);
		return result;
	}

	/**
	 * {@inheritDoc}
	 *
	 * Requires a resolved {@link #entityPrimaryKey()}: commit-time accumulation is the sole consumer of the
	 * aggregation key, and by then a generated primary key has already been assigned during upsert (see
	 * {@code EntityCollection.verifyPrimaryKeyAssignment}) — well before the mutation is read back from the
	 * WAL for conflict resolution. A null primary key here would collapse the accumulation slot of every
	 * concurrently-created entity of the same type into one, so it is rejected as a programming error rather
	 * than silently mis-merged. This precondition is deliberately stricter than {@link #parentConflictKey()},
	 * which stays null-tolerant because it serves the scope/containment path, not accumulation.
	 *
	 * Deliberately does not include {@link #sharedSurface}: two deltas on the same reference attribute must
	 * accumulate into a single running total regardless of whether the attribute was carved out at the time
	 * either delta was emitted, since the carve-out decision can only be evaluated against the schema in
	 * effect at emission time and must not fragment a single attribute's accumulation slot.
	 *
	 * @return a {@link DeltaAggregationKey} over the entity type, primary key, reference key and attribute
	 *         key, so deltas on the same reference attribute accumulate regardless of delta value or range
	 */
	@Nonnull
	@Override
	public Object aggregationKey() {
		Assert.isPremiseValid(
			this.entityPrimaryKey != null,
			"A commutative reference-attribute-delta conflict key must carry a resolved entity primary key " +
				"before it can take part in commit-time accumulation."
		);
		return new DeltaAggregationKey(this.entityType, this.entityPrimaryKey, this.referenceKey, this.attributeKey);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return {@link ConflictScope#REFERENCE_ATTRIBUTE}
	 */
	@Nonnull
	@Override
	public ConflictScope conflictScope() {
		return ConflictScope.REFERENCE_ATTRIBUTE;
	}

	/**
	 * Returns a concise, human-readable representation of this conflict key.
	 *
	 * @return non-null string representation
	 */
	@Nonnull
	@Override
	public String toString() {
		return "reference `" + this.referenceKey + "` attribute delta `" + this.attributeKey + "` of entity `" + this.entityType + "` with primary key `" + this.entityPrimaryKey + '`';
	}

}
