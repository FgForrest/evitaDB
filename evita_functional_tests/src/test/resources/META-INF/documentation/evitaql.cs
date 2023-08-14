EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        #QUERY#
	)
);