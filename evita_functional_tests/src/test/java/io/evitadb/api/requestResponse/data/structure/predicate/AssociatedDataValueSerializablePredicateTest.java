/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.EvitaRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This test verifies behaviour of {@link AssociatedDataValueSerializablePredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class AssociatedDataValueSerializablePredicateTest {

	@Test
	void shouldCreateRicherCopyForNoAssociatedData() {
		final AssociatedDataValueSerializablePredicate noAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, Collections.emptySet(), Collections.emptySet(), false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertNotSame(noAssociatedDataRequired, noAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForNoAssociatedData() {
		final AssociatedDataValueSerializablePredicate noAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, Collections.emptySet(), Collections.emptySet(), false
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(false);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertSame(noAssociatedDataRequired, noAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForNoAssociatedDataWhenAssociatedDataPresent() {
		final AssociatedDataValueSerializablePredicate noAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, Collections.emptySet(), Collections.emptySet(), true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(false);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertSame(noAssociatedDataRequired, noAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForGlobalAssociatedData() {
		final AssociatedDataValueSerializablePredicate globalAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, Collections.emptySet(), Collections.emptySet(), true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Collections.singletonList(Locale.ENGLISH)));
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertNotSame(globalAssociatedDataRequired, globalAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAssociatedData() {
		final AssociatedDataValueSerializablePredicate globalAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, Collections.emptySet(), Collections.emptySet(), true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertSame(globalAssociatedDataRequired, globalAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForGlobalAndLocalizedAssociatedData() {
		final AssociatedDataValueSerializablePredicate localizedAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, new HashSet<>(Collections.singletonList(Locale.ENGLISH)), Collections.emptySet(), true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)));
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertNotSame(localizedAssociatedDataRequired, localizedAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAssociatedData() {
		final AssociatedDataValueSerializablePredicate localizedAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)), Collections.emptySet(), true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)));
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertSame(localizedAssociatedDataRequired, localizedAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAssociatedDataSubset() {
		final AssociatedDataValueSerializablePredicate localizedAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null, null, new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)), Collections.emptySet(), true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Collections.singletonList(Locale.ENGLISH)));
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(Collections.emptySet());
		assertSame(localizedAssociatedDataRequired, localizedAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForGlobalAndLocalizedAssociatedDataByName() {
		final AssociatedDataValueSerializablePredicate localizedAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null,
			null,
			new HashSet<>(Collections.singletonList(Locale.ENGLISH)),
			new HashSet<>(Collections.singletonList("A")),
			true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)));
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(new HashSet<>(Collections.singletonList("A")));
		assertNotSame(localizedAssociatedDataRequired, localizedAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAssociatedDataByName() {
		final AssociatedDataValueSerializablePredicate localizedAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null,
			null,
			new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)),
			new HashSet<>(Arrays.asList("A", "B")),
			true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)));
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(new HashSet<>(Arrays.asList("A", "B")));
		assertSame(localizedAssociatedDataRequired, localizedAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAssociatedDataSubsetByName() {
		final AssociatedDataValueSerializablePredicate localizedAssociatedDataRequired = new AssociatedDataValueSerializablePredicate(
			null,
			null,
			new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)),
			new HashSet<>(Arrays.asList("A", "B")),
			true
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.isRequiresEntityAssociatedData()).thenReturn(true);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Collections.singletonList(Locale.ENGLISH)));
		Mockito.when(evitaRequest.getEntityAssociatedDataSet()).thenReturn(new HashSet<>(Collections.singletonList("B")));
		assertSame(localizedAssociatedDataRequired, localizedAssociatedDataRequired.createRicherCopyWith(evitaRequest));
	}

}
