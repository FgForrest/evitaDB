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

package io.evitadb.api.query.require;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link DataInLocales} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class DataInLocalesTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final DataInLocales dataInLocales1 = dataInLocalesAll();
		assertArrayEquals(new Locale[0], dataInLocales1.getLocales());

		final DataInLocales dataInLocales2 = dataInLocales(Locale.ENGLISH, Locale.FRENCH);
		assertArrayEquals(new Locale[]{Locale.ENGLISH, Locale.FRENCH}, dataInLocales2.getLocales());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(dataInLocalesAll().isApplicable());
		assertTrue(dataInLocales(Locale.ENGLISH).isApplicable());
		assertTrue(dataInLocales(Locale.ENGLISH, Locale.GERMAN).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final DataInLocales dataInLocales = dataInLocales(Locale.ENGLISH, Locale.FRENCH);
		assertEquals("dataInLocales('en','fr')", dataInLocales.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(dataInLocales(Locale.ENGLISH, Locale.FRENCH), dataInLocales(Locale.ENGLISH, Locale.FRENCH));
		assertEquals(dataInLocales(Locale.ENGLISH, Locale.FRENCH), dataInLocales(Locale.ENGLISH, Locale.FRENCH));
		assertNotEquals(dataInLocales(Locale.ENGLISH, Locale.FRENCH), dataInLocales(Locale.ENGLISH, Locale.GERMAN));
		assertNotEquals(dataInLocales(Locale.ENGLISH, Locale.FRENCH), dataInLocales(Locale.ENGLISH));
		assertEquals(dataInLocales(Locale.ENGLISH, Locale.FRENCH).hashCode(), dataInLocales(Locale.ENGLISH, Locale.FRENCH).hashCode());
		assertNotEquals(dataInLocales(Locale.ENGLISH, Locale.FRENCH).hashCode(), dataInLocales(Locale.ENGLISH, Locale.GERMAN).hashCode());
		assertNotEquals(dataInLocales(Locale.ENGLISH, Locale.FRENCH).hashCode(), dataInLocales(Locale.ENGLISH).hashCode());
	}

	@Test
	void shouldCombineWithAnotherConstraint() {
		assertEquals(dataInLocalesAll(), dataInLocalesAll().combineWith(dataInLocales(Locale.ENGLISH)));
		assertEquals(dataInLocalesAll(), dataInLocales(Locale.ENGLISH).combineWith(dataInLocalesAll()));
		assertEquals(dataInLocales(Locale.ENGLISH), dataInLocales(Locale.ENGLISH).combineWith(dataInLocales(Locale.ENGLISH)));
		assertEquals(dataInLocales(Locale.ENGLISH, Locale.FRENCH), dataInLocales(Locale.ENGLISH).combineWith(dataInLocales(Locale.FRENCH)));
	}

	@Test
	void shouldCombineWithAllAttribute() {
		final DataInLocales dataInLocales = dataInLocales(Locale.ENGLISH);
		final DataInLocales combinedDataInLocales = dataInLocales.combineWith(dataInLocalesAll());
		assertArrayEquals(new Locale[0], combinedDataInLocales.getLocales());
		assertTrue(combinedDataInLocales.isAllRequested());
	}

	@Test
	void shouldCombineWithSpecificAttribute() {
		final DataInLocales dataInLocales1 = dataInLocales(Locale.ENGLISH);
		final DataInLocales dataInLocales2 = dataInLocales(Locale.FRENCH);
		final DataInLocales combinedDataInLocales = dataInLocales1.combineWith(dataInLocales2);
		assertArrayEquals(new Locale[]{Locale.ENGLISH, Locale.FRENCH}, combinedDataInLocales.getLocales());
	}

	@Test
	void shouldCombineWithDuplicatedAttribute() {
		final DataInLocales dataInLocales1 = dataInLocales(Locale.ENGLISH);
		final DataInLocales dataInLocales2 = dataInLocales(Locale.ENGLISH);
		final DataInLocales combinedDataInLocales = dataInLocales1.combineWith(dataInLocales2);
		assertArrayEquals(new Locale[]{Locale.ENGLISH}, combinedDataInLocales.getLocales());
	}

	@Test
	void shouldThrowOnIncompatibleConstraint() {
		final DataInLocales dataInLocales = dataInLocales(Locale.ENGLISH);
		assertThrows(GenericEvitaInternalError.class, () -> dataInLocales.combineWith(associatedDataContentAll()));
	}

	@Test
	void shouldRecognizeFullContainForAllAttributes() {
		final DataInLocales dataInLocales1 = new DataInLocales();
		final DataInLocales dataInLocales2 = new DataInLocales(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN);
		assertFalse(dataInLocales1.isFullyContainedWithin(dataInLocales2));
		assertTrue(dataInLocales2.isFullyContainedWithin(dataInLocales1));
	}

	@Test
	void shouldRecognizeFullContainmentForSpecificAttributes() {
		final DataInLocales dataInLocales1 = new DataInLocales(Locale.ENGLISH, Locale.FRENCH);
		final DataInLocales dataInLocales2 = new DataInLocales(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN);
		assertTrue(dataInLocales1.isFullyContainedWithin(dataInLocales2));
	}

	@Test
	void shouldNotRecognizeFullContainmentForMissingAttributes() {
		final DataInLocales dataInLocales1 = new DataInLocales(Locale.ENGLISH, Locale.FRENCH, Locale.ITALY);
		final DataInLocales dataInLocales2 = new DataInLocales(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN);
		assertFalse(dataInLocales1.isFullyContainedWithin(dataInLocales2));
	}

}
