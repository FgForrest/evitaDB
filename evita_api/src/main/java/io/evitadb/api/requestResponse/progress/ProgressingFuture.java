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

package io.evitadb.api.requestResponse.progress;


import io.evitadb.function.BiIntConsumer;
import io.evitadb.function.Functions;
import io.evitadb.function.TriFunction;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
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
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class ProgressingFuture<T> extends CompletableFuture<T> {
	/**
	 * Empty array constant used when no nested futures are present to avoid unnecessary allocations.
	 */
	public static final ProgressingFuture<?>[] EMPTY_ARRAY = new ProgressingFuture[0];

	/**
	 * Field with internal future delegate.
	 */
	private CompletableFuture<T> futureDelegate;

	/**
	 * The total number of steps required to complete this future and all its nested futures.
	 * This value is calculated as the sum of actionSteps and the total steps of all nested futures.
	 */
	private Integer totalSteps;

	/**
	 * Total number of steps for operations performed directly by this future (excluding nested futures).
	 */
	private final int actionSteps;

	/**
	 * Lambda that executes the main operation of this future.
	 */
	private final Consumer<Executor> executionLambda;

	/**
	 * Optional consumer that receives progress updates. Called with (stepsDone, totalSteps) whenever
	 * progress is updated. May be null if progress tracking is not needed.
	 */
	private BiIntConsumer progressConsumer;

	/**
	 * Optional consumer that handles failure cases. This consumer is called when the future
	 * completes exceptionally.
	 */
	private Consumer<Throwable> onFailure;

	/**
	 * Array of nested futures whose progress is aggregated into this future's total progress.
	 * Empty when using the simple constructor without nested futures.
	 */
	private ProgressingFuture<?>[] nestedFutures;

	/**
	 * Number of steps completed by this future's direct operations (not including nested futures).
	 */
	private int stepsDone;

	/**
	 * Array tracking the number of steps completed by each nested future.
	 * Parallel to the nestedFutures array.
	 */
	private int[] nestedStepsDone;

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
	 * @param nestedFutures collection of functions that create nested ProgressingFutures.
	 *                             Each function receives an IntConsumer for progress updates
	 * @param resultMapper function that combines the results from all nested futures into the final result.
	 *                    Receives this ProgressingFuture instance and a collection of nested results
	 *
	 * @throws NullPointerException if nestedFutures, resultMapper, or executor is null
	 */
	public <S> ProgressingFuture(
		int actionSteps,
		@Nonnull Collection<ProgressingFuture<S>> nestedFutures,
		@Nonnull BiFunction<ProgressingFuture<T>, Collection<S>, T> resultMapper
	) {
		this(
			actionSteps,
			nestedFutures,
			resultMapper,
			Functions.noOpConsumer()
		);
	}

	/**
	 * Creates a ProgressingFuture that coordinates multiple nested futures and aggregates their progress.
	 * This constructor is used for complex operations that consist of multiple sub-operations, each
	 * represented by a nested ProgressingFuture.
	 *
	 * The total steps for this future will be the sum of actionSteps and the total steps of all
	 * nested futures. Progress updates from nested futures are automatically aggregated and reported
	 * through the progress consumer.
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
	 * @param initializer supplier that initializes the operation and provides initial data for nested futures
	 * @param nestedFutureFactory function that creates nested ProgressingFutures based on the initializer result
	 * @param resultMapper function that combines the results from all nested futures into the final result.
	 *                    Receives this ProgressingFuture instance, initializer result, and a collection of nested results
	 * @param onFailure consumer that handles failure cases, called when the future completes exceptionally
	 *
	 * @throws NullPointerException if initializer, nestedFutureFactory, resultMapper, or executor is null
	 */
	public <R, S> ProgressingFuture(
		int actionSteps,
		@Nonnull Supplier<R> initializer,
		@Nonnull Function<R, Collection<ProgressingFuture<S>>> nestedFutureFactory,
		@Nonnull TriFunction<ProgressingFuture<T>, R, Collection<S>, T> resultMapper,
		@Nonnull BiConsumer<R, Throwable> onFailure
	) {
		this.actionSteps = actionSteps;
		this.nestedFutures = EMPTY_ARRAY;
		this.nestedStepsDone = ArrayUtils.EMPTY_INT_ARRAY;

		this.executionLambda = executor -> {
			final R initResult;
			final Collection<ProgressingFuture<S>> nestedFutures;
			try {
				initResult = initializer.get();
				nestedFutures = nestedFutureFactory.apply(initResult);
				this.onFailure = throwable -> onFailure.accept(initResult, throwable);
			} catch (Throwable ex) {
				onFailure.accept(null, ex);
				this.completeExceptionally(ex);
				return;
			}

			this.nestedFutures = new ProgressingFuture[nestedFutures.size()];
			this.nestedStepsDone = new int[nestedFutures.size()];
			int index = 0;
			for (ProgressingFuture<S> nestedFuture : nestedFutures) {
				final int indexToUpdate = index;
				nestedFuture.setProgressConsumer((stepsDone, __) -> this.updateProgress(indexToUpdate, stepsDone));
				this.nestedFutures[index++] = nestedFuture;
			}

			// issue nested futures first
			for (ProgressingFuture<?> nestedFuture : this.nestedFutures) {
				nestedFuture.execute(executor);
			}
			//noinspection unchecked
			this.futureDelegate = CompletableFuture
				.allOf(this.nestedFutures)
				.thenApply(
					unused -> resultMapper.apply(
						this,
						initResult,
						(Collection<S>)
							Arrays.stream(this.nestedFutures)
							      .map(it -> it.getNow(null))
							      .toList()
					)
				).whenComplete(
					(result, throwable) -> {
						if (throwable == null) {
							this.complete(result);
						} else {
							this.completeExceptionally(throwable);
						}
					}
				);
		};
	}

	/**
	 * Creates a ProgressingFuture that coordinates multiple nested futures and aggregates their progress.
	 * This constructor is used for complex operations that consist of multiple sub-operations, each
	 * represented by a nested ProgressingFuture.
	 *
	 * The total steps for this future will be the sum of actionSteps and the total steps of all
	 * nested futures. Progress updates from nested futures are automatically aggregated and reported
	 * through the progress consumer.
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
	 * @param nestedFutures collection of nested ProgressingFutures.
	 * @param resultMapper function that combines the results from all nested futures into the final result.
	 *                    Receives this ProgressingFuture instance and a collection of nested results
	 *
	 * @throws NullPointerException if nestedFutures, resultMapper, or executor is null
	 */
	public <S> ProgressingFuture(
		int actionSteps,
		@Nonnull Collection<ProgressingFuture<S>> nestedFutures,
		@Nonnull BiFunction<ProgressingFuture<T>, Collection<S>, T> resultMapper,
		@Nonnull Consumer<Throwable> onFailure
	) {
		this.nestedFutures = new ProgressingFuture[nestedFutures.size()];
		this.nestedStepsDone = new int[nestedFutures.size()];
		int index = 0;
		for (ProgressingFuture<S> nestedFuture : nestedFutures) {
			final int indexToUpdate = index;
			nestedFuture.setProgressConsumer((stepsDone, __) -> this.updateProgress(indexToUpdate, stepsDone));
			this.nestedFutures[index++] = nestedFuture;
		}
		this.actionSteps = actionSteps;
		this.onFailure = onFailure;

		this.executionLambda = executor -> {
			// issue nested futures first
			for (ProgressingFuture<?> nestedFuture : this.nestedFutures) {
				nestedFuture.execute(executor);
			}
			//noinspection unchecked
			this.futureDelegate = CompletableFuture
				.allOf(this.nestedFutures)
				.thenApply(
					unused -> resultMapper.apply(
						this,
						(Collection<S>)
							Arrays.stream(this.nestedFutures)
							      .map(it -> it.getNow(null))
							      .toList()
					)
				).whenComplete(
					(result, throwable) -> {
						if (throwable == null) {
							this.complete(result);
						} else {
							this.completeExceptionally(throwable);
						}
					}
				);
		};
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
	 * @param lambda the supplier that produces the result. Should call updateProgress as needed
	 *
	 * @throws NullPointerException if lambda or executor is null
	 */
	public ProgressingFuture(
		int actionSteps,
		@Nonnull Function<ProgressingFuture<T>, T> lambda
	) {
		this(
			actionSteps,
			lambda,
			Functions.noOpConsumer()
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
	 * @param lambda the supplier that produces the result. Should call updateProgress as needed
	 *
	 * @throws NullPointerException if lambda or executor is null
	 */
	public ProgressingFuture(
		int actionSteps,
		@Nonnull Function<ProgressingFuture<T>, T> lambda,
		@Nonnull Consumer<Throwable> onFailure
	) {
		this.nestedFutures = EMPTY_ARRAY;
		this.nestedStepsDone = ArrayUtils.EMPTY_INT_ARRAY;
		this.actionSteps = actionSteps;
		this.onFailure = onFailure;
		this.executionLambda = executor -> CompletableFuture.runAsync(
			() -> {
				try {
					this.complete(lambda.apply(this));
				} catch (Throwable ex) {
					this.onFailure.accept(ex);
					this.completeExceptionally(ex);
				}
			},
			executor
		);
	}

	/**
	 * Sets the progress consumer for this ProgressingFuture. The progress consumer
	 * is used to report progress updates during the execution of the future.
	 * It will receive two integer arguments: the steps completed so far and the total steps.
	 *
	 * @param progressConsumer a {@link BiIntConsumer} that accepts two integer arguments:
	 *                         the number of steps completed and the total number of steps.
	 *                         It must not be null.
	 */
	public void setProgressConsumer(@Nonnull BiIntConsumer progressConsumer) {
		Assert.isPremiseValid(
			this.progressConsumer == null,
			"Progress consumer can only be set once for a ProgressingFuture."
		);
		this.progressConsumer = progressConsumer;
	}

	/**
	 * Retrieves the total steps calculated. This value must have been
	 * previously set, and attempting to access it before calculation
	 * will result in an IllegalStateException.
	 *
	 * @return the calculated total steps as an integer
	 * @throws IllegalStateException if the total steps have not been calculated
	 */
	public int getTotalSteps() {
		if (this.totalSteps == null) {
			this.totalSteps = this.actionSteps + 1 +
				Arrays.stream(this.nestedFutures)
				      .mapToInt(ProgressingFuture::getTotalSteps)
				      .sum();
		}
		return this.totalSteps;
	}

	/**
	 * Executes the ProgressingFuture using the provided Executor. This method requires
	 * an execution lambda to be set for the ProgressingFuture to define the operation
	 * to be performed.
	 *
	 * Preconditions:
	 * 1. The execution lambda must not be null.
	 * 2. The provided Executor must not be null.
	 *
	 * @param executor the {@link Executor} to be used for executing the operation.
	 *                 Must not be null.
	 * @throws IllegalArgumentException if the execution lambda is not set or
	 *                                  the executor is null.
	 */
	public void execute(@Nonnull Executor executor) {
		Assert.isPremiseValid(
			this.executionLambda != null,
			"Execution lambda must be set before executing the ProgressingFuture."
		);
		Assert.isPremiseValid(
			executor != null,
			"Executor must not be null."
		);
		this.executionLambda.accept(executor);
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
			final int nestedStepsSum = this.nestedStepsDone != null ? Arrays.stream(this.nestedStepsDone).sum() : 0;
			this.progressConsumer.accept(this.stepsDone + nestedStepsSum, getTotalSteps());
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
		this.nestedStepsDone[index] = Math.min(stepsDone, this.nestedFutures[index].getTotalSteps());
		if (this.progressConsumer != null) {
			this.progressConsumer.accept(this.stepsDone + Arrays.stream(this.nestedStepsDone).sum(), getTotalSteps());
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
		try {
			updateProgress(this.actionSteps + 1);
			for (int i = 0; i < this.nestedFutures.length; i++) {
				Assert.isPremiseValid(
					this.nestedFutures[i].isDone(),
					"Nested future at index " + i + " must be completed before this future can complete."
				);
			}
		} catch (Throwable ex) {
			log.error("Error updating progress for future completion", ex);
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
		try {
			updateProgress(this.actionSteps + 1);
			if (this.nestedFutures != null) {
				for (int i = 0; i < this.nestedFutures.length; i++) {
					final ProgressingFuture<?> nestedFuture = this.nestedFutures[i];
					if (!nestedFuture.isDone()) {
						nestedFuture.cancel(true);
						this.nestedStepsDone[i] = nestedFuture.getTotalSteps();
					}
				}
			}
			if (this.onFailure != null) {
				this.onFailure.accept(ex);
			}
		} catch (Throwable e) {
			log.error("Error updating progress for future completion exceptionally", e);
			// If we fail to update progress, we still want to complete exceptionally
			// with the original exception, so we don't change the exception here.
		}
		return super.completeExceptionally(ex);
	}
}
