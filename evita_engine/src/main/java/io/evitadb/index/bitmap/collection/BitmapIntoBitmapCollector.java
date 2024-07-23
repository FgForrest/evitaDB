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

package io.evitadb.index.bitmap.collection;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

/**
 * Implementation of {@link Collector} interface allowing to aggregate integers into the bitmap in following way:
 *
 * ``` java
 * Collectors.groupingBy(
 *    it -> it.getKey(),
 *    Collectors.mapping(it -> it.getValue(), BitmapIntoBitmapCollector.INSTANCE)
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BitmapIntoBitmapCollector implements Collector<Bitmap, Bitmap, Bitmap> {
	public static final BitmapIntoBitmapCollector INSTANCE = new BitmapIntoBitmapCollector();

	@Override
	public Supplier<Bitmap> supplier() {
		return BaseBitmap::new;
	}

	@Override
	public BiConsumer<Bitmap, Bitmap> accumulator() {
		return Bitmap::addAll;
	}

	@Override
	public BinaryOperator<Bitmap> combiner() {
		return (bitmap, bitmap2) -> {
			bitmap.addAll(bitmap2);
			return bitmap;
		};
	}

	@Override
	public Function<Bitmap, Bitmap> finisher() {
		return Function.identity();
	}

	@Override
	public Set<Characteristics> characteristics() {
		return EnumSet.of(Characteristics.IDENTITY_FINISH);
	}

}
