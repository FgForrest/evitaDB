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

package io.evitadb.core.transaction.memory;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the lifecycle of {@link WarmUpSavepoint} — the non-transactional per-entity savepoint used on the WARM_UP
 * (bulk indexing) write path. The participants are deliberately a hand-written {@link Snapshotable} stand-in rather
 * than a real diff layer: what is under test here is the savepoint's own contract (thread binding, first-touch dedup,
 * reverse replay, memento release on both outcomes), not any particular layer's snapshot implementation.
 *
 * Every stand-in appends to one shared event log, so the tests can assert not only *that* the savepoint called
 * `snapshot` / `restore` / `releaseMemento` but in which order and how often — the properties that decide whether a
 * rollback is faithful.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint (per-entity rollback without a transaction)")
// the enablement flag is a process-wide static and test classes in this module run concurrently in one JVM;
// @Isolated keeps the flag test's flip from reaching an unrelated class
@Isolated
class WarmUpSavepointTest {
	private final List<String> events = new ArrayList<>();

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("Thread binding")
	class ThreadBinding {

		@Test
		@DisplayName("An opened savepoint is the thread's current one until it is committed")
		void shouldBindAndUnbindTheOpenedSavepoint() {
			assertNull(WarmUpSavepoint.getIfOpen(), "No savepoint may be bound before one is opened.");
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			assertSame(savepoint, WarmUpSavepoint.getIfOpen());
			savepoint.commit();
			assertNull(WarmUpSavepoint.getIfOpen(), "A committed savepoint must no longer be bound.");
		}

		@Test
		@DisplayName("A rolled-back savepoint is unbound as well")
		void shouldUnbindRolledBackSavepoint() {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			savepoint.rollback();
			assertNull(WarmUpSavepoint.getIfOpen(), "A rolled-back savepoint must no longer be bound.");
		}

		@Test
		@DisplayName("Opening a second savepoint on the same thread throws")
		void shouldThrowExceptionWhenSavepointIsNested() {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			try {
				assertThrows(GenericEvitaInternalError.class, WarmUpSavepoint::open);
				assertSame(
					savepoint, WarmUpSavepoint.getIfOpen(),
					"The rejected nesting attempt must leave the original savepoint bound."
				);
			} finally {
				savepoint.commit();
			}
		}

		@Test
		@DisplayName("Closing a savepoint that is no longer the current one throws")
		void shouldThrowExceptionWhenClosingAlreadyClosedSavepoint() {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			savepoint.commit();
			assertThrows(GenericEvitaInternalError.class, savepoint::commit);
			assertThrows(GenericEvitaInternalError.class, savepoint::rollback);
		}
	}

	@Nested
	@DisplayName("Rollback")
	class Rollback {

		@Test
		@DisplayName("Every touched participant is restored to its pre-savepoint state")
		void shouldRestoreTouchedParticipantsOnRollback() {
			final RecordingLayer first = new RecordingLayer("a", 1);
			final RecordingLayer second = new RecordingLayer("b", 10);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touchAndMutate(savepoint, first, 2);
			touchAndMutate(savepoint, second, 20);
			assertEquals(2, first.value);
			assertEquals(20, second.value);

			savepoint.rollback();
			assertEquals(1, first.value, "The first participant must be back at its pre-savepoint value.");
			assertEquals(10, second.value, "The second participant must be back at its pre-savepoint value.");
		}

		@Test
		@DisplayName("An untouched participant is neither snapshotted nor restored")
		void shouldLeaveUntouchedParticipantAlone() {
			final RecordingLayer touched = new RecordingLayer("a", 1);
			final RecordingLayer untouched = new RecordingLayer("b", 10);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touchAndMutate(savepoint, touched, 2);
			// the second layer is mutated WITHOUT recording a touch - it stands for a structure outside the savepoint
			untouched.value = 20;
			savepoint.rollback();

			assertEquals(1, touched.value);
			assertEquals(20, untouched.value, "A participant never touched inside the savepoint must not be rewound.");
			assertEquals(0, untouched.snapshotCount);
			assertEquals(0, untouched.releaseCount);
		}

		@Test
		@DisplayName("The recorded inverses are replayed in strict reverse order")
		void shouldReplayInversesInReverseOrder() {
			final RecordingLayer first = new RecordingLayer("a", 1);
			final RecordingLayer second = new RecordingLayer("b", 10);
			final RecordingLayer third = new RecordingLayer("c", 100);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touchAndMutate(savepoint, first, 2);
			touchAndMutate(savepoint, second, 20);
			touchAndMutate(savepoint, third, 200);
			WarmUpSavepointTest.this.events.clear();

			savepoint.rollback();
			// the restores must unwind the touches - c, b, a - so that the earliest-captured pre-image wins last
			assertEquals(
				List.of("c:restore", "b:restore", "a:restore"),
				WarmUpSavepointTest.this.events.subList(0, 3),
				"Inverses must be replayed in strict reverse order of their recording."
			);
		}

		@Test
		@DisplayName("Each participant's memento is released after it has been restored")
		void shouldReleaseMementosAfterRestoreOnRollback() {
			final RecordingLayer layer = new RecordingLayer("a", 1);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touchAndMutate(savepoint, layer, 2);

			savepoint.rollback();
			assertEquals(1, layer.releaseCount, "The memento must be released exactly once on rollback.");
			assertEquals(
				List.of("a:snapshot", "a:restore", "a:release"), WarmUpSavepointTest.this.events,
				"A memento may only be released once the layer it belongs to has been rewound."
			);
		}

		@Test
		@DisplayName("The restored memento is the one captured on the FIRST touch")
		void shouldRestoreFirstTouchPreImage() {
			final RecordingLayer layer = new RecordingLayer("a", 1);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();

			touchAndMutate(savepoint, layer, 2);
			touchAndMutate(savepoint, layer, 3);
			touchAndMutate(savepoint, layer, 4);

			savepoint.rollback();
			assertEquals(1, layer.value, "Rollback must reach the pre-savepoint value, not an intermediate one.");
		}
	}

	@Nested
	@DisplayName("Commit")
	class Commit {

		@Test
		@DisplayName("The changes made while the savepoint was open are kept")
		void shouldKeepChangesOnCommit() {
			final RecordingLayer layer = new RecordingLayer("a", 1);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touchAndMutate(savepoint, layer, 2);

			savepoint.commit();
			assertEquals(2, layer.value, "Commit must keep the savepoint's changes.");
			assertFalse(
				WarmUpSavepointTest.this.events.contains("a:restore"),
				"Commit must never replay a recorded inverse."
			);
		}

		@Test
		@DisplayName("Each participant's memento is released")
		void shouldReleaseMementosOnCommit() {
			final RecordingLayer first = new RecordingLayer("a", 1);
			final RecordingLayer second = new RecordingLayer("b", 10);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touchAndMutate(savepoint, first, 2);
			touchAndMutate(savepoint, second, 20);

			savepoint.commit();
			assertEquals(1, first.releaseCount, "The memento must be released exactly once on commit.");
			assertEquals(1, second.releaseCount, "The memento must be released exactly once on commit.");
		}
	}

	@Nested
	@DisplayName("First-touch dedup")
	class FirstTouchDedup {

		@Test
		@DisplayName("Repeated touches of the same instance snapshot it only once")
		void shouldSnapshotEachParticipantOnlyOnce() {
			final RecordingLayer layer = new RecordingLayer("a", 1);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();

			savepoint.recordFirstTouch(layer);
			savepoint.recordFirstTouch(layer);
			savepoint.recordFirstTouch(layer);

			savepoint.commit();
			assertEquals(1, layer.snapshotCount, "Only the first touch may capture a memento.");
			assertEquals(1, layer.releaseCount, "A single captured memento must be released exactly once.");
			assertEquals(List.of("a:snapshot", "a:release"), WarmUpSavepointTest.this.events);
		}

		@Test
		@DisplayName("Dedup is by instance identity, not equality")
		void shouldDedupByIdentityRatherThanEquality() {
			// two distinct participants that compare equal must still be captured separately - sharing one entry would
			// silently leave the second one unrewindable
			final EqualByNameLayer first = new EqualByNameLayer("shared", 1);
			final EqualByNameLayer second = new EqualByNameLayer("shared", 10);
			assertEquals(first, second, "The fixture must supply two equal-but-distinct participants.");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			savepoint.recordFirstTouch(first);
			first.value = 2;
			savepoint.recordFirstTouch(second);
			second.value = 20;

			savepoint.rollback();
			assertEquals(1, first.value);
			assertEquals(10, second.value);
		}
	}

	@Nested
	@DisplayName("Enablement flag")
	class EnablementFlag {

		@Test
		@DisplayName("The flag follows its system property until something changes it")
		void shouldFollowTheSystemProperty() {
			assertEquals(
				Boolean.getBoolean(WarmUpSavepoint.ENABLED_PROPERTY), WarmUpSavepoint.isEnabled(),
				"The flag must follow its system property until something explicitly changes it."
			);
		}

		@Test
		@DisplayName("The flag can be flipped at runtime and restored")
		void shouldAllowFlippingTheFlagAtRuntime() {
			final boolean original = WarmUpSavepoint.isEnabled();
			try {
				WarmUpSavepoint.setEnabled(true);
				assertTrue(WarmUpSavepoint.isEnabled());
				WarmUpSavepoint.setEnabled(false);
				assertFalse(WarmUpSavepoint.isEnabled());
			} finally {
				WarmUpSavepoint.setEnabled(original);
			}
		}
	}

	/**
	 * Records a touch of the given participant into the savepoint and then mutates it, in the order a real mutator
	 * uses: the pre-image must be captured before the change it has to undo.
	 *
	 * @param savepoint the open savepoint bracketing the mutation
	 * @param layer     the participant being mutated
	 * @param newValue  the value to write
	 */
	private static void touchAndMutate(
		@Nonnull WarmUpSavepoint savepoint,
		@Nonnull RecordingLayer layer,
		int newValue
	) {
		savepoint.recordFirstTouch(layer);
		layer.value = newValue;
	}

	/**
	 * Minimal {@link Snapshotable} stand-in holding a single mutable `int`, which appends every lifecycle callback to
	 * the enclosing test's shared event log and counts how often it was snapshotted / released.
	 */
	private class RecordingLayer implements Snapshotable<Integer> {
		final String name;
		int value;
		int snapshotCount;
		int releaseCount;

		RecordingLayer(@Nonnull String name, int value) {
			this.name = name;
			this.value = value;
		}

		@Nonnull
		@Override
		public Integer snapshot() {
			this.snapshotCount++;
			WarmUpSavepointTest.this.events.add(this.name + ":snapshot");
			return this.value;
		}

		@Override
		public void restore(@Nonnull Integer memento) {
			WarmUpSavepointTest.this.events.add(this.name + ":restore");
			this.value = memento;
		}

		@Override
		public void releaseMemento(@Nonnull Integer memento) {
			this.releaseCount++;
			WarmUpSavepointTest.this.events.add(this.name + ":release");
		}
	}

	/**
	 * A {@link RecordingLayer} whose equality is decided by its name alone, so two distinct instances can compare
	 * equal — the fixture the identity-dedup test needs.
	 */
	private class EqualByNameLayer extends RecordingLayer {

		EqualByNameLayer(@Nonnull String name, int value) {
			super(name, value);
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof final EqualByNameLayer other && other.name.equals(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.hashCode();
		}
	}

}
