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

package io.evitadb.api.requestResponse.schema.dto;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds a precomputed, ordered view of all representative attributes for an entity schema.
 *
 * The class extracts attribute names that are marked as representative, sorts them lexicographically and
 * builds:
 * - a stable order of representative attribute names
 * - an array of their default values in the same order
 * - a fast lookup map of attribute name to its index in the representative list
 *
 * The data are immutable once created. The returned default values are always provided as a defensive copy to
 * protect internal state from accidental modification.
 *
 * This class is performance‑sensitive and avoids unnecessary allocations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class RepresentativeAttributeDefinition implements Serializable {
	@Serial private static final long serialVersionUID = 1946518348250618864L;
	/**
	 * Ordered list of representative attribute names (lexicographical order).
	 */
	private final List<String> representativeAttributeNames;
	/**
	 * Fast lookup from attribute name to its index in {@link #representativeAttributeNames}.
	 */
	private final Map<String, Integer> representativeAttributeIndexes;
	/**
	 * Default values of representative attributes aligned by index with {@link #representativeAttributeNames}.
	 */
	@Nonnull
	public final Serializable[] defaultValues;

	/**
	 * Creates a new definition from the provided attribute schemas.
	 *
	 * Only attributes flagged as representative are considered. Their names are sorted to achieve a stable order.
	 *
	 * @param attributes mapping of attribute name to its schema, must not be null
	 */
	public RepresentativeAttributeDefinition(@Nonnull Map<String, AttributeSchema> attributes) {
		this.representativeAttributeNames = attributes
			.values()
			.stream()
			.filter(AttributeSchema::isRepresentative)
			.map(AttributeSchema::getName)
			.sorted()
			.toList();
		this.defaultValues = new Serializable[this.representativeAttributeNames.size()];
		this.representativeAttributeIndexes = CollectionUtils.createHashMap(this.representativeAttributeNames.size());
		for (int i = 0; i < this.representativeAttributeNames.size(); i++) {
			final String attributeName = this.representativeAttributeNames.get(i);
			// align default value with its index and remember the index for quick reverse lookup
			this.defaultValues[i] = attributes.get(attributeName).getDefaultValue();
			this.representativeAttributeIndexes.put(attributeName, i);
		}
	}

	/**
	 * Returns default values of representative attributes in the same order as {@link #getAttributeNames()}.
	 * A defensive copy is returned to keep this instance immutable.
	 *
	 * @return non-null array of default values (may be empty)
	 */
	@Nonnull
	public Serializable[] getDefaultValues() {
		return this.defaultValues.length == 0 ?
			ArrayUtils.EMPTY_SERIALIZABLE_ARRAY :
			Arrays.copyOf(this.defaultValues, this.defaultValues.length);
	}

	/**
	 * Returns ordered names of representative attributes.
	 *
	 * @return non-null list of attribute names (may be empty)
	 */
	@Nonnull
	public List<String> getAttributeNames() {
		return this.representativeAttributeNames;
	}

	/**
	 * Returns the index of the provided attribute name within {@link #getAttributeNames()}.
	 *
	 * @param attributeName attribute name whose index is requested
	 * @return index wrapped in OptionalInt or empty when the name is not representative or unknown
	 */
	@Nonnull
	public OptionalInt getAttributeNameIndex(@Nonnull String attributeName) {
		final Integer index = this.representativeAttributeIndexes.get(attributeName);
		if (index == null || index < 0) {
			return OptionalInt.empty();
		} else {
			return OptionalInt.of(index);
		}
	}

	/**
	 * Two definitions are equal when they contain the same ordered names and the same ordered default values.
	 */
	@Override
	public final boolean equals(@javax.annotation.Nullable Object o) {
		if (!(o instanceof final RepresentativeAttributeDefinition that)) return false;

		return this.representativeAttributeNames.equals(that.representativeAttributeNames) && Arrays.equals(
			this.defaultValues, that.defaultValues);
	}

	/**
	 * Hash is derived from ordered names and default values.
	 */
	@Override
	public int hashCode() {
		int result = this.representativeAttributeNames.hashCode();
		result = 31 * result + Arrays.hashCode(this.defaultValues);
		return result;
	}

	/**
	 * Formats the representative attributes as: name1=default1, name2=default2, ... in stable order.
	 */
	@Override
	public String toString() {
		// initial capacity ~ names * 64 chars to minimize resizes while staying light-weight
		final StringBuilder sb = new StringBuilder(this.representativeAttributeNames.size() << 6);
		for (int i = 0; i < this.representativeAttributeNames.size(); i++) {
			final String attributeName = this.representativeAttributeNames.get(i);
			sb.append(attributeName).append("=").append(this.defaultValues[i]);
			if (i < this.representativeAttributeNames.size() - 1) {
				sb.append(", ");
			}
		}
		return sb.toString();
	}

	/**
	 * Retrieves an array of representative attribute values, populated with default values and replaced by
	 * values from the provided reference when available and valid.
	 *
	 * If no reference is provided or if a specific attribute does not have a valid value in the reference,
	 * the default value for that attribute will be returned.
	 *
	 * @param reference the reference contract used to fetch attribute values, may be null
	 * @return an array of representative attribute values, never null
	 */
	@Nonnull
	public Serializable[] getRepresentativeValues(@Nullable ReferenceContract reference) {
		// first fill representative attributes with default values
		final Serializable[] representativeAttributes = getDefaultValues();
		if (reference != null) {
			for (int i = 0; i < this.representativeAttributeNames.size(); i++) {
				String attributeName = this.representativeAttributeNames.get(i);
				final Optional<Serializable> currentValue = reference.getAttributeValue(attributeName)
					.filter(Droppable::exists)
					.map(AttributeValue::valueOrThrowException);
				if (currentValue.isPresent()) {
					representativeAttributes[i] = currentValue.get();
				}
			}
		}
		return representativeAttributes;
	}
}
