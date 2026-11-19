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
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogExpressionTriggerRegistry;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.expression.trigger.DefaultHistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueDescriptor;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.GroupHistogramEntityIndex;
import io.evitadb.index.HistogramCapableEntityIndex;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry;
import io.evitadb.index.virtual.CatalogReloadTestSupport;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart.MissingReferenceBehavior;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.WAL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior on the presence check of the reference-histogram withdrawal, chosen by the hidden system
 * property `evitadb.referenceHistogram.missingValueBehavior` when the engine starts: `REFUSE` fails a write whose
 * withdrawal misses a value, `REPORT` logs the missing value at error level, skips its withdrawal and lets the write
 * go on.
 *
 * The missing value is manufactured directly - removed from the index through the engine's own objects - rather than
 * borrowed from an open defect, so the cells keep a missing value when those defects are fixed. Every engine cell
 * boots through {@link #boot}, which sets the property for the start and flips it to the opposite value right after,
 * so session writes must keep the value the engine started with. Committed WAL replay reports independently
 * of that value. The executor cells hand the behavior to the executor
 * directly. The log lines are captured on the logger of {@link ReferenceIndexMutator} and kept only when they name
 * the histogram of the cell, which no other test uses.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Missing reference-histogram value: refuse or report")
@Tag(INDEXING)
@Tag(HISTOGRAM)
// the engine cells change a JVM-wide system property, which an engine another class starts meanwhile would read
@Isolated
class MissingHistogramValueBehaviorTest implements EvitaTestSupport {
	/**
	 * The hidden system property the engine reads the behavior from, spelled out so that the name operators are told
	 * is pinned by the test.
	 */
	private static final String PROPERTY_NAME = "evitadb.referenceHistogram.missingValueBehavior";
	private static final String PRODUCT = "product";
	private static final String BRAND = "brand";
	private static final String BRAND_GROUP = "brandGroup";
	private static final String SHARE = "share";
	/**
	 * The reference of the brand reflecting the brand reference of the product.
	 */
	private static final String PRODUCTS = "products";
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
	 * The key of the histogram store of group 100.
	 */
	private static final EntityIndexKey STORE_KEY = GroupHistogramEntityIndex.keyOf(BRAND, GROUP_PK, Scope.LIVE);
	/**
	 * The primary key of a histogram store the fixtures create.
	 */
	private static final int STORE_PK = 22;
	/**
	 * The primary key of the reduced index through which the group-type index of the fixtures tracks group 100. No
	 * index is held under it: a histogram write never looks for one.
	 */
	private static final int REDUCED_INDEX_PK = 23;
	/**
	 * The start of every message the withdrawal produces for a missing value, refused or reported.
	 */
	private static final String MISSING_VALUE_PREFIX = "Cannot withdraw histogram value";
	/**
	 * The sentence a reported missing value ends with.
	 */
	private static final String REPORT_SUFFIX =
		"The value is not withdrawn and the write continues: missing histogram values are reported, not refused.";
	/**
	 * The checkpoint interval that keeps the committed tail of the write-ahead log unpublished until the engine closes.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	/**
	 * The value the property had before the cell, or `null` when it was absent.
	 */
	@Nullable private String originalPropertyValue;

	/**
	 * Remembers the value the JVM gave the property, so that the cell can change it freely.
	 */
	@BeforeEach
	void rememberProperty() {
		this.originalPropertyValue = System.getProperty(PROPERTY_NAME);
	}

	/**
	 * Gives the property back the value it had before the cell, or removes it when it was absent.
	 */
	@AfterEach
	void restoreProperty() {
		if (this.originalPropertyValue == null) {
			System.clearProperty(PROPERTY_NAME);
		} else {
			System.setProperty(PROPERTY_NAME, this.originalPropertyValue);
		}
	}

	/**
	 * The parsing of the property value, independent of any engine and of the property itself.
	 */
	@Nested
	@DisplayName("The system property")
	class SystemPropertyTest {

		/**
		 * An absent or blank value refuses, which is what an engine started without the property does.
		 */
		@Test
		@DisplayName("Refuse when the value is absent or blank")
		void shouldRefuseWhenValueIsAbsentOrBlank() {
			assertAll(
				() -> assertEquals(MissingHistogramValueBehavior.REFUSE, MissingHistogramValueBehavior.resolve(null)),
				() -> assertEquals(MissingHistogramValueBehavior.REFUSE, MissingHistogramValueBehavior.resolve("")),
				() -> assertEquals(MissingHistogramValueBehavior.REFUSE, MissingHistogramValueBehavior.resolve("  "))
			);
		}

		/**
		 * A value naming a behavior is matched ignoring case and the whitespace around it.
		 */
		@Test
		@DisplayName("Accept the name of a behavior in any case and with whitespace around it")
		void shouldAcceptBehaviorNameIgnoringCaseAndWhitespace() {
			assertAll(
				() -> assertEquals(
					MissingHistogramValueBehavior.REPORT, MissingHistogramValueBehavior.resolve("REPORT")
				),
				() -> assertEquals(
					MissingHistogramValueBehavior.REPORT, MissingHistogramValueBehavior.resolve("report")
				),
				() -> assertEquals(
					MissingHistogramValueBehavior.REPORT, MissingHistogramValueBehavior.resolve(" Report ")
				),
				() -> assertEquals(
					MissingHistogramValueBehavior.REFUSE, MissingHistogramValueBehavior.resolve("refuse")
				)
			);
		}

		/**
		 * A value naming no behavior is refused, naming the property, the value and the values it accepts.
		 */
		@Test
		@DisplayName("Refuse a value naming no behavior")
		void shouldThrowExceptionWhenValueNamesNoBehavior() {
			final EvitaInvalidUsageException refusal = assertThrows(
				EvitaInvalidUsageException.class, () -> MissingHistogramValueBehavior.resolve("REPROT")
			);

			final String message = refusal.getMessage();
			assertAll(
				() -> assertTrue(message.contains(PROPERTY_NAME), message),
				() -> assertTrue(message.contains("REPROT"), message),
				() -> assertTrue(message.contains("REFUSE"), message),
				() -> assertTrue(message.contains("REPORT"), message)
			);
		}

		/**
		 * The engine reads the property under the name operators are told.
		 */
		@Test
		@DisplayName("Read the property under the name operators are told")
		void shouldReadPropertyUnderTheNameOperatorsAreTold() {
			assertEquals(PROPERTY_NAME, MissingHistogramValueBehavior.PROPERTY);
		}
	}

	/**
	 * What the engine does with the property when it starts, independent of any catalog.
	 */
	@Nested
	@DisplayName("The start of the engine")
	class EngineStartTest {

		/**
		 * An engine started with a value of the property that names no behavior refuses to start, naming the
		 * property, the value and the values it accepts, rather than silently falling back to refusing. The refused
		 * start keeps no lock on the storage folder: an engine starts on the same directories afterwards.
		 */
		@Test
		@DisplayName("Refuse to start when the property names no behavior")
		void shouldRefuseToStartWhenPropertyIsMalformed() {
			final TestPaths paths = createTestPaths("missingHistogramValueMalformedProperty");
			try {
				final EvitaConfiguration configuration = configuration(paths, null);
				System.setProperty(PROPERTY_NAME, "REPROT");

				final EvitaInvalidUsageException refusal = assertThrows(
					EvitaInvalidUsageException.class,
					() -> {
						// an engine that starts after all is closed at once, releasing its executors and folder lock
						try (final Evita unexpected = new Evita(configuration)) {
							assertTrue(unexpected.isActive(), "An engine that starts is active");
						}
					}
				);

				final String message = refusal.getMessage();
				assertAll(
					() -> assertTrue(message.contains(PROPERTY_NAME), message),
					() -> assertTrue(message.contains("REPROT"), message),
					() -> assertTrue(message.contains("REFUSE"), message),
					() -> assertTrue(message.contains("REPORT"), message)
				);
				try (final Evita started = boot(configuration, MissingHistogramValueBehavior.REFUSE)) {
					assertTrue(started.isActive(), "The engine must start on the directories the refused start left");
				}
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * An engine started with `REPORT` warns once, naming the property, so that an operator sees the escape hatch
		 * is open; an engine started without the property warns nothing.
		 */
		@Test
		@DisplayName("Announce the reporting behavior once when the engine starts")
		void shouldAnnounceReportingModeWhenEngineStarts() {
			final TestPaths paths = createTestPaths("missingHistogramValueAnnounced");
			try {
				final EvitaConfiguration configuration = configuration(paths, null);

				final List<String> reporting = startWarnings(
					() -> boot(configuration, MissingHistogramValueBehavior.REPORT).close()
				);
				final List<String> refusing = startWarnings(() -> boot(configuration, null).close());

				assertAll(
					() -> assertEquals(1, reporting.size(), reporting::toString),
					() -> assertTrue(
						reporting.stream().allMatch(line -> line.contains(PROPERTY_NAME)), reporting::toString
					),
					() -> assertEquals(List.of(), refusing)
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Runs the action with a list appender on the logger of {@link MissingHistogramValueBehavior} and returns the
		 * warn-level messages it logged.
		 *
		 * @param action the action to run
		 * @return the warn-level messages, in order
		 */
		@Nonnull
		private static List<String> startWarnings(@Nonnull Runnable action) {
			final Logger logger = (Logger) LoggerFactory.getLogger(MissingHistogramValueBehavior.class);
			final ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			logger.addAppender(appender);
			try {
				action.run();
			} finally {
				logger.detachAppender(appender);
				appender.stop();
			}
			final List<String> warnings = new ArrayList<>(2);
			for (final ILoggingEvent event : List.copyOf(appender.list)) {
				if (event.getLevel() == Level.WARN) {
					warnings.add(event.getFormattedMessage());
				}
			}
			return warnings;
		}
	}

	/**
	 * One write through a real engine in warm-up whose withdrawal misses its value, in both behaviors and for an
	 * ungrouped and a grouped reference - the whole path from the system property to the withdrawal.
	 */
	@Nested
	@DisplayName("A write through the engine")
	class ThroughEvitaTest {

		/**
		 * The engine started without the property refuses, and keeps refusing after the property says `REPORT`: it
		 * fails the write whose withdrawal misses the old value, naming the owner, the histogram and the value, and
		 * logs no report.
		 *
		 * @param grouped whether the reference belongs to a group, so the value lives in the group's histogram store
		 */
		@ParameterizedTest(name = "grouped {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Refuse the write when the value to withdraw is missing")
		void shouldRefuseWriteWhenValueToWithdrawIsMissing(boolean grouped) {
			final String histogram = histogramName("refused", grouped);
			final TestPaths paths = createTestPaths("missingHistogramValueRefused_" + grouped);
			try (final Evita evita = boot(configuration(paths, null), null)) {
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
		 * The engine started with `REPORT` completes the write, after the property has turned to `REFUSE`, and logs
		 * one error line identifying the missing value. The final add of the write then leaves the histogram holding
		 * the new value and not the old one.
		 *
		 * @param grouped whether the reference belongs to a group, so the value lives in the group's histogram store
		 */
		@ParameterizedTest(name = "grouped {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Report the missing value and complete the write")
		void shouldReportMissingValueAndCompleteWrite(boolean grouped) {
			final String histogram = histogramName("reported", grouped);
			final TestPaths paths = createTestPaths("missingHistogramValueReported_" + grouped);
			try (final Evita evita = boot(configuration(paths, null), MissingHistogramValueBehavior.REPORT)) {
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
		 * @param grouped whether the reference belongs to a group, so the values live in the group's histogram store
		 */
		@ParameterizedTest(name = "grouped {0}")
		@ValueSource(booleans = {false, true})
		@DisplayName("Withdraw the other values when one of them is missing")
		void shouldWithdrawOtherValuesWhenOneIsMissing(boolean grouped) {
			final String histogram = histogramName("sharesSkipped", grouped);
			final TestPaths paths = createTestPaths("missingHistogramValueSkipped_" + grouped);
			try (final Evita evita = boot(configuration(paths, null), MissingHistogramValueBehavior.REPORT)) {
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
	}

	/**
	 * The withdrawal sites where a whole index is missing, driven through the executor over an index lookup that
	 * answers the missing index with nothing, as the collection does.
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
					() -> assertTrue(message.contains("histogram index primary key -1"), message),
					() -> assertEquals(List.of(), outcome.reports())
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * The reporting withdrawal logs one line for the value and returns. The final add of the same run then meets
		 * the same missing index and fails on its own assertion, which the switch does not govern - except where only
		 * the histogram store of the group is missing, which the final add creates, as the first value of a group does.
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

				final Outcome outcome = capture(histogram, () -> fixture.changeShare(executor, NEW_SHARE));

				assertNull(outcome.failure(), () -> "The withdrawal must return, but threw " + outcome.failure());
				assertEquals(1, outcome.reports().size(), outcome.reports()::toString);
				final String line = outcome.reports().get(0);
				assertAll(
					() -> assertTrue(line.startsWith(MISSING_VALUE_PREFIX), line),
					() -> assertTrue(line.contains("value " + OLD_SHARE + ","), line),
					() -> assertTrue(line.contains("histogram index primary key -1"), line)
				);

				final String finalAddMessage = site.finalAddMessage();
				if (finalAddMessage == null) {
					fixture.finishRun(executor, NEW_SHARE);
					final GroupHistogramEntityIndex store = fixture.stores().get(STORE_KEY);
					assertNotNull(store, "The final add must create the store of the group");
					assertTrue(holds(store, histogram, NEW_SHARE, PRODUCT_PK), "The store must hold the new share");
				} else {
					final GenericEvitaInternalError finalAdd = assertThrows(
						GenericEvitaInternalError.class, () -> fixture.finishRun(executor, NEW_SHARE)
					);
					assertTrue(finalAdd.getMessage().startsWith(finalAddMessage), finalAdd.getMessage());
				}
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
				assertTrue(line.contains("histogram index primary key -1"), line);
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	/**
	 * Committed WAL replay through live trunk incorporation and catalog loading.
	 */
	@Nested
	@DisplayName("Write-ahead log replay")
	@Tag(WAL)
	class ReplayTest {

		/**
		 * Applies one mutation against the same missing value through both entry points under the refusing default.
		 */
		@Test
		@DisplayName("Refuse the session mutation and report the same missing value through the replay entry")
		void shouldRefuseSessionWriteAndReportThroughReplayEntry() {
			final String histogram = "sharesByEntryPoint";
			final TestPaths paths = createTestPaths("missingHistogramValueEntryPoint");
			try (final Evita evita = boot(configuration(paths, null), null)) {
				prepareProductWithMissingValue(evita, true, histogram);
				final EntityUpsertMutation mutation = newShareMutation();
				final Outcome sessionWrite = capture(histogram, () -> evita.updateCatalog(
					TEST_CATALOG, session -> {
						session.applyMutation(mutation);
					}, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				));
				assertTrue(missingValueError(sessionWrite.failure()).getMessage().contains(histogram));
				assertEquals(List.of(), sessionWrite.reports());
				assertEquals(OLD_SHARE, readShare(evita));
				assertTrue(lacksShare(evita, true, histogram, OLD_SHARE, PRODUCT_PK));

				final Outcome replay = capture(histogram, () -> catalog(evita).applyMutation(
					evita,
					new ServerEntityUpsertMutation(
						mutation, EnumSet.allOf(ImplicitMutationBehavior.class), false, false
					)
				));
				assertNull(replay.failure(), () -> "Replay must continue: " + replay.failure());
				assertEquals(1, replay.reports().size(), replay.reports()::toString);
				assertTrue(replay.reports().getFirst().contains("value " + OLD_SHARE + ","));
				assertEquals(NEW_SHARE, readShare(evita));
				assertTrue(holdsShare(evita, true, histogram, NEW_SHARE, PRODUCT_PK));
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Writes the share through the brand's reflected reference, so the withdrawal meeting the missing value runs in
		 * the product collection while the mutation entered the brand collection. The write the brand mutation spawns
		 * there refuses when the mutation arrives through a session and reports when it arrives through the replay
		 * entry: the behavior travels with the root mutation into every collection it reaches.
		 */
		@Test
		@DisplayName("Carry the behavior of the entry point into the write reflected into another collection")
		void shouldCarryEntryPointBehaviorIntoWriteReflectedIntoAnotherCollection() {
			final String histogram = "sharesReflected";
			final TestPaths paths = createTestPaths("missingHistogramValueReflected");
			final String productSite = "entity type `" + PRODUCT + "`";
			try (final Evita evita = boot(configuration(paths, null), null)) {
				prepareReflectedProductWithMissingValue(evita, histogram);
				final EntityUpsertMutation mutation = newReflectedShareMutation();

				final Outcome sessionWrite = capture(histogram, () -> evita.updateCatalog(
					TEST_CATALOG, session -> {
						session.applyMutation(mutation);
					}, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				));
				final String refusal = missingValueError(sessionWrite.failure()).getMessage();
				assertAll(
					() -> assertTrue(refusal.contains(productSite), refusal),
					() -> assertEquals(List.of(), sessionWrite.reports()),
					() -> assertEquals(OLD_SHARE, readShare(evita)),
					() -> assertTrue(lacksShare(evita, false, histogram, OLD_SHARE, PRODUCT_PK))
				);

				final Outcome replay = capture(histogram, () -> catalog(evita).applyMutation(
					evita,
					new ServerEntityUpsertMutation(
						mutation, EnumSet.allOf(ImplicitMutationBehavior.class), false, false
					)
				));
				assertNull(replay.failure(), () -> "Replay must continue: " + replay.failure());
				assertEquals(1, replay.reports().size(), replay.reports()::toString);
				final String line = replay.reports().getFirst();
				assertAll(
					() -> assertTrue(line.contains(productSite), line),
					() -> assertTrue(line.contains("value " + OLD_SHARE + ","), line),
					() -> assertEquals(NEW_SHARE, readShare(evita)),
					() -> assertTrue(holdsShare(evita, false, histogram, NEW_SHARE, PRODUCT_PK))
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Restores the old value in the session's transactional index view so the refusing session can commit.
		 * Only the entity mutation enters the WAL, so the alive trunk still encounters and reports the missing value.
		 */
		@Test
		@DisplayName("Report a missing value during live trunk incorporation under the refusing default")
		void shouldReportDuringLiveTrunkIncorporationWithRefusingDefault() {
			final String histogram = "sharesLiveReplay";
			final TestPaths paths = createTestPaths("missingHistogramValueLiveReplay");
			try (final Evita evita = boot(configuration(paths, null), null)) {
				prepareAliveCatalogWithStoredMissingValue(evita, histogram);
				assertStoredMissingValue(evita, histogram);
				assertEquals(CatalogState.ALIVE, catalog(evita).getCatalogState());
				final long nextVersion = catalog(evita).getVersion() + 1;
				final TransactionManager manager = catalog(evita).getTransactionManager();

				final Outcome outcome = capture(histogram, () -> evita.updateCatalog(
					TEST_CATALOG, session -> {
						assertTrue(Transaction.getTransaction().isPresent(), "The repair must be session-local");
						for (final HistogramCapableEntityIndex index : shareIndexes(evita, false)) {
							index.insertHistogramValue(histogram, null, OLD_SHARE, PRODUCT_PK, Integer.class, 0);
						}
						assertTrue(holdsShare(evita, false, histogram, OLD_SHARE, PRODUCT_PK));
						session.applyMutation(newShareMutation());
					}, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				));

				assertNull(outcome.failure(), () -> "The committed write must become visible: " + outcome.failure());
				assertEquals(1, outcome.reports().size(), outcome.reports()::toString);
				assertTrue(outcome.reports().getFirst().contains("value " + OLD_SHARE + ","));
				assertEquals(nextVersion, manager.getLastDurableCatalogVersion());
				assertEquals(nextVersion, manager.getLastFinalizedCatalogVersion());
				assertEquals(CatalogState.ALIVE, catalog(evita).getCatalogState());
				assertEquals(NEW_SHARE, readShare(evita));
				assertTrue(holdsShare(evita, false, histogram, NEW_SHARE, PRODUCT_PK));
			} finally {
				cleanupTestPaths(paths);
			}
		}

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
			final EvitaConfiguration configuration = configuration(paths, null);
			Evita evita = boot(configuration, MissingHistogramValueBehavior.REPORT);
			try {
				prepareAliveCatalogWithStoredMissingValue(evita, histogram);
				evita = restart(evita, configuration, MissingHistogramValueBehavior.REPORT);
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
		 * A committed unpublished reported write replays with a report under either engine behavior. A clean close
		 * publishes the recovered state, so subsequent starts report nothing.
		 */
		@Test
		@DisplayName("Report startup replay of a reported write after switching back to refuse")
		void shouldReportReplayOfReportedWriteWhenSwitchedBackToRefuse() {
			final String histogram = "sharesReplayed";
			final TestPaths paths = createTestPaths("missingHistogramValueReplay");
			final TestPaths reportImage = createTestPaths("missingHistogramValueReplay_reportImage");
			final TestPaths refuseImage = createTestPaths("missingHistogramValueReplay_refuseImage");
			final TransactionOptions deferredCheckpoint = TransactionOptions.builder()
				.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
				.build();
			final EvitaConfiguration configuration = configuration(paths, deferredCheckpoint);
			Evita evita = boot(configuration, MissingHistogramValueBehavior.REPORT);
			try {
				prepareAliveCatalogWithStoredMissingValue(evita, histogram);
				// going live is deferred like any other round; the clean close publishes it
				evita = restart(evita, configuration, MissingHistogramValueBehavior.REPORT);
				assertStoredMissingValue(evita, histogram);

				final Evita aliveEvita = evita;
				final Outcome committed = capture(histogram, () -> updateShare(aliveEvita, false, NEW_SHARE));
				assertNull(committed.failure(), () -> "The write must complete, but threw " + committed.failure());
				final long appliedVersion = takeImagesOfUnpublishedTail(aliveEvita, paths, reportImage, refuseImage);
				evita.close();

				assertImageLoaded(
					reportImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 1
				);
				// startup reports under the refusing default; the next clean restart has no tail left
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REFUSE, deferredCheckpoint, histogram, appliedVersion, 1
				);
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REFUSE, deferredCheckpoint, histogram, appliedVersion, 0
				);
				// the recovered state is published and subsequent starts need no report
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 0
				);
				// the ordinary refusing behavior remains the engine's default
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
		 * A refusing engine commits a valid write whose stored starting histogram is missing the old value.
		 * Startup replay reports the inconsistency independently of the property and publishes the recovered state.
		 */
		@Test
		@DisplayName("Report startup replay of a write meeting a value the stored index lost before the restart")
		void shouldReportReplayOfWriteMeetingValueMissingFromStoredIndex() {
			final String histogram = "sharesDrifted";
			final TestPaths paths = createTestPaths("missingHistogramValueDrift");
			final TestPaths reportImage = createTestPaths("missingHistogramValueDrift_reportImage");
			final TestPaths refuseImage = createTestPaths("missingHistogramValueDrift_refuseImage");
			final TransactionOptions deferredCheckpoint = TransactionOptions.builder()
				.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
				.build();
			try {
				final long appliedVersion = takeImagesOfWriteMeetingValueMissingFromStoredIndex(
					paths, deferredCheckpoint, histogram, reportImage, refuseImage
				);

				// startup reports under the refusing default; the next clean restart has no tail left
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REFUSE, deferredCheckpoint, histogram, appliedVersion, 1
				);
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REFUSE, deferredCheckpoint, histogram, appliedVersion, 0
				);
				assertImageLoaded(
					reportImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 1
				);
				assertImageLoaded(
					refuseImage, MissingHistogramValueBehavior.REPORT, deferredCheckpoint, histogram, appliedVersion, 0
				);
			} finally {
				cleanupTestPaths(paths);
				cleanupTestPaths(reportImage);
				cleanupTestPaths(refuseImage);
			}
		}

		/**
		 * Startup replay reports independently of the property even when loading is deferred. The next live write
		 * still uses the behavior captured when the engine started.
		 *
		 * @param behavior the behavior the engine starts with
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(MissingHistogramValueBehavior.class)
		@DisplayName("Deferred startup replay reports and the next session write uses the configured behavior")
		void shouldReportDeferredStartupReplayAndKeepConfiguredBehaviorForSessionWrites(
			@Nonnull MissingHistogramValueBehavior behavior
		) {
			final String histogram = "sharesDeferred";
			final TestPaths paths = createTestPaths("missingHistogramValueDeferred_" + behavior);
			final TestPaths image = createTestPaths("missingHistogramValueDeferred_image_" + behavior);
			final TransactionOptions deferredCheckpoint = TransactionOptions.builder()
				.checkpointIntervalInMillis(NEVER_ELAPSES_MILLIS)
				.build();
			try {
				final long appliedVersion = takeImagesOfWriteMeetingValueMissingFromStoredIndex(
					paths, deferredCheckpoint, histogram, image
				);

				try (final Evita deferred = bootDeferred(configuration(image, deferredCheckpoint), behavior)) {
					final Outcome loading = capture(histogram, () -> {
						deferred.scheduleInitialCatalogLoading();
						deferred.waitUntilFullyInitialized();
					});

					assertNull(loading.failure(), () -> "The replay must load the catalog: " + loading.failure());
					assertEquals(1, loading.reports().size(), loading.reports()::toString);
					CatalogReloadTestSupport.awaitCatalogLoaded(deferred, TEST_CATALOG);
					assertEquals(appliedVersion, catalog(deferred).getVersion(), "The catalog must reach the write");
					assertTrue(holdsShare(deferred, false, histogram, NEW_SHARE, PRODUCT_PK));
					removeShare(deferred, false, histogram, NEW_SHARE, PRODUCT_PK);
					final Outcome written = capture(histogram, () -> updateShare(deferred, false, SUBSEQUENT_SHARE));
					switch (behavior) {
						case REPORT -> {
							assertNull(written.failure(), () -> "The write must complete: " + written.failure());
							assertEquals(2, written.reports().size(), written.reports()::toString);
							assertEquals(SUBSEQUENT_SHARE, readShare(deferred));
						}
						case REFUSE -> {
							assertNotNull(written.failure(), "The next session write must still refuse");
							assertTrue(missingValueError(written.failure()).getMessage().contains(histogram));
							assertEquals(List.of(), written.reports());
							assertEquals(NEW_SHARE, readShare(deferred));
						}
					}
				}
			} finally {
				cleanupTestPaths(paths);
				cleanupTestPaths(image);
			}
		}

		/**
		 * A catalog loaded again by its activation, after the property has turned to the opposite value. The engine
		 * restarts once, so the catalog it deactivates is the one the clean close published, missing the old share of
		 * product 1; that is asserted again after the activation, so the write cannot pass for lack of a missing value.
		 * The write then meets the missing value:
		 *
		 * - an engine started with `REPORT` completes it with a line in its session and one in the trunk;
		 * - an engine started with `REFUSE` refuses it and reports nothing.
		 *
		 * @param behavior the behavior the engine starts with
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(MissingHistogramValueBehavior.class)
		@DisplayName("Load an activated catalog under the behavior the engine started with")
		void shouldLoadAnActivatedCatalogUnderTheBehaviorTheEngineStartedWith(
			@Nonnull MissingHistogramValueBehavior behavior
		) {
			final String histogram = "sharesActivated";
			final TestPaths paths = createTestPaths("missingHistogramValueActivated_" + behavior);
			final EvitaConfiguration configuration = configuration(paths, null);
			Evita evita = boot(configuration, behavior);
			try {
				prepareAliveCatalogWithStoredMissingValue(evita, histogram);
				evita = restart(evita, configuration, behavior);
				assertStoredMissingValue(evita, histogram);

				evita.deactivateCatalog(TEST_CATALOG);
				evita.activateCatalog(TEST_CATALOG);
				assertStoredMissingValue(evita, histogram);

				final Evita activated = evita;
				final Outcome outcome = capture(histogram, () -> updateShare(activated, false, NEW_SHARE));

				switch (behavior) {
					case REPORT -> {
						assertNull(outcome.failure(), () -> "The write must complete, but threw " + outcome.failure());
						assertAll(
							() -> assertEquals(2, outcome.reports().size(), outcome.reports()::toString),
							() -> assertEquals(NEW_SHARE, readShare(activated), "The new share must be stored")
						);
					}
					case REFUSE -> {
						final String message = missingValueError(outcome.failure()).getMessage();
						assertAll(
							() -> assertTrue(message.contains("histogram `" + histogram + "`"), message),
							() -> assertEquals(List.of(), outcome.reports())
						);
					}
				}
			} finally {
				evita.close();
				cleanupTestPaths(paths);
			}
		}
	}

	/**
	 * Where a structural cell makes an index missing, and what the final add of the same run says about it.
	 */
	enum StructuralSite {
		/**
		 * The group-type index of a grouped reference is absent, and so is the histogram store of the group.
		 */
		GROUP_TYPE_INDEX_MISSING(true, "Cannot insert histogram value: group ReducedTypeEntityIndex is missing"),
		/**
		 * The group-type index does not track the group, and the histogram store of the group is absent.
		 */
		GROUP_UNTRACKED(true, "Cannot insert histogram value: the group-type index does not track group"),
		/**
		 * The group-type index tracks the group, and the histogram store of the group is absent. The final add creates
		 * the store, as the first value of a group does.
		 */
		STORE_MISSING(true, null),
		/**
		 * The referenced-type index of an ungrouped reference is absent.
		 */
		TYPE_INDEX_MISSING(false, "Cannot insert histogram value: ReferencedTypeEntityIndex is missing");

		private final boolean grouped;
		@Nullable private final String finalAddMessage;

		/**
		 * Creates the site.
		 *
		 * @param grouped         whether the reference belongs to a group
		 * @param finalAddMessage the start of the message the final add fails with, or `null` when it succeeds
		 */
		StructuralSite(boolean grouped, @Nullable String finalAddMessage) {
			this.grouped = grouped;
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
		 * Returns the start of the message the final add fails with.
		 *
		 * @return the message start, or `null` when the final add succeeds
		 */
		@Nullable
		String finalAddMessage() {
			return this.finalAddMessage;
		}
	}

	/**
	 * An executor fixture of product 1 over a real storage executor and an index lookup the cell lays out: the
	 * referenced-type and group-type indexes it registers by key, and the histogram stores of groups, which the lookup
	 * creates on demand as the collection does. Every write gets its own executor, as every entity mutation does in the
	 * engine; all of them share the storage and the indexes.
	 *
	 * @param executors   creates a new executor over the storage and the indexes
	 * @param storage     the real storage executor of product 1
	 * @param schema      the product schema whose reference carries the `share` attribute
	 * @param grouped     TRUE when the reference is stored in group 100
	 * @param globalIndex the global index passed to reference handlers
	 * @param stores      the histogram stores the lookup holds, by their keys
	 */
	private record ExecutorFixture(
		@Nonnull Supplier<EntityIndexLocalMutationExecutor> executors,
		@Nonnull ContainerizedLocalMutationExecutor storage,
		@Nonnull EntitySchema schema,
		boolean grouped,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Map<EntityIndexKey, GroupHistogramEntityIndex> stores
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
	 * Builds the configuration of an engine over the directories.
	 *
	 * @param paths              the directories of the engine
	 * @param transactionOptions the transaction options, or `null` for the defaults
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration configuration(
		@Nonnull TestPaths paths,
		@Nullable TransactionOptions transactionOptions
	) {
		final EvitaConfiguration.Builder builder = newTestEvitaConfigurationBuilder(paths);
		if (transactionOptions != null) {
			builder.transaction(transactionOptions);
		}
		return builder.build();
	}

	/**
	 * Starts an engine with the property set to the behavior, or absent for `null`, and turns the property to the
	 * opposite value as soon as the engine is constructed. Whatever the engine does from then on - creating a catalog,
	 * writing to it, loading it - sees the opposite value in the property, so it keeps the start value only when the
	 * engine fixed it at the start.
	 *
	 * @param configuration the configuration of the engine
	 * @param behavior      the behavior the property names at the start, or `null` for an absent property
	 * @return the engine, with its initial catalog loading scheduled
	 */
	@Nonnull
	private static Evita boot(
		@Nonnull EvitaConfiguration configuration, @Nullable MissingHistogramValueBehavior behavior
	) {
		setProperty(behavior);
		final Evita evita = new Evita(configuration);
		setProperty(opposite(behavior));
		return evita;
	}

	/**
	 * Starts an engine like {@link #boot} does, but without scheduling its initial catalog loading, so that no catalog
	 * is constructed before the property turns to the opposite value.
	 *
	 * @param configuration the configuration of the engine
	 * @param behavior      the behavior the property names at the start
	 * @return the engine, whose initial catalog loading the caller schedules
	 */
	@Nonnull
	private static Evita bootDeferred(
		@Nonnull EvitaConfiguration configuration, @Nonnull MissingHistogramValueBehavior behavior
	) {
		setProperty(behavior);
		final Evita evita = new Evita(configuration, false);
		setProperty(opposite(behavior));
		return evita;
	}

	/**
	 * Sets the property to the behavior, or removes it for `null`.
	 *
	 * @param behavior the behavior the property names, or `null` for an absent property
	 */
	private static void setProperty(@Nullable MissingHistogramValueBehavior behavior) {
		if (behavior == null) {
			System.clearProperty(PROPERTY_NAME);
		} else {
			System.setProperty(PROPERTY_NAME, behavior.name());
		}
	}

	/**
	 * Returns the behavior opposite to the one an engine started with: `REFUSE` for `REPORT`, and `REPORT` for
	 * `REFUSE` or an absent property, which refuses.
	 *
	 * @param behavior the behavior at the start, or `null` for an absent property
	 * @return the opposite behavior
	 */
	@Nonnull
	private static MissingHistogramValueBehavior opposite(@Nullable MissingHistogramValueBehavior behavior) {
		return behavior == MissingHistogramValueBehavior.REPORT ?
			MissingHistogramValueBehavior.REFUSE : MissingHistogramValueBehavior.REPORT;
	}

	/**
	 * Closes the engine and starts a new one on the stored data through {@link #boot}, waiting until it has loaded the
	 * catalog. A new engine whose load fails is closed before the failure propagates.
	 *
	 * @param evita         the running engine
	 * @param configuration the configuration of the engine
	 * @param behavior      the behavior the new engine starts with
	 * @return the new engine
	 */
	@Nonnull
	private static Evita restart(
		@Nonnull Evita evita,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull MissingHistogramValueBehavior behavior
	) {
		evita.close();
		final Evita restarted = boot(configuration, behavior);
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
	 * Commits a write meeting a value the stored index lost before the restart, and copies the storage with the write
	 * still in the unpublished tail into each image. The value goes back into the index in memory only, so the write
	 * commits without a refusal, while the stored index a replay of the tail starts from still misses it - memory and
	 * storage disagree as they do in a catalog whose stored index drifted. No write here meets a missing value, so
	 * every engine starts refusing.
	 *
	 * @param paths              the directories of the engine
	 * @param transactionOptions the transaction options, which must keep the tail unpublished
	 * @param histogram          the histogram name
	 * @param images             the directories to copy the storage into
	 * @return the catalog version the tail reaches
	 */
	private long takeImagesOfWriteMeetingValueMissingFromStoredIndex(
		@Nonnull TestPaths paths,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull String histogram,
		@Nonnull TestPaths... images
	) {
		final EvitaConfiguration configuration = configuration(paths, transactionOptions);
		Evita evita = boot(configuration, MissingHistogramValueBehavior.REFUSE);
		try {
			prepareAliveCatalogWithStoredMissingValue(evita, histogram);
			// going live is deferred like any other round; the clean close publishes it
			evita = restart(evita, configuration, MissingHistogramValueBehavior.REFUSE);
			assertStoredMissingValue(evita, histogram);
			for (final HistogramCapableEntityIndex index : shareIndexes(evita, false)) {
				index.insertHistogramValue(histogram, null, OLD_SHARE, PRODUCT_PK, Integer.class, 0);
			}
			assertTrue(holdsShare(evita, false, histogram, OLD_SHARE, PRODUCT_PK), "The memory must hold it again");

			final Evita aliveEvita = evita;
			final Outcome committed = capture(histogram, () -> updateShare(aliveEvita, false, NEW_SHARE));
			assertNull(committed.failure(), () -> "The write must complete, but threw " + committed.failure());
			assertEquals(List.of(), committed.reports());
			return takeImagesOfUnpublishedTail(aliveEvita, paths, images);
		} finally {
			evita.close();
		}
	}

	/**
	 * Boots an engine over the image through {@link #boot} and asserts that the catalog loads at the version the tail
	 * reaches, holding the new share in its histogram and its body, with the expected number of report lines.
	 *
	 * @param image              the directories of the image
	 * @param behavior           the behavior the engine starts with
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
			try (final Evita loaded = boot(configuration(image, transactionOptions), behavior)) {
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
	 * group component, so its histogram lives in the histogram store of the group.
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
	 * Defines the catalog in warm-up with an ungrouped reference and the brand reflecting it as `products`, which
	 * inherits the `share` attribute, so a share written through the brand reaches the histogram of the product. Then
	 * stores product 1 with the old share and removes the old share from the index the withdrawal reads.
	 *
	 * @param evita     the engine
	 * @param histogram the histogram name
	 */
	private static void prepareReflectedProductWithMissingValue(@Nonnull Evita evita, @Nonnull String histogram) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(TEST_CATALOG, session -> {
			defineSchema(session, false, histogram, Integer.class);
			session.defineEntitySchema(BRAND)
				.withReflectedReferenceToEntity(PRODUCTS, PRODUCT, BRAND, whichIs -> whichIs.withAttributesInherited())
				.updateVia(session);
			createProduct(session, PRODUCT_PK, BRAND_PK, false, OLD_SHARE);
		});
		removeShare(evita, false, histogram, OLD_SHARE, PRODUCT_PK);
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
	 * Creates the mutation that updates the existing product's share.
	 *
	 * @return the share update for the reference to brand 10
	 */
	@Nonnull
	private static EntityUpsertMutation newShareMutation() {
		return new EntityUpsertMutation(
			PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST,
			new ReferenceAttributeMutation(
				new ReferenceKey(BRAND, BRAND_PK), new UpsertAttributeMutation(SHARE, NEW_SHARE)
			)
		);
	}

	/**
	 * Creates the mutation that sets the new share through the reference of brand 10 reflecting product 1.
	 *
	 * @return the share update of the reflected reference
	 */
	@Nonnull
	private static EntityUpsertMutation newReflectedShareMutation() {
		return new EntityUpsertMutation(
			BRAND, BRAND_PK, EntityExistence.MUST_EXIST,
			new ReferenceAttributeMutation(
				new ReferenceKey(PRODUCTS, PRODUCT_PK), new UpsertAttributeMutation(SHARE, NEW_SHARE)
			)
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
	 * Returns the indexes the withdrawal reads the share from: the referenced-type index when ungrouped, or the
	 * histogram store of the group.
	 *
	 * @param evita   the engine
	 * @param grouped whether the reference belongs to the group
	 * @return the histogram-capable indexes, never empty
	 */
	@Nonnull
	private static List<HistogramCapableEntityIndex> shareIndexes(@Nonnull Evita evita, boolean grouped) {
		final EntityCollection collection = catalog(evita).getCollectionForEntityInternal(PRODUCT).orElseThrow();
		final List<HistogramCapableEntityIndex> indexes = new ArrayList<>(1);
		if (grouped) {
			indexes.add(assertInstanceOf(GroupHistogramEntityIndex.class, collection.getIndexByKeyIfExists(STORE_KEY)));
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
	 * @param evita         the engine the catalog and its storage are created in
	 * @param site          the missing index
	 * @param histogram     the histogram name
	 * @param behavior      the behavior on a missing histogram value
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
			indexes -> {
				switch (site) {
					case GROUP_TYPE_INDEX_MISSING -> {
						indexes.register(GROUP_TYPE_KEY, null);
						indexes.register(TYPE_KEY, null);
					}
					case GROUP_UNTRACKED -> {
						indexes.register(GROUP_TYPE_KEY, new ReferencedTypeEntityIndex(21, PRODUCT, GROUP_TYPE_KEY));
						indexes.register(TYPE_KEY, null);
					}
					case STORE_MISSING -> {
						// the group is tracked through a reduced index of its own, which a histogram never reaches
						final ReferencedTypeEntityIndex groupTypeIndex =
							new ReferencedTypeEntityIndex(21, PRODUCT, GROUP_TYPE_KEY);
						groupTypeIndex.insertPrimaryKeyIfMissing(REDUCED_INDEX_PK, GROUP_PK);
						indexes.register(GROUP_TYPE_KEY, groupTypeIndex);
						indexes.register(TYPE_KEY, null);
					}
					case TYPE_INDEX_MISSING -> indexes.register(TYPE_KEY, null);
				}
			}
		);
	}

	/**
	 * Builds an executor fixture of product 1 over a real storage executor and an index lookup the layout arranges. The
	 * stored reference to brand 10 holds the old share. The reference schema is unindexed, so only the histogram
	 * trigger writes indexes.
	 *
	 * @param evita         the engine the catalog and its storage are created in
	 * @param grouped       whether the reference belongs to group 100
	 * @param histogram     the histogram name
	 * @param behavior      the behavior on a missing histogram value
	 * @param layout        registers the indexes by key on the lookup
	 * @return the fixture
	 */
	@Nonnull
	private static ExecutorFixture createExecutorFixture(
		@Nonnull Evita evita,
		boolean grouped,
		@Nonnull String histogram,
		@Nonnull MissingHistogramValueBehavior behavior,
		@Nonnull Consumer<MockEntityIndexCreator<EntityIndexKey, EntityIndex>> layout
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
		final Map<EntityIndexKey, GroupHistogramEntityIndex> stores = CollectionUtils.createHashMap(1);
		// answers the key of a histogram store with the store it holds, or nothing, and creates a missing store on
		// demand - as the collection does, whose by-key lookups the blanket index stands in for otherwise
		final MockEntityIndexCreator<EntityIndexKey, EntityIndex> indexes =
			new MockEntityIndexCreator<EntityIndexKey, EntityIndex>(globalIndex) {
				/** Answers the store held under a store key, or the registered or blanket index for any other key. */
				@Nullable
				@Override
				public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
					return entityIndexKey.type() == EntityIndexType.REFERENCED_GROUP_HISTOGRAM ?
						stores.get(entityIndexKey) : super.getIndexIfExists(entityIndexKey);
				}

				/** Answers the store held under a store key, created when missing, or the index for any other key. */
				@Nonnull
				@Override
				public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
					return entityIndexKey.type() == EntityIndexType.REFERENCED_GROUP_HISTOGRAM ?
						stores.computeIfAbsent(
							entityIndexKey, key -> new GroupHistogramEntityIndex(STORE_PK, PRODUCT, key, false)
						) :
						super.getOrCreateIndex(entityIndexKey);
				}
			};
		layout.accept(indexes);

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
			storage, schema, grouped, globalIndex, stores
		);
		fixture.storeReference(OLD_SHARE);
		return fixture;
	}

}
