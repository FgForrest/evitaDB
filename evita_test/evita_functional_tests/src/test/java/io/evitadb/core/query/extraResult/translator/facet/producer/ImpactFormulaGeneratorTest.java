/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.visitor.PrettyPrintingFormulaVisitor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies behaviour of {@link ImpactFormulaGenerator} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ImpactFormulaGeneratorTest {
	private final Set<EntityReference> facetGroupConjunction = new HashSet<>();
	private final Set<EntityReference> facetGroupDisjunction = new HashSet<>();
	private final Set<EntityReference> facetGroupNegation = new HashSet<>();
	private final Set<EntityReference> facetGroupExclusivity = new HashSet<>();
	private final ReferenceSchema brandReference = ReferenceSchema._internalBuild(Entities.BRAND, Entities.BRAND, false, Cardinality.ZERO_OR_ONE, null, false, new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) }, new Scope[]{Scope.LIVE});
	private ImpactFormulaGenerator impactFormulaGenerator;

	@BeforeEach
	void setUp() {
		this.impactFormulaGenerator = new ImpactFormulaGenerator(
			(referenceSchema, facetGroupId, level) -> ofNullable(this.facetGroupConjunction.contains(new EntityReference(referenceSchema.getReferencedEntityType(), facetGroupId))).orElse(false),
			(referenceSchema, facetGroupId, level) -> ofNullable(this.facetGroupDisjunction.contains(new EntityReference(referenceSchema.getReferencedEntityType(), facetGroupId))).orElse(false),
			(referenceSchema, facetGroupId, level) -> ofNullable(this.facetGroupNegation.contains(new EntityReference(referenceSchema.getReferencedEntityType(), facetGroupId))).orElse(false),
			(referenceSchema, facetGroupId, level) -> ofNullable(this.facetGroupExclusivity.contains(new EntityReference(referenceSchema.getReferencedEntityType(), facetGroupId))).orElse(false)
		);
	}

	@Test
	void shouldModifyExistingFacetConstraint() {
		final ConstantFormula baseFormula = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
		final Formula updatedFormula = this.impactFormulaGenerator.generateFormula(
			new AndFormula(
				baseFormula,
				new UserFilterFormula(
					new FacetGroupOrFormula(Entities.BRAND, 5, new ArrayBitmap(10), new ArrayBitmap(9))
				)
			),
			baseFormula,
			this.brandReference, 5, 15,
			new Bitmap[]{new ArrayBitmap(8, 9, 10)}
		);
		assertArrayEquals(new int[]{8, 9, 10}, updatedFormula.compute().getArray());
		assertEquals(
			"""
			[#0] AND → [8, 9, 10]
			   [#1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			   [#2] USER FILTER → [8, 9, 10]
			      [#3] FACET BRAND OR (5 - [10, 15]):  ↦ [9],  ↦ [8, 9, 10]
			""",
			PrettyPrintingFormulaVisitor.toStringVerbose(updatedFormula)
		);
	}

	@Test
	void shouldAddNewAndFacetOrConstraint() {
		final ConstantFormula baseFormula = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
		final Formula updatedFormula = this.impactFormulaGenerator.generateFormula(
			new AndFormula(
				baseFormula,
				new UserFilterFormula(
					new FacetGroupOrFormula(Entities.BRAND, 8, new ArrayBitmap(10), new ArrayBitmap(9))
				)
			),
			baseFormula,
			this.brandReference, 5, 15,
			new Bitmap[]{new ArrayBitmap(8, 9, 10)}
		);
		assertArrayEquals(new int[]{9}, updatedFormula.compute().getArray());
		assertEquals(
			"""
			[#0] AND → [9]
			   [#1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			   [#2] USER FILTER → [9]
			      [#3] FACET BRAND OR (8 - [10]):  ↦ [9]
			      [#4] FACET BRAND OR (5 - [15]):  ↦ [8, 9, 10]
			""",
			PrettyPrintingFormulaVisitor.toStringVerbose(updatedFormula)
		);
	}

	@Test
	void shouldAddNewAndFacetAndConstraint() {
		// make group 5 conjunctive
		this.facetGroupConjunction.add(new EntityReference(Entities.BRAND, 5));
		final ConstantFormula baseFormula = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
		final Formula updatedFormula = this.impactFormulaGenerator.generateFormula(
			new AndFormula(
				baseFormula,
				new UserFilterFormula(
					new FacetGroupOrFormula(Entities.BRAND, 8, new ArrayBitmap(10), new ArrayBitmap(9))
				)
			),
			baseFormula,
			this.brandReference, 5, 15,
			new Bitmap[]{new ArrayBitmap(8, 9, 10)}
		);
		assertArrayEquals(new int[]{9}, updatedFormula.compute().getArray());
		assertEquals(
			"""
			[#0] AND → [9]
			   [#1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			   [#2] USER FILTER → [9]
			      [#3] FACET BRAND OR (8 - [10]):  ↦ [9]
			      [#4] FACET BRAND AND (5 - [15]):  ↦ [8, 9, 10]
			""",
			PrettyPrintingFormulaVisitor.toStringVerbose(updatedFormula)
		);
	}

	@Test
	void shouldAddNewOrConstraint() {
		// make group 5 disjunctive
		this.facetGroupDisjunction.add(new EntityReference(Entities.BRAND, 5));
		final ConstantFormula baseFormula = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
		final Formula updatedFormula = this.impactFormulaGenerator.generateFormula(
			new AndFormula(
				baseFormula,
				new UserFilterFormula(
					baseFormula,
					new FacetGroupOrFormula(Entities.BRAND, 8, new ArrayBitmap(10), new ArrayBitmap(9))
				)
			),
			baseFormula,
			this.brandReference, 5, 15,
			new Bitmap[]{new ArrayBitmap(8, 9, 10)}
		);
		assertArrayEquals(new int[]{8, 9, 10}, updatedFormula.compute().getArray());
		assertEquals(
			"""
			[#0] AND → [8, 9, 10]
			   [#1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			   [#2] USER FILTER → [8, 9, 10]
			      [#3] OR → [8, 9, 10]
			         [#4] AND → [9]
			            [Ref to #1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			            [#5] FACET BRAND OR (8 - [10]):  ↦ [9]
			         [#6] FACET BRAND OR (5 - [15]):  ↦ [8, 9, 10]
			""",
			PrettyPrintingFormulaVisitor.toStringVerbose(updatedFormula)
		);
	}

	@Test
	void shouldAddNewNotConstraint() {
		// make group 5 negative
		this.facetGroupNegation.add(new EntityReference(Entities.BRAND, 5));
		final ConstantFormula baseFormula = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
		final Formula updatedFormula = this.impactFormulaGenerator.generateFormula(
			new AndFormula(
				baseFormula,
				new UserFilterFormula(
					baseFormula,
					new FacetGroupOrFormula(Entities.BRAND, 8, new ArrayBitmap(10), new ArrayBitmap(9))
				)
			),
			baseFormula,
			this.brandReference, 5, 15,
			new Bitmap[]{new ArrayBitmap(8, 9, 10)}
		);
		assertArrayEquals(new int[0], updatedFormula.compute().getArray());
		assertEquals(
			"""
			[#0] AND → EMPTY
			   [#1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			   [#2] USER FILTER → EMPTY
			      [#3] NOT → EMPTY
			         [#4] FACET BRAND OR (5 - [15]):  ↦ [8, 9, 10]
			         [#5] AND → [9]
			            [Ref to #1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			            [#6] FACET BRAND OR (8 - [10]):  ↦ [9]
			""",
			PrettyPrintingFormulaVisitor.toStringVerbose(updatedFormula)
		);
	}

	@Test
	void shouldAddNewExclusiveConstraint() {
		// make group 5 exclusive
		this.facetGroupExclusivity.add(new EntityReference(Entities.BRAND, 5));
		final ConstantFormula baseFormula = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
		final Formula updatedFormula = this.impactFormulaGenerator.generateFormula(
			new AndFormula(
				baseFormula,
				new UserFilterFormula(
					baseFormula,
					new FacetGroupOrFormula(Entities.BRAND, 8, new ArrayBitmap(10), new ArrayBitmap(8, 9, 12)),
					new FacetGroupOrFormula(Entities.BRAND, 5, new ArrayBitmap(16), new ArrayBitmap(12))
				)
			),
			baseFormula,
			this.brandReference, 5, 15,
			new Bitmap[]{new ArrayBitmap(8, 9, 10)}
		);
		assertArrayEquals(new int[] {8, 9}, updatedFormula.compute().getArray());
		assertEquals(
			"""
			[#0] AND → [8, 9]
			   [#1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			   [#2] USER FILTER → [8, 9]
			      [Ref to #1] [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
			      [#3] FACET BRAND OR (8 - [10]):  ↦ [8, 9, 12]
			      [#4] FACET BRAND OR (5 - [15]):  ↦ [8, 9, 10]
			""",
			PrettyPrintingFormulaVisitor.toStringVerbose(updatedFormula)
		);
	}

}
