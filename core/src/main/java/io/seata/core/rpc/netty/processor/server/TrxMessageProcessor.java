/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.core.rpc.netty.processor.server;

import io.netty.channel.ChannelHandlerContext;
import io.seata.common.thread.NamedThreadFactory;
import io.seata.common.util.NetUtil;
import io.seata.core.protocol.AbstractMessage;
import io.seata.core.protocol.AbstractResultMessage;
import io.seata.core.protocol.MergeResultMessage;
import io.seata.core.protocol.MergedWarpMessage;
import io.seata.core.protocol.RpcMessage;
import io.seata.core.protocol.transaction.BranchRegisterRequest;
import io.seata.core.protocol.transaction.BranchReportRequest;
import io.seata.core.protocol.transaction.GlobalBeginRequest;
import io.seata.core.protocol.transaction.GlobalCommitRequest;
import io.seata.core.protocol.transaction.GlobalLockQueryRequest;
import io.seata.core.protocol.transaction.GlobalReportRequest;
import io.seata.core.protocol.transaction.GlobalRollbackRequest;
import io.seata.core.protocol.transaction.GlobalStatusRequest;
import io.seata.core.rpc.ChannelManager;
import io.seata.core.rpc.RemotingServer;
import io.seata.core.rpc.RpcContext;
import io.seata.core.rpc.TransactionMessageHandler;
import io.seata.core.rpc.netty.processor.NettyProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * handle client trx message processor.
 * include mergeMessage and single message.
 * <p>
 * Trx message includes the following messages:
 * 1.Request message:
 * {@link TransactionMessageHandler#onRequest}
 * 1) BranchRegisterRequest {@link BranchRegisterRequest}
 * 2) BranchReportRequest {@link BranchReportRequest}
 * 3) GlobalBeginRequest {@link GlobalBeginRequest}
 * 4) GlobalCommitRequest {@link GlobalCommitRequest}
 * 5) GlobalLockQueryRequest {@link GlobalLockQueryRequest}
 * 6) GlobalReportRequest {@link GlobalReportRequest}
 * 7) GlobalRollbackRequest {@link GlobalRollbackRequest}
 * 8) GlobalStatusRequest {@link GlobalStatusRequest}
 * <p>
 * 2.Response message:
 * {@link TransactionMessageHandler#onResponse}
 * not yet
 *
 * @author zhangchenghui.dev@gmail.com
 * @since 1.2.0
 */
public class TrxMessageProcessor implements NettyProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrxMessageProcessor.class);

    private static BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

    private RemotingServer remotingServer;

    private TransactionMessageHandler transactionMessageHandler;

    private static final int MAX_LOG_SEND_THREAD = 1;
    private static final int MAX_LOG_TAKE_SIZE = 1024;
    private static final long KEEP_ALIVE_TIME = 0L;
    private static final String THREAD_PREFIX = "batchLoggerPrint";
    private static final long BUSY_SLEEP_MILLS = 5L;

    public TrxMessageProcessor(RemotingServer remotingServer, TransactionMessageHandler transactionMessageHandler) {
        this.remotingServer = remotingServer;
        this.transactionMessageHandler = transactionMessageHandler;
        init();
    }

    @Override
    public void process(ChannelHandlerContext ctx, RpcMessage rpcMessage) throws Exception {
        if (ChannelManager.isRegistered(ctx.channel())) {
            onTrxMessage(ctx, rpcMessage);
        } else {
            try {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("closeChannelHandlerContext channel:" + ctx.channel());
                }
                ctx.disconnect();
                ctx.close();
            } catch (Exception exx) {
                LOGGER.error(exx.getMessage());
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("close a unhandled connection! [%s]", ctx.channel().toString()));
            }
        }
    }

    private void onTrxMessage(ChannelHandlerContext ctx, RpcMessage rpcMessage) {
        Object message = rpcMessage.getBody();
        RpcContext rpcContext = ChannelManager.getContextFromIdentified(ctx.channel());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("server received:{},clientIp:{},vgroup:{}", message,
                NetUtil.toIpAddress(ctx.channel().remoteAddress()), rpcContext.getTransactionServiceGroup());
        } else {
            try {
                logQueue.put(message + ",clientIp:" + NetUtil.toIpAddress(ctx.channel().remoteAddress()) + ",vgroup:"
                    + rpcContext.getTransactionServiceGroup());
            } catch (InterruptedException e) {
                LOGGER.error("put message to logQueue error: {}", e.getMessage(), e);
            }
        }
        if (!(message instanceof AbstractMessage)) {
            return;
        }
        if (message instanceof MergedWarpMessage) {
            AbstractResultMessage[] results = new AbstractResultMessage[((MergedWarpMessage) message).msgs.size()];
            for (int i = 0; i < results.length; i++) {
                final AbstractMessage subMessage = ((MergedWarpMessage) message).msgs.get(i);
                results[i] = transactionMessageHandler.onRequest(subMessage, rpcContext);
            }
            MergeResultMessage resultMessage = new MergeResultMessage();
            resultMessage.setMsgs(results);
            remotingServer.sendResponse(rpcMessage, ctx.channel(), resultMessage);
        } else if (message instanceof AbstractResultMessage) {
            transactionMessageHandler.onResponse((AbstractResultMessage) message, rpcContext);
        } else {
            // the single send request message
            final AbstractMessage msg = (AbstractMessage) message;
            AbstractResultMessage result = transactionMessageHandler.onRequest(msg, rpcContext);
            remotingServer.sendResponse(rpcMessage, ctx.channel(), result);
        }
    }

    /**
     * Init.
     */
    private void init() {
        ExecutorService mergeSendExecutorService = new ThreadPoolExecutor(MAX_LOG_SEND_THREAD, MAX_LOG_SEND_THREAD,
            KEEP_ALIVE_TIME, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            new NamedThreadFactory(THREAD_PREFIX, MAX_LOG_SEND_THREAD, true));
        mergeSendExecutorService.submit(new BatchLogRunnable());
    }

    /**
     * The type Batch log runnable.
     */
    static class BatchLogRunnable implements Runnable {

        @Override
        public void run() {
            List<String> logList = new ArrayList<>();
            while (true) {
                try {
                    logList.add(logQueue.take());
                    logQueue.drainTo(logList, MAX_LOG_TAKE_SIZE);
                    if (LOGGER.isInfoEnabled()) {
                        for (String str : logList) {
                            LOGGER.info(str);
                        }
                    }
                    logList.clear();
                    TimeUnit.MILLISECONDS.sleep(BUSY_SLEEP_MILLS);
                } catch (InterruptedException exx) {
                    LOGGER.error("batch log busy sleep error:{}", exx.getMessage(), exx);
                }

            }
        }
    }

}
