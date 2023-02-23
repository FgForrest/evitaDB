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

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

import static java.util.Optional.ofNullable;

/**
 * This instance keeps references to the {@link ObservableOutput} instances that internally keep large buffers in
 * {@link ObservableOutput#getBuffer()} to use them for serialization. These buffers are not necessary when there are
 * no updates to the catalog / collection, so it's wise to get rid of them if there is no actual need.
 *
 * The need is determined by the number of opened read write {@link io.evitadb.api.EvitaSessionContract} to the catalog. If there
 * is at least one opened read-write session we need to keep those outputs around. When there are only read sessions we
 * don't need the outputs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ObservableOutputKeeper {
	private final StorageOptions options;
	private final ReentrantLock mutex = new ReentrantLock();
	private ConcurrentHashMap<Path, ObservableOutput<FileOutputStream>> cachedOutputs;

	public ObservableOutputKeeper(StorageOptions options) {
		this.options = options;
	}

	/**
	 * Method allowing to access {@link StorageOptions} internal settings so that we can pass only {@link ObservableOutputKeeper}
	 * in the {@link io.evitadb.store.memTable.WriteOnlyFileHandle} class.
	 */
	public long getLockTimeoutSeconds() {
		return options.lockTimeoutSeconds();
	}

	/**
	 * Returns existing or creates new {@link ObservableOutput} and cache it for the passed target file.
	 */
	@Nonnull
	public ObservableOutput<FileOutputStream> getObservableOutputOrCreate(@Nonnull Path targetFile, @Nonnull BiFunction<Path, StorageOptions, ObservableOutput<FileOutputStream>> createFct) {
		return cachedOutputs.computeIfAbsent(targetFile, path -> {
			try {
				mutex.lock();
				Assert.notNull(this.cachedOutputs, "ObservableOutputKeeper is not prepared!");
				return createFct.apply(path, options);
			} finally {
				mutex.unlock();
			}
		});
	}

	/**
	 * Method drops {@link ObservableOutput} for the target file (closes stream and releases reference).
	 */
	public void free(@Nonnull Path targetFile) {
		try {
			mutex.lock();
			Assert.isTrue(this.cachedOutputs != null, "ObservableOutputKeeper is not prepared!");
			final ObservableOutput<FileOutputStream> output = this.cachedOutputs.remove(targetFile);
			ofNullable(output).ifPresent(Output::close);
		} finally {
			mutex.unlock();
		}
	}

	/**
	 * Method prepares holder for the {@link ObservableOutput}. This method needs to be called before calling any of
	 * {@link #getObservableOutputOrCreate(Path, BiFunction)} and {@link #free()} methods.
	 */
	public void prepare() {
		try {
			mutex.lock();
			Assert.isTrue(this.cachedOutputs == null, "Cached outputs are already initialized!");
			this.cachedOutputs = new ConcurrentHashMap<>();
		} finally {
			mutex.unlock();
		}
	}

	/**
	 * Returns true if the cached outputs are already prepared.
	 */
	public boolean isPrepared() {
		return this.cachedOutputs != null;
	}

	/**
	 * Method drops all cached {@link ObservableOutput} (closes streams and releases references).
	 */
	public void free() {
		try {
			mutex.lock();
			Assert.isTrue(this.cachedOutputs != null, "Cached output are not initialized!");
			final ConcurrentHashMap<Path, ObservableOutput<FileOutputStream>> toFree = this.cachedOutputs;
			this.cachedOutputs = null;
			for (ObservableOutput<FileOutputStream> observableOutput : toFree.values()) {
				observableOutput.close();
			}
		} finally {
			mutex.unlock();
		}
	}
}
