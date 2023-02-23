/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ComparatorUtils;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This interface prescribes a set of methods that must be implemented by the object, that maintains set of attributes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AttributesContract extends Serializable {

	/**
	 * Returns true if single attribute differs between first and second instance.
	 */
	static boolean anyAttributeDifferBetween(AttributesContract first, AttributesContract second) {
		final Collection<AttributeValue> thisValues = first.getAttributeValues();
		final Collection<AttributeValue> otherValues = second.getAttributeValues();

		if (thisValues.size() != otherValues.size()) {
			return true;
		} else {
			return thisValues
				.stream()
				.anyMatch(it -> {
					final Serializable thisValue = it.getValue();
					final AttributeKey key = it.getKey();
					final AttributeValue other = second.getAttributeValue(key.getAttributeName(), key.getLocale())
						.orElse(null);
					if (other == null) {
						return true;
					} else {
						final Serializable otherValue = other.getValue();
						return it.isDropped() != other.isDropped() || QueryUtils.valueDiffers(thisValue, otherValue);
					}
				});
		}
	}

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 * This method variant differs from {@link #getAttribute(String, Class)} in the sense that it relies on Java local
	 * variable type inference. It's shorted, but it can't be used on every place. You may safely use in this context:
	 *
	 * ``` java
	 * // when the name attribute is of type String
	 * final String name = entity.getAttribute("name");
	 * ```
	 *
	 * @throws ClassCastException when attribute is of different type than expected
	 */
	@Nullable
	<T extends Serializable> T getAttribute(@Nonnull String attributeName);

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference. This approach needs to be
	 * used in {@link Optional} or {@link Stream} contexts.
	 *
	 * @throws ClassCastException when attribute is of different type than expected
	 */
	@Nullable
	default <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Class<T> expectedClass) {
		return getAttribute(attributeName);
	}

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * This method variant differs from {@link #getAttribute(String, Class)} in the sense that it relies on Java local
	 * variable type inference. It's shorted, but it can't be used on every place.
	 *
	 * @throws ClassCastException when attribute is of different type than expected or is not an array
	 */
	@Nullable
	<T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName);

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference.
	 *
	 * @throws ClassCastException when attribute is of different type than expected or is not an array
	 */
	@Nullable
	default <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Class<T> expectedType) {
		return getAttributeArray(attributeName);
	}

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 *
	 * Method returns wrapper dto for the attribute that contains information about the attribute version and state.
	 */
	@Nonnull
	Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName);

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 *
	 * @throws ClassCastException when attribute is of different type than expected
	 */
	@Nullable
	<T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale);

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference.
	 *
	 * @throws ClassCastException when attribute is of different type than expected
	 */
	default <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nonnull Class<T> expectedType) {
		return getAttribute(attributeName, locale);
	}

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 *
	 * @throws ClassCastException when attribute is of different type than expected or is not an array
	 */
	@Nullable
	<T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale);

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference.
	 *
	 * @throws ClassCastException when attribute is of different type than expected or is not an array
	 */
	default <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale, @Nonnull Class<T> expectedType) {
		return getAttributeArray(attributeName, locale);
	}

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 *
	 * Method returns wrapper dto for the attribute that contains information about the attribute version and state.
	 */
	@Nonnull
	Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale);

	/**
	 * Returns definition for the attribute of specified name.
	 */
	@Nonnull
	Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName);

	/**
	 * Returns set of all attribute names registered in this attribute set. The result set is not limited to the set
	 * of currently fetched attributes.
	 */
	@Nonnull
	Set<String> getAttributeNames();

	/**
	 * Returns set of all keys (combination of attribute name and locale) registered in this attribute set.
	 */
	@Nonnull
	Set<AttributeKey> getAttributeKeys();

	/**
	 * Returns collection of all values present in this object.
	 */
	@Nonnull
	Collection<AttributeValue> getAttributeValues();


	/**
	 * Returns collection of all values of `attributeName` present in this object. This method has usually sense
	 * only when there is attribute in multiple localizations.
	 */
	@Nonnull
	Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName);

	/**
	 * Method returns set of all locales used in the localized attributes. The result set is not limited to the set
	 * of currently fetched attributes.
	 */
	@Nonnull
	Set<Locale> getAttributeLocales();

	/**
	 * Inner implementation used in {@link Attributes} to represent a proper key in hash map.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	@Immutable
	@ThreadSafe
	@Data
	class AttributeKey implements Serializable, Comparable<AttributeKey> {
		@Serial private static final long serialVersionUID = -8516513307116598241L;

		/**
		 * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
		 * single entity instance.
		 */
		private final String attributeName;
		/**
		 * Contains locale in case the attribute is locale specific (i.e. {@link AttributeSchemaContract#isLocalized()}
		 */
		private final Locale locale;

		/**
		 * Construction for the locale agnostics attribute.
		 */
		public AttributeKey(@Nonnull String attributeName) {
			Assert.notNull(attributeName, "Attribute name cannot be null!");
			this.attributeName = attributeName;
			this.locale = null;
		}

		/**
		 * Constructor for the locale specific attribute.
		 */
		public AttributeKey(@Nonnull String attributeName, @Nullable Locale locale) {
			Assert.notNull(attributeName, "Attribute name cannot be null!");
			this.attributeName = attributeName;
			this.locale = locale;
		}

		/**
		 * Returns true if attribute is localized.
		 */
		public boolean isLocalized() {
			return locale != null;
		}

		@Override
		public int compareTo(AttributeKey o) {
			return ComparatorUtils.compareLocale(locale, o.locale, () -> attributeName.compareTo(o.attributeName));
		}

		/**
		 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
		 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
		 */
		public int estimateSize() {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
				// attribute name
				MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.computeStringSize(attributeName) +
				// locale
				MemoryMeasuringConstants.REFERENCE_SIZE;
		}

		@Override
		public String toString() {
			return attributeName + (locale == null ? "" : ":" + locale);
		}
	}

	/**
	 * Represents single attribute og the {@link Entity}. AttributeValue serves as wrapper for the attribute value
	 * that also carries current version of the value for the sake of optimistic locking and the locale (in case attribute
	 * is localized).
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	@Immutable
	@ThreadSafe
	@Data
	@EqualsAndHashCode(of = {"version", "key"})
	class AttributeValue implements Versioned, Droppable, Serializable, Comparable<AttributeValue>, ContentComparator<AttributeValue> {
		@Serial private static final long serialVersionUID = -5387437940533059959L;

		/**
		 * Contains version of this object and gets increased with any attribute update. Allows to execute
		 * optimistic locking i.e. avoiding parallel modifications.
		 */
		private final int version;
		/**
		 * Uniquely identifies the attribute value among other attributes in the same entity instance.
		 */
		@Nonnull private final AttributeKey key;
		/**
		 * Contains the current value of the attribute.
		 */
		@Nullable private final Serializable value;
		/**
		 * Contains TRUE if attribute was dropped - i.e. removed. Such attributes are not removed (unless tidying process
		 * does it), but are lying among other attributes with tombstone flag. Dropped attributes can be overwritten by
		 * a new value continuing with the versioning where it was stopped for the last time.
		 */
		private final boolean dropped;

		public AttributeValue(@Nonnull AttributeValue baseAttribute, @Nonnull Serializable replacedValue) {
			this.version = baseAttribute.version;
			this.dropped = baseAttribute.dropped;
			this.key = baseAttribute.key;
			this.value = replacedValue;
		}

		private AttributeValue(@Nonnull AttributeKey attributeKey) {
			this.version = 1;
			this.key = attributeKey;
			this.value = null;
			this.dropped = false;
		}

		public AttributeValue(@Nonnull AttributeKey attributeKey, @Nonnull Serializable value) {
			this.version = 1;
			this.key = attributeKey;
			this.value = value;
			this.dropped = false;
		}

		public AttributeValue(int version, @Nonnull AttributeKey attributeKey, @Nonnull Serializable value) {
			this.version = version;
			this.key = attributeKey;
			this.value = value;
			this.dropped = false;
		}

		public AttributeValue(int version, @Nonnull AttributeKey key, @Nonnull Serializable value, boolean dropped) {
			this.version = version;
			this.key = key;
			this.value = value;
			this.dropped = dropped;
		}

		/**
		 * Method can be used for sorted arrays binary searches but doesn't represent any valid attribute value.
		 */
		public static AttributeValue createEmptyComparableAttributeValue(@Nonnull AttributeKey attributeKey) {
			return new AttributeValue(attributeKey);
		}

		@Override
		public int compareTo(AttributeValue o) {
			return key.compareTo(o.key);
		}

		/**
		 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
		 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
		 */
		public int estimateSize() {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
				// version
				MemoryMeasuringConstants.INT_SIZE +
				// dropped
				MemoryMeasuringConstants.BYTE_SIZE +
				// key
				key.estimateSize() +
				// value size estimate
				MemoryMeasuringConstants.REFERENCE_SIZE + (value == null ? 0 : EvitaDataTypes.estimateSize(value));
		}

		/**
		 * Returns true if this attribute differs in key factors from the passed attribute.
		 */
		@Override
		public boolean differsFrom(@Nullable AttributeValue otherAttributeValue) {
			if (otherAttributeValue == null) return true;
			if (!Objects.equals(key, otherAttributeValue.key)) return true;
			if (!Objects.equals(value, otherAttributeValue.value)) return true;
			return dropped != otherAttributeValue.dropped;
		}

		@Override
		public String toString() {
			return (dropped ? "❌ " : "") +
				"\uD83D\uDD11 " + key.getAttributeName() + " " +
				(key.getLocale() == null ? "" : "(" + key.getLocale() + ")") +
				": " +
				(
					value instanceof Object[] ?
						("[" + Arrays.stream((Object[]) value).map(Object::toString).collect(Collectors.joining(",")) + "]") :
						value
				);
		}
	}
}
