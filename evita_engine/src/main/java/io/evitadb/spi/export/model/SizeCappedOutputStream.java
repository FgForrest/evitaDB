/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.spi.export.model;

import io.evitadb.api.exception.ExportFileTooLargeException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Counts archive bytes between the buffer and the export handle, refusing a whole chunk that would exceed its cap.
 * An export is a file the engine produces for a user rather than catalog storage, which is why this stream lives here.
 * After refusal it swallows writes and flushes. Closing an export handle publishes it, on S3 by starting the upload.
 * Only a body that called {@link #markFinished()} without a remembered refusal may close the delegate. An unfinished
 * body leaves the handle open and unpublished for the failure path's discard.
 *
 * Stream operations throw the refusal only once: throwing the same instance during try-with-resources cleanup
 * would cause `Self-suppression not permitted`. The remembered failure, rather than a caught exception, is the
 * authority: `ObservableOutput#flush` can replace it with a `KryoException` without retaining its cause. Tasks
 * therefore check again after closing the zip and prefer the remembered refusal during cleanup, except when the
 * cancellation is what unwound them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public final class SizeCappedOutputStream extends OutputStream {
	/** Backend sink, left open for discard if the body is unfinished or the allowance is exceeded. */
	private final OutputStream delegate;
	/** Payload allowance in bytes, after any backend metadata reserve. */
	private final long cap;
	/** Builds diagnostic details only for the first crossing write. */
	private final Supplier<ExportFileTooLargeException> failureSupplier;
	/** Accepted bytes, subtracted from the allowance to check crossings without overflow. */
	private long written;
	/** Whether the archive body completed, allowing close to publish an admitted export. */
	private boolean finished;
	/**
	 * Original refusal retained for task cleanup after subsequent stream operations stop throwing. Volatile because a
	 * traffic recording writes from the ring buffer's thread and its task reads the verdict from its own.
	 */
	@Nullable private volatile ExportFileTooLargeException failure;

	/** Creates a cap without measuring storage until a write actually crosses it. */
	SizeCappedOutputStream(
		@Nonnull OutputStream delegate, long cap, @Nonnull Supplier<ExportFileTooLargeException> failureSupplier
	) {
		this.delegate = delegate;
		this.cap = cap;
		this.failureSupplier = failureSupplier;
	}

	/** Creates an export cap, building the refusal only when a write crosses the handle's allowance. */
	@Nonnull
	public static SizeCappedOutputStream of(
		@Nonnull ExportFileHandle handle,
		@Nonnull Function<ExportFileSizeLimit, ExportFileTooLargeException> refusal
	) {
		final ExportFileSizeLimit limit = handle.sizeLimit();
		return new SizeCappedOutputStream(
			handle.outputStream(), limit.maxFileSizeInBytes(), () -> refusal.apply(limit)
		);
	}

	/** Marks the archive body complete, allowing close to publish only if no refusal is remembered. */
	public void markFinished() {
		this.finished = true;
	}

	/**
	 * Rethrows the remembered refusal, if any, so that swallowing the first one can never turn the archive into a
	 * successful export.
	 */
	public void checkFailure() {
		if (this.failure != null) {
			throw this.failure;
		}
	}

	/**
	 * Restores the refusal after cleanup and retains the exception that replaced it, unless that exception is the
	 * cancellation unwinding the task: replacing it would turn a cancelled task into a failed one. A cancel that merely
	 * arrived after the cap tripped does not count, because the export did fail for this reason.
	 *
	 * @param caught             the exception the task's failure path caught
	 * @param cancellationDriven whether `caught` is, or wraps, the cancellation itself
	 */
	public void rethrowFailure(@Nonnull Exception caught, boolean cancellationDriven) {
		if (this.failure != null && !cancellationDriven) {
			if (caught != this.failure) {
				this.failure.addSuppressed(caught);
			}
			throw this.failure;
		}
	}

	@Override
	public void write(int value) throws IOException {
		if (accept(1)) {
			this.delegate.write(value);
		}
	}

	@Override
	public void write(@Nonnull byte[] bytes) throws IOException {
		write(bytes, 0, bytes.length);
	}

	@Override
	public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
		Objects.checkFromIndexSize(offset, length, bytes.length);
		if (accept(length)) {
			this.delegate.write(bytes, offset, length);
		}
	}

	@Override
	public void flush() throws IOException {
		if (this.failure == null) {
			this.delegate.flush();
		}
	}

	@Override
	public void close() throws IOException {
		if (this.finished && this.failure == null) {
			this.delegate.close();
		}
	}

	/** Checks by subtraction to avoid overflow, and counts bytes without consulting the backend. */
	private boolean accept(int length) {
		if (this.failure != null) {
			return false;
		}
		if (length > this.cap - this.written) {
			this.failure = this.failureSupplier.get();
			throw this.failure;
		}
		this.written += length;
		return true;
	}
}
