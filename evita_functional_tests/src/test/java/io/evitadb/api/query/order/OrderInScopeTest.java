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

package io.evitadb.api.query.order;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link OrderInScope} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class OrderInScopeTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertEquals(Scope.LIVE, inScope(Scope.LIVE, attributeNatural("code", ASC)).getScope());
		assertEquals(Scope.ARCHIVED, inScope(Scope.ARCHIVED, attributeNatural("code", ASC)).getScope());
		assertArrayEquals(
			new OrderConstraint[] {attributeNatural("code", ASC)},
			inScope(Scope.LIVE, attributeNatural("code", ASC)).getOrdering()
		);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertThrows(EvitaInvalidUsageException.class, () -> new OrderInScope(null));
		assertThrows(EvitaInvalidUsageException.class, () -> new OrderInScope(Scope.LIVE));
		assertTrue(inScope(Scope.LIVE, attributeNatural("code", ASC)).isApplicable());
		assertTrue(inScope(Scope.ARCHIVED, attributeNatural("name", DESC), attributeNatural("code", ASC)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("inScope(LIVE,attributeNatural('code',ASC))", inScope(Scope.LIVE, attributeNatural("code", ASC)).toString());
		assertEquals("inScope(ARCHIVED,attributeNatural('name',DESC),attributeNatural('code',ASC))", inScope(Scope.ARCHIVED, attributeNatural("name", DESC), attributeNatural("code", ASC)).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(inScope(Scope.LIVE, attributeNatural("code", ASC)), inScope(Scope.LIVE, attributeNatural("code", ASC)));
		assertEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)), inScope(Scope.LIVE, attributeNatural("code", ASC)));
		assertNotEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)), inScope(Scope.ARCHIVED, attributeNatural("code", ASC)));
		assertNotEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)), inScope(Scope.LIVE, attributeNatural("name", DESC)));
		assertEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode(), inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode());
		assertNotEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode(), inScope(Scope.ARCHIVED, attributeNatural("code", ASC)).hashCode());
		assertNotEquals(inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode(), inScope(Scope.LIVE, attributeNatural("name", DESC)).hashCode());
	}

}