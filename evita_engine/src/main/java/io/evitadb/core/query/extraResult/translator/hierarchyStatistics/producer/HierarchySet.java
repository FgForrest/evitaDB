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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * The hierarchy set envelopes set of computers that relate to the same target hierarchical entity. It allows to
 * compute the sort order in cost-effective way for all of them at once when the final {@link List<LevelInfo>} results
 * are created. See {@link #createStatistics(QueryExecutionContext, Locale)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class HierarchySet {
	/**
	 * The list contains all registered hierarchy computers along with the string key their output will be indexed.
	 */
	private final List<NamedComputer> computers = new LinkedList<>();
	/**
	 * Contains optional sorter of the computed results. If the sorter is not defined the output hierarchy is sorted
	 * by its primary key in ascending order.
	 */
	@Nullable
	private NestedContextSorter sorter;

	/**
	 * Adds all {@link LevelInfo#entity()} primary keys to the `writer` traversing them recursively so that all entities
	 * from the output tree are registered.
	 */
	private static void collect(@Nonnull List<LevelInfo> unsortedResult, @Nonnull RoaringBitmapWriter<RoaringBitmap> writer) {
		for (LevelInfo levelInfo : unsortedResult) {
			writer.add(levelInfo.entity().getPrimaryKeyOrThrowException());
			collect(levelInfo.children(), writer);
		}
	}

	/**
	 * Method sorts `result` list deeply (it means also all its {@link LevelInfo#children()} along
	 * the position of their primary key in `sortedEntities` array.
	 */
	@Nonnull
	private static List<LevelInfo> sort(@Nonnull List<LevelInfo> result, @Nonnull int[] sortedEntities) {
		final LevelInfo[] levelInfoToSort = result
			.stream()
			.map(levelInfo -> {
				if (levelInfo.children().size() > 1) {
					return new LevelInfo(levelInfo, sort(levelInfo.children(), sortedEntities));
				} else {
					return levelInfo;
				}
			})
			.toArray(LevelInfo[]::new);
		if (result.size() > 1) {
			return Arrays.asList(
				ArrayUtils.sortAlong(
					sortedEntities,
					levelInfoToSort,
					it -> it.entity().getPrimaryKeyOrThrowException()
				)
			);
		} else if (result.isEmpty()) {
			return Collections.emptyList();
		} else {
			return Collections.singletonList(
				levelInfoToSort[0]
			);
		}
	}

	/**
	 * Initializes the {@link #sorter} field.
	 */
	public void setSorter(@Nullable NestedContextSorter sorter) {
		this.sorter = sorter;
	}

	/**
	 * Registers a `computer` with specified `outputName` to collection of {@link #computers}.
	 */
	public void addComputer(@Nonnull String outputName, @Nonnull AbstractHierarchyStatisticsComputer computer) {
		this.computers.add(new NamedComputer(outputName, computer));
	}

	/**
	 * Invokes all the registered {@link #computers} and registers their result to the output result map.
	 * If the {@link #sorter} is defined, it uses it to sort all lists in the result map.
	 */
	@Nonnull
	public Map<String, List<LevelInfo>> createStatistics(@Nonnull QueryExecutionContext context, @Nullable Locale language) {
		// invoke computers and register their output using `outputName`
		final Map<String, List<LevelInfo>> unsortedResult = this.computers
			.stream()
			.collect(
				Collectors.toMap(
					NamedComputer::outputName,
					it -> it.computer().createStatistics(context, language)
				)
			);
		// if the sorter is defined, sort them
		if (this.sorter != null) {
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			// collect all entity primary keys
			unsortedResult.values().forEach(it -> collect(it, writer));
			// create sorted array using the sorter
			final RoaringBitmap bitmap = writer.get();
			// replace the output with the sorted one
			final int[] normalizedSortedResult = this.sorter.sortAndSlice(
				bitmap.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(bitmap)
			);
			for (Entry<String, List<LevelInfo>> entry : unsortedResult.entrySet()) {
				entry.setValue(sort(entry.getValue(), normalizedSortedResult));
			}
		}
		return unsortedResult;
	}

	/**
	 * Simple tuple allowing to trap computer along with the output name for {@link #computers} field.
	 */
	private record NamedComputer(
		@Nonnull String outputName,
		@Nonnull AbstractHierarchyStatisticsComputer computer
	) {
	}

}
