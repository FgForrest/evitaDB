@EntityRef("Product")
public interface MyEntityEditor extends MyEntity {

	// sets entity with passed primary key as parent of this entity
	// if null is passed, parent is removed and this entity becomes one of the root entities
	// returns reference to self instance to allow builder pattern chaining
	@ParentEntity
	@Nonnull MyEntityEditor setParentId(@Nullable Integer parentId);

	// sets passed entity of the same type as parent of this entity
	// if null is passed, parent is removed and this entity becomes one of the root entities
	@ParentEntity
	void setParentEntity(@Nullable MyEntity parentEntity);

	// sets entity using reference DTO as parent of this entity
	// if null is passed, parent is removed and this entity becomes one of the root entities
	// returns reference to self instance to allow builder pattern chaining
	@ParentEntity
	@Nonnull MyEntityEditor setParentEntityReference(@Nullable EntityReferenceContract parentEntityReference);

	// sets entity using `EntityClassifier` interface as parent of this entity
	// if null is passed, parent is removed and this entity becomes one of the root entities
	@ParentEntity
	void setParentEntityClassifier(@Nullable EntityClassifier parentEntityClassifier);

	// sets entity using `EntityClassifierWithParent` interface as parent of this entity
	// if null is passed, parent is removed and this entity becomes one of the root entities
	@ParentEntity
	void setParentEntityClassifierWithParent(@Nullable EntityClassifierWithParent parentEntityClassifierWithParent);

	// sets entity with passed primary key as parent of this entity and allows to update data of this parent entity
	// using passed `Consumer` interface implementation
	// if entity of particular primary key does not exist, it is created with the data set by passed `Consumer`
	// interface implementation
	// method returns reference to self instance to allow builder pattern chaining
	@ParentEntity
	@Nonnull MyEntityEditor withParent(int parentPrimaryKey, @Nonnull Consumer<MyEntityEditor> setupLogic);

	// method removes parent entity of this entity
	// if this entity does not have parent, method does nothing
	// current entity becomes one of the root entities when the changes are persisted
	@ParentEntity
	@RemoveWhenExists
	void removeParent();

	// method removes parent entity of this entity and returns true if parent entity was removed
	// if this entity does not have parent, method does nothing and returns false
	// current entity becomes one of the root entities when the changes are persisted
	@ParentEntity
	@RemoveWhenExists
	boolean removeParentAndReturnResult();

	// method removes parent entity of this entity and returns its primary key if parent entity was removed
	// if this entity does not have parent, method does nothing and returns null
	@ParentEntity
	@RemoveWhenExists
	@Nullable Integer removeParentAndReturnItsPrimaryKey();

	// method removes parent entity of this entity and returns its instance if parent entity was removed
	// if this entity does not have parent, method does nothing and returns null
	// method throws exception if the parent entity body was not fetched from the database
	@ParentEntity
	@RemoveWhenExists
	@Nullable MyEntity removeParentAndReturnIt() throws ContextMissingException;

}
