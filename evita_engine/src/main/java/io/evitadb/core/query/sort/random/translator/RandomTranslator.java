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

package io.evitadb.core.query.sort.random.translator;

import io.evitadb.api.query.order.Random;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.random.RandomSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;

import javax.annotation.Nonnull;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link Random} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class RandomTranslator implements OrderingConstraintTranslator<Random> {

	@Nonnull
	@Override
	public Sorter createSorter(@Nonnull Random random, @Nonnull OrderByVisitor orderByVisitor) {
		final Sorter lastUsedSorter = orderByVisitor.getLastUsedSorter();
		if (lastUsedSorter == null) {
			return new RandomSorter();
		} else {
			return lastUsedSorter.andThen(
				new RandomSorter()
			);
		}
	}

}
