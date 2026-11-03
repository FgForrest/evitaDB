evita.updateCatalog(
	"evita",
	session -> {
		// the `marketingBrand` reference of the Product is managed, so its target collection must exist
		session
			.defineEntitySchema("brand")
			.withAttribute("code", String.class, whichIs -> whichIs.filterable())
			.updateVia(session);
	}
);
