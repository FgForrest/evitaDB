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

package io.evitadb.core;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.observability.trace.RepresentsMutation;
import io.evitadb.api.observability.trace.RepresentsQuery;
import io.evitadb.api.observability.trace.Traced;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.core.exception.SessionBusyException;
import io.evitadb.core.metric.event.session.ClosedEvent;
import io.evitadb.core.metric.event.session.OpenedEvent;
import io.evitadb.core.metric.event.transaction.TransactionFinishedEvent;
import io.evitadb.core.metric.event.transaction.TransactionResolution;
import io.evitadb.core.metric.event.transaction.TransactionStartedEvent;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Session registry maintains all active sessions for the {@link Evita} instance. It provides access to the sessions,
 * allows to terminate them or update a {@link Catalog} reference in them in a batch mode.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
final class SessionRegistry {
	/**
	 * Provides the tracing context for tracking the execution flow in the application.
	 **/
	private final TracingContext tracingContext;
	/**
	 * Reference to {@link Catalog} this instance is bound to.
	 */
	private final Supplier<Catalog> catalog;
	/**
	 * Keeps information about currently active sessions in one big data store that contains index across all catalogs.
	 */
	private final SessionRegistryDataStore sharedDataStore;
	/**
	 * Keeps information about currently active sessions.
	 */
	private final Map<UUID, EvitaSessionTuple> activeSessions = CollectionUtils.createConcurrentHashMap(512);
	/**
	 * This field is used to keep track of the current suspend operation (if any).
	 */
	private final AtomicReference<InSuspension> activeSuspendOperation = new AtomicReference<>(null);
	/**
	 * Keeps information about sessions sorted according to date of creation.
	 */
	private final ConcurrentLinkedQueue<EvitaSessionTuple> sessionsFifoQueue = new ConcurrentLinkedQueue<>();
	/**
	 * The catalogConsumedVersions variable is used to keep track of consumed versions along with number of sessions
	 * tied to them indexed by catalog names.
	 */
	private final ConcurrentHashMap<String, VersionConsumingSessions> catalogConsumedVersions = CollectionUtils.createConcurrentHashMap(32);

	/**
	 * Created data store to be shared among all SessionRegistry instances.
	 * @return the data store
	 */
	@Nonnull
	public static SessionRegistryDataStore createDataStore() {
		return new SessionRegistryDataStore();
	}

	/**
	 * Method closes and removes all active sessions from the registry.
	 * All changes are rolled back.
	 */
	public void closeAllActiveSessionsAndSuspend(@Nonnull SuspendOperation suspendOperation) {
		if (this.activeSuspendOperation.compareAndSet(null, new InSuspension(suspendOperation))) {
			final long start = System.currentTimeMillis();
			do {
				final List<CompletableFuture<Long>> futures = new ArrayList<>(this.activeSessions.size());
				for (EvitaSessionTuple sessionTuple : this.activeSessions.values()) {
					final EvitaSession plainSession = sessionTuple.plainSession();
					final EvitaInternalSessionContract proxySession = sessionTuple.proxySession();
					if (proxySession.isActive()) {
						proxySession
							// close the session once the running method is finished or immediately if there is no method running
							.executeWhenMethodIsNotRunning(
								() -> {
									if (plainSession.isActive()) {
										if (plainSession.isTransactionOpen()) {
											plainSession.setRollbackOnly();
										}
										final UUID sessionId = plainSession.getId();
										log.info("There is still active session {} - terminating.", sessionId);
										futures.add(plainSession.closeNow(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE));
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
				"Some of the sessions didn't clean themselves (" +
					this.activeSessions.values()
						.stream()
						.map(EvitaSessionTuple::plainSession)
						.map(it -> it.getId() + ((it.isActive()) ? ": active" : ": closed"))
						.collect(Collectors.joining(", "))
					+ ")!"
			);
		}
	}

	/**
	 * Method resumes operations on this registry - i.e. creating new sessions.
	 */
	public void resumeOperations() {
		final InSuspension inSuspension = this.activeSuspendOperation.getAndSet(null);
		if (inSuspension != null) {
			inSuspension.suspendFuture().complete(null);
		}
	}

	/**
	 * Creates and registers new session to the registry.
	 * Method checks that there is only a single active session when catalog is in warm-up mode.
	 */
	@Nonnull
	public EvitaInternalSessionContract addSession(boolean transactional, @Nonnull Supplier<EvitaSession> sessionSupplier) {
		return handleSuspension(() -> {
			if (!transactional && !this.activeSessions.isEmpty()) {
				throw new ConcurrentInitializationException(this.activeSessions.keySet().iterator().next());
			}

			final EvitaSession newSession = sessionSupplier.get();
			final long catalogVersion = newSession.getCatalogVersion();
			final String catalogName = newSession.getCatalogName();

			final EvitaInternalSessionContract newSessionProxy = (EvitaInternalSessionContract) Proxy.newProxyInstance(
				EvitaInternalSessionContract.class.getClassLoader(),
				new Class[]{EvitaInternalSessionContract.class, EvitaProxyFinalization.class},
				new EvitaSessionProxy(newSession, this.tracingContext)
			);
			final EvitaSessionTuple sessionTuple = new EvitaSessionTuple(newSession, newSessionProxy);
			sessionTuple.executeAtomically(
				() -> {
					this.activeSessions.put(newSession.getId(), sessionTuple);
					this.sessionsFifoQueue.add(sessionTuple);
					this.catalogConsumedVersions.computeIfAbsent(catalogName, k -> new VersionConsumingSessions())
						.registerSessionConsumingCatalogInVersion(catalogVersion);
					this.sharedDataStore.addSession(sessionTuple);
				}
			);

			return newSessionProxy;
		});
	}

	/**
	 * Removes session from the registry.
	 */
	public void removeSession(@Nonnull EvitaSession session) {
		final EvitaSessionTuple theSessionToRemove = this.activeSessions.remove(session.getId());
		if (theSessionToRemove != null) {
			theSessionToRemove.executeAtomically(
				() -> {
					final EvitaSessionTuple globallySharedSession = this.sharedDataStore.removeSession(session.getId());
					Assert.isPremiseValid(
						theSessionToRemove == globallySharedSession,
						"Session not found in the globally shared data store."
					);
					Assert.isPremiseValid(this.sessionsFifoQueue.remove(theSessionToRemove), "Session not found in the queue.");

					session.getTransaction().ifPresent(transaction -> {
						// emit event
						transaction.getFinalizationEvent()
							.finishWithResolution(
								this.sessionsFifoQueue.stream()
									.map(EvitaSessionTuple::plainSession)
									.filter(it -> it.getOpenedTransaction().isPresent())
									.map(EvitaSession::getCreated)
									.findFirst()
									.orElse(null),
								transaction.isRollbackOnly() ? TransactionResolution.ROLLBACK : TransactionResolution.COMMIT
							).commit();
					});

					this.catalogConsumedVersions.get(session.getCatalogName())
						.unregisterSessionConsumingCatalogInVersion(session.getCatalogVersion(), this.catalog);

					// emit event
					//noinspection CastToIncompatibleInterface,resource
					((EvitaProxyFinalization) theSessionToRemove.proxySession())
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
	 * Returns control object that allows external objects signalize work with the catalog of particular version.
	 *
	 * @param catalogName the name of the catalog
	 * @return the control object
	 */
	@Nonnull
	public CatalogConsumerControl createCatalogConsumerControl(@Nonnull String catalogName) {
		return new CatalogConsumerControlInternal(
			this.catalogConsumedVersions.computeIfAbsent(catalogName, k -> new VersionConsumingSessions()),
			this.catalog
		);
	}

	/**
	 * Internal method that creates and initializes session and returns it.
	 * @param sessionFactory the function that creates the session
	 * @return the created session
	 */
	@Nonnull
	public EvitaInternalSessionContract createSession(@Nonnull Function<SessionRegistry, EvitaInternalSessionContract> sessionFactory) {
		return handleSuspension(() -> sessionFactory.apply(this));
	}

	/**
	 * Handles a suspension operation based on the current state.
	 * If there is an active suspend operation, it evaluates its behavior and
	 * acts accordingly by either postponing the operation, awaiting completion,
	 * or throwing an exception. If no active suspend operation is detected, it
	 * proceeds with the supplied operation.
	 *
	 * @param <T> the type of the result provided by the supplier
	 * @param supplier a non-null supplier that provides the operation to execute
	 *                 if suspension allows it
	 * @return the result of the supplier's operation if executed successfully
	 * @throws SessionBusyException if the suspension operation has been postponed
	 *                              and could not finish within the timeout period
	 * @throws InstanceTerminatedException if the suspension operation indicates
	 *                                     the instance termination
	 */
	private <T> T handleSuspension(@Nonnull Supplier<T> supplier) {
		final InSuspension inSuspension = this.activeSuspendOperation.get();
		if (inSuspension == null || inSuspension.suspendThreadId() == Thread.currentThread().getId()) {
			return supplier.get();
		} else if (inSuspension.suspendOperation() == SuspendOperation.POSTPONE) {
			if (inSuspension.awaitFinish(500, TimeUnit.MILLISECONDS)) {
				return supplier.get();
			} else {
				throw SessionBusyException.INSTANCE;
			}
		} else {
			throw new InstanceTerminatedException("catalog");
		}
	}

	/**
	 * Internal interface for finalizing the session proxy.
	 */
	private interface EvitaProxyFinalization {

		/**
		 * Method should be called when session proxy is terminated.
		 *
		 * @param oldestSessionTimestamp the oldest active session timestamp
		 * @param activeSessions         the number of still active sessions
		 */
		void finish(@Nullable OffsetDateTime oldestSessionTimestamp, int activeSessions);

	}

	/**
	 * This handler is an infrastructural handler that delegates all calls to {@link #evitaSession}. We'll pay some
	 * performance price by wrapping {@link EvitaSession} in a proxy, that uses this error handler (all calls on session
	 * object will be approximately 1.7x less performant -
	 * <a href="http://ordinaryjava.blogspot.com/2008/08/benchmarking-cost-of-dynamic-proxies.html">source</a>) but this
	 * way we can isolate the error logging / translation in one place and avoid cluttering the source code. Graal
	 * supports JDK proxies out-of-the-box so this shouldn't be a problem in the future.
	 */
	private static class EvitaSessionProxy implements InvocationHandler {
		private final static Method IS_ACTIVE;
		private final static Method IS_METHOD_RUNNING;
		private final static Method WHEN_METHOD_IS_NOT_RUNNING;
		private final static Method INACTIVITY_IN_SECONDS;
		private final EvitaSession evitaSession;
		private final TracingContext tracingContext;
		@Getter private final ClosedEvent sessionClosedEvent;
		private final AtomicInteger insideInvocation = new AtomicInteger(0);
		private final AtomicLong lastCall = new AtomicLong(System.currentTimeMillis());
		private final AtomicReference<ClosingSequence> closeLambda = new AtomicReference<>(null);

		static {
			try {
				IS_ACTIVE = EvitaInternalSessionContract.class.getMethod("isActive");
				IS_METHOD_RUNNING = EvitaInternalSessionContract.class.getMethod("methodIsRunning");
				WHEN_METHOD_IS_NOT_RUNNING = EvitaInternalSessionContract.class.getMethod("executeWhenMethodIsNotRunning", Runnable.class);
				INACTIVITY_IN_SECONDS = EvitaInternalSessionContract.class.getMethod("getInactivityDurationInSeconds");
			} catch (NoSuchMethodException ex) {
				throw new GenericEvitaInternalError("Method not found.", ex);
			}
		}

		/**
		 * Handles arguments printing.
		 */
		@Nonnull
		private static String printArguments(@Nonnull Method method, @Nullable Object[] args) {
			final StringBuilder sb = new StringBuilder(256);
			if (args != null) {
				for (int i = 0; i < args.length; i++) {
					Object arg = args[i];
					if (i > 0) {
						sb.append("|");
					}
					sb.append(method.getParameters()[i].getName()).append("=").append(arg);
				}
			}
			return sb.toString();
		}

		public EvitaSessionProxy(@Nonnull EvitaSession evitaSession, @Nonnull TracingContext tracingContext) {
			this.evitaSession = evitaSession;
			this.tracingContext = tracingContext;
			final String catalogName = evitaSession.getCatalogName();

			// emit and prepare events
			new OpenedEvent(catalogName).commit();
			this.sessionClosedEvent = new ClosedEvent(catalogName);

			evitaSession.getTransaction()
				.ifPresent(transaction -> {
					// emit event
					new TransactionStartedEvent(
						catalogName
					).commit();
					// prepare finalization event
					transaction.setFinalizationEvent(
						new TransactionFinishedEvent(catalogName)
					);
				});
		}

		@Nullable
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass().equals(EvitaProxyFinalization.class)) {
				this.sessionClosedEvent
					.finish((OffsetDateTime) args[0], (int) args[1])
					.commit();
				return null;
			} else if (method.equals(INACTIVITY_IN_SECONDS)) {
				return (System.currentTimeMillis() - this.lastCall.get()) / 1000;
			} else if (method.equals(IS_ACTIVE)) {
				// if we know that the session is being closed on proxy level
				if (this.closeLambda.get() != null) {
					// return eagerly false result
					return false;
				} else {
					return executeDelegateMethod(method, args);
				}
			} else if (method.equals(IS_METHOD_RUNNING)) {
				return this.insideInvocation.get() > 0;
			} else if (method.equals(WHEN_METHOD_IS_NOT_RUNNING)) {
				final ClosingSequence closingSequence = new ClosingSequence((Runnable) args[0]);
				Assert.isPremiseValid(
					this.closeLambda.compareAndSet(null, closingSequence),
					"Close lambda is already set!"
				);
				// execute immediately
				if (this.insideInvocation.get() == 0) {
					executeCloseLambda(closingSequence);
				}
				return null;
			} else {
				try {
					final ClosingSequence closingSequence = this.closeLambda.get();
					// if there's a closing sequence in place
					if (closingSequence != null) {
						// wait for it to finish
						if (closingSequence.awaitFinish(500, TimeUnit.MILLISECONDS)) {
							// and then execute the call - it probably ends up with exception that session was closed
							return executeDelegateMethod(method, args);
						} else {
							throw SessionBusyException.INSTANCE;
						}
					} else {
						// standard delegated execution - normal operation
						return executeDelegateMethod(method, args);
					}
				} finally {
					final ClosingSequence closingSequence = this.closeLambda.get();
					// if there is newly registered close lambda and there is no other method being executed
					if (this.insideInvocation.get() == 0 && closingSequence != null) {
						// execute close lambda
						executeCloseLambda(closingSequence);
					}
				}
			}
		}

		/**
		 * Executes the closeLambda, a function intended to release resources or perform cleanup operations.
		 * This method ensures safe execution of the lambda function by managing thread-synchronization
		 * through a CountDownLatch.
		 */
		private void executeCloseLambda(@Nonnull ClosingSequence closingSequence) {
			if (this.closeLambda.compareAndSet(closingSequence, null)) {
				try {
					closingSequence.closeLambda().run();
				} finally {
					closingSequence.closedFuture().complete(null);
				}
			} else {
				// close lambda was already executed
			}
		}

		/**
		 * Executes a delegate method within the current session while handling transactions, tracing, and error logging.
		 * This method wraps the method invocation with additional logic for transaction management,
		 * query and mutation tracking, and tracing execution based on annotations.
		 *
		 * @param method the method to be invoked on the delegate object, must not be null
		 * @param args the arguments to pass to the method being invoked, must not be null
		 * @return the result of the method invocation or null if the method returns void or there is an error
		 * @throws TransactionException if a transaction-related error occurs during execution
		 * @throws EvitaInvalidUsageException if an invalid usage of the Evita API is detected
		 * @throws EvitaInternalError if an internal Evita-specific error occurs
		 * @throws GenericEvitaInternalError if an unexpected system error occurs during execution
		 */
		@Nullable
		private Object executeDelegateMethod(@Nonnull Method method, @Nullable Object[] args) {
			try {
				this.evitaSession.increaseNestLevel();
				// invoke original method on delegate
				return Transaction.executeInTransactionIfProvided(
					this.evitaSession.getOpenedTransaction().orElse(null),
					() -> {
						final Supplier<Object> invocation = () -> {
							try {
								this.insideInvocation.incrementAndGet();
								this.lastCall.set(System.currentTimeMillis());
								return method.invoke(evitaSession, args);
							} catch (InvocationTargetException ex) {
								// handle the error
								final Throwable targetException = ex.getTargetException() instanceof CompletionException completionException ?
									completionException.getCause() : ex.getTargetException();
								if (targetException instanceof TransactionException transactionException) {
									// just unwrap and rethrow
									throw transactionException;
								} else if (targetException instanceof EvitaInvalidUsageException evitaInvalidUsageException) {
									// just unwrap and rethrow
									throw evitaInvalidUsageException;
								} else if (targetException instanceof EvitaInternalError evitaInternalError) {
									if (log.isErrorEnabled()) {
										log.error(
											"Internal Evita error occurred in " + evitaInternalError.getErrorCode() +
												": " + evitaInternalError.getPrivateMessage() + "," +
												" arguments: " + printArguments(method, args),
											targetException
										);
									}
									// unwrap and rethrow
									throw evitaInternalError;
								} else {
									if (log.isErrorEnabled()) {
										log.error(
											"Unexpected internal Evita error occurred: " + ex.getCause().getMessage() + ", " +
												" arguments: " + printArguments(method, args),
											targetException == null ? ex : targetException
										);
									}
									throw new GenericEvitaInternalError(
										"Unexpected internal Evita error occurred: " + ex.getCause().getMessage(),
										"Unexpected internal Evita error occurred.",
										targetException == null ? ex : targetException
									);
								}
							} catch (Throwable ex) {
								if (log.isErrorEnabled()) {
									log.error(
										"Unexpected system error occurred: " + ex.getMessage() + "," +
											" arguments: " + printArguments(method, args),
										ex
									);
								}
								throw new GenericEvitaInternalError(
									"Unexpected system error occurred: " + ex.getMessage(),
									"Unexpected system error occurred.",
									ex
								);
							} finally {
								this.insideInvocation.decrementAndGet();
								this.lastCall.set(System.currentTimeMillis());
							}
						};
						if (method.isAnnotationPresent(RepresentsQuery.class)) {
							this.sessionClosedEvent.recordQuery();
						}
						if (method.isAnnotationPresent(RepresentsMutation.class)) {
							this.sessionClosedEvent.recordMutation();
						}
						if (method.isAnnotationPresent(Traced.class)) {
							return tracingContext.executeWithinBlockIfParentContextAvailable(
								"session call - " + method.getName(),
								invocation,
								() -> {
									final Parameter[] parameters = method.getParameters();
									final SpanAttribute[] spanAttributes = new SpanAttribute[1 + parameters.length];
									spanAttributes[0] = new SpanAttribute("session.id", this.evitaSession.getId().toString());
									if (args == null || args.length == 0) {
										return spanAttributes;
									} else {
										int index = 1;
										for (int i = 0; i < args.length; i++) {
											final Object arg = args[i];
											if (EvitaDataTypes.isSupportedType(parameters[i].getType()) && arg != null) {
												spanAttributes[index++] = new SpanAttribute(parameters[i].getName(), arg);
											}
										}
										return index < spanAttributes.length ?
											Arrays.copyOfRange(spanAttributes, 0, index) : spanAttributes;
									}
								}
							);
						} else {
							return invocation.get();
						}
					},
					this.evitaSession.isRootLevelExecution()
				);
			} finally {
				this.evitaSession.decreaseNestLevel();
			}
		}
	}

	/**
	 * Record that provides access both to the latch and the close lambda.
	 *
	 * @param closeLambda the close lambda that is executed when the session is closed
	 * @param closedFuture the future that is completed when the session is closed
	 */
	private record ClosingSequence(
		@Nonnull Runnable closeLambda,
		@Nonnull CompletableFuture<Void> closedFuture
	) {

		private ClosingSequence(@Nonnull Runnable closeLambda) {
			this(closeLambda, new CompletableFuture<>());
		}

		/**
		 * Waits for the session to finish closing within the specified timeout period.
		 *
		 * @param timeout the maximum time to wait for the session to finish, in the given time unit
		 * @param timeUnit the time unit of the timeout parameter
		 * @return {@code true} if the session finished closing within the timeout period, {@code false} if the timeout elapsed
		 * @throws SessionBusyException if an InterruptedException or ExecutionException occurs while waiting
		 */
		public boolean awaitFinish(int timeout, @Nonnull TimeUnit timeUnit) {
			try {
				this.closedFuture.get(timeout, timeUnit);
				return true;
			} catch (TimeoutException e) {
				return false;
			} catch (InterruptedException | ExecutionException e) {
				throw SessionBusyException.INSTANCE;
			}
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
		@Nonnull ReentrantLock atomicLock
	) {

		private EvitaSessionTuple(@Nonnull EvitaSession plainSession, @Nonnull EvitaInternalSessionContract proxySession) {
			this(
				plainSession,
				proxySession,
				new ReentrantLock()
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
		 * The keys of the map are the versions of the catalogs, and the values are the number of sessions consuming
		 * catalogs in that version.
		 */
		private final ConcurrentHashMap<Long, Integer> versionConsumingSessions = CollectionUtils.createConcurrentHashMap(32);

		/**
		 * Registers a session consuming catalog in the specified version.
		 *
		 * @param version the version of the catalog
		 */
		void registerSessionConsumingCatalogInVersion(long version) {
			this.versionConsumingSessions.compute(
				version,
				(k, v) -> v == null ? 1 : v + 1
			);
		}

		/**
		 * Unregisters a session that is consuming a catalog in the specified version.
		 *
		 * @param version the version of the catalog
		 * @param catalog the supplier of currently active catalog instance
		 */
		void unregisterSessionConsumingCatalogInVersion(long version, @Nonnull Supplier<Catalog> catalog) {
			final Integer readerCount = this.versionConsumingSessions.compute(
				version,
				(k, v) -> v== null || v == 1 ? null : v - 1
			);

			// the minimal active catalog version used by another session now
			final OptionalLong minimalActiveCatalogVersion;
			// TRUE when the session was the last reader
			final boolean lastReader;
			if (readerCount == null) {
				minimalActiveCatalogVersion = this.versionConsumingSessions.keySet().stream().mapToLong(Long::longValue).min();
				lastReader = true;
			} else {
				minimalActiveCatalogVersion = OptionalLong.of(version);
				lastReader = false;
			}

			if (lastReader) {
				// notify listeners that the catalog version is no longer used
				final Catalog theCatalog = catalog.get();
				// in rare cases (catalog replacement) the catalog might not have been available already
				if (theCatalog != null) {
					final long minimalActiveVersion = minimalActiveCatalogVersion.orElse(theCatalog.getVersion());
					theCatalog.consumersLeft(minimalActiveVersion);
				}
			}
		}

	}

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
		 * Method adds an active session to the internal index.
		 * @param activeSession the active session to be added
		 */
		void addSession(@Nonnull EvitaSessionTuple activeSession) {
			this.activeSessions.put(activeSession.plainSession.getId(), activeSession);
		}

		/**
		 * Method removes an active session from the internal index and returns it.
		 * @param sessionId the unique id of the session
		 * @return the session that was removed or NULL if such session is not found
		 */
		@Nullable
		EvitaSessionTuple removeSession(@Nonnull UUID sessionId) {
			return this.activeSessions.remove(sessionId);
		}

		/**
		 * Returns set of all active (currently open) sessions.
		 */
		@Nonnull
		public Stream<EvitaSessionContract> getActiveSessions() {
			return this.activeSessions.values()
				.stream()
				.map(EvitaSessionTuple::proxySession);
		}
	}

	/**
	 * This interface allows external objects signalize work with the catalog of particular version.
	 */
	@RequiredArgsConstructor
	private static class CatalogConsumerControlInternal implements CatalogConsumerControl {
		private final VersionConsumingSessions versionConsumingSessions;
		private final Supplier<Catalog> catalog;

		@Override
		public void registerConsumerOfCatalogInVersion(long version) {
			this.versionConsumingSessions.registerSessionConsumingCatalogInVersion(version);
		}

		@Override
		public void unregisterConsumerOfCatalogInVersion(long version) {
			this.versionConsumingSessions.unregisterSessionConsumingCatalogInVersion(version, this.catalog);
		}

	}

	/**
	 * This record is used to keep information about the current suspension period.
	 */
	private record InSuspension(
		@Nonnull SuspendOperation suspendOperation,
		@Nonnull CompletableFuture<Void> suspendFuture,
		/* TOBEDONE #187 - TRY TO REMOVE THIS LOGIC WITH BETTER REFRESHING LOGIC IN APIS */
		long suspendThreadId
	) {

		public InSuspension(@Nonnull SuspendOperation suspendOperation) {
			this(
				suspendOperation,
				new CompletableFuture<>(),
				Thread.currentThread().getId()
			);
		}

		/**
		 * Waits for the suspension period to finish or times out after the specified duration.
		 *
		 * @param timeout the maximum time to wait for the suspension to finish, in the given time unit
		 * @param timeUnit the unit of time for the timeout parameter, must not be null
		 * @return true if the suspension period finishes within the specified timeout, false if the timeout occurs
		 * @throws SessionBusyException if an error occurs during the wait or the current thread is interrupted
		 */
		public boolean awaitFinish(int timeout, @Nonnull TimeUnit timeUnit) {
			try {
				this.suspendFuture.get(timeout, timeUnit);
				return true;
			} catch (TimeoutException e) {
				return false;
			} catch (InterruptedException | ExecutionException e) {
				throw SessionBusyException.INSTANCE;
			}
		}

	}

	/**
	 * Enum contains different levels of suspension operations for the SessionRegistry.
	 */
	public enum SuspendOperation {
		/**
		 * All incoming operations are temporarily suspended due to internal operations with the catalog - such
		 * as rename or replace.
		 */
		POSTPONE,
		/**
		 * All incoming operations should be immediately rejected because the catalog is being terminated.
		 */
		REJECT
	}

}
