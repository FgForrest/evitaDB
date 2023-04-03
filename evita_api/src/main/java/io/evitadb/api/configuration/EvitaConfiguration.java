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

package io.evitadb.api.configuration;

import lombok.ToString;

import javax.annotation.Nonnull;

/**
 * This class is simple DTO object holding general options of the Evita shared for all catalogs (or better - catalog
 * agnostic).
 *
 * @param server  Contains server wide options.
 * @param storage This field contains all options related to underlying key-value store.
 * @param cache   Cache options contain settings crucial for Evita caching and cache invalidation.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record EvitaConfiguration(
	@Nonnull ServerOptions server,
	@Nonnull StorageOptions storage,
	@Nonnull CacheOptions cache
) {

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

	public EvitaConfiguration() {
		this(
			new ServerOptions(),
			new StorageOptions(),
			new CacheOptions()
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private ServerOptions server = new ServerOptions();
		private StorageOptions storage = new StorageOptions();
		private CacheOptions cache = new CacheOptions();

		Builder() {
		}

		Builder(@Nonnull EvitaConfiguration configuration) {
			this.server = configuration.server;
			this.storage = configuration.storage;
			this.cache = configuration.cache;
		}

		public EvitaConfiguration.Builder server(ServerOptions server) {
			this.server = server;
			return this;
		}

		public EvitaConfiguration.Builder storage(StorageOptions storage) {
			this.storage = storage;
			return this;
		}

		public EvitaConfiguration.Builder cache(CacheOptions cache) {
			this.cache = cache;
			return this;
		}

		public EvitaConfiguration build() {
			return new EvitaConfiguration(
				server, storage, cache
			);
		}

	}

}
