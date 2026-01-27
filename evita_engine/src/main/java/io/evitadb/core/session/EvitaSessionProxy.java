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

import io.evitadb.api.exception.RollbackException;
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
import io.evitadb.core.metric.event.transaction.TransactionStartedEvent;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Proxy handler that wraps EvitaSession and provides:
 *
 * - Method invocation tracking for inactivity detection
 * - Transaction context management
 * - Exception translation and logging
 * - Tracing and metrics recording
 *
 * This handler is an infrastructural handler that delegates all calls to the wrapped session. We'll pay some
 * performance price by wrapping {@link EvitaSession} in a proxy, that uses this error handler (all calls on session
 * object will be approximately 1.7x less performant -
 * <a href="http://ordinaryjava.blogspot.com/2008/08/benchmarking-cost-of-dynamic-proxies.html">source</a>) but this
 * way we can isolate the error logging / translation in one place and avoid cluttering the source code. Graal
 * supports JDK proxies out-of-the-box so this shouldn't be a problem in the future.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see SessionRegistry for session registry implementation
 */
@Slf4j
final class EvitaSessionProxy implements InvocationHandler {
	private static final Method IS_ACTIVE = resolveMethod("isActive");
	private static final Method IS_METHOD_RUNNING = resolveMethod("methodIsRunning");
	private static final Method WHEN_METHOD_IS_NOT_RUNNING = resolveMethod(
		"executeWhenMethodIsNotRunning", Runnable.class
	);
	private static final Method INACTIVITY_IN_SECONDS = resolveMethod("getInactivityDurationInSeconds");
	private static final Method IS_INACTIVE_AND_IDLE = resolveMethod("isInactiveAndIdle", long.class);

	private final EvitaSession evitaSession;
	private final TracingContext tracingContext;
	@Getter private final ClosedEvent sessionClosedEvent;
	private final AtomicInteger insideInvocation = new AtomicInteger(0);
	private final AtomicLong lastCall = new AtomicLong(System.currentTimeMillis());
	private final AtomicReference<ClosingSequence> closeLambda = new AtomicReference<>(null);

	/**
	 * Resolves a method from {@link EvitaInternalSessionContract} by name and parameter types.
	 *
	 * @param name       the method name to resolve
	 * @param paramTypes the parameter types of the method
	 * @return the resolved Method object
	 * @throws GenericEvitaInternalError if the method is not found
	 */
	@Nonnull
	private static Method resolveMethod(@Nonnull String name, @Nonnull Class<?>... paramTypes) {
		try {
			return EvitaInternalSessionContract.class.getMethod(name, paramTypes);
		} catch (NoSuchMethodException ex) {
			throw new GenericEvitaInternalError("Method not found: " + name, ex);
		}
	}

	/**
	 * Unwraps an exception from an {@link InvocationTargetException} to retrieve the root cause.
	 * If the target exception is a {@link CompletionException}, its cause is returned. Otherwise, the target
	 * exception itself is returned.
	 *
	 * @param ex the {@link InvocationTargetException} containing the wrapped exception, must not be null
	 * @return the unwrapped {@link Throwable}, which is the root cause of the provided exception
	 */
	@Nonnull
	private static Throwable unwrapException(@Nonnull InvocationTargetException ex) {
		final Throwable target = ex.getTargetException();
		return (target instanceof CompletionException ce) ? ce.getCause() : target;
	}

	/**
	 * Handles a {@link RollbackException} that may occur during a transaction rollback.
	 * If the session is in dry-run mode, this method logs the rollback event and suppresses the exception.
	 * Otherwise, the exception is returned to be propagated.
	 *
	 * @param re     the {@link RollbackException} to handle, must not be null
	 * @param method the method where the exception occurred, must not be null
	 * @return the {@link RuntimeException} to propagate, or null if the exception is suppressed in dry-run mode
	 */
	@Nullable
	private RuntimeException handleRollbackException(@Nonnull RollbackException re, @Nonnull Method method) {
		if (this.evitaSession.isDryRun()) {
			log.debug("Session was initiated in dry run mode, so transaction was rolled back.");
			Assert.isPremiseValid(
				void.class.equals(method.getReturnType()),
				"RollbackException is expected only for close method that returns void!"
			);
			return null; // swallow exception in dry-run mode
		}
		return re;
	}

	/**
	 * Handles an internal error specific to Evita by logging the error details if error logging is enabled.
	 *
	 * @param eie    the EvitaInternalError instance containing details about the error, must not be null
	 * @param method the method where the error occurred, must not be null
	 * @param args   the arguments passed to the method, may be null
	 * @return the same EvitaInternalError instance passed as a parameter
	 */
	@Nonnull
	private static EvitaInternalError handleEvitaInternalError(
		@Nonnull EvitaInternalError eie,
		@Nonnull Method method,
		@Nullable Object[] args
	) {
		if (log.isErrorEnabled()) {
			log.error(
				"Internal Evita error occurred in " + eie.getErrorCode() +
					": " + eie.getPrivateMessage() + "," +
					" arguments: " + printArguments(method, args),
				eie
			);
		}
		return eie;
	}

	/**
	 * Handles unexpected internal errors by logging the error details, including any associated cause
	 * and method arguments. Wraps the error in a {@link GenericEvitaInternalError} and returns it
	 * for further handling.
	 *
	 * @param cause  the original cause of the error, may be null
	 * @param ex     the {@link InvocationTargetException} that contains the actual exception, must not be null
	 * @param method the method where the error occurred, must not be null
	 * @param args   the arguments passed to the method, may be null
	 * @return a {@link GenericEvitaInternalError} instance encapsulating the error details
	 */
	@Nonnull
	private static GenericEvitaInternalError handleUnexpectedInternalError(
		@Nullable Throwable cause,
		@Nonnull InvocationTargetException ex,
		@Nonnull Method method,
		@Nullable Object[] args
	) {
		final Throwable loggedException = cause != null ? cause : ex;
		if (log.isErrorEnabled()) {
			log.error(
				"Unexpected internal Evita error occurred: " + ex.getCause().getMessage() + ", " +
					" arguments: " + printArguments(method, args),
				loggedException
			);
		}
		return new GenericEvitaInternalError(
			"Unexpected internal Evita error occurred: " + ex.getCause().getMessage(),
			"Unexpected internal Evita error occurred.",
			loggedException
		);
	}

	/**
	 * Handles unexpected exceptions that occur during method execution by logging the error details
	 * and encapsulating the exception in a {@link GenericEvitaInternalError}.
	 *
	 * @param ex     the {@link Throwable} representing the unexpected exception, must not be null
	 * @param method the {@link Method} where the exception occurred, must not be null
	 * @param args   the arguments passed to the method, may be null
	 * @return a {@link GenericEvitaInternalError} instance encapsulating the error details
	 */
	@Nonnull
	private static GenericEvitaInternalError handleUnexpectedException(
		@Nonnull Throwable ex,
		@Nonnull Method method,
		@Nullable Object[] args
	) {
		if (log.isErrorEnabled()) {
			log.error(
				"Unexpected system error occurred: " + ex.getMessage() + "," +
					" arguments: " + printArguments(method, args),
				ex
			);
		}
		return new GenericEvitaInternalError(
			"Unexpected system error occurred: " + ex.getMessage(),
			"Unexpected system error occurred.",
			ex
		);
	}

	/**
	 * Handles an {@link InvocationTargetException} by analyzing the root cause and dispatching it
	 * to appropriate exception handling methods based on its type. If the root cause does not match
	 * any known exception type, it is wrapped in a more generic internal error handler for logging
	 * and propagation.
	 *
	 * @param ex     the {@link InvocationTargetException} encountered during method invocation
	 * @param method the {@link Method} where the exception occurred, must not be null
	 * @param args   the arguments passed to the method during invocation, may be null
	 * @return the {@link RuntimeException} instance to be propagated, or null if exception was handled
	 */
	@Nullable
	private RuntimeException handleInvocationException(
		@Nonnull InvocationTargetException ex,
		@Nonnull Method method,
		@Nullable Object[] args
	) {
		final Throwable cause = unwrapException(ex);

		// Dispatch based on exception type
		if (cause instanceof TransactionException te) {
			return te;
		}
		if (cause instanceof RollbackException re) {
			return handleRollbackException(re, method);
		}
		if (cause instanceof EvitaInvalidUsageException eiu) {
			return eiu;
		}
		if (cause instanceof EvitaInternalError eie) {
			return handleEvitaInternalError(eie, method, args);
		}

		return handleUnexpectedInternalError(cause, ex, method, args);
	}

	/**
	 * Records method execution metrics for the provided method. Specifically, it tracks whether the method
	 * represents a query or a mutation based on its annotations and updates the session's metrics accordingly.
	 *
	 * @param method the {@link Method} whose execution metrics need to be recorded, must not be null
	 */
	private void recordMethodMetrics(@Nonnull Method method) {
		if (method.isAnnotationPresent(RepresentsQuery.class)) {
			this.sessionClosedEvent.recordQuery();
		}
		if (method.isAnnotationPresent(RepresentsMutation.class)) {
			this.sessionClosedEvent.recordMutation();
		}
	}

	/**
	 * Builds an array of SpanAttribute based on the given method parameters,
	 * arguments, and session. Each parameter and its corresponding argument
	 * contribute to the SpanAttribute array if they meet the specified conditions.
	 *
	 * @param method  the method whose parameters are used to build SpanAttributes
	 * @param args    the arguments provided for the method call, corresponding to the parameters
	 * @param session the EvitaSession used to retrieve session information
	 * @return an array of SpanAttribute containing attributes for each valid parameter,
	 * and the session ID as the first element
	 */
	@Nonnull
	private static SpanAttribute[] buildSpanAttributes(
		@Nonnull Method method,
		@Nullable Object[] args,
		@Nonnull EvitaSession session
	) {
		final Parameter[] parameters = method.getParameters();

		if (args == null || args.length == 0) {
			// only session.id attribute
			return new SpanAttribute[]{new SpanAttribute("session.id", session.getId().toString())};
		}

		// first pass: count valid parameters to create exact-sized array
		int validParamCount = 0;
		for (int i = 0; i < args.length; i++) {
			if (EvitaDataTypes.isSupportedType(parameters[i].getType()) && args[i] != null) {
				validParamCount++;
			}
		}

		// create exact-sized array: 1 for session.id + valid params
		final SpanAttribute[] spanAttributes = new SpanAttribute[1 + validParamCount];
		spanAttributes[0] = new SpanAttribute("session.id", session.getId().toString());

		// second pass: populate array
		int index = 1;
		for (int i = 0; i < args.length; i++) {
			final Object arg = args[i];
			if (EvitaDataTypes.isSupportedType(parameters[i].getType()) && arg != null) {
				spanAttributes[index++] = new SpanAttribute(parameters[i].getName(), arg);
			}
		}

		return spanAttributes;
	}

	/**
	 * Executes a given method invocation with tracing capabilities if a parent tracing context is available.
	 * This method builds span attributes to include additional metadata for tracing and wraps the invocation
	 * within a predefined tracing block.
	 *
	 * @param method     the method being invoked, must not be null
	 * @param args       the arguments of the method call, can be null if the method has no parameters
	 * @param session    the active session in which the method is being executed, must not be null
	 * @param invocation a supplier representing the method invocation to be executed, must not be null
	 * @return the result of the executed method invocation, or null if the invocation returns null
	 */
	@Nullable
	private Object executeWithTracing(
		@Nonnull Method method,
		@Nullable Object[] args,
		@Nonnull EvitaSession session,
		@Nonnull Supplier<Object> invocation
	) {
		return this.tracingContext.executeWithinBlockIfParentContextAvailable(
			"session call - " + method.getName(),
			invocation,
			() -> buildSpanAttributes(method, args, session)
		);
	}

	/**
	 * Safely invokes a specified method on the target object with the provided arguments.
	 * This method increments and decrements the invocation counter and updates the last call time.
	 * It handles exceptions arising during the method invocation by delegating to specific handlers.
	 *
	 * **CRITICAL ATOMICITY CONTRACT:**
	 *
	 * This method implements an atomic inactivity check to prevent race conditions
	 * with the SessionKiller. The read/write ordering MUST NOT be changed.
	 *
	 * **Write Order (this method's finally block):**
	 * 1. Write lastCall FIRST
	 * 2. Decrement insideInvocation SECOND
	 *
	 * **Read Order (isInactiveAndIdle):**
	 * 1. Read insideInvocation FIRST
	 * 2. Read lastCall SECOND
	 *
	 * This ensures proper happens-before relationship: if isInactiveAndIdle sees
	 * insideInvocation == 0, the corresponding lastCall update is already visible.
	 *
	 * For verification of this contract, see test class
	 * `io.evitadb.core.session.task.SessionKillerTest#shouldNotKillSessionWhenMethodCompletesJustBeforeTermination`
	 *
	 * @param method the method to be invoked; must not be null
	 * @param args   the arguments to pass to the method; can be null if the method has no arguments
	 * @return the result of the invoked method, or null if method has void return or exception is caught
	 * @throws RuntimeException if an invocation or unexpected exception occurs during method invocation
	 */
	@Nullable
	private Object invokeMethodSafely(@Nonnull Method method, @Nullable Object[] args) {
		this.insideInvocation.incrementAndGet();
		this.lastCall.set(System.currentTimeMillis());

		try {
			return method.invoke(this.evitaSession, args);
		} catch (InvocationTargetException ex) {
			final RuntimeException handled = handleInvocationException(ex, method, args);
			if (handled != null) {
				throw handled;
			}
			return null;
		} catch (Throwable ex) {
			throw handleUnexpectedException(ex, method, args);
		} finally {
			// IMPORTANT: Update lastCall BEFORE decrementing insideInvocation
			// This ensures proper happens-before relationship with isInactiveAndIdle:
			// - isInactiveAndIdle reads insideInvocation first, then lastCall
			// - If it sees insideInvocation == 0, lastCall is guaranteed to be up-to-date
			this.lastCall.set(System.currentTimeMillis());
			this.insideInvocation.decrementAndGet();
		}
	}

	/**
	 * Executes a method with optional tracing and metric recording, based on annotations present on the method.
	 *
	 * @param method  the method to be executed, must not be null
	 * @param args    the arguments to be passed to the method, can be null
	 * @param session the Evita session to be used during execution, must not be null
	 * @return the result of the method execution, can be null
	 */
	@Nullable
	private Object executeMethodWithTracingAndMetrics(
		@Nonnull Method method,
		@Nullable Object[] args,
		@Nonnull EvitaSession session
	) {
		recordMethodMetrics(method);

		final Supplier<Object> invocation = () -> invokeMethodSafely(method, args);

		if (method.isAnnotationPresent(Traced.class)) {
			return executeWithTracing(method, args, session, invocation);
		} else {
			return invocation.get();
		}
	}

	/**
	 * Executes the provided method within the context of an existing or newly created transaction,
	 * ensuring transactional consistency for the operation. If a transaction is provided, the method
	 * executes within that transaction; otherwise, it handles execution as a root-level operation.
	 *
	 * @param method  the method to be executed within the transaction context, must not be null
	 * @param args    the arguments to pass to the method execution, can be null if the method has no parameters
	 * @param session the current session associated with the execution, must not be null
	 * @return the result of the method execution, or null if the return value is absent
	 */
	@Nullable
	private Object executeWithinTransactionContext(
		@Nonnull Method method,
		@Nullable Object[] args,
		@Nonnull EvitaSession session
	) {
		return Transaction.executeInTransactionIfProvided(
			session.getOpenedTransaction().orElse(null),
			() -> executeMethodWithTracingAndMetrics(method, args, session),
			session.isRootLevelExecution()
		);
	}

	/**
	 * Constructs a string representation of method arguments with their parameter names.
	 * If the argument is an array, it converts the array contents into a string format.
	 *
	 * @param method The method whose parameter names need to be included in the output string. Must not be null.
	 * @param args   The array of arguments passed to the method. Can be null.
	 * @return A string representation of the method arguments in the format "paramName1=value1|paramName2=value2...".
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
				sb.append(method.getParameters()[i].getName())
					.append("=");
				if (arg == null) {
					sb.append("null");
				} else if (arg.getClass().isArray()) {
					sb.append("[");
					for (int j = 0; j < Array.getLength(arg); j++) {
						if (j > 0) {
							sb.append(", ");
						}
						sb.append(Array.get(arg, j));
					}
					sb.append("]");
				} else {
					sb.append(arg);
				}
			}
		}
		return sb.toString();
	}

	EvitaSessionProxy(@Nonnull EvitaSession evitaSession, @Nonnull TracingContext tracingContext) {
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
		} else if (method.equals(IS_INACTIVE_AND_IDLE)) {
			// CRITICAL ATOMICITY CONTRACT:
			//
			// Read insideInvocation FIRST, then lastCall.
			// Combined with the write order in invokeMethodSafely (lastCall first, then insideInvocation),
			// this ensures proper happens-before relationship:
			// - If we see insideInvocation == 0, the corresponding lastCall update is already visible
			// - This prevents the race where: we read old lastCall, method completes,
			//   we read insideInvocation == 0
			//
			// Verified in: SessionKillerTest#shouldNotKillSessionWhenMethodCompletesJustBeforeTermination
			final boolean methodRunning = this.insideInvocation.get() > 0;
			if (methodRunning) {
				return false;
			}
			final long allowedInactivityInSeconds = (long) args[0];
			final long inactivitySeconds = (System.currentTimeMillis() - this.lastCall.get()) / 1000;
			return inactivitySeconds >= allowedInactivityInSeconds;
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
	 * @param args   the arguments to pass to the method being invoked, must not be null
	 * @return the result of the method invocation or null if the method returns void or there is an error
	 * @throws TransactionException       if a transaction-related error occurs during execution
	 * @throws EvitaInvalidUsageException if an invalid usage of the Evita API is detected
	 * @throws EvitaInternalError         if an internal Evita-specific error occurs
	 * @throws GenericEvitaInternalError  if an unexpected system error occurs during execution
	 */
	@Nullable
	private Object executeDelegateMethod(@Nonnull Method method, @Nullable Object[] args) {
		final EvitaSession theSession = this.evitaSession;
		theSession.increaseNestLevel();
		try {
			return executeWithinTransactionContext(method, args, theSession);
		} finally {
			theSession.decreaseNestLevel();
		}
	}
}
