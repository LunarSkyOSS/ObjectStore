package cloud.lunarsky.store;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class StoreOperations {
    @FunctionalInterface
    interface Action {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface Query<T> {
        T get() throws IOException;
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    void read(Action action) throws IOException {
        run(lock.readLock(), action);
    }

    void write(Action action) throws IOException {
        Lock writing = lock.writeLock();
        try {
            if (!writing.tryLock(1, TimeUnit.SECONDS))
                throw new StoreException(409, "OperationAborted",
                    "Storage is busy; retry after transfers finish");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new StoreException(503, "SlowDown", "Storage operation was interrupted");
        }
        try {
            action.run();
        } finally {
            writing.unlock();
        }
    }

    <T> T readValue(Query<T> query) throws IOException {
        Lock reading = lock.readLock();
        reading.lock();
        try {
            return query.get();
        } finally {
            reading.unlock();
        }
    }

    private static void run(Lock lock, Action action) throws IOException {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
