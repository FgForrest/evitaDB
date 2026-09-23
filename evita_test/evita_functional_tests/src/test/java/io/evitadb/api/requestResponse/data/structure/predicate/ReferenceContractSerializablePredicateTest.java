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
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.chunk.NoTransformer;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

	/**
	 * Builds the named reference content requirements a query carrying reference content instance names produces -
	 * a GraphQL field alias or a REST projection name becomes the instance name, and the requirement lands in
	 * a map of its own rather than in the plain reference set.
	 *
	 * @param referenceNames reference names the named requirements point at
	 * @return named requirement map keyed by the instance name and the reference name
	 */
	@Nonnull
	private static Map<ReferenceContentKey, RequirementContext> namedRequirementContext(
		@Nonnull String... referenceNames
	) {
		return Arrays.stream(referenceNames)
			.collect(
				Collectors.toMap(
					it -> new ReferenceContentKey(it + "Alias", it),
					it -> createRequirementContext()
				)
			);
	}

	/**
	 * Builds a predicate whose reference content was requested entirely through named requirements, which is what
	 * every externally issued query produces.
	 *
	 * @param referenceNames reference names the named requirements point at
	 * @return predicate with an empty reference set and the passed named reference names
	 */
	@Nonnull
	private static ReferenceContractSerializablePredicate namedOnlyPredicate(
		@Nonnull String... referenceNames
	) {
		return new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			new HashSet<>(Arrays.asList(referenceNames)),
			null, true, null, Collections.emptySet()
		);
	}

	/**
	 * Builds an existing reference of the passed name, the only two things
	 * {@link ReferenceContractSerializablePredicate#test(ReferenceContract)} looks at.
	 *
	 * @param referenceName name the reference carries
	 * @return reference stub that exists and carries the passed name
	 */
	@Nonnull
	private static ReferenceContract existingReference(@Nonnull String referenceName) {
		final ReferenceContract reference = Mockito.mock(ReferenceContract.class);
		Mockito.when(reference.exists()).thenReturn(true);
		Mockito.when(reference.getReferenceName()).thenReturn(referenceName);
		return reference;
	}

	/**
	 * Builds a request that requires references and carries the passed named reference requirements and nothing else.
	 *
	 * @param referenceNames reference names the named requirements point at
	 * @return request stub with the named requirements only
	 */
	@Nonnull
	private static EvitaRequest namedOnlyRequest(@Nonnull String... referenceNames) {
		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getNamedReferenceEntityFetch())
			.thenReturn(namedRequirementContext(referenceNames));
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		Mockito.when(evitaRequest.getDefaultReferenceRequirement()).thenReturn(null);
		return evitaRequest;
	}

	@Nested
	@DisplayName("Fetch status checks")
	class FetchStatusTest {

		@Test
		@DisplayName("wasFetched returns true when references required")
		void shouldReturnTrueWhenRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, false,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptySet(),
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
					Collections.emptyMap(), Collections.emptySet(), null, false,
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
					Collections.emptyMap(), Collections.emptySet(), null, false,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptySet(),
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
					Collections.emptySet(),
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
					Collections.emptyMap(), Collections.emptySet(), null, false,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptySet(),
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
					null, Collections.emptySet()
				);

			assertTrue(predicate.isReferenceRequested("anything"));
		}

		@Test
		@DisplayName("returns false when not required")
		void shouldReturnFalseWhenNotRequired() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), Collections.emptySet(), null, false,
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
					Collections.emptySet(),
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
					Collections.emptySet(),
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
					Collections.emptySet(),
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, false,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptySet(),
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
					Collections.emptySet(),
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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
					Collections.emptyMap(), Collections.emptySet(), null, false,
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
					Collections.emptySet(),
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
					Collections.emptySet(),
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
					Collections.emptySet(),
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
					Collections.emptyMap(), Collections.emptySet(), null, true,
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

		@Test
		@DisplayName("merges named reference names of both sides")
		void shouldCombineNamedReferenceNamesOnEnrichment() {
			final ReferenceContractSerializablePredicate predicate = namedOnlyPredicate("A");

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(namedOnlyRequest("B"));

			assertNotSame(predicate, richerCopy);
			assertEquals(
				Set.of("A", "B"),
				richerCopy.getVisibleReferenceNames()
			);
		}

		@Test
		@DisplayName(
			"returns same when the named reference names do not widen"
		)
		void shouldReturnSameInstanceWhenNamedReferenceNamesUnchanged() {
			// the identity is load bearing - it is what tells the enrichment that the previous read already brought
			// everything the new request asks for, so an equal-but-distinct copy would reinstate a storage round trip
			final ReferenceContractSerializablePredicate predicate = namedOnlyPredicate("A");

			assertSame(
				predicate,
				predicate.createRicherCopyWith(namedOnlyRequest("A"))
			);
		}

		@Test
		@DisplayName(
			"never narrows the named reference names below what was fetched"
		)
		void shouldNotNarrowNamedReferenceNamesOnEnrichment() {
			final ReferenceContractSerializablePredicate predicate = namedOnlyPredicate("A", "B");

			final ReferenceContractSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(namedOnlyRequest("A"));

			assertSame(predicate, richerCopy);
			assertEquals(
				Set.of("A", "B"),
				richerCopy.getVisibleReferenceNames()
			);
		}
	}

	@Nested
	@DisplayName("Visible reference names")
	class VisibleReferenceNamesTest {

		@Test
		@DisplayName(
			"returns null when every reference is allowed"
		)
		void shouldReturnNullWhenAllReferencesAreAllowed() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					Collections.emptyMap(), Collections.emptySet(), null, true,
					null, Collections.emptySet()
				);

			assertNull(predicate.getVisibleReferenceNames());
		}

		@Test
		@DisplayName(
			"returns null when a default requirement is present"
		)
		void shouldReturnNullWhenDefaultRequirementIsPresent() {
			// a plain referenceContent() asks for every reference there is and must never produce a narrowed read,
			// whatever else the request happens to name
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(getDefaultRequirementContext()),
					Set.of("B"),
					AttributeRequest.EMPTY, true, null, Collections.emptySet()
				);

			assertNull(predicate.getVisibleReferenceNames());
		}

		@Test
		@DisplayName(
			"returns null when references are not required at all"
		)
		void shouldReturnNullWhenReferencesAreNotRequired() {
			// null here does not mean "read everything" - the storage layer checks isRequiresEntityReferences()
			// first and never reaches this method for such a predicate
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(getDefaultRequirementContext()),
					Collections.emptySet(), null, false,
					null, Collections.emptySet()
				);

			assertNull(predicate.getVisibleReferenceNames());
		}

		@Test
		@DisplayName(
			"returns the keys of an unnamed reference set"
		)
		void shouldReturnUnnamedReferenceSetKeys() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(getDefaultRequirementContext()),
					Collections.emptySet(), null, true,
					null, Collections.emptySet()
				);

			assertEquals(Set.of("A"), predicate.getVisibleReferenceNames());
		}

		@Test
		@DisplayName(
			"returns the names of a named-only requirement"
		)
		void shouldReturnNamedReferenceNames() {
			assertEquals(
				Set.of("A"),
				namedOnlyPredicate("A").getVisibleReferenceNames()
			);
		}

		@Test
		@DisplayName(
			"unions named and unnamed reference names"
		)
		void shouldUnionNamedAndUnnamedReferenceNames() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(getDefaultRequirementContext()),
					Set.of("B"), null, true,
					null, Collections.emptySet()
				);

			assertEquals(
				Set.of("A", "B"),
				predicate.getVisibleReferenceNames()
			);
		}

		@Test
		@DisplayName(
			"an unnamed reference set hides every name outside it"
		)
		void shouldHideReferenceNameOutsideUnnamedReferenceSet() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate(
					toAttributeRequestIndex(getDefaultRequirementContext()),
					Collections.emptySet(), null, true,
					null, Collections.emptySet()
				);

			assertEquals(Set.of("A"), predicate.getVisibleReferenceNames());
			assertFalse(predicate.isReferenceRequested("B"));
			assertFalse(predicate.wasFetched("B"));
			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched("B")
			);
			assertFalse(predicate.test(existingReference("B")));
		}

		@Test
		@DisplayName(
			"the named reference itself stays visible"
		)
		void shouldAdmitTheNamedReferenceItself() {
			final ReferenceContractSerializablePredicate predicate = namedOnlyPredicate("A");

			assertTrue(predicate.isReferenceRequested("A"));
			assertTrue(predicate.wasFetched("A"));
			assertDoesNotThrow(() -> predicate.checkFetched("A"));
			assertTrue(predicate.test(existingReference("A")));
		}

		@Test
		@DisplayName(
			"a named-only requirement hides every name its narrowed read skipped"
		)
		void shouldHideReferenceNameOutsideTheNarrowedRead() {
			// the read this predicate narrows brings in `A` alone, so `B` is absent from the composed entity even
			// when the entity has such references - reporting it as fetched would answer an empty result for data
			// that was never read
			final ReferenceContractSerializablePredicate predicate = namedOnlyPredicate("A");

			assertEquals(Set.of("A"), predicate.getVisibleReferenceNames());
			assertFalse(predicate.isReferenceRequested("B"));
			assertFalse(predicate.wasFetched("B"));
			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched("B")
			);
			assertFalse(predicate.test(existingReference("B")));
		}
	}

	/**
	 * Covers the storage facing half of the predicate - the coverage it hands the decoder, and the rules by which
	 * an enrichment combines one predicate's key narrowing with another request's.
	 *
	 * The coverage is what the enrichment gate compares and what the deserializer decides admission on, so a
	 * mistake here is never a failure: it is a reference set that is silently shorter than the caller asked for.
	 */
	@Nested
	@DisplayName("Decode coverage")
	class DecodeCoverageTest {
		private static final String NARROWED = "narrowed";
		private static final String WHOLE = "whole";

		@Test
		@DisplayName("splits the visible names into the ones read whole and the ones bound to keys")
		void shouldSplitVisibleNamesIntoWholeAndKeyNarrowed() {
			final ReferenceContractSerializablePredicate predicate = narrowingPredicate(
				Map.of(NARROWED, new int[]{10, 20}), NARROWED, WHOLE
			);

			final ReferenceDecodeCoverage coverage = predicate.getDecodeCoverage();

			assertNotNull(coverage);
			assertEquals(Set.of(WHOLE), coverage.getNamesDecodedWhole());
			assertEquals(Set.of(NARROWED), coverage.getNamesDecodedByKey().keySet());
			assertArrayEquals(new int[]{10, 20}, coverage.getAdmittedKeys(NARROWED).toArray());
		}

		@Test
		@DisplayName("hands out one and the same coverage instance on every call")
		void shouldReturnTheSameCoverageInstanceOnEveryCall() {
			final ReferenceContractSerializablePredicate predicate = narrowingPredicate(
				Map.of(NARROWED, new int[]{10}), NARROWED, WHOLE
			);

			// the deserializer compares the bound coverage by identity to resolve a per-name admission once per run
			// of same-named references - a fresh equal object per read would re-resolve it for every reference
			assertSame(predicate.getDecodeCoverage(), predicate.getDecodeCoverage());
		}

		@Test
		@DisplayName("reports no coverage at all when the predicate narrows nothing away")
		void shouldReturnNullCoverageWhenNoNameIsNarrowed() {
			final ReferenceContractSerializablePredicate predicate =
				new ReferenceContractSerializablePredicate();

			// unrestricted is the absence of an object, never an object claiming to admit everything
			assertNull(predicate.getDecodeCoverage());
			assertTrue(ReferenceDecodeCoverage.isComplete(predicate.getDecodeCoverage()));
		}

		@Test
		@DisplayName("keeps a name bound when both sides bind it, to the union of their key sets")
		void shouldKeepANameNarrowedOnlyWhenBothSidesNarrowIt() {
			final ReferenceContractSerializablePredicate predicate = narrowingPredicate(
				Map.of(NARROWED, new int[]{10, 30}), NARROWED
			);

			final ReferenceContractSerializablePredicate richer = predicate.createRicherCopyWith(
				narrowingRequest(Map.of(NARROWED, new int[]{20, 30}), NARROWED)
			);

			final ReferenceDecodeCoverage coverage = richer.getDecodeCoverage();
			assertNotNull(coverage);
			// the enriched entity has to satisfy both requirements, so the decode has to cover both key sets
			assertArrayEquals(new int[]{10, 20, 30}, coverage.getAdmittedKeys(NARROWED).toArray());
			assertTrue(coverage.getNamesDecodedWhole().isEmpty());
		}

		@Test
		@DisplayName("un-binds a name the new request wants in full")
		void shouldUnNarrowANameTheNewRequestWantsWhole() {
			final ReferenceContractSerializablePredicate predicate = narrowingPredicate(
				Map.of(NARROWED, new int[]{10}), NARROWED
			);

			final ReferenceContractSerializablePredicate richer = predicate.createRicherCopyWith(
				narrowingRequest(Collections.emptyMap(), NARROWED)
			);

			assertNameDecodedWhole(richer, NARROWED);
		}

		@Test
		@DisplayName("un-binds a name the new request does not mention at all")
		void shouldUnNarrowANameTheNewRequestDoesNotMention() {
			final ReferenceContractSerializablePredicate predicate = narrowingPredicate(
				Map.of(NARROWED, new int[]{10}), NARROWED
			);

			final ReferenceContractSerializablePredicate richer = predicate.createRicherCopyWith(
				narrowingRequest(Map.of(WHOLE, new int[]{99}), WHOLE)
			);

			// deliberately conservative in this direction: widening costs a decode, keeping a narrowing the other
			// side never agreed to would drop references
			assertNameDecodedWhole(richer, NARROWED);
		}

		@Test
		@DisplayName("stays un-bound when this predicate binds nothing to begin with")
		void shouldStayUnNarrowedWhenThisPredicateNarrowsNothing() {
			final ReferenceContractSerializablePredicate predicate = narrowingPredicate(
				Collections.emptyMap(), NARROWED
			);

			final ReferenceContractSerializablePredicate richer = predicate.createRicherCopyWith(
				narrowingRequest(Map.of(NARROWED, new int[]{10}), NARROWED)
			);

			assertNameDecodedWhole(richer, NARROWED);
		}

		@Test
		@DisplayName("hands back itself when the binding did not change")
		void shouldReturnItselfWhenTheNarrowingDidNotChange() {
			final ReferenceContractSerializablePredicate predicate = narrowingPredicate(
				Map.of(NARROWED, new int[]{10, 20}), NARROWED
			);

			// identity, not equality: the enrichment compares predicates by reference and skips a storage round
			// trip on it, so a copy allocated when nothing widened turns every enrichment into a re-read
			assertSame(
				predicate,
				predicate.createRicherCopyWith(narrowingRequest(Map.of(NARROWED, new int[]{10, 20}), NARROWED))
			);
		}

		@Test
		@DisplayName("a limited view keeps the binding the read behind it was performed under")
		void shouldInheritANarrowingIntoALimitedView() {
			final ReferenceContractSerializablePredicate underlying = narrowingPredicate(
				Map.of(NARROWED, new int[]{10}), NARROWED
			);

			final ReferenceContractSerializablePredicate limited =
				new ReferenceContractSerializablePredicate(
					narrowingRequest(Map.of(NARROWED, new int[]{10}), NARROWED), underlying
				);

			final ReferenceDecodeCoverage coverage = limited.getDecodeCoverage();
			assertNotNull(coverage);
			// a limited view shows LESS of an entity that has already been read, so it cannot have decoded more
			// than that read did - claiming the name whole here would let the gate answer "already fetched"
			assertArrayEquals(new int[]{10}, coverage.getAdmittedKeys(NARROWED).toArray());
			assertFalse(coverage.isNameDecodedWhole(NARROWED));
		}

		/**
		 * Asserts a predicate's coverage reports the passed reference name as materialized in full.
		 *
		 * @param predicate     predicate whose coverage is examined
		 * @param referenceName reference name that must be reported whole
		 */
		private void assertNameDecodedWhole(
			@Nonnull ReferenceContractSerializablePredicate predicate,
			@Nonnull String referenceName
		) {
			final ReferenceDecodeCoverage coverage = predicate.getDecodeCoverage();
			if (coverage != null) {
				assertTrue(
					coverage.isNameDecodedWhole(referenceName),
					"`" + referenceName + "` must be read whole, not bound to a key set."
				);
				assertNull(coverage.getAdmittedKeys(referenceName));
			}
		}

		/**
		 * Builds a predicate asking for the passed reference names, with the passed per-name key binding.
		 *
		 * @param referenceKeyNarrowing referenced primary keys each name is bound to
		 * @param referenceNames        reference names the predicate lets through
		 * @return the predicate
		 */
		@Nonnull
		private ReferenceContractSerializablePredicate narrowingPredicate(
			@Nonnull Map<String, int[]> referenceKeyNarrowing,
			@Nonnull String... referenceNames
		) {
			return new ReferenceContractSerializablePredicate(
				Arrays.stream(referenceNames)
					.collect(Collectors.toMap(Function.identity(), it -> AttributeRequest.EMPTY)),
				Collections.emptySet(), null, true, null, Collections.emptySet(),
				referenceKeyNarrowing
			);
		}

		/**
		 * Builds a request asking for the passed reference names, with the passed per-name key binding.
		 *
		 * @param referenceKeyNarrowing referenced primary keys each name is bound to
		 * @param referenceNames        reference names the request asks for
		 * @return the request stub
		 */
		@Nonnull
		private EvitaRequest narrowingRequest(
			@Nonnull Map<String, int[]> referenceKeyNarrowing,
			@Nonnull String... referenceNames
		) {
			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
			Mockito.when(evitaRequest.getReferenceEntityFetch())
				.thenReturn(getDefaultRequirementContext(Arrays.asList(referenceNames)));
			Mockito.when(evitaRequest.getNamedReferenceEntityFetch()).thenReturn(Collections.emptyMap());
			Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getDefaultReferenceRequirement()).thenReturn(null);
			Mockito.when(evitaRequest.getReferenceKeyNarrowing()).thenReturn(referenceKeyNarrowing);
			return evitaRequest;
		}
	}

}
