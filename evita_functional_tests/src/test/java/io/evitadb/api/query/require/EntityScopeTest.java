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

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static io.evitadb.api.query.QueryConstraints.scope;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link EntityScope} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class EntityScopeTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertEquals(EnumSet.of(Scope.LIVE), scope(Scope.LIVE).getScope());
		assertEquals(EnumSet.of(Scope.ARCHIVED), scope(Scope.ARCHIVED).getScope());
		assertEquals(EnumSet.of(Scope.LIVE, Scope.ARCHIVED), scope(Scope.LIVE, Scope.ARCHIVED).getScope());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new EntityScope().isApplicable());
		assertTrue(scope(Scope.LIVE).isApplicable());
		assertTrue(scope(Scope.ARCHIVED).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("scope(LIVE)", scope(Scope.LIVE).toString());
		assertEquals("scope(ARCHIVED)", scope(Scope.ARCHIVED).toString());
		assertEquals("scope(LIVE,ARCHIVED)", scope(Scope.LIVE, Scope.ARCHIVED).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(scope(Scope.LIVE), scope(Scope.LIVE));
		assertEquals(scope(Scope.LIVE), scope(Scope.LIVE));
		assertNotEquals(scope(Scope.LIVE), scope(Scope.ARCHIVED));
		assertEquals(scope(Scope.ARCHIVED, Scope.LIVE), scope(Scope.LIVE, Scope.ARCHIVED));
		assertNotEquals(scope(Scope.LIVE), scope(Scope.LIVE, Scope.ARCHIVED));
		assertEquals(scope(Scope.LIVE).hashCode(), scope(Scope.LIVE).hashCode());
		assertNotEquals(scope(Scope.LIVE).hashCode(), scope(Scope.ARCHIVED).hashCode());
		assertEquals(scope(Scope.ARCHIVED, Scope.LIVE).hashCode(), scope(Scope.LIVE, Scope.ARCHIVED).hashCode());
		assertNotEquals(scope(Scope.LIVE).hashCode(), scope(Scope.LIVE, Scope.ARCHIVED).hashCode());
	}

}