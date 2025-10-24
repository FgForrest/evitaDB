evita.updateCatalog(
	"evita", session -> {
		return session.deleteEntityAndItsHierarchy(
			"Category",
			1
		);
	}
);
