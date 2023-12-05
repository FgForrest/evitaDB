/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.fileOffsetIndex;

import io.evitadb.store.exception.StorageException;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Write handle protects access to the {@link #exclusiveWriteAccess} by {@link ReentrantLock} allowing only single
 * client to use the resource in parallel. Waiting may timeout after {@link #lockTimeoutSeconds}. Also after getting
 * the lock {@link #operationalCheck} is executed to verify whether the parent is still in operating mode.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class WriteOnlyFileHandle {
	private final long lockTimeoutSeconds;
	private final Runnable operationalCheck;
	private final ReentrantLock handleLock = new ReentrantLock();
	private final ExclusiveWriteAccess exclusiveWriteAccess;

	public WriteOnlyFileHandle(Path targetFile, Runnable operationalCheck, ObservableOutputKeeper observableOutputKeeper) {
		this.lockTimeoutSeconds = observableOutputKeeper.getLockTimeoutSeconds();
		this.operationalCheck = operationalCheck;
		this.exclusiveWriteAccess = new ExclusiveWriteAccess(targetFile, observableOutputKeeper);
	}

	/**
	 * Protects access to the `supplier` by using handle lock.
	 * @param operation
	 * @param logic
	 * @param <T>
	 * @return
	 */
	public <T> T execute(String operation, Function<ExclusiveWriteAccess, T> logic) {
		try {
			if (handleLock.tryLock(lockTimeoutSeconds, TimeUnit.SECONDS)) {
				try {
					operationalCheck.run();
					return logic.apply(this.exclusiveWriteAccess);
				} finally {
					handleLock.unlock();
				}
			}
			throw new StorageException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageException(operation + " due to interrupt!");
		}
	}

	/**
	 * Protects access to the `supplier` by using handle lock. Operational check is not used in this method.
	 * @param operation
	 * @param logic
	 * @param <T>
	 * @return
	 */
	public <T> T executeIgnoringOperationalCheck(String operation, Function<ExclusiveWriteAccess, T> logic) {
		try {
			if (handleLock.tryLock(lockTimeoutSeconds, TimeUnit.SECONDS)) {
				try {
					return logic.apply(exclusiveWriteAccess);
				} finally {
					handleLock.unlock();
				}
			}
			throw new StorageException(operation + " within timeout!");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageException(operation + " due to interrupt!");
		}
	}

	@RequiredArgsConstructor
	public static class ExclusiveWriteAccess {
		private final Path targetFile;
		private final ObservableOutputKeeper observableOutputKeeper;

		public File getTargetFile() {
			return targetFile.toFile();
		}

		public ObservableOutput<FileOutputStream> getWriteOnlyStream() {
			return observableOutputKeeper.getObservableOutputOrCreate(targetFile, (theFilePath, options) -> {
				try {
					final File theFile = theFilePath.toFile();
					final FileOutputStream targetOs = new FileOutputStream(theFile, true);
					final ObservableOutput<FileOutputStream> writeFile = new ObservableOutput<>(
						targetOs,
						options.outputBufferSize(),
						theFile.length()
					);
					if (options.computeCRC32C()) {
						writeFile.computeCRC32();
					}
					return writeFile;
				} catch (FileNotFoundException ex) {
					throw new StorageException("Target file " + targetFile + " cannot be opened!", ex);
				}
			});
		}

		public void close() {
			if (observableOutputKeeper.isPrepared()) {
				observableOutputKeeper.free(targetFile);
			}
		}

	}

}
