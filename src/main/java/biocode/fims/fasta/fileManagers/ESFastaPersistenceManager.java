package biocode.fims.fasta.fileManagers;

import biocode.fims.digester.Mapping;
import biocode.fims.elasticSearch.ElasticSearchIndexer;
import biocode.fims.rest.SpringObjectMapper;
import biocode.fims.run.ProcessController;
import biocode.fims.utils.EmailUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * {@link FastaPersistenceManager to handle uploading to Elastic Search
 */
public class ESFastaPersistenceManager implements FastaPersistenceManager {
    private final Logger logger = LoggerFactory.getLogger(ESFastaPersistenceManager.class);
    private final Client esClient;

    public ESFastaPersistenceManager(Client esClient) {
        this.esClient = esClient;
    }

    @Override
    public void upload(ProcessController processController, Map<String, ArrayNode> fastaSequences, boolean newDataset) {
        // do nothing, as elasticsearch "uploading" is handled in FastaFileManager.index
    }

    @Override
    public Map<String, ArrayNode> getFastaSequences(ProcessController processController, String conceptAlias) {
        Map<String, ArrayNode> fastaSequences = new HashMap<>();
        Mapping mapping = processController.getMapping();
        String uniqueKey = mapping.lookupUriForColumn(mapping.getDefaultSheetUniqueKey(), mapping.getDefaultSheetAttributes());

        SearchRequestBuilder builder = esClient.prepareSearch(String.valueOf(processController.getProjectId()))
                .setTypes(ElasticSearchIndexer.TYPE)
                .setSize(1000)
                .setScroll(new TimeValue(1, TimeUnit.MINUTES))
                .setQuery(QueryBuilders.boolQuery()
                        .must(
                                QueryBuilders.nestedQuery(
                                        conceptAlias,
                                        QueryBuilders.boolQuery()
                                                .filter(
                                                        QueryBuilders.existsQuery(conceptAlias)
                                                ),
                                        ScoreMode.None)
                        ).must(
                                QueryBuilders.matchQuery("expedition.expeditionCode.keyword", processController.getExpeditionCode())
                        )
                )
                .setFetchSource(new String[]{uniqueKey, conceptAlias}, null);
        SearchResponse response = builder.get();


        if (response.status() != RestStatus.OK) {
            logger.warn("FAILED to fetch fasta sequences for ExpeditionCode: {}.\n {}", processController.getExpeditionCode(), response);
            EmailUtils.sendAdminEmail(
                    "FAILED to fetch fasta sequences",
                    "ElasticSearch index may be out of sync with uploaded data for expedition: " + processController.getExpeditionCode() +
                            " and projectId: " + processController.getProjectId()
            );
        } else {

            SpringObjectMapper objectMapper = new SpringObjectMapper();
            do {

                for (SearchHit hit : response.getHits().getHits()) {
                    Map<String, Object> source = hit.getSource();
                    String localIdentifier = String.valueOf(source.get(uniqueKey));

                    ArrayNode sourceSequences = objectMapper.createArrayNode();

                    sourceSequences.addAll((ArrayNode) objectMapper.valueToTree(source.get(conceptAlias)));

                    fastaSequences.put(localIdentifier, sourceSequences);
                }

                response = esClient.prepareSearchScroll(response.getScrollId()).setScroll(new TimeValue(1, TimeUnit.MINUTES)).get();

            } while (response.getHits().getHits().length != 0);

        }

        return fastaSequences;
    }
}