/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link RequireInScope} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class RequireInScopeTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertEquals(Scope.LIVE, inScope(Scope.LIVE, facetSummary()).getScope());
		assertEquals(Scope.ARCHIVED, inScope(Scope.ARCHIVED, facetSummary()).getScope());
		assertArrayEquals(
			new RequireConstraint[] {facetSummary()},
			inScope(Scope.LIVE, facetSummary()).getRequire()
		);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertThrows(EvitaInvalidUsageException.class, () -> new RequireInScope(null));
		assertThrows(EvitaInvalidUsageException.class, () -> new RequireInScope(Scope.LIVE));
		assertTrue(inScope(Scope.LIVE, facetSummary()).isApplicable());
		assertTrue(inScope(Scope.ARCHIVED, attributeHistogram(10, "width"), facetSummary()).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("inScope(LIVE,facetSummary())", inScope(Scope.LIVE, facetSummary()).toString());
		assertEquals("inScope(ARCHIVED,attributeHistogram(10,'width'),facetSummary())", inScope(Scope.ARCHIVED, attributeHistogram(10, "width"), facetSummary()).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(inScope(Scope.LIVE, facetSummary()), inScope(Scope.LIVE, facetSummary()));
		assertEquals(inScope(Scope.LIVE, facetSummary()), inScope(Scope.LIVE, facetSummary()));
		assertNotEquals(inScope(Scope.LIVE, facetSummary()), inScope(Scope.ARCHIVED, facetSummary()));
		assertNotEquals(inScope(Scope.LIVE, facetSummary()), inScope(Scope.LIVE, attributeHistogram(10, "width")));
		assertEquals(inScope(Scope.LIVE, facetSummary()).hashCode(), inScope(Scope.LIVE, facetSummary()).hashCode());
		assertNotEquals(inScope(Scope.LIVE, facetSummary()).hashCode(), inScope(Scope.ARCHIVED, facetSummary()).hashCode());
		assertNotEquals(inScope(Scope.LIVE, facetSummary()).hashCode(), inScope(Scope.LIVE, attributeHistogram(10, "width")).hashCode());
	}

}