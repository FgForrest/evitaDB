/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.configuration;

import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.NetworkUtils;
import lombok.ToString;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * This class is simple DTO object holding general options of the Evita shared for all catalogs (or better - catalog
 * agnostic).
 *
 * @param name    Name of the evitaDB instance. It's used for identification purposes only.
 * @param server  Contains server wide options.
 * @param storage This field contains all options related to underlying key-value store.
 * @param cache   Cache options contain settings crucial for Evita caching and cache invalidation.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record EvitaConfiguration(
	@Nonnull String name,
	@Nonnull ServerOptions server,
	@Nonnull StorageOptions storage,
	@Nonnull TransactionOptions transactions,
	@Nonnull CacheOptions cache
) {
	public static final String DEFAULT_SERVER_NAME = "evitaDB";

	/**
	 * Builder for the evitaDB options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static EvitaConfiguration.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the evitaDB options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static EvitaConfiguration.Builder builder(@Nonnull EvitaConfiguration configuration) {
		return new Builder(configuration);
	}

	public EvitaConfiguration(
		@Nonnull String name,
		@Nonnull ServerOptions server,
		@Nonnull StorageOptions storage,
		@Nonnull TransactionOptions transactions,
		@Nonnull CacheOptions cache
	) {
		try {
			if (DEFAULT_SERVER_NAME.equals(name)) {
				final LongHashFunction hashFct = LongHashFunction.xx3();
				// We use hash of hostname and storage directory to generate unique server name
				final Path baseDirectoryPath = storage.storageDirectory().normalize();
				final File baseDirectory = baseDirectoryPath.toFile();
				if (!baseDirectory.exists()) {
					Assert.isTrue(baseDirectory.mkdirs(), "Unable to create storage directory: " + baseDirectoryPath);
				}
				final BasicFileAttributes attrs = Files.readAttributes(baseDirectoryPath, BasicFileAttributes.class);
				final long keyServerHash = hashFct.hashLongs(
					new long[]{
						hashFct.hashChars(NetworkUtils.getLocalHostName()),
						hashFct.hashChars(baseDirectoryPath.toAbsolutePath().toString()),
						attrs.creationTime().toMillis()
					}
				);
				this.name = DEFAULT_SERVER_NAME + "-" + Long.toHexString(keyServerHash);
			} else {
				this.name = name;
			}
			ClassifierUtils.validateClassifierFormat(ClassifierType.SERVER_NAME, name);
			this.server = server;
			this.storage = storage;
			this.transactions = transactions;
			this.cache = cache;
		} catch (IOException ex) {
			throw new EvitaInternalError("Unable to access storage directory creation time!", ex);
		}
	}

	public EvitaConfiguration() {
		this(
			DEFAULT_SERVER_NAME,
			new ServerOptions(),
			new StorageOptions(),
			new TransactionOptions(),
			new CacheOptions()
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private String name = DEFAULT_SERVER_NAME;
		private ServerOptions server = new ServerOptions();
		private StorageOptions storage = new StorageOptions();
		private TransactionOptions transactions = new TransactionOptions();
		private CacheOptions cache = new CacheOptions();

		Builder() {
		}

		Builder(@Nonnull EvitaConfiguration configuration) {
			this.server = configuration.server;
			this.storage = configuration.storage;
			this.transactions = configuration.transactions;
			this.cache = configuration.cache;
		}

		@Nonnull
		public EvitaConfiguration.Builder name(@Nonnull String name) {
			this.name = name;
			return this;
		}

		@Nonnull
		public EvitaConfiguration.Builder server(@Nonnull ServerOptions server) {
			this.server = server;
			return this;
		}

		@Nonnull
		public EvitaConfiguration.Builder storage(@Nonnull StorageOptions storage) {
			this.storage = storage;
			return this;
		}

		@Nonnull
		public EvitaConfiguration.Builder transactions(@Nonnull TransactionOptions transactions) {
			this.transactions = transactions;
			return this;
		}

		@Nonnull
		public EvitaConfiguration.Builder cache(@Nonnull CacheOptions cache) {
			this.cache = cache;
			return this;
		}

		public EvitaConfiguration build() {
			return new EvitaConfiguration(
				name, server, storage, transactions, cache
			);
		}

	}

}
