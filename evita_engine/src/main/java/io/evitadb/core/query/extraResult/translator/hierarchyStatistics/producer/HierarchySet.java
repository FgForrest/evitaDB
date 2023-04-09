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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * The hierarchy set envelopes set of computers that relate to the same target hierarchical entity. It allows to
 * compute the sort order in cost-effective way for all of them at once when the final {@link List<LevelInfo>} results
 * are created. See {@link #createStatistics(Formula, Formula, Locale)} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class HierarchySet {
	/**
	 * Reference to the query context that allows to access entity bodies.
	 */
	private final QueryContext queryContext;
	/**
	 * The list contains all registered hierarchy computers along with the string key their output will be indexed.
	 */
	private final List<NamedComputer> computers = new LinkedList<>();
	/**
	 * Contains optional sorter of the computed results. If the sorter is not defined the output hierarchy is sorted
	 * by its primary key in ascending order.
	 */
	@Nullable
	private Sorter sorter;

	/**
	 * Adds all {@link LevelInfo#entity()} primary keys to the `writer` traversing them recursively so that all entities
	 * from the output tree are registered.
	 */
	private static void collect(@Nonnull List<LevelInfo> unsortedResult, @Nonnull RoaringBitmapWriter<RoaringBitmap> writer) {
		for (LevelInfo levelInfo : unsortedResult) {
			writer.add(levelInfo.entity().getPrimaryKey());
			collect(levelInfo.childrenStatistics(), writer);
		}
	}

	/**
	 * Method sorts `result` list deeply (it means also all its {@link LevelInfo#childrenStatistics()} along
	 * the position of their primary key in `sortedEntities` array.
	 */
	@Nonnull
	private static List<LevelInfo> sort(@Nonnull List<LevelInfo> result, @Nonnull int[] sortedEntities) {
		final LevelInfo[] levelInfoToSort = result
			.stream()
			.map(levelInfo -> {
				if (levelInfo.childrenStatistics().size() > 1) {
					return new LevelInfo(levelInfo, sort(levelInfo.childrenStatistics(), sortedEntities));
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
					it -> it.entity().getPrimaryKey()
				)
			);
		} else {
			return result;
		}
	}

	/**
	 * Initializes the {@link #sorter} field.
	 */
	public void setSorter(@Nullable Sorter sorter) {
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
	public Map<String, List<LevelInfo>> createStatistics(
		@Nonnull Formula filteringFormula,
		@Nonnull Formula filteringFormulaWithoutUserFilter,
		@Nullable Locale language
	) {
		// invoke computers and register their output using `outputName`
		final Map<String, List<LevelInfo>> unsortedResult = computers
			.stream()
			.collect(
				Collectors.toMap(
					NamedComputer::outputName,
					it -> it.computer().createStatistics(
						filteringFormula, filteringFormulaWithoutUserFilter, language
					)
				)
			);
		// if the sorter is defined, sort them
		if (sorter != null) {
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			// collect all entity primary keys
			unsortedResult.values().forEach(it -> collect(it, writer));
			// create sorted array using the sorter
			final ConstantFormula levelIdFormula = new ConstantFormula(new BaseBitmap(writer.get()));
			final int[] sortedEntities = sorter.sortAndSlice(queryContext, levelIdFormula, 0, levelIdFormula.compute().size());
			// replace the output with the sorted one
			for (Entry<String, List<LevelInfo>> entry : unsortedResult.entrySet()) {
				entry.setValue(sort(entry.getValue(), sortedEntities));
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
