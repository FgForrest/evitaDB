@EntityRef("Product")
public interface MyEntity {

	// simplified Brand entity interface
	// this example demonstrates the option to return directly referenced entities from the main entity
	@EntityRef("Brand")
	public interface Brand extends Serializable {

		@PrimaryKeyRef
		int getId();

		// attribute code of the Brand entity
		@AttributeRef("code")
		@Nullable String getCode();

	}

	// simplified Parameter entity interface
	@EntityRef("Parameter")
	public interface Parameter extends Serializable {

		@PrimaryKeyRef
		int getId();

		// attribute code of the Parameter entity
		@AttributeRef("code")
		@Nullable String getCode();

	}

	// simplified ParameterGroup entity interface
	@EntityRef("ParameterGroup")
	public interface ParameterGroup extends Serializable {

		@PrimaryKeyRef
		int getId();

		// attribute code of the ParameterGroup entity
		@AttributeRef("code")
		@Nullable String getCode();

	}

	// simplified ProductParameter entity interface
	// this example demonstrates the option to return interfaces covering the references that provide further access
	// to the referenced entities (both grouping and referenced entities)
	@EntityRef("Parameter")
	public interface ProductParameter extends Serializable {

		@ReferencedEntity
		int getPrimaryKey();

		// attribute code of the reference to the Parameter entity
		@AttributeRef("priority")
		@Nullable Long getPriority();

		// primary key of the referenced entity of the reference
		@ReferencedEntity
		@Nullable Integer getParameter();

		// reference to the referenced entity descriptor of the reference
		@ReferencedEntity
		@Nullable EntityReferenceContract getParameterEntityClassifier();

		// reference to the referenced entity of the reference
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntity
		@Nullable Parameter getParameterEntity() throws ContextMissingException;

		// primary key of the grouping entity of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable Integer getParameterGroup();

		// reference to the grouping entity descriptor of the reference to the Parameter entity
		@ReferencedEntityGroup
		@Nullable EntityReferenceContract getParameterGroupEntityClassifier();

		// reference to the grouping entity of the reference to the Parameter entity
		// throws ContextMissingException if the referenced entity was not fetched from the server
		@ReferencedEntityGroup
		@Nullable ParameterGroup getParameterGroupEntity() throws ContextMissingException;

	}

	// method returns referenced entity Brand if such reference with cardinality ZERO_OR_ONE exists
	// and the Brand entity is fetched along with MyEntity
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable Brand getBrand() throws ContextMissingException;

	// method returns referenced Brand entity primary key if such reference with cardinality ZERO_OR_ONE exists
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("brand")
	@Nullable Integer getBrandId() throws ContextMissingException;

	// method returns collection of referenced ProductParameter references or empty list
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@Nonnull List<ProductParameter> getParameters() throws ContextMissingException;

	// alternative format for `getParameters` method with similar behaviour
	@ReferenceRef("parameters")
	@Nonnull Set<ProductParameter> getParametersAsSet() throws ContextMissingException;

	// alternative format for `getParameters` method with similar behaviour
	@ReferenceRef("parameters")
	@Nonnull Collection<ProductParameter> getParameterAsCollection() throws ContextMissingException;

	// alternative format for `getParameters` method with similar behaviour
	@ReferenceRef("parameters")
	@Nonnull ProductParameter[] getParameterAsArray() throws ContextMissingException;

	// method returns array of referenced Parameter entities or empty value if the reference information
	// was not fetched from the server or there is no reference to Parameter from the current product
	@ReferenceRef("parameters")
	@Nonnull Optional<ProductParameter[]> getParametersIfPresent();

	// method returns array of referenced Parameter entities or null
	// reference `parameters` has cardinality ZERO_OR_MORE
	// throws ContextMissingException if the reference information was not fetched from the server
	@ReferenceRef("parameters")
	@Nullable int[] getParameterIds() throws ContextMissingException;

	// method returns array of referenced Parameter entity primary keys or empty value if the reference information
	// was not fetched from the server or there is no reference to Parameter from the current product
	@ReferenceRef("parameters")
	@Nonnull Optional<int[]> getParameterIdsIfPresent();

}
