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

package io.evitadb.index.mutation.local.handler;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.Scope;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Withdraws the histogram values of the owner's stored references whose histogram condition reads an owner input
 * that is about to change - the parent or an associated data. Shared by {@link ParentMutationFanOut} and
 * {@link AssociatedDataMutationFanOut}: each looks the affected reference names up in the trigger registry and passes
 * them here. The withdrawal runs before the input changes in storage, so each condition is evaluated against the input
 * the index reflects; the final add of the current run evaluates it against the new one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class OwnerInputHistogramWithdrawal {

	/**
	 * Prevents instantiation of the shared withdrawal.
	 */
	private OwnerInputHistogramWithdrawal() {
		// no instances
	}

	/**
	 * Withdraws the histogram values of every existing stored reference named in `referenceNames` and schedules its
	 * final add, through `EntityIndexLocalMutationExecutor#withdrawHistogramValuesAndScheduleFinalAdd`. A dropped
	 * reference is skipped: it contributes nothing, and the withdrawal requires a reference that exists. The stored
	 * references are read only when `referenceNames` is not empty.
	 *
	 * @param executor       the active executor
	 * @param referenceNames the names of the references whose histogram conditions read the changing input
	 * @param scope          the scope of the current run, in which `referenceNames` were looked up
	 */
	static void withdraw(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull Set<String> referenceNames,
		@Nonnull Scope scope
	) {
		if (referenceNames.isEmpty()) {
			return;
		}
		for (final ReferenceContract reference : executor.getReferencesStoragePart().getReferences()) {
			if (reference.exists() && referenceNames.contains(reference.getReferenceName())) {
				executor.withdrawHistogramValuesAndScheduleFinalAdd(reference.getReferenceKey(), scope);
			}
		}
	}

}
