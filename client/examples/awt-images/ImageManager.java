import cloud.lunarsky.objectstore.client.Capabilities;
import cloud.lunarsky.objectstore.client.ObjectStorageClient;
import cloud.lunarsky.objectstore.client.ObjectStorageClientBuilder;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Canvas;
import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** A small, dependency-free image browser for testing an S3-compatible endpoint. */
@SuppressWarnings("serial")
public final class ImageManager extends Frame {
    private static final long MAX_UPLOAD = 64L * 1024 * 1024;
    private static final int MAX_PREVIEW = 12 * 1024 * 1024;
    private static final Color BACKGROUND = new Color(29, 27, 38);
    private static final Color FOREGROUND = new Color(231, 223, 240);

    private final TextField endpoint = new TextField(env("S3_ENDPOINT", "http://localhost:9000"));
    private final TextField region = new TextField(env("S3_REGION", "us-east-1"));
    private final TextField bucket = new TextField(env("S3_BUCKET", "photos"));
    private final TextField prefix = new TextField("images/");
    private final TextField access = new TextField(env("S3_ACCESS_KEY", ""));
    private final TextField secret = new TextField(env("S3_SECRET_KEY", ""));
    private final Checkbox allowHttp = new Checkbox("Allow HTTP for trusted tests", null,
        Boolean.parseBoolean(env("S3_ALLOW_HTTP", "false")));
    private final java.awt.List images = new java.awt.List(15, false);
    private final ImageCanvas preview = new ImageCanvas();
    private final Label status = new Label("Enter a bucket and credentials, then connect. Drop images to upload.");
    private final Button connectButton = new Button("Connect");
    private final Button moreButton = new Button("Load more");
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "objectstore-image-example");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong previewRevision = new AtomicLong();
    private final List<ObjectStorageClient.ObjectEntry> entries = new ArrayList<>();
    private volatile ObjectStorageClient client;
    private volatile String activeBucket;
    private volatile String activePrefix;
    private String nextToken;

    private ImageManager() {
        super("ObjectStore image manager");
        setLayout(new BorderLayout(8, 8));
        setBackground(BACKGROUND);
        setForeground(FOREGROUND);
        setSize(920, 640);
        setMinimumSize(new Dimension(650, 440));
        setLocationRelativeTo(null);
        secret.setEchoChar('\u2022');

        Panel serviceFields = new Panel(new GridLayout(2, 4, 8, 3));
        serviceFields.add(label("Endpoint"));
        serviceFields.add(label("Region"));
        serviceFields.add(label("Bucket"));
        serviceFields.add(label("Key prefix"));
        serviceFields.add(endpoint);
        serviceFields.add(region);
        serviceFields.add(bucket);
        serviceFields.add(prefix);
        Panel credentialFields = new Panel(new GridLayout(2, 2, 8, 3));
        credentialFields.add(label("Access key"));
        credentialFields.add(label("Secret key"));
        credentialFields.add(access);
        credentialFields.add(secret);
        Panel connection = new Panel(new BorderLayout(0, 5));
        connection.add(serviceFields, BorderLayout.NORTH);
        connection.add(credentialFields, BorderLayout.CENTER);

        Panel actions = new Panel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        Button upload = new Button("Add images...");
        Button refresh = new Button("Refresh");
        Button download = new Button("Download");
        Button delete = new Button("Delete");
        actions.add(allowHttp);
        actions.add(connectButton);
        actions.add(upload);
        actions.add(refresh);
        actions.add(moreButton);
        actions.add(download);
        actions.add(delete);
        moreButton.setEnabled(false);

        Panel top = new Panel(new BorderLayout(0, 5));
        top.add(connection, BorderLayout.CENTER);
        top.add(actions, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        Panel listing = new Panel(new BorderLayout(0, 5));
        listing.setPreferredSize(new Dimension(285, 400));
        listing.add(label("Images in this prefix"), BorderLayout.NORTH);
        listing.add(images, BorderLayout.CENTER);
        add(listing, BorderLayout.WEST);
        add(preview, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        connectButton.addActionListener(event -> connect());
        upload.addActionListener(event -> chooseImages());
        refresh.addActionListener(event -> loadPage(true));
        moreButton.addActionListener(event -> loadPage(false));
        download.addActionListener(event -> downloadSelected());
        delete.addActionListener(event -> deleteSelected());
        images.addItemListener(event -> previewSelected());
        installDropTarget(this);
        installDropTarget(preview);
        installDropTarget(images);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                io.shutdownNow();
                ObjectStorageClient previous = client;
                if (previous != null) Thread.ofVirtual().start(previous::close);
                dispose();
            }
        });
        if (Boolean.parseBoolean(env("S3_AUTO_CONNECT", "false"))) {
            EventQueue.invokeLater(this::connect);
        }
    }

    private void connect() {
        String url = endpoint.getText().trim();
        String location = region.getText().trim();
        String name = bucket.getText().trim();
        String folder = normalizePrefix(prefix.getText());
        String user = access.getText().trim();
        String password = secret.getText();
        boolean insecure = allowHttp.getState();
        connectButton.setEnabled(false);
        setStatus("Connecting...", false);
        io.execute(() -> {
            ObjectStorageClient candidate = null;
            try {
                ObjectStorageClientBuilder builder = new ObjectStorageClientBuilder()
                    .endpoint(URI.create(url)).region(location).credentials(user, password)
                    .timeout(Duration.ofSeconds(30));
                if (insecure) builder.allowInsecureHttp();
                candidate = builder.build();
                candidate.listObjects(name, folder, null, 1);
                ObjectStorageClient previous = client;
                client = candidate;
                activeBucket = name;
                activePrefix = folder;
                candidate = null;
                if (previous != null) previous.close();
                String service = "S3-compatible service";
                try {
                    Capabilities found = client.getCapabilities();
                    if (found.service() == Capabilities.ServiceKind.OBJECTSTORE) {
                        service = "ObjectStore" + (found.serviceVersion() == null ? "" : " " + found.serviceVersion());
                    }
                } catch (IOException ignored) {
                    // Listing already established the connection; service detection is optional.
                }
                String connectedService = service;
                EventQueue.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    setStatus("Connected to " + connectedService + ".", false);
                    loadPage(true);
                });
            } catch (Exception error) {
                if (candidate != null) candidate.close();
                showFailure("Connection failed", error);
                EventQueue.invokeLater(() -> connectButton.setEnabled(true));
            }
        });
    }

    private void loadPage(boolean first) {
        if (client == null) {
            setStatus("Connect first.", true);
            return;
        }
        if (!first && nextToken == null) return;
        String token = first ? null : nextToken;
        moreButton.setEnabled(false);
        setStatus(first ? "Loading images..." : "Loading more images...", false);
        io.execute(() -> {
            try {
                ObjectStorageClient.ObjectPage page = client.listObjects(activeBucket, activePrefix, token, 100);
                EventQueue.invokeLater(() -> {
                    if (first) {
                        previewRevision.incrementAndGet();
                        entries.clear();
                        images.removeAll();
                        preview.show(null, "Select an image to preview");
                    }
                    for (ObjectStorageClient.ObjectEntry entry : page.objects()) {
                        if (!isImage(entry.key())) continue;
                        entries.add(entry);
                        String name = entry.key().substring(activePrefix.length());
                        images.add(name + "  ·  " + sizeLabel(entry.size()));
                    }
                    nextToken = page.nextContinuationToken();
                    moreButton.setEnabled(nextToken != null);
                    setStatus(entries.size() + " image(s) loaded" +
                        (nextToken == null ? "." : "; more available."), false);
                });
            } catch (Exception error) {
                showFailure("Could not list images", error);
                EventQueue.invokeLater(() -> moreButton.setEnabled(token != null));
            }
        });
    }

    private void chooseImages() {
        FileDialog picker = new FileDialog(this, "Add images", FileDialog.LOAD);
        picker.setMultipleMode(true);
        picker.setVisible(true);
        java.io.File[] selected = picker.getFiles();
        if (selected.length > 0) uploadImages(List.of(selected));
    }

    private void uploadImages(List<java.io.File> files) {
        if (client == null) {
            setStatus("Connect first.", true);
            return;
        }
        io.execute(() -> {
            int uploaded = 0;
            for (java.io.File file : files) {
                try {
                    Path path = file.toPath();
                    String name = path.getFileName().toString();
                    String type = contentType(name);
                    if (type == null) throw new IOException("Unsupported image type");
                    if (!Files.isRegularFile(path) || Files.size(path) > MAX_UPLOAD)
                        throw new IOException("Image must be a file of at most 64 MiB");
                    String key = activePrefix + UUID.randomUUID() + "-" + name;
                    client.putObject(activeBucket, key, path, type);
                    uploaded++;
                    int completed = uploaded;
                    EventQueue.invokeLater(() -> setStatus("Uploaded " + completed + " of " + files.size() + ".", false));
                } catch (Exception error) {
                    showFailure("Could not upload " + file.getName(), error);
                }
            }
            if (uploaded > 0) EventQueue.invokeLater(() -> loadPage(true));
        });
    }

    private void previewSelected() {
        ObjectStorageClient.ObjectEntry entry = selectedEntry();
        long revision = previewRevision.incrementAndGet();
        if (entry == null) {
            preview.show(null, "Select an image to preview");
            return;
        }
        preview.show(null, "Loading preview...");
        io.execute(() -> {
            try (ObjectStorageClient.ObjectData data = client.getObject(activeBucket, entry.key())) {
                if (data.length() > MAX_PREVIEW) throw new IOException("Preview exceeds 12 MiB");
                byte[] bytes = data.body().readNBytes(MAX_PREVIEW + 1);
                if (bytes.length > MAX_PREVIEW) throw new IOException("Preview exceeds 12 MiB");
                BufferedImage image = decodePreview(bytes);
                EventQueue.invokeLater(() -> {
                    if (previewRevision.get() == revision) preview.show(image, null);
                });
            } catch (Exception error) {
                EventQueue.invokeLater(() -> {
                    if (previewRevision.get() == revision) preview.show(null, "Preview unavailable");
                });
                showFailure("Could not preview image", error);
            }
        });
    }

    private void downloadSelected() {
        ObjectStorageClient.ObjectEntry entry = selectedEntry();
        if (entry == null) {
            setStatus("Select an image first.", true);
            return;
        }
        FileDialog picker = new FileDialog(this, "Save image", FileDialog.SAVE);
        picker.setFile(Path.of(entry.key()).getFileName().toString());
        picker.setVisible(true);
        if (picker.getFile() == null) return;
        Path destination = Path.of(picker.getDirectory(), picker.getFile());
        if (Files.exists(destination) && !confirm("Replace this file?", destination.toString())) return;
        io.execute(() -> {
            Path temporary = null;
            try {
                Path parent = destination.toAbsolutePath().getParent();
                temporary = Files.createTempFile(parent, ".objectstore-image-", ".part");
                try (ObjectStorageClient.ObjectData data = client.getObject(activeBucket, entry.key())) {
                    Files.copy(data.body(), temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                EventQueue.invokeLater(() -> setStatus("Saved " + destination.getFileName() + ".", false));
            } catch (Exception error) {
                showFailure("Download failed", error);
            } finally {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
                }
            }
        });
    }

    private void deleteSelected() {
        ObjectStorageClient.ObjectEntry entry = selectedEntry();
        if (entry == null) {
            setStatus("Select an image first.", true);
            return;
        }
        if (!confirm("Delete this image?", entry.key())) return;
        io.execute(() -> {
            try {
                client.deleteObject(activeBucket, entry.key());
                EventQueue.invokeLater(() -> {
                    previewRevision.incrementAndGet();
                    loadPage(true);
                });
            } catch (Exception error) { showFailure("Delete failed", error); }
        });
    }

    private ObjectStorageClient.ObjectEntry selectedEntry() {
        int index = images.getSelectedIndex();
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    private void installDropTarget(java.awt.Component target) {
        new DropTarget(target, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override public void drop(DropTargetDropEvent event) {
                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    event.rejectDrop();
                    return;
                }
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    Object value = event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    List<?> dropped = (List<?>) value;
                    List<java.io.File> files = new ArrayList<>();
                    for (Object item : dropped) {
                        if (item instanceof java.io.File file) files.add(file);
                    }
                    event.dropComplete(true);
                    EventQueue.invokeLater(() -> uploadImages(files));
                } catch (Exception error) {
                    event.dropComplete(false);
                    showFailure("Drop failed", error);
                }
            }
        }, true);
    }

    private boolean confirm(String title, String detail) {
        Dialog dialog = new Dialog(this, title, true);
        dialog.setLayout(new BorderLayout(12, 12));
        dialog.add(new Label(detail), BorderLayout.CENTER);
        Panel buttons = new Panel(new FlowLayout(FlowLayout.RIGHT));
        boolean[] accepted = { false };
        Button cancel = new Button("Cancel");
        Button proceed = new Button("Continue");
        cancel.addActionListener(event -> dialog.dispose());
        proceed.addActionListener(event -> {
            accepted[0] = true;
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(proceed);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(490, 120);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return accepted[0];
    }

    private void showFailure(String action, Exception error) {
        EventQueue.invokeLater(() -> setStatus(action + ": " + error.getMessage(), true));
    }

    private void setStatus(String message, boolean failed) {
        status.setForeground(failed ? new Color(245, 143, 157) : FOREGROUND);
        status.setText(message);
    }

    private static Label label(String text) {
        return new Label(text);
    }

    private static String normalizePrefix(String value) {
        String cleaned = value.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        return cleaned.isEmpty() || cleaned.endsWith("/") ? cleaned : cleaned + "/";
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return null;
    }

    private static boolean isImage(String name) {
        return contentType(name) != null;
    }

    private static String sizeLabel(long bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    private static BufferedImage decodePreview(byte[] bytes) throws IOException {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported image data");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 16000 || height > 16000 ||
                    (long) width * height > 40_000_000)
                    throw new IOException("Image dimensions are too large for preview");
                int step = Math.max(1, (Math.max(width, height) + 1199) / 1200);
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, parameters);
            } finally { reader.dispose(); }
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @SuppressWarnings("serial")
    private static final class ImageCanvas extends Canvas {
        private BufferedImage image;
        private String message = "Select an image to preview";

        private ImageCanvas() {
            setBackground(new Color(21, 20, 29));
            setForeground(FOREGROUND);
            setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        }

        private void show(BufferedImage value, String text) {
            image = value;
            message = text;
            repaint();
        }

        @Override public void paint(Graphics graphics) {
            int width = getWidth();
            int height = getHeight();
            if (image == null) {
                graphics.setColor(FOREGROUND);
                graphics.drawString(message, 20, Math.max(35, height / 2));
                return;
            }
            double scale = Math.min((width - 24.0) / image.getWidth(),
                (height - 24.0) / image.getHeight());
            scale = Math.max(0.01, Math.min(scale, 1.0));
            int drawWidth = (int) Math.round(image.getWidth() * scale);
            int drawHeight = (int) Math.round(image.getHeight() * scale);
            graphics.drawImage(image, (width - drawWidth) / 2, (height - drawHeight) / 2,
                drawWidth, drawHeight, null);
        }
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(() -> new ImageManager().setVisible(true));
    }
}
