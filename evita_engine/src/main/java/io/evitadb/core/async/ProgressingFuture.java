/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.async;


import io.evitadb.function.BiIntConsumer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A specialized {@link CompletableFuture} that provides progress tracking capabilities for long-running operations.
 * This class extends the standard CompletableFuture to support hierarchical progress reporting through nested futures
 * and allows consumers to monitor the completion status of complex asynchronous operations.
 *
 * <p>The ProgressingFuture supports two main usage patterns:</p>
 * <ul>
 *   <li><strong>Simple execution:</strong> Execute a single supplier with progress tracking</li>
 *   <li><strong>Nested execution:</strong> Coordinate multiple nested futures and aggregate their progress</li>
 * </ul>
 *
 * <p>Progress is reported through a {@link BiIntConsumer} that receives the current number of completed steps
 * and the total number of steps. The progress consumer is called whenever progress is updated, either directly
 * through {@link #updateProgress(int)} or indirectly through nested future completion.</p>
 *
 * <p><strong>Example usage with simple execution:</strong></p>
 * <pre>{@code
 * ProgressingFuture<String, Void> future = new ProgressingFuture<>(
 *     100, // total steps
 *     (stepsDone, totalSteps) -> System.out.println("Progress: " + stepsDone + "/" + totalSteps),
 *     () -> {
 *         // Perform work and call updateProgress as needed
 *         return "Result";
 *     },
 *     executor
 * );
 * }</pre>
 *
 * <p><strong>Example usage with nested futures:</strong></p>
 * <pre>{@code
 * ProgressingFuture<List<String>, String> future = new ProgressingFuture<>(
 *     10, // additional steps for this level
 *     (stepsDone, totalSteps) -> System.out.println("Overall progress: " + stepsDone + "/" + totalSteps),
 *     nestedFutureFactories, // Collection of functions that create nested ProgressingFutures
 *     (progress, results) -> results, // Result mapper
 *     executor
 * );
 * }</pre>
 *
 * <p>The class automatically handles progress aggregation from nested futures, ensuring that the total progress
 * reflects the completion status of all nested operations. When the future completes (either successfully or
 * exceptionally), the progress is automatically set to the total number of steps.</p>
 *
 * @param <T> the type of the result produced by this future
 * @param <S> the type of results produced by nested futures (if any)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ProgressingFuture<T, S> extends CompletableFuture<T> {
	/**
	 * Empty array constant used when no nested futures are present to avoid unnecessary allocations.
	 */
	public static final ProgressingFuture<?,?>[] EMPTY_NESTED_FUTURES = new ProgressingFuture[0];

	/**
	 * The total number of steps required to complete this future and all its nested futures.
	 * This value is calculated as the sum of actionSteps and the total steps of all nested futures.
	 */
	@Getter private final int totalSteps;

	/**
	 * Total number of steps for operations performed directly by this future (excluding nested futures).
	 */
	private final int actionSteps;

	/**
	 * Optional consumer that receives progress updates. Called with (stepsDone, totalSteps) whenever
	 * progress is updated. May be null if progress tracking is not needed.
	 */
	private final @Nullable BiIntConsumer progressConsumer;

	/**
	 * Array of nested futures whose progress is aggregated into this future's total progress.
	 * Empty when using the simple constructor without nested futures.
	 */
	private final ProgressingFuture<S, ?>[] nestedFutures;

	/**
	 * Number of steps completed by this future's direct operations (not including nested futures).
	 */
	private int stepsDone;

	/**
	 * Array tracking the number of steps completed by each nested future.
	 * Parallel to the nestedFutures array.
	 */
	private final int[] nestedStepsDone;

	/**
	 * Creates a ProgressingFuture that coordinates multiple nested futures and aggregates their progress.
	 * This constructor is used for complex operations that consist of multiple sub-operations, each
	 * represented by a nested ProgressingFuture.
	 *
	 * <p>The total steps for this future will be the sum of actionSteps and the total steps of all
	 * nested futures. Progress updates from nested futures are automatically aggregated and reported
	 * through the progress consumer.</p>
	 *
	 * <p>The execution flow:</p>
	 * <ol>
	 *   <li>All nested futures are created using the provided factories</li>
	 *   <li>The futures are executed concurrently</li>
	 *   <li>When all nested futures complete, the result mapper is called</li>
	 *   <li>The final result is used to complete this future</li>
	 * </ol>
	 *
	 * @param actionSteps the number of steps for operations performed directly by this future
	 *                   (not including nested futures)
	 * @param progressConsumer optional consumer to receive progress updates in the form
	 *                        (stepsDone, totalSteps). May be null if progress tracking is not needed
	 * @param nestedFutureFactories collection of functions that create nested ProgressingFutures.
	 *                             Each function receives an IntConsumer for progress updates
	 * @param resultMapper function that combines the results from all nested futures into the final result.
	 *                    Receives this ProgressingFuture instance and a collection of nested results
	 * @param executor the executor to use for asynchronous operations
	 *
	 * @throws NullPointerException if nestedFutureFactories, resultMapper, or executor is null
	 */
	public ProgressingFuture(
		int actionSteps,
		@Nullable BiIntConsumer progressConsumer,
		@Nonnull Collection<Function<IntConsumer, ProgressingFuture<S, ?>>> nestedFutureFactories,
		@Nonnull BiFunction<ProgressingFuture<T, S>, Collection<S>, T> resultMapper,
		@Nonnull Executor executor
	) {
		//noinspection unchecked
		this.nestedFutures = new ProgressingFuture[nestedFutureFactories.size()];
		this.nestedStepsDone = new int[nestedFutureFactories.size()];
		int index = 0;
		for (Function<IntConsumer, ProgressingFuture<S, ?>> nestedFutureFactory : nestedFutureFactories) {
			final int indexToUpdate = index;
			this.nestedFutures[index++] = nestedFutureFactory.apply(stepsDone -> this.updateProgress(indexToUpdate, stepsDone));
		}
		this.actionSteps = actionSteps;
		this.totalSteps = actionSteps + 1 + Arrays.stream(this.nestedFutures).mapToInt(ProgressingFuture::getTotalSteps).sum();
		this.progressConsumer = progressConsumer;

		CompletableFuture.allOf(this.nestedFutures)
			.thenApplyAsync(
				unused -> resultMapper.apply(
					this,
					Arrays.stream(this.nestedFutures)
						.map(it -> it.getNow(null))
						.toList()
				),
				executor
			).whenComplete(
				(result, throwable) -> {
					if (throwable == null) {
						this.complete(result);
					} else {
						this.completeExceptionally(throwable);
					}
				}
			);
	}

	/**
	 * Creates a ProgressingFuture for simple execution of a single supplier with progress tracking.
	 * This constructor is used for operations that don't require nested futures but still need
	 * progress reporting capabilities.
	 *
	 * <p>The supplier is executed asynchronously using the provided executor. Progress updates
	 * must be manually reported by calling {@link #updateProgress(int)} from within the supplier
	 * or from external code that monitors the operation.</p>
	 *
	 * <p>The execution flow:</p>
	 * <ol>
	 *   <li>The supplier is executed asynchronously</li>
	 *   <li>Progress can be updated manually during execution</li>
	 *   <li>When the supplier completes, this future is completed with the result</li>
	 *   <li>Progress is automatically set to totalSteps upon completion</li>
	 * </ol>
	 *
	 * @param actionSteps the total number of steps for this operation
	 * @param progressConsumer optional consumer to receive progress updates in the form
	 *                        (stepsDone, totalSteps). May be null if progress tracking is not needed
	 * @param lambda the supplier that produces the result. Should call updateProgress as needed
	 * @param executor the executor to use for asynchronous execution
	 *
	 * @throws NullPointerException if lambda or executor is null
	 */
	public ProgressingFuture(
		int actionSteps,
		@Nullable BiIntConsumer progressConsumer,
		@Nonnull Supplier<T> lambda,
		@Nonnull Executor executor
	) {
		//noinspection unchecked
		this.nestedFutures = (ProgressingFuture<S, ?>[]) EMPTY_NESTED_FUTURES;
		this.nestedStepsDone = ArrayUtils.EMPTY_INT_ARRAY;
		this.actionSteps = actionSteps;
		this.totalSteps = actionSteps + 1;
		this.progressConsumer = progressConsumer;
		CompletableFuture.supplyAsync(lambda, executor)
			.whenComplete(
				(result, throwable) -> {
					if (throwable == null) {
						this.complete(result);
					} else {
						this.completeExceptionally(throwable);
					}
				}
			);
	}

	/**
	 * Updates the progress of this future's direct operations (not including nested futures).
	 * This method should be called to report progress during the execution of the operation.
	 *
	 * <p>The progress consumer (if present) will be notified with the total progress including
	 * both this future's progress and the aggregated progress from all nested futures.</p>
	 *
	 * @param stepsDone the number of steps completed by this future's direct operations.
	 *                 Should be between 0 and the actionSteps provided in the constructor
	 */
	public void updateProgress(int stepsDone) {
		this.stepsDone = stepsDone;
		if (this.progressConsumer != null) {
			this.progressConsumer.accept(this.stepsDone + Arrays.stream(this.nestedStepsDone).sum(), this.totalSteps);
		}
	}

	/**
	 * Updates the progress of a specific nested future. This method is called internally
	 * when nested futures report their progress.
	 *
	 * <p>The progress consumer (if present) will be notified with the total aggregated progress
	 * from this future and all nested futures.</p>
	 *
	 * @param index the index of the nested future in the nestedFutures array
	 * @param stepsDone the number of steps completed by the nested future
	 */
	private void updateProgress(int index, int stepsDone) {
		this.nestedStepsDone[index] = stepsDone;
		if (this.progressConsumer != null) {
			this.progressConsumer.accept(this.stepsDone + Arrays.stream(this.nestedStepsDone).sum(), this.totalSteps);
		}
	}

	/**
	 * Completes this future with the given value and automatically sets progress to 100%.
	 * This override ensures that when the future completes successfully, the progress
	 * is automatically updated to reflect full completion.
	 *
	 * @param value the result value to complete this future with
	 * @return true if this invocation caused this CompletableFuture to transition to a completed state
	 */
	@Override
	public boolean complete(T value) {
		updateProgress(this.actionSteps + 1);
		for (int i = 0; i < this.nestedFutures.length; i++) {
			Assert.isPremiseValid(
				this.nestedFutures[i].isDone(),
				"Nested future at index " + i + " must be completed before this future can complete."
			);
		}
		return super.complete(value);
	}

	/**
	 * Completes this future exceptionally with the given exception and automatically sets progress to 100%.
	 * This override ensures that even when the future completes with an exception, the progress
	 * is updated to reflect that the operation has finished (albeit unsuccessfully).
	 *
	 * @param ex the exception to complete this future with
	 * @return true if this invocation caused this CompletableFuture to transition to a completed state
	 */
	@Override
	public boolean completeExceptionally(Throwable ex) {
		updateProgress(this.actionSteps + 1);
		for (int i = 0; i < this.nestedFutures.length; i++) {
			final ProgressingFuture<S, ?> nestedFuture = this.nestedFutures[i];
			if (!nestedFuture.isDone()) {
				nestedFuture.cancel(true);
				this.nestedStepsDone[i] = nestedFuture.totalSteps;
			}
		}
		return super.completeExceptionally(ex);
	}
}
