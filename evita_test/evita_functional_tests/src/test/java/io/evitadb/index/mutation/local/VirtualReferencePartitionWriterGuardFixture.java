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


package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.index.VirtualReferencePartitionIndex;

import javax.annotation.Nonnull;
import java.util.function.IntPredicate;

/**
 * Counterfactual fixture of {@link VirtualReferencePartitionWriterGuardTest}: every member here writes the registry
 * of single-owner partitions from a class that must not, in one of the shapes the guard must recognise. It is never
 * executed - the guard reads its bytecode only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings("unused")
final class VirtualReferencePartitionWriterGuardFixture {

	private VirtualReferencePartitionWriterGuardFixture() {
	}

	/**
	 * Calls a registry mutator directly.
	 *
	 * @param registry the registry
	 */
	static void mutatorCallShape(@Nonnull VirtualReferencePartitionIndex registry) {
		registry.putPartitionOwner(1, 2, 3);
	}

	/**
	 * Calls the writer directly.
	 *
	 * @param registry the registry
	 */
	static void writerCallShape(@Nonnull VirtualReferencePartitionIndex registry) {
		VirtualReferencePartitionWriter.partitionEntered(
			registry, 1, 2, new RepresentativeReferenceKey(new ReferenceKey("reference", 3))
		);
	}

	/**
	 * Refers to a registry mutator by a method reference.
	 *
	 * @param registry the registry
	 * @return the predicate dropping a partition
	 */
	@Nonnull
	static IntPredicate methodReferenceShape(@Nonnull VirtualReferencePartitionIndex registry) {
		return registry::removePartition;
	}

	/**
	 * Calls a registry mutator from a lambda body, which the guard attributes to this method.
	 *
	 * @param registry the registry
	 * @return the runnable retiring the keys
	 */
	@Nonnull
	static Runnable lambdaShape(@Nonnull VirtualReferencePartitionIndex registry) {
		return () -> registry.retireAllKeysIfNoPartition();
	}

	/**
	 * Only reads the registry, which the guard must not report.
	 *
	 * @param registry the registry
	 * @return the number of entries
	 */
	static int readShape(@Nonnull VirtualReferencePartitionIndex registry) {
		return registry.getEntryCount() + (registry.getPartitionOwner(1).isPresent() ? 1 : 0);
	}
}
