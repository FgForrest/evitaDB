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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This test verifies behaviour of {@link ReferenceAttributeValueSerializablePredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class ReferenceAttributeValueSerializablePredicateTest {

	@Test
	void shouldCreateRicherCopyForGlobalAttributes() {
		final ReferenceAttributeValueSerializablePredicate globalAttributesRequired = new ReferenceAttributeValueSerializablePredicate(
			null, Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Collections.singletonList(Locale.ENGLISH)));
		assertNotSame(globalAttributesRequired, globalAttributesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAttributes() {
		final ReferenceAttributeValueSerializablePredicate globalAttributesRequired = new ReferenceAttributeValueSerializablePredicate(
			null, Collections.emptySet()
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(Collections.emptySet());
		assertSame(globalAttributesRequired, globalAttributesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldCreateRicherCopyForGlobalAndLocalizedAttributes() {
		final ReferenceAttributeValueSerializablePredicate localizedAttributesRequired = new ReferenceAttributeValueSerializablePredicate(
			null, new HashSet<>(Collections.singletonList(Locale.ENGLISH))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)));
		assertNotSame(localizedAttributesRequired, localizedAttributesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAttributes() {
		final ReferenceAttributeValueSerializablePredicate localizedAttributesRequired = new ReferenceAttributeValueSerializablePredicate(
			null, new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA)));
		assertSame(localizedAttributesRequired, localizedAttributesRequired.createRicherCopyWith(evitaRequest));
	}

	@Test
	void shouldNotCreateRicherCopyForGlobalAndLocalizedAttributesSubset() {
		final ReferenceAttributeValueSerializablePredicate localizedAttributesRequired = new ReferenceAttributeValueSerializablePredicate(
			null, new HashSet<>(Arrays.asList(Locale.ENGLISH, Locale.CANADA))
		);

		final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
		Mockito.when(evitaRequest.getRequiredLocales()).thenReturn(new HashSet<>(Collections.singletonList(Locale.ENGLISH)));
		assertSame(localizedAttributesRequired, localizedAttributesRequired.createRicherCopyWith(evitaRequest));
	}

}