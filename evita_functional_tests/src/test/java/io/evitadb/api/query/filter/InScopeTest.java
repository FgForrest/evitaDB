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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link InScope} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class InScopeTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertEquals(Scope.LIVE, inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).getScope());
		assertEquals(Scope.ARCHIVED, inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(1)).getScope());
		assertArrayEquals(
			new FilterConstraint[] {entityPrimaryKeyInSet(1)},
			inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).getFiltering()
		);
	}

	@Test
	void shouldRecognizeApplicability() {
		assertThrows(EvitaInvalidUsageException.class, () -> new InScope(null));
		assertThrows(EvitaInvalidUsageException.class, () -> new InScope(Scope.LIVE));
		assertTrue(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).isApplicable());
		assertTrue(inScope(Scope.ARCHIVED, entityLocaleEquals(Locale.ENGLISH), entityPrimaryKeyInSet(1)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("inScope(LIVE,entityPrimaryKeyInSet(1))", inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).toString());
		assertEquals("inScope(ARCHIVED,entityLocaleEquals('en'),entityPrimaryKeyInSet(1))", inScope(Scope.ARCHIVED, entityLocaleEquals(Locale.ENGLISH), entityPrimaryKeyInSet(1)).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)), inScope(Scope.LIVE, entityPrimaryKeyInSet(1)));
		assertEquals(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)), inScope(Scope.LIVE, entityPrimaryKeyInSet(1)));
		assertNotEquals(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)), inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(1)));
		assertNotEquals(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)), inScope(Scope.LIVE, entityLocaleEquals(Locale.ENGLISH)));
		assertEquals(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode(), inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode());
		assertNotEquals(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode(), inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(1)).hashCode());
		assertNotEquals(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode(), inScope(Scope.LIVE, entityLocaleEquals(Locale.ENGLISH)).hashCode());
	}

}