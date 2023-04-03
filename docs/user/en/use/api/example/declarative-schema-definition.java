evita.updateCatalog(
	"testCatalog",
	session -> {
		session.defineEntitySchemaFromModelClass(
			Product.class
		);
	}
);