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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link ReferencedEntityIndexPrimaryKeyTranslatingFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferencedEntityIndexPrimaryKeyTranslatingFormulaIdentityFixture
	implements IdentityFixture<ReferencedEntityIndexPrimaryKeyTranslatingFormula> {
	/** Covers the value and locality contracts of the holder. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<ReferencedEntityIndexPrimaryKeyTranslatingFormula>> modes() {
		final ReferencedTypeEntityIndex index = index();
		final ReferencedTypeEntityIndex otherIndex = index();
		final UnaryOperator<Bitmap> expansion = UnaryOperator.identity();
		final GlobalEntityIndex live = GlobalEntityIndex.createEmptyIndex("Product", Scope.LIVE);
		final GlobalEntityIndex archived = GlobalEntityIndex.createEmptyIndex("Product", Scope.ARCHIVED);
		final GlobalEntityIndex changedLive = GlobalEntityIndex.createEmptyIndex("Product", Scope.LIVE);
		final long[] oversized = new long[TokenList.CAP + 1];
		Arrays.fill(oversized, 1);
		return Map.of(
			"internal no expansion", new ConstructionMode<>(
				() -> internal(new long[]{1, 2}, index, null),
				Map.of("scope id", () -> internal(new long[]{1, 3}, index, null),
					"scope order", () -> internal(new long[]{2, 1}, index, null),
					"index", () -> internal(new long[]{1, 2}, otherIndex, null)), Map.of(),
				Map.of("many scopes", () -> internal(oversized, index, null)),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of("expansionFunction"), Set.of("referencedEntityTypeIndex", "expansionFunction"),
				Set.of("expansionFunction")),
			"optional expansion", new ConstructionMode<>(
				() -> internal(new long[]{1, 2}, index, null),
				Map.of("scope id", () -> internal(new long[]{1, 3}, index, null),
					"index", () -> internal(new long[]{1, 2}, otherIndex, null)),
				Map.of("expansion", () -> internal(new long[]{1, 2}, index, expansion)),
				Map.of("many scopes", () -> internal(oversized, index, null)), Map.of(),
				Set.of(), Set.of("referencedEntityTypeIndex"), Set.of()),
			"internal expansion", new ConstructionMode<>(null, Map.of(),
				Map.of("expansion", () -> internal(new long[]{1, 2}, index, expansion),
					"managed expansion", () -> managed(index, true),
					"unmanaged expansion", () -> new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
						ReferenceSchema._internalBuild("relatedProducts", "Product", false, Cardinality.ZERO_OR_MORE,
							null, false, null, null), "Product", false, (type, scope) -> Optional.empty(),
						index, leaf(1), expansion)), Map.of(),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), UNEQUAL)),
				Set.of(), Set.of("referencedEntityTypeSuperSetTransactionalIds", "referencedEntityTypeIndex"),
				Set.of()),
			"managed indexes", new ConstructionMode<>(() -> managedIndexes(index, live, archived),
				Map.of("live index", () -> managedIndexes(index, changedLive, archived),
					"scope order", () -> managedIndexes(index, archived, live),
					"target index", () -> managedIndexes(otherIndex, live, archived)), Map.of(), Map.of(),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of("expansionFunction"),
				Set.of("referencedEntityTypeSuperSetTransactionalIds", "referencedEntityTypeIndex",
					"expansionFunction"),
				Set.of("expansionFunction")),
			"managed target", new ConstructionMode<>(() -> managed(index, false),
				Map.of("index", () -> managed(otherIndex, false)), Map.of(), Map.of(),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of("referencedEntityTypeSuperSetTransactionalIds", "expansionFunction"),
				Set.of("referencedEntityTypeSuperSetTransactionalIds", "referencedEntityTypeIndex",
					"expansionFunction"),
				Set.of("referencedEntityTypeSuperSetIdentityIds", "expansionFunction")),
			"unmanaged target", new ConstructionMode<>(() -> unmanaged(index),
				Map.of("index", () -> unmanaged(otherIndex)), Map.of(), Map.of(),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of("referencedEntityTypeSuperSetTransactionalIds", "expansionFunction"),
				Set.of("referencedEntityTypeSuperSetTransactionalIds", "referencedEntityTypeIndex",
					"expansionFunction"),
				Set.of("referencedEntityTypeSuperSetIdentityIds", "expansionFunction")));

	}

	/** Builds an exact nonempty child. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

	/** Builds a real referenced-type index. */
	@Nonnull
	static ReferencedTypeEntityIndex index() {
		return new ReferencedTypeEntityIndex(1, "Product",
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "relatedProducts"));
	}

	/** Exercises the internal constructor with explicitly supplied producer versions. */
	@Nonnull
	static ReferencedEntityIndexPrimaryKeyTranslatingFormula internal(
		@Nonnull long[] ids, @Nonnull ReferencedTypeEntityIndex index, @Nullable UnaryOperator<Bitmap> expansion
	) {
		return new ReferencedEntityIndexPrimaryKeyTranslatingFormula(null, ids, index, 1, expansion, leaf(1));
	}

	/** Exercises the managed constructor when no scope index exists. */
	@Nonnull
	static ReferencedEntityIndexPrimaryKeyTranslatingFormula managed(
		@Nonnull ReferencedTypeEntityIndex index, boolean expansion
	) {
		return new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
			ReferenceSchema._internalBuild("relatedProducts", "Product", true, Cardinality.ZERO_OR_MORE,
				null, false, null, null), "Product", true, (type, scope) -> Optional.empty(), index, leaf(1),
			expansion ? UnaryOperator.identity() : null);
	}

	/** Exercises the unmanaged constructor without a superset. */
	@Nonnull
	static ReferencedEntityIndexPrimaryKeyTranslatingFormula unmanaged(@Nonnull ReferencedTypeEntityIndex index) {
		return new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
			ReferenceSchema._internalBuild("relatedProducts", "Product", false, Cardinality.ZERO_OR_MORE,
				null, false, null, null), "Product", false, (type, scope) -> Optional.empty(), index, leaf(1), null);
	}

	/** Builds the superset from the real global indexes of both scopes. */
	@Nonnull
	private static ReferencedEntityIndexPrimaryKeyTranslatingFormula managedIndexes(
		@Nonnull ReferencedTypeEntityIndex index, @Nonnull GlobalEntityIndex live, @Nonnull GlobalEntityIndex archived
	) {
		return new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
			ReferenceSchema._internalBuild("relatedProducts", "Product", true, Cardinality.ZERO_OR_MORE,
				null, false, null, null), "Product", true,
			(type, scope) -> Optional.of(scope == Scope.LIVE ? live : archived), index, leaf(1), null);
	}

}
