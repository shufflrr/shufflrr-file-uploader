package com.shufflrr.uploader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shufflrr.sdk.Credentials;
import com.shufflrr.sdk.InTypes;
import com.shufflrr.sdk.Lens;
import com.shufflrr.sdk.OutTypes;
import com.shufflrr.sdk.Requests;
import com.shufflrr.sdk.Shufflrr;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.paint.Paint;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class Controller implements Initializable {
    private static final int ROOT_FOLDER_ID = 0;
    private Stage stage;

    @FXML
    TextField site;
    @FXML
    TextField email;
    @FXML
    PasswordField password;
    @FXML
    Button chooser;
    @FXML
    TextField path;
    @FXML
    TextField target;
    @FXML
    Label status;
    @FXML
    Button submit;

    void setStage(Stage stage) {
        this.stage = stage;
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        this.target.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*")) {
                this.target.setText(oldValue);
            }
        });

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Open Resource Folder");
        this.chooser.setOnAction(e -> {
            File file = dirChooser.showDialog(this.stage);
            this.path.setText(file.getAbsolutePath());
        });
    }

    @FXML
    private void upload(ActionEvent event) {
        this.submit.setDisable(true);
        setElementText(this.status, "Attempting upload...", "#000000");

        Path path = Path.of(this.path.getText());

        if (!path.toFile().isDirectory()) {
            this.submit.setDisable(false);
            setElementText(this.status, "Path must point to a directory.", "#ee0000");
            return;
        }

        Credentials creds = Credentials.builder()
                .withSite(this.site.getText())
                .withUsername(this.email.getText())
                .withPassword(this.password.getText())
                .build();

        Shufflrr conn = Shufflrr.connect(creds);

        conn.sendAsync(Requests.USER, InTypes.NULL, OutTypes.NODE).thenApply(HttpResponse::body).thenAcceptAsync(opt -> {
            opt.ifPresentOrElse(node -> {
                var xportal = Lens.INTEGER.compose(Lens.TRAVERSE.apply("portalId"));
                int portalId = xportal.apply(node);
                getOrCreateFolder(conn, portalId, path.toFile().getName(), Integer.parseInt(this.target.getText())).thenAcceptAsync(id -> {
                    recurseDirectories(conn, portalId, path, id);
                });
                Platform.runLater(() -> {
                    this.submit.setDisable(false);
                    setElementText(this.status, "Processing complete.", "#228b22");
                });
            }, () -> {
                Platform.runLater(() -> {
                    this.submit.setDisable(false);
                    setElementText(this.status, "Authentication error.", "#ee0000");
                });
            });
        });
    }

    private void recurseDirectories(Shufflrr conn, int portalId, Path path, int parent) {
        List<CompletableFuture<?>> cfs = new ArrayList<>();

        for (File file : Objects.requireNonNull(path.toFile().listFiles())) {
            if (file.isDirectory()) {
                cfs.add(getOrCreateFolder(conn, portalId, file.getName(), parent)
                        .thenAcceptAsync(id -> recurseDirectories(conn, portalId, file.toPath(), id)));
            } else {
                cfs.add(sendFile(conn, file.toPath(), boundary(), parent));
            }
        }

        CompletableFuture.allOf(cfs.toArray(CompletableFuture[]::new)).join();
    }

    private CompletableFuture<JsonNode> sendFile(Shufflrr conn, Path path, String boundary, int parent) {
        return conn.sendAsync(
                Requests.FOLDER_UPLOAD.withHeaders("Content-Type", "multipart/form-data; boundary=" + boundary),
                InTypes.MULTIPART.with(builder -> {
                    try {
                        return builder.boundary(boundary).filePart("files[]", path);
                    } catch (FileNotFoundException e) {
                        throw new IllegalArgumentException("File not found.", e);
                    }
                }),
                OutTypes.GZIP_NODE, String.valueOf(parent)
        ).thenApplyAsync(res -> res.body().orElseThrow(RuntimeException::new));
    }

    private CompletableFuture<Integer> getOrCreateFolder(Shufflrr conn, int portalId, String name, int parent) {
        var xcontents = Lens.ARRAY.compose(Lens.TRAVERSE.apply("contents"));
        CompletableFuture<Optional<Iterator<JsonNode>>> c = parent == 0
                ? conn.sendAsync(Requests.ALL_FOLDERS, InTypes.NULL, OutTypes.NODE).thenApplyAsync(res -> res.body().map(xcontents).map(ArrayNode::elements))
                : conn.sendAsync(Requests.FOLDER_CONTENTS, InTypes.NULL, OutTypes.NODE, String.valueOf(parent)).thenApplyAsync(res -> res.body().map(Lens.ARRAY).map(ArrayNode::elements));

        return c.thenComposeAsync(opt -> {
            if (opt.isEmpty()) {
                return createFolder(conn, portalId, name, parent);
            }

            var xname = Lens.STRING.compose(Lens.TRAVERSE.apply("name"));
            var xid = Lens.INTEGER.compose(Lens.TRAVERSE.apply("id"));
            Iterable<JsonNode> it = opt::get;
            List<Integer> list = StreamSupport.stream(it.spliterator(), false)
                    .filter(n -> xname.apply(n).equalsIgnoreCase(name))
                    .map(xid)
                    .collect(Collectors.toList());

            if (list.size() == 1) {
                return CompletableFuture.completedFuture(list.get(0));
            } else {
                return createFolder(conn, portalId, name, parent);
            }
        });
    }

    private CompletableFuture<Integer> createFolder(Shufflrr conn, int portalId, String name, int parent) {
        String json = createFolderJson(portalId, name, parent);
        var xid = Lens.INTEGER.compose(Lens.TRAVERSE.apply("id"));
        return conn.sendAsync(Requests.CREATE_FOLDER, InTypes.STRING.with(json), OutTypes.GZIP_NODE)
                .thenApplyAsync(res -> res.body().map(xid).orElse(ROOT_FOLDER_ID));
    }

    private static String createFolderJson(int portalId, String name, int parent) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("portalId", portalId);
        node.put("name", name);
        if (parent != 0) node.put("parentFolderId", parent);
        return node.toString();
    }

    private static String boundary() {
        return UUID.randomUUID().toString();
    }

    private static void setElementText(Labeled labeled, String text, String color) {
        labeled.setText(text);
        labeled.setTextFill(Paint.valueOf(color));
    }
}
