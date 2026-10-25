package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class TrafficMeter {
    private long inbound;
    private long outbound;

    private TrafficMeter() { }

    static TrafficMeter attach(HttpExchange exchange) {
        TrafficMeter meter = new TrafficMeter();
        InputStream input = exchange.getRequestBody();
        OutputStream output = exchange.getResponseBody();
        exchange.setStreams(meter.input(input), meter.output(output));
        return meter;
    }

    long inbound() { return inbound; }
    long outbound() { return outbound; }

    private InputStream input(InputStream source) {
        return new InputStream() {
            @Override public int read() throws IOException {
                int value = source.read();
                if (value >= 0) inbound++;
                return value;
            }

            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = source.read(buffer, offset, length);
                if (count > 0) inbound += count;
                return count;
            }

            @Override public long skip(long count) throws IOException {
                long skipped = source.skip(count);
                inbound += skipped;
                return skipped;
            }

            @Override public int available() throws IOException { return source.available(); }
            @Override public void close() throws IOException { source.close(); }
        };
    }

    private OutputStream output(OutputStream destination) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                destination.write(value);
                outbound++;
            }

            @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                destination.write(buffer, offset, length);
                outbound += length;
            }

            @Override public void flush() throws IOException { destination.flush(); }
            @Override public void close() throws IOException { destination.close(); }
        };
    }
}
