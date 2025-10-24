@EntityRef("Category")
public interface MyEntity {

	// return id of parent entity, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable Integer getParentId() throws ContextMissingException;

	// return optional id of parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull OptionalInt getParentIdIfNotRoot();

	// return parent entity, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable SealedEntity getParentEntity() throws ContextMissingException;

	// return optional parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<SealedEntity> getParentEntityIfNotRoot();

	// return reference to a parent entity, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable EntityReference getParentEntityReference() throws ContextMissingException;

	// return optional reference to a parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<EntityReferenceContract> getParentEntityReferenceIfPresent();

	// return reference to a parent wrapped in this interface, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable MyEntity getParentMyEntity() throws ContextMissingException;

	// return optional reference to a parent wrapped in this interface, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<MyEntity> getParentMyEntityIfPresent();

}
