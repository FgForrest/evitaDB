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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper.GlobalIndexAndFormula;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper.GroupRowLookup;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Pins that the matching groups' reduced group indexes of one `groupHaving` are resolved once per plan, however many
 * times the translation asks for them.
 *
 * Index discovery, the per-index pass and - on the filtered `referenceContent` fetch path - every single reduced
 * index ask for the same lookup, each time through a freshly built key: the fetch path re-translates its filter per
 * index and `histogramHaving` rewrites itself on every translation. A memo compared by the identity of a rebuilt
 * object never hits, answers correctly and silently costs the matching groups' index resolution once per call - so
 * no result-based test can see it. These tests count the resolutions instead.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Group row lookup memo")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(FILTER)
class GroupRowLookupMemoTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "parameterValues";
	/** The groups the nested query matches. */
	private static final int[] MATCHING_GROUPS = {1, 2};

	private FilterByVisitor visitor;
	private EntitySchemaContract entitySchema;
	private ReferenceSchemaContract referenceSchema;
	private Formula matchingGroups;

	/**
	 * Returns a mocked reference schema with the passed name.
	 *
	 * @param name name of the reference
	 * @return the schema
	 */
	@Nonnull
	private static ReferenceSchemaContract referenceSchema(@Nonnull String name) {
		final ReferenceSchemaContract schema = mock(ReferenceSchemaContract.class);
		when(schema.getName()).thenReturn(name);
		return schema;
	}

	/**
	 * Returns a mocked nested group query that matches {@link #MATCHING_GROUPS}.
	 *
	 * @return the formula
	 */
	@Nonnull
	private static Formula nestedGroupQuery() {
		final Formula formula = mock(Formula.class);
		when(formula.compute()).thenAnswer(invocation -> new BaseBitmap(MATCHING_GROUPS));
		return formula;
	}

	/**
	 * Returns a mocked reduced group index of the passed scope holding rows of the passed targets.
	 *
	 * @param scope   scope of the owners
	 * @param targets referenced entities the index holds rows for
	 * @return the index
	 */
	@Nonnull
	private static ReducedGroupEntityIndex groupIndex(@Nonnull Scope scope, @Nonnull Set<Integer> targets) {
		final RepresentativeReferenceKey key = new RepresentativeReferenceKey(
			new ReferenceKey(REFERENCE_NAME, 1), new Serializable[0]
		);
		final ReducedGroupEntityIndex index = mock(ReducedGroupEntityIndex.class);
		when(index.getIndexKey()).thenReturn(new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY, scope, key));
		when(index.getRepresentativeReferenceKey()).thenReturn(key);
		when(index.getReferencedEntityPrimaryKeys()).thenReturn(targets);
		return index;
	}

	/**
	 * Asks for the lookup the way a translation does - with a freshly built nested result wrapping the passed formula.
	 *
	 * @param reference reference schema to ask for
	 * @param formula   the nested group query
	 * @return the lookup
	 */
	@Nonnull
	private GroupRowLookup lookup(@Nonnull ReferenceSchemaContract reference, @Nonnull Formula formula) {
		return HavingTranslatorHelper.getGroupRowLookup(
			this.visitor, this.entitySchema, reference,
			new GlobalIndexAndFormula(mock(GlobalEntityIndex.class), formula)
		);
	}

	/**
	 * Sets the scopes the visitor's processing scope reports.
	 *
	 * @param scopes the scopes
	 */
	private void processInScopes(@Nonnull Set<Scope> scopes) {
		final ProcessingScope<?> processingScope = mock(ProcessingScope.class);
		when(processingScope.getScopes()).thenReturn(scopes);
		doReturn(processingScope).when(this.visitor).getProcessingScope();
	}

	@BeforeEach
	void setUp() {
		this.visitor = mock(FilterByVisitor.class);
		// the real memo over an uninitialized context: it creates its map on first use
		final QueryPlanningContext queryContext = mock(
			QueryPlanningContext.class, withSettings().defaultAnswer(CALLS_REAL_METHODS)
		);
		when(this.visitor.getQueryContext()).thenReturn(queryContext);
		processInScopes(EnumSet.of(Scope.LIVE));
		when(this.visitor.getReferencedGroupEntityIndexes(any(), any(), anyInt()))
			.thenAnswer(invocation -> Stream.of(groupIndex(Scope.LIVE, Set.of(invocation.getArgument(2, Integer.class)))));
		this.entitySchema = mock(EntitySchemaContract.class);
		when(this.entitySchema.getName()).thenReturn(ENTITY_TYPE);
		this.referenceSchema = referenceSchema(REFERENCE_NAME);
		this.matchingGroups = nestedGroupQuery();
	}

	@Test
	@DisplayName("a key rebuilt by every call still hits - the groups are resolved once")
	void shouldResolveTheMatchingGroupsOnceForRebuiltKeys() {
		final GroupRowLookup first = lookup(this.referenceSchema, this.matchingGroups);
		for (int i = 0; i < 99; i++) {
			assertSame(first, lookup(referenceSchema(REFERENCE_NAME), this.matchingGroups));
		}
		verify(this.visitor, times(MATCHING_GROUPS.length)).getReferencedGroupEntityIndexes(any(), any(), anyInt());
		verify(this.matchingGroups, times(1)).compute();
	}

	@Test
	@DisplayName("another nested group query is another lookup")
	void shouldBuildAnotherLookupForAnotherNestedQuery() {
		assertNotSame(lookup(this.referenceSchema, this.matchingGroups), lookup(this.referenceSchema, nestedGroupQuery()));
		verify(this.visitor, times(2 * MATCHING_GROUPS.length)).getReferencedGroupEntityIndexes(any(), any(), anyInt());
	}

	@Test
	@DisplayName("another reference is another lookup")
	void shouldBuildAnotherLookupForAnotherReference() {
		assertNotSame(
			lookup(this.referenceSchema, this.matchingGroups),
			lookup(referenceSchema("parameterVariants"), this.matchingGroups)
		);
	}

	@Test
	@DisplayName("other processing scopes are another lookup")
	void shouldBuildAnotherLookupForOtherScopes() {
		final GroupRowLookup live = lookup(this.referenceSchema, this.matchingGroups);
		processInScopes(EnumSet.allOf(Scope.class));
		assertNotSame(live, lookup(this.referenceSchema, this.matchingGroups));
	}

	@Test
	@DisplayName("the targets of a scope are the union of its group indexes, built once")
	void shouldUniteTheTargetsOfAScopeOnce() {
		final ReducedGroupEntityIndex first = groupIndex(Scope.LIVE, Set.of(1, 2));
		final ReducedGroupEntityIndex second = groupIndex(Scope.LIVE, Set.of(2, 3));
		final ReducedGroupEntityIndex archived = groupIndex(Scope.ARCHIVED, Set.of(7));
		final Map<Integer, ReducedGroupEntityIndex> byGroup = Map.of(1, first, 2, second);
		when(this.visitor.getReferencedGroupEntityIndexes(any(), any(), anyInt()))
			.thenAnswer(invocation -> {
				final int group = invocation.getArgument(2, Integer.class);
				return group == 1 ? Stream.of(byGroup.get(1), archived) : Stream.of(byGroup.get(group));
			});
		final GroupRowLookup lookup = lookup(this.referenceSchema, this.matchingGroups);

		assertArrayEquals(new int[]{1, 2, 3}, lookup.getTargetsInScope(Scope.LIVE).toArray());
		assertSame(lookup.getTargetsInScope(Scope.LIVE), lookup.getTargetsInScope(Scope.LIVE));
		assertArrayEquals(new int[]{7}, lookup.getTargetsInScope(Scope.ARCHIVED).toArray());
		verify(first, times(1)).getReferencedEntityPrimaryKeys();
		verify(second, times(1)).getReferencedEntityPrimaryKeys();
	}

	@Test
	@DisplayName("a scope without group indexes has no targets and leaves the shared empty lookup untouched")
	void shouldAnswerNoTargetsForAScopeWithoutGroupIndexes() {
		assertArrayEquals(new int[0], GroupRowLookup.EMPTY.getTargetsInScope(Scope.LIVE).toArray());
		assertNotSame(GroupRowLookup.EMPTY.getTargetsInScope(Scope.LIVE), GroupRowLookup.EMPTY.getTargetsInScope(Scope.LIVE));
	}

}
