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
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.VirtualRowConsumer;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.virtual.DifferentialEngines.Divergence;
import io.evitadb.index.virtual.ReadRouteActivation.LineConsumption;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;

import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link DifferentialEngines} compares what it claims to: an identical history leaves the two engines
 * equal at every checkpoint, one extra write in one engine is found at the query level, at the index level and in
 * what the open session enrolls, a difference that exists only inside an open transaction is found there even though
 * the commit would never show it, and differences only a complete comparison of an attribute index can see - other
 * sort blocks over the same order, values swapped in a unique index, a predecessor the chain order hides - are found.
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
	/**
	 * A localized attribute unique across all locales the unique-swap test adds to the products.
	 */
	private static final String ATTRIBUTE_URL = "url";
	/**
	 * A chained attribute the chain test adds to the products.
	 */
	private static final String ATTRIBUTE_CHAIN = "chain";
	/**
	 * A product primary key no product of the fixture has.
	 */
	private static final int MISSING_PRODUCT = 99;
	/**
	 * The query lines of {@link DifferentialEngines} whose routes answer virtual partitions - each must read virtual
	 * rows over the fixture of every read route.
	 */
	private static final List<String> VIRTUAL_LINES = List.of(
		DifferentialEngines.LINE_REFERENCE_HAVING_TARGET,
		DifferentialEngines.LINE_REFERENCE_HAVING_NOT,
		DifferentialEngines.LINE_REFERENCE_HAVING_GROUP,
		DifferentialEngines.LINE_REFERENCE_HAVING_LOCALE,
		DifferentialEngines.LINE_REFERENCE_HAVING_ATTRIBUTE,
		DifferentialEngines.LINE_BIDIRECTIONAL,
		DifferentialEngines.LINE_REFERENCE_CONTENT_FILTER,
		DifferentialEngines.LINE_REFERENCE_CONTENT_CHAIN,
		DifferentialEngines.LINE_HIERARCHY_WITHIN,
		DifferentialEngines.LINE_HIERARCHY_WITHIN_ROOT,
		DifferentialEngines.LINE_HIERARCHY_STATISTICS,
		DifferentialEngines.LINE_PICK_FIRST_INDEX,
		DifferentialEngines.LINE_PICK_FIRST_PREFETCH,
		DifferentialEngines.LINE_PICK_FIRST_REFERENCED_KEY,
		DifferentialEngines.LINE_TRAVERSE_ATTRIBUTE,
		DifferentialEngines.LINE_TRAVERSE_CHAIN,
		DifferentialEngines.LINE_TRAVERSE_REFERENCED_KEY,
		DifferentialEngines.LINE_TRAVERSE_ATTRIBUTE_SET_EXACT,
		DifferentialEngines.LINE_HIERARCHY_TRAVERSE
	);

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
		// the harness checks the registry of the maintaining engine against its partitions at every checkpoint
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
		assertContains(snapshot, "query/product/LIVE/referenceContent/gallery/1");
		assertContains(snapshot, "query/product/ARCHIVED/referenceContent/categories/5");
		assertContains(snapshot, "query/product/LIVE/facetSummary/");
		assertContains(snapshot, "query/product/LIVE/attributeHistograms/");
		assertContains(snapshot, "query/product/LIVE/referenceHistograms/");
		assertContains(snapshot, "index/product/LIVE/REFERENCED_ENTITY_TYPE/categories/target/11");
		assertContains(snapshot, "index/product/LIVE/REFERENCED_ENTITY_TYPE/gallery/partition/");
		assertContains(snapshot, "index/product/ARCHIVED/REFERENCED_ENTITY_TYPE/categories/partition/");
		assertContains(snapshot, "index/product/LIVE/membership/categories/covered");
		assertContains(snapshot, "index/product/LIVE/global/sort/");
		assertContains(snapshot, "index/product/LIVE/REFERENCED_ENTITY_TYPE/categories/cardinality/partitions");
		assertContains(snapshot, "index/product/LIVE/REFERENCED_ENTITY_TYPE/categories/cardinality/attribute/");
		assertContains(snapshot, "storage/product/part/");
		assertContains(snapshot, "storage/product/population");
		// the landed query lines of the maintaining engine read virtual rows - since its restart, which starts the count
		// afresh - rather than comparing the object reading with itself
		assertTrue(
			((Catalog) this.engines.getMaintaining().getCatalogInstance(CATALOG).orElseThrow())
				.getVirtualReferencePartitionMaintenance()
				.getVirtualRowsRead() > 0,
			"The maintaining engine must read virtual rows on the landed query lines"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("every route line reads virtual rows at every consuming stage it declares and leaves the engines equal")
	void shouldReadVirtualRowsOnEveryRouteLine(CatalogState state) {
		this.engines = new DifferentialEngines(this, "everyRoute", EVERY_ROUTE_ENTITY_TYPES);
		this.engines.prepare(state, SingleOwnerPartitionFixture::setUpWithEveryReadRoute);

		// a new single-owner partition, a second owner joining it, an archived owner and a restart - every checkpoint
		// runs every query line on both engines
		this.engines.step(
			"a product joins a new category",
			updateProduct(4, it -> it.setReference(REFERENCE_CATEGORIES, 11))
		);
		this.engines.step(
			"a second product joins it",
			updateProduct(6, it -> it.setReference(REFERENCE_CATEGORIES, 11))
		);
		// product 5 shares no chained partition - archiving the second owner of a shared partition carrying a
		// `ReferencedEntityPredecessor` fails with the switch off as well
		this.engines.step("a product moves to the archive", session -> session.archiveEntity(ENTITY_PRODUCT, 5));
		this.engines.restart();

		final SortedMap<String, String> snapshot = this.engines.referenceSnapshot();
		assertContains(snapshot, "query/product/LIVE/referenceHaving/sections/groupHaving");
		assertContains(snapshot, "query/product/LIVE/referenceHaving/categories/locale/de");
		assertContains(snapshot, "query/category/LIVE/bidirectional/products/" + ATTRIBUTE_ORDER);
		assertContains(snapshot, "query/product/LIVE/hierarchyWithin/sections/" + SHARED_SECTION);
		assertContains(snapshot, "query/product/LIVE/hierarchyStatistics/sections/");
		assertContains(snapshot, "query/product/LIVE/order/categories/pickFirst/" + ATTRIBUTE_ORDER + "/prefetch");
		assertContains(snapshot, "query/product/LIVE/order/sections/traverse/" + ATTRIBUTE_POSITION);
		assertContains(snapshot, "query/product/LIVE/referenceContent/sections/chain/" + ATTRIBUTE_SEQUENCE + "/");
		assertContains(
			snapshot, "query/product/LIVE/order/sections/hierarchyWithinRoot/traverse/" + ATTRIBUTE_PRIORITY
		);
		// every line of a route that answers virtual partitions must have read some of them - the entity bodies, the
		// facet summary and the histograms read nothing a virtual partition answers
		final SortedMap<String, Long> rowsPerLine = this.engines.getVirtualRowsReadPerLine();
		final List<String> unread = new ArrayList<>(VIRTUAL_LINES.size());
		for (final String line : VIRTUAL_LINES) {
			if (rowsPerLine.getOrDefault(line, 0L) <= 0L) {
				unread.add(line);
			}
		}
		assertTrue(
			unread.isEmpty(),
			"Every route line must read virtual rows in the maintaining engine, these read none: " + unread +
				" (all lines: " + rowsPerLine + ")"
		);
		// the aggregate count of a line is earned by its first virtual stage alone - a gather reading partitions
		// virtually earns it even when the claims, values or providers consuming them resolve their objects again - so
		// every consuming stage a declared route of a line is counted by must have answered virtual rows itself
		final SortedMap<String, LineConsumption> consumptionPerLine = this.engines.getConsumptionPerLine();
		final EnumSet<VirtualRowConsumer> asserted = EnumSet.noneOf(VirtualRowConsumer.class);
		final List<String> idle = new ArrayList<>(VIRTUAL_LINES.size());
		for (final String line : VIRTUAL_LINES) {
			final LineConsumption consumption = consumptionPerLine.get(line);
			assertNotNull(consumption, "The line `" + line + "` never ran");
			asserted.addAll(consumption.getRequiredConsumers());
			if (!consumption.getIdleConsumers().isEmpty()) {
				idle.add(line + " " + consumption.getIdleConsumers());
			}
		}
		assertTrue(
			idle.isEmpty(),
			"Every consuming stage of a declared route must answer virtual rows in the maintaining engine, these " +
				"answered none: " + idle + " (all lines: " + consumptionPerLine + ")"
		);
		assertEquals(
			EnumSet.allOf(VirtualRowConsumer.class), asserted,
			"Every consuming stage must be asserted by a line of a route it counts"
		);
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
		assertFound(divergence.inSession(), "differs: enrollment/product");
		assertFound(divergence.afterClose(), ONLY_IN_MAINTAINING + "query/product/LIVE/entity/" + EXTRA_PRODUCT);
		assertFound(divergence.afterClose(), ONLY_IN_MAINTAINING + "index/product/LIVE/REFERENCED_ENTITY_TYPE/");
		assertFound(divergence.afterClose(), "differs: storage/product/");
	}

	@Test
	@DisplayName("the same records split into other blocks over the same values are found in the sort index")
	void shouldFindOtherSortBlocks() {
		this.engines = new DifferentialEngines(this, "sortBlocks", ENTITY_TYPES);
		this.engines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUp);

		// both engines order products 1 to 6 alike over the ranks 10, 30, 40, 50 and 60 - product 2 merely joins the
		// block of product 1 in one engine and the block of product 3 in the other
		final Divergence divergence = this.engines.divergentSession(
			updateProduct(2, it -> it.setAttribute(ATTRIBUTE_RANK, 10)),
			updateProduct(2, it -> it.setAttribute(ATTRIBUTE_RANK, 30)),
			false
		);

		assertFound(divergence.inSession(), "differs: index/product/LIVE/global/sort/");
		assertFound(divergence.afterClose(), "differs: index/product/LIVE/global/sort/");
	}

	@Test
	@DisplayName("values swapped between two records of a standalone unique index are found in its mapping")
	void shouldFindValuesSwappedInAStandaloneUniqueIndex() {
		this.engines = new DifferentialEngines(this, "uniqueSwap", ENTITY_TYPES);
		this.engines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUp);
		// a localized attribute unique across all locales is kept by a unique index of its own, not by a filter index
		this.engines.step(
			"products get a localized unique url",
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.unique().localized())
					.updateVia(session);
				updateProduct(1, it -> it.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/one")).accept(session);
				updateProduct(2, it -> it.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/two")).accept(session);
			}
		);

		final Divergence divergence = this.engines.divergentSession(
			session -> { },
			session -> {
				updateProduct(1, it -> it.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/swapping")).accept(session);
				updateProduct(2, it -> it.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/one")).accept(session);
				updateProduct(1, it -> it.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/two")).accept(session);
			},
			false
		);

		for (final List<String> differences : List.of(divergence.inSession(), divergence.afterClose())) {
			final List<String> uniqueDifferences = differences.stream()
				.filter(it -> it.startsWith("differs: index/product/LIVE/global/unique/"))
				.toList();
			assertEquals(1, uniqueDifferences.size(), String.join("\n", differences));
			assertTrue(
				uniqueDifferences.get(0).contains("/mapping"),
				"Only the mapping may tell the swap apart, the records and the value count are the same: " +
					uniqueDifferences
			);
		}
	}

	@Test
	@DisplayName("a predecessor that changes while the chain order does not is found")
	void shouldFindAPredecessorTheChainOrderDoesNotShow() {
		this.engines = new DifferentialEngines(this, "chainPredecessor", ENTITY_TYPES);
		this.engines.prepare(CatalogState.ALIVE, SingleOwnerPartitionFixture::setUp);
		this.engines.step(
			"products 1 to 3 are chained",
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withAttribute(ATTRIBUTE_CHAIN, Predecessor.class, AttributeSchemaEditor::sortable)
					.updateVia(session);
				updateProduct(1, it -> it.setAttribute(ATTRIBUTE_CHAIN, Predecessor.HEAD)).accept(session);
				updateProduct(2, it -> it.setAttribute(ATTRIBUTE_CHAIN, new Predecessor(1))).accept(session);
				updateProduct(3, it -> it.setAttribute(ATTRIBUTE_CHAIN, new Predecessor(2))).accept(session);
			}
		);

		// product 3 then points at a product that does not exist, which still leaves it right after product 2
		final Divergence divergence = this.engines.divergentSession(
			session -> { },
			updateProduct(3, it -> it.setAttribute(ATTRIBUTE_CHAIN, new Predecessor(MISSING_PRODUCT))),
			false
		);

		for (final List<String> differences : List.of(divergence.inSession(), divergence.afterClose())) {
			final List<String> chainDifferences = differences.stream()
				.filter(it -> it.startsWith("differs: index/product/LIVE/global/chain/"))
				.toList();
			assertEquals(1, chainDifferences.size(), String.join("\n", differences));
			assertTrue(
				chainDifferences.get(0).contains("/predecessors"),
				"Only the predecessors may tell the change apart, the order is the same: " + chainDifferences
			);
		}
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
