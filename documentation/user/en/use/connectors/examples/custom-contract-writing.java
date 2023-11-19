evita.updateCatalog(
	"testCatalog",
	session -> {
		TODO JNO - UPDATE THIS WITH WRITABLE PRODUCT INTERFACE

		// create new product, fill the data and store it
		final Product product = session.createNew(
			Product.class, 100
		);

		// get existing product, update the data and store it
		session.getEntity(
			Product.class, 1, entityFetchAllContent()
		).orElseThrow();
	}
);