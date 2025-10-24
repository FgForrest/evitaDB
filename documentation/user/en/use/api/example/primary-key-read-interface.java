@EntityRef("Product")
public interface MyEntity {

	@PrimaryKey int getId();
	@PrimaryKey Integer getIdAsIntegerObject();
	@PrimaryKey long getIdAsLong();
	@PrimaryKey Long getIdAsLongObject();
	@PrimaryKeyRef int getIdAlternative();
	@PrimaryKeyRef Integer getIdAlternativeAsIntegerObject();
	@PrimaryKeyRef long getIdAlternativeAsLong();
	@PrimaryKeyRef Long getIdAlternativeAsLongObject();

}
