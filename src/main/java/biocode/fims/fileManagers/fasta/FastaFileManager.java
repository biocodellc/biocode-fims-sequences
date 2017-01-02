package biocode.fims.fileManagers.fasta;

import biocode.fims.bcid.ResourceTypes;
import biocode.fims.digester.Attribute;
import biocode.fims.digester.Entity;
import biocode.fims.digester.Mapping;
import biocode.fims.entities.Bcid;
import biocode.fims.entities.Expedition;
import biocode.fims.fasta.FastaData;
import biocode.fims.fileManagers.AuxilaryFileManager;
import biocode.fims.fimsExceptions.ServerErrorException;
import biocode.fims.renderers.RowMessage;
import biocode.fims.run.ProcessController;
import biocode.fims.service.BcidService;
import biocode.fims.service.ExpeditionService;
import biocode.fims.settings.PathManager;
import biocode.fims.settings.SettingsManager;
import biocode.fims.utils.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AuxilaryFileManger implementation to handle fasta sequences
 */
public class FastaFileManager implements AuxilaryFileManager {
    private static final Logger logger = LoggerFactory.getLogger(FastaFileManager.class);

    public static final String ENTITY_CONCEPT_ALIAS = "fastaSequence";
    public static final String SEQUENCE_ATTRIBUTE_URI = "urn:sequence";
    public static final String NAME = "fasta";
    public static final String DATASET_RESOURCE_SUB_TYPE = "Fasta";

    private final FastaPersistenceManager persistenceManager;
    private final SettingsManager settingsManager;
    private final BcidService bcidService;
    private final ExpeditionService expeditionService;

    private ProcessController processController;
    private Map<String, List<JSONObject>> fastaSequences = new HashMap<>();
    private List<FastaData> fastaDataList = new ArrayList<>();
    private Entity entity;

    public FastaFileManager(FastaPersistenceManager persistenceManager, SettingsManager settingsManager,
                            BcidService bcidService, ExpeditionService expeditionService) {
        this.persistenceManager = persistenceManager;
        this.settingsManager = settingsManager;
        this.bcidService = bcidService;
        this.expeditionService = expeditionService;
    }

    /**
     * verify that the identifiers in the fasta file are in a dataset.
     */
    @Override
    public boolean validate(JSONArray dataset) {
        Assert.notNull(processController);
        boolean valid = true;

        if (!fastaDataList.isEmpty()) {

            List<String> sampleIds = getUniqueIds(dataset);

            if (sampleIds.isEmpty()) {
                processController.addMessage(
                        processController.getMapping().getDefaultSheetName(),
                        new RowMessage("No sample data found", "Spreadsheet check", RowMessage.ERROR)
                );
                return false;
            }

            for (FastaData fastaData : fastaDataList) {
                String uniqueKey = "[" + String.valueOf(fastaData.getMetadata().get(entity.getUniqueKey())) + "] " + entity.getUniqueKey();

                processController.appendStatus("\nRunning FASTA validation " + uniqueKey);

                // parse the FASTA file, setting the fastaSequences object
                Map<String, List<JSONObject>> fastaSequences = parseFasta(fastaData);

                if (fastaSequences.isEmpty()) {
                    processController.addMessage(
                            "FASTA: " + uniqueKey,
                            new RowMessage("No data found", "FASTA check", RowMessage.ERROR)
                    );
                    valid = false;
                }

                // verify that all fastaIds exist in the dataset
                ArrayList<String> invalidIds = new ArrayList<>();
                for (String identifier : fastaSequences.keySet()) {
                    if (!sampleIds.contains(identifier)) {
                        invalidIds.add(identifier);
                    }
                }

                if (!invalidIds.isEmpty()) {
                    int level;
                    // this is an error if no ids exist in the dataset
                    if (invalidIds.size() == fastaSequences.size()) {
                        level = RowMessage.ERROR;
                    } else {
                        level = RowMessage.WARNING;
                        processController.setHasWarnings(true);
                    }
                    processController.addMessage(
                            "FASTA: " + uniqueKey,
                            new RowMessage(StringUtils.join(invalidIds, ", "),
                                    "The following sequences exist in the FASTA file, but not the dataset.", level)
                    );
                    if (level == RowMessage.ERROR) {
                        valid = false;
                    }
                }

                fastaSequences.entrySet()
                        .forEach(e -> this.fastaSequences.merge(
                                e.getKey(), e.getValue(), (l1, l2) -> Stream.concat(l1.stream(), l2.stream())
                                        .collect(Collectors.toList())
                                )
                        );
            }
        }

        return valid;
    }


    @Override
    public void upload(boolean newDataset) {
        persistenceManager.upload(processController, fastaSequences, newDataset);

        if (!fastaDataList.isEmpty()) {
            // save the file on the server
            for (FastaData fastaData : fastaDataList) {

                Bcid bcid = new Bcid.BcidBuilder(ResourceTypes.DATASET_RESOURCE_TYPE)
                        .ezidRequest(Boolean.parseBoolean(settingsManager.retrieveValue("ezidRequests")))
                        .title("Fasta Dataset: " + processController.getExpeditionCode())
                        .subResourceType(DATASET_RESOURCE_SUB_TYPE)
                        .finalCopy(processController.getFinalCopy())
                        .build();

                bcidService.create(bcid, processController.getUserId());

                Expedition expedition = expeditionService.getExpedition(
                        processController.getExpeditionCode(),
                        processController.getProjectId()
                );

                bcidService.attachBcidToExpedition(
                        bcid,
                        expedition.getExpeditionId()
                );

                File inputFile = new File(fastaData.getFilename());
                String ext = FileUtils.getExtension(inputFile.getName(), null);
                String filename = "fasta_bcid_id_" + bcid.getBcidId() + "." + ext;
                File outputFile = PathManager.createUniqueFile(filename, settingsManager.retrieveValue("serverRoot"));

                try {

                    Files.copy(inputFile.toPath(), outputFile.toPath());

                    bcid.setSourceFile(filename);
                    bcidService.update(bcid);

                } catch (IOException e) {
                    logger.error("failed to save fasta input file {}", filename);
                }
            }
        }
    }

    /**
     * add the fastaSequence entities to the dataset for indexing
     */
    @Override
    public void index(JSONArray dataset) {
        Mapping mapping = processController.getMapping();
        String uniqueKey = mapping.lookupUriForColumn(mapping.getDefaultSheetUniqueKey(), mapping.getDefaultSheetAttributes());

        mergeFastaSequences(dataset);

        if (!fastaSequences.isEmpty()) {

            for (Object o : dataset) {
                JSONObject resource = (JSONObject) o;

                String localIdentifier = String.valueOf(resource.get(uniqueKey));

                if (fastaSequences.containsKey(localIdentifier)) {
                    resource.put(entity.getConceptAlias(), fastaSequences.get(localIdentifier));
                }
            }

        }

    }

    /**
     * merge any existing fastaSequences with and new fastaSequences. merging is done based on the
     * {@link Entity#getUniqueKey()}. we will either overwrite the existing fastaSequence object if
     * the identifier and uniqueKey match, or add the new fastaSequence to the list of existing
     * fastaSequences for the identifier
     */
    private void mergeFastaSequences(JSONArray dataset) {
        // TODO, if this isn't a new dataset, then the fastaSequences will have already be fetched
        Map<String, List<JSONObject>> existingSequences = persistenceManager.getFastaSequences(processController, entity.getConceptAlias());

        for (String identifier : existingSequences.keySet()) {

            if (!fastaSequences.containsKey(identifier)) {

                if (datasetContainsResource(dataset, identifier)) {
                    fastaSequences.put(identifier, existingSequences.get(identifier));
                }

            } else {

                for (JSONObject existingSequence : existingSequences.get(identifier)) {

                    if (!fastaSequencesContainsSequence(identifier, existingSequence)) {
                        fastaSequences.get(identifier).add(existingSequence);
                    }
                }

            }

        }

    }

    /**
     * check if a resource with the same local identifier exists in the dataset
     *
     * @param dataset
     * @param localIdentifier the value to check against the {@link Entity#getUniqueKey()} value for each resource in
     *                        the dataset
     * @return
     */
    private boolean datasetContainsResource(JSONArray dataset, String localIdentifier) {
        // check that the dataset still contains the resource, and add the existingSequences if it does
        Mapping mapping = processController.getMapping();
        String uniqueKeyUri = mapping.lookupUriForColumn(mapping.getDefaultSheetUniqueKey(), mapping.getDefaultSheetAttributes());
        for (Object o : dataset) {
            JSONObject resource = (JSONObject) o;

            if (resource.get(uniqueKeyUri).equals(localIdentifier)) {
                return true;
            }
        }

        return false;
    }

    /**
     * check if fastaSequences contains a sequence with the same identifier and {@link Entity#getUniqueKey()}
     *
     * @param identifier
     * @param sequence
     * @return
     */
    private boolean fastaSequencesContainsSequence(String identifier, JSONObject sequence) {
        String uniqueKey = processController.getMapping().lookupUriForColumn(entity.getUniqueKey(), entity.getAttributes());

        for (JSONObject newSequence : fastaSequences.get(identifier)) {

            if (StringUtils.equals(
                    String.valueOf(newSequence.get(uniqueKey)),
                    String.valueOf(sequence.get(uniqueKey))
            )) {

                return true;

            }
        }

        return false;
    }

    private List<String> getUniqueIds(JSONArray dataset) {
        List<String> sampleIds = new ArrayList<>();
        Mapping mapping = processController.getMapping();

        String uniqueKey = mapping.getDefaultSheetUniqueKey();

        for (Object obj : dataset) {
            JSONObject sample = (JSONObject) obj;
            if (sample.containsKey(uniqueKey)) {
                sampleIds.add(String.valueOf(sample.get(uniqueKey)));
            }
        }

        return sampleIds;
    }

    /**
     * parse the fasta file identifier-sequence pairs, populating the fastaSequences property
     */
    private Map<String, List<JSONObject>> parseFasta(FastaData fastaData) {
        Map<String, List<JSONObject>> fastaSequences = new HashMap<>();

        try {
            FileReader input = new FileReader(fastaData.getFilename());
            BufferedReader bufRead = new BufferedReader(input);
            String line;
            String identifier = null;
            String sequence = "";

            while ((line = bufRead.readLine()) != null) {

                // > deliminates the next identifier, sequence block in the fasta file
                if (line.startsWith(">")) {

                    if (!sequence.isEmpty() || identifier != null) {

                        addFastaSequence(identifier, sequence, fastaData.getMetadata(), fastaSequences);

                        // after putting the sequence into the object, reset the sequence
                        sequence = "";

                    }

                    int endIdentifierIndex;

                    if (line.contains(" ")) {
                        endIdentifierIndex = line.indexOf(" ");
                    } else if (line.contains("\n")) {
                        endIdentifierIndex = line.indexOf("\n");
                    } else {
                        endIdentifierIndex = line.length();
                    }

                    // parse the identifier - minus the deliminator
                    identifier = line.substring(1, endIdentifierIndex);

                } else {

                    // if we are here, we are in between 2 identifiers. This means this is all sequence data
                    sequence += line;

                }

            }

            // need to put the last sequence data into the hashmap
            if (identifier != null) {

                addFastaSequence(identifier, sequence, fastaData.getMetadata(), fastaSequences);

            }
        } catch (IOException e) {
            throw new ServerErrorException(e);
        }

        return fastaSequences;
    }

    /**
     * adds a fastaSequence object to the fastaSequences map
     *
     * @param identifier
     * @param sequence
     * @param metadata
     */
    private void addFastaSequence(String identifier, String sequence, JSONObject metadata, Map<String, List<JSONObject>> fastaSequences) {
        List<Attribute> fastaAttributes = entity.getAttributes();

        JSONObject fastaSequence = new JSONObject();
        fastaSequence.put(SEQUENCE_ATTRIBUTE_URI, sequence);

        // currently we are only looking for fastaSequence entity attributes in the FastaData object.
        // in the future, if we don't find an attribute in the FastaData, we should look for the attribute
        // in the fasta definition line https://www.ncbi.nlm.nih.gov/Sequin/modifiers.html
        for (Attribute attribute : fastaAttributes) {

            // sequence is a required column that is already added to the object
            if (!attribute.getUri().equals(SEQUENCE_ATTRIBUTE_URI)) {

                String key = attribute.getColumn();

                if (metadata.containsKey(key)) {
                    fastaSequence.put(attribute.getUri(), metadata.get(key));
                }

            }

        }

        if (!fastaSequences.containsKey(identifier)) {
            List<JSONObject> sequences = new ArrayList<>();

            sequences.add(fastaSequence);
            fastaSequences.put(identifier, sequences);

        } else {
            fastaSequences.get(identifier).add(fastaSequence);
        }

    }

    @Override
    public String getName() {
        return NAME;
    }

    public void setFastaData(List<FastaData> fastaDataList) {
        this.fastaDataList = fastaDataList;
    }

    @Override
    public void setProcessController(ProcessController processController) {
        this.processController = processController;
        entity = processController.getMapping().findEntity(ENTITY_CONCEPT_ALIAS);
    }

    @Override
    public void close() {
        if (!fastaDataList.isEmpty()) {
            for (FastaData fastaData : fastaDataList) {
                new File(fastaData.getFilename()).delete();
            }
        }
    }
}
