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

package io.evitadb.api.functional.schema;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.core.Evita;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Asserts that a refused catalog-lifecycle mutation raised the unpublishable barrier, observed as the catalog
 * settling into a given {@link CatalogState} on the system change stream.
 *
 * **This is what keeps a refusal test from passing for the wrong reason.** A guarantee built on the barrier rests
 * on catalog-schema validation having already exchanged the invalid schema into the running catalog before it
 * refuses - see {@code Catalog#markUnpublishableDueToInvalidSchema}. A refusal from a *pre-flight* check instead
 * throws the same exception type with a similar message and leaves the catalog untouched, which would make every
 * assertion about a reopened schema hold trivially, because nothing was ever exchanged. Deactivation is the
 * barrier's observable consequence ({@code Catalog#recordUnpublishableCause} schedules it), so waiting for it is
 * what tells the two refusals apart.
 *
 * Shared by {@link WarmUpRefusedSchemaPersistenceTest} and {@link AttributeFilterAcceleratorRefusalTest}, which
 * both pin refusals of {@code SetAttributeSchemaAcceleratedMutation} - a mutation that deliberately skips its own
 * pre-flight applicability check today, one edit away from gaining one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CatalogUnpublishableBarrierAssertions {

	/**
	 * Upper bound on how long the deactivation a refusal schedules is waited for. Generous on purpose - a latch
	 * returns the instant the work completes, so the bound is only ever paid by a genuine hang, and a loaded
	 * machine can only push a positive wait toward expiry (`.claude/rules/testing.md`).
	 */
	private static final long AWAIT_BUDGET_MILLIS = 30_000L;

	private CatalogUnpublishableBarrierAssertions() {
		throw new UnsupportedOperationException("This is a static helper class, no instances are allowed.");
	}

	/**
	 * Runs an action expected to be refused, and asserts that the refusal raised the unpublishable barrier.
	 *
	 * @param evita             the running instance the catalog under test lives in; must not be null
	 * @param catalogName       name of the catalog the refusal is expected to deactivate; must not be null
	 * @param expectedException type the refusal is expected to throw; must not be null
	 * @param refusedAction     the action whose refusal is under test; must not be null
	 * @return the exception the action was refused with; never null
	 */
	@Nonnull
	static <T extends RuntimeException> T assertRefusalRaisesTheBarrier(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull Class<T> expectedException,
		@Nonnull Executable refusedAction
	) {
		// registered BEFORE the action, so the deactivation it schedules cannot settle into a stream nobody is
		// subscribed to
		final CatalogState inactive = CatalogState.INACTIVE;
		try (final SettledStateLatch deactivation = subscribeToSettledState(evita, catalogName, inactive)) {
			final T exception = assertThrows(expectedException, refusedAction);
			deactivation.await();
			return exception;
		}
	}

	/**
	 * Subscribes to the system change stream and returns a latch that opens when the engine reports the named
	 * catalog as having settled into the given state.
	 *
	 * The deactivation a refusal schedules is asynchronous by design - it runs on the engine's scheduler because it
	 * has to close the very sessions the refusing thread is running under - so a test that asks what happened next
	 * has to wait for it. This is the transition announcing itself rather than the test guessing when to look:
	 * {@code Evita#notifyCatalogStateSettled} emits a {@link HostSystemEvent.CatalogInstalledIntoLiveView} from the
	 * deactivation operator's own completion phase. **Register before triggering the deactivation**, or the event
	 * fires into a stream nobody is subscribed to.
	 *
	 * @param evita         the running instance the catalog under test lives in; must not be null
	 * @param catalogName   name of the catalog to watch; must not be null
	 * @param expectedState the settled state to wait for; must not be null
	 * @return the subscription, which closes the underlying publisher and carries the latch; never null
	 */
	@Nonnull
	static SettledStateLatch subscribeToSettledState(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull CatalogState expectedState
	) {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = evita.registerSystemChangeCapture(
			ChangeSystemCaptureRequest.builder()
				.sinceVersion(evita.getEngineState().version() + 1)
				.content(ChangeCaptureContent.BODY)
				.hostArea()
				.build()
		);
		final SettledStateLatch latch = new SettledStateLatch(publisher, catalogName, expectedState);
		publisher.subscribe(latch);
		return latch;
	}

	/**
	 * A {@link Flow.Subscriber} over the system change stream that opens a latch when the named catalog is reported
	 * as having settled into one particular state.
	 */
	static final class SettledStateLatch implements Flow.Subscriber<ChangeSystemCapture>, AutoCloseable {
		private final ChangeCapturePublisher<ChangeSystemCapture> publisher;
		private final String catalogName;
		private final CatalogState expectedState;
		private final CountDownLatch latch = new CountDownLatch(1);

		SettledStateLatch(
			@Nonnull ChangeCapturePublisher<ChangeSystemCapture> publisher,
			@Nonnull String catalogName,
			@Nonnull CatalogState expectedState
		) {
			this.publisher = publisher;
			this.catalogName = catalogName;
			this.expectedState = expectedState;
		}

		@Override
		public void onSubscribe(@Nonnull Flow.Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(@Nonnull ChangeSystemCapture item) {
			if (item.body() instanceof HostSystemEvent.CatalogInstalledIntoLiveView installed
				&& this.catalogName.equals(installed.catalogName())
				&& installed.observedState() == this.expectedState) {
				this.latch.countDown();
			}
		}

		@Override
		public void onError(@Nonnull Throwable throwable) {
			// nothing to do - a stream that fails leaves the latch closed and `await` reports the timeout, which
			// carries the same verdict with a message that names the state the test was waiting for
		}

		@Override
		public void onComplete() {
			// see `onError` - a stream that ends without the event is indistinguishable from one that never
			// delivered it, and both are the same test failure
		}

		/**
		 * Blocks until the expected state is reported, failing the test when it is not reported in time.
		 */
		void await() {
			try {
				if (!this.latch.await(AWAIT_BUDGET_MILLIS, TimeUnit.MILLISECONDS)) {
					fail(
						"Catalog `" + this.catalogName + "` was never reported as having settled into `" +
							this.expectedState + "`."
					);
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				fail("Interrupted while waiting for catalog `" + this.catalogName + "` to settle.");
			}
		}

		@Override
		public void close() {
			this.publisher.close();
		}
	}

}
