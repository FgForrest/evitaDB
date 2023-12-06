evita.UpdateCatalog(
	"testCatalog", session => {
		return session.DeleteEntityAndItsHierarchy(
			"Category",
			1
		);
	}
);