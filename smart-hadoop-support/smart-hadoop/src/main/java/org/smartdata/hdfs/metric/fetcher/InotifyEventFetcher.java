/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.hdfs.metric.fetcher;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.squareup.tape.QueueFile;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSInotifyEventInputStream;
import org.apache.hadoop.hdfs.inotify.EventBatch;
import org.apache.hadoop.hdfs.inotify.MissingEventsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartdata.SmartConstants;
import org.smartdata.conf.SmartConf;
import org.smartdata.metastore.MetaStore;
import org.smartdata.metastore.MetaStoreException;
import org.smartdata.model.SystemInfo;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class InotifyEventFetcher {
  private final DFSClient client;
  private final NamespaceFetcher nameSpaceFetcher;
  private final ScheduledExecutorService scheduledExecutorService;
  private final InotifyEventApplier applier;
  private final MetaStore metaStore;
  private Callable finishedCallback;
  private ScheduledFuture inotifyFetchFuture;
  private ScheduledFuture fetchAndApplyFuture;
  private EventApplyTask eventApplyTask;
  private java.io.File inotifyFile;
  private QueueFile queueFile;
  private SmartConf conf;
  public static final Logger LOG =
      LoggerFactory.getLogger(InotifyEventFetcher.class);

  public InotifyEventFetcher(DFSClient client, MetaStore metaStore,
      ScheduledExecutorService service, Callable callBack) {
    this(client, metaStore, service, new InotifyEventApplier(metaStore, client), callBack, new SmartConf());
  }

  public InotifyEventFetcher(DFSClient client, MetaStore metaStore,
      ScheduledExecutorService service, Callable callBack, SmartConf conf) {
    this(client, metaStore, service, new InotifyEventApplier(metaStore, client), callBack, conf);
  }

  public InotifyEventFetcher(DFSClient client, MetaStore metaStore,
      ScheduledExecutorService service, InotifyEventApplier applier, Callable callBack) {
    this.client = client;
    this.applier = applier;
    this.metaStore = metaStore;
    this.scheduledExecutorService = service;
    this.finishedCallback = callBack;
    this.nameSpaceFetcher = new NamespaceFetcher(client, metaStore, service);
    this.conf = new SmartConf();
  }

  public InotifyEventFetcher(DFSClient client, MetaStore metaStore,
      ScheduledExecutorService service, InotifyEventApplier applier, Callable callBack, SmartConf conf) {
    this.client = client;
    this.applier = applier;
    this.metaStore = metaStore;
    this.scheduledExecutorService = service;
    this.finishedCallback = callBack;
    this.conf = conf;
    this.nameSpaceFetcher = new NamespaceFetcher(client, metaStore, service,conf);
  }

  public void start() throws IOException {
    Long lastTxid = getLastTxid();
    if (lastTxid != null && lastTxid != -1 && canContinueFromLastTxid(client, lastTxid)) {
      startFromLastTxid(lastTxid);
    } else {
      startWithFetchingNameSpace();
    }
  }

  @VisibleForTesting
  static boolean canContinueFromLastTxid(DFSClient client, Long lastId) {
    try {
      if (client.getNamenode().getCurrentEditLogTxid() == lastId) {
        return true;
      }
      DFSInotifyEventInputStream is = client.getInotifyEventStream(lastId);
      EventBatch eventBatch = is.poll();
      return eventBatch != null;
    } catch (Exception e) {
      return false;
    }
  }

  private Long getLastTxid() {
    try {
      SystemInfo info =
          metaStore.getSystemInfoByProperty(SmartConstants.SMART_HADOOP_LAST_INOTIFY_TXID);
      return info != null ? Long.parseLong(info.getValue()) : -1L;
    } catch (MetaStoreException e) {
      return -1L;
    }
  }

  private void startWithFetchingNameSpace() throws IOException {
    ListeningExecutorService listeningExecutorService = MoreExecutors.listeningDecorator(scheduledExecutorService);
    inotifyFile = new File("/tmp/inotify" + new Random().nextLong());
    queueFile = new QueueFile(inotifyFile);
    long startId = client.getNamenode().getCurrentEditLogTxid();
    LOG.info("Start fetching namespace with current edit log txid = " + startId);
    nameSpaceFetcher.startFetch();
    inotifyFetchFuture = scheduledExecutorService.scheduleAtFixedRate(
      new InotifyFetchTask(queueFile, client, startId), 0, 100, TimeUnit.MILLISECONDS);
    eventApplyTask = new EventApplyTask(nameSpaceFetcher, applier, queueFile, startId);
    ListenableFuture<?> future = listeningExecutorService.submit(eventApplyTask);
    Futures.addCallback(future, new NameSpaceFetcherCallBack(), scheduledExecutorService);
    LOG.info("Start apply iNotify events.");
  }

  private void startFromLastTxid(long lastId) throws IOException {
    LOG.info("Skipped fetching Name Space, start applying inotify events from " + lastId);
    submitFetchAndApplyTask(lastId);
    try {
      finishedCallback.call();
    } catch (Exception e) {
      LOG.error("Call back failed", e);
    }
  }

  private void submitFetchAndApplyTask(long lastId) throws IOException {
    fetchAndApplyFuture =
        scheduledExecutorService.scheduleAtFixedRate(
            new InotifyFetchAndApplyTask(client, metaStore, applier, lastId),
            0,
            100,
            TimeUnit.MILLISECONDS);
  }

  private class NameSpaceFetcherCallBack implements FutureCallback<Object> {

    @Override
    public void onSuccess(@Nullable Object o) {
      inotifyFetchFuture.cancel(false);
      nameSpaceFetcher.stop();
      try {
        queueFile.close();
        submitFetchAndApplyTask(eventApplyTask.getLastId());
        LOG.info("Name space fetch finished.");
        finishedCallback.call();
      } catch (Exception e) {
        LOG.error("Call back failed", e);
      }
    }

    @Override
    public void onFailure(Throwable throwable) {
      LOG.error("NameSpaceFetcher failed", throwable);
    }
  }

  public void stop() {
    if (inotifyFile != null) {
      inotifyFile.delete();
    }
    if (inotifyFetchFuture != null) {
      inotifyFetchFuture.cancel(false);
    }
    if (fetchAndApplyFuture != null){
      fetchAndApplyFuture.cancel(false);
    }
  }

  private static class InotifyFetchTask implements Runnable {
    private final QueueFile queueFile;
    private DFSInotifyEventInputStream inotifyEventInputStream;

    public InotifyFetchTask(QueueFile queueFile, DFSClient client, long startId) throws IOException {
      this.queueFile = queueFile;
      this.inotifyEventInputStream = client.getInotifyEventStream(startId);
    }

    @Override
    public void run() {
      try {
        EventBatch eventBatch = inotifyEventInputStream.poll();
        while (eventBatch != null) {
          this.queueFile.add(EventBatchSerializer.serialize(eventBatch));
          eventBatch = inotifyEventInputStream.poll();
        }
      } catch (IOException | MissingEventsException e) {
        LOG.error("Inotify enqueue error", e);
      }
    }
  }

  private static class EventApplyTask implements Runnable {
    private final NamespaceFetcher namespaceFetcher;
    private final InotifyEventApplier applier;
    private final QueueFile queueFile;
    private long lastId;

    public EventApplyTask(NamespaceFetcher namespaceFetcher, InotifyEventApplier applier,
        QueueFile queueFile, long lastId) {
      this.namespaceFetcher = namespaceFetcher;
      this.queueFile = queueFile;
      this.applier = applier;
      this.lastId = lastId;
    }

    @Override
    public void run() {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          if (!namespaceFetcher.fetchFinished()) {
            Thread.sleep(100);
          } else {
            while (!queueFile.isEmpty()) {
              EventBatch batch = EventBatchSerializer.deserialize(queueFile.peek());
              queueFile.remove();
              this.applier.apply(batch.getEvents());
              this.lastId = batch.getTxid();
            }
            break;
          }
        }
      } catch (InterruptedException | IOException | MetaStoreException e) {
        LOG.error("Inotify dequeue error", e);
      }
    }

    public long getLastId() {
      return this.lastId;
    }
  }
}
