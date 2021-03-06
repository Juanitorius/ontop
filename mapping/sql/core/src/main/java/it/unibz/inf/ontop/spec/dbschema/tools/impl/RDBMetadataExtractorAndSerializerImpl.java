package it.unibz.inf.ontop.spec.dbschema.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import it.unibz.inf.ontop.dbschema.RDBMetadata;
import it.unibz.inf.ontop.dbschema.RDBMetadataExtractionTools;
import it.unibz.inf.ontop.exception.DBMetadataExtractionException;
import it.unibz.inf.ontop.injection.OntopSQLCredentialSettings;
import it.unibz.inf.ontop.model.type.TypeFactory;
import it.unibz.inf.ontop.spec.dbschema.tools.DBMetadataExtractorAndSerializer;
import it.unibz.inf.ontop.utils.LocalJDBCConnectionUtils;

import java.sql.Connection;
import java.sql.SQLException;

public class RDBMetadataExtractorAndSerializerImpl implements DBMetadataExtractorAndSerializer {

    private final OntopSQLCredentialSettings settings;
    private final TypeFactory typeFactory;

    @Inject
    private RDBMetadataExtractorAndSerializerImpl(OntopSQLCredentialSettings settings, TypeFactory typeFactory) {
        this.settings = settings;
        this.typeFactory = typeFactory;
    }

    @Override
    public String extractAndSerialize() throws DBMetadataExtractionException {

        try (Connection localConnection = LocalJDBCConnectionUtils.createConnection(settings)) {
            RDBMetadata metadata = RDBMetadataExtractionTools.createMetadata(localConnection, typeFactory);
            RDBMetadataExtractionTools.loadMetadata(metadata, localConnection, null);

            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
            return jsonString;

        } catch (SQLException e) {
            throw new DBMetadataExtractionException("Connection problem while extracting the metadata.\n" + e);
        } catch (JsonProcessingException e) {
            throw new DBMetadataExtractionException("problem with JSON processing.\n" + e);
        }
    }
}
