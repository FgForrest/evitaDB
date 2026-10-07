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

package io.evitadb.core.collection;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.statistics.AttributeIndexType;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link IndexCardinalityProjection#describeIndex} - reached from `EntityCollection#describeIndex` and
 * from the `INDEX_CARDINALITY` statistic, neither of which takes a snapshot - describes every attribute index while a
 * warm-up writer files a new one into the entity index it is walking.
 *
 * The projection walks each attribute-index family with `forEach`, and outside a transaction a family is a `HashMap`
 * the warm-up writer adds to in place. The write is placed deterministically: the fixture index files a new attribute
 * from inside the sub-index lookup the projection makes for every key of the walk - the same point a concurrent
 * writer's store can land at. `HashMap#forEach` then throws `ConcurrentModificationException` after it has visited the
 * whole table.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Index cardinality under a concurrent warm-up write")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class IndexCardinalityConcurrentWriteTest {
	/**
	 * Entity type of the fixture index.
	 */
	private static final String ENTITY_TYPE = "product";
	/**
	 * Locales the fixture schema admits - none of its attributes is localized.
	 */
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH);
	/**
	 * First record id - above the autobox cache, as elsewhere in the index fixtures.
	 */
	private static final int FIRST_RECORD = 1_000;
	/**
	 * Records the fixture's first attribute of each family is filled for.
	 */
	private static final int RECORDS = 3;
	/**
	 * The record the warm-up write files its new attribute for.
	 */
	private static final int LATE_RECORD = FIRST_RECORD + RECORDS;

	/**
	 * The catalog the fixture schema belongs to.
	 */
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * Two attributes per family - one present from the start, one the warm-up write files mid-walk. The unique pair is
	 * non-localized and so folded into the shared filter tree, which is the shape every ordinary unique attribute
	 * takes.
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute("code", String.class, AttributeSchemaEditor::filterable)
		.withAttribute("lateCode", String.class, AttributeSchemaEditor::filterable)
		.withAttribute("rank", Integer.class, AttributeSchemaEditor::sortable)
		.withAttribute("lateRank", Integer.class, AttributeSchemaEditor::sortable)
		.withAttribute("ean", String.class, AttributeSchemaEditor::unique)
		.withAttribute("lateEan", String.class, AttributeSchemaEditor::unique)
		.toInstance();

	/**
	 * Resolves an attribute of the fixture schema.
	 *
	 * @param name name of the attribute
	 * @return its schema
	 */
	@Nonnull
	private static AttributeSchemaContract attribute(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	/**
	 * Describes the index and returns its readings of one family, keyed by attribute name.
	 *
	 * @param index the index to describe
	 * @param type  the family to return
	 * @return `(distinct values, records covered)` per attribute of that family
	 */
	@Nonnull
	private static Map<String, Reading> describe(@Nonnull WritingEntityIndex index, @Nonnull AttributeIndexType type) {
		final IndexCardinality cardinality = IndexCardinalityProjection.describeIndex(index.getIndexKey(), null, index);
		assertTrue(index.hasWritten(), "the write must have landed during the walk");
		return Arrays.stream(cardinality.attributes())
			.filter(it -> it.indexType() == type)
			.collect(
				Collectors.toMap(
					AttributeCardinality::attributeName,
					it -> new Reading(it.distinctValueCount(), it.recordsCovered())
				)
			);
	}

	@Test
	@DisplayName("describes every filter index, including the one filed while the family was walked")
	void shouldDescribeEveryFilterIndexWhenOneIsFiledMidWalk() {
		final WritingEntityIndex index = new WritingEntityIndex();
		for (int record = FIRST_RECORD; record < FIRST_RECORD + RECORDS; record++) {
			index.insertPrimaryKeyIfMissing(record);
			index.insertFilterAttribute(null, attribute("code"), ALLOWED_LOCALES, null, "c" + record, record, false);
		}
		index.armWrite(
			AttributeIndexType.FILTER,
			() -> index.insertFilterAttribute(
				null, attribute("lateCode"), ALLOWED_LOCALES, null, "late", LATE_RECORD, false
			)
		);

		assertEquals(
			Map.of("code", new Reading(RECORDS, RECORDS), "lateCode", new Reading(1, 1)),
			describe(index, AttributeIndexType.FILTER)
		);
	}

	@Test
	@DisplayName("describes every sort index, including the one filed while the family was walked")
	void shouldDescribeEverySortIndexWhenOneIsFiledMidWalk() {
		final WritingEntityIndex index = new WritingEntityIndex();
		for (int record = FIRST_RECORD; record < FIRST_RECORD + RECORDS; record++) {
			index.insertPrimaryKeyIfMissing(record);
			index.insertSortAttribute(null, attribute("rank"), ALLOWED_LOCALES, null, record, record);
		}
		index.armWrite(
			AttributeIndexType.SORT,
			() -> index.insertSortAttribute(null, attribute("lateRank"), ALLOWED_LOCALES, null, 1, LATE_RECORD)
		);

		assertEquals(
			Map.of("rank", new Reading(RECORDS, RECORDS), "lateRank", new Reading(1, 1)),
			describe(index, AttributeIndexType.SORT)
		);
	}

	@Test
	@DisplayName("describes every unique index, including the one filed while the family was walked")
	void shouldDescribeEveryUniqueIndexWhenOneIsFiledMidWalk() {
		final WritingEntityIndex index = new WritingEntityIndex();
		for (int record = FIRST_RECORD; record < FIRST_RECORD + RECORDS; record++) {
			index.insertPrimaryKeyIfMissing(record);
			index.insertFilterAttribute(null, attribute("ean"), ALLOWED_LOCALES, null, "e" + record, record, true);
		}
		index.armWrite(
			AttributeIndexType.UNIQUE,
			() -> index.insertFilterAttribute(
				null, attribute("lateEan"), ALLOWED_LOCALES, null, "late", LATE_RECORD, true
			)
		);

		assertEquals(
			Map.of("ean", new Reading(RECORDS, RECORDS), "lateEan", new Reading(1, 1)),
			describe(index, AttributeIndexType.UNIQUE)
		);
	}

	/**
	 * One attribute reading reduced to its two figures.
	 *
	 * @param distinctValueCount how many distinct values the index holds
	 * @param recordsCovered     how many records those values cover
	 */
	private record Reading(int distinctValueCount, int recordsCovered) {
	}

	/**
	 * A real global entity index that runs one armed warm-up write the first time the projection resolves a
	 * sub-index of the armed family - that is, from inside that family's walk rather than a neighbouring one. Only the
	 * three lookups the projection makes are intercepted, and each still answers from the real index after the write.
	 */
	private static final class WritingEntityIndex extends GlobalEntityIndex {
		/**
		 * The armed write, or null once it has run (or before it is armed).
		 */
		@Nullable private Runnable pendingWrite;
		/**
		 * The family whose lookup runs {@link #pendingWrite}.
		 */
		@Nullable private AttributeIndexType armedFamily;
		/**
		 * Whether the armed write has run.
		 */
		private boolean written;

		/**
		 * Creates an empty global index of the fixture's entity type.
		 */
		WritingEntityIndex() {
			super(1, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		}

		/**
		 * Arms the write to run on the next sub-index lookup of the given family.
		 *
		 * @param family the family whose walk the write must land in
		 * @param write  the warm-up write
		 */
		void armWrite(@Nonnull AttributeIndexType family, @Nonnull Runnable write) {
			this.armedFamily = family;
			this.pendingWrite = write;
		}

		/**
		 * Tells whether the armed write has run.
		 *
		 * @return true once it has
		 */
		boolean hasWritten() {
			return this.written;
		}

		@Nullable
		@Override
		public FilterIndex getFilterIndex(@Nonnull AttributeIndexKey lookupKey) {
			fireOnce(AttributeIndexType.FILTER);
			return super.getFilterIndex(lookupKey);
		}

		@Nullable
		@Override
		public SortIndex getSortIndex(@Nonnull AttributeIndexKey lookupKey) {
			fireOnce(AttributeIndexType.SORT);
			return super.getSortIndex(lookupKey);
		}

		@Nullable
		@Override
		public UniqueIndex getUniqueIndex(@Nonnull AttributeIndexKey lookupKey) {
			fireOnce(AttributeIndexType.UNIQUE);
			return super.getUniqueIndex(lookupKey);
		}

		/**
		 * Runs the armed write, once, when `family` is the armed one.
		 *
		 * @param family the family whose sub-index is being looked up
		 */
		private void fireOnce(@Nonnull AttributeIndexType family) {
			final Runnable write = this.pendingWrite;
			if (write != null && family == this.armedFamily) {
				this.pendingWrite = null;
				this.written = true;
				write.run();
			}
		}
	}

}
