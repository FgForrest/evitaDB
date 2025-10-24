@EntityRef("Product")
@Data
public class MyEntityEditor {

	// field contains referenced entity Brand if such reference with cardinality ZERO_OR_ONE exists
	// and the Brand entity is fetched along with MyEntity
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable private Brand brand;

	// field contains referenced Brand entity primary key if such reference with cardinality ZERO_OR_ONE exists
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable private Integer brandId;

	// field contains collection of referenced ProductParameter references or empty list
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@NonNull private List<ProductParameter> parameters;

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull private Set<ProductParameter> parametersAsSet;

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull private Collection<ProductParameter> parameterAsCollection;

	// alternative format for `Parameters` method with similar behaviour
	@ReferenceRef("parameters")
	@NonNull private ProductParameter[] parameterAsArray;

	// field contains array of referenced Parameter entities or null
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@Nullable private int[] parameterIds;

	// simplified Brand entity interface
	// this example demonstrates the option to return directly referenced entities from the main entity
	@EntityRef("Brand")
	@Data
	public class Brand implements Serializable {
		@PrimaryKeyRef
		private int id;

		// attribute code of the Brand entity
		@AttributeRef("code")
		@Nullable  private String code;

	}

	// simplified Parameter entity interface
	@EntityRef("Parameter")
	@Data
	public class Parameter implements Serializable {

		@PrimaryKeyRef
		private int id;

		// attribute code of the Parameter entity
		@AttributeRef("code")
		@Nullable private String code;

	}

	// simplified ParameterGroup entity interface
	@EntityRef("ParameterGroup")
	@Data
	public class ParameterGroup implements Serializable {

		@PrimaryKeyRef
		private int id;

		// attribute code of the ParameterGroup entity
		@AttributeRef("code")
		@Nullable private String code;

	}

	// simplified ProductParameter entity interface
	// this example demonstrates the option to return interfaces covering the references that provide further access
	// to the referenced entities (both grouping and referenced entities)
	@EntityRef("Parameter")
	@Data
	public class ProductParameter implements Serializable {

		@ReferencedEntity
		private int primaryKey;

		// attribute code of the reference to the Parameter entity
		@AttributeRef("priority")
		@Nullable private Long priority;

		// primary key of the referenced entity of the reference
		@ReferencedEntity
		@Nullable private Integer parameter;

		// reference to the referenced entity descriptor of the reference
		@ReferencedEntity
		@Nullable private EntityReferenceContract parameterEntityClassifier;

		// reference to the referenced entity of the reference
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntity
		@Nullable private Parameter parameterEntity;

		// primary key of the grouping entity of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable private Integer parameterGroup;

		// reference to the grouping entity descriptor of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable private EntityReferenceContract parameterGroupEntityClassifier;

		// reference to the grouping entity of the reference to the Parameter entity
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntityGroup
		@Nullable private ParameterGroup parameterGroupEntity;

	}

}
