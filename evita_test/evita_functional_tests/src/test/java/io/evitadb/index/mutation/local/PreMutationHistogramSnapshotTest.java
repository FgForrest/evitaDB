/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.core.expression.trigger.DefaultHistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Scope;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.HISTOGRAM;

/**
 * Tests for {@link PreMutationHistogramSnapshot} verifying correct capture of pre-mutation histogram
 * attribute values for various trigger configurations, attribute types, and localization modes.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PreMutationHistogramSnapshot")
@Tag(INDEXING)
@Tag(SCHEMA)
@Tag(HISTOGRAM)
class PreMutationHistogramSnapshotTest implements EvitaTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "parameter";
	private static final String WEIGHT_ATTR = "weight";
	private static final String PRICE_ATTR = "price";
	private static final String HISTOGRAM_INDEX = "weightHistogram";

	@Nested
	@DisplayName("Non-localized attributes")
	class NonLocalizedAttributes {

		@Test
		@DisplayName("Should capture old values for non-localized reference attribute trigger")
		void shouldCaptureNonLocalizedOldValues() {
			final HistogramExpressionTrigger trigger = createUnconditionalTrigger(
				HISTOGRAM_INDEX,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, WEIGHT_ATTR,
					Integer.class, false, false, null, null, 0
				)
			);
			final ExistingAttributeValueSupplier supplier = createSupplier(
				Map.of(new AttributeKey(WEIGHT_ATTR), 42)
			);

			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(List.of(trigger), WEIGHT_ATTR, supplier);

			assertTrue(snapshot.isValueSourceChanged(trigger));
			final Map<Locale, Serializable[]> oldValues = snapshot.getOldValuesByLocale(trigger);
			assertEquals(1, oldValues.size());
			assertTrue(oldValues.containsKey(null));
			assertArrayEquals(new Serializable[]{42}, oldValues.get(null));
		}

		@Test
		@DisplayName("Should apply default value when attribute is null")
		void shouldApplyDefaultValueWhenAttributeIsNull() {
			final HistogramValueDescriptor descriptor = new HistogramValueDescriptor(
				HistogramValueSource.REFERENCE_ATTRIBUTE, null, WEIGHT_ATTR,
				Integer.class, false, false, 0, null, 0
			);
			final HistogramExpressionTrigger trigger = createUnconditionalTrigger(HISTOGRAM_INDEX, descriptor);
			final ExistingAttributeValueSupplier supplier = createSupplier(Map.of());

			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(List.of(trigger), WEIGHT_ATTR, supplier);

			assertTrue(snapshot.isValueSourceChanged(trigger));
			assertArrayEquals(new Serializable[]{0}, snapshot.getOldValuesByLocale(trigger).get(null));
		}

		@Test
		@DisplayName("Should return empty array when attribute is null and no default")
		void shouldReturnEmptyArrayWhenAttributeIsNullAndNoDefault() {
			final HistogramValueDescriptor descriptor = new HistogramValueDescriptor(
				HistogramValueSource.REFERENCE_ATTRIBUTE, null, WEIGHT_ATTR,
				Integer.class, false, false, null, null, 0
			);
			final HistogramExpressionTrigger trigger = createUnconditionalTrigger(HISTOGRAM_INDEX, descriptor);
			final ExistingAttributeValueSupplier supplier = createSupplier(Map.of());

			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(List.of(trigger), WEIGHT_ATTR, supplier);

			assertTrue(snapshot.isValueSourceChanged(trigger));
			assertArrayEquals(new Serializable[0], snapshot.getOldValuesByLocale(trigger).get(null));
		}
	}

	@Nested
	@DisplayName("Localized attributes")
	class LocalizedAttributes {

		@Test
		@DisplayName("Should capture old values per locale for localized attribute trigger")
		void shouldCaptureLocalizedOldValuesPerLocale() {
			final HistogramExpressionTrigger trigger = createUnconditionalTrigger(
				HISTOGRAM_INDEX,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, WEIGHT_ATTR,
					Integer.class, false, true, null, null, 0
				)
			);
			final Map<AttributeKey, Serializable> attributes = new HashMap<>(2);
			attributes.put(new AttributeKey(WEIGHT_ATTR, Locale.ENGLISH), 10);
			attributes.put(new AttributeKey(WEIGHT_ATTR, Locale.GERMAN), 20);
			final ExistingAttributeValueSupplier supplier = createLocalizedSupplier(
				attributes, Set.of(Locale.ENGLISH, Locale.GERMAN)
			);

			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(List.of(trigger), WEIGHT_ATTR, supplier);

			assertTrue(snapshot.isValueSourceChanged(trigger));
			final Map<Locale, Serializable[]> oldValues = snapshot.getOldValuesByLocale(trigger);
			assertEquals(2, oldValues.size());
			assertArrayEquals(new Serializable[]{10}, oldValues.get(Locale.ENGLISH));
			assertArrayEquals(new Serializable[]{20}, oldValues.get(Locale.GERMAN));
		}
	}

	@Nested
	@DisplayName("Array-typed attributes")
	class ArrayTypedAttributes {

		@Test
		@DisplayName("Should resolve array-typed attribute into multiple Number elements")
		void shouldResolveArrayTypedAttributes() {
			final HistogramExpressionTrigger trigger = createUnconditionalTrigger(
				HISTOGRAM_INDEX,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, WEIGHT_ATTR,
					Integer.class, true, false, null, null, 0
				)
			);
			final ExistingAttributeValueSupplier supplier = createSupplier(
				Map.of(new AttributeKey(WEIGHT_ATTR), new Integer[]{5, 15, 25})
			);

			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(List.of(trigger), WEIGHT_ATTR, supplier);

			assertTrue(snapshot.isValueSourceChanged(trigger));
			assertArrayEquals(new Serializable[]{5, 15, 25}, snapshot.getOldValuesByLocale(trigger).get(null));
		}
	}

	@Nested
	@DisplayName("Trigger filtering")
	class TriggerFiltering {

		@Test
		@DisplayName("Should skip triggers whose source attribute name differs from changed attribute")
		void shouldSkipTriggersWhoseSourceIsNotChangedAttribute() {
			final HistogramExpressionTrigger trigger = createUnconditionalTrigger(
				HISTOGRAM_INDEX,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, PRICE_ATTR,
					Integer.class, false, false, null, null, 0
				)
			);
			final ExistingAttributeValueSupplier supplier = createSupplier(
				Map.of(new AttributeKey(PRICE_ATTR), 100)
			);

			// changed attribute is WEIGHT_ATTR but trigger source is PRICE_ATTR
			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(List.of(trigger), WEIGHT_ATTR, supplier);

			assertFalse(snapshot.isValueSourceChanged(trigger));
		}

		@Test
		@DisplayName("Should skip triggers with REFERENCED_ENTITY_ATTRIBUTE source")
		void shouldSkipTriggersWithNonReferenceAttributeSource() {
			final HistogramExpressionTrigger trigger = createUnconditionalTrigger(
				HISTOGRAM_INDEX,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCED_ENTITY_ATTRIBUTE, ENTITY_TYPE, WEIGHT_ATTR,
					Integer.class, false, false, null, null, 0
				)
			);
			final ExistingAttributeValueSupplier supplier = createSupplier(
				Map.of(new AttributeKey(WEIGHT_ATTR), 42)
			);

			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(List.of(trigger), WEIGHT_ATTR, supplier);

			assertFalse(snapshot.isValueSourceChanged(trigger));
		}

		@Test
		@DisplayName("Should produce empty snapshot for empty trigger collection")
		void shouldHandleEmptyTriggerCollection() {
			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(
					List.of(), WEIGHT_ATTR, ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER
				);

			final HistogramExpressionTrigger anyTrigger = createUnconditionalTrigger(
				HISTOGRAM_INDEX,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, WEIGHT_ATTR,
					Integer.class, false, false, null, null, 0
				)
			);
			assertFalse(snapshot.isValueSourceChanged(anyTrigger));
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandling {

		@Test
		@DisplayName("Should throw when accessing old values for uncaptured trigger")
		void shouldThrowWhenAccessingUncapturedTrigger() {
			final HistogramExpressionTrigger uncapturedTrigger = createUnconditionalTrigger(
				HISTOGRAM_INDEX,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, PRICE_ATTR,
					Integer.class, false, false, null, null, 0
				)
			);
			final PreMutationHistogramSnapshot snapshot =
				new PreMutationHistogramSnapshot(
					List.of(), WEIGHT_ATTR, ExistingAttributeValueSupplier.NO_EXISTING_VALUE_SUPPLIER
				);

			assertThrows(Exception.class, () -> snapshot.getOldValuesByLocale(uncapturedTrigger));
		}
	}

	/**
	 * Creates an unconditional histogram trigger with the given index name and value descriptor.
	 *
	 * @param histogramIndexName the histogram index name
	 * @param descriptor         the value resolution descriptor
	 * @return new unconditional histogram trigger
	 */
	@Nonnull
	private static HistogramExpressionTrigger createUnconditionalTrigger(
		@Nonnull String histogramIndexName,
		@Nonnull HistogramValueDescriptor descriptor
	) {
		return new DefaultHistogramExpressionTrigger(
			ENTITY_TYPE, REFERENCE_NAME, Scope.LIVE,
			histogramIndexName, descriptor
		);
	}

	/**
	 * Creates an {@link ExistingAttributeValueSupplier} backed by a flat attribute map (non-localized).
	 *
	 * @param attributes map from attribute key to value
	 * @return supplier returning values from the given map
	 */
	@Nonnull
	private static ExistingAttributeValueSupplier createSupplier(
		@Nonnull Map<AttributeKey, Serializable> attributes
	) {
		return new ExistingAttributeValueSupplier() {
			@Nonnull
			@Override
			public Set<Locale> getEntityExistingAttributeLocales() {
				return Collections.emptySet();
			}

			@Nonnull
			@Override
			public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
				final Serializable value = attributes.get(attributeKey);
				return value != null
					? Optional.of(new AttributeValue(attributeKey, value))
					: Optional.empty();
			}

			@Nonnull
			@Override
			public Stream<AttributeValue> getAttributeValues() {
				return attributes.entrySet().stream()
					.map(e -> new AttributeValue(e.getKey(), e.getValue()));
			}

			@Nonnull
			@Override
			public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
				return Stream.empty();
			}
		};
	}

	/**
	 * Creates an {@link ExistingAttributeValueSupplier} backed by a localized attribute map.
	 *
	 * @param attributes map from locale-qualified attribute key to value
	 * @param locales    set of available locales
	 * @return supplier returning values from the given map with locale awareness
	 */
	@Nonnull
	private static ExistingAttributeValueSupplier createLocalizedSupplier(
		@Nonnull Map<AttributeKey, Serializable> attributes,
		@Nonnull Set<Locale> locales
	) {
		return new ExistingAttributeValueSupplier() {
			@Nonnull
			@Override
			public Set<Locale> getEntityExistingAttributeLocales() {
				return locales;
			}

			@Nonnull
			@Override
			public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
				final Serializable value = attributes.get(attributeKey);
				return value != null
					? Optional.of(new AttributeValue(attributeKey, value))
					: Optional.empty();
			}

			@Nonnull
			@Override
			public Stream<AttributeValue> getAttributeValues() {
				return attributes.entrySet().stream()
					.map(e -> new AttributeValue(e.getKey(), e.getValue()));
			}

			@Nonnull
			@Override
			public Stream<AttributeValue> getAttributeValues(@Nonnull Locale locale) {
				return attributes.entrySet().stream()
					.filter(e -> locale.equals(e.getKey().locale()))
					.map(e -> new AttributeValue(e.getKey(), e.getValue()));
			}
		};
	}

}
