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

package io.evitadb.store.settings;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;

/**
 * Unified wrapper combining storage and transaction configuration settings for the evitaDB storage layer.
 *
 * This class aggregates {@link StorageOptions} and {@link TransactionOptions} into a single settings object
 * that can be passed throughout the storage layer. It also implements both {@link ChecksumFactory} and
 * {@link CompressionFactory} interfaces, delegating to appropriate implementations based on the configuration:
 *
 * - **Checksum Factory**: Uses {@link Crc32CChecksumFactory} when
 *   {@link StorageOptions#computeCRC32C()} is true, otherwise uses {@link ChecksumFactory#NO_OP}
 * - **Compression Factory**: Uses {@link ZipCompressionFactory} when {@link StorageOptions#compress()} is true,
 *   otherwise uses {@link CompressionFactory#NO_COMPRESSION}
 *
 * The class uses Lombok's {@link Delegate} annotation to expose all methods from the wrapped objects,
 * providing convenient access to configuration values throughout the storage layer without manual delegation.
 *
 * Special handling is provided for bootstrap files via {@link #modifyForBootstrapFile()}, which creates
 * modified settings with compression disabled and fixed buffer size to ensure fixed-size record format.
 *
 * @see StorageOptions
 * @see TransactionOptions
 * @see ChecksumFactory
 * @see CompressionFactory
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class StorageSettings implements ChecksumFactory, CompressionFactory {
	@Delegate private final StorageOptions storageOptions;
	@Delegate private final TransactionOptions transactionOptions;
	@Delegate private final ChecksumFactory checksumFactory;
	@Delegate private final CompressionFactory compressionFactory;

	/**
	 * Creates storage settings by combining storage and transaction options.
	 * Automatically selects appropriate checksum and compression factories based on configuration.
	 *
	 * @param storageOptions     the storage configuration options
	 * @param transactionOptions the transaction configuration options
	 */
	public StorageSettings(
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions
	) {
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.checksumFactory = this.storageOptions.computeCRC32C() ?
			new Crc32CChecksumFactory() : ChecksumFactory.NO_OP;
		this.compressionFactory = this.storageOptions.compress() ?
			new ZipCompressionFactory() : CompressionFactory.NO_COMPRESSION;
	}

	/**
	 * The only place with fixed record size is the bootstrap file, which means we must not allow compression for it
	 * even if it would be enabled in the main configuration. Compression would ultimately lead to variable record size
	 * and we would not be able to read the records correctly.
	 *
	 * @return the storage settings with compression disabled and with specific output buffer size for bootstrap file
	 */
	@Nonnull
	public StorageSettings modifyForBootstrapFile() {
		return new StorageSettings(
			StorageOptions.builder(this.storageOptions)
				.outputBufferSize(CatalogBootstrap.BOOTSTRAP_RECORD_SIZE)
				.computeCRC32(true)
				.compress(false)
				.build(),
			this.transactionOptions
		);
	}

}
