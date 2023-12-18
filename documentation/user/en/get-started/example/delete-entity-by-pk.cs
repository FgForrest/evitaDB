evita.UpdateCatalog(
	"evita", session => {
		return session.DeleteEntity(
			"Brand",
			2
		);
	}
);