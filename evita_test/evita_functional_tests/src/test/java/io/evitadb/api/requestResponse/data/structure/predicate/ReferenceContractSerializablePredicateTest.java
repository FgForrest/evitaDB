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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.AttributeRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.chunk.NoTransformer;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link ReferenceContractSerializablePredicate} verifying
 * reference filtering, fetch status, attribute predicates, locale
 * handling, and richer copy creation logic.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Reference contract predicate")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class ReferenceContractSerializablePredicateTest {
	private static final List<String> REFERENCED_ENTITY =
		Collections.singletonList("A");

	@Nonnull
	private static Map<String, RequirementContext> getDefaultRequirementContext() {
		return getDefaultRequirementContext(REFERENCED_ENTITY);
	}

	@Nonnull
	private static Map<String, RequirementContext> getDefaultRequirementContext(
		@Nonnull List<String> referenceNames
	) {
		return referenceNames
			.stream()
			.collect(Collectors.toMap(
				Function.identity(),
				it -> new RequirementContext(
					ManagedReferencesBehaviour.ANY,
					null, null, null, null, null,
					NoTransformer.INSTANCE
				)
			));
	}

	@Nonnull
	private static Map<String, AttributeRequest> toAttributeRequestIndex(
		@Nonnull Map<String, RequirementContext> defaultRequirementContext
	) {
		return defaultRequirementContext
			.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Map.Entry::getKey,
					it -> it.getValue().attributeRequest()
				)
			);
	}

	@Nonnull
	private static RequirementContext createRequirementContext(
		@Nonnull String... attributes
	) {
		return new RequirementContext(
			ManagedReferencesBehaviour.ANY,
			new AttributeContent(attributes),
			null, null, null, null, NoTransformer.INSTANCE
		);
	}

	@Nested
	@DisplayName("Fetch status checks")
	class FetchStatusTest {

		@Test
		@DisplayName("wasFetched returns true when references required")
		void shouldReturnTrueWhenRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			assertTrue(predicate.wasFetched());
		}

		@Test
		@DisplayName(
			"wasFetched returns false when references not required"
		)
		void shouldReturnFalseWhenNotRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, false,
					null, Collections.emptySet()
				);

			assertFalse(predicate.wasFetched());
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns true for any name when "
				+ "reference set is empty"
		)
		void shouldReturnTrueForAnyNameWhenSetEmpty() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			assertTrue(predicate.wasFetched("anyRef"));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns true only for present names"
		)
		void shouldReturnTrueOnlyForPresentNames() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(
						getDefaultRequirementContext()
					),
					null, true, null, Collections.emptySet()
				);

			assertTrue(predicate.wasFetched("A"));
			assertFalse(predicate.wasFetched("B"));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns false when not required"
		)
		void shouldReturnFalseForNameWhenNotRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, false,
					null, Collections.emptySet()
				);

			assertFalse(predicate.wasFetched("anyRef"));
		}
	}

	@Nested
	@DisplayName("Check fetched - exception throwing")
	class CheckFetchedTest {

		@Test
		@DisplayName(
			"checkFetched throws when references not required"
		)
		void shouldThrowWhenNotRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, false,
					null, Collections.emptySet()
				);

			assertThrows(
				ContextMissingException.class,
				predicate::checkFetched
			);
		}

		@Test
		@DisplayName("checkFetched does not throw when required")
		void shouldNotThrowWhenRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			predicate.checkFetched();
		}

		@Test
		@DisplayName(
			"checkFetched(name) throws when name not in set"
		)
		void shouldThrowWhenNameNotInSet() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(
						getDefaultRequirementContext()
					),
					null, true, null, Collections.emptySet()
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched("B")
			);
		}

		@Test
		@DisplayName(
			"checkFetched(name) does not throw for known name"
		)
		void shouldNotThrowForKnownName() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(
						getDefaultRequirementContext()
					),
					null, true, null, Collections.emptySet()
				);

			predicate.checkFetched("A");
		}
	}

	@Nested
	@DisplayName("Predicate test method")
	class TestMethodTest {

		@Test
		@DisplayName("returns false when references not required")
		void shouldReturnFalseWhenNotRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, false,
					null, Collections.emptySet()
				);

			final ReferenceContract reference =
				Mockito.mock(ReferenceContract.class);
			Mockito.when(reference.exists()).thenReturn(true);
			Mockito.when(reference.getReferenceName())
				.thenReturn("A");

			assertFalse(predicate.test(reference));
		}

		@Test
		@DisplayName(
			"returns true for existing reference when set is empty"
		)
		void shouldReturnTrueWhenSetEmpty() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final ReferenceContract reference =
				Mockito.mock(ReferenceContract.class);
			Mockito.when(reference.exists()).thenReturn(true);
			Mockito.when(reference.getReferenceName())
				.thenReturn("A");

			assertTrue(predicate.test(reference));
		}

		@Test
		@DisplayName("returns false for dropped reference")
		void shouldReturnFalseForDropped() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final ReferenceContract reference =
				Mockito.mock(ReferenceContract.class);
			Mockito.when(reference.exists()).thenReturn(false);
			Mockito.when(reference.getReferenceName())
				.thenReturn("A");

			assertFalse(predicate.test(reference));
		}

		@Test
		@DisplayName(
			"returns false for reference not in restricted set"
		)
		void shouldReturnFalseWhenNameNotInSet() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(
						getDefaultRequirementContext()
					),
					null, true, null, Collections.emptySet()
				);

			final ReferenceContract reference =
				Mockito.mock(ReferenceContract.class);
			Mockito.when(reference.exists()).thenReturn(true);
			Mockito.when(reference.getReferenceName())
				.thenReturn("B");

			assertFalse(predicate.test(reference));
		}
	}

	@Nested
	@DisplayName("isReferenceRequested method")
	class IsReferenceRequestedTest {

		@Test
		@DisplayName(
			"returns true for any name when set is empty"
		)
		void shouldReturnTrueForAnyNameWhenEmpty() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			assertTrue(predicate.isReferenceRequested("anything"));
		}

		@Test
		@DisplayName("returns false when not required")
		void shouldReturnFalseWhenNotRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, false,
					null, Collections.emptySet()
				);

			assertFalse(predicate.isReferenceRequested("anything"));
		}

		@Test
		@DisplayName(
			"returns true only for names in set"
		)
		void shouldReturnTrueOnlyForNamesInSet() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(
						getDefaultRequirementContext()
					),
					null, true, null, Collections.emptySet()
				);

			assertTrue(predicate.isReferenceRequested("A"));
			assertFalse(predicate.isReferenceRequested("B"));
		}
	}

	@Nested
	@DisplayName("Attribute predicate factory methods")
	class AttributePredicateTest {

		@Test
		@DisplayName(
			"getAttributePredicate returns predicate with "
				+ "specified attributes"
		)
		void shouldReturnPredicateWithSpecifiedAttributes() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(
						"A",
						createRequirementContext("x", "y")
							.attributeRequest()
					),
					null, true, null, Collections.emptySet()
				);

			final ReferenceAttributeValueSerializablePredicate
				attrPredicate = predicate.getAttributePredicate("A");

			assertTrue(attrPredicate.wasFetched());
			assertEquals(
				Set.of("x", "y"),
				attrPredicate.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName(
			"getAttributePredicate returns EMPTY for unknown ref"
		)
		void shouldReturnEmptyForUnknownRef() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(
						"A",
						createRequirementContext("x")
							.attributeRequest()
					),
					null, true, null, Collections.emptySet()
				);

			final ReferenceAttributeValueSerializablePredicate
				attrPredicate =
				predicate.getAttributePredicate("unknown");

			assertFalse(attrPredicate.wasFetched());
		}

		@Test
		@DisplayName(
			"getAllAttributePredicate returns predicate with ALL"
		)
		void shouldReturnAllAttributePredicate() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final ReferenceAttributeValueSerializablePredicate
				attrPredicate = predicate.getAllAttributePredicate();

			assertTrue(attrPredicate.wasFetched());
			assertTrue(
				attrPredicate.getReferenceAttributes()
					.attributeSet().isEmpty()
			);
			assertTrue(
				attrPredicate.getReferenceAttributes()
					.isRequiresEntityAttributes()
			);
		}
	}

	@Nested
	@DisplayName("Locale handling")
	class LocaleTest {

		@Test
		@DisplayName(
			"getAllLocales returns null when no locales defined"
		)
		void shouldReturnNullWhenNoLocales() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, null
				);

			assertNull(predicate.getAllLocales());
		}

		@Test
		@DisplayName(
			"getAllLocales returns implicit locale set when "
				+ "locales null"
		)
		void shouldReturnImplicitLocaleWhenLocalesNull() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					Locale.ENGLISH, null
				);

			assertEquals(
				Set.of(Locale.ENGLISH),
				predicate.getAllLocales()
			);
		}

		@Test
		@DisplayName(
			"getAllLocales merges implicit locale with locales"
		)
		void shouldMergeImplicitLocaleWithLocales() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					Locale.ENGLISH, Set.of(Locale.FRENCH)
				);

			final Set<Locale> allLocales = predicate.getAllLocales();

			assertTrue(allLocales.contains(Locale.ENGLISH));
			assertTrue(allLocales.contains(Locale.FRENCH));
		}
	}

	@Nested
	@DisplayName("Richer copy creation")
	class RicherCopyTest {

		@Test
		@DisplayName(
			"creates richer copy when request requires references"
		)
		void shouldCreateRicherCopyForNoReferences() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, false,
					null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when already required and no changes"
		)
		void shouldNotCreateRicherCopyForNoReferences() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when already required and request "
				+ "doesn't require"
		)
		void shouldNotCreateRicherCopyWhenAlreadyPresent() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(false);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName("creates richer copy when adding new references")
		void shouldCreateRicherCopyForReferences() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(getDefaultRequirementContext());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when references match exactly"
		)
		void shouldNotCreateRicherCopyForReferences() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(
						getDefaultRequirementContext()
					),
					null, true, null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(getDefaultRequirementContext());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when request is subset"
		)
		void shouldNotCreateRicherCopyForReferencesSubset() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(
						getDefaultRequirementContext(
							Arrays.asList("A", "B")
						)
					),
					null, true, null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(
					getDefaultRequirementContext(
						Collections.singletonList("A")
					)
				);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy when adding locales"
		)
		void shouldCreateRicherCopyForLocales() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Set.of(Locale.ENGLISH));
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertEquals(
				Set.of(Locale.ENGLISH), richerCopy.getAllLocales()
			);
		}

		@Test
		@DisplayName(
			"creates richer copy with additional locales"
		)
		void shouldCreateRicherCopyForAdditionalLocales() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					Set.of(Locale.ENGLISH, Locale.CANADA)
				);
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertEquals(
				Set.of(Locale.ENGLISH, Locale.CANADA),
				richerCopy.getAllLocales()
			);
		}

		@Test
		@DisplayName(
			"returns same when locales match exactly"
		)
		void shouldNotCreateRicherCopyWhenLocalesMatch() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					Set.of(Locale.ENGLISH, Locale.CANADA)
				);
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when locale request is subset"
		)
		void shouldNotCreateRicherCopyWhenLocaleSubset() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Set.of(Locale.ENGLISH));
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy with reference attributes by name"
		)
		void shouldCreateRicherCopyForAttributesByName() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, false,
					null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(
					Map.of(
						"A",
						createRequirementContext("D", "E")
					)
				);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertEquals(
				Set.of("D", "E"),
				richerCopy.getAttributePredicate("A")
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName(
			"merges attributes from basis and request"
		)
		void shouldMergeAttributesFromBasisAndRequest() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(
						"A",
						createRequirementContext("D", "E")
							.attributeRequest()
					),
					null, true, null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(
					Map.of(
						"A",
						createRequirementContext("F", "X")
					)
				);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertEquals(
				Set.of("D", "E", "F", "X"),
				richerCopy.getAttributePredicate("A")
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName(
			"overrides specific attributes with ALL"
		)
		void shouldOverrideSpecificWithAll() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(
						"A",
						createRequirementContext("D", "E")
							.attributeRequest()
					),
					null, true, null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(
					Map.of("A", createRequirementContext())
				);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertEquals(
				Set.of(),
				richerCopy.getAttributePredicate("A")
					.getReferenceAttributes().attributeSet()
			);
			assertTrue(
				richerCopy.getAttributePredicate("A")
					.getReferenceAttributes()
					.isRequiresEntityAttributes()
			);
		}

		@Test
		@DisplayName(
			"returns same when ALL basis gets specific subset"
		)
		void shouldReturnSameWhenAllBasisGetsSubset() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(
						"A",
						createRequirementContext().attributeRequest()
					),
					null, true, null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(
					Map.of(
						"A",
						createRequirementContext("D", "E")
					)
				);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"merges implicitLocale from request when "
				+ "predicate has null implicitLocale"
		)
		void shouldMergeImplicitLocaleFromRequest() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true,
					null, Collections.emptySet()
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(Locale.ENGLISH);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			// The richer copy should pick up implicitLocale
			// from the request when predicate has null
			assertNotSame(predicate, richerCopy);
			assertEquals(
				Locale.ENGLISH,
				richerCopy.getImplicitLocale()
			);
		}
	}

	/**
	 * A query may name some references specifically and cover the rest with a catch-all `referenceContent`
	 * requirement. The fetcher loads both kinds, so the predicate has to admit both: a reference the specific
	 * entries do not name is covered by the default attribute request, and that request is what shapes its
	 * attributes. The reference-specific entries stay an override for the references they name, and a query with no
	 * catch-all requirement keeps hiding everything it did not name.
	 */
	@Nested
	@DisplayName("Default requirement beside reference specific ones")
	class DefaultBesideSpecificTest {

		private static final String COVERED_BY_DEFAULT = "B";

		/**
		 * Builds a predicate for the shape "one reference named specifically, everything else covered by the
		 * catch-all requirement".
		 *
		 * @param defaultAttributes attributes the catch-all requirement asks for
		 * @return the predicate to assert on
		 */
		@Nonnull
		private ReferenceContractSerializablePredicate createDefaultBesideSpecificPredicate(
			@Nonnull String... defaultAttributes
		) {
			return new ReferenceContractSerializablePredicate(
				Map.of("A", createRequirementContext("D", "E").attributeRequest()),
				createRequirementContext(defaultAttributes).attributeRequest(),
				true, null, Collections.emptySet()
			);
		}

		/**
		 * Builds an entity schema declaring references of the passed names.
		 *
		 * @param referenceNames names of the references the schema declares
		 * @return the mocked schema
		 */
		@Nonnull
		private EntitySchemaContract createSchemaWithReferences(@Nonnull String... referenceNames) {
			final Map<String, ReferenceSchemaContract> references =
				CollectionUtils.createLinkedHashMap(referenceNames.length);
			for (final String referenceName : referenceNames) {
				references.put(referenceName, Mockito.mock(ReferenceSchemaContract.class));
			}
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getReferences()).thenReturn(references);
			return entitySchema;
		}

		/**
		 * Builds an existing reference of the passed name.
		 *
		 * @param referenceName name the mocked reference carries
		 * @return the mocked reference
		 */
		@Nonnull
		private ReferenceContract createReference(@Nonnull String referenceName) {
			final ReferenceContract reference = Mockito.mock(ReferenceContract.class);
			Mockito.when(reference.exists()).thenReturn(true);
			Mockito.when(reference.getReferenceName()).thenReturn(referenceName);
			return reference;
		}

		@Test
		@DisplayName("reference covered by the default is fetched and visible")
		void shouldTreatReferenceCoveredByDefaultAsFetched() {
			final ReferenceContractSerializablePredicate predicate =
				createDefaultBesideSpecificPredicate("F");

			assertTrue(predicate.wasFetched(COVERED_BY_DEFAULT));
			assertTrue(predicate.isReferenceRequested(COVERED_BY_DEFAULT));
			assertTrue(predicate.test(createReference(COVERED_BY_DEFAULT)));
			predicate.checkFetched(COVERED_BY_DEFAULT);
		}

		@Test
		@DisplayName("reference covered by the default gets the default attributes")
		void shouldGiveDefaultAttributesToReferenceCoveredByDefault() {
			final ReferenceContractSerializablePredicate predicate =
				createDefaultBesideSpecificPredicate("F");

			assertEquals(
				Set.of("F"),
				predicate.getAttributePredicate(COVERED_BY_DEFAULT)
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName("reference named specifically keeps its own attributes")
		void shouldKeepSpecificAttributesOfNamedReference() {
			final ReferenceContractSerializablePredicate predicate =
				createDefaultBesideSpecificPredicate("F");

			assertTrue(predicate.wasFetched("A"));
			assertEquals(
				Set.of("D", "E"),
				predicate.getAttributePredicate("A")
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName("reference not named is hidden when there is no default")
		void shouldHideUnnamedReferenceWhenNoDefaultExists() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of("A", createRequirementContext("D", "E").attributeRequest()),
					null, true, null, Collections.emptySet()
				);

			assertFalse(predicate.wasFetched(COVERED_BY_DEFAULT));
			assertFalse(predicate.isReferenceRequested(COVERED_BY_DEFAULT));
			assertFalse(predicate.test(createReference(COVERED_BY_DEFAULT)));
			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(COVERED_BY_DEFAULT)
			);
			assertEquals(
				Set.of(),
				predicate.getAttributePredicate(COVERED_BY_DEFAULT)
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName("every reference stays visible when only the default is present")
		void shouldKeepEveryReferenceVisibleWithDefaultOnly() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(),
					createRequirementContext("F").attributeRequest(),
					true, null, Collections.emptySet()
				);

			assertTrue(predicate.wasFetched(COVERED_BY_DEFAULT));
			assertTrue(predicate.test(createReference(COVERED_BY_DEFAULT)));
			assertEquals(
				Set.of("F"),
				predicate.getAttributePredicate(COVERED_BY_DEFAULT)
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName("no reference is visible when references were not requested")
		void shouldHideEveryReferenceWhenReferencesAreNotRequested() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of("A", createRequirementContext("D", "E").attributeRequest()),
					createRequirementContext("F").attributeRequest(),
					false, null, Collections.emptySet()
				);

			assertFalse(predicate.wasFetched());
			assertFalse(predicate.wasFetched(COVERED_BY_DEFAULT));
			assertFalse(predicate.isReferenceRequested(COVERED_BY_DEFAULT));
			assertFalse(predicate.test(createReference(COVERED_BY_DEFAULT)));
			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(COVERED_BY_DEFAULT)
			);
		}

		@Test
		@DisplayName("every schema reference is requested when a default is present")
		void shouldRequestEverySchemaReferenceWhenDefaultIsPresent() {
			final ReferenceContractSerializablePredicate predicate =
				createDefaultBesideSpecificPredicate("F");

			assertEquals(
				Set.of("A", COVERED_BY_DEFAULT),
				predicate.getRequestedReferenceNames(
					createSchemaWithReferences("A", COVERED_BY_DEFAULT)
				)
			);
		}

		@Test
		@DisplayName("only the named references are requested without a default")
		void shouldRequestOnlyNamedReferencesWithoutDefault() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of("A", createRequirementContext("D", "E").attributeRequest()),
					null, true, null, Collections.emptySet()
				);

			assertEquals(
				Set.of("A"),
				predicate.getRequestedReferenceNames(
					createSchemaWithReferences("A", COVERED_BY_DEFAULT)
				)
			);
		}

		@Test
		@DisplayName("every schema reference is requested when nothing was named")
		void shouldRequestEverySchemaReferenceWhenNothingWasNamed() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), null, true, null, Collections.emptySet()
				);

			assertEquals(
				Set.of("A", COVERED_BY_DEFAULT),
				predicate.getRequestedReferenceNames(
					createSchemaWithReferences("A", COVERED_BY_DEFAULT)
				)
			);
		}

		@Test
		@DisplayName("richer copy keeps the default beside the specific entries")
		void shouldKeepDefaultBesideSpecificEntriesInRicherCopy() {
			final ReferenceContractSerializablePredicate predicate =
				createDefaultBesideSpecificPredicate("F");

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(Map.of("A", createRequirementContext("X")));
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(null);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertEquals(
				Set.of("D", "E", "X"),
				richerCopy.getAttributePredicate("A")
					.getReferenceAttributes().attributeSet()
			);
			assertTrue(richerCopy.wasFetched(COVERED_BY_DEFAULT));
			assertEquals(
				Set.of("F"),
				richerCopy.getAttributePredicate(COVERED_BY_DEFAULT)
					.getReferenceAttributes().attributeSet()
			);
		}
	}

	@Nested
	@DisplayName("Richer copy keeps what an earlier fetch made visible")
	class EnrichmentMonotonicityTest {

		private static final String NAMED_REFERENCE = "A";
		private static final String OTHER_REFERENCE = "C";

		/**
		 * Builds the request an enrichment is driven by.
		 *
		 * @param defaultRequirement   requirement covering every reference the per-name map does not name, NULL when
		 *                             the request carries no catch-all `referenceContent`
		 * @param referenceEntityFetch requirements the request names specifically
		 * @return the mocked request
		 */
		@Nonnull
		private EvitaRequest createEnrichingRequest(
			@Nullable RequirementContext defaultRequirement,
			@Nonnull Map<String, RequirementContext> referenceEntityFetch
		) {
			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences())
				.thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(referenceEntityFetch);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement())
				.thenReturn(defaultRequirement);
			return evitaRequest;
		}

		/**
		 * Returns the requirement a bare `referenceContent(<name>)` produces - the reference is fetched, none of its
		 * attributes are.
		 *
		 * @return the requirement asking for no reference attribute
		 */
		@Nonnull
		private RequirementContext createBareRequirementContext() {
			return new RequirementContext(
				ManagedReferencesBehaviour.ANY,
				null, null, null, null, null,
				NoTransformer.INSTANCE
			);
		}

		@Test
		@DisplayName("bare named reference does not hide the attributes of a catch-all with all of them")
		void shouldKeepAllAttributesOfCatchAllWhenEnrichedWithBareNamedReference() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(),
					createRequirementContext().attributeRequest(),
					true, null, Collections.emptySet()
				);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(
					createEnrichingRequest(
						null,
						Map.of(NAMED_REFERENCE, createBareRequirementContext())
					)
				);

			assertTrue(
				richerCopy.getAttributePredicate(NAMED_REFERENCE)
					.getReferenceAttributes().isRequiresEntityAttributes()
			);
		}

		@Test
		@DisplayName("bare named reference does not hide the attributes of a catch-all")
		void shouldKeepCatchAllAttributesWhenEnrichedWithBareNamedReference() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(),
					createRequirementContext("F").attributeRequest(),
					true, null, Collections.emptySet()
				);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(
					createEnrichingRequest(
						null,
						Map.of(NAMED_REFERENCE, createBareRequirementContext())
					)
				);

			assertEquals(
				Set.of("F"),
				richerCopy.getAttributePredicate(NAMED_REFERENCE)
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName("catch-all of the enriching request reaches the reference named earlier")
		void shouldGiveNewCatchAllAttributesToPreviouslyNamedReference() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(NAMED_REFERENCE, createRequirementContext("D", "E").attributeRequest()),
					null, true, null, Collections.emptySet()
				);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(
					createEnrichingRequest(
						createRequirementContext("F"),
						Collections.emptyMap()
					)
				);

			assertEquals(
				Set.of("D", "E", "F"),
				richerCopy.getAttributePredicate(NAMED_REFERENCE)
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName("catch-all without attributes takes none away from the reference named earlier")
		void shouldKeepNamedAttributesWhenCatchAllWithoutAttributesArrives() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(NAMED_REFERENCE, createRequirementContext("D", "E").attributeRequest()),
					null, true, null, Collections.emptySet()
				);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(
					createEnrichingRequest(
						createBareRequirementContext(),
						Collections.emptyMap()
					)
				);

			assertEquals(
				Set.of("D", "E"),
				richerCopy.getAttributePredicate(NAMED_REFERENCE)
					.getReferenceAttributes().attributeSet()
			);
		}

		@Test
		@DisplayName("enrichment naming references only keeps behaving as it did")
		void shouldLeaveSpecificOnlyEnrichmentUnchanged() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Map.of(NAMED_REFERENCE, createRequirementContext("D", "E").attributeRequest()),
					null, true, null, Collections.emptySet()
				);

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(
					createEnrichingRequest(
						null,
						Map.of(
							NAMED_REFERENCE, createRequirementContext("X"),
							OTHER_REFERENCE, createRequirementContext("Y")
						)
					)
				);

			assertEquals(
				Set.of("D", "E", "X"),
				richerCopy.getAttributePredicate(NAMED_REFERENCE)
					.getReferenceAttributes().attributeSet()
			);
			assertEquals(
				Set.of("Y"),
				richerCopy.getAttributePredicate(OTHER_REFERENCE)
					.getReferenceAttributes().attributeSet()
			);
			assertFalse(richerCopy.wasFetched("Z"));
		}
	}
}
