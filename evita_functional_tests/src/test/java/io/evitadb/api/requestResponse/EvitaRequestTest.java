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

package io.evitadb.api.requestResponse;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.filter.AttributeSpecialValue.NOT_NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies more complex methods in {@link EvitaRequest}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class EvitaRequestTest {

	@Test
	void shouldAddScopeToRequestWithoutFilter() {
		final EvitaRequest request = new EvitaRequest(
			query(
				collection("a")
			),
			OffsetDateTime.now(),
			SealedEntity.class,
			null
		);
		final EvitaRequest copy = request.deriveCopyWith(
			"b", null, null, Locale.ENGLISH, EnumSet.of(Scope.ARCHIVED)
		);
		assertEquals("b", copy.getEntityType());
		assertEquals(Locale.ENGLISH, copy.getLocale());
		assertEquals(EnumSet.of(Scope.ARCHIVED), copy.getScopes());
		assertEquals(
			"""
				query(
					collection('b'),
					filterBy(
						scope(ARCHIVED)
					),
					require()
				)""",
			copy.getQuery().prettyPrint()
		);
	}

	@Test
	void shouldAddScopeToRequest() {
		final EvitaRequest request = new EvitaRequest(
			query(
				collection("a"),
				filterBy(
					entityLocaleEquals(Locale.GERMAN)
				)
			),
			OffsetDateTime.now(),
			SealedEntity.class,
			null
		);
		final EvitaRequest copy = request.deriveCopyWith(
			"b", null, null, Locale.ENGLISH, EnumSet.of(Scope.ARCHIVED)
		);
		assertEquals("b", copy.getEntityType());
		assertEquals(Locale.ENGLISH, copy.getLocale());
		assertEquals(EnumSet.of(Scope.ARCHIVED), copy.getScopes());
		assertEquals(
			"""
				query(
					collection('b'),
					filterBy(
						scope(ARCHIVED)
					),
					require()
				)""",
			copy.getQuery().prettyPrint()
		);
	}

	@Test
	void shouldReplaceScopeInRequest() {
		final EvitaRequest request = new EvitaRequest(
			query(
				collection("a"),
				filterBy(
					scope(Scope.LIVE)
				)
			),
			OffsetDateTime.now(),
			SealedEntity.class,
			null
		);
		final EvitaRequest copy = request.deriveCopyWith(
			"b", null, null, Locale.ENGLISH, EnumSet.of(Scope.ARCHIVED)
		);
		assertEquals("b", copy.getEntityType());
		assertEquals(Locale.ENGLISH, copy.getLocale());
		assertEquals(EnumSet.of(Scope.ARCHIVED), copy.getScopes());
		assertEquals(
			"""
				query(
					collection('b'),
					filterBy(
						scope(ARCHIVED)
					),
					require()
				)""",
			copy.getQuery().prettyPrint()
		);
	}

	@Test
	void shouldReplaceScopeInRequestAndExcludeNonMatchingContainers() {
		final EvitaRequest request = new EvitaRequest(
			query(
				collection("a"),
				filterBy(
					inScope(Scope.LIVE, attributeIs("code", NOT_NULL)),
					scope(Scope.LIVE, Scope.ARCHIVED)
				)
			),
			OffsetDateTime.now(),
			SealedEntity.class,
			null
		);
		final EvitaRequest copy = request.deriveCopyWith(
			"b", null, null, Locale.ENGLISH, EnumSet.of(Scope.ARCHIVED)
		);
		assertEquals("b", copy.getEntityType());
		assertEquals(Locale.ENGLISH, copy.getLocale());
		assertEquals(EnumSet.of(Scope.ARCHIVED), copy.getScopes());
		assertEquals(
			"""
				query(
					collection('b'),
					filterBy(
						scope(ARCHIVED)
					),
					require()
				)""",
			copy.getQuery().prettyPrint()
		);
	}

}