/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.configuration;

import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.GenericEvitaInternalError;
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
 * @param name      Name of the evitaDB instance with automatically added hash consisting of host name, path of the data
 *                  directory and the timestamp of its creation. This hash allows to correctly distinguish instances even
 *                  if the user leaves configuration defaults and doesn't bother changing the instance name.
 *                  It's used for identification purposes only.
 * @param server    Contains server wide options.
 * @param storage   This field contains all options related to underlying key-value store.
 * @param cache     Cache options contain settings crucial for Evita caching and cache invalidation.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record EvitaConfiguration(
	@Nonnull String name,
	@Nonnull ServerOptions server,
	@Nonnull StorageOptions storage,
	@Nonnull TransactionOptions transaction,
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
		@Nonnull TransactionOptions transaction,
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
			this.transaction = transaction;
			this.cache = cache;
		} catch (IOException ex) {
			throw new GenericEvitaInternalError("Unable to access storage directory creation time!", ex);
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
	 * Name of the evitaDB instance as it was provided in the configuration file without the automatically added hash.
	 * @return Name of the evitaDB instance without appended hash.
	 */
	@Nonnull
	public String plainName() {
		return this.name.startsWith(DEFAULT_SERVER_NAME + "-") ? DEFAULT_SERVER_NAME : this.name;
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private String name = DEFAULT_SERVER_NAME;
		private ServerOptions server = ServerOptions.builder().build();
		private StorageOptions storage = StorageOptions.builder().build();
		private TransactionOptions transaction = TransactionOptions.builder().build();
		private CacheOptions cache = CacheOptions.builder().build();

		Builder() {
		}

		Builder(@Nonnull EvitaConfiguration configuration) {
			this.server = configuration.server;
			this.storage = configuration.storage;
			this.transaction = configuration.transaction;
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
		public EvitaConfiguration.Builder transaction(@Nonnull TransactionOptions transaction) {
			this.transaction = transaction;
			return this;
		}

		@Nonnull
		public EvitaConfiguration.Builder cache(@Nonnull CacheOptions cache) {
			this.cache = cache;
			return this;
		}

		public EvitaConfiguration build() {
			return new EvitaConfiguration(
				this.name, this.server, this.storage, this.transaction, this.cache
			);
		}

	}

}
