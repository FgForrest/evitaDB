/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

@EntityRef("Product")
public record MyEntity(

	// component contains referenced entity Brand if such reference with cardinality ZERO_OR_ONE exists
	// and the Brand entity is fetched along with MyEntity
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable Brand brand,

	// component contains referenced Brand entity primary key if such reference with cardinality ZERO_OR_ONE exists
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable Integer brandId,

	// component contains collection of referenced ProductParameter references or empty list
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@NonNull List<ProductParameter> parameters,

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull Set<ProductParameter> parametersAsSet,

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull Collection<ProductParameter> parameterAsCollection,

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull ProductParameter[] parameterAsArray,

	// component contains array of referenced Parameter entities or null
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@Nullable int[] parameterIds

) {

	// simplified Brand entity interface
	// this example demonstrates the option to return directly referenced entities from the main entity
	@EntityRef("Brand")
	public record Brand(
		@PrimaryKeyRef
		int id,

		// attribute code of the Brand entity
		@AttributeRef("code")
		@Nullable String code

	) implements Serializable {

	}

	// simplified Parameter entity interface
	@EntityRef("Parameter")
	public record Parameter(

		@PrimaryKeyRef
		int id,

		// attribute code of the Parameter entity
		@AttributeRef("code")
		@Nullable String code

	) implements Serializable {

	}

	// simplified ParameterGroup entity interface
	@EntityRef("ParameterGroup")
	public record ParameterGroup(

		@PrimaryKeyRef
		int id,

		// attribute code of the ParameterGroup entity
		@AttributeRef("code")
		@Nullable String code

	) implements Serializable {

	}

	// simplified ProductParameter entity interface
	// this example demonstrates the option to return interfaces covering the references that provide further access
	// to the referenced entities (both grouping and referenced entities)
	@EntityRef("Parameter")
	public record ProductParameter(

		@ReferencedEntity
		int primaryKey,

		// attribute code of the reference to the Parameter entity
		@AttributeRef("priority")
		@Nullable Long priority,

		// primary key of the referenced entity of the reference
		@ReferencedEntity
		@Nullable Integer parameter,

		// reference to the referenced entity descriptor of the reference
		@ReferencedEntity
		@Nullable EntityReferenceContract<?> parameterEntityClassifier,

		// reference to the referenced entity of the reference
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntity
		@Nullable Parameter parameterEntity,

		// primary key of the grouping entity of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable Integer parameterGroup,

		// reference to the grouping entity descriptor of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable EntityReferenceContract<?> parameterGroupEntityClassifier,

		// reference to the grouping entity of the reference to the Parameter entity
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntityGroup
		@Nullable ParameterGroup parameterGroupEntity

	) implements Serializable {

	}

}
