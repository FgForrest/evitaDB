evita.UpdateCatalog(
	"evita", session => {
		return session.DeleteEntityAndItsHierarchy(
			"Category",
			1
		);
	}
);