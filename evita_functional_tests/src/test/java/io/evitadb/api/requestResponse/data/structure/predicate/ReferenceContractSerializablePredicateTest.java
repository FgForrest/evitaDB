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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.AttributeRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behaviour of {@link ReferenceContractSerializablePredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class ReferenceContractSerializablePredicateTest {
	private static final List<String> REFERENCED_ENTITY = Collections.singletonList("A");

	@Nonnull
	private static Map<String, RequirementContext> getDefaultRequirementContext() {
		return getDefaultRequirementContext(REFERENCED_ENTITY);
	}

	@Nonnull
	private static Map<String, RequirementContext> getDefaultRequirementContext(List<String> referenceNames) {
		return referenceNames
			.stream()
			.collect(Collectors.toMap(
					Function.identity(),
					it -> new RequirementContext(AttributeRequest.EMPTY, null, null, null, null)
				)
			);
	}

	private static Map<String, AttributeRequest> toAttributeRequestIndex(Map<String, RequirementContext> defaultRequirementContext) {
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
	private static RequirementContext createRequirementContext(String... attributes) {
		return new RequirementContext(
			new AttributeRequest(Set.of(attributes), true),
			null, null, null, null
		);
	}

	@Test
	void shouldCreateRicherCopyForNoReferences() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			false,
			null,
			Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		assertNotSame(noReferencesRequired, noReferencesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForNoReferences() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			true,
			null,
			Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		assertSame(noReferencesRequired, noReferencesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForNoReferencesWhenReferencesPresent() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			true,
			null,
			Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(false);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		assertSame(noReferencesRequired, noReferencesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForReferences() {
		final ReferenceContractSerializablePredicate referencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			true,
			null,
			Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(getDefaultRequirementContext());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		assertNotSame(referencesRequired, referencesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForReferences() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			toAttributeRequestIndex(getDefaultRequirementContext()),
			null,
			true,
			null,
			Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(getDefaultRequirementContext());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		assertSame(noReferencesRequired, noReferencesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForReferencesSubset() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			toAttributeRequestIndex(
				getDefaultRequirementContext(Arrays.asList("A", "B"))
			),
			null,
			true,
			null,
			Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(getDefaultRequirementContext(Collections.singletonList("A")));
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		assertSame(noReferencesRequired, noReferencesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForRicherCopyOfAttributePredicate() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			true,
			null,
			Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Set.of(Locale.ENGLISH));

		final ReferenceContractSerializablePredicate richerCopy = noReferencesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(noReferencesRequired, richerCopy);
		assertEquals(Set.of(Locale.ENGLISH), richerCopy.getAllLocales());
	}

	@Test
	void shouldCreateRicherCopyForGlobalAndLocalizedAttributes() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			true,
			null,
			new HashSet<>(Collections.singletonList(Locale.ENGLISH))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Set.of(Locale.ENGLISH, Locale.CANADA));

		final ReferenceContractSerializablePredicate richerCopy = noReferencesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(noReferencesRequired, richerCopy);
		assertEquals(Set.of(Locale.ENGLISH, Locale.CANADA), richerCopy.getAllLocales());
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAttributes() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			true,
			null,
			new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Set.of(Locale.ENGLISH, Locale.CANADA));

		final ReferenceContractSerializablePredicate richerCopy = noReferencesRequired.createRicherCopyWith(evitaRequest);
		assertSame(noReferencesRequired, richerCopy);
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAttributesSubset() {
		final ReferenceContractSerializablePredicate noReferencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			true,
			null,
			new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(Collections.emptyMap());
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Set.of(Locale.ENGLISH));

		final ReferenceContractSerializablePredicate richerCopy = noReferencesRequired.createRicherCopyWith(evitaRequest);
		assertSame(noReferencesRequired, richerCopy);
	}

	@Test
	void shouldCreateRicherCopyForAttributesByName() {
		final ReferenceContractSerializablePredicate referencesRequired = new ReferenceContractSerializablePredicate(
			Collections.emptyMap(),
			null,
			false,
			null,
			new HashSet<>(Collections.singletonList(Locale.ENGLISH))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(
			Map.of(
				"A", createRequirementContext("D", "E")
			)
		);
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());

		final ReferenceContractSerializablePredicate richerCopy = referencesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(referencesRequired, richerCopy);
		assertEquals(Set.of("D", "E"), richerCopy.getAttributePredicate("A").getReferenceAttributes().attributeSet());
	}

	@Test
	void shouldCreateRicherCopyForAttributesByNameWithDefinedBasis() {
		final ReferenceContractSerializablePredicate referencesRequired = new ReferenceContractSerializablePredicate(
			Map.of(
				"A", createRequirementContext("D", "E").attributeRequest()
			),
			null,
			true,
			null,
			new HashSet<>(Collections.singletonList(Locale.ENGLISH))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(
			Map.of(
				"A", createRequirementContext("F", "X")
			)
		);
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());

		final ReferenceContractSerializablePredicate richerCopy = referencesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(referencesRequired, richerCopy);
		assertEquals(Set.of("D", "E", "F", "X"), richerCopy.getAttributePredicate("A").getReferenceAttributes().attributeSet());
	}

	@Test
	void shouldCreateRicherCopyForAttributesByNameWithDefinedBasisAndOverriddenByAll() {
		final ReferenceContractSerializablePredicate referencesRequired = new ReferenceContractSerializablePredicate(
			Map.of(
				"A", createRequirementContext("D", "E").attributeRequest()
			),
			null,
			true,
			null,
			new HashSet<>(Collections.singletonList(Locale.ENGLISH))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(
			Map.of(
				"A", createRequirementContext()
			)
		);
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());

		final ReferenceContractSerializablePredicate richerCopy = referencesRequired.createRicherCopyWith(evitaRequest);
		assertNotSame(referencesRequired, richerCopy);
		assertEquals(Set.of(), richerCopy.getAttributePredicate("A").getReferenceAttributes().attributeSet());
		assertTrue(richerCopy.getAttributePredicate("A").getReferenceAttributes().isRequiresEntityAttributes());
	}

	@Test
	void shouldCreateRicherCopyForAttributesByNameWithAllBasisAndOverriddenByAFew() {
		final ReferenceContractSerializablePredicate referencesRequired = new ReferenceContractSerializablePredicate(
			Map.of(
				"A", createRequirementContext().attributeRequest()
			),
			null,
			true,
			null,
			new HashSet<>(Collections.singletonList(Locale.ENGLISH))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityReferences()).thenReturn(true);
		Mockito.when(evitaRequest.getReferenceEntityFetch()).thenReturn(
			Map.of(
				"A", createRequirementContext("D", "E")
			)
		);
		Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());

		final ReferenceContractSerializablePredicate richerCopy = referencesRequired.createRicherCopyWith(evitaRequest);
		assertSame(referencesRequired, richerCopy);
	}

}