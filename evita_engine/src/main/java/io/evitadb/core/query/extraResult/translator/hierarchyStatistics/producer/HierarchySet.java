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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * TODO JNO - document me and methods
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class HierarchySet {
	private final QueryContext queryContext;
	private final Map<String, AbstractHierarchyStatisticsComputer> computers = new HashMap<>(16);
	@Nullable
	private Sorter sorter;


	public void setSorter(@Nullable Sorter sorter) {
		this.sorter = sorter;
	}

	public void addComputer(String outputName, AbstractHierarchyStatisticsComputer computer) {
		this.computers.put(outputName, computer);
	}

	public Map<String, List<LevelInfo>> createStatistics(
		@Nonnull Formula filteringFormula,
		@Nonnull Formula filteringFormulaWithoutUserFilter,
		@Nullable Locale language
	) {
		final Map<String, List<LevelInfo>> unsortedResult = computers.entrySet()
			.stream()
			.collect(
				Collectors.toMap(
					Entry::getKey,
					entry -> entry.getValue().createStatistics(
						filteringFormula, filteringFormulaWithoutUserFilter, language
					)
				)
			);
		if (sorter != null) {
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			// collect all entity primary keys
			for (List<LevelInfo> levelInfos : unsortedResult.values()) {
				collect(levelInfos, writer);
			}
			final ConstantFormula levelIdFormula = new ConstantFormula(new BaseBitmap(writer.get()));
			final int[] sortedEntities = sorter.sortAndSlice(queryContext, levelIdFormula, 0, levelIdFormula.compute().size());
			for (Entry<String, List<LevelInfo>> entry : unsortedResult.entrySet()) {
				entry.setValue(sort(entry.getValue(), sortedEntities));
			}
		}
		return unsortedResult;
	}

	private static void collect(List<LevelInfo> unsortedResult, RoaringBitmapWriter<RoaringBitmap> writer) {
		for (LevelInfo levelInfo : unsortedResult) {
			writer.add(levelInfo.entity().getPrimaryKey());
			collect(levelInfo.childrenStatistics(), writer);
		}
	}

	/**
	 * TODO JNO - document me
	 * @param result
	 * @param sortedEntities
	 * @return
	 */
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

}
