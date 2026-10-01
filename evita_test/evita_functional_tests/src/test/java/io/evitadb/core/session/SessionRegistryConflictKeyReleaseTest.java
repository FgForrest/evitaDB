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

package io.evitadb.core.session;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ReflectionLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a read-write session still detects write conflicts with transactions committed after its version when
 * the conflict keys of those transactions were released while the session was being built.
 *
 * Conflict keys are released by departures of read-write sessions, down to the lowest version the read-write census
 * still names. A session that is being built has already captured its version but is not in the census yet, so a
 * departure inside that window can release the keys the session will need to check its commit against. That alone is
 * survivable - the conflict ring buffer reports the released range as out of scope and the commit falls back to
 * re-deriving the keys from the write-ahead log. It turns into a silently lost update only when the ring buffer then
 * claims to cover the released range again, which is what a later release reporting the session's own, lower version
 * did while the buffer happened to hold no keys at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conflict keys released while a read-write session is built")
@Tag(ENGINE)
@Tag(SESSION)
@Tag(TRANSACTION)
class SessionRegistryConflictKeyReleaseTest implements EvitaTestSupport {
	private static final String CATALOG = "conflictKeyReleaseCatalog";
	private static final String ATTRIBUTE_CODE = "code";
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("SessionRegistryConflictKeyReleaseTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Rewrites the code of product 1 in a session of its own - a write every other write of that product conflicts
	 * with under the default entity-scoped conflict policy.
	 *
	 * @param code the new code of the product
	 */
	private void commitProductCode(String code) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_CODE, code)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Opens and closes a read-write session on the current version without changing anything, so that its departure
	 * releases the conflict keys of every version below the lowest one the read-write census still names.
	 */
	private void leaveCurrentVersionReadWrite() {
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::getCatalogVersion);
	}

	@Test
	@DisplayName("A write conflicting with a transaction whose keys were released while the session was built is rejected")
	void shouldRejectConflictingWriteWhenKeysWereReleasedWhileSessionWasBuilt() {
		this.evita.defineCatalog(CATALOG);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_CODE, String.class)
					.updateVia(session);
				// brands never emit conflict keys, so a transaction writing only a brand leaves the ring buffer empty
				// once the keys of every transaction before it have been released
				session.defineEntitySchema(Entities.BRAND)
					.withConflictResolution(new ConflictResolution(ConflictPolicy.NONE))
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "initial")
				);
			}
		);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);

		final SessionRegistry registry = this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow();
		final long versionBeforeRegistration = registry.getCatalog().getVersion();
		final AtomicLong resolvedVersion = new AtomicLong(-1L);

		// The registration is held open at the point the engine's own factory runs: the catalog has been resolved, the
		// session is not in the census yet. Meanwhile, a conflicting write of product 1 commits, then a brand, and the
		// read-write sessions of both leave - with nobody in the read-write census, each departure releases the conflict
		// keys of every version below the current one, the product write's keys included.
		final EvitaInternalSessionContract session = registry.createSession(
			theRegistry -> theRegistry.addSession(
				true,
				resolvedCatalog -> {
					resolvedVersion.set(resolvedCatalog.getVersion());
					CompletableFuture.runAsync(
						() -> {
							commitProductCode("concurrent");
							this.evita.updateCatalog(
								CATALOG,
								brandSession -> {
									brandSession.upsertEntity(brandSession.createNewEntity(Entities.BRAND, 1));
								}
							);
							leaveCurrentVersionReadWrite();
						}
					).orTimeout(30, TimeUnit.SECONDS).join();
					return new EvitaSession(
						this.evita, resolvedCatalog, ReflectionLookup.NO_CACHE_INSTANCE,
						theSession -> theRegistry.removeSession((EvitaSession) theSession),
						CommitBehavior.defaultBehaviour(),
						new SessionTraits(CATALOG, SessionFlags.READ_WRITE),
						theRegistry::createCatalogConsumerControl
					);
				}
			)
		);

		// the premises: the interleaving ran after the catalog was resolved, and it did publish two newer versions
		assertEquals(versionBeforeRegistration, resolvedVersion.get());
		assertEquals(versionBeforeRegistration, session.getCatalogVersion());
		assertEquals(
			versionBeforeRegistration + 2,
			this.evita.queryCatalog(CATALOG, EvitaSessionContract::getCatalogVersion),
			"The interleaving must have published exactly two newer catalog versions, or the test proves nothing!"
		);

		// Another read-write session leaves now that the held one is in the census, and reports the held session's
		// version as the lowest one still in use - lower than what the departures above had already released.
		leaveCurrentVersionReadWrite();

		// the held session still reads the product as it was at its own version ...
		assertEquals(
			"initial",
			session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				.orElseThrow()
				.getAttribute(ATTRIBUTE_CODE)
		);
		// ... so its write of the same product must conflict with the one committed after its version
		session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTRIBUTE_CODE, "held")
			.upsertVia(session);

		try {
			session.close();
			fail(
				"The held session overwrote a product committed after its own version without a conflict - the " +
					"conflict keys of that commit were released while the session was being built!"
			);
		} catch (Throwable ex) {
			Throwable cause = ex;
			while (cause != null && !(cause instanceof ConflictingCatalogMutationException)) {
				cause = cause.getCause();
			}
			assertNotNull(cause, "Expected ConflictingCatalogMutationException in the cause chain, but got: " + ex);
		}
		final String survivingCode = this.evita.queryCatalog(
			CATALOG,
			theSession -> {
				return theSession.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.getAttribute(ATTRIBUTE_CODE);
			}
		);
		assertEquals("concurrent", survivingCode, "The concurrent write must survive the rejected one!");
	}

}
