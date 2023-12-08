@EntityRef("Product")
public interface MyEntity {

	@PrimaryKey int id();
	@PrimaryKey Integer idAsIntegerObject();
	@PrimaryKey long idAsLong();
	@PrimaryKey Long idAsLongObject();
	@PrimaryKeyRef int idAlternative();
	@PrimaryKeyRef Integer idAlternativeAsIntegerObject();
	@PrimaryKeyRef long idAlternativeAsLong();
	@PrimaryKeyRef Long idAlternativeAsLongObject();

}