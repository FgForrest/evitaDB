/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.spi;

import io.evitadb.api.requestResponse.mutation.Mutation;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.UUID;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface IsolatedWalPersistenceService extends Closeable {

	/**
	 * TODO JNO - document me
	 */
	@Nonnull
	UUID getTransactionId();

	/**
	 * TODO JNO - document me
	 */
	int getMutationCount();

	/**
	 * TODO JNO - document me
	 */
	long getMutationSizeInBytes();

	/**
	 * TODO JNO - document me
	 * @param catalogVersion we expect the catalog version might conflict - because we're writing WAL before
	 *                       transaction commit but at least it will somehow describe the version of the catalog
	 *                       the transaction is based on
	 * @param mutation
	 */
	void write(long catalogVersion, @Nonnull Mutation mutation);

	/**
	 * TODO JNO - document me
	*/
	@Nonnull
	OffHeapWithFileBackupReference getWalReference();

	@Override
	void close();


}
