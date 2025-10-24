@EntityRef("Product")
public interface MyEntityEditor extends MyEntity {

	@PrimaryKey void setId(int id);
	@PrimaryKey void setIdAsIntegerObject(@Nonnull Integer id);
	@PrimaryKey void setIdAsLong(long id);
	@PrimaryKey void setIdAsLongObject(@Nonnull Long id);
	@PrimaryKeyRef void setIdAlternative(int id);
	@PrimaryKeyRef void setIdAlternativeAsIntegerObject(@Nonnull Integer id);
	@PrimaryKeyRef void setIdAlternativeAsLong(long id);
	@PrimaryKeyRef void setIdAlternativeAsLongObject(@Nonnull Long id);

	@PrimaryKey @Nonnull MyEntityEditor setIdReturningSelf(int id);
	@PrimaryKey @Nonnull MyEntityEditor setIdAsIntegerObjectReturningSelf(@Nonnull Integer id);
	@PrimaryKey @Nonnull MyEntityEditor setIdAsLongReturningSelf(long id);
	@PrimaryKey @Nonnull MyEntityEditor setIdAsLongObjectReturningSelf(@Nonnull Long id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeReturningSelf(int id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeAsIntegerObjectReturningSelf(@Nonnull Integer id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeAsLongReturningSelf(long id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeAsLongObjectReturningSelf(@Nonnull Long id);

}
