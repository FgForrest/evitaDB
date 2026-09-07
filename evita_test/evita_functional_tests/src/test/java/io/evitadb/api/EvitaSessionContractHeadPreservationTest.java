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

package io.evitadb.api;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.data.SealedEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SESSION;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The `queryOneSealedEntity` / `queryListOfSealedEntities` / `querySealedEntity` convenience methods of
 * {@link EvitaSessionContract} inject an `entityFetch` requirement when the caller did not supply one, and do so by
 * rebuilding the query. The rebuild reads the header through {@link Query#getCollection()}, which extracts only the
 * {@link Collection} constraint — so every other head constraint, {@link Label} included, is discarded before the
 * query ever reaches an implementation.
 *
 * Because these are `default` methods on the contract, the loss happens on the embedded session just as it does on
 * the remote driver. The tests below drive each method through a mock that captures the query the default method
 * actually delegates, and assert that the header arrived intact.
 *
 * See issue #1507.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EvitaSessionContract — head preservation in entityFetch-injecting defaults")
@Tag(CONTRACT)
@Tag(SESSION)
@Tag(QUERY)
class EvitaSessionContractHeadPreservationTest {
	private static final String ENTITY_TYPE = "PRODUCT";
	private static final String LABEL_NAME = "rest_method";
	private static final String LABEL_VALUE = "CartController.updateCartByOperation";

	/**
	 * Query whose header carries a label and which defines no `require` section at all — the first rebuilding
	 * branch of each convenience method.
	 */
	@Nonnull
	private static Query labelledQueryWithoutRequire() {
		return query(
			head(
				collection(ENTITY_TYPE),
				label(LABEL_NAME, LABEL_VALUE)
			),
			filterBy(entityPrimaryKeyInSet(1))
		);
	}

	/**
	 * Query whose header carries a label and whose `require` section exists but contains no `entityFetch` — the
	 * second rebuilding branch of each convenience method.
	 */
	@Nonnull
	private static Query labelledQueryWithoutEntityFetch() {
		return query(
			head(
				collection(ENTITY_TYPE),
				label(LABEL_NAME, LABEL_VALUE)
			),
			filterBy(entityPrimaryKeyInSet(1)),
			require(page(1, 5))
		);
	}

	/**
	 * Query that already satisfies the `entityFetch` requirement — no rebuild happens and the instance must be
	 * handed over untouched.
	 */
	@Nonnull
	private static Query labelledQueryWithEntityFetch() {
		return query(
			head(
				collection(ENTITY_TYPE),
				label(LABEL_NAME, LABEL_VALUE)
			),
			filterBy(entityPrimaryKeyInSet(1)),
			require(entityFetch())
		);
	}

	/**
	 * Builds a session mock whose `default` methods run for real, stubs the three abstract query methods to capture
	 * the {@link Query} they receive, runs `invocation` against it and returns whatever was captured.
	 *
	 * @param invocation the convenience method to exercise on the contract
	 * @return the query the default method delegated to the abstract query method
	 */
	@Nonnull
	private static Query captureDelegatedQuery(@Nonnull Consumer<EvitaSessionContract> invocation) {
		final AtomicReference<Query> captured = new AtomicReference<>();
		final EvitaSessionContract session = mock(EvitaSessionContract.class, CALLS_REAL_METHODS);

		doAnswer(it -> {
			captured.set(it.getArgument(0));
			return Optional.empty();
		}).when(session).queryOne(any(Query.class), eq(SealedEntity.class));
		doAnswer(it -> {
			captured.set(it.getArgument(0));
			return List.of();
		}).when(session).queryList(any(Query.class), eq(SealedEntity.class));
		doAnswer(it -> {
			captured.set(it.getArgument(0));
			return null;
		}).when(session).query(any(Query.class), eq(SealedEntity.class));

		invocation.accept(session);

		return requireNonNull(captured.get(), "the convenience method never delegated to a query method");
	}

	/**
	 * Asserts that the rebuilt query still targets the original collection AND still carries the original label.
	 * The collection half guards the opposite mistake — a merge that forgets the collection while keeping the label.
	 */
	private static void assertHeaderSurvived(@Nonnull Query rebuiltQuery) {
		final Collection collection = rebuiltQuery.getCollection();
		assertNotNull(collection, () -> "the collection was lost during the rebuild: " + rebuiltQuery);
		assertEquals(ENTITY_TYPE, collection.getEntityType());

		final List<Label> labels = QueryUtils.findConstraints(
			requireNonNull(rebuiltQuery.getHead(), () -> "the whole head was lost during the rebuild"),
			Label.class
		);
		assertEquals(1, labels.size(), () -> "the head label was dropped during the rebuild: " + rebuiltQuery);
		assertEquals(LABEL_NAME, labels.get(0).getLabelName());
		assertEquals(LABEL_VALUE, labels.get(0).getLabelValue());
	}

	@Test
	@DisplayName("queryOneSealedEntity should keep head labels when the query defines no require")
	void shouldKeepHeadLabelsInQueryOneSealedEntityWithoutRequire() {
		assertHeaderSurvived(
			captureDelegatedQuery(session -> session.queryOneSealedEntity(labelledQueryWithoutRequire()))
		);
	}

	@Test
	@DisplayName("queryOneSealedEntity should keep head labels when the require carries no entityFetch")
	void shouldKeepHeadLabelsInQueryOneSealedEntityWithoutEntityFetch() {
		assertHeaderSurvived(
			captureDelegatedQuery(session -> session.queryOneSealedEntity(labelledQueryWithoutEntityFetch()))
		);
	}

	@Test
	@DisplayName("queryListOfSealedEntities should keep head labels when the query defines no require")
	void shouldKeepHeadLabelsInQueryListOfSealedEntitiesWithoutRequire() {
		assertHeaderSurvived(
			captureDelegatedQuery(session -> session.queryListOfSealedEntities(labelledQueryWithoutRequire()))
		);
	}

	@Test
	@DisplayName("queryListOfSealedEntities should keep head labels when the require carries no entityFetch")
	void shouldKeepHeadLabelsInQueryListOfSealedEntitiesWithoutEntityFetch() {
		assertHeaderSurvived(
			captureDelegatedQuery(session -> session.queryListOfSealedEntities(labelledQueryWithoutEntityFetch()))
		);
	}

	@Test
	@DisplayName("querySealedEntity should keep head labels when the query defines no require")
	void shouldKeepHeadLabelsInQuerySealedEntityWithoutRequire() {
		assertHeaderSurvived(
			captureDelegatedQuery(session -> session.querySealedEntity(labelledQueryWithoutRequire()))
		);
	}

	@Test
	@DisplayName("querySealedEntity should keep head labels when the require carries no entityFetch")
	void shouldKeepHeadLabelsInQuerySealedEntityWithoutEntityFetch() {
		assertHeaderSurvived(
			captureDelegatedQuery(session -> session.querySealedEntity(labelledQueryWithoutEntityFetch()))
		);
	}

	@Test
	@DisplayName("A query that already defines entityFetch must be delegated untouched")
	void shouldDelegateQueryWithEntityFetchUntouched() {
		final Query original = labelledQueryWithEntityFetch();
		assertSame(
			original,
			captureDelegatedQuery(session -> session.queryListOfSealedEntities(original))
		);
	}

}
