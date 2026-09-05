/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.CatalogVersionPin;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogConsumerControl;
import io.evitadb.core.exception.CatalogTransitioningException;
import io.evitadb.core.exception.SessionBusyException;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Session registry maintains all active sessions for a single catalog in the {@link Evita} instance.
 *
 * ## Responsibilities
 *
 * - **Session Lifecycle**: Creates, registers, and removes sessions
 * - **Suspension Handling**: Supports catalog rename/replace by suspending session creation
 * - **Version Tracking**: Tracks which catalog versions are consumed by active sessions
 * - **Thread Safety**: All operations are thread-safe using concurrent data structures
 *
 * ## Thread Safety Model
 *
 * Uses {@link ConcurrentHashMap} for session storage, {@link AtomicReference} for suspension state,
 * and per-session {@link ReentrantLock} for atomic operations. Registering a session and suspending the registry are
 * additionally fenced against each other by {@link #registrationGate} - see its documentation for why the two cannot
 * be left to the atomics alone.
 *
 * A second, narrower lock - {@link #exclusiveAdmissionLock} - guards a different invariant on the same map: a catalog
 * that is not yet transactional (warming up, or otherwise not ALIVE) admits **at most one** session, and that rule is
 * a check-then-act that the registration gate's *read* lock deliberately does not serialise. The two locks are always
 * taken in the order gate-read then admission, never the reverse, and the ALIVE path never takes the second one at
 * all.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see EvitaSessionProxy for session proxy implementation
 */
@Slf4j
public final class SessionRegistry {
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Supplier that provides reference to the current {@link Catalog} this instance is bound to.
	 */
	private final Supplier<Catalog> catalogSupplier;
	/**
	 * Keeps information about currently active sessions in one big data store that contains index across all catalogs.
	 */
	private final SessionRegistryDataStore sharedDataStore;
	/**
	 * Keeps information about currently active sessions.
	 */
	private final Map<UUID, EvitaSessionTuple> activeSessions;
	/**
	 * This field is used to keep track of the current suspend operation (if any).
	 */
	private final AtomicReference<InSuspension> currentSuspension;
	/**
	 * Fences session registration against suspension, so that "this registry is not suspended" and "the session is
	 * visible in {@link #activeSessions}" are one indivisible step rather than two.
	 *
	 * Registration holds the read lock; {@link #closeAllActiveSessionsAndSuspend(SuspendOperation)} takes the write
	 * lock once as a barrier after publishing the suspension. Left to the atomics alone the two are a check-then-act:
	 * a thread that has already read {@link #currentSuspension} as empty may still be several instructions short of
	 * putting its session into the map, and the drain then finds the map empty and reports the catalog quiesced while
	 * a live session is about to appear on it. Callers act on that report by destroying the catalog - a rename closes
	 * the persistence service the straggler is about to read - so the straggler sees `PersistenceServiceClosed`, an
	 * internal error, where the contract owes it a well-defined invalid-usage answer.
	 *
	 * Shared by reference with the registry {@link #withDifferentCatalogSupplier(Supplier)} builds, exactly as
	 * {@link #activeSessions} and {@link #currentSuspension} are: a fresh lock there would leave two independent gates
	 * guarding one map, which is this very bug reintroduced by a rename.
	 *
	 * **The gate is only as complete as the paths that take it.** {@link #addSession(boolean, Supplier)} is today the
	 * one and only way a session enters {@link #activeSessions}, which is what lets a single gate cover the whole
	 * registry - {@link #createSession(Function)} reaches it too, since its factory ends there. A second registration
	 * path that writes the map directly would be outside this fence and would reopen the race in silence.
	 */
	private final ReentrantReadWriteLock registrationGate;
	/**
	 * Serialises the admission {@link #addSession(boolean, Supplier)} performs while the catalog behind this registry
	 * is **not** transactional, so that "there is no session yet" and "this session is in {@link #activeSessions}"
	 * are one indivisible step.
	 *
	 * The invariant it protects: a catalog that does not support transactions - i.e. one that is not ALIVE, warm-up
	 * being the ordinary case - admits at most one session, and the second caller is refused with
	 * {@link ConcurrentInitializationException}. Everything downstream leans on it. A warm-up write mutates the
	 * published indexes in place, outside any transaction, so the single session is the only thing that keeps a
	 * second thread from reading a structure that is being rewritten under it.
	 *
	 * **Why {@link #registrationGate} does not cover this.** That gate orders registration against *suspension*, and
	 * registration holds its READ lock - which by design lets registrations run concurrently with one another. That
	 * is exactly what an ALIVE catalog wants, and exactly what leaves the emptiness test racy: two threads may both
	 * find the map empty and both register. Promoting the admission to the gate's WRITE lock would serialise every
	 * session creation, ALIVE included, and would contend with the suspension barrier that takes the same lock - a
	 * throughput regression and a deadlock surface, for a rule that only ever applies to a state in which one session
	 * is the maximum anyway. Hence a separate lock, taken **only** on the non-transactional path; the ALIVE path never
	 * touches it and pays nothing.
	 *
	 * The caller's session supplier runs **inside** the critical section, which is what makes the check and the map
	 * write indivisible. A competing warm-up caller therefore waits out the construction and is then refused, where
	 * before it would have been admitted; on a state whose maximum is one session that is the correct answer arriving
	 * a little later, not added contention. Note the consequence for suspension: concurrent non-transactional
	 * admissions now serialise while each still holds the gate's read lock, so a suspension barrier waits for their
	 * **sum** rather than for the longest of them. Bounded and rare by design — the state admits one session — but it
	 * is the price the atomicity is bought with.
	 *
	 * **Lock order is always gate-read then admission, never the reverse, and that ordering is the invariant** — not
	 * the absence of an interaction between the two. The gate's read lock is taken first, by
	 * {@link #registerWhileNotSuspended(Supplier)}, and this lock is taken inside it; nothing executing under this
	 * lock may acquire the gate, which is what keeps the pair acyclic.
	 * {@link #closeAllActiveSessionsAndSuspend(SuspendOperation)} takes the gate's write lock without ever touching
	 * this one. A future change that lets session construction reach back into the registry would break the rule
	 * rather than merely widen a window, and the warm-up path is where that would bite first, because construction is
	 * the expensive part of the critical section. Nor can the retry loop in
	 * {@link #registerWhileNotSuspended(Supplier)} multiply anything: it runs the supplier on the one attempt that
	 * finds no suspension in effect, and that call either returns or throws, so the supplier runs **at most once**.
	 * No registration is repeated by the retry and no catalog version pin is taken twice.
	 *
	 * Shared by reference with the registry {@link #withDifferentCatalogSupplier(Supplier)} builds, exactly as
	 * {@link #activeSessions}, {@link #currentSuspension} and {@link #registrationGate} are. A fresh lock there would
	 * leave two registries admitting into one map through two independent locks, which serialises neither against the
	 * other and voids the guarantee entirely.
	 */
	private final ReentrantLock exclusiveAdmissionLock;
	/**
	 * This field is used to keep track of the sessions that were forcefully closed due to a suspension operation.
	 * The information is held only for a limited time.
	 */
	private final AtomicReference<SuspensionInformation> lastSuspensionInfo = new AtomicReference<>(null);
	/**
	 * Keeps information about sessions sorted according to date of creation.
	 */
	private final ConcurrentLinkedQueue<EvitaSessionTuple> sessionsFifoQueue;
	/**
	 * The catalogConsumedVersions variable is used to keep track of consumed versions along with number of sessions
	 * tied to them indexed by catalog names.
	 */
	private final ConcurrentHashMap<String, VersionConsumingSessions> catalogConsumedVersions;

	/**
	 * Created data store to be shared among all SessionRegistry instances.
	 *
	 * @return the data store
	 */
	@Nonnull
	public static SessionRegistryDataStore createDataStore() {
		return new SessionRegistryDataStore();
	}

	public SessionRegistry(
		@Nonnull TracingContext tracingContext,
		@Nonnull Supplier<Catalog> catalogSupplier,
		@Nonnull SessionRegistryDataStore sharedDataStore
	) {
		this.tracingContext = tracingContext;
		this.catalogSupplier = catalogSupplier;
		this.sharedDataStore = sharedDataStore;
		this.activeSessions = CollectionUtils.createConcurrentHashMap(512);
		this.currentSuspension = new AtomicReference<>(null);
		this.registrationGate = new ReentrantReadWriteLock();
		this.exclusiveAdmissionLock = new ReentrantLock();
		this.sessionsFifoQueue = new ConcurrentLinkedQueue<>();
		this.catalogConsumedVersions = CollectionUtils.createConcurrentHashMap(32);
	}

	private SessionRegistry(
		@Nonnull TracingContext tracingContext,
		@Nonnull Supplier<Catalog> catalogSupplier,
		@Nonnull SessionRegistryDataStore sharedDataStore,
		@Nonnull Map<UUID, EvitaSessionTuple> activeSessions,
		@Nonnull AtomicReference<InSuspension> currentSuspension,
		@Nonnull ReentrantReadWriteLock registrationGate,
		@Nonnull ReentrantLock exclusiveAdmissionLock,
		@Nonnull ConcurrentLinkedQueue<EvitaSessionTuple> sessionsFifoQueue,
		@Nonnull ConcurrentHashMap<String, VersionConsumingSessions> catalogConsumedVersions
	) {
		this.tracingContext = tracingContext;
		this.catalogSupplier = catalogSupplier;
		this.sharedDataStore = sharedDataStore;
		this.activeSessions = activeSessions;
		this.currentSuspension = currentSuspension;
		this.registrationGate = registrationGate;
		this.exclusiveAdmissionLock = exclusiveAdmissionLock;
		this.sessionsFifoQueue = sessionsFifoQueue;
		this.catalogConsumedVersions = catalogConsumedVersions;
	}

	/**
	 * Retrieves the catalog associated with the registry.
	 *
	 * @return the current catalog instance
	 */
	@Nonnull
	public Catalog getCatalog() {
		return this.catalogSupplier.get();
	}

	/**
	 * Method closes and removes all active sessions from the registry.
	 * All changes are rolled back.
	 */
	@Nonnull
	public Optional<SuspensionInformation> closeAllActiveSessionsAndSuspend(
		@Nonnull SuspendOperation suspendOperation
	) {
		if (this.currentSuspension.compareAndSet(null, new InSuspension(suspendOperation))) {
			// A barrier rather than a critical section: taking the write lock and giving it straight back waits out
			// every registration that read the suspension as empty before the line above and has not yet reached
			// `activeSessions`. Once it is granted, each of those is either already visible in the map or will see
			// the suspension and be refused - which is what makes the drain below reading an empty map mean "there
			// is nobody left" rather than "there is nobody left yet". Taken before the census on the next line so
			// that the count covers the stragglers too.
			final Lock registrationBarrier = this.registrationGate.writeLock();
			registrationBarrier.lock();
			registrationBarrier.unlock();
			// init information about closed sessions
			final SuspensionInformation suspensionInformation = new SuspensionInformation(
				this.activeSessions.size()
			);
			this.lastSuspensionInfo.set(suspensionInformation);
			final long start = System.currentTimeMillis();
			// reuse list across iterations to reduce allocations
			final List<CompletableFuture<CommitVersions>> futures = new ArrayList<>(this.activeSessions.size());
			do {
				futures.clear();
				for (EvitaSessionTuple sessionTuple : this.activeSessions.values()) {
					//noinspection resource
					final EvitaSession plainSession = sessionTuple.plainSession();
					//noinspection resource
					final EvitaInternalSessionContract proxySession = sessionTuple.proxySession();
					if (proxySession.isActive()) {
						proxySession
							// close the session once the running method is finished
							// or immediately if there is no method running
							.executeWhenMethodIsNotRunning(
								() -> {
									if (plainSession.isActive()) {
										if (plainSession.isTransactionOpen()) {
											plainSession.setRollbackOnly();
										}
										final UUID sessionId = plainSession.getId();
										log.info("There is still an active session {} - terminating.", sessionId);
										suspensionInformation.addForcefullyClosedSession(sessionId);
										futures.add(
											plainSession.closeNow(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE)
												.toCompletableFuture()
												// ignore exceptions, we don't care about them here
												.exceptionally(ex -> null)
										);
									}
								}
							);
					}
				}
				// wait for all futures to complete
				CompletableFuture
					.allOf(futures.toArray(new CompletableFuture[0]))
					.join();
				// wait for active sessions to be empty, but at most 5 seconds
			} while (!this.activeSessions.isEmpty() && System.currentTimeMillis() - start < 5000);

			Assert.isPremiseValid(
				this.activeSessions.isEmpty(),
				() -> {
					final StringBuilder sb = new StringBuilder("Some of the sessions didn't clean themselves (");
					boolean first = true;
					for (EvitaSessionTuple tuple : this.activeSessions.values()) {
						final EvitaSession session = tuple.plainSession();
						if (!first) {
							sb.append(", ");
						}
						first = false;
						sb.append(session.getId()).append(session.isActive() ? ": active" : ": closed");
					}
					sb.append(")!");
					return sb.toString();
				}
			);
			return of(suspensionInformation);
		}
		return ofNullable(this.lastSuspensionInfo.get());
	}

	/**
	 * Method resumes operations on this registry - i.e. creating new sessions.
	 */
	public void resumeOperations() {
		final InSuspension inSuspension = this.currentSuspension.getAndSet(null);
		if (inSuspension != null) {
			inSuspension.suspendFuture().complete(null);
		}
	}

	/**
	 * Clears any temporary information related to forcefully closed sessions in the registry.
	 *
	 * If there is information about sessions that were forcefully closed and the suspension event
	 * occurred more than 5 minutes ago, this method will clear that information.
	 *
	 * This helps in cleaning up outdated suspension data to keep the registry up-to-date
	 * and free from stale information.
	 */
	public void clearTemporaryInformation() {
		final SuspensionInformation suspensionInformation = this.lastSuspensionInfo.get();
		if (suspensionInformation != null &&
			suspensionInformation.getSuspensionDateTime().isBefore(OffsetDateTime.now().minusMinutes(5))) {
			// clear the information about forcefully closed sessions after 5 minutes
			this.lastSuspensionInfo.set(null);
		}
	}

	/**
	 * Determines whether the sessions associated with a catalog were forcefully closed.
	 *
	 * @param sessionId the unique identifier of the session in the registry
	 * @return true if the sessions associated with the catalog were forcefully closed; false otherwise
	 */
	public boolean wereSessionsForcefullyClosedForCatalog(@Nonnull UUID sessionId) {
		return ofNullable(this.lastSuspensionInfo.get())
			.map(it -> it.contains(sessionId))
			.orElse(false);
	}

	/**
	 * Counts the sessions currently open against this catalog, split by whether they may write.
	 *
	 * The read-write count is the operationally interesting half: an open read-write session pins a catalog version,
	 * which keeps superseded data files from being purged. A count that will not fall to zero is therefore a direct
	 * explanation for disk space that will not come back.
	 *
	 * The map is walked rather than counted from two maintained counters, because a session's mode is a property of
	 * the session and duplicating it into a counter pair is one more thing that can drift out of step with the map
	 * itself. The walk is over open sessions only, so it is bounded by concurrency rather than by data size.
	 *
	 * @return the number of open sessions, and how they split between read-only and read-write
	 */
	@Nonnull
	public SessionStatistics countActiveSessions() {
		int readOnly = 0;
		int readWrite = 0;
		for (final EvitaSessionTuple sessionTuple : this.activeSessions.values()) {
			if (sessionTuple.plainSession().isReadOnly()) {
				readOnly++;
			} else {
				readWrite++;
			}
		}
		return new SessionStatistics(readOnly + readWrite, readOnly, readWrite);
	}

	/**
	 * Creates and registers new session to the registry.
	 *
	 * Method checks that there is only a single active session when catalog is in warm-up mode - more precisely,
	 * whenever the catalog does not support transactions. That check and the registration it guards are performed
	 * together under {@link #exclusiveAdmissionLock}, because the two apart are a check-then-act that lets two
	 * concurrent callers both find {@link #activeSessions} empty and both register. See that lock's documentation for
	 * why the registration gate's read lock cannot stand in for it. A transactional catalog admits sessions in
	 * parallel and does not take the admission lock at all.
	 *
	 * @param transactional   TRUE when the catalog behind this registry supports transactions, i.e. it is ALIVE
	 * @param sessionSupplier constructs the session to be registered
	 * @return the proxy wrapping the newly registered session
	 * @throws ConcurrentInitializationException when a session is already open on a non-transactional catalog
	 */
	@Nonnull
	public EvitaInternalSessionContract addSession(
		boolean transactional,
		@Nonnull Supplier<EvitaSession> sessionSupplier
	) {
		return registerWhileNotSuspended(() -> {
			if (transactional) {
				return registerNewSession(sessionSupplier);
			}
			// CALIBRATION - this critical section is swept by `LongRunningSessionRegistryWarmUpAdmissionTest`
			// (evita_test/evita_long_running_tests, io.evitadb.core.session). Measured on a 24-core Linux box with
			// OpenJDK 17.0.20: with the lock removed, two threads leaving one barrier were both admitted in ROUND 0
			// on five runs out of five; with it in place all 50 000 rounds pass in ~2.8 s. The window swept is what
			// `registerNewSession` does between the refusal check and the map write - the proxy, the opened event,
			// the version pin - so MAKING THAT PATH CHEAPER NARROWS IT, and so does making the test's own fixture
			// cheaper. A change here that stops the counterfactual failing has not made anything safer, it has
			// blunted the test; re-measure and widen the sweep. Build the counterfactual by shadowing a mutated copy
			// on the classpath rather than editing this file - the test's javadoc gives the recipe. Green side:
			//   mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
			//       -Dtest=LongRunningSessionRegistryWarmUpAdmissionTest -Dsurefire.failIfNoSpecifiedTests=false
			this.exclusiveAdmissionLock.lock();
			try {
				// one weakly-consistent iterator rather than `isEmpty()` followed by a fresh `iterator().next()`:
				// `removeSession` empties the map without taking this lock - deliberately, since locking it there
				// would serialise every ALIVE session close - so a session closing between the two statements would
				// make `next()` throw `NoSuchElementException` at a caller owed a well-defined answer. Driven off a
				// single iterator, the worst outcome is a refusal naming a session that has just closed, which is
				// acceptable; an internal error is not.
				final Iterator<UUID> incumbent = this.activeSessions.keySet().iterator();
				if (incumbent.hasNext()) {
					throw new ConcurrentInitializationException(incumbent.next());
				}
				return registerNewSession(sessionSupplier);
			} finally {
				this.exclusiveAdmissionLock.unlock();
			}
		});
	}

	/**
	 * Builds the session from the supplier, wraps it in its {@link EvitaSessionProxy} and publishes it into every
	 * index the registry maintains - {@link #activeSessions}, {@link #sessionsFifoQueue}, the version census in
	 * {@link #catalogConsumedVersions} and the shared data store.
	 *
	 * Extracted out of {@link #addSession(boolean, Supplier)} so that the non-transactional path can wrap the
	 * emptiness check **and** this registration in a single critical section, while the transactional path calls it
	 * with no extra lock held.
	 *
	 * **Ordering is load-bearing:** every step that can throw runs before the session becomes visible anywhere, so a
	 * failed registration leaves no half-registered session behind. See the comment at the version pin.
	 *
	 * @param sessionSupplier constructs the session to be registered
	 * @return the proxy wrapping the newly registered session
	 */
	@Nonnull
	private EvitaInternalSessionContract registerNewSession(@Nonnull Supplier<EvitaSession> sessionSupplier) {
		final EvitaSession newSession = sessionSupplier.get();
		final long catalogVersion = newSession.getCatalogVersion();
		final String catalogName = newSession.getCatalogName();

		final EvitaInternalSessionContract newSessionProxy = (EvitaInternalSessionContract) Proxy.newProxyInstance(
			EvitaInternalSessionContract.class.getClassLoader(),
			new Class[]{EvitaInternalSessionContract.class, EvitaProxyFinalization.class},
			new EvitaSessionProxy(newSession, this.tracingContext)
		);
		final EvitaSessionTuple sessionTuple = new EvitaSessionTuple(newSession, newSessionProxy);

		// INVARIANT - nothing is published until every step that can throw has succeeded, so a failed registration
		// leaves the registry exactly as it found it. The pin is taken first, into a local, because it is the only
		// remaining step that can fail: the catalog supplier *throws* when the catalog has gone (a
		// `CatalogNotFoundException`, or an unusable catalog's representative exception) and
		// `registerSessionConsumingCatalogInVersion` only absorbs `CatalogTransitioningException`. Published first,
		// such a throw would leave a session in `activeSessions` that no caller ever received and therefore never
		// closes - and on a catalog that is not transactional that orphan refuses every later admission for the
		// life of the process. Everything below is a non-throwing publication.
		final CatalogVersionPin catalogVersionPin;
		try {
			catalogVersionPin =
				this.catalogConsumedVersions.computeIfAbsent(catalogName, k -> new VersionConsumingSessions())
					.registerSessionConsumingCatalogInVersion(
						catalogVersion,
						newSession.getSessionTraits(),
						this.catalogSupplier
					);
		} catch (Throwable ex) {
			// the supplier has already built the session by this point, and the caller never receives it, so nothing
			// else will ever close it: it holds an open traffic-recording session and, on an ALIVE catalog, an open
			// transaction. Closing it here is the same forced close `closeAllActiveSessionsAndSuspend` performs on a
			// session it takes away from its owner. `removeSession` - which the session's termination callback
			// reaches - starts with a `activeSessions.remove` that answers null for a session that was never
			// published, so it returns without touching the FIFO queue or the version census
			closeAbandonedSession(newSession, ex);
			throw ex;
		}
		// PREMISE - none of the four steps below can throw. `executeAtomically` runs them under the tuple's own lock,
		// `activeSessions.put` and `sharedDataStore.addSession` are plain map puts, `sessionsFifoQueue.add` is a
		// queue add and the pin is a setter. If that ever stops holding, this ordering has to be revisited: a throw
		// here leaves a half-published session the catch above cannot see.
		sessionTuple.executeAtomically(
			() -> {
				this.activeSessions.put(newSession.getId(), sessionTuple);
				this.sessionsFifoQueue.add(sessionTuple);
				// the lease rides on the tuple rather than in a map keyed by version: two sessions holding the same
				// version across a catalog replacement hold pins on *different* instances, and only the session
				// that took one knows which
				sessionTuple.versionPin().set(catalogVersionPin);
				this.sharedDataStore.addSession(sessionTuple);
			}
		);

		return newSessionProxy;
	}

	/**
	 * Closes a session that was constructed but never published, after the step that would have published it threw.
	 *
	 * The session is fully live by the time this runs - its constructor opened a traffic-recording session and, on a
	 * catalog that supports transactions, a transaction - and the caller never receives it, so nothing else will ever
	 * close it. This is the same forced close {@link #closeAllActiveSessionsAndSuspend(SuspendOperation)} performs on
	 * a session it takes away from its owner, including the commit behaviour it uses.
	 *
	 * **A failure to close must never replace the failure that caused it.** The registration's own exception is what
	 * the caller asked about; anything this close throws is recorded as suppressed on it and nothing more.
	 *
	 * @param session the constructed but unpublished session
	 * @param cause   the failure that abandoned the registration, which any close failure is attached to
	 */
	private static void closeAbandonedSession(@Nonnull EvitaSession session, @Nonnull Throwable cause) {
		try {
			//noinspection resource
			session.closeNow(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE);
		} catch (Throwable closeFailure) {
			cause.addSuppressed(closeFailure);
		}
	}

	/**
	 * Removes session from the registry.
	 */
	public void removeSession(@Nonnull EvitaSession session) {
		final EvitaSessionTuple removedSession = this.activeSessions.remove(session.getId());
		if (removedSession != null) {
			removedSession.executeAtomically(
				() -> {
					final EvitaSessionTuple globalSession = this.sharedDataStore.removeSession(session.getId());
					Assert.isPremiseValid(
						removedSession == globalSession,
						"Session not found in the globally shared data store."
					);
					Assert.isPremiseValid(
						this.sessionsFifoQueue.remove(removedSession),
						"Session not found in the queue."
					);

					session.getTransaction().ifPresent(this::reportTransactionResolution);

					this.catalogConsumedVersions.get(session.getCatalogName())
						.unregisterSessionConsumingCatalogInVersion(
							session.getCatalogVersion(),
							session.getSessionTraits(),
							this.catalogSupplier,
							removedSession.versionPin().get()
						);

					// emit event
					//noinspection CastToIncompatibleInterface,resource
					((EvitaProxyFinalization) removedSession.proxySession())
						.finish(
							ofNullable(this.sessionsFifoQueue.peek())
								.map(it -> it.plainSession().getCreated())
								.orElse(null),
							this.activeSessions.size()
						);
				}
			);
		}
	}

	/**
	 * Reports how the closing session's transaction ended - both to the observability event and, when it was rolled
	 * back, to the {@link io.evitadb.api.statistics.CatalogStatisticsComponent#ACTIVITY} counters.
	 *
	 * **Both readings are taken from a single {@link Transaction#isRollbackOnly()} evaluation, deliberately.** The
	 * event's {@link TransactionResolution} and the counter describe the same population, and reading the flag twice
	 * in two places is what would eventually let them disagree.
	 *
	 * **Why the counter lives here and not in {@link Transaction#close()}, which is where the rollback actually
	 * happens.** Three reasons, none of them cosmetic:
	 *
	 * - `Transaction` is not only a user transaction. `TransactionManager` builds instances with `replay = true` to
	 *   incorporate the write-ahead log into the trunk and closes each one as the log drains, so `close()` also runs
	 *   for every transaction replayed at startup. Since `Transaction.executeInTransactionIfProvided` marks a
	 *   transaction rollback-only on any throwable, a failed replay would be counted as a client rollback - the same
	 *   distortion that kept `recordCommittedTransaction` out of the trunk incorporation stage. A `!replay` guard
	 *   would paper over it, which is the tell: the call site would not carry the distinction, a filter would.
	 * - `Transaction` has no route to the {@link io.evitadb.core.transaction.TransactionManager}. It holds a
	 *   `TransactionHandler`, whose implementation differs between a session and a replay, so reaching the manager
	 *   would take a downcast, a new field, or a wider interface - all of which put statistics bookkeeping into the
	 *   transaction's construction path.
	 * - A session's transaction is discarded here and never offered to the commit pipeline, so this is the only place
	 *   a rolled-back transaction is observable at all; the stages that count everything else never see it.
	 *
	 * Note a **dry-run** session marks its transaction rollback-only at creation, so its transactions are counted as
	 * rolled back. That is literally true - they never commit - but it means the counter tracks dry runs rather than
	 * failures on a catalog that receives them.
	 *
	 * @param transaction the closing session's transaction
	 */
	private void reportTransactionResolution(@Nonnull Transaction transaction) {
		// find oldest session with open transaction using loop to avoid Stream allocation
		OffsetDateTime oldestWithTransaction = null;
		for (EvitaSessionTuple tuple : this.sessionsFifoQueue) {
			final EvitaSession queuedSession = tuple.plainSession();
			if (queuedSession.getOpenedTransaction().isPresent()) {
				oldestWithTransaction = queuedSession.getCreated();
				break;
			}
		}
		final boolean rolledBack = transaction.isRollbackOnly();
		// emit event
		transaction.getFinalizationEvent()
			.finishWithResolution(
				oldestWithTransaction,
				rolledBack ? TransactionResolution.ROLLBACK : TransactionResolution.COMMIT
			).commit();
		if (rolledBack) {
			this.catalogSupplier.get().getTransactionManager().recordRolledBackTransaction();
		}
	}

	/**
	 * Returns control object that allows a backup to hold a catalog version against reclamation for as long as it
	 * is copying that version.
	 *
	 * @param catalogName the name of the catalog whose consumer census is ensured to exist
	 * @return the control object
	 */
	@Nonnull
	public CatalogConsumerControl createCatalogConsumerControl(@Nonnull String catalogName) {
		// the returned control holds versions through the catalog itself and never consults the census - but the
		// census is kept present for this name regardless, because the map outlives a single registry (a rename
		// hands it to the registry built by `withDifferentCatalogSupplier`) and the removal path reads it with a
		// bare `get`. Ensuring it here costs nothing and is the behaviour every caller has had so far.
		this.catalogConsumedVersions.computeIfAbsent(catalogName, k -> new VersionConsumingSessions());
		return new CatalogConsumerControlInternal(this.catalogSupplier);
	}

	/**
	 * Internal method that creates and initializes session and returns it.
	 *
	 * @param sessionFactory the function that creates the session
	 * @return the created session
	 */
	@Nonnull
	public EvitaInternalSessionContract createSession(
		@Nonnull Function<SessionRegistry, EvitaInternalSessionContract> sessionFactory
	) {
		return handleSuspension(() -> sessionFactory.apply(this));
	}

	/**
	 * Creates a new instance of SessionRegistry using a different supplier for the catalog.
	 * This method allows changing the catalog supplier while re-using the other existing settings
	 * from the current SessionRegistry instance.
	 *
	 * @param catalogSupplier a non-null supplier of the catalog to be used in the new SessionRegistry
	 * @return a new instance of SessionRegistry configured with the provided catalog supplier
	 */
	@Nonnull
	public SessionRegistry withDifferentCatalogSupplier(@Nonnull Supplier<Catalog> catalogSupplier) {
		return new SessionRegistry(
			this.tracingContext,
			catalogSupplier,
			this.sharedDataStore,
			this.activeSessions,
			this.currentSuspension,
			this.registrationGate,
			// carried by reference for the same reason the gate is: a fresh admission lock here would leave two
			// registries admitting into one map through two independent locks, and guard nothing
			this.exclusiveAdmissionLock,
			this.sessionsFifoQueue,
			this.catalogConsumedVersions
		);
	}

	/**
	 * Handles a suspension operation based on the current state.
	 * If there is an active suspend operation, it evaluates its behavior and
	 * acts accordingly by either postponing the operation, awaiting completion,
	 * or throwing an exception. If no active suspend operation is detected, it
	 * proceeds with the supplied operation.
	 *
	 * @param <T>      the type of the result provided by the supplier
	 * @param supplier a non-null supplier that provides the operation to execute
	 *                 if suspension allows it
	 * @return the result of the supplier's operation if executed successfully
	 * @throws SessionBusyException        if the suspension operation has been postponed
	 *                                     and could not finish within the timeout period
	 * @throws InstanceTerminatedException if the suspension operation indicates
	 *                                     the instance termination
	 */
	private <T> T handleSuspension(@Nonnull Supplier<T> supplier) {
		final InSuspension inSuspension = this.currentSuspension.get();
		if (inSuspension != null) {
			awaitResumeOrRefuse(inSuspension);
		}
		return supplier.get();
	}

	/**
	 * Runs a session registration behind the gate {@link #registrationGate} describes, so that the suspension check
	 * and the session's appearance in {@link #activeSessions} cannot be split apart by a concurrent suspension.
	 *
	 * Registration alone is gated. {@link #handleSuspension(Supplier)} stays ungated for the outer session factory,
	 * whose body reaches user-supplied callbacks that must never be able to hold a rename up.
	 *
	 * @param <T>      the type of the result provided by the supplier
	 * @param supplier registers the session and returns it
	 * @return the result of the supplier's operation
	 * @throws SessionBusyException        if the registry is suspended and did not resume in time
	 * @throws InstanceTerminatedException if the registry is suspended because the catalog is being terminated
	 */
	private <T> T registerWhileNotSuspended(@Nonnull Supplier<T> supplier) {
		// At most one retry: the first pass is the ordinary path and the second is the one a POSTPONE that has since
		// finished has earned. A registry that manages to suspend again in between is genuinely busy, and answering
		// so beats looping until it stops - `SessionBusyException` is already what the postponed path answers when
		// its own wait runs out, so no caller gains a case it does not already handle.
		for (int attempt = 0; attempt < 2; attempt++) {
			final Lock registrationLock = this.registrationGate.readLock();
			registrationLock.lock();
			try {
				if (this.currentSuspension.get() == null) {
					return supplier.get();
				}
			} finally {
				registrationLock.unlock();
			}
			// Waited out with the gate released, deliberately: a POSTPONE is waited out until the operation that
			// installed it finishes, and that operation is itself waiting on the barrier this thread would
			// otherwise be holding shut.
			final InSuspension inSuspension = this.currentSuspension.get();
			// null means the suspension ended between releasing the gate and this read - there is nothing to wait
			// for, and the next pass takes the gate again
			if (inSuspension != null) {
				awaitResumeOrRefuse(inSuspension);
			}
		}
		throw SessionBusyException.INSTANCE;
	}

	/**
	 * Waits out a postponing suspension, or refuses outright when the catalog behind this registry is being
	 * terminated.
	 *
	 * @param inSuspension the suspension currently in effect
	 * @throws SessionBusyException        if the suspension postpones and did not finish within the timeout
	 * @throws InstanceTerminatedException if the suspension rejects
	 */
	private static void awaitResumeOrRefuse(@Nonnull InSuspension inSuspension) {
		if (inSuspension.suspendOperation() == SuspendOperation.POSTPONE) {
			if (!inSuspension.awaitFinish(500, TimeUnit.MILLISECONDS)) {
				throw SessionBusyException.INSTANCE;
			}
		} else {
			throw new InstanceTerminatedException("catalog");
		}
	}

	/**
	 * The DTO combines both plain session and the proxy wrapper around it so that one or another can be used on places
	 * where necessary.
	 *
	 * @param plainSession the session object
	 * @param proxySession the proxy wrapper around the very session object
	 */
	private record EvitaSessionTuple(
		@Nonnull EvitaSession plainSession,
		@Nonnull EvitaInternalSessionContract proxySession,
		@Nonnull ReentrantLock atomicLock,
		@Nonnull AtomicReference<CatalogVersionPin> versionPin
	) {

		private EvitaSessionTuple(
			@Nonnull EvitaSession plainSession,
			@Nonnull EvitaInternalSessionContract proxySession
		) {
			this(
				plainSession,
				proxySession,
				new ReentrantLock(),
				// replaced by the real lease as the session registers; a session that never got that far holds
				// nothing, and closing this releases nothing
				new AtomicReference<>(CatalogVersionPin.NONE)
			);
		}

		/**
		 * Method executes the given lambda in an atomic way, ensuring that no other thread can interfere with
		 * the block execution.
		 *
		 * @param lambda the lambda to be executed atomically
		 */
		public void executeAtomically(@Nonnull Runnable lambda) {
			this.atomicLock.lock();
			try {
				lambda.run();
			} finally {
				this.atomicLock.unlock();
			}
		}
	}

	/**
	 * This class represents a collection of sessions that are consuming catalogs in different versions.
	 */
	private static class VersionConsumingSessions {
		/**
		 * ConcurrentHashMap representing a collection of sessions that are consuming catalogs in different versions.
		 * The keys of the map are the versions of the catalogs, and the values are the number of the read-only sessions
		 * consuming catalogs in that version.
		 */
		private final ConcurrentHashMap<Long, Integer> versionConsumingReadOnlySessions =
			CollectionUtils.createConcurrentHashMap(32);
		/**
		 * ConcurrentHashMap representing a collection of sessions that are consuming catalogs in different versions.
		 * The keys of the map are the versions of the catalogs, and the values are the number of the read-write
		 * sessions consuming catalogs in that version.
		 */
		private final ConcurrentHashMap<Long, Integer> versionConsumingReadWriteSessions =
			CollectionUtils.createConcurrentHashMap(32);

		/**
		 * Registers a session consuming catalog in the specified version: raises this version's reader count and pins
		 * it against reclamation for as long as the session lives.
		 *
		 * The two halves are not equivalent bookkeeping and the body says why - the count answers "is anyone still
		 * here", the pin clamps the retention trim. Both are given back exactly once, and a registration that fails
		 * after the count was raised gives it back here before rethrowing, because no later path can: an unpublished
		 * session never reaches {@code removeSession} and therefore never reaches
		 * {@link #unregisterSessionConsumingCatalogInVersion} either.
		 *
		 * @param version the version of the catalog the session will read
		 * @param traits  the session's traits, which select the read-only or the read-write census map
		 * @param catalog supplies the catalog to pin; it throws when the catalog has gone or is unusable
		 * @return the lease holding that version, which the caller keeps for the session's lifetime and hands back to
		 *         {@link #unregisterSessionConsumingCatalogInVersion} - {@link CatalogVersionPin#NONE} when the pin
		 *         could not be taken at all
		 * @throws RuntimeException whatever the catalog supplier throws, propagated after the census increment has
		 *                          been given back, so the caller may abandon the session without leaking a reader
		 */
		@Nonnull
		CatalogVersionPin registerSessionConsumingCatalogInVersion(
			long version,
			@Nonnull SessionTraits traits,
			@Nonnull Supplier<Catalog> catalog
		) {
			final ConcurrentHashMap<Long, Integer> targetIndex = traits.isReadWrite() ?
				this.versionConsumingReadWriteSessions :
				this.versionConsumingReadOnlySessions;

			targetIndex.compute(
				version,
				(k, v) -> v == null ? 1 : v + 1
			);

			// Pin the version against reclamation. This is not symmetrical bookkeeping with the maps above: those
			// answer "is anyone still here" on departure, which can only ever report a rising minimum. A consumer
			// that starts on a version in the past - a point-in-time backup pins the bootstrap record it copies -
			// is invisible to that, so retention has to be told about the arrival, not only about the departure.
			//
			// INVARIANT - *every* session pins, read-only ones included, and this must not be "optimized" away.
			// Reclamation of files no bootstrap record can reach runs without asking about pins at all, and the only
			// thing making that safe is that a session reads exclusively through the record serving its version,
			// while the trim deciding which records are retained is clamped by this very pin. Drop the pin for
			// read-only sessions and that argument collapses silently - no test fails, and a reader loses the
			// generation underneath it. See the deleter matrix in
			// `documentation/adr/2026-08-06-time-travel-disk-budget.md`.
			try {
				final Catalog theCatalog = catalog.get();
				// in rare cases (catalog replacement) the catalog might not be available already. A pin that was not
				// taken needs no separate record of the omission: `NONE` closes to nothing, so the release cannot give
				// back something this session never held
				if (theCatalog == null) {
					return CatalogVersionPin.NONE;
				}
				theCatalog.catalogVersionPinned(version);
				// bound to `theCatalog`, so a replacement taking over this name later cannot receive the release
				return CatalogVersionPin.pinnedOn(version, theCatalog::catalogVersionReleased);
			} catch (CatalogTransitioningException ignored) {
				// catalog is transitioning, we cannot notify it anyway - and nothing was pinned, so nothing is owed
				return CatalogVersionPin.NONE;
			} catch (Throwable ex) {
				// The count above was raised for a session that is about to be abandoned. Nothing downstream takes
				// it back: the registration catch closes the unpublished session, and `removeSession` opens with an
				// `activeSessions.remove` that answers null for a session no caller ever received, so it returns
				// before reaching the census at all. Left standing, this version's reader count never falls to zero
				// again - `unregisterSessionConsumingCatalogInVersion` keeps taking its non-last-reader branch,
				// `catalogConsumersLeft` is never fired for it, and its files, history roots and conflict keys are
				// retained for the life of the process. Repeated failures stack.
				//
				// This is the only place that knows the increment happened, so it is the only place that can undo
				// it. The decrement mirrors the one in `unregisterSessionConsumingCatalogInVersion` exactly,
				// including dropping the entry at one rather than leaving a zero behind.
				targetIndex.compute(
					version,
					(k, v) -> v == null || v == 1 ? null : v - 1
				);
				throw ex;
			}
		}

		/**
		 * Unregisters a session that is consuming a catalog in the specified version.
		 *
		 * @param version the version of the catalog
		 * @param catalog the supplier of currently active catalog instance
		 */
		void unregisterSessionConsumingCatalogInVersion(
			long version,
			@Nonnull SessionTraits traits,
			@Nonnull Supplier<Catalog> catalog,
			@Nonnull CatalogVersionPin versionPin
		) {
			final ConcurrentHashMap<Long, Integer> targetIndex = traits.isReadWrite() ?
				this.versionConsumingReadWriteSessions :
				this.versionConsumingReadOnlySessions;

			final Integer readerCount = targetIndex.compute(
				version,
				(k, v) -> v == null || v == 1 ? null : v - 1
			);

			// give the lease taken on registration back - paired and counted, so the version stays held until the last
			// consumer of it has gone, independently of the last-reader notification below. The lease releases on the
			// catalog instance that granted it and does nothing when registration could not take a pin at all, so
			// neither a replacement taking over this name nor a skipped acquisition can turn this into a decrement of
			// somebody else's pin
			versionPin.close();

			// the minimal active catalog version used by another session now
			final OptionalLong minimalActiveCatalogVersion;
			// TRUE when the session was the last reader
			final boolean lastReader;
			if (readerCount == null) {
				minimalActiveCatalogVersion = getMinimalVersionFrom(targetIndex);
				lastReader = true;
			} else {
				minimalActiveCatalogVersion = OptionalLong.of(version);
				lastReader = false;
			}

			if (lastReader) {
				// notify listeners that the catalog version is no longer used
				final Catalog theCatalog;
				try {
					theCatalog = catalog.get();
					// in rare cases (catalog replacement) the catalog might not have been available already
					if (theCatalog != null) {
						final long minimalActiveVersion = minimalActiveCatalogVersion.orElse(theCatalog.getVersion());
						theCatalog.catalogConsumersLeft(
							traits.isReadWrite() ?
								getMinimalVersionFrom(this.versionConsumingReadOnlySessions)
									.orElse(minimalActiveVersion) :
								minimalActiveVersion,
							traits.isReadWrite() ?
								minimalActiveVersion :
								getMinimalVersionFrom(this.versionConsumingReadWriteSessions)
									.orElse(minimalActiveVersion)
						);
					}
				} catch (CatalogTransitioningException ignored) {
					// catalog is transitioning, we cannot notify it anyway
				}
			}
		}

		/**
		 * Retrieves the minimal version from the provided ConcurrentHashMap of versions.
		 *
		 * @param targetIndex a ConcurrentHashMap where the keys represent version numbers
		 *                    and the values are associated integer data.
		 * @return an {@link OptionalLong} containing the minimum version number
		 * if the ConcurrentHashMap is not empty, otherwise an empty {@link OptionalLong}.
		 */
		@Nonnull
		private static OptionalLong getMinimalVersionFrom(@Nonnull ConcurrentHashMap<Long, Integer> targetIndex) {
			if (targetIndex.isEmpty()) {
				return OptionalLong.empty();
			}
			long min = Long.MAX_VALUE;
			for (Long version : targetIndex.keySet()) {
				if (version < min) {
					min = version;
				}
			}
			return min == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(min);
		}

	}

	/**
	 * The SessionRegistryDataStore is a utility class used to manage active sessions.
	 * It maintains an internal index of sessions and provides methods for session retrieval,
	 * addition, and removal.
	 */
	public static class SessionRegistryDataStore {
		/**
		 * Keeps information about currently active sessions.
		 */
		private final Map<UUID, EvitaSessionTuple> activeSessions = CollectionUtils.createConcurrentHashMap(512);

		/**
		 * Method returns active session by its unique id or empty value if such session is not found.
		 */
		@Nonnull
		public Optional<EvitaSessionContract> getActiveSessionById(@Nonnull UUID sessionId) {
			return ofNullable(this.activeSessions.get(sessionId))
				.map(EvitaSessionTuple::proxySession);
		}

		/**
		 * Returns a stream of all active (currently open) sessions.
		 */
		@Nonnull
		public Stream<EvitaSessionContract> getActiveSessions() {
			return this.activeSessions.values()
				.stream()
				.map(EvitaSessionTuple::proxySession);
		}

		/**
		 * Method adds an active session to the internal index.
		 *
		 * @param activeSession the active session to be added
		 */
		void addSession(@Nonnull EvitaSessionTuple activeSession) {
			this.activeSessions.put(activeSession.plainSession.getId(), activeSession);
		}

		/**
		 * Method removes an active session from the internal index and returns it.
		 *
		 * @param sessionId the unique id of the session
		 * @return the session that was removed or NULL if such session is not found
		 */
		@Nullable
		EvitaSessionTuple removeSession(@Nonnull UUID sessionId) {
			return this.activeSessions.remove(sessionId);
		}
	}

	/**
	 * Holds a catalog version against reclamation on behalf of a backup, and gives that hold back - see
	 * {@link CatalogConsumerControl}. Sessions are not held this way: they are pinned and released by
	 * {@link VersionConsumingSessions}, which also keeps the consumer census this class stays out of.
	 */
	@RequiredArgsConstructor
	private static class CatalogConsumerControlInternal implements CatalogConsumerControl {
		private final Supplier<Catalog> catalog;

		@Nonnull
		@Override
		public CatalogVersionPin pinCatalogVersion(long version) {
			// deliberately intolerant, unlike the session registration above. The only caller is a backup, and this
			// pin is the whole of its protection against having the history it is copying reclaimed underneath it -
			// its own post-pin re-verification is conclusive *because* the pin landed first. Swallowing the failure
			// would not degrade the backup, it would silently remove the guarantee and let it run to completion over
			// files that are free to be deleted. A `CatalogTransitioningException` is likewise left to propagate: the
			// caller can retry, whereas a backup that never held anything cannot be repaired afterwards
			final Catalog theCatalog = this.catalog.get();
			if (theCatalog == null) {
				throw new GenericEvitaInternalError(
					"Catalog is not available - catalog version " + version +
						" cannot be held against reclamation!"
				);
			}
			theCatalog.catalogVersionPinned(version);
			// bound to `theCatalog` and not to this supplier: a rename or a replace between here and the release must
			// not be able to redirect the release onto the instance that took over the name
			return CatalogVersionPin.pinnedOn(version, theCatalog::catalogVersionReleased);
		}

	}

	/**
	 * This record is used to keep information about the current suspension period.
	 */
	private record InSuspension(
		@Nonnull SuspendOperation suspendOperation,
		@Nonnull CompletableFuture<Void> suspendFuture
	) {

		InSuspension(@Nonnull SuspendOperation suspendOperation) {
			this(
				suspendOperation,
				new CompletableFuture<>()
			);
		}

		/**
		 * Waits for the suspension period to finish or times out after the specified duration.
		 *
		 * @param timeout  the maximum time to wait for the suspension to finish, in the given time unit
		 * @param timeUnit the unit of time for the timeout parameter, must not be null
		 * @return true if the suspension period finishes within the specified timeout, false if the timeout occurs
		 * @throws SessionBusyException if an error occurs during the wait or the current thread is interrupted
		 */
		boolean awaitFinish(int timeout, @Nonnull TimeUnit timeUnit) {
			return FutureAwaiter.awaitWithTimeout(this.suspendFuture, timeout, timeUnit);
		}

	}

}
