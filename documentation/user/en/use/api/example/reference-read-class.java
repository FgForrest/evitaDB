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
@Data
public class MyEntity {

	// field contains referenced entity Brand if such reference with cardinality ZERO_OR_ONE exists
	// and the Brand entity is fetched along with MyEntity
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable private final Brand brand;

	// field contains referenced Brand entity primary key if such reference with cardinality ZERO_OR_ONE exists
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable private final Integer brandId;

	// field contains collection of referenced ProductParameter references or empty list
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@NonNull private final List<ProductParameter> parameters;

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull private final Set<ProductParameter> parametersAsSet;

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull private final Collection<ProductParameter> parameterAsCollection;

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull private final ProductParameter[] parameterAsArray;

	// field contains array of referenced Parameter entities or null
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@Nullable private final int[] parameterIds;

	// constructor is usually generated by Lombok
	public MyEntity(
		Brand brand, Integer brandId,
		List<ProductParameter> parameters, Set<ProductParameter> parametersAsSet,
		Collection<ProductParameter> parameterAsCollection, ProductParameter[] parameterAsArray,
		int[] parameterIds
	) {
		this.brand = brand;
		this.brandId = brandId;
		this.parameters = parameters;
		this.parametersAsSet = parametersAsSet;
		this.parameterAsCollection = parameterAsCollection;
		this.parameterAsArray = parameterAsArray;
		this.parameterIds = parameterIds;
	}

	// simplified Brand entity interface
	// this example demonstrates the option to return directly referenced entities from the main entity
	@EntityRef("Brand")
	@Data
	public class Brand implements Serializable {
		@PrimaryKeyRef
		private final int id;

		// attribute code of the Brand entity
		@AttributeRef("code")
		@Nullable  private final String code;

		// constructor is usually generated by Lombok
		public Brand(int id, String code) {
			this.id = id;
			this.code = code;
		}

	}

	// simplified Parameter entity interface
	@EntityRef("Parameter")
	@Data
	public class Parameter implements Serializable {

		@PrimaryKeyRef
		private final int id;

		// attribute code of the Parameter entity
		@AttributeRef("code")
		@Nullable private final String code;

		// constructor is usually generated by Lombok
		public Parameter(int id, String code) {
			this.id = id;
			this.code = code;
		}

	}

	// simplified ParameterGroup entity interface
	@EntityRef("ParameterGroup")
	@Data
	public class ParameterGroup implements Serializable {

		@PrimaryKeyRef
		private final int id;

		// attribute code of the ParameterGroup entity
		@AttributeRef("code")
		@Nullable private final String code;

		// constructor is usually generated by Lombok
		public ParameterGroup(int id, String code) {
			this.id = id;
			this.code = code;
		}

	}

	// simplified ProductParameter entity interface
	// this example demonstrates the option to return interfaces covering the references that provide further access
	// to the referenced entities (both grouping and referenced entities)
	@EntityRef("Parameter")
	@Data
	public class ProductParameter implements Serializable {

		@ReferencedEntity
		private final int primaryKey;

		// attribute code of the reference to the Parameter entity
		@AttributeRef("priority")
		@Nullable private final Long priority;

		// primary key of the referenced entity of the reference
		@ReferencedEntity
		@Nullable private final Integer parameter;

		// reference to the referenced entity descriptor of the reference
		@ReferencedEntity
		@Nullable private final EntityReferenceContract<?> parameterEntityClassifier;

		// reference to the referenced entity of the reference
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntity
		@Nullable private final Parameter parameterEntity;

		// primary key of the grouping entity of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable private final Integer parameterGroup;

		// reference to the grouping entity descriptor of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable private final EntityReferenceContract<?> parameterGroupEntityClassifier;

		// reference to the grouping entity of the reference to the Parameter entity
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntityGroup
		@Nullable private final ParameterGroup parameterGroupEntity;

		// constructor is usually generated by Lombok
		public ProductParameter(
			int primaryKey, Long priority,
			Integer parameter, EntityReferenceContract<?> parameterEntityClassifier, Parameter parameterEntity,
			Integer parameterGroup, EntityReferenceContract<?> parameterGroupEntityClassifier, ParameterGroup parameterGroupEntity
		) {
			this.primaryKey = primaryKey;
			this.priority = priority;
			this.parameter = parameter;
			this.parameterEntityClassifier = parameterEntityClassifier;
			this.parameterEntity = parameterEntity;
			this.parameterGroup = parameterGroup;
			this.parameterGroupEntityClassifier = parameterGroupEntityClassifier;
			this.parameterGroupEntity = parameterGroupEntity;
		}
	}

}
