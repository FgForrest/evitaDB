evita.updateCatalog(
	"evita",
	session -> {
		session.defineEntitySchemaFromModelClass(
			Product.class
		);
	}
);
