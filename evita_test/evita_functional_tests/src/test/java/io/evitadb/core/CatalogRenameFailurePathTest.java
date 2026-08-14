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

package io.evitadb.core;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.exception.EvitaError;
import io.evitadb.spi.store.catalog.persistence.CatalogHandoverFailedException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers what a **failed** rename leaves behind, which issue #1414 reported as its second symptom and which no
 * test reaches: every other rename test asserts about the success path.
 *
 * The issue reported three consequences of a rename that threw after the point of no return - sessions on the
 * former name suspended for ever (surfacing as `SessionBusyException`), a catalog reachable under neither name,
 * and an engine that could not release its folder lock, so the next boot in the same JVM failed with
 * `FolderAlreadyUsedException` *after* `close()` had returned. The folder decoupling is expected to have removed
 * all three - the lock now sits once on the storage root rather than per catalog, and a rename commits nothing
 * until the storage work has succeeded - but "expected to" is what this test exists to replace.
 *
 * **The first two tests found live defects, and both are calibrated against the fixes that closed them.** Drop
 * `prevailingCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations)` from `undoOperations` in
 * `ModifyCatalogSchemaNameMutationOperator#doReplaceCatalogInternal` and the rename test fails reading the
 * catalog back, with `SessionBusyException`. Drop the `quiescedTargetRegistry` resume beside it and the replace
 * test fails the same way with `InstanceTerminatedException`, thrown out of
 * `SessionRegistry#awaitResumeOrRefuse` - the catalog that was *not* replaced refusing every session it is
 * offered. Both suspensions used to be lifted on the success path only.
 *
 * The third covers the *other* undo branch, which the second deliberately avoids: a target nobody has opened a
 * session on since boot has no registry, so the operation installs one purely to quiesce it, and the undo has to
 * resume that registry **in place** rather than restore or unpublish it. Calibrated by dropping the
 * `resumeOperations` call from that branch - the target then answers `InstanceTerminatedException` for the rest
 * of the process.
 *
 * The last two pin the invariants that made the declaration necessary, and both were written red against the
 * behaviour they now forbid: a session served under the surviving name observed the *target's* schema name, and a
 * single retried write was appended durably to the write-ahead log before the commit pipeline died on that
 * poisoned name (`ExpandedEngineState#replaceCatalogReference` found no such catalog) - after which the next boot
 * failed replaying it, which is issue #1414's headline symptom re-created by the branch that fixes it. They are
 * written against the invariant rather than against the fix, so they stay green whether the window is closed
 * (prepare-then-commit in the store) or declared, as it is today.
 *
 * **How the failure is injected, and where it actually lands.** No production seam is used and no timing is raced.
 * Revoking read permission on the folder's data file before the rename fails `replaceWith` deterministically, and
 * the cause chain says exactly where: `SyncFailedException` wrapping an `AccessDeniedException`, raised by the
 * offset-index flush inside `recordBootstrap`. That is **before** the former persistence service is closed, not
 * after - an earlier version of this comment claimed the failure landed in the takeover constructor, and it does
 * not. The distinction is worth the words, because it is the whole reason the catalog is unusable afterwards: the
 * header naming the incoming catalog has already been written, so the folder's stored identity disagrees with
 * engine state, while the service that could still write to it is very much alive. That is the point of no return
 * (`CatalogHandoverFailedException`), and it is reached here without any service ever being lost.
 *
 * **The same revocation lands on either side of that line, depending on how warm the engine is**, and the suite
 * covers both deliberately. Warm, the offset index has writes buffered and the failure comes at the flush, past
 * the relabel. Freshly booted, it has none, so `replaceWith` fails opening the file before writing anything and
 * the catalog is untouched - `shouldLeaveTheTargetServingWhenTheFailedReplaceInstalledItsRegistry` asserts that
 * this one is *not* reported as a handover failure. Without that pairing nothing stops the marker widening until
 * every failed rename takes a healthy catalog offline.
 *
 * POSIX permissions are also why the test is confined to Linux and macOS, and why it skips itself when the
 * revocation does not bite, which is what happens when the suite runs as root.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("A rename or replace that fails partway through its storage handover")
@Tag(STORAGE)
@Tag(MANAGEMENT)
@Tag(SESSION)
@EnabledOnOs({OS.LINUX, OS.MAC})
class CatalogRenameFailurePathTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_PAYLOAD = "payload";
	private static final String RENAMED_CATALOG = "renamedCatalog";
	private static final String REPLACED_CATALOG = "replacedCatalog";
	private static final String COMMITTED_VALUE = "committed before the failed rename";
	private static final String TARGET_VALUE = "committed into the catalog being replaced away";
	private static final String CATALOG_FILE_SUFFIX = ".catalog";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogRenameFailurePathTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Refuses sessions legibly under its former name, and recovers on restart")
	void shouldRefuseSessionsLegiblyWhenRenameFailsPastTheFolderRelabel() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);

		failRenamePastTheFolderRelabel();

		// The name stays listed, and must: the commit never ran, so engine state still binds it to its folder.
		// What changed is the *instance* behind the name. What must never happen is the symptom the issue is
		// named after - the name answering `SessionBusyException` for the life of the process, because the
		// operator resumed its sessions only on the success path.
		assertTrue(
			this.evita.getCatalogNames().contains(TEST_CATALOG),
			"A failed rename must leave the catalog under the name it started with!"
		);

		// **This test asserted the opposite until the write path was measured.** The catalog was left serving,
		// and reads did work - out of memory. Behind them the persistence service was closed, and a single
		// write accepted afterwards was appended to the write-ahead log and then wedged the next boot
		// replaying it. Serving a catalog whose persistence layer is gone is not availability, it is a trap
		// that converts "recoverable by restart" into "unloadable at boot", so the honest answer past the
		// handover is a refusal that says exactly what happened and leaves the restart able to repair it.
		final CatalogCorruptedException refusal = assertThrows(
			CatalogCorruptedException.class,
			() -> readPayload(TEST_CATALOG, 1),
			"A rename that failed past its point of no return must refuse sessions legibly!"
		);
		// walked rather than read off `getCause()` directly: the failure travels out of a nested future and
		// arrives wrapped, and it is the marker's presence that matters, not its depth
		assertTrue(
			carriesHandoverFailure(refusal),
			"The refusal must name the failed handover in its cause chain, not merely report corruption!"
		);

		// the reported symptom: `close()` returned, yet the lock was still held and the next boot in the same
		// JVM refused to start. The restart is also what *lifts* the refusal above - the folder is reconciled
		// against the name engine state binds it to, so the catalog comes back whole and under its own name.
		restartEngine();

		assertTrue(
			this.evita.getCatalogNames().contains(TEST_CATALOG),
			"The catalog must come back from the restart that follows a failed rename!"
		);
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"The transaction committed before the failed rename must survive the restart!"
		);
	}

	@Test
	@DisplayName("Refuses only the source of a failed replace, and leaves its target serving")
	void shouldRefuseOnlyTheSourceWhenReplaceFailsPastTheFolderRelabel() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);
		defineCatalogAndGoLive(REPLACED_CATALOG);
		commitProduct(REPLACED_CATALOG, 2, TARGET_VALUE);

		// the target's registry must already exist when the replace starts, or the operation installs one purely
		// to quiesce it and the undo path takes a different branch than the one under test here
		assertEquals(TARGET_VALUE, readPayload(REPLACED_CATALOG, 2));

		// the *source* is the catalog that survives a replace, so failing its handover fails the operation at
		// the same point the rename case does
		final Path dataFile = soleCatalogDataFile(catalogFolder(TEST_CATALOG));
		final Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(dataFile);

		try {
			Files.setPosixFilePermissions(dataFile, Collections.emptySet());
			assumeTrue(
				isUnreadable(dataFile),
				"Revoking read permission did not bite - the suite is running as a user that ignores it."
			);

			assertThrows(
				RuntimeException.class,
				() -> this.evita.replaceCatalog(TEST_CATALOG, REPLACED_CATALOG),
				"The replace must report the failure rather than swallow it!"
			);
		} finally {
			Files.setPosixFilePermissions(dataFile, originalPermissions);
		}

		// The source is the catalog whose handover failed, so it - and only it - is refused past that point,
		// for the reason spelled out in the rename test above.
		assertThrows(
			CatalogCorruptedException.class,
			() -> readPayload(TEST_CATALOG, 1),
			"A replace that failed past its point of no return must refuse sessions on the source legibly!"
		);
		// The target is the half of a replace that the handover never touches: its folder, its service and its
		// data are exactly as they were, and the operation owes it nothing but the lifting of the REJECT
		// suspension it opened with. Refusing it too would be the failure the branch already fixed, dressed
		// up as caution - so the blast radius of the declaration above has to stop here, and this asserts it.
		assertEquals(
			TARGET_VALUE, readPayload(REPLACED_CATALOG, 2),
			"A failed replace must leave the target catalog readable rather than rejecting sessions!"
		);

		restartEngine();

		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"The source catalog must survive the restart that follows a failed replace!"
		);
		assertEquals(
			TARGET_VALUE, readPayload(REPLACED_CATALOG, 2),
			"The target catalog must survive the restart that follows a failed replace!"
		);
	}

	@Test
	@DisplayName("Leaves the target serving when it installed a registry purely to quiesce it")
	void shouldLeaveTheTargetServingWhenTheFailedReplaceInstalledItsRegistry() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);
		defineCatalogAndGoLive(REPLACED_CATALOG);
		commitProduct(REPLACED_CATALOG, 2, TARGET_VALUE);

		// session registries live in memory only, so a restart is what produces a catalog that has none - and
		// that is the branch where the operation has to install one before it can quiesce the target at all
		restartEngine();
		assertTrue(
			this.evita.getCatalogSessionRegistry(REPLACED_CATALOG).isEmpty(),
			"The target must start with no session registry, or this test exercises the other undo branch!"
		);

		final Path dataFile = soleCatalogDataFile(catalogFolder(TEST_CATALOG));
		final Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(dataFile);

		try {
			Files.setPosixFilePermissions(dataFile, Collections.emptySet());
			assumeTrue(
				isUnreadable(dataFile),
				"Revoking read permission did not bite - the suite is running as a user that ignores it."
			);

			// **This injection lands on the other side of the point of no return from its siblings, and that is
			// the point of the test.** On a freshly booted engine the offset index has nothing buffered, so the
			// first thing `replaceWith` does is *open* the data file - which fails outright
			// (`UnexpectedIOException` wrapping `FileNotFoundException`) before a single byte of the relabel is
			// written. The sibling tests keep the engine warm, so the same revocation instead survives as far as
			// the flush inside `recordBootstrap` and leaves the header already naming the incoming catalog.
			// Same fault, same file, opposite verdicts - and the assertions below are what hold the engine to
			// telling them apart.
			final RuntimeException reported = assertThrows(
				RuntimeException.class,
				() -> this.evita.replaceCatalog(TEST_CATALOG, REPLACED_CATALOG),
				"The replace must report the failure rather than swallow it!"
			);
			assertFalse(
				carriesHandoverFailure(reported),
				"A replace that failed before it relabelled anything must not be reported as a handover " +
					"failure - declaring this one unusable would take a healthy catalog offline!"
			);
		} finally {
			Files.setPosixFilePermissions(dataFile, originalPermissions);
		}

		// Asserted before anything opens a session, so the registry can only be the one the operation installed
		// to quiesce the target: it stays published and is resumed in place, which is exactly the state the
		// first session would have left it in. The alternative - unpublishing it - orphans whatever sessions
		// outlived a drain that gave up, since every later quiesce walks the registry map.
		assertTrue(
			this.evita.getCatalogSessionRegistry(REPLACED_CATALOG).isPresent(),
			"The failed replace must keep the registry it installed, rather than unpublishing it!"
		);
		assertEquals(
			TARGET_VALUE, readPayload(REPLACED_CATALOG, 2),
			"A failed replace must leave an untouched target readable rather than rejecting sessions!"
		);
		// The source stays readable here, where its sibling above is refused, and the difference is not
		// arbitrary: this failure never reached the relabel, so there is nothing about the catalog that a
		// restart would need to repair and no reason to take it offline. Compensation is the right answer on
		// this side of the line, and this asserts that the declaration does not creep across it.
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"A replace that failed before it relabelled anything must leave the source catalog readable!"
		);

		// **The only place the deferred schema exchange can be observed, and therefore the only place that
		// holds it honest.** Past the relabel the catalog is refused outright, so a schema naming the wrong
		// catalog would sit there unseen; here the catalog keeps serving, so an exchange performed ahead of
		// the handover is visible through a plain session - and dangerous, because the commit pipeline looks
		// a catalog up by the name its schema reports. A write accepted against a source still claiming the
		// target's name is appended to the write-ahead log and wedges the next boot, which is the failure
		// the sibling tests exist for, reached by a route the declaration deliberately does not guard.
		assertEquals(
			TEST_CATALOG,
			this.evita.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, String>) session -> session.getCatalogSchema().getName()
			),
			"A replace that failed before it relabelled anything must leave the source's schema naming the " +
				"source - it is still serving, so a schema naming the replace's target poisons every write!"
		);
	}

	@Test
	@DisplayName("Never serves the target's schema name through a session after a failed rename")
	void shouldNeverServeTargetSchemaNameWhenRenameFailsPastTheFolderRelabel() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);

		failRenamePastTheFolderRelabel();

		// Two outcomes honour the invariant, and the test deliberately accepts both so it survives either fix:
		// a legible refusal (the catalog declares itself unusable past the point of no return), or a served
		// session that tells the truth (the handover never destroyed anything, so there is nothing to hide).
		// What must never happen is what happens today: a session is served, and it observes a catalog whose
		// schema names the target of the rename that just FAILED - listed as `testCatalog`, answering
		// `renamedCatalog`. Every consumer keying on the schema name - including the engine's own commit
		// pipeline, see the sibling test - is lied to from that moment on.
		try {
			final String reportedName = this.evita.queryCatalog(
				TEST_CATALOG,
				(Function<EvitaSessionContract, String>) session -> session.getCatalogSchema().getName()
			);
			assertEquals(
				TEST_CATALOG, reportedName,
				"A session served after a failed rename must never observe the schema of the name the " +
					"catalog does NOT answer to!"
			);
		} catch (RuntimeException refusal) {
			// a refusal is the other acceptable outcome, but only a legible one: every deliberate evitaDB
			// exception carries the `EvitaError` contract, and a bare NPE or wrapper leaking from a damaged
			// internal state does not
			assertTrue(
				refusal instanceof EvitaError,
				() -> "A refusal after a failed rename must be a legible evitaDB error, not " +
					refusal.getClass().getName() + "!"
			);
		}
	}

	@Test
	@DisplayName("Survives the restart even when a client retried a write after the failed rename")
	void shouldRecoverOnRestartWhenWriteWasAttemptedAfterTheFailedRename() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);

		failRenamePastTheFolderRelabel();

		// The retry a real client makes after seeing the rename fail. Its own outcome is deliberately not
		// asserted - a legible refusal and a successful commit are both acceptable, depending on which fix
		// lands - but it must not be allowed to hang the test, so it runs on a bounded daemon thread.
		attemptWriteIgnoringOutcome(TEST_CATALOG, 7, "retried after the failed rename");

		// The write above must not have poisoned the write-ahead log. Today it does: the commit pipeline
		// appends the transaction durably and then dies looking the catalog up by its poisoned schema name
		// (`ExpandedEngineState#replaceCatalogReference` finds no `renamedCatalog`), and the next boot dies
		// replaying it - `processWriteAheadLog` stages a catalog named `renamedCatalog` that has no folder
		// binding. One retried write converts "recoverable by restart" - which the sibling test proves holds
		// when nothing touches the damaged catalog - into "unloadable at boot", which is issue #1414's
		// headline symptom re-created. Whatever a fix does with the write itself, the boot must recover.
		restartEngine();

		assertTrue(
			this.evita.getCatalogNames().contains(TEST_CATALOG),
			"The catalog must come back from the restart even though a write was attempted after the " +
				"failed rename!"
		);
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"The transaction committed before the failed rename must survive the restart even though a " +
				"write was attempted after it!"
		);
	}

	/**
	 * Tells whether a failure carries the storage layer's point-of-no-return marker anywhere in its cause
	 * chain - the signal that the folder had already been relabelled when the handover failed.
	 *
	 * @param failure failure reported to the caller
	 * @return true when the handover marker is present
	 */
	private static boolean carriesHandoverFailure(@Nonnull Throwable failure) {
		Throwable current = failure;
		while (current != null && current != current.getCause()) {
			if (current instanceof CatalogHandoverFailedException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * Fails a rename of {@link #TEST_CATALOG} to {@link #RENAMED_CATALOG} deterministically inside the
	 * persistence-service handover - after the former service is closed - by revoking read permission on the
	 * catalog's data file for the duration of the operation. See the class javadoc for why this seam and no
	 * other reaches the point the issue's second shape failed at.
	 */
	private void failRenamePastTheFolderRelabel() throws IOException {
		final Path dataFile = soleCatalogDataFile(catalogFolder(TEST_CATALOG));
		final Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(dataFile);
		try {
			Files.setPosixFilePermissions(dataFile, Collections.emptySet());
			assumeTrue(
				isUnreadable(dataFile),
				"Revoking read permission did not bite - the suite is running as a user that ignores it."
			);
			assertThrows(
				RuntimeException.class,
				() -> this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG),
				"The rename must report the failure rather than swallow it!"
			);
		} finally {
			// restored before anything else touches the catalog - a wedged fixture would otherwise be
			// indistinguishable from the defect under test, and would take the cleanup down with it
			Files.setPosixFilePermissions(dataFile, originalPermissions);
		}
	}

	/**
	 * Commits a single product on a bounded daemon thread and swallows whatever the attempt produces -
	 * success, refusal or timeout. Used where the *consequences* of an attempted write are under test rather
	 * than the write itself, so its outcome must not decide the test and its potential hang must not stall it.
	 *
	 * @param catalogName    catalog to write into
	 * @param primaryKey     primary key of the product
	 * @param attributeValue value stored in the payload attribute
	 */
	private void attemptWriteIgnoringOutcome(
		@Nonnull String catalogName,
		int primaryKey,
		@Nonnull String attributeValue
	) {
		final ExecutorService executor = Executors.newSingleThreadExecutor(
			runnable -> {
				final Thread thread = new Thread(runnable, "bounded-write-attempt");
				thread.setDaemon(true);
				return thread;
			}
		);
		try {
			executor
				.submit(() -> commitProduct(catalogName, primaryKey, attributeValue))
				.get(30, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException ex) {
			// deliberately ignored - the attempt's outcome is not what this test asserts about
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Closes the engine and boots a new one over the same storage folder.
	 */
	private void restartEngine() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Returns the single catalog data file of the passed folder - the file the reopen constructor opens afresh,
	 * and therefore the one whose permissions decide whether the handover succeeds.
	 *
	 * @param catalogDirectory folder to inspect
	 * @return path of the data file, never null
	 */
	@Nonnull
	private static Path soleCatalogDataFile(@Nonnull Path catalogDirectory) {
		final File[] dataFiles = catalogDirectory.toFile().listFiles(
			(dir, name) -> name.endsWith(CATALOG_FILE_SUFFIX)
		);
		assertNotNull(dataFiles, "The catalog folder must be listable!");
		assertEquals(
			1, dataFiles.length,
			() -> "Exactly one catalog data file is expected in a freshly written catalog!"
		);
		return dataFiles[0].toPath();
	}

	/**
	 * Tells whether the passed file genuinely cannot be opened for reading, which permission bits alone do not
	 * settle - a superuser ignores them entirely.
	 *
	 * @param file file to probe
	 * @return true when opening the file for reading fails
	 */
	private static boolean isUnreadable(@Nonnull Path file) {
		try (final InputStream stream = Files.newInputStream(file)) {
			assertNotNull(stream);
			return false;
		} catch (IOException ex) {
			return true;
		}
	}

	/**
	 * Resolves the folder the passed catalog is bound to - an opaque token, not the catalog's name.
	 *
	 * @param catalogName the catalog whose folder to resolve
	 * @return the folder path, never null
	 */
	@Nonnull
	private Path catalogFolder(@Nonnull String catalogName) {
		return this.paths.storage()
			.resolve(this.evita.getCatalogFolderContext().folderIdFor(catalogName).id());
	}

	/**
	 * Commits a single product in its own transaction and waits until the change is visible.
	 *
	 * @param catalogName    catalog to write into
	 * @param primaryKey     primary key of the product
	 * @param attributeValue value stored in the payload attribute
	 */
	private void commitProduct(@Nonnull String catalogName, int primaryKey, @Nonnull String attributeValue) {
		final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(catalogName, SessionFlags.READ_WRITE));
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, primaryKey)
			       .setAttribute(ATTRIBUTE_PAYLOAD, attributeValue)
		);
		assertNotNull(session.closeNowWithProgress().onChangesVisible().toCompletableFuture().join());
	}

	/**
	 * Reads a single product's payload attribute back through a fresh session.
	 *
	 * @param catalogName catalog to read from
	 * @param primaryKey  primary key of the product
	 * @return the stored payload
	 */
	@Nonnull
	private String readPayload(@Nonnull String catalogName, int primaryKey) {
		return this.evita.queryCatalog(
			catalogName,
			session -> {
				return session.getEntity(Entities.PRODUCT, primaryKey, attributeContentAll())
					.orElseThrow(() -> new AssertionError("The product must be present!"))
					.getAttribute(ATTRIBUTE_PAYLOAD, String.class);
			}
		);
	}

	/**
	 * Defines the passed catalog with a single product schema and takes it live.
	 *
	 * @param catalogName name of the catalog to define
	 */
	private void defineCatalogAndGoLive(@Nonnull String catalogName) {
		this.evita.defineCatalog(catalogName);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
				       .withoutGeneratedPrimaryKey()
				       .withAttribute(ATTRIBUTE_PAYLOAD, String.class)
				       .updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Stock storage options - nothing here depends on the log or on time travel.
	 *
	 * @return the configuration, never null
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.build();
	}

}
