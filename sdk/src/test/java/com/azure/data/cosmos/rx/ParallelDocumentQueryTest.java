/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.azure.data.cosmos.rx;

import com.azure.data.cosmos.*;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import com.azure.data.cosmos.CosmosClientException;
import com.azure.data.cosmos.directconnectivity.Protocol;

import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import com.azure.data.cosmos.internal.routing.Range;
import com.azure.data.cosmos.internal.Utils.ValueHolder;
import com.azure.data.cosmos.internal.query.CompositeContinuationToken;

import io.reactivex.subscribers.TestSubscriber;
import reactor.core.publisher.Flux;
import rx.Observable;

import java.util.Map;

public class ParallelDocumentQueryTest extends TestSuiteBase {
    private CosmosDatabase createdDatabase;
    private CosmosContainer createdCollection;
    private List<CosmosItemProperties> createdDocuments;

    private CosmosClient client;

    public String getCollectionLink() {
        return Utils.getCollectionNameLink(createdDatabase.id(), createdCollection.id());
    }

    @Factory(dataProvider = "clientBuildersWithDirect")
    public ParallelDocumentQueryTest(CosmosClientBuilder clientBuilder) {
        this.clientBuilder = clientBuilder;
    }

    @DataProvider(name = "queryMetricsArgProvider")
    public Object[][] queryMetricsArgProvider() {
        return new Object[][]{
                {true},
                {false},
        };
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT, dataProvider = "queryMetricsArgProvider")
    public void queryDocuments(boolean qmEnabled) {
        String query = "SELECT * from c where c.prop = 99";
        FeedOptions options = new FeedOptions();
        options.maxItemCount(5);
        options.enableCrossPartitionQuery(true);
        options.populateQueryMetrics(qmEnabled);
        options.maxDegreeOfParallelism(2);
        Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);

        List<CosmosItemProperties> expectedDocs = createdDocuments.stream().filter(d -> 99 == d.getInt("prop") ).collect(Collectors.toList());
        assertThat(expectedDocs).isNotEmpty();

        FeedResponseListValidator<CosmosItemProperties> validator = new FeedResponseListValidator.Builder<CosmosItemProperties>()
                .totalSize(expectedDocs.size())
                .exactlyContainsInAnyOrder(expectedDocs.stream().map(d -> d.resourceId()).collect(Collectors.toList()))
                .allPagesSatisfy(new FeedResponseValidator.Builder<CosmosItemProperties>()
                        .requestChargeGreaterThanOrEqualTo(1.0).build())
                .hasValidQueryMetrics(qmEnabled)
                .build();

        try {
            validateQuerySuccess(queryObservable, validator, TIMEOUT);
        } catch (Throwable error) {
            if (this.clientBuilder.getConfigs().getProtocol() == Protocol.TCP) {
                String message = String.format(String.format("DIRECT TCP test failure: desiredConsistencyLevel=%s", this.clientBuilder.getDesiredConsistencyLevel()));
                logger.info(message, error);
                throw new SkipException(message, error);
            }
            throw error;
        }
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void queryMetricEquality() throws Exception {
        String query = "SELECT * from c where c.prop = 99";
        FeedOptions options = new FeedOptions();
        options.maxItemCount(5);
        options.enableCrossPartitionQuery(true);
        options.populateQueryMetrics(true);
        options.maxDegreeOfParallelism(0);

        Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);
        List<FeedResponse<CosmosItemProperties>> resultList1 = queryObservable.collectList().block();

        options.maxDegreeOfParallelism(4);
        Flux<FeedResponse<CosmosItemProperties>> threadedQueryObs = createdCollection.queryItems(query, options);
        List<FeedResponse<CosmosItemProperties>> resultList2 = threadedQueryObs.collectList().block();

        assertThat(resultList1.size()).isEqualTo(resultList2.size());
        for(int i = 0; i < resultList1.size(); i++){
            compareQueryMetrics(resultList1.get(i).queryMetrics(), resultList2.get(i).queryMetrics());
        }
    }

    private void compareQueryMetrics(Map<String, QueryMetrics> qm1, Map<String, QueryMetrics> qm2) {
        assertThat(qm1.keySet().size()).isEqualTo(qm2.keySet().size());
        QueryMetrics queryMetrics1 = BridgeInternal.createQueryMetricsFromCollection(qm1.values());
        QueryMetrics queryMetrics2 = BridgeInternal.createQueryMetricsFromCollection(qm2.values());
        assertThat(queryMetrics1.getRetrievedDocumentSize()).isEqualTo(queryMetrics2.getRetrievedDocumentSize());
        assertThat(queryMetrics1.getRetrievedDocumentCount()).isEqualTo(queryMetrics2.getRetrievedDocumentCount());
        assertThat(queryMetrics1.getIndexHitDocumentCount()).isEqualTo(queryMetrics2.getIndexHitDocumentCount());
        assertThat(queryMetrics1.getOutputDocumentCount()).isEqualTo(queryMetrics2.getOutputDocumentCount());
        assertThat(queryMetrics1.getOutputDocumentSize()).isEqualTo(queryMetrics2.getOutputDocumentSize());
        assertThat(BridgeInternal.getClientSideMetrics(queryMetrics1).getRequestCharge())
                .isEqualTo(BridgeInternal.getClientSideMetrics(queryMetrics1).getRequestCharge());
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void queryDocuments_NoResults() {
        String query = "SELECT * from root r where r.id = '2'";
        FeedOptions options = new FeedOptions();
        options.enableCrossPartitionQuery(true);
        Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);

        FeedResponseListValidator<CosmosItemProperties> validator = new FeedResponseListValidator.Builder<CosmosItemProperties>()
                .containsExactly(new ArrayList<>())
                .numberOfPagesIsGreaterThanOrEqualTo(1)
                .allPagesSatisfy(new FeedResponseValidator.Builder<CosmosItemProperties>()
                                         .pageSizeIsLessThanOrEqualTo(0)
                                         .requestChargeGreaterThanOrEqualTo(1.0).build())
                .build();
        validateQuerySuccess(queryObservable, validator);
    }

    // TODO: DANOBLE: Investigate DIRECT TCP performance issue
    // See: https://msdata.visualstudio.com/CosmosDB/_workitems/edit/367028https://msdata.visualstudio.com/CosmosDB/_workitems/edit/367028

    @Test(groups = { "simple" }, timeOut = 2 * TIMEOUT)
    public void queryDocumentsWithPageSize() {
        String query = "SELECT * from root";
        FeedOptions options = new FeedOptions();
        int pageSize = 3;
        options.maxItemCount(pageSize);
        options.maxDegreeOfParallelism(-1);
        options.enableCrossPartitionQuery(true);
        Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);

        List<CosmosItemProperties> expectedDocs = createdDocuments;
        assertThat(expectedDocs).isNotEmpty();

        FeedResponseListValidator<CosmosItemProperties> validator = new FeedResponseListValidator
                .Builder<CosmosItemProperties>()
                .exactlyContainsInAnyOrder(expectedDocs
                        .stream()
                        .map(d -> d.resourceId())
                        .collect(Collectors.toList()))
                .numberOfPagesIsGreaterThanOrEqualTo((expectedDocs.size() + 1) / 3)
                .allPagesSatisfy(new FeedResponseValidator.Builder<CosmosItemProperties>()
                                         .requestChargeGreaterThanOrEqualTo(1.0)
                                         .pageSizeIsLessThanOrEqualTo(pageSize)
                                         .build())
                .build();
        try {
            validateQuerySuccess(queryObservable, validator, 2 * subscriberValidationTimeout);
        } catch (Throwable error) {
            if (this.clientBuilder.getConfigs().getProtocol() == Protocol.TCP) {
                String message = String.format("DIRECT TCP test failure ignored: desiredConsistencyLevel=%s", this.clientBuilder.getDesiredConsistencyLevel());
                logger.info(message, error);
                throw new SkipException(message, error);
            }
            throw error;
        }
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void invalidQuerySyntax() {
        String query = "I am an invalid query";
        FeedOptions options = new FeedOptions();
        options.enableCrossPartitionQuery(true);
        Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);

        FailureValidator validator = new FailureValidator.Builder()
                .instanceOf(CosmosClientException.class)
                .statusCode(400)
                .notNullActivityId()
                .build();
        validateQueryFailure(queryObservable, validator);
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void crossPartitionQueryNotEnabled() {
        String query = "SELECT * from root";
        FeedOptions options = new FeedOptions();
        Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);

        FailureValidator validator = new FailureValidator.Builder()
                .instanceOf(CosmosClientException.class)
                .statusCode(400)
                .build();
        validateQueryFailure(queryObservable, validator);
    }

    // TODO: DANOBLE: Investigate DIRECT TCP performance issue
    // Links: 
    // https://msdata.visualstudio.com/CosmosDB/_workitems/edit/367028https://msdata.visualstudio.com/CosmosDB/_workitems/edit/367028

    @Test(groups = { "simple" }, timeOut = 2 * TIMEOUT)
    public void partitionKeyRangeId() {
        int sum = 0;
        try {
            for (String partitionKeyRangeId : CosmosBridgeInternal.getAsyncDocumentClient(client).readPartitionKeyRanges(getCollectionLink(), null)
                .flatMap(p -> Observable.from(p.results()))
                .map(pkr -> pkr.id()).toList().toBlocking().single()) {
                String query = "SELECT * from root";
                FeedOptions options = new FeedOptions();
                options.partitionKeyRangeIdInternal(partitionKeyRangeId);
                int queryResultCount = createdCollection.queryItems(query, options)
                    .flatMap(p -> Flux.fromIterable(p.results()))
                    .collectList().block().size();

                sum += queryResultCount;
            }
        } catch (Throwable error) {
            if (this.clientBuilder.getConfigs().getProtocol() == Protocol.TCP) {
                String message = String.format("DIRECT TCP test failure ignored: desiredConsistencyLevel=%s", this.clientBuilder.getDesiredConsistencyLevel());
                logger.info(message, error);
                throw new SkipException(message, error);
            }
            throw error;
        }

        assertThat(sum).isEqualTo(createdDocuments.size());
    }
    
    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void compositeContinuationTokenRoundTrip() throws Exception {
    	{
    		// Positive
    		CompositeContinuationToken compositeContinuationToken = new CompositeContinuationToken("asdf",
    		        new Range<String>("A", "D", false, true));
    		String serialized = compositeContinuationToken.toString();
    		ValueHolder<CompositeContinuationToken> outCompositeContinuationToken = new ValueHolder<CompositeContinuationToken>();
    		boolean succeeed = CompositeContinuationToken.tryParse(serialized, outCompositeContinuationToken);
    		assertThat(succeeed).isTrue();
    		CompositeContinuationToken deserialized = outCompositeContinuationToken.v;
    		String token = deserialized.getToken();
    		Range<String> range = deserialized.getRange();
    		assertThat(token).isEqualTo("asdf");
    		assertThat(range.getMin()).isEqualTo("A");
    		assertThat(range.getMax()).isEqualTo("D");
    		assertThat(range.isMinInclusive()).isEqualTo(false);
    		assertThat(range.isMaxInclusive()).isEqualTo(true);
    	}
    	
    	{
    		// Negative
    		ValueHolder<CompositeContinuationToken> outCompositeContinuationToken = new ValueHolder<CompositeContinuationToken>();
    		boolean succeeed = CompositeContinuationToken.tryParse("{\"property\" : \"not a valid composite continuation token\"}", outCompositeContinuationToken);
    		assertThat(succeeed).isFalse();
    	}
    	
    	{
    		// Negative - GATEWAY composite continuation token
    		ValueHolder<CompositeContinuationToken> outCompositeContinuationToken = new ValueHolder<CompositeContinuationToken>();
    		boolean succeeed = CompositeContinuationToken.tryParse("{\"token\":\"-RID:tZFQAImzNLQLAAAAAAAAAA==#RT:1#TRC:10\",\"range\":{\"min\":\"\",\"max\":\"FF\"}}", outCompositeContinuationToken);
    		assertThat(succeeed).isFalse();
    	}
    }

    //  TODO: This test has been timing out on build, related work item - https://msdata.visualstudio.com/CosmosDB/_workitems/edit/402438/
    @Test(groups = { "non-emulator" }, timeOut = TIMEOUT * 10)
    public void queryDocumentsWithCompositeContinuationTokens() throws Exception {
        String query = "SELECT * FROM c";
        
        // Get Expected
        List<CosmosItemProperties> expectedDocs = createdDocuments
        		.stream()
        		.collect(Collectors.toList());
        assertThat(expectedDocs).isNotEmpty();
        
        this.queryWithContinuationTokensAndPageSizes(query, new int[] {1, 10, 100}, expectedDocs);
    }
	
    // TODO: DANOBLE: Investigate DIRECT TCP performance issue
    // Links:
    // https://msdata.visualstudio.com/CosmosDB/_workitems/edit/367028https://msdata.visualstudio.com/CosmosDB/_workitems/edit/367028
    // Notes:
    // When I've watch this method execute in the debugger and seen that the code sometimes pauses for quite a while in
    // the middle of the second group of 21 documents. I test against a debug instance of the public emulator and so
    // what I'm seeing could be the result of a public emulator performance issue. Of course, it might also be the
    // result of a TCP protocol performance problem.

    @BeforeClass(groups = { "simple", "non-emulator" }, timeOut = 2 * SETUP_TIMEOUT)
    public void beforeClass() {
        client = clientBuilder.build();
        createdDatabase = getSharedCosmosDatabase(client);
        createdCollection = getSharedMultiPartitionCosmosContainer(client);
        truncateCollection(createdCollection);
        List<CosmosItemProperties> docDefList = new ArrayList<>();
        for(int i = 0; i < 13; i++) {
            docDefList.add(getDocumentDefinition(i));
        }

        for(int i = 0; i < 21; i++) {
            docDefList.add(getDocumentDefinition(99));
        }

        createdDocuments = bulkInsertBlocking(createdCollection, docDefList);

        waitIfNeededForReplicasToCatchUp(clientBuilder);
    }

    @AfterClass(groups = { "simple", "non-emulator" }, timeOut = SHUTDOWN_TIMEOUT, alwaysRun = true)
    public void afterClass() {
        safeClose(client);
    }

    private static CosmosItemProperties getDocumentDefinition(int cnt) {
        String uuid = UUID.randomUUID().toString();
        CosmosItemProperties doc = new CosmosItemProperties(String.format("{ "
                + "\"id\": \"%s\", "
                + "\"prop\" : %d, "
                + "\"mypk\": \"%s\", "
                + "\"sgmts\": [[6519456, 1471916863], [2498434, 1455671440]]"
                + "}"
                , uuid, cnt, uuid));
        return doc;
    }

	@Test(groups = { "simple" }, timeOut = TIMEOUT, enabled = false)
	public void invalidQuerySytax() throws Exception {

		String query = "I am an invalid query";
		FeedOptions options = new FeedOptions();
		options.enableCrossPartitionQuery(true);
		Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);

		FailureValidator validator = new FailureValidator.Builder().instanceOf(CosmosClientException.class)
				.statusCode(400).notNullActivityId().build();
		validateQueryFailure(queryObservable, validator);
	}

	public CosmosItemProperties createDocument(CosmosContainer cosmosContainer, int cnt) throws CosmosClientException {

	    CosmosItemProperties docDefinition = getDocumentDefinition(cnt);

		return cosmosContainer.createItem(docDefinition, new CosmosItemProperties()).block().properties();
	}
	
	private void queryWithContinuationTokensAndPageSizes(String query, int[] pageSizes, List<CosmosItemProperties> expectedDocs) {
        for (int pageSize : pageSizes) {
            List<CosmosItemProperties> receivedDocuments = this.queryWithContinuationTokens(query, pageSize);
            List<String> actualIds = new ArrayList<String>();
            for (CosmosItemProperties document : receivedDocuments) {
                actualIds.add(document.resourceId());
            }

            List<String> expectedIds = new ArrayList<String>();
            for (CosmosItemProperties document : expectedDocs) {
                expectedIds.add(document.resourceId());
            }

            assertThat(actualIds).containsOnlyElementsOf(expectedIds);
        }
    }

    private List<CosmosItemProperties> queryWithContinuationTokens(String query, int pageSize) {
        String requestContinuation = null;
        List<String> continuationTokens = new ArrayList<String>();
        List<CosmosItemProperties> receivedDocuments = new ArrayList<CosmosItemProperties>();
        do {
            FeedOptions options = new FeedOptions();
            options.maxItemCount(pageSize);
            options.enableCrossPartitionQuery(true);
            options.maxDegreeOfParallelism(2);
            options.requestContinuation(requestContinuation);
            Flux<FeedResponse<CosmosItemProperties>> queryObservable = createdCollection.queryItems(query, options);

            TestSubscriber<FeedResponse<CosmosItemProperties>> testSubscriber = new TestSubscriber<>();
            queryObservable.subscribe(testSubscriber);
            testSubscriber.awaitTerminalEvent(TIMEOUT, TimeUnit.MILLISECONDS);
            testSubscriber.assertNoErrors();
            testSubscriber.assertComplete();

            FeedResponse<CosmosItemProperties> firstPage = (FeedResponse<CosmosItemProperties>) testSubscriber.getEvents().get(0).get(0);
            requestContinuation = firstPage.continuationToken();
            receivedDocuments.addAll(firstPage.results());
            continuationTokens.add(requestContinuation);
        } while (requestContinuation != null);

        return receivedDocuments;
    }
}