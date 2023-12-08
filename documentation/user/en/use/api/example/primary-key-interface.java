@EntityRef("Product")
public interface MyEntity {

	@PrimaryKey int any();
	@PrimaryKey Integer any();
	@PrimaryKey long any();
	@PrimaryKey Long any();

	@PrimaryKeyRef int any();
	@PrimaryKeyRef Integer any();
	@PrimaryKeyRef long any();
	@PrimaryKeyRef Long any();

}