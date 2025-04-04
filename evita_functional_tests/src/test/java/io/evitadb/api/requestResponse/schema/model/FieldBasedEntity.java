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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.*;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.dataType.Scope;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Example class for ClassSchemaAnalyzerTest.
 * The entity attributes are by design empty to test default initialization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Entity(
	allowedEvolution = {
		EvolutionMode.ADDING_LOCALES,
		EvolutionMode.ADDING_CURRENCIES
	}
)
@SortableAttributeCompounds({
	@SortableAttributeCompound(
		name = "compoundA",
		description = "Compound A description",
		attributeElements = {
			@AttributeSource(attributeName = "code", orderDirection = OrderDirection.DESC, orderBehaviour = OrderBehaviour.NULLS_FIRST),
			@AttributeSource(attributeName = "name")
		},
		scope = { Scope.LIVE }
	),
	@SortableAttributeCompound(
		name = "compoundB",
		description = "Compound B description",
		deprecated = "Not used anymore",
		attributeElements = {
			@AttributeSource(attributeName = "ean"),
			@AttributeSource(attributeName = "quantity", orderDirection = OrderDirection.DESC, orderBehaviour = OrderBehaviour.NULLS_FIRST)
		},
		scope = {}
	)
})
@Data
@NoArgsConstructor
public class FieldBasedEntity {

	@PrimaryKey
	private int id;

	@Attribute
	@Nonnull
	private String code;

	@Attribute(localized = true)
	@Nonnull
	private String name;

	@Attribute
	@Nonnull
	private String ean;

	@Attribute
	@Nonnull
	private BigDecimal quantity;

	@Attribute
	@Nonnull
	private int[] years = new int[] {1978,2005,2020};

	@AssociatedData
	@Nonnull
	private ReferencedFiles referencedFiles;

	@ParentEntity
	private int parentEntity;

	@PriceForSale
	private PriceContract sellingPrice;

	@Reference
	private Brand marketingBrand;

	@Reference
	private Brand[] licensingBrands;

	record ReferencedFiles(@Nonnull int... fileId) implements Serializable {}

	@SortableAttributeCompounds({
		@SortableAttributeCompound(
			name = "compoundC",
			description = "Compound C description",
			attributeElements = {
				@AttributeSource(attributeName = "market", orderDirection = OrderDirection.DESC, orderBehaviour = OrderBehaviour.NULLS_FIRST),
				@AttributeSource(attributeName = "inceptionYear")
			},
			scope = { Scope.LIVE }
		)
	})
	public static class Brand {

		@ReferencedEntity
		private int brand;

		@ReferencedEntityGroup
		private int brandGroup;

		@Attribute
		private String market;

		@Attribute
		private int inceptionYear;

	}

}
