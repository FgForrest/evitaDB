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


package io.evitadb.store.catalog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures upgrade diagnostics while real release fixtures are migrated and reopened by the current engine.
 * Isolated execution protects the process console and migration logger while the reports are captured.
 */
@DisplayName("Migration_2026_3 — actionable reports for legacy indexes")
@Tag(STORAGE)
@Tag(INDEXING)
@Isolated
class Migration_2026_3_UpgradeReportTest implements EvitaTestSupport {
	/** Copies the released catalogs and opens them through the real migration path. */
	@Nonnull
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/** Keeps an unverifiable counter distinct from diagnosed damage and supplies the conditional remedy. */
	@Test
	@DisplayName("Should recommend conditional reindexing when a counter has no surviving filter sibling")
	void shouldRecommendReindexingForUncheckedCounter() {
		final CapturedReport report = captureReport(() -> this.harness.withUpgradedCatalog("d16", evita -> {
			assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, "d16"));
		}));
		final List<String> unchecked = report.warnings().stream()
			.filter(message -> message.contains("could NOT be checked")).toList();
		assertEquals(1, unchecked.size());
		final String message = unchecked.getFirst();
		assertTrue(message.contains("brand.label [en]"));
		assertTrue(message.contains("no sibling filter index"));
		assertTrue(message.contains("They may or may not reference entries that are no longer present"));
		assertTrue(message.contains("If filtering is incomplete or writes fail an index premise, " +
			"REINDEX entity collection `product` of catalog `d16` to rebuild its indexes."),
			"The unchecked-counter report must name the collection and catalog in its conditional REINDEX remedy");
		assertFalse(report.events().stream().anyMatch(event -> event.getLevel() == Level.ERROR));
		assertEquals(1, report.console().lines().filter(line -> line.equals(message)).count());
	}

	/** A checked catalog must not receive the unchecked-counter remedy. */
	@Test
	@DisplayName("Should omit conditional reindexing when no counter is unchecked")
	void shouldOmitReindexingWhenAllCountersAreChecked() {
		final CapturedReport report = captureReport(() -> this.harness.withUpgradedCatalog("d13", evita -> {
			assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, "d13"));
		}));
		assertFalse(report.warnings().stream().anyMatch(message -> message.contains("could NOT be checked")));
		assertFalse(report.warnings().stream().anyMatch(message -> message.contains("If filtering is incomplete")));
		assertFalse(report.console().contains("If filtering is incomplete"));
	}

	/** Names every susceptible field without claiming that its particular stored values lost information. */
	@ParameterizedTest
	@CsvSource({
		"d2,validity|validities,only values with sub-second precision",
		"d14,price validity,only values with sub-second precision",
		"d2p,price validity,only values with sub-second precision",
		"d4,name,only spellings the collator treats as equal",
		"d4f,name,only spellings the collator treats as equal",
		"d5s,sortValidity,only values with sub-second precision",
		"d5f,validity,only values with sub-second precision",
		"d5r,brand.validity,only values with sub-second precision",
		"d12,label,only spellings the collator treats as equal",
		"d12b,label,only spellings the collator treats as equal",
		"d4c,label,only spellings the collator treats as equal",
		"dU,period,only values with sub-second precision",
		"dRng,brand.validity,only values with sub-second precision",
		"d13,range,only if values were first written by 2026.1 or older"
	})
	@DisplayName("Should warn once about potentially lossy fields and omit the report on later loads")
	void shouldWarnOnceAboutPotentiallyLossyFields(
		@Nonnull String fixture, @Nonnull String fields, @Nonnull String condition
	) {
		final CapturedReport report = upgradeAndReopen(fixture);
		assertEquals(1, report.warnings().size(), "A catalog upgrade must emit one grouped loss warning");
		final String message = report.warnings().getFirst();
		assertTrue(message.startsWith("Catalog `" + fixture + "`: legacy indexes may have lost precision"));
		assertTrue(message.contains("Indexes alone cannot determine whether any value was affected."));
		for (final String field : fields.split("\\|")) {
			assertTrue(message.contains(field.equals("price validity") ? field : "attribute `" + field + "`"),
				"The warning must name every susceptible field: " + field);
		}
		assertTrue(message.contains(condition));
		assertTrue(message.endsWith("If a listed condition holds for a collection's data, " +
			"republish that collection from source data."));
		assertEquals(1, report.console().lines().filter(line -> line.equals(message)).count());
	}

	/** Earlier releases could fold distinct decimal bounds; surviving buckets cannot split their owners. */
	@Test
	@DisplayName("Should warn once about decimal bounds potentially folded by an earlier release")
	void shouldWarnOnceAboutPotentiallyFoldedDecimalRanges() {
		final CapturedReport report = upgradeAndReopen("d15");
		assertEquals(1, report.warnings().size(), "A catalog upgrade must emit one grouped loss warning");
		final String message = report.warnings().getFirst();
		assertTrue(message.startsWith("Catalog `d15`: legacy indexes may have lost precision"));
		assertTrue(message.contains("entity collection `product`"));
		assertTrue(message.contains("attribute `size` (decimal range; only if values were first written by " +
			"2026.1 or older and two of them differ only in the position of the decimal point, " +
			"such as `[12, 90]` and `[1.2, 9]`)"));
		assertTrue(message.contains("Indexes alone cannot determine whether any value was affected."));
		assertTrue(message.endsWith("If a listed condition holds for a collection's data, " +
			"republish that collection from source data."));
		assertEquals(1, report.console().lines().filter(line -> line.equals(message)).count());
	}

	/** Duplicate temporal representatives require advice even when their rows have no indexes in their scope. */
	@ParameterizedTest
	@ValueSource(strings = {"d7", "d17", "d17a"})
	@DisplayName("Should group duplicate temporal representative risks into the single upgrade warning")
	void shouldWarnAboutDuplicateTemporalRepresentatives(@Nonnull String fixture) {
		final CapturedReport report = upgradeAndReopen(fixture);
		assertEquals(1, report.warnings().size(), "A catalog upgrade must emit one grouped loss warning");
		final String message = report.warnings().getFirst();
		assertTrue(message.contains("entity collection `product`"));
		assertTrue(message.contains("reference `brand` (only if rows of this reference to one target differ " +
			"only in time zone offset or below one second; adding, editing or removing such rows may fail)"),
			"The warning must name the duplicate reference and its temporal condition and mutation consequence");
		assertTrue(message.endsWith("If a listed condition holds for a collection's data, " +
			"republish that collection from source data."));
		assertEquals(1, report.console().lines().filter(line -> line.equals(message)).count());
	}

	/** Ordinary representative attributes do not expose a duplicate reference to temporal equality changes. */
	@Test
	@DisplayName("Should omit reference risk when duplicate representatives are not temporal")
	void shouldOmitReferenceRiskForNonTemporalRepresentatives() {
		final CapturedReport report = upgradeAndReopen("dK1h");
		assertFalse(report.warnings().stream().anyMatch(message -> message.contains("reference `brand`")));
		assertFalse(report.console().contains("reference `brand`"));
	}

	/** Several collections and inherited reference attributes share one complete, uncapped report. */
	@Test
	@DisplayName("Should group several collections and reflected attributes in one warning")
	void shouldGroupCollectionsAndReflectedAttributes() {
		final CapturedReport report = upgradeAndReopen("dRR");
		assertEquals(1, report.warnings().size(), "Several collections must still produce one loss warning");
		final String message = report.warnings().getFirst();
		assertTrue(message.contains("entity collection `brand`"));
		assertTrue(message.contains("entity collection `product`"));
		for (final String field : new String[]{"products.validity", "products.localizedName",
			"brand.validity", "brand.localizedName"}) {
			assertTrue(message.contains("attribute `" + field + "`"), "Missing reflected field " + field);
		}
		assertTrue(message.contains("only values with sub-second precision"));
		assertTrue(message.contains("only spellings the collator treats as equal"));
		assertEquals(1, report.console().lines().filter(line -> line.equals(message)).count());
	}

	/** Pins the complete operator-facing wording and the two independently named scalar and array attributes. */
	@Test
	@DisplayName("Should state the exact conditional republish recommendation for fractional range fields")
	void shouldDescribeFractionalRangeRiskPrecisely() {
		final CapturedReport report = upgradeAndReopen("d2");
		assertEquals(List.of(
			"Catalog `d2`: legacy indexes may have lost precision or distinct spellings. " +
				"Indexes alone cannot determine whether any value was affected. Potentially affected fields: " +
				"entity collection `product`: attribute `validities` " +
				"(DateTimeRange; only values with sub-second precision), attribute `validity` " +
				"(DateTimeRange; only values with sub-second precision). " +
				"If a listed condition holds for a collection's data, republish that collection from source data."
		), report.warnings());
	}

	/** A reflected reference can inherit a compound by name while it excludes one of the compound's elements. */
	@Test
	@DisplayName("Should name only the compound elements a reflected reference inherits")
	void shouldNameOnlyCompoundElementsTheReflectedReferenceInherits() {
		final TestPaths paths = createTestPaths("PartiallyInheritedCompound");
		try (final Evita evita = this.harness.boot(paths)) {
			evita.defineCatalog("compounds");
			try (final EvitaSessionContract session = evita.createReadWriteSession("compounds")) {
				session.defineEntitySchema("brand")
					.withLocale(Locale.ENGLISH)
					.withReflectedReferenceToEntity(
						"products", "product", "brand", whichIs -> whichIs.withAttributesInheritedExcept("label")
					)
					.updateVia(session);
				session.defineEntitySchema("product")
					.withLocale(Locale.ENGLISH)
					.withReferenceToEntity(
						"brand", "brand", Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.withAttribute("validity", DateTimeRange.class)
							.withAttribute("label", String.class, thatIs -> thatIs.localized())
							.withSortableAttributeCompound(
								"validityLabel", attributeElement("validity"), attributeElement("label")
							)
					)
					.updateVia(session);
				final ReferenceSchemaContract reflected =
					session.getEntitySchemaOrThrowException("brand").getReferenceOrThrowException("products");
				assertTrue(reflected.getSortableAttributeCompounds().containsKey("validityLabel"));
				assertFalse(reflected.getAttributes().containsKey("label"));
				final Set<String> affected = new TreeSet<>();

				Migration_2026_3.collectPotentiallyLossyAttributes(
					reflected.getAttributes(), reflected.getSortableAttributeCompounds(),
					reflected.getIndexedInScopes(), "products.", affected
				);

				assertEquals(
					Set.of("attribute `products.validity` (DateTimeRange; only values with sub-second precision)"),
					affected
				);
			}
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** Plain text representatives are outside the susceptible temporal, localized text and decimal types. */
	@Test
	@DisplayName("Should omit the loss warning when no indexed field has a susceptible type")
	void shouldOmitLossWarningForOtherTypes() {
		final CapturedReport report = upgradeAndReopen("dK1h");
		assertTrue(report.warnings().isEmpty());
		assertFalse(report.console().contains("Potentially affected fields:"));
		assertFalse(report.console().contains("republish that collection from source data"));
	}

	/** Current writes with the same susceptible schema are outside the release migration's reporting path. */
	@Test
	@DisplayName("Should omit the migration loss warning for a fresh current catalog")
	void shouldOmitLossWarningForFreshCurrentCatalog() {
		final CapturedReport report = captureReport(() -> this.harness.withFreshCatalog("d2", evita -> {
			assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, "d2"));
		}));
		assertTrue(report.warnings().isEmpty());
		assertFalse(report.console().contains("Potentially affected fields:"));
	}

	/**
	 * Migrates real fixture bytes once and verifies a second engine boot emits no upgrade reminder.
	 *
	 * @param fixture the release catalog copied into this test's private directories
	 * @return the first boot's report
	 */
	@Nonnull
	private CapturedReport upgradeAndReopen(@Nonnull String fixture) {
		final TestPaths paths = this.harness.copy(fixture);
		try {
			final CapturedReport upgraded = captureReport(() -> bootFixture(paths, fixture));
			final CapturedReport reopened = captureReport(() -> bootFixture(paths, fixture));
			assertTrue(reopened.warnings().isEmpty(), "A later load must not repeat migration warnings");
			assertFalse(reopened.console().contains("Potentially affected fields:"));
			return upgraded;
		} finally {
			this.harness.cleanup(paths);
		}
	}

	/** Opens the real engine, waits for migration completion and verifies the published protocol. */
	private void bootFixture(@Nonnull TestPaths paths, @Nonnull String fixture) {
		try (final Evita evita = this.harness.boot(paths)) {
			ReleaseFixtureHarness.requireAlive(evita, fixture);
			assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, fixture));
		}
	}

	/**
	 * Uses the module's Logback list-appender pattern and console capture while an actual migration runs.
	 *
	 * @param upgrade the real fixture operation, with no mocked services
	 * @return the logger events and undecorated console text
	 */
	@Nonnull
	private static CapturedReport captureReport(@Nonnull Runnable upgrade) {
		final Logger logger = (Logger) LoggerFactory.getLogger(Migration_2026_3.class);
		final Level originalLevel = logger.getLevel();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		final PrintStream originalOut = System.out;
		final ByteArrayOutputStream console = new ByteArrayOutputStream();
		appender.start();
		logger.setLevel(Level.WARN);
		logger.addAppender(appender);
		try (final PrintStream output = new PrintStream(console, true, StandardCharsets.UTF_8)) {
			System.setOut(output);
			upgrade.run();
		} finally {
			System.setOut(originalOut);
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
		return new CapturedReport(List.copyOf(appender.list), console.toString(StandardCharsets.UTF_8)
			.replaceAll("\u001B\\[[;\\d]*m", ""));
	}

	/**
	 * A stable snapshot taken after the catalog loader completed its migration.
	 *
	 * @param events the migration logger's events, including their levels
	 * @param console the same boot's console output with ANSI decoration removed
	 */
	private record CapturedReport(@Nonnull List<ILoggingEvent> events, @Nonnull String console) {
		/** Returns the WARN messages without losing the number of distinct emitted entries. */
		@Nonnull
		private List<String> warnings() {
			return this.events.stream().filter(event -> event.getLevel() == Level.WARN)
				.map(ILoggingEvent::getFormattedMessage).toList();
		}
	}
}
