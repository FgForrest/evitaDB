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

package io.evitadb.store.engine;


import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.EnginePersistenceServiceFactory;

import javax.annotation.Nonnull;

/**
 * This implementation is the single and only implementation of {@link EnginePersistenceServiceFactory}. Instance is
 * created and located by {@link java.util.ServiceLoader} pattern.
 *
 * @see EnginePersistenceServiceFactory
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class DefaultEnginePersistenceServiceFactory implements EnginePersistenceServiceFactory {

	@Nonnull
	@Override
	public EnginePersistenceService create(
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		return new DefaultEnginePersistenceService(storageOptions, transactionOptions, scheduler);
	}

}
