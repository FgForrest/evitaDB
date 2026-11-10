/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks scope placement before planning and mutation and root conjunction parity when fetching references.
 * Items 1 and 2 are live, 3 and 4 archived, with codes a and b respectively. Owners 10 through 40 reference
 * the corresponding item; owner 60 references both items with code a, in different scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Scope placement across query and fetch entry points")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(REFERENCE)
public class ScopePlacementFunctionalTest {
	private static final String DATA_SET = "scopePlacement";
	private static final String WRITE_DATA_SET = "scopePlacementWrites";
	private static final String ENTITY_ITEM = "Item";
	private static final String ENTITY_OWNER = "Owner";
	private static final String REF_ITEM = "item";
	private static final String ATTR_CODE = "code";
	private static final Scope L = Scope.LIVE;
	private static final Scope A = Scope.ARCHIVED;
	private static final FilterConstraint CODE_A = attributeEquals(ATTR_CODE, "a");

	/** Creates the read-only fixture. */
	@DataSet(value = DATA_SET, destroyAfterClass = true)
	void setUpDataSet(@Nonnull Evita evita) {
		populate(evita);
	}

	/** Keeps mutations out of the shared read-only fixture. */
	@DataSet(value = WRITE_DATA_SET, readOnly = false, destroyAfterClass = true)
	void setUpWriteDataSet(@Nonnull Evita evita) {
		populate(evita);
	}

	/** Builds the live and archived targets and their owners. */
	private void populate(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_ITEM).withoutGeneratedPrimaryKey()
				.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.filterableInScope(L, A))
				.updateVia(session);
			session.defineEntitySchema(ENTITY_OWNER).withoutGeneratedPrimaryKey()
				.withReferenceToEntity(
					REF_ITEM, ENTITY_ITEM, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.withGroupTypeRelatedToEntity(ENTITY_ITEM).indexedInScope(L).facetedInScope(L)
				)
				.updateVia(session);
			session.createNewEntity(ENTITY_ITEM, 1).setAttribute(ATTR_CODE, "a").upsertVia(session);
			session.createNewEntity(ENTITY_ITEM, 2).setAttribute(ATTR_CODE, "b").upsertVia(session);
			session.createNewEntity(ENTITY_ITEM, 3).setAttribute(ATTR_CODE, "a").upsertVia(session);
			session.createNewEntity(ENTITY_ITEM, 4).setAttribute(ATTR_CODE, "b").upsertVia(session);
			session.archiveEntity(ENTITY_ITEM, 3);
			session.archiveEntity(ENTITY_ITEM, 4);
			session.createNewEntity(ENTITY_OWNER, 10)
				.setReference(REF_ITEM, 1, whichIs -> whichIs.setGroup(ENTITY_ITEM, 1))
				.upsertVia(session);
			session.createNewEntity(ENTITY_OWNER, 20)
				.setReference(REF_ITEM, 2, whichIs -> whichIs.setGroup(ENTITY_ITEM, 2))
				.upsertVia(session);
			session.createNewEntity(ENTITY_OWNER, 30)
				.setReference(REF_ITEM, 3, whichIs -> whichIs.setGroup(ENTITY_ITEM, 3))
				.upsertVia(session);
			session.createNewEntity(ENTITY_OWNER, 40)
				.setReference(REF_ITEM, 4, whichIs -> whichIs.setGroup(ENTITY_ITEM, 4))
				.upsertVia(session);
			session.createNewEntity(ENTITY_OWNER, 60)
				.setReference(REF_ITEM, 1).setReference(REF_ITEM, 3).upsertVia(session);
		});
	}


	/**
	 * Supplies shapes that previously widened the lookup or failed with an ambiguous-scope exception, each with the
	 * exact diagnostic of the entry check, so a refusal raised later for another reason does not pass.
	 */
	@Nonnull
	static Stream<Arguments> misplacedScopes() {
		return Stream.of(
			Arguments.of(inScope(L, scope(L, A), CODE_A), placedIn(scope(L, A), "inScope")),
			Arguments.of(inScope(A, scope(A), CODE_A), placedIn(scope(A), "inScope")),
			Arguments.of(userFilter(scope(A), CODE_A), placedIn(scope(A), "userFilter")),
			Arguments.of(and(scope(L), scope(A)), repeated(scope(A), scope(L))),
			Arguments.of(and(scope(L), and(scope(L))), repeated(scope(L), scope(L)))
		);
	}

	/** Returns the diagnostic of a scope placed in a forbidden container, with the extra advice of `inScope`. */
	@Nonnull
	private static String placedIn(@Nonnull EntityScope scope, @Nonnull String container) {
		return "Constraint `" + scope + "` cannot be placed in `" + container + "` - it selects the scopes its " +
			"whole entity level is searched in, so it may stand only at the root of the filter of that level or in " +
			"`and`." +
			("inScope".equals(container) ?
				" Name the scopes in a `scope(...)` at the root of that level and keep only the conditions in " +
					"`inScope(...)`." : "");
	}

	/** Returns the diagnostic of a second scope selection in one entity level. */
	@Nonnull
	private static String repeated(@Nonnull EntityScope next, @Nonnull EntityScope first) {
		return "Constraint `" + next + "` repeats the scope selection of `" + first + "` in the same entity level - " +
			"name all the scopes, in the order you prefer them, in one `scope(...)`.";
	}

	/** Root conjunction forms must select targets in exactly the same scopes. */
	@Nonnull
	static Stream<FilterBy> rootReferenceFilters() {
		return Stream.of(
			filterBy(scope(L, A), entityHaving(CODE_A)),
			filterBy(and(scope(L, A), entityHaving(CODE_A))),
			filterBy(and(and(scope(L, A)), and(entityHaving(CODE_A)))),
			filterBy(or(and(scope(L, A), entityHaving(CODE_A)))),
			filterBy(and(scope(A, L), entityHaving(CODE_A)))
		);
	}

	/**
	 * Checks planning errors and the valid placement beside userFilter.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Planning")
	class Planning {
		/** Invalid placement is diagnosed before resolving requested scopes. */
		@ParameterizedTest
		@MethodSource("io.evitadb.api.functional.reference.ScopePlacementFunctionalTest#misplacedScopes")
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse misplaced or duplicate scope selections")
		void shouldRefuseScopeWhenPlacementInvalid(
			@Nonnull FilterConstraint filter,
			@Nonnull String expectedMessage,
			@Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(expectedMessage, assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> session.queryList(query(collection(ENTITY_ITEM), filterBy(filter)), EntityReference.class)
				).getMessage());
			});
		}

		/** userFilter still filters the entities chosen by a root scope. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Accept the scope beside userFilter")
		void shouldAcceptScopeWhenBesideUserFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(
					List.of(3), session.queryList(
						query(collection(ENTITY_ITEM), filterBy(scope(A), userFilter(CODE_A))), EntityReference.class
					).stream().map(EntityReference::getPrimaryKey).toList()
				);
			});
		}

		/** The control proves that widening the target lookup changes the rows. */
		@ParameterizedTest
		@MethodSource("io.evitadb.api.functional.reference.ScopePlacementFunctionalTest#rootReferenceFilters")
		@UseDataSet(DATA_SET)
		@DisplayName("Select both target scopes through a root conjunction while planning")
		void shouldFetchBothScopesWhenOnRootPath(@Nonnull FilterBy filter, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(List.of(1), queryRows(session, filterBy(entityHaving(CODE_A))));
				assertEquals(List.of(1, 3), queryRows(session, filter));
			});
		}
	}

	/**
	 * Covers the direct entry point without query normalization.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Direct fetch")
	class DirectFetch {
		/** Root and nested conjunctions must produce the same non-vacuous result. */
		@ParameterizedTest
		@MethodSource("io.evitadb.api.functional.reference.ScopePlacementFunctionalTest#rootReferenceFilters")
		@UseDataSet(DATA_SET)
		@DisplayName("Select both target scopes through a root conjunction on getEntity")
		void shouldFetchBothScopesWhenOnRootPath(@Nonnull FilterBy filter, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(List.of(1), rows(session.getEntity(ENTITY_OWNER, 60,
					referenceContent(REF_ITEM, filterBy(entityHaving(CODE_A)))).orElseThrow()));
				assertEquals(List.of(1, 3), rows(session.getEntity(ENTITY_OWNER, 60,
					referenceContent(REF_ITEM, filter)).orElseThrow()));
			});
		}

		/** Direct fetch rejects a scope nested in a row-level wrapper before compiling it. */
		@ParameterizedTest
		@MethodSource("io.evitadb.api.functional.reference.ScopePlacementFunctionalTest#misplacedScopes")
		@UseDataSet(DATA_SET)
		@DisplayName("Refuse invalid fetched-reference scope placement on getEntity")
		void shouldRefuseScopeWhenPlacementInvalid(
			@Nonnull FilterConstraint filter,
			@Nonnull String expectedMessage,
			@Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(expectedMessage, assertThrowsExactly(
					EvitaInvalidUsageException.class,
					() -> session.getEntity(ENTITY_OWNER, 60, referenceContent(REF_ITEM, filterBy(filter)))
				).getMessage());
			});
		}
	}

	/**
	 * Checks success and refusal before a mutation can escape.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Write and fetch")
	class WriteAndFetch {
		/** An accepted root path compiles successfully after upserting the owner. */
		@Test
		@UseDataSet(WRITE_DATA_SET)
		@DisplayName("Select both target scopes through root conjunctions on upsertAndFetchEntity")
		void shouldFetchBothScopesWhenUpserting(@Nonnull Evita evita) {
			evita.updateCatalog(TEST_CATALOG, session -> {
				final List<Integer> control = rows(session.upsertAndFetchEntity(
					session.createNewEntity(ENTITY_OWNER, 70).setReference(REF_ITEM, 1).setReference(REF_ITEM, 3),
					referenceContent(REF_ITEM, filterBy(entityHaving(CODE_A)))
				));
				assertEquals(List.of(1), control);
				final List<FilterBy> filters = rootReferenceFilters().toList();
				filters.forEach(filter -> {
					final List<Integer> actual = rows(session.upsertAndFetchEntity(
						session.createNewEntity(ENTITY_OWNER, 71 + filters.indexOf(filter))
							.setReference(REF_ITEM, 1).setReference(REF_ITEM, 3),
						referenceContent(REF_ITEM, filter)
					));
					assertEquals(List.of(1, 3), actual, filter::toString);
					assertNotEquals(control, actual);
				});
			});
		}

		/** Invalid placement is refused before writing, even when the catalog cannot roll back a write. */
		@Test
		@UseDataSet(WRITE_DATA_SET)
		@DisplayName("Leave no entity written when fetched-reference placement is refused")
		void shouldLeaveNoEntityWhenScopePlacementRefused(@Nonnull Evita evita) {
			evita.updateCatalog(TEST_CATALOG, session -> {
				assertEquals(placedIn(scope(L), "inScope"), assertThrowsExactly(
					EvitaInvalidUsageException.class, () -> session.upsertAndFetchEntity(
						session.createNewEntity(ENTITY_OWNER, 80).setReference(REF_ITEM, 1),
						referenceContent(REF_ITEM, filterBy(inScope(L, scope(L))))
					)
				).getMessage());
				assertTrue(session.getEntity(ENTITY_OWNER, 80).isEmpty());
			});
		}
	}

	/** Queries owner 60 and returns the target keys retained by the reference row filter. */
	@Nonnull
	private List<Integer> queryRows(@Nonnull EvitaSessionContract session, @Nonnull FilterBy filter) {
		final ReferenceContent content = referenceContent(REF_ITEM, filter);
		return rows(session.queryList(query(collection(ENTITY_OWNER), filterBy(entityPrimaryKeyInSet(60)),
			require(entityFetch(content))), SealedEntity.class).getFirst());
	}

	/** Returns sorted keys so target iteration order does not affect the scope assertion. */
	@Nonnull
	private List<Integer> rows(@Nonnull SealedEntity entity) {
		return entity.getReferences(REF_ITEM).stream()
			.map(ReferenceContract::getReferencedPrimaryKey).sorted().toList();
	}
}
