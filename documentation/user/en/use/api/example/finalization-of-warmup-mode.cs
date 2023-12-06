evita.UpdateCatalog(
	"testCatalog",
	session => {
		session.GoLiveAndClose();
	}
);