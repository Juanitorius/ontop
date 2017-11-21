package it.unibz.inf.ontop.spec.mapping.transformer;

import it.unibz.inf.ontop.dbschema.DBMetadata;
import it.unibz.inf.ontop.exception.DBMetadataExtractionException;
import it.unibz.inf.ontop.exception.MappingException;
import it.unibz.inf.ontop.exception.OntologyException;
import it.unibz.inf.ontop.spec.OBDASpecInput;
import it.unibz.inf.ontop.spec.OBDASpecification;
import it.unibz.inf.ontop.spec.mapping.Mapping;
import it.unibz.inf.ontop.spec.mapping.transformer.MappingTransformer;
import it.unibz.inf.ontop.spec.ontology.Ontology;
import it.unibz.inf.ontop.spec.ontology.TBoxReasoner;
import it.unibz.inf.ontop.temporal.model.DatalogMTLProgram;

public interface TemporalMappingTransformer extends MappingTransformer {

    OBDASpecification transform(OBDASpecInput specInput, Mapping mapping, DBMetadata dbMetadata,
                                Ontology ontology, TBoxReasoner tBox, Mapping temporalMapping, DBMetadata temporalDBMetadata, DatalogMTLProgram datalogMTLProgram)
            throws MappingException, OntologyException, DBMetadataExtractionException;
}