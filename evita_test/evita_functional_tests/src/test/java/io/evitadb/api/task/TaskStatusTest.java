/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.task;

import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TaskStatus} record verifying state resolution, progress updates, state transitions,
 * failure handling, and record equality.
 *
 * @author evitaDB
 */
@DisplayName("TaskStatus record functionality")
class TaskStatusTest {

	private static final String TASK_TYPE = "TestTask";
	private static final String TASK_NAME = "Test task";
	private static final UUID TASK_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
	private static final String SETTINGS = "settings";
	private static final OffsetDateTime CREATED = OffsetDateTime.now();

	/**
	 * Creates a base {@link TaskStatus} instance with the given timestamp and exception fields.
	 * Uses fixed values for taskType, taskName, taskId, catalogName, settings, and result.
	 *
	 * @param issued                  the issued timestamp, or null if not yet issued
	 * @param started                 the started timestamp, or null if not yet started
	 * @param finished                the finished timestamp, or null if not yet finished
	 * @param progress                the progress percentage (0-100)
	 * @param publicExceptionMessage  the public-facing exception message, or null
	 * @param exceptionWithStackTrace the full exception with stack trace, or null
	 * @param traits                  the task traits to set
	 * @return a new {@link TaskStatus} instance
	 */
	@Nonnull
	private static TaskStatus<String, String> createBaseStatus(
		@Nullable OffsetDateTime issued,
		@Nullable OffsetDateTime started,
		@Nullable OffsetDateTime finished,
		int progress,
		@Nullable String publicExceptionMessage,
		@Nullable String exceptionWithStackTrace,
		@Nonnull TaskTrait... traits
	) {
		return new TaskStatus<>(
			TASK_TYPE,
			TASK_NAME,
			TASK_ID,
			null,
			CREATED,
			issued,
			started,
			finished,
			progress,
			SETTINGS,
			null,
			publicExceptionMessage,
			exceptionWithStackTrace,
			traits.length == 0 ? EnumSet.noneOf(TaskTrait.class) : EnumSet.of(traits[0], traits)
		);
	}

	@Nested
	@DisplayName("Simplified state resolution")
	class SimplifiedStateResolutionTest {

		@Test
		@DisplayName("should return WAITING_FOR_PRECONDITION when all timestamps are null")
		void shouldReturnWaitingForPreconditionWhenAllTimestampsAreNull() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);

			assertEquals(TaskSimplifiedState.WAITING_FOR_PRECONDITION, status.simplifiedState());
		}

		@Test
		@DisplayName("should return QUEUED when only issued is set")
		void shouldReturnQueuedWhenOnlyIssuedIsSet() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), null, null, 0, null, null
			);

			assertEquals(TaskSimplifiedState.QUEUED, status.simplifiedState());
		}

		@Test
		@DisplayName("should return RUNNING when started is set")
		void shouldReturnRunningWhenStartedIsSet() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50, null, null
			);

			assertEquals(TaskSimplifiedState.RUNNING, status.simplifiedState());
		}

		@Test
		@DisplayName("should return FINISHED when finished is set without exception")
		void shouldReturnFinishedWhenFinishedIsSetWithoutException() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
				100, null, null
			);

			assertEquals(TaskSimplifiedState.FINISHED, status.simplifiedState());
		}

		@Test
		@DisplayName("should return FAILED when exceptionWithStackTrace is set")
		void shouldReturnFailedWhenExceptionWithStackTraceIsSet() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
				100, null, "java.lang.RuntimeException: error\n  at ..."
			);

			assertEquals(TaskSimplifiedState.FAILED, status.simplifiedState());
		}

		@Test
		@DisplayName("should return FAILED when publicExceptionMessage is set")
		void shouldReturnFailedWhenPublicExceptionMessageIsSet() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
				100, "Something went wrong", null
			);

			assertEquals(TaskSimplifiedState.FAILED, status.simplifiedState());
		}

		@Test
		@DisplayName("should return FAILED when both exception fields are set")
		void shouldReturnFailedWhenBothExceptionFieldsAreSet() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
				100, "Public error", "java.lang.RuntimeException: error\n  at ..."
			);

			assertEquals(TaskSimplifiedState.FAILED, status.simplifiedState());
		}

		@Test
		@DisplayName("should prioritize FAILED over FINISHED when exception is present")
		void shouldPrioritizeFailedOverFinished() {
			// even though finished timestamp is set, exception presence makes it FAILED
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
				100, "Error occurred", "java.lang.Exception: fail\n  at ..."
			);

			assertEquals(TaskSimplifiedState.FAILED, status.simplifiedState());
		}

		@Test
		@DisplayName("should return RUNNING when started but not finished")
		void shouldReturnRunningWhenStartedButNotFinished() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 75, null, null
			);

			assertEquals(TaskSimplifiedState.RUNNING, status.simplifiedState());
		}
	}

	@Nested
	@DisplayName("Progress updates")
	class ProgressUpdatesTest {

		@Test
		@DisplayName("should return same instance when progress is unchanged")
		void shouldReturnSameInstanceWhenProgressUnchanged() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 42, null, null
			);

			final TaskStatus<String, String> updated = status.updateProgress(42);

			assertSame(status, updated);
		}

		@Test
		@DisplayName("should return new instance with updated progress")
		void shouldReturnNewInstanceWithUpdatedProgress() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 10, null, null
			);

			final TaskStatus<String, String> updated = status.updateProgress(50);

			assertNotSame(status, updated);
			assertEquals(50, updated.progress());
		}

		@Test
		@DisplayName("should update progress to zero")
		void shouldUpdateProgressToZero() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 50, null, null
			);

			final TaskStatus<String, String> updated = status.updateProgress(0);

			assertEquals(0, updated.progress());
		}

		@Test
		@DisplayName("should update progress to hundred")
		void shouldUpdateProgressToHundred() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 50, null, null
			);

			final TaskStatus<String, String> updated = status.updateProgress(100);

			assertEquals(100, updated.progress());
		}

		@Test
		@DisplayName("should preserve all fields when updating progress")
		void shouldPreserveAllFieldsWhenUpdatingProgress() {
			final OffsetDateTime issued = OffsetDateTime.now();
			final OffsetDateTime started = OffsetDateTime.now();
			final TaskStatus<String, String> status = createBaseStatus(
				issued, started, null, 25, null, null, TaskTrait.CAN_BE_CANCELLED
			);

			final TaskStatus<String, String> updated = status.updateProgress(75);

			assertEquals(TASK_TYPE, updated.taskType());
			assertEquals(TASK_NAME, updated.taskName());
			assertEquals(TASK_ID, updated.taskId());
			assertNull(updated.catalogName());
			assertEquals(CREATED, updated.created());
			assertEquals(issued, updated.issued());
			assertEquals(started, updated.started());
			assertNull(updated.finished());
			assertEquals(75, updated.progress());
			assertEquals(SETTINGS, updated.settings());
			assertNull(updated.result());
			assertNull(updated.publicExceptionMessage());
			assertNull(updated.exceptionWithStackTrace());
			assertEquals(EnumSet.of(TaskTrait.CAN_BE_CANCELLED), updated.traits());
		}
	}

	@Nested
	@DisplayName("Task name and traits updates")
	class TaskNameAndTraitsUpdatesTest {

		@Test
		@DisplayName("should return new instance when name changes")
		void shouldReturnNewInstanceWhenNameChanges() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);

			final TaskStatus<String, String> updated = status.updateTaskNameAndTraits("New name");

			assertNotSame(status, updated);
			assertEquals("New name", updated.taskName());
		}

		@Test
		@DisplayName("should return new instance when traits change")
		void shouldReturnNewInstanceWhenTraitsChange() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);

			final TaskStatus<String, String> updated = status.updateTaskNameAndTraits(
				TASK_NAME, TaskTrait.CAN_BE_STARTED
			);

			assertNotSame(status, updated);
			assertEquals(EnumSet.of(TaskTrait.CAN_BE_STARTED), updated.traits());
		}

		@Test
		@DisplayName("should return same instance when name and traits are unchanged")
		void shouldReturnSameInstanceWhenNameAndTraitsAreUnchanged() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);

			final TaskStatus<String, String> updated = status.updateTaskNameAndTraits(TASK_NAME);

			assertSame(status, updated);
		}

		@Test
		@DisplayName("should set empty traits when no traits provided")
		void shouldSetEmptyTraitsWhenNoTraitsProvided() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null, TaskTrait.CAN_BE_STARTED
			);

			final TaskStatus<String, String> updated = status.updateTaskNameAndTraits("New name");

			assertTrue(updated.traits().isEmpty());
		}

		@Test
		@DisplayName("should set single trait")
		void shouldSetSingleTrait() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);

			final TaskStatus<String, String> updated = status.updateTaskNameAndTraits(
				TASK_NAME, TaskTrait.CAN_BE_CANCELLED
			);

			assertEquals(EnumSet.of(TaskTrait.CAN_BE_CANCELLED), updated.traits());
		}

		@Test
		@DisplayName("should set multiple traits")
		void shouldSetMultipleTraits() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);

			final TaskStatus<String, String> updated = status.updateTaskNameAndTraits(
				TASK_NAME, TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED
			);

			assertEquals(
				EnumSet.of(TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED),
				updated.traits()
			);
		}

		@Test
		@DisplayName("should preserve all fields except name and traits")
		void shouldPreserveAllFieldsExceptNameAndTraits() {
			final OffsetDateTime issued = OffsetDateTime.now();
			final TaskStatus<String, String> status = createBaseStatus(
				issued, null, null, 42, null, null
			);

			final TaskStatus<String, String> updated = status.updateTaskNameAndTraits(
				"Updated name", TaskTrait.NEEDS_TO_BE_STOPPED
			);

			assertEquals(TASK_TYPE, updated.taskType());
			assertEquals("Updated name", updated.taskName());
			assertEquals(TASK_ID, updated.taskId());
			assertNull(updated.catalogName());
			assertEquals(CREATED, updated.created());
			assertEquals(issued, updated.issued());
			assertNull(updated.started());
			assertNull(updated.finished());
			assertEquals(42, updated.progress());
			assertEquals(SETTINGS, updated.settings());
			assertNull(updated.result());
			assertNull(updated.publicExceptionMessage());
			assertNull(updated.exceptionWithStackTrace());
			assertEquals(EnumSet.of(TaskTrait.NEEDS_TO_BE_STOPPED), updated.traits());
		}
	}

	@Nested
	@DisplayName("Transition to issued")
	class TransitionToIssuedTest {

		@Test
		@DisplayName("should set issued timestamp")
		void shouldSetIssuedTimestamp() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);
			final OffsetDateTime beforeTransition = OffsetDateTime.now();

			final TaskStatus<String, String> issued = status.transitionToIssued();

			assertNotNull(issued.issued());
			assertFalse(issued.issued().isBefore(beforeTransition));
		}

		@Test
		@DisplayName("should reset progress to zero")
		void shouldResetProgressToZero() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 50, null, null
			);

			final TaskStatus<String, String> issued = status.transitionToIssued();

			assertEquals(0, issued.progress());
		}

		@Test
		@DisplayName("should throw when already issued")
		void shouldThrowWhenAlreadyIssued() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), null, null, 0, null, null
			);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				status::transitionToIssued
			);
			assertEquals("Task is already issued.", exception.getMessage());
		}

		@Test
		@DisplayName("should preserve other fields")
		void shouldPreserveOtherFields() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null, TaskTrait.CAN_BE_STARTED
			);

			final TaskStatus<String, String> issued = status.transitionToIssued();

			assertEquals(TASK_TYPE, issued.taskType());
			assertEquals(TASK_NAME, issued.taskName());
			assertEquals(TASK_ID, issued.taskId());
			assertNull(issued.catalogName());
			assertEquals(CREATED, issued.created());
			assertEquals(SETTINGS, issued.settings());
			assertNull(issued.result());
			assertEquals(EnumSet.of(TaskTrait.CAN_BE_STARTED), issued.traits());
		}

		@Test
		@DisplayName("should clear exception fields when transitioning to issued")
		void shouldClearExceptionFieldsWhenTransitioningToIssued() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, "error", "stack"
			);

			final TaskStatus<String, String> issued = status.transitionToIssued();

			assertNull(issued.publicExceptionMessage());
			assertNull(issued.exceptionWithStackTrace());
			assertEquals(TaskSimplifiedState.QUEUED, issued.simplifiedState());
		}
	}

	@Nested
	@DisplayName("Transition to started")
	class TransitionToStartedTest {

		@Test
		@DisplayName("should set started timestamp")
		void shouldSetStartedTimestamp() {
			final OffsetDateTime issuedTime = OffsetDateTime.now();
			final TaskStatus<String, String> status = createBaseStatus(
				issuedTime, null, null, 0, null, null
			);
			final OffsetDateTime beforeTransition = OffsetDateTime.now();

			final TaskStatus<String, String> started = status.transitionToStarted();

			assertNotNull(started.started());
			assertFalse(started.started().isBefore(beforeTransition));
		}

		@Test
		@DisplayName("should preserve issued timestamp")
		void shouldPreserveIssuedTimestamp() {
			final OffsetDateTime issuedTime = OffsetDateTime.now();
			final TaskStatus<String, String> status = createBaseStatus(
				issuedTime, null, null, 0, null, null
			);

			final TaskStatus<String, String> started = status.transitionToStarted();

			assertEquals(issuedTime, started.issued());
		}

		@Test
		@DisplayName("should allow transition without issued timestamp")
		void shouldAllowTransitionWithoutIssuedTimestamp() {
			final TaskStatus<String, String> status = createBaseStatus(
				null, null, null, 0, null, null
			);

			// transitionToStarted has no precondition check on issued
			final TaskStatus<String, String> started = status.transitionToStarted();

			assertNotNull(started.started());
			assertNull(started.issued());
		}

		@Test
		@DisplayName("should preserve all other fields")
		void shouldPreserveAllOtherFields() {
			final OffsetDateTime issuedTime = OffsetDateTime.now();
			final TaskStatus<String, String> status = createBaseStatus(
				issuedTime, null, null, 25, null, null, TaskTrait.CAN_BE_CANCELLED
			);

			final TaskStatus<String, String> started = status.transitionToStarted();

			assertEquals(TASK_TYPE, started.taskType());
			assertEquals(TASK_NAME, started.taskName());
			assertEquals(TASK_ID, started.taskId());
			assertNull(started.catalogName());
			assertEquals(CREATED, started.created());
			assertEquals(issuedTime, started.issued());
			assertNull(started.finished());
			assertEquals(0, started.progress());
			assertEquals(SETTINGS, started.settings());
			assertNull(started.result());
			assertNull(started.publicExceptionMessage());
			assertNull(started.exceptionWithStackTrace());
			assertEquals(EnumSet.of(TaskTrait.CAN_BE_CANCELLED), started.traits());
		}

		@Test
		@DisplayName("should clear exception fields when transitioning to started")
		void shouldClearExceptionFieldsWhenTransitioningToStarted() {
			final OffsetDateTime issuedTime = OffsetDateTime.now();
			final TaskStatus<String, String> status = createBaseStatus(
				issuedTime, null, null, 0, "error", "stack"
			);

			final TaskStatus<String, String> started = status.transitionToStarted();

			assertNull(started.publicExceptionMessage());
			assertNull(started.exceptionWithStackTrace());
			assertEquals(TaskSimplifiedState.RUNNING, started.simplifiedState());
		}
	}

	@Nested
	@DisplayName("Transition to finished")
	class TransitionToFinishedTest {

		@Test
		@DisplayName("should set finished timestamp and result")
		void shouldSetFinishedTimestampAndResult() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 90, null, null
			);
			final OffsetDateTime beforeTransition = OffsetDateTime.now();

			final TaskStatus<String, String> finished = status.transitionToFinished("done");

			assertNotNull(finished.finished());
			assertFalse(finished.finished().isBefore(beforeTransition));
			assertEquals("done", finished.result());
		}

		@Test
		@DisplayName("should clear exception fields")
		void shouldClearExceptionFields() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50,
				"old error", "old stack trace"
			);

			final TaskStatus<String, String> finished = status.transitionToFinished("result");

			assertNull(finished.publicExceptionMessage());
			assertNull(finished.exceptionWithStackTrace());
		}

		@Test
		@DisplayName("should accept null result")
		void shouldAcceptNullResult() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50, null, null
			);

			final TaskStatus<String, String> finished = status.transitionToFinished(null);

			assertNull(finished.result());
			assertNotNull(finished.finished());
		}

		@Test
		@DisplayName("should set progress to hundred")
		void shouldSetProgressToHundred() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 60, null, null
			);

			final TaskStatus<String, String> finished = status.transitionToFinished("result");

			assertEquals(100, finished.progress());
		}

		@Test
		@DisplayName("should preserve issued and started timestamps")
		void shouldPreserveIssuedAndStartedTimestamps() {
			final OffsetDateTime issuedTime = OffsetDateTime.now();
			final OffsetDateTime startedTime = OffsetDateTime.now();
			final TaskStatus<String, String> status = createBaseStatus(
				issuedTime, startedTime, null, 50, null, null
			);

			final TaskStatus<String, String> finished = status.transitionToFinished("result");

			assertEquals(issuedTime, finished.issued());
			assertEquals(startedTime, finished.started());
		}
	}

	@Nested
	@DisplayName("Transition to failed")
	class TransitionToFailedTest {

		@Test
		@DisplayName("should set public message from EvitaError")
		void shouldSetPublicMessageFromEvitaError() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50, null, null
			);
			final EvitaInvalidUsageException evitaError =
				new EvitaInvalidUsageException("Public error message");

			final TaskStatus<String, String> failed = status.transitionToFailed(evitaError);

			assertEquals("Public error message", failed.publicExceptionMessage());
		}

		@Test
		@DisplayName("should set cancellation message for CancellationException")
		void shouldSetCancellationMessage() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50, null, null
			);
			final CancellationException cancellation = new CancellationException("cancelled");

			final TaskStatus<String, String> failed = status.transitionToFailed(cancellation);

			assertEquals("Task was cancelled.", failed.publicExceptionMessage());
		}

		@Test
		@DisplayName("should set generic failure message for other exceptions")
		void shouldSetGenericFailureMessageForOtherExceptions() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50, null, null
			);
			final RuntimeException genericException = new RuntimeException("unexpected");

			final TaskStatus<String, String> failed = status.transitionToFailed(genericException);

			assertEquals("Task failed for unknown reasons.", failed.publicExceptionMessage());
		}

		@Test
		@DisplayName("should include exception class and message in stack trace field")
		void shouldIncludeExceptionClassAndMessageInStackTrace() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50, null, null
			);
			final IllegalStateException exception = new IllegalStateException("bad state");

			final TaskStatus<String, String> failed = status.transitionToFailed(exception);

			assertNotNull(failed.exceptionWithStackTrace());
			assertTrue(
				failed.exceptionWithStackTrace().startsWith(
					"java.lang.IllegalStateException: bad state"
				)
			);
		}

		@Test
		@DisplayName("should include full stack trace")
		void shouldIncludeFullStackTrace() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 50, null, null
			);
			final RuntimeException exception = new RuntimeException("test error");

			final TaskStatus<String, String> failed = status.transitionToFailed(exception);

			assertNotNull(failed.exceptionWithStackTrace());
			// stack trace should contain the test class name
			assertTrue(failed.exceptionWithStackTrace().contains("TaskStatusTest"));
		}

		@Test
		@DisplayName("should set progress to hundred")
		void shouldSetProgressToHundred() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 30, null, null
			);

			final TaskStatus<String, String> failed = status.transitionToFailed(
				new RuntimeException("error")
			);

			assertEquals(100, failed.progress());
		}

		@Test
		@DisplayName("should set finished timestamp")
		void shouldSetFinishedTimestamp() {
			final TaskStatus<String, String> status = createBaseStatus(
				OffsetDateTime.now(), OffsetDateTime.now(), null, 30, null, null
			);
			final OffsetDateTime beforeTransition = OffsetDateTime.now();

			final TaskStatus<String, String> failed = status.transitionToFailed(
				new RuntimeException("error")
			);

			assertNotNull(failed.finished());
			assertFalse(failed.finished().isBefore(beforeTransition));
		}

		@Test
		@DisplayName("should set result to null")
		void shouldSetResultToNull() {
			final TaskStatus<String, String> status = new TaskStatus<>(
				TASK_TYPE, TASK_NAME, TASK_ID, null, CREATED,
				OffsetDateTime.now(), OffsetDateTime.now(), null,
				50, SETTINGS, "previous result", null, null,
				EnumSet.noneOf(TaskTrait.class)
			);

			final TaskStatus<String, String> failed = status.transitionToFailed(
				new RuntimeException("error")
			);

			assertNull(failed.result());
		}
	}

	@Nested
	@DisplayName("Record equality and accessors")
	class RecordEqualityAndAccessorsTest {

		@Test
		@DisplayName("should be equal when all fields match")
		void shouldBeEqualWhenAllFieldsMatch() {
			final OffsetDateTime now = OffsetDateTime.now();
			final TaskStatus<String, String> status1 = new TaskStatus<>(
				TASK_TYPE, TASK_NAME, TASK_ID, "catalog", CREATED,
				now, now, now, 100, SETTINGS, "result", null, null,
				EnumSet.of(TaskTrait.CAN_BE_STARTED)
			);
			final TaskStatus<String, String> status2 = new TaskStatus<>(
				TASK_TYPE, TASK_NAME, TASK_ID, "catalog", CREATED,
				now, now, now, 100, SETTINGS, "result", null, null,
				EnumSet.of(TaskTrait.CAN_BE_STARTED)
			);

			assertEquals(status1, status2);
		}

		@Test
		@DisplayName("should not be equal when progress differs")
		void shouldNotBeEqualWhenProgressDiffers() {
			final TaskStatus<String, String> status1 = createBaseStatus(
				null, null, null, 10, null, null
			);
			final TaskStatus<String, String> status2 = createBaseStatus(
				null, null, null, 20, null, null
			);

			assertNotEquals(status1, status2);
		}

		@Test
		@DisplayName("should have consistent hash code")
		void shouldHaveConsistentHashCode() {
			final OffsetDateTime now = OffsetDateTime.now();
			final TaskStatus<String, String> status1 = new TaskStatus<>(
				TASK_TYPE, TASK_NAME, TASK_ID, null, CREATED,
				now, now, now, 100, SETTINGS, "result", null, null,
				EnumSet.noneOf(TaskTrait.class)
			);
			final TaskStatus<String, String> status2 = new TaskStatus<>(
				TASK_TYPE, TASK_NAME, TASK_ID, null, CREATED,
				now, now, now, 100, SETTINGS, "result", null, null,
				EnumSet.noneOf(TaskTrait.class)
			);

			assertEquals(status1.hashCode(), status2.hashCode());
		}

		@Test
		@DisplayName("should expose all fields via accessors")
		void shouldExposeAllFieldsViaAccessors() {
			final OffsetDateTime issued = OffsetDateTime.now();
			final OffsetDateTime started = OffsetDateTime.now();
			final OffsetDateTime finished = OffsetDateTime.now();
			final TaskStatus<String, String> status = new TaskStatus<>(
				TASK_TYPE, TASK_NAME, TASK_ID, "myCatalog", CREATED,
				issued, started, finished, 75, SETTINGS, "myResult",
				"public error", "stack trace",
				EnumSet.of(TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED)
			);

			assertEquals(TASK_TYPE, status.taskType());
			assertEquals(TASK_NAME, status.taskName());
			assertEquals(TASK_ID, status.taskId());
			assertEquals("myCatalog", status.catalogName());
			assertEquals(CREATED, status.created());
			assertEquals(issued, status.issued());
			assertEquals(started, status.started());
			assertEquals(finished, status.finished());
			assertEquals(75, status.progress());
			assertEquals(SETTINGS, status.settings());
			assertEquals("myResult", status.result());
			assertEquals("public error", status.publicExceptionMessage());
			assertEquals("stack trace", status.exceptionWithStackTrace());
			assertEquals(
				EnumSet.of(TaskTrait.CAN_BE_CANCELLED, TaskTrait.NEEDS_TO_BE_STOPPED),
				status.traits()
			);
		}
	}

	@Nested
	@DisplayName("TaskSimplifiedState enum")
	class TaskSimplifiedStateEnumTest {

		@Test
		@DisplayName("should contain all expected states")
		void shouldContainAllExpectedStates() {
			final TaskSimplifiedState[] values = TaskSimplifiedState.values();

			assertEquals(5, values.length);
			assertNotNull(TaskSimplifiedState.WAITING_FOR_PRECONDITION);
			assertNotNull(TaskSimplifiedState.QUEUED);
			assertNotNull(TaskSimplifiedState.RUNNING);
			assertNotNull(TaskSimplifiedState.FINISHED);
			assertNotNull(TaskSimplifiedState.FAILED);
		}

		@Test
		@DisplayName("should resolve from name")
		void shouldResolveFromName() {
			assertEquals(
				TaskSimplifiedState.WAITING_FOR_PRECONDITION,
				TaskSimplifiedState.valueOf("WAITING_FOR_PRECONDITION")
			);
			assertEquals(TaskSimplifiedState.QUEUED, TaskSimplifiedState.valueOf("QUEUED"));
			assertEquals(TaskSimplifiedState.RUNNING, TaskSimplifiedState.valueOf("RUNNING"));
			assertEquals(TaskSimplifiedState.FINISHED, TaskSimplifiedState.valueOf("FINISHED"));
			assertEquals(TaskSimplifiedState.FAILED, TaskSimplifiedState.valueOf("FAILED"));
		}
	}

	@Nested
	@DisplayName("TaskTrait enum")
	class TaskTraitEnumTest {

		@Test
		@DisplayName("should contain all expected traits")
		void shouldContainAllExpectedTraits() {
			final TaskTrait[] values = TaskTrait.values();

			assertEquals(3, values.length);
			assertNotNull(TaskTrait.CAN_BE_STARTED);
			assertNotNull(TaskTrait.CAN_BE_CANCELLED);
			assertNotNull(TaskTrait.NEEDS_TO_BE_STOPPED);
		}

		@Test
		@DisplayName("should resolve from name")
		void shouldResolveFromName() {
			assertEquals(TaskTrait.CAN_BE_STARTED, TaskTrait.valueOf("CAN_BE_STARTED"));
			assertEquals(TaskTrait.CAN_BE_CANCELLED, TaskTrait.valueOf("CAN_BE_CANCELLED"));
			assertEquals(
				TaskTrait.NEEDS_TO_BE_STOPPED,
				TaskTrait.valueOf("NEEDS_TO_BE_STOPPED")
			);
		}
	}
}
