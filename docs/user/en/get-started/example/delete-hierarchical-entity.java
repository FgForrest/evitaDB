evita.updateCatalog(
	"testCatalog", session -> {
		return session.deleteEntityAndItsHierarchy(
			"Category",
			1
		);
	}
);