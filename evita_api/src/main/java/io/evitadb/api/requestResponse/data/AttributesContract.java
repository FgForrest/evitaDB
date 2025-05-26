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

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ComparatorUtils;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
public interface AttributesContract<S extends AttributeSchemaContract> extends Serializable, AttributesAvailabilityChecker {

	/**
	 * Returns true if single attribute differs between first and second instance.
	 */
	static <S extends AttributeSchemaContract> boolean anyAttributeDifferBetween(@Nonnull AttributesContract<S> first, @Nonnull AttributesContract<S> second) {
		final Collection<AttributeValue> thisValues = first.attributesAvailable() ? first.getAttributeValues() : Collections.emptyList();
		final Collection<AttributeValue> otherValues = second.attributesAvailable() ? second.getAttributeValues() : Collections.emptyList();

		if (thisValues.size() != otherValues.size()) {
			return true;
		} else {
			return thisValues
				.stream()
				.anyMatch(it -> {
					final Serializable thisValue = it.value();
					final AttributeKey key = it.key();
					final AttributeValue other = key.locale() == null ?
						second.getAttributeValue(key.attributeName()).orElse(null) :
						second.getAttributeValue(key.attributeName(), key.localeOrThrowException()).orElse(null);
					if (other == null) {
						return true;
					} else {
						final Serializable otherValue = other.value();
						return it.dropped() != other.dropped() || QueryUtils.valueDiffers(thisValue, otherValue);
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
	 * @throws ClassCastException         when attribute is of different type than expected
	 * @throws ContextMissingException    when attribute is localized and entity is not related to any {@link Query} or
	 *                                    the query lacks locale identifier, or the attribute was not fetched at all
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 */
	@Nullable
	<T extends Serializable> T getAttribute(@Nonnull String attributeName)
		throws ContextMissingException, AttributeNotFoundException;

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference. This approach needs to be
	 * used in {@link Optional} or {@link Stream} contexts.
	 *
	 * @throws ClassCastException         when attribute is of different type than expected
	 * @throws ContextMissingException    when attribute is localized and entity is not related to any {@link Query} or
	 *                                    the query lacks locale identifier, or the attribute was not fetched at all
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 */
	@Nullable
	default <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Class<T> expectedClass)
		throws ContextMissingException, AttributeNotFoundException {
		return getAttribute(attributeName);
	}

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * This method variant differs from {@link #getAttribute(String, Class)} in the sense that it relies on Java local
	 * variable type inference. It's shorted, but it can't be used on every place.
	 *
	 * @throws ClassCastException         when attribute is of different type than expected or is not an array
	 * @throws ContextMissingException    when attribute is localized and entity is not related to any {@link Query} or
	 *                                    the query lacks locale identifier, or the attribute was not fetched at all
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 */
	@Nullable
	<T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName)
		throws ContextMissingException, AttributeNotFoundException;

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference.
	 *
	 * @throws ClassCastException         when attribute is of different type than expected or is not an array
	 * @throws ContextMissingException    when attribute is localized and entity is not related to any {@link Query} or
	 *                                    the query lacks locale identifier, or the attribute was not fetched at all
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 */
	@Nullable
	default <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Class<T> expectedType)
		throws ContextMissingException, AttributeNotFoundException {
		return getAttributeArray(attributeName);
	}

	/**
	 * Returns value associated with the key or null when the attribute is missing. This method doesn't throw any
	 * {@link ContextMissingException}, but instead it returns an empty value even for localized attributes.
	 *
	 * Method returns wrapper dto for the attribute that contains information about the attribute version and state.
	 *
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when attribute is localized and entity is not related to any {@link Query} or
	 *                                    the query lacks locale identifier, or the attribute was not fetched at all
	 */
	@Nonnull
	Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) throws AttributeNotFoundException;

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 *
	 * @throws ClassCastException         when attribute is of different type than expected
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when the query lacks locale identifier, or the attribute was not fetched at all
	 */
	@Nullable
	<T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale)
		throws AttributeNotFoundException;

	/**
	 * Returns value associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference.
	 *
	 * @throws ClassCastException         when attribute is of different type than expected
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when the query lacks locale identifier, or the attribute was not fetched at all
	 */
	@Nullable
	default <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nonnull Class<T> expectedType)
		throws AttributeNotFoundException {
		return getAttribute(attributeName, locale);
	}

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 *
	 * @throws ClassCastException         when attribute is of different type than expected or is not an array
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when the query lacks locale identifier, or the attribute was not fetched at all
	 */
	@Nullable
	<T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale)
		throws AttributeNotFoundException;

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 * This method variant differs from {@link #getAttribute(String)} in the sense that it specifies expected returned
	 * type as an input argument and doesn't rely on Java local variable type inference.
	 *
	 * @throws ClassCastException         when attribute is of different type than expected or is not an array
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when the query lacks locale identifier, or the attribute was not fetched at all
	 */
	@Nullable
	default <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale, @Nonnull Class<T> expectedType)
		throws AttributeNotFoundException {
		return getAttributeArray(attributeName, locale);
	}

	/**
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 *
	 * Method returns wrapper dto for the attribute that contains information about the attribute version and state.
	 *
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when the query lacks locale identifier, or the attribute was not fetched at all
	 */
	@Nonnull
	Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale)
		throws AttributeNotFoundException;

	/**
	 * Returns definition for the attribute of specified name.
	 */
	@Nonnull
	Optional<S> getAttributeSchema(@Nonnull String attributeName);

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
	 * Returns array of values associated with the key or null when the attribute is missing.
	 * When localized attribute is not found it is looked up in generic (non-localized) attributes. This makes this
	 * method the safest way how to lookup for attribute if caller doesn't know whether it is localized or not.
	 *
	 * Method returns wrapper dto for the attribute that contains information about the attribute version and state.
	 *
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when attribute is localized and entity is not related to any {@link Query} or
	 *                                    the query lacks locale identifier, or the attribute was not fetched at all
	 */
	@Nonnull
	Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey);

	/**
	 * Returns collection of all values present in this object.
	 */
	@Nonnull
	Collection<AttributeValue> getAttributeValues();


	/**
	 * Returns collection of all values of `attributeName` present in this object. This method has usually sense
	 * only when there is attribute in multiple localizations.
	 *
	 * @throws AttributeNotFoundException when attribute is not defined in the schema
	 * @throws ContextMissingException    when attribute is localized and entity is not related to any {@link Query} or
	 *                                    the query lacks locale identifier, or the attribute was not fetched at all
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
	 * @param attributeName unique name of the attribute. Case-sensitive. Distinguishes one associated data item from
	 *                      another within single entity instance.
	 * @param locale        contains locale in case the attribute is locale specific (i.e. {@link AttributeSchemaContract#isLocalized()}
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	record AttributeKey(
		@Nonnull String attributeName,
		@Nullable Locale locale

	) implements Serializable, Comparable<AttributeKey> {
		@Serial private static final long serialVersionUID = -8516513307116598241L;

		/**
		 * Constructor for the locale specific attribute.
		 */
		public AttributeKey {
			Assert.notNull(attributeName, "Attribute name cannot be null!");
		}

		/**
		 * Construction for the locale agnostics attribute.
		 */
		public AttributeKey(@Nonnull String attributeName) {
			this(attributeName, null);
		}

		/**
		 * Returns true if attribute is localized.
		 */
		public boolean localized() {
			return this.locale != null;
		}

		/**
		 * Returns the locale associated with this attribute key or throws an exception if the locale is not present.
		 *
		 * @return the locale associated with the attribute key
		 * @throws IllegalArgumentException if the locale is not present
		 */
		@Nonnull
		public Locale localeOrThrowException() {
			Assert.isTrue(
				this.locale != null,
				"Attribute key " + this.attributeName + " is not accompanied by locale identifier!"
			);
			return this.locale;
		}

		@Override
		public int compareTo(AttributeKey o) {
			return ComparatorUtils.compareLocale(this.locale, o.locale, () -> this.attributeName.compareTo(o.attributeName));
		}

		/**
		 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
		 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
		 */
		public int estimateSize() {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
				// attribute name
				MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.computeStringSize(this.attributeName) +
				// locale
				MemoryMeasuringConstants.REFERENCE_SIZE;
		}

		@Nonnull
		@Override
		public String toString() {
			return this.attributeName + (this.locale == null ? "" : ":" + this.locale);
		}
	}

	/**
	 * Represents single attribute og the {@link Entity}. AttributeValue serves as wrapper for the attribute value
	 * that also carries current version of the value for the sake of optimistic locking and the locale (in case attribute
	 * is localized).
	 *
	 * @param version contains version of this object and gets increased with any attribute update. Allows to execute
	 *                optimistic locking i.e. avoiding parallel modifications.
	 * @param key     uniquely identifies the attribute value among other attributes in the same entity instance.
	 * @param value   contains the current value of the attribute
	 * @param dropped contains TRUE if attribute was dropped - i.e. removed. Such attributes are not removed (unless
	 *                tidying process does it), but are lying among other attributes with tombstone flag. Dropped
	 *                attributes can be overwritten by a new value continuing with the versioning where it was stopped
	 *                for the last time.
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	record AttributeValue(
		int version,
		@Nonnull AttributeKey key,
		@Nullable Serializable value,
		boolean dropped
	) implements Versioned, Droppable, Serializable, Comparable<AttributeValue>, ContentComparator<AttributeValue> {
		@Serial private static final long serialVersionUID = -5387437940533059959L;

		/**
		 * Method can be used for sorted arrays binary searches but doesn't represent any valid attribute value.
		 */
		public static AttributeValue createEmptyComparableAttributeValue(@Nonnull AttributeKey attributeKey) {
			return new AttributeValue(attributeKey);
		}

		public AttributeValue(@Nonnull AttributeValue baseAttribute, @Nonnull Serializable replacedValue) {
			this(baseAttribute.version, baseAttribute.key, replacedValue, baseAttribute.dropped);
		}

		private AttributeValue(@Nonnull AttributeKey attributeKey) {
			this(1, attributeKey, null, false);
		}

		public AttributeValue(@Nonnull AttributeKey attributeKey, @Nonnull Serializable value) {
			this(1, attributeKey, value, false);
		}

		public AttributeValue(int version, @Nonnull AttributeKey attributeKey, @Nonnull Serializable value) {
			this(version, attributeKey, value, false);
		}

		/**
		 * Returns the value of the attribute if it is not null. Throws an exception if the value is null,
		 * indicating that the attribute value is unexpectedly missing.
		 *
		 * @return the non-null value of the attribute
		 * @throws GenericEvitaInternalError if the attribute value is null
		 */
		@Nonnull
		public Serializable valueOrThrowException() {
			final Serializable theValue = this.value;
			Assert.isPremiseValid(
				theValue != null,
				"Attribute value " + this.key.attributeName() + " is unexpectedly null!"
			);
			return theValue;
		}

		@Override
		public int compareTo(AttributeValue o) {
			return this.key.compareTo(o.key);
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
				this.key.estimateSize() +
				// value size estimate
				MemoryMeasuringConstants.REFERENCE_SIZE + (this.value == null ? 0 : EvitaDataTypes.estimateSize(this.value));
		}

		/**
		 * Returns true if this attribute differs in key factors from the passed attribute.
		 */
		@Override
		public boolean differsFrom(@Nullable AttributeValue otherAttributeValue) {
			if (otherAttributeValue == null) return true;
			if (!Objects.equals(this.key, otherAttributeValue.key)) return true;
			if (!Objects.equals(this.value, otherAttributeValue.value)) return true;
			return this.dropped != otherAttributeValue.dropped;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			AttributeValue that = (AttributeValue) o;

			if (this.version != that.version) return false;
			return this.key.equals(that.key);
		}

		public int hashCode() {
			int result = this.version;
			result = 31 * result + this.key.hashCode();
			return result;
		}

		@Nonnull
		@Override
		public String toString() {
			return (this.dropped ? "❌ " : "") +
				"\uD83D\uDD11 " + this.key.attributeName() + " " +
				(this.key.locale() == null ? "" : "(" + this.key.locale() + ")") +
				": " +
				(
					this.value instanceof Object[] ?
						("[" + Arrays.stream((Object[]) this.value).map(Object::toString).collect(Collectors.joining(",")) + "]") :
						this.value
				);
		}
	}
}
