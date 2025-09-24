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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Contract for classes that allow reading information about references in {@link Entity} instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ReferenceContract extends AttributesContract<AttributeSchemaContract>, Droppable, ContentComparator<ReferenceContract> {
	/**
	 * Use this comparator when you need to sort references in generic order - i.e. by reference name and referenced entity primary key.
	 */
	@Nonnull
	Comparator<ReferenceContract> GENERIC_COMPARATOR = new GenericReferenceComparator();
	/**
	 * Use this comparator when you need to sort references in full order - i.e. by reference name, referenced entity primary key,
	 * and internal primary key.
	 */
	@Nonnull
	Comparator<ReferenceContract> FULL_COMPARATOR = new FullReferenceComparator();

	/**
	 * Method allows to access unique and primary identifier of the ReferenceContract within {@link EntityContract}.
	 */
	@Nonnull
	ReferenceKey getReferenceKey();

	/**
	 * Returns name of the reference. The name always corresponds with {@link #getReferenceSchema()} name.
	 */
	@Nonnull
	default String getReferenceName() {
		return getReferenceKey().referenceName();
	}

	/**
	 * Returns primary key of the referenced (internal or external) entity.
	 */
	default int getReferencedPrimaryKey() {
		return getReferenceKey().primaryKey();
	}

	/**
	 * Returns body of the referenced entity in case its fetching was requested via {@link EntityFetch}
	 * constraint.
	 */
	@Nonnull
	Optional<SealedEntity> getReferencedEntity();

	/**
	 * Returns referenced entity type - conforming to {@link ReferenceSchemaContract#getReferencedEntityType()}.
	 */
	@Nonnull
	String getReferencedEntityType();

	/**
	 * Returns cardinality of the reference. The name always corresponds with {@link #getReferenceSchema()} cardinality.
	 */
	@Nonnull
	Cardinality getReferenceCardinality();

	/**
	 * Returns reference group. Group is composed of entity type and primary key of the referenced group entity.
	 * Group may or may not be Evita entity.
	 */
	@Nonnull
	Optional<GroupEntityReference> getGroup();

	/**
	 * Returns body of the referenced entity in case its fetching was requested via {@link EntityGroupFetch}
	 * constraint.
	 */
	@Nonnull
	Optional<SealedEntity> getGroupEntity();

	/**
	 * Returns schema that describes this type of reference.
	 * NULL can be returned in case schema hasn't yet known the reference type, but will be automatically created
	 * if {@link EvolutionMode#ADDING_REFERENCES} is allowed. So the NULL will be returned in very rare cases.
	 */
	@Nonnull
	Optional<ReferenceSchemaContract> getReferenceSchema();

	/**
	 * Returns schema that describes this type of reference or throws an exception.
	 * NULL can be returned in case schema hasn't yet known the reference type, but will be automatically created
	 * if {@link EvolutionMode#ADDING_REFERENCES} is allowed. So the NULL will be returned in very rare cases.
	 */
	@Nonnull
	ReferenceSchemaContract getReferenceSchemaOrThrow();

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	default int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// version
			MemoryMeasuringConstants.INT_SIZE +
			// dropped
			MemoryMeasuringConstants.BYTE_SIZE +
			// referenced entity
			MemoryMeasuringConstants.REFERENCE_SIZE + getReferenceKey().estimateSize() +
			// group
			MemoryMeasuringConstants.REFERENCE_SIZE + getGroup().stream().mapToInt(GroupEntityReference::estimateSize).sum() +
			// schema
			MemoryMeasuringConstants.REFERENCE_SIZE;
	}

	/**
	 * Returns true if reference differs by any "business" related data from other reference.
	 */
	@Override
	default boolean differsFrom(@Nullable ReferenceContract otherReference) {
		if (otherReference == null) return true;
		if (!Objects.equals(getReferenceKey(), otherReference.getReferenceKey())) return true;
		if (getGroup().map(it -> it.differsFrom(otherReference.getGroup().orElse(null))).orElseGet(() -> otherReference.getGroup().isPresent()))
			return true;
		if (dropped() != otherReference.dropped()) return true;
		return AttributesContract.anyAttributeDifferBetween(this, otherReference);
	}

	/**
	 * This class envelopes reference to the reference group. It adds support for versioning and tombstone on top of basic
	 * {@link EntityReference} structure.
	 *
	 * @param referencedEntity reference to {@link Entity#getType()} of the referenced entity. Might be also any {@link String}
	 *                         that identifies type some external resource not maintained by Evita.
	 * @param primaryKey       reference to {@link Entity#getPrimaryKey()} of the referenced entity. Might be also any integer
	 *                         that uniquely identifies some external resource of type {@link #getType()} not maintained by Evita.
	 * @param version          contains version of this object and gets increased with any entity update. Allows to execute
	 *                         optimistic locking i.e. avoiding parallel modifications.
	 * @param dropped          contains TRUE if reference group reference was dropped - i.e. removed. Such reference is not removed (unless
	 *                         tidying process does it), but are lying in reference with tombstone flag. Dropped reference
	 *                         can be overwritten by a new value continuing with the versioning where it was stopped for the last time.
	 */
	record GroupEntityReference(@Nonnull String referencedEntity, int primaryKey, int version, boolean dropped)
		implements EntityReferenceContract<GroupEntityReference>, Droppable, Serializable, ContentComparator<GroupEntityReference> {
		@Serial private static final long serialVersionUID = 7432447904441796055L;

		@Nonnull
		@Override
		public String getType() {
			return this.referencedEntity;
		}

		@Nonnull
		@Override
		public Integer getPrimaryKey() {
			return this.primaryKey;
		}

		@Override
		public boolean dropped() {
			return this.dropped;
		}

		@Override
		public int version() {
			return this.version;
		}

		@Override
		public int compareTo(@Nonnull GroupEntityReference o) {
			return compareReferenceContract(o);
		}

		/**
		 * Returns true if reference group differs by any "business" related data from other reference group.
		 */
		@Override
		public boolean differsFrom(@Nullable GroupEntityReference otherReferenceGroup) {
			if (otherReferenceGroup == null) {
				return true;
			}
			if (!Objects.equals(this.primaryKey, otherReferenceGroup.primaryKey())) {
				return true;
			}
			return this.dropped != otherReferenceGroup.dropped();
		}

		public int estimateSize() {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
				// type
				EvitaDataTypes.estimateSize(this.referencedEntity) +
				// primary key
				MemoryMeasuringConstants.INT_SIZE +
				//version
				MemoryMeasuringConstants.INT_SIZE +
				// dropped
				MemoryMeasuringConstants.BYTE_SIZE;
		}

		@Nonnull
		@Override
		public String toString() {
			return (this.dropped ? "❌ " : "") +
				"`" + this.referencedEntity + "`" + " with key " + getPrimaryKey();
		}
	}

	/**
	 * A comparator implementation for comparing two instances of {@link ReferenceContract}.
	 * The comparison is based on the generic ordering of their {@link ReferenceKey} values - i.e. comparing only
	 * by reference name and referenced entity primary key.
	 */
	class GenericReferenceComparator implements Comparator<ReferenceContract>, Serializable {
		@Serial private static final long serialVersionUID = -146990155014983687L;

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return ReferenceKey.GENERIC_COMPARATOR.compare(o1.getReferenceKey(), o2.getReferenceKey());
		}

	}

	/**
	 * A comparator implementation for comparing two instances of {@link ReferenceContract}.
	 * The comparison is based on the full ordering of their {@link ReferenceKey} values including internal primary key.
	 */
	class FullReferenceComparator implements Comparator<ReferenceContract>, Serializable {
		@Serial private static final long serialVersionUID = 3357522966949186255L;

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return ReferenceKey.FULL_COMPARATOR.compare(o1.getReferenceKey(), o2.getReferenceKey());
		}

	}

}
