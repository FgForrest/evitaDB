evita.UpdateCatalog(
	"evita",
	session => {
		session.GoLiveAndClose();
	}
);