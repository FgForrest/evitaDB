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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the class-initialisation contract between {@link FilteredPriceRecords} and its implementations.
 *
 * `FilteredPriceRecords` declares a default method, so JLS 12.4.1 makes the initialisation of **any**
 * implementation initialise the interface first. That is a harmless one-way dependency until the interface's
 * own static initialiser constructs an implementation — then the dependency becomes a cycle:
 *
 * - thread A initialises `FilteredPriceRecords`, marks it in-progress, and blocks constructing
 *   `ResolvedFilteredPriceRecords` in a field initialiser;
 * - thread B initialises `ResolvedFilteredPriceRecords`, marks it in-progress, and blocks initialising its
 *   superinterface `FilteredPriceRecords`.
 *
 * Both threads then wait forever on the JVM's class-initialisation monitors. Nothing reports it: `jstack` and
 * `jcmd` detect monitor and `AbstractQueuedSynchronizer` cycles, not class-init cycles, and both threads show
 * as `RUNNABLE` at zero CPU. It is invisible single-threaded, because a thread may re-enter a class it is
 * already initialising — which is why it survived until two query threads happened to touch the pair
 * simultaneously from cold.
 *
 * Reproducing it requires both classes to be **uninitialised**, so every attempt loads a virgin copy of the
 * whole `filteredPriceRecords` package through a throw-away child class loader that bypasses parent
 * delegation for that package only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(PRICE)
@DisplayName("FilteredPriceRecords class initialization")
class FilteredPriceRecordsClassInitializationTest {
	/**
	 * Package whose classes are re-defined by {@link SiblingPackageClassLoader} instead of being delegated to
	 * the parent loader. Covers the interface, its nested types and every implementation.
	 */
	private static final String ISOLATED_PACKAGE = "io.evitadb.core.query.algebra.price.filteredPriceRecords.";
	private static final String INTERFACE_NAME = ISOLATED_PACKAGE + "FilteredPriceRecords";
	private static final String IMPLEMENTATION_NAME = ISOLATED_PACKAGE + "ResolvedFilteredPriceRecords";
	/**
	 * Number of independent races executed by the test. The interleaving that deadlocks is narrow — thread B
	 * must mark the implementation in-progress while thread A is still inside the interface's initialiser —
	 * so a single attempt is not conclusive. Attempts are cheap (two class definitions and two short-lived
	 * threads each) and the loop only runs to completion when the fix is in place; without it the very first
	 * hung attempt fails the test.
	 */
	private static final int ATTEMPTS = 500;
	/**
	 * Positive wait: how long a single attempt may take before it is declared a deadlock. Generous on purpose —
	 * a healthy attempt releases the latch in microseconds, so the bound is only ever paid by a genuine hang.
	 */
	private static final long ATTEMPT_TIMEOUT_SECONDS = 30L;

	@Test
	@DisplayName("should not deadlock when the interface and its implementation are initialized concurrently")
	void shouldNotDeadlockWhenInterfaceAndImplementationAreInitializedConcurrently() throws Exception {
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			final SiblingPackageClassLoader classLoader = new SiblingPackageClassLoader(
				FilteredPriceRecordsClassInitializationTest.class.getClassLoader()
			);
			// load - but deliberately do NOT initialize - both classes up front, so the race below is a race
			// on class initialization alone and not on class loading
			final Class<?> interfaceClass = Class.forName(INTERFACE_NAME, false, classLoader);
			final Class<?> implementationClass = Class.forName(IMPLEMENTATION_NAME, false, classLoader);

			final CyclicBarrier startingLine = new CyclicBarrier(2);
			final CountDownLatch finished = new CountDownLatch(2);
			final AtomicReference<Throwable> failure = new AtomicReference<>();

			// thread A enters through the interface: its <clinit> is the end of the cycle that constructs
			// an implementation
			final Thread interfaceInitializer = startDaemon(
				"clinit-interface-" + attempt, startingLine, finished, failure,
				() -> Class.forName(INTERFACE_NAME, true, classLoader)
			);
			// thread B enters through the implementation: initializing it initializes the superinterface first
			final Thread implementationInitializer = startDaemon(
				"clinit-implementation-" + attempt, startingLine, finished, failure,
				() -> implementationClass.getDeclaredConstructor().newInstance()
			);

			if (!finished.await(ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				fail(
					"Class initialization deadlock between " + interfaceClass.getName() + " and " +
						implementationClass.getName() + " on attempt " + attempt + " - neither thread " +
						"completed within " + ATTEMPT_TIMEOUT_SECONDS + "s. The interface's static " +
						"initializer must not construct an implementation of itself."
				);
			}
			// both threads finished; join is instantaneous and keeps thread counts flat across attempts
			interfaceInitializer.join();
			implementationInitializer.join();
			assertNull(failure.get(), () -> "Unexpected failure on the class initialization race: " + failure.get());
		}
	}

	/**
	 * Starts a daemon thread that waits on `startingLine`, runs `action` and counts `finished` down. Daemon on
	 * purpose: when the guarded bug is present the thread never returns, and a non-daemon one would keep the
	 * surefire JVM alive after the test has already failed.
	 *
	 * @param name        thread name, so a captured thread dump names the side that hung
	 * @param startingLine rendezvous releasing both racers as close to simultaneously as the platform allows
	 * @param finished    latch counted down once the action returns (or throws)
	 * @param failure     first unexpected throwable raised by either racer
	 * @param action      the initialization trigger to run
	 * @return the started thread
	 */
	@Nonnull
	private static Thread startDaemon(
		@Nonnull String name,
		@Nonnull CyclicBarrier startingLine,
		@Nonnull CountDownLatch finished,
		@Nonnull AtomicReference<Throwable> failure,
		@Nonnull ThrowingRunnable action
	) {
		final Thread thread = new Thread(
			() -> {
				try {
					startingLine.await();
					action.run();
				} catch (BrokenBarrierException | InterruptedException e) {
					Thread.currentThread().interrupt();
					failure.compareAndSet(null, e);
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				} finally {
					finished.countDown();
				}
			},
			name
		);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/**
	 * Runnable whose body may throw the checked exceptions of reflective class loading.
	 */
	@FunctionalInterface
	private interface ThrowingRunnable {

		/**
		 * Runs the class initialization trigger.
		 *
		 * @throws Exception whatever the reflective call raises
		 */
		void run() throws Exception;

	}

	/**
	 * Class loader that defines a fresh copy of every class in {@link #ISOLATED_PACKAGE} and delegates
	 * everything else to its parent. Each instance therefore hands out a virgin, uninitialised
	 * `FilteredPriceRecords` / `ResolvedFilteredPriceRecords` pair, which is the only way to observe a
	 * class-initialization race - once the JVM has initialised a class, it can never be raced again.
	 *
	 * Registered as parallel capable so that concurrent `loadClass` calls lock per class name rather than on
	 * the loader itself; a loader-wide lock would serialise the two racers before they reach initialisation
	 * and hide the very interleaving the test is after.
	 */
	private static final class SiblingPackageClassLoader extends ClassLoader {
		static {
			registerAsParallelCapable();
		}

		SiblingPackageClassLoader(@Nonnull ClassLoader parent) {
			super("filteredPriceRecords-isolated", parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith(ISOLATED_PACKAGE)) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				final Class<?> alreadyDefined = findLoadedClass(name);
				final Class<?> result = alreadyDefined == null ? defineFromParentResource(name) : alreadyDefined;
				if (resolve) {
					resolveClass(result);
				}
				return result;
			}
		}

		/**
		 * Reads the class file through the parent loader's resource lookup and defines it in this loader.
		 *
		 * @param name binary name of the class to define
		 * @return the freshly defined class
		 * @throws ClassNotFoundException when the class file cannot be located or read
		 */
		@Nonnull
		private Class<?> defineFromParentResource(@Nonnull String name) throws ClassNotFoundException {
			final String resourceName = name.replace('.', '/') + ".class";
			try (final InputStream classFile = getParent().getResourceAsStream(resourceName)) {
				if (classFile == null) {
					throw new ClassNotFoundException(name);
				}
				final byte[] bytes = classFile.readAllBytes();
				return defineClass(name, bytes, 0, bytes.length);
			} catch (IOException e) {
				throw new ClassNotFoundException(name, e);
			}
		}

	}

}
