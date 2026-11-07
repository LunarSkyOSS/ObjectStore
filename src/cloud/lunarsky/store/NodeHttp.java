package cloud.lunarsky.store;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class NodeHttp {
    private NodeHttp() {}

    static HttpResponse<byte[]> bytes(HttpClient client, HttpRequest request, int maximum) throws IOException {
        if (maximum < 0) throw new IllegalArgumentException("Invalid response size limit");
        return send(client, request, HttpResponse.BodySubscribers.ofByteArray(), maximum);
    }

    static HttpResponse<Void> discard(HttpClient client, HttpRequest request) throws IOException {
        return send(client, request, HttpResponse.BodySubscribers.discarding(), Long.MAX_VALUE);
    }

    private static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                           HttpResponse.BodySubscriber<T> delegate, long maximum) throws IOException {
        long timeout = request.timeout().orElseThrow().toNanos();
        long started = System.nanoTime();
        ResponseBody<T> body = new ResponseBody<>(delegate, maximum);
        CompletableFuture<HttpResponse<T>> response = client.sendAsync(request, ignored -> body);
        boolean completed = false;
        try {
            HttpResponse<T> result = response.get(Math.max(1, timeout - (System.nanoTime() - started)),
                TimeUnit.NANOSECONDS);
            completed = true;
            return result;
        } catch (TimeoutException error) {
            throw new HttpTimeoutException("Storage node response timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during storage node request", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException failure) throw failure;
            throw new IOException("Storage node request failed", error.getCause());
        } finally {
            if (!completed) {
                body.abort(new IOException("Storage node request cancelled"));
                response.cancel(true);
            }
        }
    }

    private static final class ResponseBody<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private Flow.Subscription subscription;
        private long remaining;
        private boolean finished;

        ResponseBody(HttpResponse.BodySubscriber<T> delegate, long maximum) {
            this.delegate = delegate;
            remaining = maximum;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (finished || subscription != null) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            delegate.onSubscribe(incoming);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > remaining) {
                    abort(new IOException("Storage node response is too large"));
                    return;
                }
                remaining -= buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (finished) return;
            finished = true;
            delegate.onError(error);
        }

        @Override
        public synchronized void onComplete() {
            if (finished) return;
            finished = true;
            delegate.onComplete();
        }

        synchronized void abort(IOException error) {
            if (finished) return;
            finished = true;
            if (subscription != null) subscription.cancel();
            delegate.onError(error);
        }
    }
}
