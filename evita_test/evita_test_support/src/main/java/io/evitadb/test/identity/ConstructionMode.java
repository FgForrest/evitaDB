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

package io.evitadb.test.identity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Real constructor inputs and applicable operations for one holder construction mode.
 * Suppliers must construct fresh holders while retaining the same opaque input objects across calls.
 *
 * @param base fresh holders over equal value inputs, absent for an always opaque mode
 * @param variants holders with changed value inputs
 * @param opaqueVariants holders with opaque inputs
 * @param oversizedVariants holders with exact inputs exceeding the admission limit
 * @param clonePaths applicable clone operations
 * @param pinned components fixed by this construction mode
 * @param alwaysExact token, list or key components that cannot become query-local in this mode
 * @param <T> holder type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ConstructionMode<T>(
	@Nullable Supplier<T> base,
	@Nonnull Map<String, Supplier<T>> variants,
	@Nonnull Map<String, Supplier<T>> opaqueVariants,
	@Nonnull Map<String, Supplier<T>> oversizedVariants,
	@Nonnull Map<String, ClonePath<T>> clonePaths,
	@Nonnull Set<String> pinned,
	@Nonnull Set<String> alwaysExact
) {
	/** Copies all fixture declarations so checks cannot mutate them. */
	public ConstructionMode {
		variants = Map.copyOf(variants);
		opaqueVariants = Map.copyOf(opaqueVariants);
		oversizedVariants = Map.copyOf(oversizedVariants);
		clonePaths = Map.copyOf(clonePaths);
		pinned = Set.copyOf(pinned);
		alwaysExact = Set.copyOf(alwaysExact);
	}
}
