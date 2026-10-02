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

package io.evitadb.index.virtual;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.index.virtual.DifferentialEngines.Divergence;
import io.evitadb.index.virtual.VirtualReferencePartitionOracle.Mode;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.SortedMap;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link DifferentialEngines} compares what it claims to: an identical history leaves the two engines
 * equal at every checkpoint, one extra write in one engine is found at the query level and at the index level, and a
 * difference that exists only inside an open transaction is found there even though the commit would never show it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Differential engines")
@Tag(TEST_HARNESS)
@Tag(INDEXING)
@Tag(REFERENCE)
class DifferentialEnginesTest implements EvitaTestSupport {
	/**
	 * The product the divergence tests write into one engine only.
	 */
	private static final int EXTRA_PRODUCT = 50;
	/**
	 * How a difference present only in the maintaining engine starts.
	 */
	private static final String ONLY_IN_MAINTAINING = "only in the maintaining engine: ";

	private DifferentialEngines engines;

	@AfterEach
	void tearDown() {
		if (this.engines != null) {
			this.engines.close();
			this.engines = null;
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("an identical history leaves the engines equal at every checkpoint, failures and restart included")
	void shouldFindIdenticalHistoriesEqual(CatalogState state) {
		this.engines = new DifferentialEngines(this, "identicalHistory", ENTITY_TYPES);
		this.engines.addMaintainingEngineCheck(
			(label, collection) -> VirtualReferencePartitionOracle.assertConsistent(label, collection, Mode.SUBSET)
		);
		this.engines.prepare(state, SingleOwnerPartitionFixture::setUp);

		// a new single-owner partition, then a second owner joining it
		this.engines.step(
			"a product joins a new category", updateProduct(1, it -> it.setReference(REFERENCE_CATEGORIES, 11))
		);
		this.engines.step(
			"a second product joins it", updateProduct(2, it -> it.setReference(REFERENCE_CATEGORIES, 11))
		);
		// several boundaries inside one transaction, compared after each of them
		this.engines.transaction(
			"several boundaries in one session",
			List.of(
				updateProduct(3, it -> it.removeReference(REFERENCE_CATEGORIES, 3)),
				updateProduct(2, it -> it.removeReference(REFERENCE_CATEGORIES, 11)),
				updateProduct(4, it -> withMedia(it, 4, ROLE_THUMBNAIL)),
				updateProduct(4, it -> it.setAttribute(ATTRIBUTE_RANK, 1))
			)
		);
		this.engines.step("a product moves to the archive", session -> session.archiveEntity(ENTITY_PRODUCT, 5));
		this.engines.step("a product is removed", session -> session.deleteEntity(ENTITY_PRODUCT, 6));
		this.engines.failingStep(
			"a product reusing a unique code is refused",
			session -> session.upsertEntity(
				newProduct(session, 7).setAttribute(ATTRIBUTE_CODE, "P1")
			),
			UniqueValueViolationException.class
		);
		this.engines.restart();
		this.engines.step(
			"a product is added after the restart", session -> session.upsertEntity(newProduct(session, 8))
		);

		// the comparison must have had something to compare, at every level it claims
		final SortedMap<String, String> snapshot = this.engines.referenceSnapshot();
		assertContains(snapshot, "query/product/LIVE/entity/1");
		assertContains(snapshot, "query/product/ARCHIVED/entity/5");
		assertContains(snapshot, "query/product/LIVE/referenceHaving/categories/11");
		assertContains(snapshot, "query/product/LIVE/facetSummary/");
		assertContains(snapshot, "query/product/LIVE/attributeHistograms/");
		assertContains(snapshot, "query/product/LIVE/referenceHistograms/");
		assertContains(snapshot, "index/product/LIVE/REFERENCED_ENTITY_TYPE/categories/target/11");
		assertContains(snapshot, "index/product/LIVE/REFERENCED_ENTITY_TYPE/gallery/partition/");
		assertContains(snapshot, "index/product/ARCHIVED/REFERENCED_ENTITY_TYPE/categories/partition/");
		assertContains(snapshot, "index/product/LIVE/membership/categories/covered");
		assertContains(snapshot, "storage/product/part/");
		assertContains(snapshot, "storage/product/population");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("one extra write in the maintaining engine is found at the query and at the index level")
	void shouldFindAnExtraWriteInOneEngine(CatalogState state) {
		this.engines = new DifferentialEngines(this, "extraWrite", ENTITY_TYPES);
		this.engines.prepare(state, SingleOwnerPartitionFixture::setUp);

		final Divergence divergence = this.engines.divergentSession(
			session -> { },
			session -> session.upsertEntity(newProduct(session, EXTRA_PRODUCT)),
			false
		);

		assertFound(divergence.inSession(), ONLY_IN_MAINTAINING + "query/product/LIVE/entity/" + EXTRA_PRODUCT);
		assertFound(divergence.inSession(), ONLY_IN_MAINTAINING + "index/product/LIVE/REFERENCED_ENTITY_TYPE/");
		assertFound(divergence.afterClose(), ONLY_IN_MAINTAINING + "query/product/LIVE/entity/" + EXTRA_PRODUCT);
		assertFound(divergence.afterClose(), ONLY_IN_MAINTAINING + "index/product/LIVE/REFERENCED_ENTITY_TYPE/");
		assertFound(divergence.afterClose(), "differs: storage/product/");
	}

	@Test
	@DisplayName("a difference that exists only inside a rolled-back transaction is found inside it")
	void shouldFindADifferenceOnlyTheOpenTransactionShows() {
		this.engines = new DifferentialEngines(this, "transactionLocal", ENTITY_TYPES);
		this.engines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUp);

		final Divergence divergence = this.engines.divergentSession(
			session -> { },
			session -> session.upsertEntity(newProduct(session, EXTRA_PRODUCT)),
			true
		);

		assertFound(divergence.inSession(), ONLY_IN_MAINTAINING + "query/product/LIVE/entity/" + EXTRA_PRODUCT);
		assertFound(divergence.inSession(), ONLY_IN_MAINTAINING + "index/product/LIVE/REFERENCED_ENTITY_TYPE/");
		assertTrue(
			divergence.afterClose().isEmpty(),
			"The rollback must leave nothing to compare after the session, or this test proves nothing about the " +
				"checkpoint inside it: " + divergence.afterClose()
		);
	}

	/**
	 * Creates a step updating one product.
	 *
	 * @param primaryKey the product primary key
	 * @param change     the change to make
	 * @return the step
	 */
	@Nonnull
	private static Consumer<EvitaSessionContract> updateProduct(
		int primaryKey,
		@Nonnull Consumer<EntityBuilder> change
	) {
		return session -> {
			final EntityBuilder builder = session
				.getEntity(ENTITY_PRODUCT, primaryKey, entityFetchAllContent())
				.orElseThrow()
				.openForWrite();
			change.accept(builder);
			builder.upsertVia(session);
		};
	}

	/**
	 * Asserts the snapshot holds at least one line under the path prefix.
	 *
	 * @param snapshot the snapshot
	 * @param prefix   the path prefix
	 */
	private static void assertContains(@Nonnull SortedMap<String, String> snapshot, @Nonnull String prefix) {
		final String next = snapshot.tailMap(prefix).isEmpty() ? null : snapshot.tailMap(prefix).firstKey();
		assertTrue(
			next != null && next.startsWith(prefix),
			"The comparison holds no line under `" + prefix + "`, so it compares nothing there"
		);
	}

	/**
	 * Asserts some difference starts with the given text.
	 *
	 * @param differences the differences
	 * @param start       the text a difference must start with
	 */
	private static void assertFound(@Nonnull List<String> differences, @Nonnull String start) {
		assertFalse(differences.isEmpty(), "The engines must differ");
		assertTrue(
			differences.stream().anyMatch(it -> it.startsWith(start)),
			"No difference starts with `" + start + "`:\n" + String.join("\n", differences)
		);
	}
}
