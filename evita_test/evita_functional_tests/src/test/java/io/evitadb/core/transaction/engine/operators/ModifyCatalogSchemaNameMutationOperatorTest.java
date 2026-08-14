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

package io.evitadb.core.transaction.engine.operators;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.TestCatalogFolderContexts;
import io.evitadb.core.exception.SessionBusyException;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.TRANSACTION;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests `ModifyCatalogSchemaNameMutationOperator` — the operator behind both `renameCatalog` and `replaceCatalog`.
 *
 * The subject here is **when** the surviving catalog's session registry appears under the name it is moving to,
 * relative to the engine-state commit that makes that name resolve. The commit is what publishes the new name, and
 * it does so synchronously; a registry published after it therefore leaves a window in which the name resolves to a
 * live catalog that has no registry behind it, and `Evita#createSessionInternal` fills such a name with a fresh,
 * *unsuspended* registry of its own. The handoff then displaces that registry, and any session inside it is
 * reachable through no name at all — invisible to every later quiesce, and bound to a catalog the next rename,
 * delete or shutdown terminates underneath it.
 *
 * That window is a few instructions wide, so no test can reliably race it. What a test *can* pin down is the
 * ordering that removes it, and the operator's own contract is the seam for that: `applyMutation` takes the
 * completion-phase engine-state updater as a parameter, so a test that supplies its own observes the registry map
 * at exactly the instant the commit is requested — before any part of it has run.
 *
 * The engine is mocked, which is what makes the fixtures state outright which names start with a registry: the real
 * `obtainCatalogSessionRegistry` installs one for a catalog that has none, whereas the map here is pre-populated by
 * each test. Sibling operator tests are driven the same way — see `DuplicateCatalogMutationOperatorTest`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ModifyCatalogSchemaNameMutationOperator tests")
@Tag(ENGINE)
@Tag(TRANSACTION)
@Tag(SCHEMA)
@Tag(SESSION)
class ModifyCatalogSchemaNameMutationOperatorTest {

	private static final String SOURCE_CATALOG_NAME = "renameSource";
	private static final String TARGET_CATALOG_NAME = "renameTarget";

	/**
	 * Thrown by the probe's session factory to prove it was reached — see {@link #isServing(SessionRegistry)}.
	 */
	private static final class SessionAdmitted extends RuntimeException {
		@Serial private static final long serialVersionUID = 8724551364772915826L;
	}

	/**
	 * Reports whether the registry admits session creation.
	 *
	 * `SessionRegistry#createSession` runs its factory only once the suspension check has let the caller through, so
	 * reaching the sentinel is proof that no suspension is standing. A suspended registry answers with the exception
	 * its suspend operation prescribes instead — `SessionBusyException` after the POSTPONE wait runs out, or
	 * `InstanceTerminatedException` immediately for REJECT.
	 *
	 * @param registry registry to probe
	 * @return true when the registry is serving, false when it is still suspended
	 */
	private static boolean isServing(@Nonnull SessionRegistry registry) {
		try {
			registry.createSession(
				__ -> {
					throw new SessionAdmitted();
				}
			);
			throw new GenericEvitaInternalError("The sentinel session factory must have thrown!");
		} catch (SessionAdmitted admitted) {
			return true;
		} catch (SessionBusyException | InstanceTerminatedException refused) {
			return false;
		}
	}

	/**
	 * Builds a `SessionRegistry` that owns no catalog. Nothing in these tests creates a session through it — the
	 * probe above throws before the factory can touch a catalog — so the supplier is never called.
	 *
	 * @param catalogName name the registry is created for, for readability of failures only
	 * @return a fresh registry, not suspended
	 */
	@Nonnull
	private static SessionRegistry sessionRegistryFor(@Nonnull String catalogName) {
		return new SessionRegistry(
			mock(TracingContext.class),
			() -> {
				throw new GenericEvitaInternalError(
					"Registry of catalog `" + catalogName + "` must not resolve its catalog in this test!"
				);
			},
			SessionRegistry.createDataStore()
		);
	}

	/**
	 * Builds a catalog stub carrying a schema, which is all the operator reads of a catalog it is not renaming.
	 *
	 * @return the stub, answering version 1
	 */
	@Nonnull
	private static Catalog catalogWithSchema() {
		final SealedCatalogSchema schema = mock(SealedCatalogSchema.class);
		when(schema.version()).thenReturn(1);

		final Catalog catalog = mock(Catalog.class);
		when(catalog.getSchema()).thenReturn(schema);
		return catalog;
	}

	/**
	 * Builds the catalog under rename: it answers a schema for the mutation to rewrite, and a `replace` future that
	 * completes immediately with the renamed instance.
	 *
	 * @param replacementResult the catalog the `replace` future completes with
	 * @return the stub, with `replace` wired to an already-finished future
	 */
	@Nonnull
	private static Catalog catalogYielding(@Nonnull Catalog replacementResult) {
		final Catalog catalog = catalogWithSchema();
		when(catalog.replace(any(), any()))
			.thenAnswer(__ -> new ProgressingFuture<CatalogContract>(1, ___ -> replacementResult));
		return catalog;
	}

	/**
	 * Builds an engine whose catalog lookups and session-registry map are backed by the passed maps, so that the
	 * operator's writes are observable and its reads answer consistently with them.
	 *
	 * @param catalogs   catalogs the engine knows about, by name
	 * @param registries registry map the operator reads and writes; mutated in place
	 * @return the mocked engine
	 */
	@Nonnull
	private static Evita mockEngine(
		@Nonnull Map<String, CatalogContract> catalogs,
		@Nonnull Map<String, SessionRegistry> registries
	) {
		final Evita evita = mock(Evita.class);
		when(evita.getCatalogNames()).thenReturn(catalogs.keySet());
		when(evita.getCatalogInstance(anyString()))
			.thenAnswer(invocation -> ofNullable(catalogs.get(invocation.<String>getArgument(0))));
		when(evita.getCatalogInstanceOrThrowException(anyString()))
			.thenAnswer(
				invocation -> {
					final String catalogName = invocation.getArgument(0);
					final CatalogContract catalog = catalogs.get(catalogName);
					if (catalog == null) {
						throw new CatalogNotFoundException(catalogName);
					}
					return catalog;
				}
			);
		// `obtain` differs from `get` in production only by installing a registry for a catalog that has none, and
		// every test here pre-populates the map for the names it cares about - so the two answer alike.
		when(evita.obtainCatalogSessionRegistry(anyString()))
			.thenAnswer(invocation -> ofNullable(registries.get(invocation.<String>getArgument(0))));
		when(evita.getCatalogSessionRegistry(anyString()))
			.thenAnswer(invocation -> ofNullable(registries.get(invocation.<String>getArgument(0))));
		when(evita.registerWithReplaceCatalogSessionRegistry(anyString(), any()))
			.thenAnswer(invocation -> registries.put(invocation.getArgument(0), invocation.getArgument(1)));
		doAnswer(invocation -> registries.remove(invocation.<String>getArgument(0)))
			.when(evita).removeCatalogSessionRegistryIfPresent(anyString());
		return evita;
	}

	/**
	 * Drives one rename or replace to completion on the calling thread.
	 *
	 * A same-thread executor rather than a pool, so that every assertion below runs after the whole chain - the
	 * completion phase and the undo alike - rather than racing it.
	 *
	 * @param evita              engine the operator acts on
	 * @param storageDirectory   root the folder context is built over
	 * @param mutation           the rename or replace to apply
	 * @param completionUpdater  stands in for the engine-state commit
	 * @throws ExecutionException when the operation fails, exactly as the engine would see it
	 */
	private static void applyAndAwait(
		@Nonnull Evita evita,
		@Nonnull Path storageDirectory,
		@Nonnull ModifyCatalogSchemaNameMutation mutation,
		@Nonnull Consumer<EngineStateUpdater> completionUpdater
	) throws Exception {
		// The surviving catalog keeps its folder across the rename and the completion phase relabels it, while a
		// replace retires the folder its target was living in - so both have to be there, or the run is noisy with
		// failures that have nothing to do with the test.
		Files.createDirectories(storageDirectory.resolve(SOURCE_CATALOG_NAME));
		Files.createDirectories(storageDirectory.resolve(TARGET_CATALOG_NAME));

		final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
		@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater = mock(Consumer.class);

		final ProgressingFuture<?> future = new ModifyCatalogSchemaNameMutationOperator(folderContext)
			.applyMutation(UUID.randomUUID(), mutation, evita, transitionUpdater, completionUpdater);
		future.execute(Runnable::run);
		// generous, because it can only expire on a genuine hang - the executor above has already run the
		// whole chain to completion by the time this line is reached
		future.get(30, TimeUnit.SECONDS);
	}

	@Nested
	@DisplayName("Where the target name's registry comes from, and when")
	class PublicationOrdering {

		@Test
		@DisplayName("should publish the renamed catalog's registry before the engine state is committed")
		void shouldPublishTheTargetRegistryBeforeTheEngineStateCommit(@TempDir Path storageDirectory)
			throws Exception {
			final Map<String, SessionRegistry> registries = new ConcurrentHashMap<>(4);
			final SessionRegistry sourceRegistry = sessionRegistryFor(SOURCE_CATALOG_NAME);
			registries.put(SOURCE_CATALOG_NAME, sourceRegistry);

			final Map<String, CatalogContract> catalogs = new HashMap<>(4);
			final Catalog sourceCatalog = catalogYielding(catalogWithSchema());
			catalogs.put(SOURCE_CATALOG_NAME, sourceCatalog);

			// The instant the commit is requested is the last instant at which the target name is still
			// unresolvable, so what the map holds for it here is what a session arriving a moment later finds.
			final AtomicReference<SessionRegistry> atCommitTime = new AtomicReference<>();
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);
			doAnswer(
				invocation -> {
					atCommitTime.set(registries.get(TARGET_CATALOG_NAME));
					return null;
				}
			).when(completionUpdater).accept(any());

			applyAndAwait(
				mockEngine(catalogs, registries),
				storageDirectory,
				new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, false),
				completionUpdater
			);

			assertNotNull(
				atCommitTime.get(),
				"The target name must already carry a session registry when the commit that publishes it runs - " +
					"otherwise the name resolves to a live catalog with nothing behind it, and the first session " +
					"to arrive installs a registry the handoff then throws away."
			);
			assertSame(
				atCommitTime.get(), registries.get(TARGET_CATALOG_NAME),
				"The registry published before the commit must be the one the operation leaves behind - a second " +
					"one installed afterwards would orphan every session admitted into the first."
			);
			assertNotSame(
				sourceRegistry, atCommitTime.get(),
				"The target must carry the derived view, whose catalog supplier resolves the new name."
			);
			assertNull(
				registries.get(SOURCE_CATALOG_NAME),
				"The source name stops naming a catalog once the commit lands, so it must stop carrying a registry."
			);
			assertTrue(
				isServing(sourceRegistry),
				"The suspension the rename opened with must be lifted once it has committed."
			);
		}

		@Test
		@DisplayName("should leave a replace target holding its own registry until the commit has landed")
		void shouldNotAliasTheNameOfAReplaceTargetThatCanStillBeRestored(@TempDir Path storageDirectory)
			throws Exception {
			// The mirror image of the test above, and the reason it is not simply "publish early, always". A
			// target that already names a catalog has a registry of its own holding that name from the read-only
			// phase to the handoff, so the window the early publication closes never opens here - while the
			// publication itself would cost something real. A caller captures the published registry *before* it
			// waits out a suspension, and neither `handleSuspension` nor `registerWhileNotSuspended` consults the
			// map again afterwards. So an alias taken back by a failed commit would leave that caller holding an
			// unpublished registry, resolving a target catalog the failure left standing, and registering into the
			// source registry's session map - untracked by the name it asked for, and invisible to the next
			// quiesce of it.
			final Map<String, SessionRegistry> registries = new ConcurrentHashMap<>(4);
			final SessionRegistry sourceRegistry = sessionRegistryFor(SOURCE_CATALOG_NAME);
			final SessionRegistry targetRegistry = sessionRegistryFor(TARGET_CATALOG_NAME);
			registries.put(SOURCE_CATALOG_NAME, sourceRegistry);
			registries.put(TARGET_CATALOG_NAME, targetRegistry);

			final Map<String, CatalogContract> catalogs = new HashMap<>(4);
			catalogs.put(SOURCE_CATALOG_NAME, catalogYielding(catalogWithSchema()));
			catalogs.put(TARGET_CATALOG_NAME, catalogWithSchema());

			final AtomicReference<SessionRegistry> atCommitTime = new AtomicReference<>();
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);
			doAnswer(
				invocation -> {
					atCommitTime.set(registries.get(TARGET_CATALOG_NAME));
					return null;
				}
			).when(completionUpdater).accept(any());

			applyAndAwait(
				mockEngine(catalogs, registries),
				storageDirectory,
				new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, true),
				completionUpdater
			);

			assertSame(
				targetRegistry, atCommitTime.get(),
				"While the replace can still fail, its target must answer through the registry it already had - " +
					"an alias installed here is one the undo has to take back, and a caller that captured it in " +
					"the meantime is no longer reachable through the name it opened."
			);
			assertNotSame(
				targetRegistry, registries.get(TARGET_CATALOG_NAME),
				"Once the replace has committed the name belongs to the surviving catalog, so the swap must have " +
					"happened by the time the operation returns."
			);
			assertNotSame(
				sourceRegistry, registries.get(TARGET_CATALOG_NAME),
				"The target must carry the derived view, whose catalog supplier resolves the target name."
			);
			assertNull(
				registries.get(SOURCE_CATALOG_NAME),
				"The consumed catalog's name stops naming anything, so it must stop carrying a registry."
			);
			assertTrue(
				isServing(sourceRegistry),
				"The suspension the replace opened with must be lifted once it has committed."
			);
		}

	}

	@Nested
	@DisplayName("The derived registry the target is published through shares its origin's suspension")
	class DerivedRegistry {

		@Test
		@DisplayName("should refuse sessions through the derived view until the origin is resumed")
		void shouldRefuseSessionsThroughTheDerivedViewUntilTheOriginResumes() {
			// This is what makes publishing under the target name *before* the commit safe at all: the view the
			// target is published through is already suspended, so a session that resolves the new name in the
			// window between the commit and the resume waits rather than being admitted into a registry the
			// handoff is about to displace. Give the derived view a suspension of its own - a plain field copy
			// rather than the shared reference - and the early publication silently becomes the bug it fixes.
			final SessionRegistry origin = sessionRegistryFor(SOURCE_CATALOG_NAME);
			origin.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT);

			final SessionRegistry derived = origin.withDifferentCatalogSupplier(() -> null);
			assertFalse(
				isServing(derived),
				"A view derived from a suspended registry must be suspended too - it shares the suspension, the " +
					"active sessions and the registration gate with the registry it came from."
			);

			origin.resumeOperations();
			assertTrue(
				isServing(derived),
				"Resuming the origin must release the derived view as well, or the name the clients moved to " +
					"stays shut after the operation that moved them has finished."
			);
		}

	}

	@Nested
	@DisplayName("A commit that fails leaves each name answering through the registry it started with")
	class CommitFailure {

		@Test
		@DisplayName("should unpublish the target's registry when a rename fails at the commit")
		void shouldUnpublishTheTargetRegistryWhenTheRenameCommitFails(@TempDir Path storageDirectory) {
			final Map<String, SessionRegistry> registries = new ConcurrentHashMap<>(4);
			final SessionRegistry sourceRegistry = sessionRegistryFor(SOURCE_CATALOG_NAME);
			registries.put(SOURCE_CATALOG_NAME, sourceRegistry);

			final Map<String, CatalogContract> catalogs = new HashMap<>(4);
			catalogs.put(SOURCE_CATALOG_NAME, catalogYielding(catalogWithSchema()));

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);
			doAnswer(
				invocation -> {
					throw new GenericEvitaInternalError("The engine state refused the rename!");
				}
			).when(completionUpdater).accept(any());

			assertThrows(
				ExecutionException.class,
				() -> applyAndAwait(
					mockEngine(catalogs, registries),
					storageDirectory,
					new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, false),
					completionUpdater
				)
			);

			assertNull(
				registries.get(TARGET_CATALOG_NAME),
				"A rename that failed names no catalog under the target, so a registry left published there would " +
					"refuse or serve sessions for a name that does not exist."
			);
			assertSame(
				sourceRegistry, registries.get(SOURCE_CATALOG_NAME),
				"The catalog kept its name, so it must keep the registry that name is served through."
			);
			assertTrue(
				isServing(sourceRegistry),
				"A failed rename must lift the suspension it opened with, or the catalog it did not rename spends " +
					"the rest of the process answering SessionBusyException."
			);
		}

		@Test
		@DisplayName("should restore the replace target's own registry when the commit fails")
		void shouldRestoreTheTargetsOwnRegistryWhenTheReplaceCommitFails(@TempDir Path storageDirectory) {
			final Map<String, SessionRegistry> registries = new ConcurrentHashMap<>(4);
			final SessionRegistry sourceRegistry = sessionRegistryFor(SOURCE_CATALOG_NAME);
			final SessionRegistry targetRegistry = sessionRegistryFor(TARGET_CATALOG_NAME);
			registries.put(SOURCE_CATALOG_NAME, sourceRegistry);
			registries.put(TARGET_CATALOG_NAME, targetRegistry);

			final Map<String, CatalogContract> catalogs = new HashMap<>(4);
			catalogs.put(SOURCE_CATALOG_NAME, catalogYielding(catalogWithSchema()));
			catalogs.put(TARGET_CATALOG_NAME, catalogWithSchema());

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);
			doAnswer(
				invocation -> {
					throw new GenericEvitaInternalError("The engine state refused the replace!");
				}
			).when(completionUpdater).accept(any());

			assertThrows(
				ExecutionException.class,
				() -> applyAndAwait(
					mockEngine(catalogs, registries),
					storageDirectory,
					new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, true),
					completionUpdater
				)
			);

			assertSame(
				targetRegistry, registries.get(TARGET_CATALOG_NAME),
				"The replace changed nothing, so the target must still answer through the registry it had - a " +
					"replace onto an existing catalog never publishes the derived view ahead of the commit, so " +
					"there is nothing here for the undo to restore and nothing that may have displaced it."
			);
			assertTrue(
				isServing(targetRegistry),
				"The target was quiesced with REJECT before the replace began; a failure that leaves that " +
					"suspension standing makes the catalog it did not replace answer InstanceTerminatedException " +
					"for the life of the process."
			);
			assertSame(
				sourceRegistry, registries.get(SOURCE_CATALOG_NAME),
				"The source catalog was not consumed, so it keeps the name and the registry it had."
			);
			assertTrue(
				isServing(sourceRegistry),
				"A failed replace must lift the suspension it opened the surviving catalog with."
			);
		}

	}

	@Nested
	@DisplayName("A registry that appears under the target name is refused, not tidied away")
	class ForeignRegistry {

		@Test
		@DisplayName("should refuse to commit when the target name gained a registry of its own")
		void shouldRefuseToCommitWhenTheTargetGainedAForeignRegistry(@TempDir Path storageDirectory) {
			// Unreachable in production - a rename's target names no catalog until the commit lands, and session
			// creation refuses a name it cannot resolve - which is why it is asserted rather than cleaned up after.
			// The only cleanup available after the commit is draining a registry full of live sessions and giving
			// up on it after five seconds, and that is the silent half-measure the ordering exists to remove.
			final Map<String, SessionRegistry> registries = new ConcurrentHashMap<>(4);
			final SessionRegistry sourceRegistry = sessionRegistryFor(SOURCE_CATALOG_NAME);
			final SessionRegistry foreignRegistry = sessionRegistryFor(TARGET_CATALOG_NAME);
			registries.put(SOURCE_CATALOG_NAME, sourceRegistry);

			final Map<String, CatalogContract> catalogs = new HashMap<>(4);
			catalogs.put(SOURCE_CATALOG_NAME, catalogYielding(catalogWithSchema()));

			final Evita evita = mockEngine(catalogs, registries);
			// Slipped in after the operator's read-only phase, so that it is present at the publication and absent
			// at every earlier lookup - the interleaving the assertion guards against. Re-stubbed through
			// `doAnswer`, because the `when(mock.call(...))` form would run the answer already installed by
			// `mockEngine` against Mockito's null placeholder arguments.
			doAnswer(
				invocation -> {
					registries.put(invocation.getArgument(0), invocation.getArgument(1));
					return foreignRegistry;
				}
			).when(evita).registerWithReplaceCatalogSessionRegistry(anyString(), any());

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);

			assertThrows(
				ExecutionException.class,
				() -> applyAndAwait(
					evita,
					storageDirectory,
					new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, false),
					completionUpdater
				),
				"A registry that appeared under the target name holds sessions the handoff would orphan, so the " +
					"operation must fail while failing is still free - before the commit."
			);

			assertNull(
				registries.get(TARGET_CATALOG_NAME),
				"The rename never committed, so the target name must be left naming nothing at all."
			);
			assertTrue(
				isServing(sourceRegistry),
				"The refusal happens before the commit, so it is an ordinary failure and the suspension it opened " +
					"with is lifted like any other."
			);
		}

	}

	@Nested
	@DisplayName("Progress reporting")
	class OperationName {

		@Test
		@DisplayName("should name the operation after the direction it moves the catalog in")
		void shouldProduceDescriptiveOperationName(@TempDir Path storageDirectory) {
			final ModifyCatalogSchemaNameMutationOperator operator =
				new ModifyCatalogSchemaNameMutationOperator(TestCatalogFolderContexts.onDirectory(storageDirectory));

			assertEquals(
				"Renaming catalog `" + SOURCE_CATALOG_NAME + "` to `" + TARGET_CATALOG_NAME + "`",
				operator.getOperationName(
					new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, false)
				)
			);
			assertEquals(
				"Replacing catalog `" + SOURCE_CATALOG_NAME + "` with `" + TARGET_CATALOG_NAME + "`",
				operator.getOperationName(
					new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, true)
				)
			);
		}

	}

	/**
	 * Guards the assumption every test above rests on: a rename's target name must not already be a catalog, which
	 * is what makes "nothing can have installed a registry under it" true in the first place.
	 */
	@Nested
	@DisplayName("Preconditions")
	class Preconditions {

		@Test
		@DisplayName("should refuse to rename onto a name that already names a catalog")
		void shouldRefuseToRenameOntoAnExistingName(@TempDir Path storageDirectory) {
			final Map<String, SessionRegistry> registries = new ConcurrentHashMap<>(4);
			registries.put(SOURCE_CATALOG_NAME, sessionRegistryFor(SOURCE_CATALOG_NAME));

			final Map<String, CatalogContract> catalogs = new HashMap<>(4);
			catalogs.put(SOURCE_CATALOG_NAME, catalogYielding(catalogWithSchema()));
			catalogs.put(TARGET_CATALOG_NAME, catalogWithSchema());

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater = mock(Consumer.class);

			assertThrows(
				RuntimeException.class,
				() -> applyAndAwait(
					mockEngine(catalogs, registries),
					storageDirectory,
					new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME, false),
					completionUpdater
				)
			);

			assertFalse(
				registries.containsKey(TARGET_CATALOG_NAME),
				"A rename refused before it started must not have touched the target name's registry."
			);
		}

	}

}
