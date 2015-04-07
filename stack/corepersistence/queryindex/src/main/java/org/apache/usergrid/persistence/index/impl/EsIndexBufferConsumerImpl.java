/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  *  contributor license agreements.  The ASF licenses this file to You
 *  * under the Apache License, Version 2.0 (the "License"); you may not
 *  * use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.  For additional information regarding
 *  * copyright in this work, please see the NOTICE file in the top level
 *  * directory of this distribution.
 *
 */
package org.apache.usergrid.persistence.index.impl;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.usergrid.persistence.core.metrics.MetricsFactory;
import org.apache.usergrid.persistence.index.IndexFig;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.schedulers.Schedulers;


/**
 * Consumer for IndexOperationMessages
 */
@Singleton
public class EsIndexBufferConsumerImpl implements IndexBufferConsumer {
    private static final Logger log = LoggerFactory.getLogger(EsIndexBufferConsumerImpl.class);

    private final IndexFig config;
    private final FailureMonitorImpl failureMonitor;
    private final Client client;

    private final Timer flushTimer;
    private final Counter indexSizeCounter;
    private final Counter indexErrorCounter;
    private final Meter flushMeter;
    private final Timer produceTimer;
    private final BufferQueue bufferQueue;
    private final IndexFig indexFig;
    private final AtomicLong counter = new AtomicLong(  );

    //the actively running subscription
    private List<Subscription> subscriptions;

    private Object mutex = new Object();


    private AtomicLong inFlight = new AtomicLong(  );

    @Inject
    public EsIndexBufferConsumerImpl( final IndexFig config, final EsProvider provider, final MetricsFactory
        metricsFactory, final BufferQueue bufferQueue, final IndexFig indexFig ){

        this.flushTimer = metricsFactory.getTimer(EsIndexBufferConsumerImpl.class, "buffer.flush");
        this.flushMeter = metricsFactory.getMeter(EsIndexBufferConsumerImpl.class, "buffer.meter");
        this.indexSizeCounter =  metricsFactory.getCounter(EsIndexBufferConsumerImpl.class, "buffer.size");
        this.indexErrorCounter =  metricsFactory.getCounter(EsIndexBufferConsumerImpl.class, "error.count");

        //wire up the gauge of inflight messages
        metricsFactory.addGauge( EsIndexBufferConsumerImpl.class, "inflight.meter", new Gauge<Long>() {
            @Override
            public Long getValue() {
                return inFlight.longValue();
            }
        } );



        this.config = config;
        this.failureMonitor = new FailureMonitorImpl(config,provider);
        this.client = provider.getClient();
        this.produceTimer = metricsFactory.getTimer(EsIndexBufferConsumerImpl.class,"index.buffer.consumer.messageFetch");
        this.bufferQueue = bufferQueue;
        this.indexFig = indexFig;

        subscriptions = new ArrayList<>( indexFig.getWorkerCount() );

        //batch up sets of some size and send them in batch
          start();
    }


    /**
     * Loop throught and start the workers
     */
    public void start() {
        final int count = indexFig.getWorkerCount();

        for(int i = 0; i < count; i ++){
            startWorker();
        }
    }


    /**
     * Stop the workers
     */
    public void stop() {
        synchronized ( mutex ) {
            //stop consuming

            for(final Subscription subscription: subscriptions){
                subscription.unsubscribe();
            }
        }
    }


    private void startWorker(){
        synchronized ( mutex) {

            Observable<List<IndexIdentifierImpl.IndexOperationMessage>> consumer = Observable.create(
                new Observable.OnSubscribe<List<IndexIdentifierImpl.IndexOperationMessage>>() {
                    @Override
                    public void call( final Subscriber<? super List<IndexIdentifierImpl.IndexOperationMessage>> subscriber ) {

                        //name our thread so it's easy to see
                        Thread.currentThread().setName( "QueueConsumer_" + counter.incrementAndGet() );


                        List<IndexIdentifierImpl.IndexOperationMessage> drainList = null;

                        do {

                            Timer.Context timer = produceTimer.time();


                            try {


                                drainList = bufferQueue
                                    .take( config.getIndexBufferSize(), config.getIndexBufferTimeout(),
                                        TimeUnit.MILLISECONDS );


                                subscriber.onNext( drainList );

                                //take since  we're in flight
                                inFlight.addAndGet( drainList.size() );


                                timer.stop();
                            }
                            //DO NOT add any doOnError* functions to this subscription.  We want the producer
                            //to receive these exceptions and sleep before a retry
                            catch ( Throwable t ) {
                                final long sleepTime = config.getFailureRetryTime();

                                log.error( "Failed to dequeue.  Sleeping for {} milliseconds", sleepTime, t );

                                if ( drainList != null ) {
                                    inFlight.addAndGet( -1 * drainList.size() );
                                }

                                try {
                                    Thread.sleep( sleepTime );
                                }
                                catch ( InterruptedException ie ) {
                                    //swallow
                                }

                                indexErrorCounter.inc();
                            }
                        }
                        while ( true );
                    }
                } ).doOnNext( containerList -> {
                    if ( containerList.size() == 0 ) {
                        return;
                    }

                    flushMeter.mark(containerList.size());
                    Timer.Context time = flushTimer.time();


                    execute(containerList);

                    time.stop();
                } )
                //ack after we process
                .doOnNext( indexOperationMessages -> {
                    bufferQueue.ack( indexOperationMessages );
                    //release  so we know we've done processing
                    inFlight.addAndGet( -1 * indexOperationMessages.size() );
                } ).subscribeOn( Schedulers.newThread() );

            //start in the background

           final Subscription subscription = consumer.subscribe();

            subscriptions.add(subscription );
        }
    }


    /**
     * Execute the request, check for errors, then re-init the batch for future use
     */
    private void execute( final List<IndexIdentifierImpl.IndexOperationMessage> operationMessages ) {

        if ( operationMessages == null || operationMessages.size() == 0 ) {
            return;
        }

        //process and flatten all the messages to builder requests
        //batch shard operations into a bulk request
        Observable.from( operationMessages ).flatMap( indexOperationMessage -> {
            final Observable<IndexRequest> index = Observable.from( indexOperationMessage.getIndexRequests() );
            final Observable<DeIndexRequest> deIndex = Observable.from( indexOperationMessage.getDeIndexRequests() );

            indexSizeCounter.dec( indexOperationMessage.getDeIndexRequests().size() );
            indexSizeCounter.dec( indexOperationMessage.getIndexRequests().size() );

            return Observable.merge( index, deIndex );
        } )
            //collection all the operations into a single stream
            .collect( () -> initRequest(), ( bulkRequestBuilder, batchRequest ) -> {
                batchRequest.doOperation( client, bulkRequestBuilder );
            } )  //send the request off to ES
            .doOnNext( bulkRequestBuilder -> sendRequest( bulkRequestBuilder ) ).toBlocking().lastOrDefault( null );

        //call back all futures
        Observable.from( operationMessages ).doOnNext( operationMessage -> operationMessage.getFuture().done() ).toBlocking().lastOrDefault( null );
    }


    /**
     * initialize request
     * @return
     */
    private BulkRequestBuilder initRequest() {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        bulkRequest.setConsistencyLevel(WriteConsistencyLevel.fromString(config.getWriteConsistencyLevel()));
        bulkRequest.setRefresh(config.isForcedRefresh());
        return bulkRequest;
    }

    /**
     * send bulk request
     * @param bulkRequest
     */
    private void sendRequest(BulkRequestBuilder bulkRequest) {
        //nothing to do, we haven't added anything to the index
        if (bulkRequest.numberOfActions() == 0) {
            return;
        }

        final BulkResponse responses;


        try {
            responses = bulkRequest.execute().actionGet();
        } catch (Throwable t) {
            log.error("Unable to communicate with elasticsearch");
            failureMonitor.fail("Unable to execute batch", t);
            throw t;
        }

        failureMonitor.success();

        boolean error = false;

        for (BulkItemResponse response : responses) {

            if (response.isFailed()) {
                // log error and continue processing
                log.error("Unable to index id={}, type={}, index={}, failureMessage={} ",
                    response.getId(),
                    response.getType(),
                    response.getIndex(),
                    response.getFailureMessage()
                );

                error = true;
            }
        }

        if ( error ) {
            throw new RuntimeException("Error during processing of bulk index operations one of the responses failed.  Check previous log entries");
        }
    }
}
