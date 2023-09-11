EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "audio")
        		)
        	),
        	Require(
        		HierarchyOfReference(
        			"categories",
        			RemoveEmpty,
        			Siblings(
        				"audioSiblings",
        				EntityFetch(
        					AttributeContent("code")
        				),
        				Statistics(WithoutUserFilter, ChildrenCount, QueriedEntityCount)
        			)
        		)
        	)
        )
	)
);