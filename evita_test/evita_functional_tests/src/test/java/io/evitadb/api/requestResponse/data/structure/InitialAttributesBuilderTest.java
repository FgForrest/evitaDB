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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.dataType.IntegerNumberRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This abstract test verifies shared contract of
 * {@link InitialAttributesBuilder} implementations.
 * Concrete subclasses must provide an appropriate builder
 * via {@link #builder()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("InitialAttributesBuilder")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(ATTRIBUTE)
abstract class InitialAttributesBuilderTest extends AbstractBuilderTest {
	/**
	 * Provides fresh builder instance for each test.
	 */
	protected abstract InitialAttributesBuilder<?, ?> builder();

	/**
	 * Builds the Attributes instance from given builder
	 * (implemented by subclass to invoke correct build()).
	 */
	protected abstract Attributes<?> build(
		InitialAttributesBuilder<?, ?> builder
	);

	@Nested
	@DisplayName("Setting and getting")
	class SettingAndGettingTest {

		@Test
		@DisplayName("Should store new value and retrieve it")
		void shouldStoreNewValueAndRetrieveIt() {
			final InitialAttributesBuilder<?, ?> b = builder();
			final Attributes<?> attributes =
				build(b.setAttribute("abc", "DEF"));
			assertEquals("DEF", attributes.getAttribute("abc"));
		}

		@Test
		@DisplayName("Should override one operation with another")
		void shouldOverrideOneOperationWithAnother() {
			InitialAttributesBuilder<?, ?> b = builder();
			b = b.setAttribute("abc", "DEF");
			b = b.setAttribute("abc", "RTE");
			final Attributes<?> attributes = build(b);
			assertEquals("RTE", attributes.getAttribute("abc"));
		}

		@Test
		@DisplayName(
			"Should fail with ClassCastException"
				+ " if mapping to different type"
		)
		void shouldFailWithClassCastIfMappingToDifferentType() {
			final Attributes<?> attributes =
				build(builder().setAttribute("abc", "DEF"));
			assertThrows(ClassCastException.class, () -> {
				final Integer someInt =
					attributes.getAttribute("abc");
				fail("Should not be executed at all!");
			});
		}

		@Test
		@DisplayName(
			"Should store new value array and retrieve it"
		)
		void shouldStoreNewValueArrayAndRetrieveIt() {
			final Attributes<?> attributes = build(
				builder().setAttribute(
					"abc", new String[]{"DEF", "XYZ"}
				)
			);
			assertArrayEquals(
				new String[]{"DEF", "XYZ"},
				attributes.getAttributeArray("abc")
			);
		}

		@Test
		@DisplayName(
			"Should accept mutation on new attribute container"
		)
		void shouldFailToAddMutationToNewAttributeContainer() {
			final InitialAttributesBuilder<?, ?> builder =
				builder();
			builder.mutateAttribute(
				new UpsertAttributeMutation("abc", 1)
			);
			assertEquals(
				1,
				builder.getAttribute("abc", Integer.class)
					.intValue()
			);
		}
	}

	@Nested
	@DisplayName("Removing")
	class RemovingTest {

		@Test
		@DisplayName("Should remove value")
		void shouldRemoveValue() {
			final Attributes<?> attributes = build(
				builder()
					.setAttribute("abc", "DEF")
					.removeAttribute("abc")
			);
			assertFalse(
				attributes.attributeValues
					.containsKey(new AttributeKey("abc"))
			);
		}

		@Test
		@DisplayName("Should remove previously set value")
		void shouldRemovePreviouslySetValue() {
			final Attributes<?> attributes = build(
				builder()
					.setAttribute("abc", "DEF")
					.setAttribute("abc", "DEF")
					.removeAttribute("abc")
			);
			assertFalse(
				attributes.attributeValues
					.containsKey(new AttributeKey("abc"))
			);
		}
	}

	@Nested
	@DisplayName("Names and locales")
	class NamesAndLocalesTest {

		@Test
		@DisplayName("Should return attribute names")
		void shouldReturnAttributeNames() {
			final Attributes<?> attributes = build(
				builder()
					.setAttribute("abc", 1)
					.setAttribute(
						"def",
						IntegerNumberRange.between(4, 8)
					)
			);

			final Set<String> names =
				attributes.getAttributeNames();
			assertEquals(2, names.size());
			assertTrue(names.contains("abc"));
			assertTrue(names.contains("def"));
		}

		@Test
		@DisplayName("Should support localized attributes")
		void shouldSupportLocalizedAttributes() {
			final InitialAttributesBuilder<?, ?> b = builder()
				.setAttribute("abc", 1)
				.setAttribute(
					"def",
					IntegerNumberRange.between(4, 8)
				)
				.setAttribute(
					"dd", new BigDecimal("1.123")
				)
				.setAttribute(
					"greetings", Locale.ENGLISH, "Hello"
				)
				.setAttribute(
					"greetings", Locale.GERMAN, "Tschüss"
				);

			assertEquals(Integer.valueOf(1), b.getAttribute("abc"));
			assertEquals(
				IntegerNumberRange.between(4, 8),
				b.getAttribute("def")
			);
			assertEquals(
				new BigDecimal("1.123"),
				b.getAttribute("dd")
			);
			assertEquals(
				"Hello",
				b.getAttribute("greetings", Locale.ENGLISH)
			);
			assertEquals(
				"Tschüss",
				b.getAttribute("greetings", Locale.GERMAN)
			);
			assertNull(
				b.getAttribute("greetings", Locale.FRENCH)
			);

			final Attributes<?> attributes = build(b);
			final Set<String> names =
				attributes.getAttributeNames();
			assertEquals(4, names.size());
			assertTrue(names.contains("abc"));
			assertTrue(names.contains("def"));
			assertTrue(names.contains("dd"));
			assertTrue(names.contains("greetings"));

			assertEquals(
				Integer.valueOf(1),
				attributes.getAttribute("abc")
			);
			assertEquals(
				IntegerNumberRange.between(4, 8),
				attributes.getAttribute("def")
			);
			assertEquals(
				new BigDecimal("1.123"),
				attributes.getAttribute("dd")
			);
			assertEquals(
				"Hello",
				attributes.getAttribute(
					"greetings", Locale.ENGLISH
				)
			);
			assertEquals(
				"Tschüss",
				attributes.getAttribute(
					"greetings", Locale.GERMAN
				)
			);
			assertNull(
				attributes.getAttribute(
					"greetings", Locale.FRENCH
				)
			);
		}

		@Test
		@DisplayName(
			"Should report error on ambiguous"
				+ " attribute definition"
		)
		void shouldReportErrorOnAmbiguousAttributeDefinition() {
			assertThrows(
				IllegalArgumentException.class,
				() -> build(
					builder()
						.setAttribute(
							"greetings",
							Locale.ENGLISH, "Hello"
						)
						.setAttribute(
							"greetings",
							Locale.GERMAN, 1
						)
				)
			);
		}
	}

	@Nested
	@DisplayName("Accessor methods and change set")
	class AccessorMethodsAndChangeSetTest {

		@Test
		@DisplayName(
			"Should return attribute value as Optional"
		)
		void shouldReturnAttributeValueOptional() {
			final InitialAttributesBuilder<?, ?> b =
				builder().setAttribute("name", "test");

			final Optional<AttributeValue> present =
				b.getAttributeValue("name");
			assertTrue(present.isPresent());
			assertEquals("test", present.get().value());

			final Optional<AttributeValue> absent =
				b.getAttributeValue("nonexistent");
			assertTrue(absent.isEmpty());
		}

		@Test
		@DisplayName("Should return attribute locales")
		void shouldReturnAttributeLocales() {
			final InitialAttributesBuilder<?, ?> b = builder()
				.setAttribute(
					"greetings", Locale.ENGLISH, "Hello"
				)
				.setAttribute(
					"greetings", Locale.GERMAN, "Hallo"
				)
				.setAttribute("global", 42);

			final Set<Locale> locales =
				b.getAttributeLocales();
			assertEquals(2, locales.size());
			assertTrue(locales.contains(Locale.ENGLISH));
			assertTrue(locales.contains(Locale.GERMAN));
		}

		@Test
		@DisplayName(
			"Should report attributes as available"
		)
		void shouldReportAttributesAvailable() {
			final InitialAttributesBuilder<?, ?> b =
				builder();
			assertTrue(b.attributesAvailable());
		}

		@Test
		@DisplayName(
			"Should remove localized attribute"
		)
		void shouldRemoveLocalizedAttribute() {
			final InitialAttributesBuilder<?, ?> b = builder()
				.setAttribute(
					"greetings", Locale.ENGLISH, "Hello"
				)
				.removeAttribute("greetings", Locale.ENGLISH);

			final Optional<AttributeValue> removed =
				b.getAttributeValue(
					"greetings", Locale.ENGLISH
				);
			assertTrue(removed.isEmpty());
		}

		@Test
		@DisplayName(
			"Should build change set with mutations"
		)
		void shouldBuildChangeSet() {
			final InitialAttributesBuilder<?, ?> b = builder()
				.setAttribute("abc", 1)
				.setAttribute("def", "value");

			final List<? extends AttributeMutation> mutations =
				b.buildChangeSet().toList();
			assertEquals(2, mutations.size());
			assertTrue(
				mutations.stream().allMatch(
					UpsertAttributeMutation.class::isInstance
				)
			);
		}

		@Test
		@DisplayName(
			"Should build empty change set"
				+ " when no attributes set"
		)
		void shouldBuildEmptyChangeSet() {
			final InitialAttributesBuilder<?, ?> b =
				builder();

			final List<? extends AttributeMutation> mutations =
				b.buildChangeSet().toList();
			assertTrue(mutations.isEmpty());
		}
	}


	/**
	 * The builder must hand back the value that will actually be **stored**, not the value it was handed. Both
	 * conversions this covers are performed by {@link UpsertAttributeMutation} on the way to the engine, so before
	 * these tests a value read off the builder differed from the one the catalog ended up holding — silently, and
	 * only until `upsertVia(...)` was called.
	 */
	@Nested
	@DisplayName("Eager normalization to the stored form")
	class EagerNormalizationTest {
		/**
		 * A moment carrying six sub-millisecond digits, so an assertion on the truncated form cannot be satisfied
		 * by simply echoing the input back.
		 */
		private static final OffsetDateTime NANO_PRECISE_MOMENT =
			OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_456_789, ZoneOffset.UTC);
		private static final OffsetDateTime TRUNCATED_MOMENT =
			OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000, ZoneOffset.UTC);

		@Test
		@DisplayName("an OffsetDateTime is truncated to whole milliseconds when it is set")
		void shouldTruncateAnOffsetDateTimeWhenItIsSet() {
			final InitialAttributesBuilder<?, ?> b =
				builder().setAttribute("moment", NANO_PRECISE_MOMENT);

			assertEquals(TRUNCATED_MOMENT, b.getAttribute("moment"));
			assertNotEquals(NANO_PRECISE_MOMENT, b.getAttribute("moment"));
			assertEquals(TRUNCATED_MOMENT, build(b).getAttribute("moment"));
		}

		@Test
		@DisplayName("every element of an OffsetDateTime array is truncated when it is set")
		void shouldTruncateAnOffsetDateTimeArrayWhenItIsSet() {
			final InitialAttributesBuilder<?, ?> b = builder().setAttribute(
				"moments",
				new OffsetDateTime[]{NANO_PRECISE_MOMENT, NANO_PRECISE_MOMENT.plusSeconds(1)}
			);

			assertArrayEquals(
				new OffsetDateTime[]{TRUNCATED_MOMENT, TRUNCATED_MOMENT.plusSeconds(1)},
				b.getAttributeArray("moments")
			);
		}

		@Test
		@DisplayName("a localized OffsetDateTime is truncated when it is set")
		void shouldTruncateALocalizedOffsetDateTimeWhenItIsSet() {
			final InitialAttributesBuilder<?, ?> b =
				builder().setAttribute("moment", Locale.ENGLISH, NANO_PRECISE_MOMENT);

			assertEquals(TRUNCATED_MOMENT, b.getAttribute("moment", Locale.ENGLISH));
			assertNotEquals(NANO_PRECISE_MOMENT, b.getAttribute("moment", Locale.ENGLISH));
		}

		@Test
		@DisplayName("a LocalDateTime is truncated but keeps its own type")
		void shouldTruncateALocalDateTimeWithoutRewritingIt() {
			// the storage-path normalization deliberately does NOT rewrite a LocalDateTime to an OffsetDateTime at
			// UTC the way the query-path one does - an attribute declared LocalDateTime has to be able to carry one
			final LocalDateTime probe = LocalDateTime.of(2026, 5, 20, 12, 19, 26, 123_456_789);
			final InitialAttributesBuilder<?, ?> b = builder().setAttribute("local", probe);

			assertEquals(LocalDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000), b.getAttribute("local"));
			assertInstanceOf(LocalDateTime.class, b.getAttribute("local"));
		}

		@Test
		@DisplayName("a LocalTime is truncated to whole milliseconds")
		void shouldTruncateALocalTime() {
			final InitialAttributesBuilder<?, ?> b =
				builder().setAttribute("timeOfDay", LocalTime.of(12, 19, 26, 123_456_789));

			assertEquals(LocalTime.of(12, 19, 26, 123_000_000), b.getAttribute("timeOfDay"));
		}

		@Test
		@DisplayName("a Float becomes the BigDecimal it is stored as")
		void shouldConvertAFloatToBigDecimalWhenItIsSet() {
			final InitialAttributesBuilder<?, ?> b = builder().setAttribute("price", 1.5f);

			assertEquals(new BigDecimal("1.5"), b.getAttribute("price"));
			assertInstanceOf(BigDecimal.class, b.getAttribute("price"));
			assertEquals(new BigDecimal("1.5"), build(b).getAttribute("price"));
		}

		@Test
		@DisplayName("a Double becomes the BigDecimal it is stored as")
		void shouldConvertADoubleToBigDecimalWhenItIsSet() {
			final InitialAttributesBuilder<?, ?> b = builder().setAttribute("weight", 2.25d);

			assertEquals(new BigDecimal("2.25"), b.getAttribute("weight"));
			assertInstanceOf(BigDecimal.class, b.getAttribute("weight"));
		}

		@Test
		@DisplayName("what the builder reports is exactly what the change set carries")
		void shouldReportExactlyWhatTheChangeSetCarries() {
			// the asymmetry itself, stated directly: before eager normalization the two sides of this comparison
			// were the un-truncated input and the truncated stored value
			final InitialAttributesBuilder<?, ?> b = builder()
				.setAttribute("moment", NANO_PRECISE_MOMENT)
				.setAttribute("price", 1.5f);

			final List<? extends AttributeMutation> mutations = b.buildChangeSet().toList();
			assertEquals(2, mutations.size());
			for (final AttributeMutation mutation : mutations) {
				final UpsertAttributeMutation upsert = assertInstanceOf(UpsertAttributeMutation.class, mutation);
				assertSame(
					b.getAttributeValue(upsert.getAttributeKey()).orElseThrow().value(),
					upsert.getAttributeValue(),
					"the mutation must carry the very instance the builder reports for " + upsert.getAttributeKey()
				);
			}
		}

		@Test
		@DisplayName("an already-normal value is kept as the very same instance")
		void shouldKeepAnAlreadyNormalValueIdentical() {
			// normalization has to stay identity-preserving, otherwise the pass buildChangeSet still performs would
			// allocate a fresh copy of every value on the way to the engine
			final String text = "unchanged";
			final String[] texts = {"a", "b"};
			final InitialAttributesBuilder<?, ?> b = builder()
				.setAttribute("text", text)
				.setAttribute("texts", texts)
				.setAttribute("moment", TRUNCATED_MOMENT);

			assertSame(text, b.getAttribute("text"));
			assertSame(texts, b.getAttributeArray("texts"));
			assertSame(TRUNCATED_MOMENT, b.getAttribute("moment"));
		}
	}

}
