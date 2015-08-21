package com.xjeffrose.xio.server;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.xjeffrose.xio.core.ConnectionContext;
import com.xjeffrose.xio.core.ConnectionContexts;
import com.xjeffrose.xio.processor.XioProcessorFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;


public class XioDispatcher extends SimpleChannelUpstreamHandler {
  private final XioProcessorFactory processorFactory;
  private final Executor exe;
//  private final long taskTimeoutMillis;
//  private final Timer taskTimeoutTimer;
  private final int queuedResponseLimit;
  private final Map<Integer, HttpMessage> responseMap = new HashMap<>();
  private final AtomicInteger dispatcherSequenceId = new AtomicInteger(0);
  private final AtomicInteger lastResponseWrittenId = new AtomicInteger(0);

  public XioDispatcher(XioServerDef def, Timer timer) {
    this.processorFactory = def.getProcessorFactory();
    this.queuedResponseLimit = def.getQueuedResponseLimit();
    this.exe = def.getExecutor();
//    this.taskTimeoutMillis = (def.getTaskTimeout() == null ? 0 : def.getTaskTimeout().toMillis());
//    this.taskTimeoutTimer = (def.getTaskTimeout() == null ? null : timer);
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
      throws Exception {
    if (e.getMessage() instanceof HttpMessage) {
      HttpMessage message = (HttpMessage) e.getMessage();

      processRequest(ctx, message);
    } else {
      ctx.sendUpstream(e);
    }
  }

  private void processRequest(
      final ChannelHandlerContext ctx,
      final HttpMessage message) {

    final int requestSequenceId = dispatcherSequenceId.incrementAndGet();

    synchronized (responseMap) {
      // Limit the number of pending responses (responses which finished out of order, and are
      // waiting for previous requests to be finished so they can be written in order), by
      // blocking further channel reads. Due to the way Netty frame decoders work, this is more
      // of an estimate than a hard limit. Netty may continue to decode and process several
      // more requests that were in the latest read, even while further reads on the channel
      // have been blocked.
      if (requestSequenceId > lastResponseWrittenId.get() + queuedResponseLimit &&
          !DispatcherContext.isChannelReadBlocked(ctx)) {
        DispatcherContext.blockChannelReads(ctx);
      }
    }

    try {
      exe.execute(new Runnable() {
        @Override
        public void run() {
          ListenableFuture<Boolean> processFuture;
          final AtomicBoolean responseSent = new AtomicBoolean(false);
          // Use AtomicReference as a generic holder class to be able to mark it final
          // and pass into inner classes. Since we only use .get() and .set(), we don't
          // actually do any atomic operations.
          final AtomicReference<Timeout> expireTimeout = new AtomicReference<>(null);

          // TODO: Impliment a timeout [Not ready to tackle this yet
          try {
            try {
//              long timeRemaining = 0;
//              if (taskTimeoutMillis > 0) {
//                long timeElapsed = System.currentTimeMillis() - message.getProcessStartTimeMillis();
//                if (timeElapsed >= taskTimeoutMillis) {
//                  TApplicationException taskTimeoutException = new TApplicationException(
//                      TApplicationException.INTERNAL_ERROR,
//                      "Task stayed on the queue for " + timeElapsed +
//                          " milliseconds, exceeding configured task timeout of " + taskTimeoutMillis +
//                          " milliseconds."
//                  );
//                  sendTApplicationException(taskTimeoutException, ctx, message, requestSequenceId, messageTransport,
//                      inProtocol, outProtocol);
//                  return;
//                } else {
//                  timeRemaining = taskTimeoutMillis - timeElapsed;
//                }
//              }
//
//              if (timeRemaining > 0) {
//                expireTimeout.set(taskTimeoutTimer.newTimeout(new TimerTask() {
//                  @Override
//                  public void run(Timeout timeout) throws Exception {
//                    // The immediateFuture returned by processors isn't cancellable, cancel() and
//                    // isCanceled() always return false. Use a flag to detect task expiration.
//                    if (responseSent.compareAndSet(false, true)) {
//                      TApplicationException ex = new TApplicationException(
//                          TApplicationException.INTERNAL_ERROR,
//                          "Task timed out while executing."
//                      );
//                      // Create a temporary transport to send the exception
//                      ChannelBuffer duplicateBuffer = message.getBuffer().duplicate();
//                      duplicateBuffer.resetReaderIndex();
//                      TXioTransport temporaryTransport = new TXioTransport(
//                          ctx.getChannel(),
//                          duplicateBuffer,
//                          message.getTransportType());
//                      TProtocolPair protocolPair = duplexProtocolFactory.getProtocolPair(
//                          TTransportPair.fromSingleTransport(temporaryTransport));
//                      sendTApplicationException(ex, ctx, message,
//                          requestSequenceId,
//                          temporaryTransport,
//                          protocolPair.getInputProtocol(),
//                          protocolPair.getOutputProtocol());
//                    }
//                  }
//                }, timeRemaining, TimeUnit.MILLISECONDS));
//              }

              ConnectionContext connectionContext = ConnectionContexts.getContext(ctx.getChannel());
              RequestContext requestContext = new XioRequestContext(connectionContext);
              RequestContexts.setCurrentContext(requestContext);

              processFuture = processorFactory.getProcessor().process(ctx, (HttpRequest) message, requestContext, responseMap);
            } finally {
              // RequestContext does NOT stay set while we are waiting for the process
              // future to complete. This is by design because we'll might move on to the
              // next request using this thread before this one is completed. If you need
              // the context throughout an asynchronous handler, you need to read and store
              // it before returning a future.
              RequestContexts.clearCurrentContext();
            }

            Futures.addCallback(
                processFuture,
                new FutureCallback<Boolean>() {
                  @Override
                  public void onSuccess(Boolean result) {
                    deleteExpirationTimer(expireTimeout.get());
                    try {
                      // Only write response if the client is still there and the task timeout
                      // hasn't expired.
                      if (ctx.getChannel().isConnected() && responseSent.compareAndSet(false, true)) {
                        //TODO: This is where the real magic happens
                        HttpMessage response = responseMap.get(1);
                        writeResponse(ctx, response, requestSequenceId);
                      }
                    } catch (Throwable t) {
                      onDispatchException(ctx, t);
                    }
                  }

                  @Override
                  public void onFailure(Throwable t) {
                    deleteExpirationTimer(expireTimeout.get());
                    onDispatchException(ctx, t);
                  }
                }
            );
          } catch (Exception e) {
            onDispatchException(ctx, e);
          }
        }
      });
    } catch (RejectedExecutionException ex) {
      //TODO: Make default bad response
//      TApplicationException x = new TApplicationException(TApplicationException.INTERNAL_ERROR,
//          "Server overloaded");
//      sendTApplicationException(x, ctx, message, requestSequenceId, messageTransport, inProtocol, outProtocol);
    }
  }

  private void deleteExpirationTimer(Timeout timeout) {
    if (timeout == null) {
      return;
    }
    timeout.cancel();
  }

  private void onDispatchException(ChannelHandlerContext ctx, Throwable t) {
    Channels.fireExceptionCaught(ctx, t);
    closeChannel(ctx);
  }

  private void writeResponse(ChannelHandlerContext ctx,
                             HttpMessage response,
                             int responseSequenceId) {

    Channels.write(ctx.getChannel(), response);
    lastResponseWrittenId.incrementAndGet();
  }

  private void writeResponseInOrder(ChannelHandlerContext ctx,
                                    HttpMessage response,
                                    int responseSequenceId) {
    // Ensure responses to requests are written in the same order the requests
    // were received.
    synchronized (responseMap) {
      int currentResponseId = lastResponseWrittenId.get() + 1;
      if (responseSequenceId != currentResponseId) {
        // This response is NOT next in line of ordered responses, save it to
        // be sent later, after responses to all earlier requests have been
        // sent.
        responseMap.put(responseSequenceId, response);
      } else {
        // This response was next in line, write this response now, and see if
        // there are others next in line that should be sent now as well.
        do {
          Channels.write(ctx.getChannel(), response);
          lastResponseWrittenId.incrementAndGet();
          ++currentResponseId;
          response = responseMap.remove(currentResponseId);
        } while (null != response);

        // Now that we've written some responses, check if reads should be unblocked
        if (DispatcherContext.isChannelReadBlocked(ctx)) {
          int lastRequestSequenceId = dispatcherSequenceId.get();
          if (lastRequestSequenceId <= lastResponseWrittenId.get() + queuedResponseLimit) {
            DispatcherContext.unblockChannelReads(ctx);
          }
        }
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
      throws Exception {
    // Any out of band exception are caught here and we tear down the socket
    closeChannel(ctx);

    // Send for logging
    ctx.sendUpstream(e);
  }

  private void closeChannel(ChannelHandlerContext ctx) {
    if (ctx.getChannel().isOpen()) {
      ctx.getChannel().close();
    }
  }

  @Override
  public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    // Reads always start out unblocked
    DispatcherContext.unblockChannelReads(ctx);
    super.channelOpen(ctx, e);
  }

  private static class DispatcherContext {
    private ReadBlockedState readBlockedState = ReadBlockedState.NOT_BLOCKED;

    public static boolean isChannelReadBlocked(ChannelHandlerContext ctx) {
      return getDispatcherContext(ctx).readBlockedState == ReadBlockedState.BLOCKED;
    }

    public static void blockChannelReads(ChannelHandlerContext ctx) {
      // Remember that reads are blocked (there is no Channel.getReadable())
      getDispatcherContext(ctx).readBlockedState = ReadBlockedState.BLOCKED;

      // NOTE: this shuts down reads, but isn't a 100% guarantee we won't get any more messages.
      // It sets up the channel so that the polling loop will not report any new read events
      // and netty won't read any more data from the socket, but any messages already fully read
      // from the socket before this ran may still be decoded and arrive at this handler. Thus
      // the limit on queued messages before we block reads is more of a guidance than a hard
      // limit.
      ctx.getChannel().setReadable(false);
    }

    public static void unblockChannelReads(ChannelHandlerContext ctx) {
      // Remember that reads are unblocked (there is no Channel.getReadable())
      getDispatcherContext(ctx).readBlockedState = ReadBlockedState.NOT_BLOCKED;
      ctx.getChannel().setReadable(true);
    }

    private static DispatcherContext getDispatcherContext(ChannelHandlerContext ctx) {
      DispatcherContext dispatcherContext;
      Object attachment = ctx.getAttachment();

      if (attachment == null) {
        // No context was added yet, add one
        dispatcherContext = new DispatcherContext();
        ctx.setAttachment(dispatcherContext);
      } else if (!(attachment instanceof DispatcherContext)) {
        // There was a context, but it was the wrong type. This should never happen.
        throw new IllegalStateException("XioDispatcher handler context should be of type XioDispatcher.DispatcherContext");
      } else {
        dispatcherContext = (DispatcherContext) attachment;
      }

      return dispatcherContext;
    }

    private enum ReadBlockedState {
      NOT_BLOCKED,
      BLOCKED,
    }
  }
}
