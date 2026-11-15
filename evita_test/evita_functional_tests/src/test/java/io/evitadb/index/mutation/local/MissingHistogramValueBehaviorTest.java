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

package io.evitadb.index.mutation.local;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.MissingHistogramValueBehavior;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.expression.trigger.DefaultHistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.HistogramCapableEntityIndex;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.index.virtual.CatalogReloadTestSupport;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart.MissingReferenceBehavior;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.WAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the `server.missingHistogramValueBehavior` switch on the presence check of the reference-histogram
 * withdrawal: `REFUSE` fails a write whose withdrawal misses a value, `REPORT` logs the missing value at error level,
 * skips its withdrawal and lets the write go on.
 *
 * The missing value is manufactured directly - removed from the index through the engine's own objects - rather than
 * borrowed from an open defect, so the cells keep a missing value when those defects are fixed. Every cell sets the
 * behavior explicitly, so a changed default changes no cell. The log lines are captured on the logger of
 * {@link ReferenceIndexMutator} and kept only when they name the histogram of the cell, which no other test uses.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Missing reference-histogram value: refuse or report")
@Tag(INDEXING)
@Tag(HISTOGRAM)
class MissingHistogramValueBehaviorTest implements EvitaTestSupport {
	private static final String PRODUCT = "product";
	private static final String BRAND = "brand";
	private static final String BRAND_GROUP = "brandGroup";
	private static final String SHARE = "share";
	private static final int PRODUCT_PK = 1;
	private static final int OTHER_PRODUCT_PK = 2;
	private static final int BRAND_PK = 10;
	private static final int OTHER_BRAND_PK = 11;
	private static final int GROUP_PK = 100;
	/**
	 * The share the reference holds before the write under test.
	 */
	private static final int OLD_SHARE = 5;
	/**
	 * The share the write under test sets.
	 */
	private static final int NEW_SHARE = 7;
	/**
	 * The share of another product, which keeps the index written in the replay cells.
	 */
	private static final int OTHER_SHARE = 9;
	/**
	 * The share a write after the reported one sets.
	 */
	private static final int SUBSEQUENT_SHARE = 11;
	/**
	 * The key of the referenced-type index of the brand reference.
	 */
	private static final EntityIndexKey TYPE_KEY =
		new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, BRAND);
	/**
	 * The key of the group-type index of the brand reference.
	 */
	private static final EntityIndexKey GROUP_TYPE_KEY =
		new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, BRAND);
	/**
	 * The primary key of the reduced index of group 100 listed first.
	 */
	private static final int FIRST_REDUCED_INDEX_PK = 22;
	/**
	 * The primary key of the reduced index of group 100 listed second.
	 */
	private static final int SECOND_REDUCED_INDEX_PK = 23;
	/**
	 * A reduced-index primary key a group-type index of a real engine lists while its collection holds no index there.
	 */
	private static final int UNHELD_REDUCED_INDEX_PK = 1_000_000;
	/**
	 * The start of every message the withdrawal produces for a missing value, refused or reported.
	 */
	private static final String MISSING_VALUE_PREFIX = "Cannot withdraw histogram value";
	/**
	 * The sentence a reported missing value ends with.
	 */
	private static final String REPORT_SUFFIX =
		"The value is not withdrawn and the write continues, because `server.missingHistogramValueBehavior` is " +
			"`REPORT`.";
	/**
	 * The checkpoint interval that keeps the committed tail of the write-ahead log unpublished until the engine closes.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;

	/**
	 * The defaults and the copying of the setting itself, independent of any engine.
	 */
	@Nested
	@DisplayName("The setting")
	class ConfigurationTest {

		/**
		 * Both the options built without any value and the default constant refuse.
		 */
		@Test
		@DisplayName("Refuse by default")
		void shouldRefuseByDefault() {
			assertAll(
				() -> assertEquals(
					MissingHistogramValueBehavior.REFUSE, ServerOptions.DEFAULT_MISSING_HISTOGRAM_VALUE_BEHAVIOR
				),
				() -> assertEquals(
					MissingHistogramValueBehavior.REFUSE, new ServerOptions().missingHistogramValueBehavior()
				),
				() -> assertEquals(
					MissingHistogramValueBehavior.REFUSE,
					ServerOptions.builder().build().missingHistogramValueBehavior()
				)
			);
		}

		/**
		 * The builder sets `REPORT` and the copying builder keeps it.
		 */
		@Test
		@DisplayName("Keep the report behavior through the builder and its copy")
		void shouldKeepReportBehaviorWhenBuiltAndCopied() {
			final ServerOptions options = ServerOptions.builder()
				.missingHistogramValueBehavior(MissingHistogramValueBehavior.REPORT)
				.build();

			assertAll(
				() -> assertEquals(MissingHistogramValueBehavior.REPORT, options.missingHistogramValueBehavior()),
				() -> assertEquals(
					MissingHistogramValueBehavior.REPORT,
					ServerOptions.builder(options).build().missingHistogramValueBehavior()
				)
			);
		}

		/**
		 * The canonical constructor - the one the configuration file binds to - fills an absent value with the default.
		 */
		@Test
		@DisplayName("Fill an absent value with the default")
		void shouldUseDefaultWhenBehaviorIsNull() {
			final ServerOptions options = new ServerOptions(
				null, null, null,
				ServerOptions.DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS,
				ServerOptions.DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS,
				ServerOptions.DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY,
				ServerOptions.DEFAULT_DROP_COLLATION_KEYS_AFTER_SECONDS_OF_INACTIVITY,
				null, null, false, false, ServerOptions.DEFAULT_USAGE_STATISTICS_TRACKING, null
			);

			assertEquals(
				ServerOptions.DEFAULT_MISSING_HISTOGRAM_VALUE_BEHAVIOR, options.missingHistogramValueBehavior()
			);
		}
	}

	/**
	 * One write through a real engine in warm-up whose withdrawal misses its value, in both behaviors and for an
	 * ungrouped and a grouped reference - the whole path from the configuration to the withdrawal.
	 */
	@Nested
	@DisplayName("A write through the engine")
	class ThroughEvitaTest {

		/**
		 * The refusing engine fails the write whose withdrawal misses the old value, naming the owner, the histogram
		 * and the value, and logs no report.
		 *
		 * @param grouped whether the reference belongs to a group, so the value lives in the group's reduced index
		 */
		@ParameterizedTest(name = "grouped {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Refuse the write when the value to withdraw is missing")
		void shouldRefuseWriteWhenValueToWithdrawIsMissing(boolean grouped) {
			final String histogram = histogramName("refused", grouped);
			final TestPaths paths = createTestPaths("missingHistogramValueRefused_" + grouped);
			try (final Evita evita = new Evita(configuration(paths, MissingHistogramValueBehavior.REFUSE, null))) {
				prepareProductWithMissingValue(evita, grouped, histogram);

				final Outcome outcome = capture(histogram, () -> updateShare(evita, grouped, NEW_SHARE));

				final GenericEvitaInternalError error = missingValueError(outcome.failure());
				final String message = error.getMessage();
				assertAll(
					() -> assertTrue(message.startsWith(MISSING_VALUE_PREFIX), message),
					() -> assertTrue(message.contains("owner primary key " + PRODUCT_PK + ","), message),
					() -> assertTrue(message.contains("histogram `" + histogram + "`"), message),
					() -> assertTrue(message.contains("value " + OLD_SHARE + ","), message),
					() -> assertFalse(message.contains(REPORT_SUFFIX), message),
					() -> assertEquals(List.of(), outcome.reports())
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * The reporting engine completes the write and logs one error line identifying the missing value. The final
		 * add of the write then leaves the histogram holding the new value and not the old one.
		 *
		 * @param grouped whether the reference belongs to a group, so the value lives in the group's reduced index
		 */
		@ParameterizedTest(name = "grouped {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Report the missing value and complete the write")
		void shouldReportMissingValueAndCompleteWrite(boolean grouped) {
			final String histogram = histogramName("reported", grouped);
			final TestPaths paths = createTestPaths("missingHistogramValueReported_" + grouped);
			try (final Evita evita = new Evita(configuration(paths, MissingHistogramValueBehavior.REPORT, null))) {
				prepareProductWithMissingValue(evita, grouped, histogram);

				final Outcome outcome = capture(histogram, () -> updateShare(evita, grouped, NEW_SHARE));

				assertNull(outcome.failure(), () -> "The write must complete, but threw " + outcome.failure());
				assertEquals(1, outcome.reports().size(), () -> "One line per missing value: " + outcome.reports());
				final String line = outcome.reports().get(0);
				assertAll(
					() -> assertTrue(line.startsWith(MISSING_VALUE_PREFIX), line),
					() -> assertTrue(line.contains("entity type `" + PRODUCT + "`"), line),
					() -> assertTrue(line.contains("owner primary key " + PRODUCT_PK + ","), line),
					() -> assertTrue(line.contains("group " + (grouped ? GROUP_PK : null) + ","), line),
					() -> assertTrue(line.contains("histogram `" + histogram + "`"), line),
					() -> assertTrue(line.contains("value " + OLD_SHARE + ","), line),
					() -> assertTrue(line.endsWith(REPORT_SUFFIX), line),
					() -> assertTrue(
						holdsShare(evita, grouped, histogram, NEW_SHARE, PRODUCT_PK), "The new share must be added"
					),
					() -> assertTrue(
						lacksShare(evita, grouped, histogram, OLD_SHARE, PRODUCT_PK), "The old share must be gone"
					),
					() -> assertEquals(NEW_SHARE, readShare(evita), "The new share must be stored")
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	/**
	 * A reporting write that misses one of the values or one of the places it withdraws from, and must still withdraw
	 * everything else.
	 */
	@Nested
	@DisplayName("A write skipping only what is missing")
	class SkipsOnlyTheMissingValueTest {

		/**
		 * The reference holds the values 5, 6 and 7 of an array attribute and the index misses the middle one. The
		 * reporting write skips 6 only and withdraws 5 and 7, whatever order it walks them in, so after the write the
		 * owner holds the new value alone.
		 *
		 * @param grouped whether the reference belongs to a group, so the values live in the group's reduced index
		 */
		@ParameterizedTest(name = "grouped {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Withdraw the other values when one of them is missing")
		void shouldWithdrawOtherValuesWhenOneIsMissing(boolean grouped) {
			final String histogram = histogramName("sharesSkipped", grouped);
			final TestPaths paths = createTestPaths("missingHistogramValueSkipped_" + grouped);
			try (final Evita evita = new Evita(configuration(paths, MissingHistogramValueBehavior.REPORT, null))) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					defineSchema(session, grouped, histogram, Integer[].class);
					session.createNewEntity(PRODUCT, PRODUCT_PK)
						.setReference(BRAND, BRAND_PK, sharesSetter(grouped, new Integer[]{5, 6, 7}))
						.upsertVia(session);
				});
				removeShare(evita, grouped, histogram, 6, PRODUCT_PK);

				final Outcome outcome = capture(
					histogram,
					() -> evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(PRODUCT, PRODUCT_PK, entityFetchAllContent()).orElseThrow()
								.openForWrite()
								.setReference(BRAND, BRAND_PK, sharesSetter(grouped, new Integer[]{8}))
								.upsertVia(session);
						}
					)
				);

				assertNull(outcome.failure(), () -> "The write must complete, but threw " + outcome.failure());
				assertAll(
					() -> assertEquals(1, outcome.reports().size(), outcome.reports()::toString),
					() -> assertTrue(outcome.reports().get(0).contains("value 6,"), outcome.reports()::toString),
					() -> assertTrue(lacksShare(evita, grouped, histogram, 5, PRODUCT_PK), "5 must be withdrawn"),
					() -> assertTrue(lacksShare(evita, grouped, histogram, 6, PRODUCT_PK), "6 was never there"),
					() -> assertTrue(lacksShare(evita, grouped, histogram, 7, PRODUCT_PK), "7 must be withdrawn"),
					() -> assertTrue(holdsShare(evita, grouped, histogram, 8, PRODUCT_PK), "8 must be added")
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Group 100 lists two valid reduced indexes. The second holds the old share of product 1, the first misses it
		 * - a value-level miss, not a missing index - and both hold the same share of a neighbouring product. The
		 * reporting write withdraws the old share from the second index, logs one line naming the first, and the final
		 * add leaves the new share in both. A subsequent write then finds both indexes consistent and logs nothing,
		 * while the neighbour keeps its share in both throughout.
		 */
		@Test
		@DisplayName("Withdraw from the reduced index holding the value when another listed one misses it")
		void shouldWithdrawFromHoldingIndexWhenAnotherListedIndexMissesValue() {
			final String histogram = "sharesSplitAcrossGroupIndexes";
			final TestPaths paths = createTestPaths("missingHistogramValueOneOfTwoIndexes");
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final ReducedGroupEntityIndex missing = reducedGroupIndex(FIRST_REDUCED_INDEX_PK);
				final ReducedGroupEntityIndex holding = reducedGroupIndex(SECOND_REDUCED_INDEX_PK);
				final List<ReducedGroupEntityIndex> both = List.of(missing, holding);
				for (final ReducedGroupEntityIndex index : both) {
					index.insertHistogramValue(histogram, null, OLD_SHARE, OTHER_PRODUCT_PK, Integer.class, 0);
				}
				holding.insertHistogramValue(histogram, null, OLD_SHARE, PRODUCT_PK, Integer.class, 0);
				final ExecutorFixture fixture = createExecutorFixture(
					evita, true, histogram, MissingHistogramValueBehavior.REPORT,
					(indexes, indexesByPrimaryKey) -> {
						indexesByPrimaryKey.put(FIRST_REDUCED_INDEX_PK, missing);
						indexesByPrimaryKey.put(SECOND_REDUCED_INDEX_PK, holding);
						listGroupReducedIndexes(indexes);
					}
				);

				final EntityIndexLocalMutationExecutor reported = fixture.newExecutor();
				final Outcome outcome = capture(histogram, () -> fixture.changeShare(reported, NEW_SHARE));

				assertNull(outcome.failure(), () -> "The withdrawal must return, but threw " + outcome.failure());
				assertEquals(1, outcome.reports().size(), outcome.reports()::toString);
				final String line = outcome.reports().get(0);
				assertAll(
					() -> assertTrue(line.contains("reduced index primary key " + FIRST_REDUCED_INDEX_PK), line),
					() -> assertTrue(line.contains("listed group indexes 2"), line),
					() -> assertFalse(
						holds(holding, histogram, OLD_SHARE, PRODUCT_PK),
						"The index holding the old share must have lost it"
					)
				);

				fixture.finishRun(reported, NEW_SHARE);
				assertShareInBothIndexes(both, histogram, NEW_SHARE, "after the reported write");

				final EntityIndexLocalMutationExecutor subsequent = fixture.newExecutor();
				final Outcome next = capture(histogram, () -> {
					fixture.changeShare(subsequent, SUBSEQUENT_SHARE);
					fixture.finishRun(subsequent, SUBSEQUENT_SHARE);
				});

				assertNull(next.failure(), () -> "The subsequent write must complete, but threw " + next.failure());
				assertEquals(List.of(), next.reports());
				assertShareInBothIndexes(both, histogram, SUBSEQUENT_SHARE, "after the subsequent write");
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Asserts that every reduced index holds the share of product 1 and none of its earlier shares, and that the
		 * neighbouring product keeps its own share in every index.
		 *
		 * @param indexes   the reduced indexes of the group
		 * @param histogram the histogram name
		 * @param share     the share product 1 must hold
		 * @param stage     names the moment in the failure messages
		 */
		private static void assertShareInBothIndexes(
			@Nonnull List<ReducedGroupEntityIndex> indexes,
			@Nonnull String histogram,
			int share,
			@Nonnull String stage
		) {
			final List<Executable> checks = new ArrayList<>(indexes.size() * 4);
			for (final ReducedGroupEntityIndex index : indexes) {
				final String where = stage + ", index " + index.getPrimaryKey() + ": ";
				checks.add(() -> assertTrue(holds(index, histogram, share, PRODUCT_PK), where + "share " + share));
				for (final int earlier : new int[]{OLD_SHARE, NEW_SHARE}) {
					if (earlier != share) {
						checks.add(() -> assertFalse(
							holds(index, histogram, earlier, PRODUCT_PK), where + "no share " + earlier
						));
					}
				}
				checks.add(() -> assertTrue(
					holds(index, histogram, OLD_SHARE, OTHER_PRODUCT_PK), where + "the neighbour's share"
				));
			}
			assertAll(checks);
		}
	}

	/**
	 * The withdrawal sites where a whole index is missing, driven through the executor over an index lookup that
	 * answers the missing index with nothing. For a listed reduced index the collection does not hold, that answer is
	 * the fixture's and not the engine's: the collection refuses such an index before the withdrawal sees it, so the
	 * cells of that site test the mutator in isolation, and one cell through a real engine pins what the engine does.
	 */
	@Nested
	@DisplayName("A withdrawal missing an index")
	class StructuralSitesTest {

		/**
		 * The refusing withdrawal fails at every site where an index it must withdraw from is missing.
		 *
		 * @param site the missing index
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(StructuralSite.class)
		@DisplayName("Refuse the withdrawal")
		void shouldRefuseWithdrawalWhenIndexIsMissing(@Nonnull StructuralSite site) {
			final String histogram = histogramName("structuralRefused", site.grouped());
			final TestPaths paths = createTestPaths("missingHistogramIndexRefused_" + site);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final ExecutorFixture fixture = createStructuralFixture(
					evita, site, histogram, MissingHistogramValueBehavior.REFUSE
				);
				final EntityIndexLocalMutationExecutor executor = fixture.newExecutor();

				final Outcome outcome = capture(histogram, () -> fixture.changeShare(executor, NEW_SHARE));

				final String message = missingValueError(outcome.failure()).getMessage();
				assertAll(
					() -> assertTrue(message.contains("reduced index primary key " + site.reportedIndex()), message),
					() -> assertEquals(List.of(), outcome.reports())
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * The reporting withdrawal logs one line for the value and returns. Where a listed reduced index is missing it
		 * still withdraws the value from the valid index listed after it. The final add of the same run then meets the
		 * same missing index and fails on its own assertion, which the switch does not govern.
		 *
		 * @param site the missing index
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(StructuralSite.class)
		@DisplayName("Report the missing index and leave the final add to its own check")
		void shouldReportMissingIndexAndLeaveFinalAddToItsOwnCheck(@Nonnull StructuralSite site) {
			final String histogram = histogramName("structuralReported", site.grouped());
			final TestPaths paths = createTestPaths("missingHistogramIndexReported_" + site);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final ExecutorFixture fixture = createStructuralFixture(
					evita, site, histogram, MissingHistogramValueBehavior.REPORT
				);
				final EntityIndexLocalMutationExecutor executor = fixture.newExecutor();
				final EntityIndex validIndex = fixture.indexesByPrimaryKey().get(SECOND_REDUCED_INDEX_PK);

				final Outcome outcome = capture(histogram, () -> fixture.changeShare(executor, NEW_SHARE));

				assertNull(outcome.failure(), () -> "The withdrawal must return, but threw " + outcome.failure());
				assertEquals(1, outcome.reports().size(), outcome.reports()::toString);
				final String line = outcome.reports().get(0);
				assertAll(
					() -> assertTrue(line.startsWith(MISSING_VALUE_PREFIX), line),
					() -> assertTrue(line.contains("value " + OLD_SHARE + ","), line),
					() -> assertTrue(line.contains("reduced index primary key " + site.reportedIndex()), line)
				);
				// only a missing listed reduced index leaves a valid index after it for the withdrawal to go on to
				if (site == StructuralSite.LISTED_REDUCED_INDEX_MISSING) {
					assertFalse(
						holds(
							assertInstanceOf(HistogramCapableEntityIndex.class, validIndex),
							histogram, OLD_SHARE, PRODUCT_PK
						),
						"The valid index listed after the missing one must have lost the old value"
					);
				}

				final GenericEvitaInternalError finalAdd = assertThrows(
					GenericEvitaInternalError.class, () -> fixture.finishRun(executor, NEW_SHARE)
				);
				assertTrue(
					finalAdd.getMessage().startsWith(site.finalAddMessage()), finalAdd.getMessage()
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * The reporting removal of the reference logs one line for the value and completes its run: the removal
		 * cancels the final add, so nothing is added into the missing index and no histogram check fails the write.
		 *
		 * @param site the missing index
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(StructuralSite.class)
		@DisplayName("Complete the removal of the reference, which adds nothing into the missing index")
		void shouldCompleteReferenceRemovalWhenIndexIsMissing(@Nonnull StructuralSite site) {
			final String histogram = histogramName("structuralRemoved", site.grouped());
			final TestPaths paths = createTestPaths("missingHistogramIndexRemoved_" + site);
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				final ExecutorFixture fixture = createStructuralFixture(
					evita, site, histogram, MissingHistogramValueBehavior.REPORT
				);
				final EntityIndexLocalMutationExecutor executor = fixture.newExecutor();

				final Outcome outcome = capture(histogram, () -> {
					executor.updateReferences(
						new RemoveReferenceMutation(ExecutorFixture.REFERENCE_KEY), fixture.globalIndex()
					);
					// the end of the run has no final add left to perform
					executor.executeDeferredReEvaluations();
				});

				assertNull(outcome.failure(), () -> "The removal must complete, but threw " + outcome.failure());
				assertEquals(1, outcome.reports().size(), outcome.reports()::toString);
				final String line = outcome.reports().get(0);
				assertTrue(line.contains("reduced index primary key " + site.reportedIndex()), line);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Characterizes a real engine whose group-type index lists, beside the group's reduced index, a primary key the
		 * collection holds no index under. The withdrawal of the write asks the collection for that index to modify it,
		 * and the collection refuses with a premise error instead of answering nothing: the write fails under either
		 * behavior and no value is reported. The mutator's branch for a missing listed reduced index - the one the
		 * `LISTED_REDUCED_INDEX_MISSING` cells drive through the fixture - is not reached through the collection.
		 *
		 * @param behavior the behavior on a missing histogram value
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(MissingHistogramValueBehavior.class)
		@DisplayName("Fail the write reaching a listed reduced index the collection does not hold, in either behavior")
		void shouldFailWriteReachingListedReducedIndexTheCollectionDoesNotHold(
			@Nonnull MissingHistogramValueBehavior behavior
		) {
			final String histogram = histogramName("unheldReducedIndex", true);
			final TestPaths paths = createTestPaths("missingHistogramIndexUnheld_" + behavior);
			try (final Evita evita = new Evita(configuration(paths, behavior, null))) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					defineSchema(session, true, histogram, Integer.class);
					createProduct(session, PRODUCT_PK, BRAND_PK, true, OLD_SHARE);
				});
				final EntityCollection collection =
					catalog(evita).getCollectionForEntityInternal(PRODUCT).orElseThrow();
				assertNull(
					collection.getIndexByPrimaryKeyIfExists(UNHELD_REDUCED_INDEX_PK),
					"The collection must hold no index under the listed primary key"
				);
				assertInstanceOf(ReferencedTypeEntityIndex.class, collection.getIndexByKeyIfExists(GROUP_TYPE_KEY))
					.insertPrimaryKeyIfMissing(UNHELD_REDUCED_INDEX_PK, GROUP_PK);

				final Outcome outcome = capture(histogram, () -> updateShare(evita, true, NEW_SHARE));

				final Throwable refusal = causeContaining(
					outcome.failure(),
					"Index with primary key " + UNHELD_REDUCED_INDEX_PK + " was not found in the persistent storage"
				);
				assertAll(
					() -> assertInstanceOf(GenericEvitaInternalError.class, refusal),
					() -> assertEquals(List.of(), outcome.reports())
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	/**
	 * A reported write applied more than once: by the trunk of an alive catalog after its session, and by the replay
	 * of the write-ahead log when the engine starts again.
	 */
	@Nested
	@DisplayName("A reported write applied again")
	@Tag(WAL)
	class ReplayTest {

		/**
		 * In an alive catalog the write is applied once in the session and once more when the committed transaction
		 * is incorporated into the trunk, and both applications meet the missing value: the write completes and logs
		 * one line per application.
		 */
		@Test
		@DisplayName("Report once per application when an alive catalog incorporates the write")
		void shouldReportOncePerApplicationWhenAliveCatalogIncorporatesWrite() {
			final String histogram = "sharesAlive";
			final TestPaths paths = createTestPaths("missingHistogramValueAlive");
			final EvitaConfiguration configuration = configuration(paths, MissingHistogramValueBehavior.REPORT, null);
			Evita evita = new Evita(configuration);
			try {
				prepareAliveCatalogWithStoredMissingValue(evita, histogram);
				evita = restart(evita, configuration);
				assertStoredMissingValue(evita, histogram);

				final Evita aliveEvita = evita;
				final Outcome outcome = capture(histogram, () -> updateShare(aliveEvita, false, NEW_SHARE));

				assertNull(outcome.failure(), () -> "The write must complete, but threw " + outcome.failure());
				assertAll(
					() -> assertEquals(2, outcome.reports().size(), outcome.reports()::toString),
					() -> assertTrue(
						holdsShare(aliveEvita, false, histogram, NEW_SHARE, PRODUCT_PK), "The new share must be added"
					),
					() -> assertTrue(
						lacksShare(aliveEvita, false, histogram, OLD_SHARE, PRODUCT_PK), "The old share must be gone"
					),
					() -> assertEquals(NEW_SHARE, readShare(aliveEvita), "The new share must be stored")
				);
			} finally {
				evita.close();
				cleanupTestPaths(paths);
			}
		}

		/**
		 * A reported write committed into the write-ahead log but not yet checkpointed is replayed when the engine
		 * starts again, against a stored index that misses the value. Two images of the same storage are booted:
		 *
		 * - under `REPORT` the replay reaches the version the write committed and logs its line again, which proves the
		 *   replay happened;
		 * - under `REFUSE` the same replay meets the same missing value and refuses: the catalog is marked corrupted
		 *   and the engine's wait for its initial load fails with the refusal, on the first restart and on the second
		 *   alike. Booted once more under `REPORT` the same image loads at the version the write committed, because
		 *   the refused replays published nothing, and a boot under `REFUSE` after that loads it with nothing left to
		 *   replay.
		 */
		@Test
		@DisplayName("Refuse the replay of a reported write after switching back to refuse")
		void shouldRefuseReplayOfReportedWriteWhenSwitchedBackToRefuse() {
			final String histogram = "sharesReplayed";
			final TestPaths paths = createTestPaths("missingHistogramValueReplay");
			final TestPaths reportImage = createTestPaths("missingHistogramValueReplay_reportImage");
			final TestPaths refuseImage = createTestPaths("missingHistogramValueReplay_refuseImage");
			final TransactionOptions deferredCheckpoint = TransactionOptions.builder()
				.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
				.build();
			final EvitaConfiguration configuration = configuration(
				paths, MissingHistogramValueBehavior.REPORT, deferredCheckpoint
			);
			Evita evita = new Evita(configuration);
			try {
				prepareAliveCatalogWithStoredMissingValue(evita, histogram);
				// going live is deferred like any other round; the clean close publishes it
				evita = restart(evita, configuration);
				assertStoredMissingValue(evita, histogram);

				final Evita aliveEvita = evita;
				final Outcome committed = capture(histogram, () -> updateShare(aliveEvita, false, NEW_SHARE));
				assertNull(committed.failure(), () -> "The write must complete, but threw " + committed.failure());
				final long appliedVersion = takeImagesOfUnpublishedTail(aliveEvita, paths, reportImage, refuseImage);
				evita.close();

				assertImageLoaded(
					reportImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 1
				);
				// the first and the second restart under refuse
				assertReplayRefused(refuseImage, histogram, deferredCheckpoint);
				assertReplayRefused(refuseImage, histogram, deferredCheckpoint);
				// the refused replays published nothing, so the same storage loads once the engine reports again
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 1
				);
				// the clean close of the recovered engine published the replayed state, so refusing again is safe
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REFUSE, deferredCheckpoint, histogram, appliedVersion, 0
				);
			} finally {
				evita.close();
				cleanupTestPaths(paths);
				cleanupTestPaths(reportImage);
				cleanupTestPaths(refuseImage);
			}
		}

		/**
		 * The same replay without any switch of the behavior: the engine refuses throughout, and the value is missing
		 * because the stored index lost it before the restart. The value goes back into the index in memory only, so
		 * the refusing engine commits the write without a refusal, while the stored index the replay starts from still
		 * misses the value - memory and storage disagree as they do in a catalog whose stored index drifted. The
		 * replay refuses on the first restart and on the second alike, and the same tail loads under `REPORT`.
		 */
		@Test
		@DisplayName("Refuse the replay of a write meeting a value the stored index lost before the restart")
		void shouldRefuseReplayOfWriteMeetingValueMissingFromStoredIndex() {
			final String histogram = "sharesDrifted";
			final TestPaths paths = createTestPaths("missingHistogramValueDrift");
			final TestPaths reportImage = createTestPaths("missingHistogramValueDrift_reportImage");
			final TestPaths refuseImage = createTestPaths("missingHistogramValueDrift_refuseImage");
			final TransactionOptions deferredCheckpoint = TransactionOptions.builder()
				.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
				.build();
			final EvitaConfiguration configuration = configuration(
				paths, MissingHistogramValueBehavior.REFUSE, deferredCheckpoint
			);
			Evita evita = new Evita(configuration);
			try {
				prepareAliveCatalogWithStoredMissingValue(evita, histogram);
				// going live is deferred like any other round; the clean close publishes it
				evita = restart(evita, configuration);
				assertStoredMissingValue(evita, histogram);
				for (final HistogramCapableEntityIndex index : shareIndexes(evita, false)) {
					index.insertHistogramValue(histogram, null, OLD_SHARE, PRODUCT_PK, Integer.class, 0);
				}
				assertTrue(holdsShare(evita, false, histogram, OLD_SHARE, PRODUCT_PK), "The memory must hold it again");

				final Evita aliveEvita = evita;
				final Outcome committed = capture(histogram, () -> updateShare(aliveEvita, false, NEW_SHARE));
				assertNull(committed.failure(), () -> "The write must complete, but threw " + committed.failure());
				assertEquals(List.of(), committed.reports());
				final long appliedVersion = takeImagesOfUnpublishedTail(aliveEvita, paths, reportImage, refuseImage);
				evita.close();

				// the first and the second restart under refuse
				assertReplayRefused(refuseImage, histogram, deferredCheckpoint);
				assertReplayRefused(refuseImage, histogram, deferredCheckpoint);
				assertImageLoaded(
					reportImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 1
				);
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 1
				);
			} finally {
				evita.close();
				cleanupTestPaths(paths);
				cleanupTestPaths(reportImage);
				cleanupTestPaths(refuseImage);
			}
		}
	}

	/**
	 * Where a structural cell makes an index missing, and what the final add of the same run says about it.
	 */
	enum StructuralSite {
		/**
		 * The group-type index of a grouped reference is absent.
		 */
		GROUP_TYPE_INDEX_MISSING(true, -1, "Cannot insert histogram value: group ReducedTypeEntityIndex is missing"),
		/**
		 * The group-type index lists no reduced index for the group.
		 */
		NO_REDUCED_INDEX_LISTED(true, -1, "Cannot insert histogram value: the group-type index lists no reduced index"),
		/**
		 * The group-type index lists two reduced indexes for the group, and the first one is absent. Only the fixture's
		 * lookup answers the absent index with nothing; the collection refuses it first (see
		 * {@link StructuralSitesTest#shouldFailWriteReachingListedReducedIndexTheCollectionDoesNotHold}).
		 */
		LISTED_REDUCED_INDEX_MISSING(
			true, FIRST_REDUCED_INDEX_PK,
			"Cannot insert histogram value: per-group reduced index is missing"
		),
		/**
		 * The referenced-type index of an ungrouped reference is absent.
		 */
		TYPE_INDEX_MISSING(false, -1, "Cannot insert histogram value: ReferencedTypeEntityIndex is missing");

		private final boolean grouped;
		private final int reportedIndex;
		private final String finalAddMessage;

		/**
		 * Creates the site.
		 *
		 * @param grouped         whether the reference belongs to a group
		 * @param reportedIndex   the reduced index primary key the withdrawal names
		 * @param finalAddMessage the start of the message the final add fails with
		 */
		StructuralSite(boolean grouped, int reportedIndex, @Nonnull String finalAddMessage) {
			this.grouped = grouped;
			this.reportedIndex = reportedIndex;
			this.finalAddMessage = finalAddMessage;
		}

		/**
		 * Returns whether the reference belongs to a group.
		 *
		 * @return TRUE for a grouped reference
		 */
		boolean grouped() {
			return this.grouped;
		}

		/**
		 * Returns the reduced index primary key the withdrawal names.
		 *
		 * @return the primary key, or `-1` when no reduced index is involved
		 */
		int reportedIndex() {
			return this.reportedIndex;
		}

		/**
		 * Returns the start of the message the final add fails with.
		 *
		 * @return the message start
		 */
		@Nonnull
		String finalAddMessage() {
			return this.finalAddMessage;
		}
	}

	/**
	 * An executor fixture of product 1 over a real storage executor and an index lookup the cell lays out: the
	 * referenced-type and group-type indexes it registers by key, and the reduced indexes it registers by primary key.
	 * Every write gets its own executor, as every entity mutation does in the engine; all of them share the storage and
	 * the indexes.
	 *
	 * @param executors           creates a new executor over the storage and the indexes
	 * @param storage             the real storage executor of product 1
	 * @param schema              the product schema whose reference carries the `share` attribute
	 * @param grouped             TRUE when the reference is stored in group 100
	 * @param globalIndex         the global index passed to reference handlers
	 * @param indexesByPrimaryKey the reduced indexes the lookup answers by primary key
	 */
	private record ExecutorFixture(
		@Nonnull Supplier<EntityIndexLocalMutationExecutor> executors,
		@Nonnull ContainerizedLocalMutationExecutor storage,
		@Nonnull EntitySchema schema,
		boolean grouped,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Map<Integer, EntityIndex> indexesByPrimaryKey
	) {
		/**
		 * The stored reference, with its internal primary key.
		 */
		static final ReferenceKey REFERENCE_KEY = new ReferenceKey(BRAND, BRAND_PK, 7);

		/**
		 * Creates the executor of one write.
		 *
		 * @return a new executor
		 */
		@Nonnull
		EntityIndexLocalMutationExecutor newExecutor() {
			return this.executors.get();
		}

		/**
		 * Sends the change of the share to the executor, which withdraws the stored share first.
		 *
		 * @param executor the executor of the write
		 * @param share    the share the mutation sets
		 */
		void changeShare(@Nonnull EntityIndexLocalMutationExecutor executor, int share) {
			executor.updateReferences(
				new ReferenceAttributeMutation(REFERENCE_KEY, new UpsertAttributeMutation(SHARE, share)),
				this.globalIndex
			);
		}

		/**
		 * Writes the share to storage, as the storage half of the mutation would, and ends the deferred run of the
		 * executor with the final add of the reference's values.
		 *
		 * @param executor the executor of the write
		 * @param share    the share the mutation set
		 */
		void finishRun(@Nonnull EntityIndexLocalMutationExecutor executor, int share) {
			storeReference(share);
			executor.executeDeferredReEvaluations();
		}

		/**
		 * Writes the reference to storage independently of index execution, in group 100 when the fixture is grouped.
		 *
		 * @param share the stored `share` attribute
		 */
		void storeReference(int share) {
			final Map<AttributeKey, AttributeValue> attributes =
				Map.of(new AttributeKey(SHARE), new AttributeValue(new AttributeKey(SHARE), share));
			this.storage.getReferencesStoragePart(PRODUCT, PRODUCT_PK).replaceOrAddReference(
				REFERENCE_KEY, ignored -> new Reference(
					this.schema, this.schema.getReferenceOrThrowException(BRAND), PRODUCT_PK, REFERENCE_KEY,
					this.grouped ? new GroupEntityReference(BRAND_GROUP, GROUP_PK, 1, false) : null, attributes, false
				), () -> MissingReferenceBehavior.ACCEPT_INTERNAL_KEY
			);
		}
	}

	/**
	 * What a captured action did: the failure it threw, if any, and the report lines it logged for the cell's
	 * histogram.
	 *
	 * @param failure the throwable the action threw, or `null` when it completed
	 * @param reports the error-level messages of {@link ReferenceIndexMutator} naming the histogram, in order
	 */
	private record Outcome(@Nullable Throwable failure, @Nonnull List<String> reports) {
	}

	/**
	 * Runs the action with a list appender on the logger of {@link ReferenceIndexMutator} and keeps the error-level
	 * messages that name the histogram. The appender sees lines logged by any thread, so an application on the
	 * transaction pool is captured as well; it is detached before the lines are read.
	 *
	 * @param histogram the histogram name the kept lines must carry
	 * @param action    the action to run
	 * @return what the action did
	 */
	@Nonnull
	private static Outcome capture(@Nonnull String histogram, @Nonnull Runnable action) {
		final Logger logger = (Logger) LoggerFactory.getLogger(ReferenceIndexMutator.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		Throwable failure = null;
		try {
			action.run();
		} catch (RuntimeException ex) {
			failure = ex;
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		final String histogramFragment = "histogram `" + histogram + "`";
		final List<String> reports = new ArrayList<>(4);
		for (final ILoggingEvent event : List.copyOf(appender.list)) {
			if (event.getLevel() == Level.ERROR && event.getFormattedMessage().contains(histogramFragment)) {
				reports.add(event.getFormattedMessage());
			}
		}
		return new Outcome(failure, reports);
	}

	/**
	 * Finds the refusal of the withdrawal in the cause chain of a failure.
	 *
	 * @param failure the failure, which must not be `null`
	 * @return the refusal
	 */
	@Nonnull
	private static GenericEvitaInternalError missingValueError(@Nullable Throwable failure) {
		assertNotNull(failure, "The write must be refused");
		Throwable current = failure;
		while (current != null) {
			if (
				current instanceof GenericEvitaInternalError error &&
					error.getMessage().startsWith(MISSING_VALUE_PREFIX)
			) {
				return error;
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		throw new AssertionError("The failure is not the refusal of the withdrawal", failure);
	}

	/**
	 * Finds the throwable in the cause chain of a failure whose message contains the fragment.
	 *
	 * @param failure  the failure, which must not be `null`
	 * @param fragment the text the message must contain
	 * @return the throwable carrying the fragment
	 */
	@Nonnull
	private static Throwable causeContaining(@Nullable Throwable failure, @Nonnull String fragment) {
		assertNotNull(failure, "The write must fail");
		Throwable current = failure;
		while (current != null) {
			final String message = current.getMessage();
			if (message != null && message.contains(fragment)) {
				return current;
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		throw new AssertionError("No cause of the failure says: " + fragment, failure);
	}

	/**
	 * Builds the configuration of an engine over the directories with the behavior on a missing value.
	 *
	 * @param paths              the directories of the engine
	 * @param behavior           the behavior on a missing histogram value
	 * @param transactionOptions the transaction options, or `null` for the defaults
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration(
		@Nonnull TestPaths paths,
		@Nonnull MissingHistogramValueBehavior behavior,
		@Nullable TransactionOptions transactionOptions
	) {
		final EvitaConfiguration.Builder builder = newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().missingHistogramValueBehavior(behavior).build());
		if (transactionOptions != null) {
			builder.transaction(transactionOptions);
		}
		return builder.build();
	}

	/**
	 * Closes the engine and starts a new one on the stored data, waiting until it has loaded the catalog. A new engine
	 * whose load fails is closed before the failure propagates.
	 *
	 * @param evita         the running engine
	 * @param configuration the configuration of the engine
	 * @return the new engine
	 */
	@Nonnull
	private static Evita restart(@Nonnull Evita evita, @Nonnull EvitaConfiguration configuration) {
		evita.close();
		final Evita restarted = new Evita(configuration);
		try {
			CatalogReloadTestSupport.awaitCatalogLoaded(restarted, TEST_CATALOG);
		} catch (RuntimeException | Error ex) {
			// the caller never receives this engine, so nobody else would close it before its storage is cleaned up
			restarted.close();
			throw ex;
		}
		return restarted;
	}

	/**
	 * Waits until the alive catalog has applied everything its write-ahead log holds, asserts that the last checkpoint
	 * lies behind it, and copies the storage into each image - the state a crash would leave, with the committed tail
	 * not yet published. The copies are taken while the engine is idle and before it closes, because a clean close
	 * publishes the tail.
	 *
	 * @param evita  the running engine
	 * @param paths  the directories of the engine
	 * @param images the directories to copy the storage into
	 * @return the catalog version the tail reaches
	 */
	private static long takeImagesOfUnpublishedTail(
		@Nonnull Evita evita, @Nonnull TestPaths paths, @Nonnull TestPaths... images
	) {
		await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.until(() -> {
				final Catalog current = catalog(evita);
				return current.getVersion() == current.getLastCatalogVersionInMutationStream();
			});
		final Catalog catalog = catalog(evita);
		final long appliedVersion = catalog.getVersion();
		final long persistedVersion = catalog.getLastPersistedCatalogVersion();
		assertTrue(
			persistedVersion < appliedVersion,
			"The image must hold the write in the write-ahead log past the last checkpoint: applied " +
				appliedVersion + ", checkpointed " + persistedVersion
		);
		for (final TestPaths image : images) {
			CatalogReloadTestSupport.copyRecursively(paths.storage(), image.storage());
		}
		assertSame(catalog, catalog(evita), "The catalog must not move while its storage is copied");
		return appliedVersion;
	}

	/**
	 * Boots a refusing engine over the image and asserts that the replay of its tail refuses: the engine starts, the
	 * catalog is marked corrupted, the engine's wait for its initial load fails with the refusal, the corrupted
	 * catalog names the refusal as its cause, and no report line is logged.
	 *
	 * @param image              the directories of the image
	 * @param histogram          the histogram name
	 * @param transactionOptions the transaction options of the engine
	 */
	private void assertReplayRefused(
		@Nonnull TestPaths image, @Nonnull String histogram, @Nonnull TransactionOptions transactionOptions
	) {
		final Outcome outcome = capture(histogram, () -> {
			try (
				final Evita refusing = new Evita(
					configuration(image, MissingHistogramValueBehavior.REFUSE, transactionOptions)
				)
			) {
				// the engine starts, but the initial load it waits for fails with the refusal of the replay
				final CompletionException loading = assertThrows(
					CompletionException.class, refusing::waitUntilFullyInitialized
				);
				final String loadingMessage = missingValueError(loading).getMessage();
				assertTrue(loadingMessage.contains("histogram `" + histogram + "`"), loadingMessage);
				assertEquals(CatalogState.CORRUPTED, refusing.getCatalogState(TEST_CATALOG).orElseThrow());
				final CatalogCorruptedException corrupted = assertThrows(
					CatalogCorruptedException.class,
					() -> refusing.getCatalogInstance(TEST_CATALOG).orElseThrow().getSchema()
				);
				final String message = missingValueError(corrupted).getMessage();
				assertTrue(message.contains("histogram `" + histogram + "`"), message);
			}
		});
		assertNull(outcome.failure(), () -> "The boot itself must not throw: " + outcome.failure());
		assertEquals(List.of(), outcome.reports());
	}

	/**
	 * Boots an engine over the image and asserts that the catalog loads at the version the tail reaches, holding the
	 * new share in its histogram and its body, with the expected number of report lines.
	 *
	 * @param image              the directories of the image
	 * @param behavior           the behavior on a missing histogram value
	 * @param transactionOptions the transaction options of the engine
	 * @param histogram          the histogram name
	 * @param appliedVersion     the catalog version the tail reaches
	 * @param expectedReports    the number of report lines the boot logs
	 */
	private void assertImageLoaded(
		@Nonnull TestPaths image,
		@Nonnull MissingHistogramValueBehavior behavior,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull String histogram,
		long appliedVersion,
		int expectedReports
	) {
		final Outcome outcome = capture(histogram, () -> {
			try (final Evita loaded = new Evita(configuration(image, behavior, transactionOptions))) {
				CatalogReloadTestSupport.awaitCatalogLoaded(loaded, TEST_CATALOG);
				assertEquals(appliedVersion, catalog(loaded).getVersion(), "The catalog must reach the write");
				assertTrue(holdsShare(loaded, false, histogram, NEW_SHARE, PRODUCT_PK), "The new share must be there");
				assertEquals(NEW_SHARE, readShare(loaded), "The new share must be stored");
			}
		});
		assertNull(outcome.failure(), () -> "The boot must load the catalog: " + outcome.failure());
		assertEquals(expectedReports, outcome.reports().size(), outcome.reports()::toString);
	}

	/**
	 * Returns a histogram name unique to a cell.
	 *
	 * @param label   the cell's label
	 * @param grouped whether the reference of the cell belongs to a group
	 * @return the histogram name
	 */
	@Nonnull
	private static String histogramName(@Nonnull String label, boolean grouped) {
		return label + (grouped ? "Grouped" : "Ungrouped");
	}

	/**
	 * Defines the brand, the brand group and the product, whose reference to a brand carries the `share` attribute
	 * and the histogram over it. The reference is indexed for filtering and faceted; a grouped one also indexes its
	 * group component, so its histogram lives in the reduced index of the group.
	 *
	 * @param session   the session of the catalog in warm-up
	 * @param grouped   whether the reference belongs to a group
	 * @param histogram the histogram name
	 * @param shareType the type of the `share` attribute
	 */
	private static void defineSchema(
		@Nonnull EvitaSessionContract session,
		boolean grouped,
		@Nonnull String histogram,
		@Nonnull Class<? extends Serializable> shareType
	) {
		session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(BRAND_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					if (grouped) {
						whichIs.withGroupTypeRelatedToEntity(BRAND_GROUP)
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							);
					}
					whichIs.indexedForFiltering()
						.faceted()
						.withAttribute(SHARE, shareType, thatIs -> thatIs.filterable().nullable())
						.bucketed(histogram, ExpressionFactory.parse("$reference.attributes['" + SHARE + "']"));
				}
			)
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(BRAND_GROUP, GROUP_PK));
		session.upsertEntity(session.createNewEntity(BRAND, BRAND_PK));
		session.upsertEntity(session.createNewEntity(BRAND, OTHER_BRAND_PK));
	}

	/**
	 * Stores a product referencing a brand with the share, in the brand group when grouped.
	 *
	 * @param session   the session
	 * @param productPk the primary key of the product
	 * @param brandPk   the primary key of the referenced brand
	 * @param grouped   whether the reference belongs to the group
	 * @param share     the share
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session, int productPk, int brandPk, boolean grouped, int share
	) {
		session.createNewEntity(PRODUCT, productPk)
			.setReference(BRAND, brandPk, shareSetter(grouped, share))
			.upsertVia(session);
	}

	/**
	 * Returns the change of a reference setting the share, keeping it in the brand group when grouped.
	 *
	 * @param grouped whether the reference belongs to the group
	 * @param share   the share
	 * @return the change of the reference
	 */
	@Nonnull
	private static Consumer<ReferenceBuilder> shareSetter(boolean grouped, int share) {
		return whichIs -> {
			if (grouped) {
				whichIs.setGroup(BRAND_GROUP, GROUP_PK);
			}
			whichIs.setAttribute(SHARE, share);
		};
	}

	/**
	 * Returns the change of a reference setting the array of shares, keeping it in the brand group when grouped.
	 *
	 * @param grouped whether the reference belongs to the group
	 * @param shares  the shares
	 * @return the change of the reference
	 */
	@Nonnull
	private static Consumer<ReferenceBuilder> sharesSetter(boolean grouped, @Nonnull Integer[] shares) {
		return whichIs -> {
			if (grouped) {
				whichIs.setGroup(BRAND_GROUP, GROUP_PK);
			}
			whichIs.setAttribute(SHARE, shares);
		};
	}

	/**
	 * Defines the catalog in warm-up, stores product 1 with the old share and removes the old share from the index
	 * the withdrawal reads, after asserting that it was there.
	 *
	 * @param evita     the engine
	 * @param grouped   whether the reference belongs to a group
	 * @param histogram the histogram name
	 */
	private static void prepareProductWithMissingValue(
		@Nonnull Evita evita, boolean grouped, @Nonnull String histogram
	) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(TEST_CATALOG, session -> {
			defineSchema(session, grouped, histogram, Integer.class);
			createProduct(session, PRODUCT_PK, BRAND_PK, grouped, OLD_SHARE);
		});
		removeShare(evita, grouped, histogram, OLD_SHARE, PRODUCT_PK);
	}

	/**
	 * Builds an alive catalog whose stored histogram index misses the old share of product 1 while the product keeps
	 * the share in its body. The hole is made while the warm-up session that goes live is open, and product 2 makes
	 * the same session write the referenced-type index, so the flush stores the index without the value.
	 *
	 * @param evita     the engine
	 * @param histogram the histogram name
	 */
	private static void prepareAliveCatalogWithStoredMissingValue(@Nonnull Evita evita, @Nonnull String histogram) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(TEST_CATALOG, session -> {
			defineSchema(session, false, histogram, Integer.class);
			createProduct(session, PRODUCT_PK, BRAND_PK, false, OLD_SHARE);
		});
		evita.updateCatalog(TEST_CATALOG, session -> {
			removeShare(evita, false, histogram, OLD_SHARE, PRODUCT_PK);
			createProduct(session, OTHER_PRODUCT_PK, OTHER_BRAND_PK, false, OTHER_SHARE);
			session.goLiveAndClose();
		});
	}

	/**
	 * Asserts the starting state of the replay cells as the engine loaded it from storage: the histogram misses the
	 * old share of product 1, holds the share of product 2, and product 1 keeps the old share in its body. Without it
	 * a replay cell would prove nothing about a stored missing value.
	 *
	 * @param evita     the engine
	 * @param histogram the histogram name
	 */
	private static void assertStoredMissingValue(@Nonnull Evita evita, @Nonnull String histogram) {
		assertAll(
			() -> assertTrue(
				lacksShare(evita, false, histogram, OLD_SHARE, PRODUCT_PK), "The stored index must miss the old share"
			),
			() -> assertTrue(
				holdsShare(evita, false, histogram, OTHER_SHARE, OTHER_PRODUCT_PK),
				"The stored index must hold the other product's share"
			),
			() -> assertEquals(OLD_SHARE, readShare(evita), "The product must keep the old share in its body")
		);
	}

	/**
	 * Changes the share of product 1's brand reference in a session of its own. The commit waits until the change is
	 * visible, so in an alive catalog it returns only after the trunk has applied the write as well - the report lines
	 * of both applications are logged by then.
	 *
	 * @param evita   the engine
	 * @param grouped whether the reference belongs to the group
	 * @param share   the new share
	 */
	private static void updateShare(@Nonnull Evita evita, boolean grouped, int share) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(PRODUCT, PRODUCT_PK, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setReference(BRAND, BRAND_PK, shareSetter(grouped, share))
					.upsertVia(session);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
	}

	/**
	 * Reads the stored share of product 1's brand reference.
	 *
	 * @param evita the engine
	 * @return the stored share
	 */
	@Nullable
	private static Serializable readShare(@Nonnull Evita evita) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(PRODUCT, PRODUCT_PK, entityFetchAllContent()).orElseThrow()
					.getReference(BRAND, BRAND_PK).orElseThrow()
					.getAttribute(SHARE);
			}
		);
	}

	/**
	 * Returns the current catalog of the engine.
	 *
	 * @param evita the engine
	 * @return the catalog
	 */
	@Nonnull
	private static Catalog catalog(@Nonnull Evita evita) {
		return (Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/**
	 * Returns the indexes the withdrawal reads the share from: the referenced-type index when ungrouped, or every
	 * reduced index the group-type index lists for the group.
	 *
	 * @param evita   the engine
	 * @param grouped whether the reference belongs to the group
	 * @return the histogram-capable indexes, never empty
	 */
	@Nonnull
	private static List<HistogramCapableEntityIndex> shareIndexes(@Nonnull Evita evita, boolean grouped) {
		final EntityCollection collection = catalog(evita).getCollectionForEntityInternal(PRODUCT).orElseThrow();
		final List<HistogramCapableEntityIndex> indexes = new ArrayList<>(2);
		if (grouped) {
			final EntityIndex groupTypeIndex = collection.getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE, Scope.LIVE, BRAND)
			);
			final int[] reducedIndexPks =
				assertInstanceOf(ReferencedTypeEntityIndex.class, groupTypeIndex).getAllReferenceIndexes(GROUP_PK);
			for (final int reducedIndexPk : reducedIndexPks) {
				indexes.add(assertInstanceOf(
					HistogramCapableEntityIndex.class, collection.getIndexByPrimaryKeyIfExists(reducedIndexPk)
				));
			}
		} else {
			indexes.add(assertInstanceOf(
				HistogramCapableEntityIndex.class,
				collection.getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, BRAND)
				)
			));
		}
		assertFalse(indexes.isEmpty(), "The share must have an index to live in");
		return indexes;
	}

	/**
	 * Returns whether every index the withdrawal reads holds the share for the owner.
	 *
	 * @param evita     the engine
	 * @param grouped   whether the reference belongs to the group
	 * @param histogram the histogram name
	 * @param share     the share
	 * @param ownerPk   the owner primary key
	 * @return TRUE when every index holds the share for the owner
	 */
	private static boolean holdsShare(
		@Nonnull Evita evita, boolean grouped, @Nonnull String histogram, int share, int ownerPk
	) {
		for (final HistogramCapableEntityIndex index : shareIndexes(evita, grouped)) {
			if (!holds(index, histogram, share, ownerPk)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns whether no index the withdrawal reads holds the share for the owner.
	 *
	 * @param evita     the engine
	 * @param grouped   whether the reference belongs to the group
	 * @param histogram the histogram name
	 * @param share     the share
	 * @param ownerPk   the owner primary key
	 * @return TRUE when no index holds the share for the owner
	 */
	private static boolean lacksShare(
		@Nonnull Evita evita, boolean grouped, @Nonnull String histogram, int share, int ownerPk
	) {
		for (final HistogramCapableEntityIndex index : shareIndexes(evita, grouped)) {
			if (holds(index, histogram, share, ownerPk)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Removes the share of the owner from every index the withdrawal reads, after asserting it is there - the missing
	 * value of the cell.
	 *
	 * @param evita     the engine
	 * @param grouped   whether the reference belongs to the group
	 * @param histogram the histogram name
	 * @param share     the share
	 * @param ownerPk   the owner primary key
	 */
	private static void removeShare(
		@Nonnull Evita evita, boolean grouped, @Nonnull String histogram, int share, int ownerPk
	) {
		for (final HistogramCapableEntityIndex index : shareIndexes(evita, grouped)) {
			assertTrue(holds(index, histogram, share, ownerPk), "The value must be there before it is removed");
			index.removeHistogramValue(histogram, null, share, ownerPk, 0);
		}
	}

	/**
	 * Returns whether the index holds the share for the owner, reading the bucket the way the withdrawal's presence
	 * check does.
	 *
	 * @param index     the histogram-capable index
	 * @param histogram the histogram name
	 * @param share     the share
	 * @param ownerPk   the owner primary key
	 * @return TRUE when the index holds the share for the owner
	 */
	private static boolean holds(
		@Nonnull HistogramCapableEntityIndex index, @Nonnull String histogram, int share, int ownerPk
	) {
		final HistogramIndex histogramIndex = index.getHistogramIndex(histogram);
		if (histogramIndex == null) {
			return false;
		}
		final FilterIndex filterIndex = histogramIndex.getFilterIndex(null);
		return filterIndex != null &&
			filterIndex.getInvertedIndex().getRecordsEqualTo(histogramIndex.normalizeValue(share)).contains(ownerPk);
	}

	/**
	 * Builds the executor fixture of a structural cell, whose index lookup makes one index of the withdrawal missing.
	 * The stored reference to brand 10 holds the old share, and every index the site keeps holds the old share for
	 * product 1.
	 *
	 * @param evita     the engine the catalog and its storage are created in
	 * @param site      the missing index
	 * @param histogram the histogram name
	 * @param behavior  the behavior on a missing histogram value
	 * @return the fixture
	 */
	@Nonnull
	private static ExecutorFixture createStructuralFixture(
		@Nonnull Evita evita,
		@Nonnull StructuralSite site,
		@Nonnull String histogram,
		@Nonnull MissingHistogramValueBehavior behavior
	) {
		return createExecutorFixture(
			evita, site.grouped(), histogram, behavior,
			(indexes, indexesByPrimaryKey) -> {
				switch (site) {
					case GROUP_TYPE_INDEX_MISSING -> {
						indexes.register(GROUP_TYPE_KEY, null);
						indexes.register(TYPE_KEY, null);
					}
					case NO_REDUCED_INDEX_LISTED -> {
						indexes.register(GROUP_TYPE_KEY, new ReferencedTypeEntityIndex(21, PRODUCT, GROUP_TYPE_KEY));
						indexes.register(TYPE_KEY, null);
					}
					case LISTED_REDUCED_INDEX_MISSING -> {
						// the first listed index is held by nobody, the second one is valid and holds the old share
						final ReducedGroupEntityIndex reducedIndex = reducedGroupIndex(SECOND_REDUCED_INDEX_PK);
						reducedIndex.insertHistogramValue(histogram, null, OLD_SHARE, PRODUCT_PK, Integer.class, 0);
						indexesByPrimaryKey.put(SECOND_REDUCED_INDEX_PK, reducedIndex);
						listGroupReducedIndexes(indexes);
					}
					case TYPE_INDEX_MISSING -> indexes.register(TYPE_KEY, null);
				}
			}
		);
	}

	/**
	 * Registers a group-type index listing the first and then the second reduced index for group 100, and no
	 * referenced-type index.
	 *
	 * @param indexes the index lookup of the fixture
	 */
	private static void listGroupReducedIndexes(@Nonnull MockEntityIndexCreator<EntityIndexKey, EntityIndex> indexes) {
		final ReferencedTypeEntityIndex groupTypeIndex = new ReferencedTypeEntityIndex(21, PRODUCT, GROUP_TYPE_KEY);
		groupTypeIndex.insertPrimaryKeyIfMissing(FIRST_REDUCED_INDEX_PK, GROUP_PK);
		groupTypeIndex.insertPrimaryKeyIfMissing(SECOND_REDUCED_INDEX_PK, GROUP_PK);
		assertArrayEquals(
			new int[]{FIRST_REDUCED_INDEX_PK, SECOND_REDUCED_INDEX_PK},
			groupTypeIndex.getAllReferenceIndexes(GROUP_PK),
			"The first reduced index must be listed before the second one"
		);
		indexes.register(GROUP_TYPE_KEY, groupTypeIndex);
		indexes.register(TYPE_KEY, null);
	}

	/**
	 * Creates a reduced index of group 100.
	 *
	 * @param primaryKey the primary key of the index
	 * @return the reduced index
	 */
	@Nonnull
	private static ReducedGroupEntityIndex reducedGroupIndex(int primaryKey) {
		return new ReducedGroupEntityIndex(
			primaryKey, PRODUCT, new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(BRAND, GROUP_PK))
			)
		);
	}

	/**
	 * Builds an executor fixture of product 1 over a real storage executor and an index lookup the layout arranges. The
	 * stored reference to brand 10 holds the old share. The reference schema is unindexed, so only the histogram
	 * trigger writes indexes.
	 *
	 * @param evita     the engine the catalog and its storage are created in
	 * @param grouped   whether the reference belongs to group 100
	 * @param histogram the histogram name
	 * @param behavior  the behavior on a missing histogram value
	 * @param layout    registers the indexes by key on the lookup and the reduced indexes by primary key in the map
	 * @return the fixture
	 */
	@Nonnull
	private static ExecutorFixture createExecutorFixture(
		@Nonnull Evita evita,
		boolean grouped,
		@Nonnull String histogram,
		@Nonnull MissingHistogramValueBehavior behavior,
		@Nonnull BiConsumer<MockEntityIndexCreator<EntityIndexKey, EntityIndex>, Map<Integer, EntityIndex>> layout
	) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
			session.createNewEntity(PRODUCT, PRODUCT_PK).upsertVia(session);
		});
		final Catalog catalog = catalog(evita);
		final EntityCollection collection = catalog.getCollectionForEntityInternal(PRODUCT).orElseThrow();
		final EntitySchema productSchema = collection.getInternalSchema();
		final EntitySchema schema = (EntitySchema) new InternalEntitySchemaBuilder(
			catalog.getInternalSchema(), productSchema
		).withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_MORE,
			ref -> ref.withAttribute(SHARE, Integer.class)).toInstance();
		final ContainerizedLocalMutationExecutor storage = new ContainerizedLocalMutationExecutor(
			new WarmUpDataStoreMemoryBuffer(collection.getStoragePartPersistenceService()),
			collection.getDataStoreReader(), catalog.getVersion(), PRODUCT_PK, EntityExistence.MUST_EXIST,
			catalog::getInternalSchema, () -> schema, entityType -> null,
			(entityType, primaryKey) -> null, () -> 1, false
		);

		final GlobalEntityIndex globalIndex =
			new GlobalEntityIndex(1, PRODUCT, new EntityIndexKey(EntityIndexType.GLOBAL));
		final Map<Integer, EntityIndex> indexesByPrimaryKey = CollectionUtils.createHashMap(2);
		// answers a primary key nobody holds with nothing instead of the blanket index - for the lookup registering an
		// index for modification this is a model of a lenient collection, not of the real one, which refuses such a
		// primary key with a premise error before the withdrawal sees it
		final MockEntityIndexCreator<EntityIndexKey, EntityIndex> indexes =
			new MockEntityIndexCreator<EntityIndexKey, EntityIndex>(globalIndex) {
				/** Answers the index registered under the primary key, or nothing, as the collection does. */
				@Nullable
				@Override
				public EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
					return indexesByPrimaryKey.get(indexPrimaryKey);
				}

				/** Answers the index registered under the primary key, or nothing where the collection refuses. */
				@Nullable
				@Override
				public EntityIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
					return indexesByPrimaryKey.get(indexPrimaryKey);
				}
			};
		layout.accept(indexes, indexesByPrimaryKey);

		final CatalogExpressionTriggerRegistry registry = CatalogExpressionTriggerRegistry.EMPTY
			.rebuildForEntityType(PRODUCT, List.of(new DefaultHistogramExpressionTrigger(
				PRODUCT, BRAND, Scope.LIVE, histogram,
				new HistogramValueDescriptor(
					HistogramValueSource.REFERENCE_ATTRIBUTE, null, SHARE, Integer.class, false, false, null, null, 0
				)
			)));
		final AtomicInteger sequencer = new AtomicInteger(1);
		final EntityTypeClassifierResolver classifierResolver = new EntityTypeClassifierResolver() {
			/** Resolves the sole entity type in the fixture. */
			@Override
			public int toEntityTypePrimaryKey(@Nonnull String entityType) {
				return 1;
			}

			/** Resolves the sole entity type in the fixture. */
			@Nonnull
			@Override
			public String toEntityTypeName(int entityTypePrimaryKey) {
				return PRODUCT;
			}
		};
		final ExecutorFixture fixture = new ExecutorFixture(
			() -> new EntityIndexLocalMutationExecutor(
				storage, PRODUCT_PK, indexes,
				new MockEntityIndexCreator<>(new CatalogIndex(Scope.LIVE)), () -> schema, sequencer::getAndIncrement,
				() -> new Entity(schema, PRODUCT_PK), () -> registry, null, null, classifierResolver,
				new SchemaCapabilityUsageRegistry(), new SchemaCapabilityUsageRegistry(), true,
				behavior, new PartitionReservations()
			),
			storage, schema, grouped, globalIndex, indexesByPrimaryKey
		);
		fixture.storeReference(OLD_SHARE);
		return fixture;
	}

}
